//! One server, two TCP connections: `browse` for metadata, table pages, counts and row
//! changes; `query` for the user's own SQL, so a long query never blocks browsing (spec §6).

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use mysql_async::prelude::Queryable;
use mysql_async::{Column, Conn, Opts, OptsBuilder, Row, SslOpts, TxOpts, Value};
use tokio::sync::Mutex;

use crate::classify::{self, classify, SqlKind};
use crate::edit::{self, Prepared, RowChange};
use crate::error::{is_connection_lost, DbyError};
use crate::paging::{self, Cursor, Filter, PageSpec, SelectSpec, Sort, TableMeta};
use crate::schema::{self, Schema};
use crate::store::{self, NewHistory};
use crate::value::{self, Cell, ColMeta};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// Run at login on the query connection: the server stops after 1001 rows for any top-level
/// SELECT without its own LIMIT, so `SELECT *` never streams a whole table.
const QUERY_SETUP: &str = "SET SESSION sql_select_limit = 1001";
const READ_ONLY: &str = "SET SESSION TRANSACTION READ ONLY";
const READ_WRITE: &str = "SET SESSION TRANSACTION READ WRITE";

/// Amazon's RDS CA bundle, trusted in `TlsMode::Verify` alongside the public CAs.
static RDS_CA: &[u8] = include_bytes!("../certs/rds-global-bundle.pem");

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
    /// "MySQL 8.4.6" or "MariaDB 11.4.7", from the login handshake.
    pub version: String,
    /// Both logins, in milliseconds.
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
    /// All must match.
    pub filters: Vec<Filter>,
    /// None pages in primary-key order.
    pub sort: Option<Sort>,
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

