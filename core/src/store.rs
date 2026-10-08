//! The phone's own state in one SQLite file: saved connections (passwords arrive already
//! encrypted by the Android Keystore), cached schemas, run history and saved queries.

use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{params, Connection, OptionalExtension, Row};

use crate::error::DbyError;
use crate::schema::Schema;
use crate::session::TlsMode;

/// History entries kept; older ones are deleted as new ones arrive.
const HISTORY_KEEP: i64 = 500;

const MIGRATION: &str = "
CREATE TABLE IF NOT EXISTS connections (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  host TEXT NOT NULL,
  port INTEGER NOT NULL,
  user_name TEXT NOT NULL,
  db_name TEXT NOT NULL,
  env TEXT NOT NULL,
  tls TEXT NOT NULL,
  pinned INTEGER NOT NULL DEFAULT 0,
  password BLOB NOT NULL,
  last_used_ms INTEGER,
  created_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS schemas (
  connection_id TEXT NOT NULL,
  db_name TEXT NOT NULL,
  json TEXT NOT NULL,
  saved_ms INTEGER NOT NULL,
  PRIMARY KEY (connection_id, db_name)
);
CREATE TABLE IF NOT EXISTS history (
  id INTEGER PRIMARY KEY,
  connection_id TEXT NOT NULL,
  db_name TEXT NOT NULL,
  sql TEXT NOT NULL,
  error TEXT,
  row_count INTEGER NOT NULL,
  elapsed_ms INTEGER NOT NULL,
  at_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS saved_queries (
  sql TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  at_ms INTEGER NOT NULL
);
";

const SELECT_CONNECTION: &str =
    "SELECT id, name, host, port, user_name, db_name, env, tls, pinned, password, last_used_ms FROM connections";

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Env {
    Prod,
    Staging,
    Dev,
    Local,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ConnectionInput {
    /// None saves a new connection; Some updates that one.
    pub id: Option<String>,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub user: String,
    pub database: String,
    pub env: Env,
    pub tls: TlsMode,
    /// The password encrypted with the app's Keystore key. The core never sees the key.
    pub password_cipher: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SavedConnection {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub user: String,
    pub database: String,
    pub env: Env,
    pub tls: TlsMode,
    pub pinned: bool,
    pub password_cipher: Vec<u8>,
    pub last_used_ms: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HistoryEntry {
    pub id: i64,
    pub connection_id: String,
    /// None once the connection has been deleted.
    pub connection_name: Option<String>,
    pub database: String,
    pub sql: String,
    /// None when it succeeded.
    pub error: Option<String>,
    /// Rows returned, or rows changed by a write.
    pub rows: u64,
    pub elapsed_ms: u32,
    pub at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SavedQuery {
    pub sql: String,
    pub title: String,
    pub at_ms: i64,
}

pub struct NewHistory {
    pub connection_id: String,
    pub database: String,
    pub sql: String,
    pub error: Option<String>,
    pub rows: u64,
    pub elapsed_ms: u32,
}

pub struct Store {
    db: Connection,
}

impl Store {
    pub fn open(path: &Path) -> Result<Store, DbyError> {
        Store::setup(Connection::open(path)?)
    }

    #[cfg(test)]
    pub fn open_in_memory() -> Result<Store, DbyError> {
        Store::setup(Connection::open_in_memory()?)
    }

    fn setup(db: Connection) -> Result<Store, DbyError> {
        db.execute_batch(MIGRATION)?;
        Ok(Store { db })
    }

    pub fn save_connection(&self, input: &ConnectionInput) -> Result<SavedConnection, DbyError> {
        let (env, tls) = (env_name(input.env), tls_name(input.tls));
        let id: String = match &input.id {
            Some(id) => {
                let changed = self.db.execute(
                    "UPDATE connections SET name = ?2, host = ?3, port = ?4, user_name = ?5, db_name = ?6, \
                     env = ?7, tls = ?8, password = ?9 WHERE id = ?1",
                    params![id, input.name, input.host, input.port, input.user, input.database, env, tls, input.password_cipher],
                )?;
                if changed == 0 {
                    return Err(not_found(id));
                }
                id.clone()
            }
            None => self.db.query_row(
                "INSERT INTO connections (id, name, host, port, user_name, db_name, env, tls, password, created_ms) \
                 VALUES (lower(hex(randomblob(16))), ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9) RETURNING id",
                params![input.name, input.host, input.port, input.user, input.database, env, tls, input.password_cipher, now_ms()],
                |row| row.get(0),
            )?,
        };
        self.connection(&id)
    }

    pub fn connection(&self, id: &str) -> Result<SavedConnection, DbyError> {
        self.db
            .query_row(&format!("{SELECT_CONNECTION} WHERE id = ?1"), [id], connection_from)
            .optional()?
            .ok_or_else(|| not_found(id))
    }

    /// Pinned first, then the most recently used (or created).
    pub fn connections(&self) -> Result<Vec<SavedConnection>, DbyError> {
        let mut statement = self.db.prepare(&format!(
            "{SELECT_CONNECTION} ORDER BY pinned DESC, COALESCE(last_used_ms, created_ms) DESC, rowid DESC"
        ))?;
        let rows = statement.query_map([], connection_from)?.collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn delete_connection(&self, id: &str) -> Result<(), DbyError> {
        self.db.execute("DELETE FROM schemas WHERE connection_id = ?1", [id])?;
        self.db.execute("DELETE FROM connections WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), DbyError> {
        match self.db.execute("UPDATE connections SET pinned = ?2 WHERE id = ?1", params![id, pinned])? {
            0 => Err(not_found(id)),
            _ => Ok(()),
        }
    }

    pub fn touch_connection(&self, id: &str) -> Result<(), DbyError> {
        self.db.execute("UPDATE connections SET last_used_ms = ?2 WHERE id = ?1", params![id, now_ms()])?;
        Ok(())
    }

    pub fn put_schema(&self, id: &str, schema: &Schema) -> Result<(), DbyError> {
        self.db.execute(
            "INSERT OR REPLACE INTO schemas (connection_id, db_name, json, saved_ms) VALUES (?1, ?2, ?3, ?4)",
            params![id, schema.database, serde_json::to_string(schema)?, now_ms()],
        )?;
        Ok(())
    }

    pub fn schema(&self, id: &str, database: &str) -> Result<Option<Schema>, DbyError> {
        let json: Option<String> = self
            .db
            .query_row("SELECT json FROM schemas WHERE connection_id = ?1 AND db_name = ?2", [id, database], |r| r.get(0))
            .optional()?;
        Ok(match json {
            Some(json) => Some(serde_json::from_str(&json)?),
            None => None,
        })
    }

    pub fn add_history(&self, entry: &NewHistory) -> Result<(), DbyError> {
        self.db.execute(
            "INSERT INTO history (connection_id, db_name, sql, error, row_count, elapsed_ms, at_ms) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                entry.connection_id,
                entry.database,
                entry.sql,
                entry.error,
                i64::try_from(entry.rows).unwrap_or(i64::MAX),
                entry.elapsed_ms,
                now_ms()
            ],
        )?;
        self.db.execute(
            "DELETE FROM history WHERE id <= (SELECT id FROM history ORDER BY id DESC LIMIT 1 OFFSET ?1)",
            [HISTORY_KEEP],
        )?;
        Ok(())
    }

    /// Newest first.
    pub fn history(&self, limit: u32) -> Result<Vec<HistoryEntry>, DbyError> {
        let mut statement = self.db.prepare(
            "SELECT h.id, h.connection_id, c.name, h.db_name, h.sql, h.error, h.row_count, h.elapsed_ms, h.at_ms \
             FROM history h LEFT JOIN connections c ON c.id = h.connection_id ORDER BY h.id DESC LIMIT ?1",
        )?;
        let rows = statement
            .query_map([limit], |r| {
                Ok(HistoryEntry {
                    id: r.get(0)?,
                    connection_id: r.get(1)?,
                    connection_name: r.get(2)?,
                    database: r.get(3)?,
                    sql: r.get(4)?,
                    error: r.get(5)?,
                    rows: u64::try_from(r.get::<_, i64>(6)?).unwrap_or(0),
                    elapsed_ms: r.get(7)?,
                    at_ms: r.get(8)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn delete_history(&self, id: i64) -> Result<(), DbyError> {
        self.db.execute("DELETE FROM history WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Saving the same SQL again renames it and moves it to the top.
    pub fn save_query(&self, title: &str, sql: &str) -> Result<(), DbyError> {
        self.db.execute(
            "INSERT INTO saved_queries (sql, title, at_ms) VALUES (?1, ?2, ?3) \
             ON CONFLICT(sql) DO UPDATE SET title = excluded.title, at_ms = excluded.at_ms",
            params![sql, title, now_ms()],
        )?;
        Ok(())
    }

    pub fn saved_queries(&self) -> Result<Vec<SavedQuery>, DbyError> {
        let mut statement = self.db.prepare("SELECT sql, title, at_ms FROM saved_queries ORDER BY at_ms DESC, rowid DESC")?;
        let rows = statement
            .query_map([], |r| Ok(SavedQuery { sql: r.get(0)?, title: r.get(1)?, at_ms: r.get(2)? }))?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn delete_saved_query(&self, sql: &str) -> Result<(), DbyError> {
        self.db.execute("DELETE FROM saved_queries WHERE sql = ?1", [sql])?;
        Ok(())
    }
}

fn connection_from(row: &Row) -> rusqlite::Result<SavedConnection> {
    Ok(SavedConnection {
        id: row.get(0)?,
        name: row.get(1)?,
        host: row.get(2)?,
        port: row.get(3)?,
        user: row.get(4)?,
        database: row.get(5)?,
        env: env_from(&row.get::<_, String>(6)?),
        tls: tls_from(&row.get::<_, String>(7)?),
        pinned: row.get(8)?,
        password_cipher: row.get(9)?,
        last_used_ms: row.get(10)?,
    })
}

fn not_found(id: &str) -> DbyError {
    DbyError::NotFound { detail: format!("saved connection {id}") }
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

fn env_name(env: Env) -> &'static str {
    match env {
        Env::Prod => "prod",
        Env::Staging => "staging",
        Env::Dev => "dev",
        Env::Local => "local",
    }
}

fn env_from(name: &str) -> Env {
    match name {
        "prod" => Env::Prod,
        "staging" => Env::Staging,
        "dev" => Env::Dev,
        _ => Env::Local,
    }
}

fn tls_name(tls: TlsMode) -> &'static str {
    match tls {
        TlsMode::Verify => "verify",
        TlsMode::EncryptOnly => "encrypt_only",
        TlsMode::Off => "off",
    }
}

/// Unknown text falls back to the safest mode.
fn tls_from(name: &str) -> TlsMode {
    match name {
        "encrypt_only" => TlsMode::EncryptOnly,
        "off" => TlsMode::Off,
        _ => TlsMode::Verify,
    }
}

static STORE: OnceLock<Mutex<Store>> = OnceLock::new();

pub(crate) fn with_store<T>(f: impl FnOnce(&Store) -> Result<T, DbyError>) -> Result<T, DbyError> {
    let store = STORE.get().ok_or_else(|| DbyError::Storage { detail: "the store is not open; call open_store first".into() })?;
    let guard = store.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    f(&guard)
}

/// Opens (creating if needed) `<data_dir>/dby.sqlite3`. Called once at app start; later
/// calls keep the first store.
#[uniffi::export]
pub fn open_store(data_dir: String) -> Result<(), DbyError> {
    if STORE.get().is_none() {
        let store = Store::open(&Path::new(&data_dir).join("dby.sqlite3"))?;
        let _ = STORE.set(Mutex::new(store));
    }
    Ok(())
}

#[uniffi::export]
pub fn list_connections() -> Result<Vec<SavedConnection>, DbyError> {
    with_store(|s| s.connections())
}

#[uniffi::export]
pub fn save_connection(input: ConnectionInput) -> Result<SavedConnection, DbyError> {
    with_store(|s| s.save_connection(&input))
}

#[uniffi::export]
pub fn delete_connection(id: String) -> Result<(), DbyError> {
    with_store(|s| s.delete_connection(&id))
}

#[uniffi::export]
pub fn set_pinned(id: String, pinned: bool) -> Result<(), DbyError> {
    with_store(|s| s.set_pinned(&id, pinned))
}

/// The last schema read for this connection and database, instantly, without the network.
#[uniffi::export]
pub fn cached_schema(id: String, database: String) -> Option<Schema> {
    with_store(|s| s.schema(&id, &database)).ok().flatten()
}

#[uniffi::export]
pub fn history(limit: u32) -> Result<Vec<HistoryEntry>, DbyError> {
    with_store(|s| s.history(limit))
}

#[uniffi::export]
pub fn delete_history(id: i64) -> Result<(), DbyError> {
    with_store(|s| s.delete_history(id))
}

#[uniffi::export]
pub fn saved_queries() -> Result<Vec<SavedQuery>, DbyError> {
    with_store(|s| s.saved_queries())
}

#[uniffi::export]
pub fn save_query(title: String, sql: String) -> Result<(), DbyError> {
    with_store(|s| s.save_query(&title, &sql))
}

#[uniffi::export]
pub fn delete_saved_query(sql: String) -> Result<(), DbyError> {
    with_store(|s| s.delete_saved_query(&sql))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{ColumnDef, TableInfo};

    fn input(name: &str) -> ConnectionInput {
        ConnectionInput {
            id: None,
            name: name.into(),
            host: "db.example".into(),
            port: 3306,
            user: "app".into(),
            database: "shop".into(),
            env: Env::Prod,
            tls: TlsMode::Verify,
            password_cipher: vec![1, 2, 3],
        }
    }

    fn entry(id: &str, sql: &str, error: Option<&str>) -> NewHistory {
        NewHistory { connection_id: id.into(), database: "shop".into(), sql: sql.into(), error: error.map(Into::into), rows: 3, elapsed_ms: 12 }
    }

    #[test]
    fn saves_updates_lists_and_deletes_connections() {
        let store = Store::open_in_memory().unwrap();
        let a = store.save_connection(&input("A")).unwrap();
        assert_eq!(a.id.len(), 32);
        assert_eq!((a.name.as_str(), a.port, a.env, a.tls, a.pinned), ("A", 3306, Env::Prod, TlsMode::Verify, false));
        assert_eq!(a.password_cipher, vec![1, 2, 3]);
        assert_eq!(a.last_used_ms, None);

        let mut changed = input("A2");
        changed.id = Some(a.id.clone());
        changed.env = Env::Dev;
        changed.tls = TlsMode::EncryptOnly;
        let a2 = store.save_connection(&changed).unwrap();
        assert_eq!((a2.id.as_str(), a2.name.as_str(), a2.env, a2.tls), (a.id.as_str(), "A2", Env::Dev, TlsMode::EncryptOnly));
        assert_eq!(store.connections().unwrap().len(), 1);

        store.touch_connection(&a.id).unwrap();
        assert!(store.connection(&a.id).unwrap().last_used_ms.is_some());

        store.delete_connection(&a.id).unwrap();
        assert!(store.connections().unwrap().is_empty());
        assert!(matches!(store.connection(&a.id), Err(DbyError::NotFound { .. })));
    }

    #[test]
    fn updating_or_pinning_a_missing_connection_is_not_found() {
        let store = Store::open_in_memory().unwrap();
        let mut ghost = input("G");
        ghost.id = Some("nope".into());
        assert!(matches!(store.save_connection(&ghost), Err(DbyError::NotFound { .. })));
        assert!(matches!(store.set_pinned("nope", true), Err(DbyError::NotFound { .. })));
    }

    #[test]
    fn pinned_connections_come_first() {
        let store = Store::open_in_memory().unwrap();
        let a = store.save_connection(&input("A")).unwrap();
        store.save_connection(&input("B")).unwrap();
        store.save_connection(&input("C")).unwrap();
        assert_eq!(store.connections().unwrap().iter().map(|c| c.name.as_str()).collect::<Vec<_>>(), ["C", "B", "A"]);
        store.set_pinned(&a.id, true).unwrap();
        let names: Vec<String> = store.connections().unwrap().into_iter().map(|c| c.name).collect();
        assert_eq!(names, ["A", "C", "B"]);
    }

    #[test]
    fn schema_cache_round_trips_and_goes_with_its_connection() {
        let store = Store::open_in_memory().unwrap();
        let a = store.save_connection(&input("A")).unwrap();
        let schema = Schema {
            database: "shop".into(),
            routines: 1,
            tables: vec![TableInfo {
                name: "orders".into(),
                is_view: false,
                rows_estimate: Some(5),
                bytes: None,
                columns: vec![ColumnDef {
                    name: "id".into(),
                    data_type: "int".into(),
                    column_type: "int".into(),
                    nullable: false,
                    pk_seq: Some(1),
                    max_len: None,
                    enum_values: vec![],
                }],
            }],
        };
        store.put_schema(&a.id, &schema).unwrap();
        assert_eq!(store.schema(&a.id, "shop").unwrap(), Some(schema));
        assert_eq!(store.schema(&a.id, "other").unwrap(), None);
        store.delete_connection(&a.id).unwrap();
        assert_eq!(store.schema(&a.id, "shop").unwrap(), None);
    }

    #[test]
    fn history_is_newest_first_named_and_capped() {
        let store = Store::open_in_memory().unwrap();
        let a = store.save_connection(&input("A")).unwrap();
        store.add_history(&entry(&a.id, "SELECT 1", None)).unwrap();
        store.add_history(&entry(&a.id, "SELECT 2", Some("boom"))).unwrap();
        let all = store.history(10).unwrap();
        assert_eq!(all.iter().map(|h| h.sql.as_str()).collect::<Vec<_>>(), ["SELECT 2", "SELECT 1"]);
        assert_eq!(all[0].error.as_deref(), Some("boom"));
        assert_eq!(all[1].error, None);
        assert_eq!((all[1].rows, all[1].elapsed_ms), (3, 12));
        assert_eq!(all[0].connection_name.as_deref(), Some("A"));
        store.delete_history(all[0].id).unwrap();
        assert_eq!(store.history(10).unwrap().len(), 1);
        for i in 0..HISTORY_KEEP + 5 {
            store.add_history(&entry(&a.id, &format!("SELECT {i}"), None)).unwrap();
        }
        assert_eq!(store.history(1000).unwrap().len() as i64, HISTORY_KEEP);
    }

    #[test]
    fn saved_queries_upsert_by_sql() {
        let store = Store::open_in_memory().unwrap();
        store.save_query("first", "SELECT 1").unwrap();
        store.save_query("second", "SELECT 2").unwrap();
        store.save_query("renamed", "SELECT 1").unwrap();
        let saved = store.saved_queries().unwrap();
        assert_eq!(saved.len(), 2);
        assert_eq!(saved.iter().find(|q| q.sql == "SELECT 1").unwrap().title, "renamed");
        store.delete_saved_query("SELECT 1").unwrap();
        assert_eq!(store.saved_queries().unwrap().len(), 1);
    }
}
