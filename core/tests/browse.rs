mod common;

use common::{column, open, page, targets};
use dby_core::{Cell, CellRequest, CountRequest, FacetRequest, Filter, FilterOp, SelectSpec, Sort};

fn filter(column: &str, op: FilterOp, value: &str) -> Filter {
    Filter { column: column.into(), op, value: value.into() }
}

#[tokio::test]
async fn refresh_schema_reads_tables_views_columns_and_routines() {
    for (name, port) in targets() {
        let session = open(port).await;
        let schema = session.refresh_schema().await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert_eq!(schema.database, "shop", "{name}");
        let orders = schema.tables.iter().find(|t| t.name == "orders").expect("orders");
        assert!(!orders.is_view, "{name}");
        let names: Vec<&str> = orders.columns.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, ["id", "customer", "status", "total", "notes", "created_at"], "{name}");
        assert_eq!(orders.columns[0].pk_seq, Some(1), "{name}");
        assert!(orders.columns[4].nullable && !orders.columns[1].nullable, "{name}");
        assert!(schema.tables.iter().any(|t| t.name == "big_orders" && t.is_view), "{name}");
        let items = schema.tables.iter().find(|t| t.name == "items").expect("items");
        assert_eq!(items.columns[1].enum_values, ["new", "used", "broken"], "{name}");
        assert!(schema.routines >= 1, "{name}");
    }
}

#[tokio::test]
async fn lists_databases_and_moves_both_connections() {
    for (name, port) in targets() {
        let session = open(port).await;
        let databases = session.databases().await.unwrap();
        assert!(databases.contains(&"shop".to_string()) && databases.contains(&"archive".to_string()), "{name}: {databases:?}");
        let schema = session.use_database("archive".into()).await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert_eq!(schema.database, "archive", "{name}");
        assert_eq!(schema.tables.iter().map(|t| t.name.as_str()).collect::<Vec<_>>(), ["old"], "{name}");
        assert_eq!(session.database(), "archive", "{name}");
        let r = session.run_sql("db".into(), "SELECT DATABASE()".into(), false).await.unwrap();
        assert_eq!(r.rows[0][0], Cell::Text { v: "archive".into(), full_len: 7 }, "{name}");
    }
}

#[tokio::test]
async fn sorted_and_filtered_pages_follow_the_order_without_gaps() {
    for (name, port) in targets() {
        let session = open(port).await;
        let mut req = page("orders", 1000);
        req.filters = vec![filter("status", FilterOp::Eq, "shipped")];
        req.sort = Some(Sort { column: "created_at".into(), descending: true });
        let mut ids = Vec::new();
        loop {
            let p = session.table_page(req.clone()).await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
            ids.extend(p.rows.iter().map(|r| match r[0] {
                Cell::Unsigned { v } => v,
                ref other => panic!("{name}: {other:?}"),
            }));
            match p.next {
                Some(next) => req.cursor = Some(next),
                None => break,
            }
        }
        let expected: Vec<u64> = (1..=100_000u64).rev().filter(|n| n % 4 == 0).collect();
        assert_eq!(ids, expected, "{name}");
    }
}

#[tokio::test]
async fn nullable_sort_column_pages_by_offset() {
    for (name, port) in targets() {
        let session = open(port).await;
        let mut req = page("items", 30);
        req.sort = Some(Sort { column: "label".into(), descending: false });
        let mut ids = Vec::new();
        loop {
            let p = session.table_page(req.clone()).await.unwrap();
            ids.extend(p.rows.iter().map(|r| match r[0] {
                Cell::Signed { v } => v,
                ref other => panic!("{name}: {other:?}"),
            }));
            match p.next {
                Some(next) => req.cursor = Some(next),
                None => break,
            }
        }
        ids.sort_unstable();
        assert_eq!(ids, (0..100).collect::<Vec<i64>>(), "{name}");
    }
}

#[tokio::test]
async fn counts_respect_filters() {
    for (name, port) in targets() {
        let session = open(port).await;
        let count = |table: &str, filters: Vec<Filter>| CountRequest { table: table.into(), filters };
        let shipped_big = session
            .count_rows(count("orders", vec![filter("status", FilterOp::Eq, "shipped"), filter("total", FilterOp::Gt, "500")]))
            .await
            .unwrap();
        let expected = column(port, "SELECT COUNT(*) FROM orders WHERE status = 'shipped' AND total > 500").await;
        assert_eq!(shipped_big.map(|n| n.to_string()), Some(expected[0].clone()), "{name}");
        let customer = session.count_rows(count("orders", vec![filter("customer", FilterOp::Contains, "Customer 99")])).await.unwrap();
        let expected = column(port, "SELECT COUNT(*) FROM orders WHERE customer LIKE '%Customer 99%'").await;
        assert_eq!(customer.map(|n| n.to_string()), Some(expected[0].clone()), "{name}");
        let percent = session.count_rows(count("tags", vec![filter("name", FilterOp::Contains, "%")])).await.unwrap();
        assert_eq!(percent, Some(0), "{name}: % must match literally");
        let no_notes = session.count_rows(count("orders", vec![filter("notes", FilterOp::IsNull, "")])).await.unwrap();
        assert_eq!(no_notes, Some(90_000), "{name}");
    }
}

#[tokio::test]
async fn facet_counts_group_an_enum_column() {
    for (name, port) in targets() {
        let session = open(port).await;
        let facets = session
            .facet_counts(FacetRequest { table: "items".into(), column: "state".into(), filters: vec![] })
            .await
            .unwrap()
            .expect("not timed out");
        let mut got: Vec<(String, u64)> = facets.into_iter().map(|f| (f.value.expect("not NULL"), f.count)).collect();
        got.sort();
        assert_eq!(got, [("broken".to_string(), 33), ("new".to_string(), 34), ("used".to_string(), 33)], "{name}");
    }
}

#[tokio::test]
async fn full_cell_loads_the_whole_value() {
    for (name, port) in targets() {
        let session = open(port).await;
        let note = session
            .full_cell(CellRequest { table: "orders".into(), column: "notes".into(), key: vec![Cell::Unsigned { v: 10 }] })
            .await
            .unwrap();
        assert_eq!(note, Cell::Text { v: "x".repeat(5000), full_len: 5000 }, "{name}");
        let blob = session
            .full_cell(CellRequest { table: "exact_values".into(), column: "blobby".into(), key: vec![Cell::Signed { v: 1 }] })
            .await
            .unwrap();
        assert_eq!(blob, Cell::Bytes { len: 1000, preview: vec![0xab; 1000] }, "{name}");
    }
}

#[tokio::test]
async fn builder_sql_reads_well_and_runs() {
    for (name, port) in targets() {
        let session = open(port).await;
        session.refresh_schema().await.unwrap();
        let sql = session
            .build_select(SelectSpec {
                table: "orders".into(),
                columns: vec!["id".into(), "customer".into()],
                filters: vec![filter("created_at", FilterOp::Ge, "2026-02-01"), filter("total", FilterOp::Gt, "500")],
                match_all: true,
                sort: Some(Sort { column: "created_at".into(), descending: true }),
                limit: Some(50),
            })
            .unwrap();
        assert_eq!(
            sql,
            "SELECT id, customer\nFROM orders\nWHERE created_at >= '2026-02-01'\n  AND total > 500\nORDER BY created_at DESC\nLIMIT 50;",
            "{name}"
        );
        let r = session.run_sql("b".into(), sql, false).await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert_eq!(r.rows.len(), 50, "{name}");
    }
}
