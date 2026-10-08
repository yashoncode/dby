mod common;

use common::targets;
use dby_core::{cached_schema, connect, history, list_connections, open_store, save_connection, ConnectionInput, DbyError, Env, TlsMode};

/// One store per test process; every test in this file shares it.
fn store() {
    let dir = std::env::temp_dir().join(format!("dby-core-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    open_store(dir.to_string_lossy().into_owned()).unwrap();
}

fn saved(name: &str, port: u16) -> ConnectionInput {
    ConnectionInput {
        id: None,
        name: name.into(),
        host: "127.0.0.1".into(),
        port,
        user: "root".into(),
        database: "shop".into(),
        env: Env::Local,
        tls: TlsMode::EncryptOnly,
        password_cipher: vec![9],
    }
}

#[tokio::test]
async fn saved_connection_connects_and_records_history_and_schema() {
    store();
    for (name, port) in targets() {
        let label = format!("test {name}");
        let conn = save_connection(saved(&label, port)).unwrap();
        assert!(list_connections().unwrap().iter().any(|c| c.id == conn.id), "{name}");
        assert!(cached_schema(conn.id.clone(), "shop".into()).is_none(), "{name}");
        let session = connect(conn.id.clone(), "dbytest".into(), false).await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        session.refresh_schema().await.unwrap();
        let cached = cached_schema(conn.id.clone(), "shop".into()).expect("cached schema");
        assert!(cached.tables.iter().any(|t| t.name == "orders"), "{name}");
        let marker = format!("SELECT '{}' AS marker", conn.id);
        session.run_sql("h".into(), marker.clone(), false).await.unwrap();
        let entry = history(100).unwrap().into_iter().find(|h| h.sql == marker).expect("history entry");
        assert_eq!((entry.error.as_deref(), entry.rows, entry.database.as_str()), (None, 1, "shop"), "{name}");
        assert_eq!(entry.connection_name.as_deref(), Some(label.as_str()), "{name}");
        session.disconnect().await;
    }
}

#[tokio::test]
async fn read_only_connect_makes_a_read_only_session() {
    store();
    for (name, port) in targets() {
        let conn = save_connection(saved(&format!("ro {name}"), port)).unwrap();
        let session = connect(conn.id, "dbytest".into(), true).await.unwrap();
        assert!(session.is_read_only(), "{name}");
        let flag = session.run_sql("f".into(), "SELECT @@transaction_read_only".into(), false).await.unwrap();
        assert_eq!(flag.rows[0][0], dby_core::Cell::Signed { v: 1 }, "{name}: login did not make the server session read-only");
        let blocked = session.run_sql("w".into(), "INSERT INTO edits VALUES (3, 'x', NULL)".into(), true).await;
        assert!(matches!(blocked, Err(DbyError::ReadOnlyBlocked { .. })), "{name}: {blocked:?}");
    }
}

#[tokio::test]
async fn unknown_saved_connection_is_not_found() {
    store();
    let err = connect("no-such-id".into(), "x".into(), false).await.err().expect("should fail");
    assert!(matches!(err, DbyError::NotFound { .. }), "{err:?}");
}
