//! One server, two TCP connections: `browse` for metadata and table pages, `query` for the
//! user's own SQL, so a long query never blocks browsing (spec §6).

use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};

use mysql_async::prelude::Queryable;
use mysql_async::{Conn, Opts, OptsBuilder, Row, SslOpts, Value};
use tokio::sync::Mutex;

use crate::error::{is_connection_lost, DbyError};
use crate::paging::{self, ColumnRow, TableMeta};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// Run once at login on the query connection: the server stops after 1001 rows for any
/// top-level SELECT without its own LIMIT, so `SELECT *` never streams a whole table.
const QUERY_SETUP: &str = "SET SESSION sql_select_limit = 1001";

/// Amazon's RDS CA bundle, trusted in `TlsMode::Verify` alongside the public CAs.
static RDS_CA: &[u8] = include_bytes!("../certs/rds-global-bundle.pem");

/// The server version, then every column of every table in the current database with its
/// position in the primary key. One round trip.
// ponytail: the whole schema loads at open; M1 keeps it in the local cache and refreshes it
// in the background, which also removes this query from the connect time.
const SCHEMA_SQL: &str = "SELECT VERSION(); \
    SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.CHARACTER_MAXIMUM_LENGTH, s.SEQ_IN_INDEX \
    FROM information_schema.COLUMNS c \
    LEFT JOIN information_schema.STATISTICS s \
      ON s.TABLE_SCHEMA = c.TABLE_SCHEMA AND s.TABLE_NAME = c.TABLE_NAME \
     AND s.COLUMN_NAME = c.COLUMN_NAME AND s.INDEX_NAME = 'PRIMARY' \
    WHERE c.TABLE_SCHEMA = DATABASE() \
    ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION";

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TlsMode {
    /// Encrypted; certificate checked against public CAs and the Amazon RDS bundle.
    Verify,
    /// Encrypted; certificate not checked. Shown as "Encrypted, not verified".
    EncryptOnly,
    /// No TLS. Shown as "Not encrypted".
    Off,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ConnectParams {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub password: String,
    pub database: String,
    pub tls: TlsMode,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ServerInfo {
    pub version: String,
    /// Both logins plus the schema load, in milliseconds.
    pub connect_ms: u32,
}

#[derive(uniffi::Object)]
pub struct Session {
    browse_opts: Opts,
    query_opts: Opts,
    browse: Mutex<Option<Conn>>,
    query: Mutex<Option<Conn>>,
    /// Server-side id of the query connection, for `KILL QUERY` sent over browse.
    query_conn_id: AtomicU32,
    /// `run_id` of the user SQL in flight, if any.
    running: StdMutex<Option<String>>,
    tables: StdMutex<HashMap<String, Arc<TableMeta>>>,
    info: ServerInfo,
}

/// Logs in on both connections at once and loads the schema on browse while query is still
/// logging in.
#[uniffi::export(async_runtime = "tokio")]
pub async fn open_session(params: ConnectParams) -> Result<Arc<Session>, DbyError> {
    // rustls needs one process-wide crypto provider; ring is the only one compiled in.
    let _ = rustls::crypto::ring::default_provider().install_default();
    let started = Instant::now();
    let browse_opts = build_opts(&params, None);
    let query_opts = build_opts(&params, Some(QUERY_SETUP));
    let ((browse, version, tables), query) = tokio::try_join!(
        async {
            let mut conn = connect(&browse_opts).await?;
            let (version, tables) = load_schema(&mut conn).await?;
            Ok::<_, DbyError>((conn, version, tables))
        },
        connect(&query_opts),
    )?;
    Ok(Arc::new(Session {
        query_conn_id: AtomicU32::new(query.id()),
        browse: Mutex::new(Some(browse)),
        query: Mutex::new(Some(query)),
        running: StdMutex::new(None),
        tables: StdMutex::new(tables),
        info: ServerInfo { version, connect_ms: elapsed_ms(started) },
        browse_opts,
        query_opts,
    }))
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    pub fn server_info(&self) -> ServerInfo {
        self.info.clone()
    }

    /// Round trip on browse, in milliseconds. Reconnects first if the connection was dropped,
    /// which is what the app calls on resume.
    pub async fn ping(&self) -> Result<u32, DbyError> {
        let started = Instant::now();
        let mut guard = self.browse.lock().await;
        let lost = match guard.as_mut() {
            Some(conn) => match conn.ping().await {
                Ok(()) => false,
                Err(e) if is_connection_lost(&e) => true,
                Err(e) => return Err(e.into()),
            },
            None => return Err(closed()),
        };
        if lost {
            *guard = Some(connect(&self.browse_opts).await?);
        }
        Ok(elapsed_ms(started))
    }

    pub async fn disconnect(&self) {
        let query = self.query.lock().await.take();
        let browse = self.browse.lock().await.take();
        for conn in [query, browse].into_iter().flatten() {
            let _ = conn.disconnect().await;
        }
    }
}

impl Session {
    /// Server-side ids of (browse, query), for tests that kill connections. Not exported.
    #[doc(hidden)]
    pub async fn debug_connection_ids(&self) -> (u32, u32) {
        let browse = self.browse.lock().await.as_ref().map_or(0, Conn::id);
        (browse, self.query_conn_id.load(Ordering::SeqCst))
    }
}

fn build_opts(p: &ConnectParams, setup: Option<&str>) -> Opts {
    let ssl = match p.tls {
        TlsMode::Off => None,
        TlsMode::Verify => Some(SslOpts::default().with_root_certs(vec![RDS_CA.into()])),
        TlsMode::EncryptOnly => Some(
            SslOpts::default()
                .with_danger_accept_invalid_certs(true)
                .with_danger_skip_domain_validation(true),
        ),
    };
    OptsBuilder::default()
        .ip_or_hostname(p.host.clone())
        .tcp_port(p.port)
        .user(Some(p.user.clone()))
        .pass(Some(p.password.clone()))
        .db_name(Some(p.database.clone()))
        .prefer_socket(false)
        .tcp_nodelay(true)
        .tcp_keepalive(Some(Duration::from_secs(30)))
        .ssl_opts(ssl)
        .init(setup.map(|s| vec![s.to_string()]).unwrap_or_default())
        .into()
}

pub(crate) async fn connect(opts: &Opts) -> Result<Conn, DbyError> {
    match tokio::time::timeout(CONNECT_TIMEOUT, Conn::new(opts.clone())).await {
        Ok(conn) => Ok(conn?),
        Err(_) => Err(DbyError::Timeout),
    }
}

async fn load_schema(conn: &mut Conn) -> Result<(String, HashMap<String, Arc<TableMeta>>), DbyError> {
    let mut result = conn.query_iter(SCHEMA_SQL).await?;
    let version: Vec<Row> = result.collect().await?;
    let columns: Vec<Row> = result.collect().await?;
    result.drop_result().await?;
    let version = version.first().and_then(|r| text_at(r, 0)).unwrap_or_default();
    let rows = columns
        .iter()
        .map(|r| ColumnRow {
            table: text_at(r, 0).unwrap_or_default(),
            column: text_at(r, 1).unwrap_or_default(),
            data_type: text_at(r, 2).unwrap_or_default(),
            max_len: text_at(r, 3).and_then(|s| s.parse().ok()),
            pk_seq: text_at(r, 4).and_then(|s| s.parse().ok()),
        })
        .collect();
    Ok((version, paging::build_tables(rows)))
}

fn text_at(row: &Row, index: usize) -> Option<String> {
    match row.as_ref(index)? {
        Value::Bytes(b) => Some(String::from_utf8_lossy(b).into_owned()),
        _ => None,
    }
}

pub(crate) fn elapsed_ms(started: Instant) -> u32 {
    u32::try_from(started.elapsed().as_millis()).unwrap_or(u32::MAX)
}

pub(crate) fn closed() -> DbyError {
    DbyError::internal("session is disconnected")
}
