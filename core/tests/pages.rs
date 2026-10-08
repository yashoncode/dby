mod common;

use common::{column, open, targets};
use dby_core::{Cell, Cursor, DbyError, PageRequest, Session};

async fn all_rows(session: &Session, table: &str, limit: u32) -> Vec<Vec<Cell>> {
    let mut cursor: Option<Cursor> = None;
    let mut rows = Vec::new();
    for _ in 0..10_000 {
        let page = session
            .table_page(PageRequest { filters: vec![], sort: None, table: table.into(), cursor: cursor.clone(), limit })
            .await
            .expect("page");
        assert!(page.rows.len() <= limit as usize);
        rows.extend(page.rows);
        match page.next {
            Some(next) => cursor = Some(next),
            None => return rows,
        }
    }
    panic!("paging through {table} did not finish");
}

fn text(cell: &Cell) -> String {
    match cell {
        Cell::Text { v, .. } => v.clone(),
        other => panic!("expected text, got {other:?}"),
    }
}

#[tokio::test]
async fn pages_through_every_order_once() {
    for (name, port) in targets() {
        let session = open(port).await;
        let ids: Vec<u64> = all_rows(&session, "orders", 1000)
            .await
            .iter()
            .map(|r| match r[0] {
                Cell::Unsigned { v } => v,
                ref other => panic!("{name}: id was {other:?}"),
            })
            .collect();
        assert_eq!(ids, (1..=100_000).collect::<Vec<u64>>(), "{name}");
    }
}

#[tokio::test]
async fn long_text_is_trimmed_with_its_full_length() {
    for (name, port) in targets() {
        let session = open(port).await;
        let page = session
            .table_page(PageRequest { filters: vec![], sort: None, table: "orders".into(), cursor: None, limit: 50 })
            .await
            .unwrap();
        let names: Vec<&str> = page.columns.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, ["id", "customer", "status", "total", "notes", "created_at"], "{name}");
        assert_eq!(page.rows.len(), 50, "{name}");
        assert!(page.next.is_some(), "{name}");
        assert_eq!(page.rows[0][3], Cell::Exact { v: "0.01".into() }, "{name}");
        assert_eq!(page.rows[0][4], Cell::Null, "{name}");
        assert_eq!(page.rows[0][5], Cell::Temporal { v: "2026-01-01 00:01:00".into() }, "{name}");
        // id 10 is the first row with a 5000-character note.
        assert_eq!(page.rows[9][4], Cell::Text { v: "x".repeat(256), full_len: 5000 }, "{name}");
    }
}

#[tokio::test]
async fn composite_key_pages_cover_all_pairs() {
    for (name, port) in targets() {
        let session = open(port).await;
        let keys: Vec<(i64, i64)> = all_rows(&session, "pairs", 7)
            .await
            .iter()
            .map(|r| match (&r[0], &r[1]) {
                (Cell::Signed { v: a }, Cell::Signed { v: b }) => (*a, *b),
                other => panic!("{name}: key was {other:?}"),
            })
            .collect();
        let expected: Vec<(i64, i64)> = (0..10).flat_map(|a| (0..10).map(move |b| (a, b))).collect();
        assert_eq!(keys, expected, "{name}");
    }
}

#[tokio::test]
async fn text_key_paging_follows_column_collation() {
    for (name, port) in targets() {
        let session = open(port).await;
        let expected = column(port, "SELECT name FROM tags ORDER BY name").await;
        assert_eq!(expected.len(), 10, "{name}: seed did not load every tag");
        let got: Vec<String> = all_rows(&session, "tags", 3).await.iter().map(|r| text(&r[0])).collect();
        assert_eq!(got, expected, "{name}");
    }
}

#[tokio::test]
async fn table_without_key_pages_by_offset() {
    for (name, port) in targets() {
        let session = open(port).await;
        let mut values: Vec<i64> = all_rows(&session, "nopk", 30)
            .await
            .iter()
            .map(|r| match r[0] {
                Cell::Signed { v } => v,
                ref other => panic!("{name}: {other:?}"),
            })
            .collect();
        values.sort_unstable();
        assert_eq!(values, (0..100).collect::<Vec<i64>>(), "{name}");
    }
}

#[tokio::test]
async fn exact_values_survive() {
    for (name, port) in targets() {
        let session = open(port).await;
        let page = session
            .table_page(PageRequest { filters: vec![], sort: None, table: "exact_values".into(), cursor: None, limit: 10 })
            .await
            .unwrap();
        let row = &page.rows[0];
        assert_eq!(row[1], Cell::Unsigned { v: u64::MAX }, "{name}");
        assert_eq!(row[2], Cell::Signed { v: i64::MIN }, "{name}");
        assert_eq!(
            row[3],
            Cell::Exact { v: "12345678901234567890123456789012345.123456789012345678901234567890".into() },
            "{name}"
        );
        assert_eq!(row[4], Cell::Bytes { len: 2, preview: vec![0x00, 0xff] }, "{name}");
        match &row[5] {
            Cell::Bytes { len, preview } => {
                assert_eq!(*len, 1000, "{name}");
                assert_eq!(preview.len(), 256, "{name}");
            }
            other => panic!("{name}: blob was {other:?}"),
        }
        assert_eq!(row[6], Cell::Temporal { v: "0000-00-00 00:00:00".into() }, "{name}");
    }
}

#[tokio::test]
async fn unknown_table_is_not_found() {
    for (name, port) in targets() {
        let session = open(port).await;
        let err = session
            .table_page(PageRequest { filters: vec![], sort: None, table: "no_such_table".into(), cursor: None, limit: 10 })
            .await
            .expect_err("should fail");
        assert!(matches!(err, DbyError::NotFound { .. }), "{name}: {err:?}");
    }
}
