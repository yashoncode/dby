mod common;

use common::{column, open, raw, targets};
use dby_core::{Cell, ChangeKind, DbyError, FieldValue, RowChange};
use mysql_async::prelude::Queryable;

async fn clear(port: u16, ids: &str) {
    let mut conn = raw(port).await;
    conn.query_drop(format!("DELETE FROM edits WHERE id IN ({ids})")).await.unwrap();
    conn.disconnect().await.unwrap();
}

fn set(column: &str, value: Option<&str>) -> FieldValue {
    FieldValue { column: column.into(), value: value.map(Into::into) }
}

fn change(kind: ChangeKind, key: Vec<Cell>, values: Vec<FieldValue>) -> RowChange {
    RowChange { table: "edits".into(), kind, key, values }
}

#[tokio::test]
async fn writes_run_only_when_confirmed() {
    for (name, port) in targets() {
        clear(port, "1").await;
        let session = open(port).await;
        let sql = "INSERT INTO edits VALUES (1, 'one', NULL)".to_string();
        let blocked = session.run_sql("w".into(), sql.clone(), false).await;
        assert!(matches!(blocked, Err(DbyError::ReadOnlyBlocked { .. })), "{name}: {blocked:?}");
        assert_eq!(column(port, "SELECT COUNT(*) FROM edits WHERE id = 1").await, ["0"], "{name}");
        let ok = session.run_sql("w2".into(), sql, true).await.unwrap();
        assert_eq!(ok.affected_rows, 1, "{name}");
    }
}

#[tokio::test]
async fn read_only_session_refuses_writes_until_unlocked() {
    for (name, port) in targets() {
        clear(port, "2").await;
        let session = open(port).await;
        session.set_read_only(true).await.unwrap();
        assert!(session.is_read_only(), "{name}");
        let flag = session.run_sql("f".into(), "SELECT @@transaction_read_only".into(), false).await.unwrap();
        assert_eq!(flag.rows[0][0], Cell::Signed { v: 1 }, "{name}: the server session is not read-only");
        let insert = "INSERT INTO edits VALUES (2, 'two', NULL)".to_string();
        let blocked = session.run_sql("w".into(), insert.clone(), true).await;
        assert!(matches!(blocked, Err(DbyError::ReadOnlyBlocked { .. })), "{name}: {blocked:?}");
        let blocked = session.apply_row_change(change(ChangeKind::Insert, vec![], vec![set("id", Some("2")), set("name", Some("two"))])).await;
        assert!(matches!(blocked, Err(DbyError::ReadOnlyBlocked { .. })), "{name}: {blocked:?}");
        session.set_read_only(false).await.unwrap();
        let flag = session.run_sql("f2".into(), "SELECT @@transaction_read_only".into(), false).await.unwrap();
        assert_eq!(flag.rows[0][0], Cell::Signed { v: 0 }, "{name}");
        assert_eq!(session.run_sql("w2".into(), insert, true).await.unwrap().affected_rows, 1, "{name}");
    }
}

#[tokio::test]
async fn row_changes_insert_update_and_delete_exactly_one_row() {
    for (name, port) in targets() {
        clear(port, "100").await;
        let session = open(port).await;
        let insert = change(ChangeKind::Insert, vec![], vec![set("id", Some("100")), set("name", Some("O'Brien")), set("qty", None)]);
        assert_eq!(session.apply_row_change(insert).await.unwrap_or_else(|e| panic!("{name}: {e:?}")), 1);
        let update = change(ChangeKind::Update, vec![Cell::Signed { v: 100 }], vec![set("qty", Some("5"))]);
        assert_eq!(session.preview_row_change(update.clone()).unwrap(), "UPDATE edits\nSET qty = 5\nWHERE id = 100;", "{name}");
        assert_eq!(session.apply_row_change(update.clone()).await.unwrap(), 1, "{name}");
        // Writing the same value again still matches the row.
        assert_eq!(session.apply_row_change(update).await.unwrap(), 1, "{name}");
        assert_eq!(column(port, "SELECT CONCAT(name, '/', qty) FROM edits WHERE id = 100").await, ["O'Brien/5"], "{name}");
        let delete = change(ChangeKind::Delete, vec![Cell::Signed { v: 100 }], vec![]);
        assert_eq!(session.apply_row_change(delete).await.unwrap(), 1, "{name}");
        assert_eq!(column(port, "SELECT COUNT(*) FROM edits WHERE id = 100").await, ["0"], "{name}");
    }
}

#[tokio::test]
async fn row_change_matching_no_row_is_rolled_back() {
    for (name, port) in targets() {
        let session = open(port).await;
        let ghost = change(ChangeKind::Update, vec![Cell::Signed { v: 999_999 }], vec![set("qty", Some("1"))]);
        let r = session.apply_row_change(ghost).await;
        assert!(matches!(r, Err(DbyError::RowEditMismatch { affected: 0 })), "{name}: {r:?}");
    }
}

#[tokio::test]
async fn row_changes_on_a_table_without_a_key_are_blocked() {
    for (name, port) in targets() {
        let session = open(port).await;
        let r = session
            .apply_row_change(RowChange { table: "nopk".into(), kind: ChangeKind::Update, key: vec![Cell::Signed { v: 1 }], values: vec![set("v", Some("2"))] })
            .await;
        assert!(matches!(r, Err(DbyError::ReadOnlyBlocked { .. })), "{name}: {r:?}");
    }
}
