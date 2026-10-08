//! DBY's core: the MySQL/MariaDB client the Android app calls through UniFFI.

uniffi::setup_scaffolding!();

mod error;
mod paging;
mod session;
mod value;

pub use error::DbyError;
pub use paging::Cursor;
pub use session::{open_session, ConnectParams, ServerInfo, Session, TlsMode};
pub use value::Cell;

/// The core's version, shown by the app so a stale native library is easy to spot.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