#[derive(Debug, Clone, uniffi::Record)]
pub struct CountRequest {
    pub table: String,
    pub filters: Vec<Filter>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FacetRequest {
    pub table: String,
    pub column: String,
    pub filters: Vec<Filter>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FacetCount {
    /// None for NULL.
    pub value: Option<String>,
    pub count: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CellRequest {
    pub table: String,
    pub column: String,
    /// The row's primary-key values in key order, as the page returned them.
    pub key: Vec<Cell>,
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

struct ResultSet {
    columns: Vec<Column>,
    rows: Vec<Row>,
}

#[derive(Clone, Copy)]
enum Role {
    Browse,
    Query,
}

#[derive(uniffi::Object)]
pub struct Session {
    /// What a reconnect logs in with; `database` follows `use_database`.
    params: StdMutex<ConnectParams>,
    read_only: AtomicBool,
    /// The saved connection this session came from; None for `open_session`.
    connection_id: Option<String>,
    mariadb: bool,
    browse: Mutex<Option<Conn>>,
    query: Mutex<Option<Conn>>,
    /// Server-side id of the query connection, for `KILL QUERY` sent over browse.
    query_conn_id: AtomicU32,
    /// `run_id` of the user SQL in flight, if any.
    running: StdMutex<Option<String>>,
    tables: StdMutex<HashMap<String, Arc<TableMeta>>>,
    info: ServerInfo,
}

/// A writable session straight from parameters, with no saved connection behind it, so
/// nothing is cached or recorded. M0's entry point, and the tests'.
#[uniffi::export(async_runtime = "tokio")]
pub async fn open_session(params: ConnectParams) -> Result<Arc<Session>, DbyError> {
    Session::open(params, None, false).await
}

/// Opens saved connection `id`. `read_only` is the app's call (PROD with "Read-only on PROD"
/// on). Its tables come from the local cache until `refresh_schema` answers.
#[uniffi::export(async_runtime = "tokio")]
pub async fn connect(id: String, password: String, read_only: bool) -> Result<Arc<Session>, DbyError> {
    let saved = store::with_store(|s| s.connection(&id))?;
    let database = saved.database.clone();
    let params = ConnectParams { host: saved.host, port: saved.port, user: saved.user, password, database: saved.database, tls: saved.tls };
    let session = Session::open(params, Some(id.clone()), read_only).await?;
    if let Ok(Some(schema)) = store::with_store(|s| s.schema(&id, &database)) {
        session.set_tables(&schema);
    }
    let _ = store::with_store(|s| s.touch_connection(&id));
    Ok(session)
}

impl Session {
    async fn open(params: ConnectParams, connection_id: Option<String>, read_only: bool) -> Result<Arc<Session>, DbyError> {
        // rustls needs one process-wide crypto provider; ring is the only one compiled in.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let started = Instant::now();
        let (browse_opts, query_opts) = (build_opts(&params, Role::Browse, read_only), build_opts(&params, Role::Query, read_only));
        let (browse, query) = tokio::try_join!(connect_conn(&browse_opts), connect_conn(&query_opts))?;
        let (major, minor, patch) = browse.server_version();
        // MariaDB's versions start at 10; MySQL's run from 5 to 9.
        let mariadb = major >= 10;
        let flavor = if mariadb { "MariaDB" } else { "MySQL" };
        Ok(Arc::new(Session {
            info: ServerInfo { version: format!("{flavor} {major}.{minor}.{patch}"), connect_ms: elapsed_ms(started) },
            query_conn_id: AtomicU32::new(query.id()),
            browse: Mutex::new(Some(browse)),
            query: Mutex::new(Some(query)),
            running: StdMutex::new(None),
            tables: StdMutex::new(HashMap::new()),
            read_only: AtomicBool::new(read_only),
            params: StdMutex::new(params),
            connection_id,
            mariadb,
        }))
    }

    fn opts(&self, role: Role) -> Opts {
        build_opts(&self.params.lock().unwrap(), role, self.read_only.load(Ordering::SeqCst))
    }

    fn conn(&self, role: Role) -> &Mutex<Option<Conn>> {
        match role {
            Role::Browse => &self.browse,
            Role::Query => &self.query,
        }
    }

    fn set_tables(&self, schema: &Schema) {
        *self.tables.lock().unwrap() = paging::tables_from(schema);
    }

    fn known_table(&self, table: &str) -> Result<Arc<TableMeta>, DbyError> {
        let known = self.tables.lock().unwrap().get(table).cloned();
        known.ok_or_else(|| DbyError::NotFound { detail: format!("table {table}") })
    }

    /// The table's metadata, reading the schema first if the table is not known yet.
    async fn meta(&self, table: &str) -> Result<Arc<TableMeta>, DbyError> {
        let known = self.known_table(table);
        if known.is_ok() {
            return known;
        }
        self.refresh_schema().await?;
        self.known_table(table)
    }

    fn check_writable(&self) -> Result<(), DbyError> {
        if self.read_only.load(Ordering::SeqCst) {
            Err(DbyError::ReadOnlyBlocked { detail: "this connection is read-only; unlock it to change data".into() })
        } else {
            Ok(())
        }
    }

    /// Adds a history entry when this session came from a saved connection.
    fn record(&self, sql: &str, error: Option<String>, rows: u64, elapsed_ms: u32) {
        let Some(id) = &self.connection_id else { return };
        let entry = NewHistory { connection_id: id.clone(), database: self.database(), sql: sql.to_string(), error, rows, elapsed_ms };
        let _ = store::with_store(|s| s.add_history(&entry));
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    pub fn server_info(&self) -> ServerInfo {
        self.info.clone()
    }

    /// The database both connections are using.
    pub fn database(&self) -> String {
        self.params.lock().unwrap().database.clone()
    }

    pub fn is_read_only(&self) -> bool {
        self.read_only.load(Ordering::SeqCst)
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
            *guard = Some(connect_conn(&self.opts(Role::Browse)).await?);
        }
        Ok(elapsed_ms(started))
    }

    /// Reads the current database's schema in one round trip and caches it on the phone.
    pub async fn refresh_schema(&self) -> Result<Schema, DbyError> {
        let sets = self.read_browse(schema::SCHEMA_SQL, 4).await?;
        let schema = schema::from_rows(&sets[0].rows, &sets[1].rows, &sets[2].rows, &sets[3].rows);
        self.set_tables(&schema);
        if let Some(id) = &self.connection_id {
            let _ = store::with_store(|s| s.put_schema(id, &schema));
        }
        Ok(schema)
    }

    pub async fn databases(&self) -> Result<Vec<String>, DbyError> {
        let sets = self.read_browse("SHOW DATABASES", 1).await?;
        Ok(sets[0].rows.iter().filter_map(|r| schema::text_at(r, 0)).collect())
    }

    /// Moves both connections to `name` and reads its schema.
    pub async fn use_database(&self, name: String) -> Result<Schema, DbyError> {
        let sql = format!("USE {}", paging::quote_ident(&name));
        {
            // Both locks at once, query first (the order run_sql's kill_query takes them in), so
            // no browse read runs in the new database under the old schema.
            let mut query = self.query.lock().await;
            let mut browse = self.browse.lock().await;
            for conn in [query.as_mut(), browse.as_mut()] {
                conn.ok_or_else(closed)?.query_drop(&sql).await?;
            }
            self.params.lock().unwrap().database = name;
            self.tables.lock().unwrap().clear();
        }
        self.refresh_schema().await
    }

    /// Locks or unlocks writes on both connections (spec §7). Reconnects keep the choice.
    pub async fn set_read_only(&self, read_only: bool) -> Result<(), DbyError> {
        self.read_only.store(read_only, Ordering::SeqCst);
        let sql = if read_only { READ_ONLY } else { READ_WRITE };
        for role in [Role::Browse, Role::Query] {
            let mut guard = self.conn(role).lock().await;
            if let Some(conn) = guard.as_mut() {
                conn.query_drop(sql).await?;
            }
        }
        Ok(())
    }

    pub async fn disconnect(&self) {
        let query = self.query.lock().await.take();
        let browse = self.browse.lock().await.take();
        for conn in [query, browse].into_iter().flatten() {
            let _ = conn.disconnect().await;
        }
        // ponytail: wipes this copy of the password only; mysql_async's own copies are freed,
        // not wiped. Switch to a zeroizing secret type if that ever matters.
        let mut password = std::mem::take(&mut self.params.lock().unwrap().password).into_bytes();
        password.fill(0);
        std::hint::black_box(&password);
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// One page of a table, filtered and sorted: one round trip, long cells trimmed.
    pub async fn table_page(&self, req: PageRequest) -> Result<Page, DbyError> {
        let started = Instant::now();
        let limit = req.limit.clamp(1, MAX_PAGE);
        let meta = self.meta(&req.table).await?;
        let order = paging::order_of(&meta, req.sort.as_ref())?;
        let built = paging::page_sql(&meta, &PageSpec { table: &req.table, filters: &req.filters, order: &order, cursor: req.cursor.as_ref(), limit })?;
        let set = self.read_browse(&built.sql, 1).await?.remove(0);
        let metas: Vec<ColMeta> = set.columns.iter().map(value::meta_of).collect();
        let mut rows: Vec<Vec<Cell>> = set.rows.into_iter().map(|row| decode_page_row(row, &metas, &built.trimmed)).collect();
        let next = if rows.len() > limit as usize {
            rows.truncate(limit as usize);
            paging::next_cursor(&order, req.cursor.as_ref(), &rows, limit)
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

    /// Rows matching the filters, or None when counting took longer than two seconds.
    pub async fn count_rows(&self, req: CountRequest) -> Result<Option<u64>, DbyError> {
        let meta = self.meta(&req.table).await?;
        let sql = paging::time_limited(&paging::count_sql(&meta, &req.table, &req.filters)?, self.mariadb);
        match self.read_browse(&sql, 1).await {
            Ok(sets) => Ok(sets[0].rows.first().and_then(|r| schema::number_at(r, 0))),
            Err(DbyError::Timeout) => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Rows per value of `column` under the filters, most common first; None on timeout.
    pub async fn facet_counts(&self, req: FacetRequest) -> Result<Option<Vec<FacetCount>>, DbyError> {
        let meta = self.meta(&req.table).await?;
        let sql = paging::time_limited(&paging::facet_sql(&meta, &req.table, &req.column, &req.filters)?, self.mariadb);
        match self.read_browse(&sql, 1).await {
            Ok(sets) => Ok(Some(
                sets[0]
                    .rows
                    .iter()
                    .map(|r| FacetCount { value: schema::text_at(r, 0), count: schema::number_at(r, 1).unwrap_or(0) })
                    .collect(),
            )),
            Err(DbyError::Timeout) => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// One cell in full (up to 1,000,000 characters or 65,536 bytes), for the cell viewer.
    pub async fn full_cell(&self, req: CellRequest) -> Result<Cell, DbyError> {
        let meta = self.meta(&req.table).await?;
        let built = paging::cell_sql(&meta, &req.table, &req.column, &req.key)?;
        let set = self.read_browse(&built.sql, 1).await?.remove(0);
        let metas: Vec<ColMeta> = set.columns.iter().map(value::meta_of).collect();
        let row = set.rows.into_iter().next().ok_or_else(|| DbyError::NotFound { detail: "that row no longer exists".into() })?;
        Ok(decode_page_row(row, &metas, &built.trimmed).remove(0))
    }

    /// The Query builder's SQL, written the way a person would. Needs the table's schema.
    pub fn build_select(&self, spec: SelectSpec) -> Result<String, DbyError> {
        paging::build_select(&*self.known_table(&spec.table)?, &spec)
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// Runs the user's SQL on the query connection and stops at 1000 rows. A write runs only
    /// when `allow_write` is true (the person confirmed it) and the session is not read-only.
    pub async fn run_sql(&self, run_id: String, sql: String, allow_write: bool) -> Result<QueryResult, DbyError> {
        if let Some(database) = classify::use_target(&sql) {
            let started = Instant::now();
            let outcome = self.use_database(database).await;
            self.record(&sql, outcome.as_ref().err().map(|e| e.to_string()), 0, elapsed_ms(started));
            outcome?;
            return Ok(QueryResult { columns: vec![], rows: vec![], affected_rows: 0, truncated: false, elapsed_ms: elapsed_ms(started) });
        }
        if classify::contains_use(&sql) {
            return Err(DbyError::ReadOnlyBlocked { detail: "run USE on its own, so both of DBY's connections switch".into() });
        }
        let kind = classify(&sql);
        if kind == SqlKind::Write {
            self.check_writable()?;
            if !allow_write {
                return Err(DbyError::ReadOnlyBlocked { detail: "this statement changes data; confirm it first".into() });
            }
        }
        let started = Instant::now();
        *self.running.lock().unwrap() = Some(run_id);
        let outcome = self.run_on_query(&sql, kind).await;
        *self.running.lock().unwrap() = None;
        let elapsed = elapsed_ms(started);
        match &outcome {
            Ok(c) => self.record(&sql, None, if c.columns.is_empty() { c.affected_rows } else { c.rows.len() as u64 }, elapsed),
            Err(e) => self.record(&sql, Some(e.to_string()), 0, elapsed),
        }
        let capped = outcome?;
        Ok(QueryResult {
            columns: capped.columns,
            rows: capped.rows,
            affected_rows: capped.affected_rows,
            truncated: capped.truncated,
            elapsed_ms: elapsed,
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

    /// The SQL the Edit row sheet shows for `change`. Needs the table's schema.
    pub fn preview_row_change(&self, change: RowChange) -> Result<String, DbyError> {
        edit::preview(&*self.known_table(&change.table)?, &change)
    }

    /// Applies one row change in a transaction; anything but exactly one matched row is
    /// rolled back (spec §7). Returns the rows changed, which is always 1.
    pub async fn apply_row_change(&self, change: RowChange) -> Result<u64, DbyError> {
        self.check_writable()?;
        let meta = self.meta(&change.table).await?;
        let prepared = edit::prepare(&meta, &change)?;
        let shown = edit::preview(&meta, &change)?;
        let started = Instant::now();
        let outcome = self.apply_on_browse(&prepared).await;
        let elapsed = elapsed_ms(started);
        match &outcome {
            Ok(n) => self.record(&shown, None, *n, elapsed),
            Err(e) => self.record(&shown, Some(e.to_string()), 0, elapsed),
        }
        outcome
    }
}

impl Session {
    async fn run_on_query(&self, sql: &str, kind: SqlKind) -> Result<Capped, DbyError> {
        let mut guard = self.query.lock().await;
        let first = match guard.as_mut() {
            Some(conn) => self.stream_capped(conn, sql).await,
            None => return Err(closed()),
        };
        match first {
            Ok(capped) => Ok(capped),
            // Only reads are re-run: a write may already have committed when the connection died.
            Err(e) if is_connection_lost(&e) && kind == SqlKind::Read => {
                let mut conn = connect_conn(&self.opts(Role::Query)).await?;
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

    // ponytail: a browse connection the server dropped while idle fails the first row change
    // with a network error (the app pings on resume, which covers the usual case). Retrying
    // before COMMIT would be safe, since the server rolls back an uncommitted transaction.
    async fn apply_on_browse(&self, prepared: &Prepared) -> Result<u64, DbyError> {
        let mut guard = self.browse.lock().await;
        let conn = guard.as_mut().ok_or_else(closed)?;
        let mut tx = conn.start_transaction(TxOpts::default()).await?;
        if let Err(e) = tx.exec_drop(prepared.sql.as_str(), prepared.params.clone()).await {
            // Roll back now: a dropped transaction rolls back only at the next command, and
            // until then the row stays locked for everyone else.
            let _ = tx.rollback().await;
            return Err(e.into());
        }
        let affected = tx.affected_rows();
        if affected != 1 {
            tx.rollback().await?;
            return Err(DbyError::RowEditMismatch { affected });
        }
        tx.commit().await?;
        Ok(affected)
    }

    /// Server-side ids of (browse, query), for tests that kill connections. Not exported.
    #[doc(hidden)]
    pub async fn debug_connection_ids(&self) -> (u32, u32) {
        let browse = self.browse.lock().await.as_ref().map_or(0, Conn::id);
        (browse, self.query_conn_id.load(Ordering::SeqCst))
    }

    /// `sets` result sets read on browse. If the server or network dropped the connection,
    /// reconnects once and retries: everything browse reads this way is a read.
    async fn read_browse(&self, sql: &str, sets: usize) -> Result<Vec<ResultSet>, DbyError> {
        let mut guard = self.browse.lock().await;
        let first = match guard.as_mut() {
            Some(conn) => fetch_sets(conn, sql, sets).await,
            None => return Err(closed()),
        };
        match first {
            Ok(found) => Ok(found),
            Err(e) if is_connection_lost(&e) => {
                let mut conn = connect_conn(&self.opts(Role::Browse)).await?;
                let found = fetch_sets(&mut conn, sql, sets).await;
                *guard = Some(conn);
                Ok(found?)
            }
            Err(e) => Err(e.into()),
        }
    }
}

fn build_opts(p: &ConnectParams, role: Role, read_only: bool) -> Opts {
    let ssl = match p.tls {
        TlsMode::Off => None,
        TlsMode::Verify => Some(SslOpts::default().with_root_certs(vec![RDS_CA.into()])),
        TlsMode::EncryptOnly => Some(
            SslOpts::default()
                .with_danger_accept_invalid_certs(true)
                .with_danger_skip_domain_validation(true),
        ),
    };
    let mut setup = Vec::new();
    if let Role::Query = role {
        setup.push(QUERY_SETUP);
    }
    if read_only {
        setup.push(READ_ONLY);
    }
    // One statement list, so session setup stays one round trip.
    let init = if setup.is_empty() { Vec::new() } else { vec![setup.join("; ")] };
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
        // Browse applies row changes: count matched rows, so an update that writes a value
        // equal to the current one still reports its one row.
        .client_found_rows(matches!(role, Role::Browse))
        .init(init)
        .into()
}

async fn connect_conn(opts: &Opts) -> Result<Conn, DbyError> {
    match tokio::time::timeout(CONNECT_TIMEOUT, Conn::new(opts.clone())).await {
        Ok(conn) => Ok(conn?),
        Err(_) => Err(DbyError::Timeout),
    }
}

async fn fetch_sets(conn: &mut Conn, sql: &str, count: usize) -> Result<Vec<ResultSet>, mysql_async::Error> {
    let mut result = conn.query_iter(sql).await?;
    let mut sets = Vec::with_capacity(count);
    for _ in 0..count {
        let columns = result.columns().map(|c| c.to_vec()).unwrap_or_default();
        let rows: Vec<Row> = result.collect().await?;
        sets.push(ResultSet { columns, rows });
    }
    result.drop_result().await?;
    Ok(sets)
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

fn elapsed_ms(started: Instant) -> u32 {
    u32::try_from(started.elapsed().as_millis()).unwrap_or(u32::MAX)
}

fn closed() -> DbyError {
    DbyError::internal("session is disconnected")
}

fn column_out(col: &mysql_async::Column) -> ColumnOut {
    let type_name = format!("{:?}", col.column_type());
    ColumnOut {
        name: col.name_str().into_owned(),
        type_name: type_name.trim_start_matches("MYSQL_TYPE_").to_ascii_lowercase(),
    }
}
