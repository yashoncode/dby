mod common;

use std::time::{Duration, Instant};

use common::{kill, open, raw, targets};
use dby_core::{Cell, DbyError};
use mysql_async::prelude::Queryable;

#[tokio::test]
async fn select_without_limit_stops_at_1000_rows() {
    for (name, port) in targets() {
        let session = open(port).await;
        let r = session.run_sql("r1".into(), "SELECT id FROM orders".into(), true).await.unwrap();
        assert_eq!(r.rows.len(), 1000, "{name}");
        assert!(r.truncated, "{name}");
        let ok = session.run_sql("r2".into(), "SELECT 1".into(), true).await.unwrap();
        assert_eq!(ok.rows, vec![vec![Cell::Signed { v: 1 }]], "{name}");
    }
}

#[tokio::test]
async fn explicit_huge_limit_is_cut_and_connection_survives() {
    for (name, port) in targets() {
        let session = open(port).await;
        let r = session
            .run_sql("big".into(), "SELECT id, notes FROM orders ORDER BY id LIMIT 100000".into(), true)
            .await
            .unwrap();
        assert_eq!(r.rows.len(), 1000, "{name}");
        assert!(r.truncated, "{name}");
        assert_eq!(r.rows[9][1], Cell::Text { v: "x".repeat(256), full_len: 5000 }, "{name}");
        let count = session.run_sql("count".into(), "SELECT COUNT(*) FROM orders".into(), true).await.unwrap();
        assert_eq!(count.rows, vec![vec![Cell::Signed { v: 100_000 }]], "{name}");
    }
}

#[tokio::test]
async fn small_result_is_not_truncated() {
    for (name, port) in targets() {
        let session = open(port).await;
        let r = session.run_sql("s".into(), "SELECT id FROM orders ORDER BY id LIMIT 5".into(), true).await.unwrap();
        assert_eq!(r.rows.len(), 5, "{name}");
        assert!(!r.truncated, "{name}");
        assert_eq!(r.rows[0], vec![Cell::Unsigned { v: 1 }], "{name}");
        assert_eq!(r.columns[0].name, "id", "{name}");
    }
}

#[tokio::test]
async fn writes_report_affected_rows() {
    for (name, port) in targets() {
        let session = open(port).await;
        session.run_sql("w1".into(), "CREATE TEMPORARY TABLE scratch (x INT)".into(), true).await.unwrap();
        let r = session.run_sql("w2".into(), "INSERT INTO scratch VALUES (1), (2), (3)".into(), true).await.unwrap();
        assert_eq!(r.affected_rows, 3, "{name}");
        assert!(r.columns.is_empty(), "{name}");
    }
}

#[tokio::test]
async fn cancel_interrupts_a_long_query() {
    for (name, port) in targets() {
        let session = open(port).await;
        let runner = session.clone();
        let started = Instant::now();
        let task = tokio::spawn(async move {
            runner
                .run_sql("slow".into(), "SELECT COUNT(*) FROM orders a, orders b WHERE a.id < b.id".into(), true)
                .await
        });
        tokio::time::sleep(Duration::from_millis(500)).await;
        session.cancel("slow".into()).await.unwrap();
        let outcome = task.await.unwrap();
        assert!(matches!(outcome, Err(DbyError::Cancelled)), "{name}: {outcome:?}");
        assert!(started.elapsed() < Duration::from_secs(10), "{name}: cancel took {:?}", started.elapsed());
        let ok = session.run_sql("after".into(), "SELECT 1".into(), true).await.unwrap();
        assert_eq!(ok.rows.len(), 1, "{name}");
    }
}

#[tokio::test]
async fn cancelling_an_unknown_run_is_a_no_op() {
    for (_, port) in targets() {
        let session = open(port).await;
        session.cancel("nothing-running".into()).await.unwrap();
    }
}

#[tokio::test]
async fn read_is_retried_after_the_server_drops_the_connection() {
    for (name, port) in targets() {
        let session = open(port).await;
        let (_, query_id) = session.debug_connection_ids().await;
        kill(port, query_id).await;
        let r = session
            .run_sql("again".into(), "SELECT 2".into(), true)
            .await
            .unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert_eq!(r.rows, vec![vec![Cell::Signed { v: 2 }]], "{name}");
        let (_, new_id) = session.debug_connection_ids().await;
        assert_ne!(new_id, query_id, "{name}");
    }
}

#[tokio::test]
async fn write_is_not_retried_after_the_server_drops_the_connection() {
    for (name, port) in targets() {
        let session = open(port).await;
        let mut setup = raw(port).await;
        setup.query_drop("CREATE TABLE IF NOT EXISTS write_once (x INT)").await.unwrap();
        setup.query_drop("DELETE FROM write_once").await.unwrap();
        let (_, query_id) = session.debug_connection_ids().await;
        kill(port, query_id).await;
        let failed = session.run_sql("w".into(), "INSERT INTO write_once VALUES (1)".into(), true).await;
        assert!(failed.is_err(), "{name}: a write after a dropped connection must fail, got {failed:?}");
        let count: Vec<i64> = setup.query("SELECT COUNT(*) FROM write_once").await.unwrap();
        assert_eq!(count, vec![0], "{name}: the write ran anyway");
        let r = session.run_sql("r".into(), "SELECT 3".into(), true).await.unwrap();
        assert_eq!(r.rows, vec![vec![Cell::Signed { v: 3 }]], "{name}");
    }
}
