//! One server, two TCP connections: `browse` for metadata and table pages, `query` for the
//! user's own SQL, so a long query never blocks browsing (spec §6).

use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use mysql_async::prelude::Queryable;
use mysql_async::{Conn, Opts, OptsBuilder, Row, SslOpts, Value};
use tokio::sync::Mutex;

use crate::error::{is_connection_lost, DbyError};
use crate::paging::{self, ColumnRow, Cursor, TableMeta};
use crate::value::{self, Cell, ColMeta};

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

/// Largest page the app may ask for.
const MAX_PAGE: u32 = 1000;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ColumnOut {
    pub name: String,
    pub type_name: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PageRequest {
    pub table: String,
    /// None for the first page; afterwards the previous page's `next`.
    pub cursor: Option<Cursor>,
    pub limit: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Page {
    pub columns: Vec<ColumnOut>,
    pub rows: Vec<Vec<Cell>>,
    /// None on the last page.
    pub next: Option<Cursor>,
    pub elapsed_ms: u32,
}

/// Rows the user's own SQL may return before the core stops reading.
const ROW_CAP: usize = 1000;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct QueryResult {
    pub columns: Vec<ColumnOut>,
    pub rows: Vec<Vec<Cell>>,
    pub affected_rows: u64,
    /// True when the result had more than 1000 rows and the rest were not read.
    pub truncated: bool,
    pub elapsed_ms: u32,
}

struct Capped {
    columns: Vec<ColumnOut>,
    rows: Vec<Vec<Cell>>,
    affected_rows: u64,
    truncated: bool,
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

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// One page of a table in primary-key order: one round trip, long cells trimmed.
    pub async fn table_page(&self, req: PageRequest) -> Result<Page, DbyError> {
        let started = Instant::now();
        let limit = req.limit.clamp(1, MAX_PAGE);
        let meta = self
            .tables
            .lock()
            .unwrap()
            .get(&req.table)
            .cloned()
            .ok_or_else(|| DbyError::NotFound { detail: format!("table {}", req.table) })?;
        let built = paging::page_sql(&req.table, &meta, req.cursor.as_ref(), limit)?;
        let (columns, raw) = self.read_browse(&built.sql).await?;
        let metas: Vec<ColMeta> = columns.iter().map(value::meta_of).collect();
        let mut rows: Vec<Vec<Cell>> = raw.into_iter().map(|row| decode_page_row(row, &metas, &built.trimmed)).collect();
        let next = if rows.len() > limit as usize {
            rows.truncate(limit as usize);
            paging::next_cursor(&meta, req.cursor.as_ref(), &rows, limit)
        } else {
            None
        };
        Ok(Page {
            columns: meta.columns.iter().map(|c| ColumnOut { name: c.name.clone(), type_name: c.data_type.clone() }).collect(),
            rows,
            next,
            elapsed_ms: elapsed_ms(started),
        })
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// Runs the user's SQL on the query connection. Stops at 1000 rows; `truncated` says so.
    pub async fn run_sql(&self, run_id: String, sql: String) -> Result<QueryResult, DbyError> {
        let started = Instant::now();
        *self.running.lock().unwrap() = Some(run_id);
        let outcome = self.run_on_query(&sql).await;
        *self.running.lock().unwrap() = None;
        let capped = outcome?;
        Ok(QueryResult {
            columns: capped.columns,
            rows: capped.rows,
            affected_rows: capped.affected_rows,
            truncated: capped.truncated,
            elapsed_ms: elapsed_ms(started),
        })
    }

    /// Interrupts `run_id` if it is still running. A finished or unknown run is a no-op.
    pub async fn cancel(&self, run_id: String) -> Result<(), DbyError> {
        let is_running = self.running.lock().unwrap().as_deref() == Some(run_id.as_str());
        if is_running {
            self.kill_query().await?;
        }
        Ok(())
    }
}

impl Session {
    async fn run_on_query(&self, sql: &str) -> Result<Capped, DbyError> {
        let mut guard = self.query.lock().await;
        let first = match guard.as_mut() {
            Some(conn) => self.stream_capped(conn, sql).await,
            None => return Err(closed()),
        };
        match first {
            Ok(capped) => Ok(capped),
            // Only reads are re-run: a write may already have committed when the connection died.
            Err(e) if is_connection_lost(&e) && is_read(sql) => {
                let mut conn = connect(&self.query_opts).await?;
                self.query_conn_id.store(conn.id(), Ordering::SeqCst);
                let capped = self.stream_capped(&mut conn, sql).await;
                *guard = Some(conn);
                Ok(capped?)
            }
            Err(e) => Err(e.into()),
        }
    }

    async fn stream_capped(&self, conn: &mut Conn, sql: &str) -> Result<Capped, mysql_async::Error> {
        let mut result = conn.query_iter(sql).await?;
        let columns = result.columns().map(|c| c.to_vec()).unwrap_or_default();
        let metas: Vec<ColMeta> = columns.iter().map(value::meta_of).collect();
        let mut rows = Vec::new();
        let mut truncated = false;
        if !columns.is_empty() {
            if let Some(mut stream) = result.stream::<Row>().await? {
                while let Some(row) = stream.next().await {
                    let row = row?;
                    if rows.len() == ROW_CAP {
                        truncated = true;
                        break;
                    }
                    rows.push(row.unwrap().into_iter().zip(&metas).map(|(v, m)| value::decode(v, m, None, true)).collect());
                }
            }
        }
        let affected_rows = result.affected_rows();
        if truncated {
            // Stop the server instead of draining the rest of a huge result over mobile data.
            let _ = self.kill_query().await;
        }
        match result.drop_result().await {
            Err(mysql_async::Error::Server(e)) if truncated && e.code == 1317 => {}
            other => other?,
        }
        Ok(Capped { columns: columns.iter().map(column_out).collect(), rows, affected_rows, truncated })
    }

    /// `KILL QUERY` for the query connection, sent over browse.
    async fn kill_query(&self) -> Result<(), DbyError> {
        let id = self.query_conn_id.load(Ordering::SeqCst);
        let mut guard = self.browse.lock().await;
        let conn = guard.as_mut().ok_or_else(closed)?;
        conn.query_drop(format!("KILL QUERY {id}")).await?;
        Ok(())
    }
}

impl Session {
    /// Server-side ids of (browse, query), for tests that kill connections. Not exported.
    #[doc(hidden)]
    pub async fn debug_connection_ids(&self) -> (u32, u32) {
        let browse = self.browse.lock().await.as_ref().map_or(0, Conn::id);
        (browse, self.query_conn_id.load(Ordering::SeqCst))
    }

    /// A read on browse. If the server or network dropped the connection, reconnects once and
    /// retries: every statement browse runs is a read.
    async fn read_browse(&self, sql: &str) -> Result<(Vec<mysql_async::Column>, Vec<Row>), DbyError> {
        let mut guard = self.browse.lock().await;
        let first = match guard.as_mut() {
            Some(conn) => fetch_all(conn, sql).await,
            None => return Err(closed()),
        };
        match first {
            Ok(found) => Ok(found),
            Err(e) if is_connection_lost(&e) => {
                let mut conn = connect(&self.browse_opts).await?;
                let found = fetch_all(&mut conn, sql).await;
                *guard = Some(conn);
                Ok(found?)
            }
            Err(e) => Err(e.into()),
        }
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

async fn fetch_all(conn: &mut Conn, sql: &str) -> Result<(Vec<mysql_async::Column>, Vec<Row>), mysql_async::Error> {
    let mut result = conn.query_iter(sql).await?;
    let columns = result.columns().map(|c| c.to_vec()).unwrap_or_default();
    let rows: Vec<Row> = result.collect().await?;
    result.drop_result().await?;
    Ok((columns, rows))
}

/// Folds each trimmed column's (value, full length) pair back into one cell.
fn decode_page_row(row: Row, metas: &[ColMeta], trimmed: &[bool]) -> Vec<Cell> {
    let mut values = row.unwrap().into_iter();
    let mut metas = metas.iter();
    trimmed
        .iter()
        .map(|&is_trimmed| {
            let value = values.next().unwrap_or(Value::NULL);
            let meta = metas.next().copied().unwrap_or(ColMeta::TEXT);
            let full_len = if is_trimmed {
                metas.next();
                values.next().as_ref().and_then(value::as_len)
            } else {
                None
            };
            value::decode(value, &meta, full_len, false)
        })
        .collect()
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

fn column_out(col: &mysql_async::Column) -> ColumnOut {
    let type_name = format!("{:?}", col.column_type());
    ColumnOut {
        name: col.name_str().into_owned(),
        type_name: type_name.trim_start_matches("MYSQL_TYPE_").to_ascii_lowercase(),
    }
}

/// True only for statements that cannot change data, so re-running one after a dropped
/// connection is safe. `WITH` is excluded because `WITH ... DELETE` exists.
// ponytail: first-keyword check; M1's sqlparser classifier replaces it.
fn is_read(sql: &str) -> bool {
    let first = sql.trim_start().split(|c: char| c.is_whitespace() || c == '(').next().unwrap_or("");
    ["select", "show", "describe", "desc", "explain"].iter().any(|k| first.eq_ignore_ascii_case(k))
}

#[cfg(test)]
mod tests {
    use super::is_read;

    #[test]
    fn only_plain_reads_are_retryable() {
        for sql in ["SELECT 1", "  select * from t", "SHOW TABLES", "describe t", "DESC t", "EXPLAIN SELECT 1", "SELECT(1)"] {
            assert!(is_read(sql), "{sql}");
        }
        for sql in ["INSERT INTO t VALUES (1)", "UPDATE t SET x = 1", "WITH a AS (SELECT 1) DELETE FROM t", "/* c */ SELECT 1", "CALL p()", ""] {
            assert!(!is_read(sql), "{sql}");
        }
    }
}
