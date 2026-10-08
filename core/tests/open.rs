mod common;

use common::{kill, open, params, targets};
use dby_core::{open_session, DbyError};

#[tokio::test]
async fn opens_and_reports_version() {
    for (name, port) in targets() {
        let session = open(port).await;
        let info = session.server_info();
        assert!(!info.version.is_empty(), "{name}: empty version");
        if name == "mariadb" {
            assert!(info.version.contains("MariaDB"), "{name}: {}", info.version);
        }
        session.disconnect().await;
    }
}

#[tokio::test]
async fn wrong_password_is_an_auth_error() {
    for (name, port) in targets() {
        let mut p = params(port);
        p.password = "wrong".into();
        let err = open_session(p).await.err().expect("should fail");
        assert!(matches!(err, DbyError::Auth { .. }), "{name}: {err:?}");
    }
}

#[tokio::test]
async fn unknown_database_is_reported_as_such() {
    for (name, port) in targets() {
        let mut p = params(port);
        p.database = "no_such_db".into();
        let err = open_session(p).await.err().expect("should fail");
        assert!(matches!(err, DbyError::UnknownDatabase { .. }), "{name}: {err:?}");
    }
}

#[tokio::test]
async fn closed_port_is_a_network_error() {
    // Nothing listens on port 1, so this needs no database.
    let err = open_session(params(1)).await.err().expect("should fail");
    assert!(matches!(err, DbyError::Network { .. }), "{err:?}");
}

#[tokio::test]
async fn ping_measures_a_round_trip() {
    for (name, port) in targets() {
        let session = open(port).await;
        let ms = session.ping().await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        assert!(ms < 5_000, "{name}: ping took {ms} ms");
    }
}

#[tokio::test]
async fn ping_reconnects_after_browse_connection_is_killed() {
    for (name, port) in targets() {
        let session = open(port).await;
        let (browse_id, _) = session.debug_connection_ids().await;
        kill(port, browse_id).await;
        session.ping().await.unwrap_or_else(|e| panic!("{name}: {e:?}"));
        let (new_id, _) = session.debug_connection_ids().await;
        assert_ne!(new_id, browse_id, "{name}: browse connection was not replaced");
    }
}
