//! DBY's core: the MySQL/MariaDB client the Android app calls through UniFFI.

uniffi::setup_scaffolding!();

mod classify;
mod edit;
mod error;
mod paging;
mod schema;
mod session;
mod store;
mod value;

pub use classify::{classify_sql, SqlKind};
pub use edit::{ChangeKind, FieldValue, RowChange};
pub use error::DbyError;
pub use paging::{Cursor, Filter, FilterOp, SelectSpec, Sort};
pub use schema::{ColumnDef, Schema, TableInfo};
pub use session::{
    connect, open_session, CellRequest, ColumnOut, ConnectParams, CountRequest, FacetCount, FacetRequest, Page,
    PageRequest, QueryResult, ServerInfo, Session, TlsMode,
};
pub use store::{
    cached_schema, delete_connection, delete_history, delete_saved_query, history, list_connections, open_store,
    save_connection, save_query, saved_queries, set_pinned, ConnectionInput, Env, HistoryEntry, SavedConnection,
    SavedQuery,
};
pub use value::Cell;

/// The core's version, shown by the app so a stale native library is easy to spot.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
