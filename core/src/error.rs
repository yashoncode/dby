use mysql_async::{DriverError, Error};

/// Every failure the core reports. Text fields are `detail`, never `message`:
/// UniFFI turns this into a Kotlin exception, where `message` is already taken.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum DbyError {
    #[error("could not reach the server: {detail}")]
    Network { detail: String },
    #[error("TLS failed: {detail}")]
    Tls { detail: String },
    #[error("wrong user or password: {detail}")]
    Auth { detail: String },
    #[error("unknown database: {detail}")]
    UnknownDatabase { detail: String },
    #[error("server error {code}: {detail}")]
    Server { code: u16, detail: String },
    #[error("not found: {detail}")]
    NotFound { detail: String },
    #[error("cancelled")]
    Cancelled,
    #[error("timed out")]
    Timeout,
    #[error("internal error: {detail}")]
    Internal { detail: String },
}

impl DbyError {
    pub(crate) fn internal(detail: impl Into<String>) -> Self {
        DbyError::Internal { detail: detail.into() }
    }
}

impl From<Error> for DbyError {
    fn from(e: Error) -> Self {
        match e {
            Error::Server(s) => match s.code {
                1045 | 1698 => DbyError::Auth { detail: s.message },
                1049 => DbyError::UnknownDatabase { detail: s.message },
                1317 => DbyError::Cancelled,
                code => DbyError::Server { code, detail: s.message },
            },
            Error::Driver(DriverError::NoClientSslFlagFromServer) => {
                DbyError::Tls { detail: "the server does not offer TLS".into() }
            }
            Error::Io(io) => {
                let detail = io.to_string();
                // ponytail: TLS failures are told apart by text, because IoError's TLS variant
                // depends on mysql_async's feature flags; match the variant once it is stable API.
                let lower = detail.to_ascii_lowercase();
                if lower.contains("certificate") || lower.contains("tls") || lower.contains("handshake") {
                    DbyError::Tls { detail }
                } else {
                    DbyError::Network { detail }
                }
            }
            other => DbyError::Internal { detail: other.to_string() },
        }
    }
}

/// True when the connection itself is gone (network drop, server restart, `KILL`, idle
/// timeout), as opposed to an error in the statement. Codes: 1053 server shutdown,
/// 1927 MariaDB connection killed, 3169 MySQL session killed, 4031 MySQL idle disconnect.
pub(crate) fn is_connection_lost(e: &Error) -> bool {
    match e {
        Error::Io(_) => true,
        Error::Driver(DriverError::ConnectionClosed) => true,
        Error::Server(s) => matches!(s.code, 1053 | 1927 | 3169 | 4031),
        _ => false,
    }
}
