#![allow(dead_code)] // each test file uses a different subset

use std::sync::Arc;

use dby_core::{open_session, ConnectParams, Session, TlsMode};
use mysql_async::prelude::Queryable;

/// Databases from tests/docker-compose.yml. An unset variable skips that target.
pub fn targets() -> Vec<(&'static str, u16)> {
    let mut found = Vec::new();
    for (name, var) in [("mysql", "DBY_TEST_MYSQL_PORT"), ("mariadb", "DBY_TEST_MARIADB_PORT")] {
        match std::env::var(var) {
            Ok(port) => found.push((name, port.parse().expect("port number"))),
            Err(_) => eprintln!("skipping {name}: {var} is not set"),
        }
    }
    found
}

pub fn params(port: u16) -> ConnectParams {
    ConnectParams {
        host: "127.0.0.1".into(),
        port,
        user: "root".into(),
        password: "dbytest".into(),
        database: "shop".into(),
        // Docker's servers use self-signed certificates.
        tls: TlsMode::EncryptOnly,
    }
}

pub async fn open(port: u16) -> Arc<Session> {
    open_session(params(port)).await.expect("open session")
}

/// A separate plain connection, for doing things behind the session's back.
pub async fn raw(port: u16) -> mysql_async::Conn {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let opts = mysql_async::OptsBuilder::default()
        .ip_or_hostname("127.0.0.1")
        .tcp_port(port)
        .user(Some("root"))
        .pass(Some("dbytest"))
        .db_name(Some("shop"))
        .prefer_socket(false)
        .ssl_opts(
            mysql_async::SslOpts::default()
                .with_danger_accept_invalid_certs(true)
                .with_danger_skip_domain_validation(true),
        );
    mysql_async::Conn::new(opts).await.expect("raw connection")
}

/// Has the server drop one connection, the way an idle timeout or a restart would.
pub async fn kill(port: u16, connection_id: u32) {
    let mut conn = raw(port).await;
    conn.query_drop(format!("KILL {connection_id}")).await.expect("kill");
    conn.disconnect().await.expect("disconnect");
}

/// The first column of `sql`, as text, straight from the server.
pub async fn column(port: u16, sql: &str) -> Vec<String> {
    let mut conn = raw(port).await;
    let values: Vec<String> = conn.query(sql).await.expect("query");
    conn.disconnect().await.expect("disconnect");
    values
}
