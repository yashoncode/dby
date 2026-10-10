//! Turns MySQL wire values into `Cell`s without losing precision.

use mysql_async::consts::{ColumnFlags, ColumnType};
use mysql_async::{Column, Value};

/// Characters or bytes a cell keeps when the client trims it (results of the user's own SQL).
pub const CLIENT_CAP: usize = 256;

/// MySQL's charset number for binary strings.
const BINARY_CHARSET: u16 = 63;

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum Cell {
    Null,
    Signed { v: i64 },
    Unsigned { v: u64 },
    /// FLOAT and DOUBLE only.
    Real { v: f64 },
    /// DECIMAL, and any number whose text does not parse.
    Exact { v: String },
    /// `full_len` counts characters; more than `v` holds means the value was trimmed.
    Text { v: String, full_len: u64 },
    /// DATE, TIME, DATETIME, TIMESTAMP as the server writes them.
    Temporal { v: String },
    Bytes { len: u64, preview: Vec<u8> },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Integer,
    Float,
    Decimal,
    Temporal,
    Text,
    Binary,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColMeta {
    pub kind: Kind,
    pub unsigned: bool,
}

impl ColMeta {
    pub const TEXT: ColMeta = ColMeta { kind: Kind::Text, unsigned: false };
}

pub fn meta_of(col: &Column) -> ColMeta {
    use ColumnType::*;
    let kind = match col.column_type() {
        MYSQL_TYPE_TINY | MYSQL_TYPE_SHORT | MYSQL_TYPE_LONG | MYSQL_TYPE_INT24 | MYSQL_TYPE_LONGLONG
        | MYSQL_TYPE_YEAR => Kind::Integer,
        MYSQL_TYPE_FLOAT | MYSQL_TYPE_DOUBLE => Kind::Float,
        MYSQL_TYPE_DECIMAL | MYSQL_TYPE_NEWDECIMAL => Kind::Decimal,
        MYSQL_TYPE_DATE | MYSQL_TYPE_NEWDATE | MYSQL_TYPE_TIME | MYSQL_TYPE_TIME2 | MYSQL_TYPE_DATETIME
        | MYSQL_TYPE_DATETIME2 | MYSQL_TYPE_TIMESTAMP | MYSQL_TYPE_TIMESTAMP2 => Kind::Temporal,
        // MySQL sends JSON with the binary charset, but it is text.
        MYSQL_TYPE_JSON => Kind::Text,
        MYSQL_TYPE_BIT | MYSQL_TYPE_GEOMETRY => Kind::Binary,
        _ if col.character_set() == BINARY_CHARSET => Kind::Binary,
        _ => Kind::Text,
    };
    ColMeta { kind, unsigned: col.flags().contains(ColumnFlags::UNSIGNED_FLAG) }
}

/// Trims a text cell longer than [CLIENT_CAP] characters and hands back the whole text, so a
/// result of the user's SQL can keep it aside for when they open the value.
pub fn cap_text(cell: Cell) -> (Cell, Option<String>) {
    match cell {
        Cell::Text { v, full_len } if full_len > CLIENT_CAP as u64 => {
            (Cell::Text { v: v.chars().take(CLIENT_CAP).collect(), full_len }, Some(v))
        }
        other => (other, None),
    }
}

/// `server_full_len` is the length the server reported for a value it trimmed.
/// `client_cap` trims long bytes here instead, for SQL the user wrote (text goes through [cap_text]).
pub fn decode(value: Value, meta: &ColMeta, server_full_len: Option<u64>, client_cap: bool) -> Cell {
    match value {
        Value::NULL => Cell::Null,
        Value::Int(v) => Cell::Signed { v },
        Value::UInt(v) => Cell::Unsigned { v },
        Value::Float(v) => Cell::Real { v: f64::from(v) },
        Value::Double(v) => Cell::Real { v },
        Value::Date(y, mo, d, h, mi, s, us) => Cell::Temporal { v: date_text(y, mo, d, h, mi, s, us) },
        Value::Time(neg, days, h, mi, s, us) => Cell::Temporal { v: time_text(neg, days, h, mi, s, us) },
        Value::Bytes(b) => from_text(b, meta, server_full_len, client_cap),
    }
}

/// Reads a length the server sent alongside a trimmed value.
pub fn as_len(value: &Value) -> Option<u64> {
    match value {
        Value::Bytes(b) => std::str::from_utf8(b).ok()?.parse().ok(),
        Value::Int(v) => u64::try_from(*v).ok(),
        Value::UInt(v) => Some(*v),
        _ => None,
    }
}

/// The text protocol sends every value as bytes; the column type says what they mean.
fn from_text(b: Vec<u8>, meta: &ColMeta, server_full_len: Option<u64>, client_cap: bool) -> Cell {
    match meta.kind {
        Kind::Integer => {
            let s = lossy(b);
            let parsed = if meta.unsigned {
                s.parse().ok().map(|v| Cell::Unsigned { v })
            } else {
                s.parse().ok().map(|v| Cell::Signed { v })
            };
            parsed.unwrap_or(Cell::Exact { v: s })
        }
        Kind::Float => {
            let s = lossy(b);
            match s.parse::<f64>() {
                Ok(v) if v.is_finite() => Cell::Real { v },
                _ => Cell::Exact { v: s },
            }
        }
        Kind::Decimal => Cell::Exact { v: lossy(b) },
        Kind::Temporal => Cell::Temporal { v: lossy(b) },
        Kind::Binary => {
            let len = server_full_len.unwrap_or(b.len() as u64);
            let mut preview = b;
            if client_cap {
                preview.truncate(CLIENT_CAP);
            }
            Cell::Bytes { len, preview }
        }
        Kind::Text => {
            let s = lossy(b);
            let chars = s.chars().count() as u64;
            Cell::Text { v: s, full_len: server_full_len.unwrap_or(chars) }
        }
    }
}

fn lossy(b: Vec<u8>) -> String {
    String::from_utf8(b).unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned())
}

fn date_text(y: u16, mo: u8, d: u8, h: u8, mi: u8, s: u8, us: u32) -> String {
    let base = format!("{y:04}-{mo:02}-{d:02} {h:02}:{mi:02}:{s:02}");
    if us == 0 { base } else { format!("{base}.{us:06}") }
}

fn time_text(neg: bool, days: u32, h: u8, mi: u8, s: u8, us: u32) -> String {
    let hours = days * 24 + u32::from(h);
    let sign = if neg { "-" } else { "" };
    let base = format!("{sign}{hours:02}:{mi:02}:{s:02}");
    if us == 0 { base } else { format!("{base}.{us:06}") }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mysql_async::consts::{ColumnFlags, ColumnType};
    use mysql_async::{Column, Value};

    const UTF8MB4: u16 = 255;
    const BIN: u16 = 63;

    fn meta(t: ColumnType, flags: ColumnFlags, charset: u16) -> ColMeta {
        meta_of(&Column::new(t).with_flags(flags).with_character_set(charset))
    }

    fn text_col() -> ColMeta {
        meta(ColumnType::MYSQL_TYPE_VAR_STRING, ColumnFlags::empty(), UTF8MB4)
    }

    fn bytes(s: &str) -> Value {
        Value::Bytes(s.as_bytes().to_vec())
    }

    #[test]
    fn unsigned_bigint_max_stays_exact() {
        let m = meta(ColumnType::MYSQL_TYPE_LONGLONG, ColumnFlags::UNSIGNED_FLAG, BIN);
        assert_eq!(decode(bytes("18446744073709551615"), &m, None, false), Cell::Unsigned { v: u64::MAX });
    }

    #[test]
    fn signed_bigint_min_stays_exact() {
        let m = meta(ColumnType::MYSQL_TYPE_LONGLONG, ColumnFlags::empty(), BIN);
        assert_eq!(decode(bytes("-9223372036854775808"), &m, None, false), Cell::Signed { v: i64::MIN });
    }

    #[test]
    fn decimal_travels_as_exact_text() {
        let m = meta(ColumnType::MYSQL_TYPE_NEWDECIMAL, ColumnFlags::empty(), BIN);
        let v = "12345678901234567890123456789012345.123456789012345678901234567890";
        assert_eq!(decode(bytes(v), &m, None, false), Cell::Exact { v: v.to_string() });
    }

    #[test]
    fn double_becomes_real() {
        let m = meta(ColumnType::MYSQL_TYPE_DOUBLE, ColumnFlags::empty(), BIN);
        assert_eq!(decode(bytes("1.5"), &m, None, false), Cell::Real { v: 1.5 });
    }

    #[test]
    fn json_is_text_even_with_binary_charset() {
        let m = meta(ColumnType::MYSQL_TYPE_JSON, ColumnFlags::empty(), BIN);
        assert_eq!(m.kind, Kind::Text);
        assert_eq!(decode(bytes("{\"a\":1}"), &m, None, false), Cell::Text { v: "{\"a\":1}".into(), full_len: 7 });
    }

    #[test]
    fn blob_with_binary_charset_is_bytes_and_with_utf8_is_text() {
        assert_eq!(meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), BIN).kind, Kind::Binary);
        assert_eq!(meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), UTF8MB4).kind, Kind::Text);
    }

    #[test]
    fn zero_date_passes_through_untouched() {
        let m = meta(ColumnType::MYSQL_TYPE_DATETIME, ColumnFlags::empty(), BIN);
        assert_eq!(decode(bytes("0000-00-00 00:00:00"), &m, None, false), Cell::Temporal { v: "0000-00-00 00:00:00".into() });
    }

    #[test]
    fn invalid_utf8_is_replaced_not_dropped() {
        let v = Value::Bytes(vec![b'a', 0xff, b'b']);
        assert_eq!(decode(v, &text_col(), None, false), Cell::Text { v: "a\u{FFFD}b".into(), full_len: 3 });
    }

    #[test]
    fn client_cap_trims_text_and_bytes() {
        let long = "é".repeat(1000);
        assert_eq!(
            cap_text(decode(bytes(&long), &text_col(), None, true)),
            (Cell::Text { v: "é".repeat(CLIENT_CAP), full_len: 1000 }, Some(long))
        );
        let short = Cell::Text { v: "é".repeat(CLIENT_CAP), full_len: CLIENT_CAP as u64 };
        assert_eq!(cap_text(short.clone()), (short, None));
        let m = meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), BIN);
        assert_eq!(
            decode(Value::Bytes(vec![7; 1000]), &m, None, true),
            Cell::Bytes { len: 1000, preview: vec![7; CLIENT_CAP] }
        );
    }

    #[test]
    fn server_trimmed_values_keep_their_full_length() {
        assert_eq!(
            decode(bytes(&"x".repeat(256)), &text_col(), Some(5000), false),
            Cell::Text { v: "x".repeat(256), full_len: 5000 }
        );
        let m = meta(ColumnType::MYSQL_TYPE_BLOB, ColumnFlags::empty(), BIN);
        assert_eq!(decode(Value::Bytes(vec![1; 256]), &m, Some(1000), false), Cell::Bytes { len: 1000, preview: vec![1; 256] });
    }

    #[test]
    fn untrimmed_short_values_are_kept_whole() {
        assert_eq!(decode(bytes("abc"), &text_col(), None, false), Cell::Text { v: "abc".into(), full_len: 3 });
    }

    #[test]
    fn null_is_null() {
        assert_eq!(decode(Value::NULL, &text_col(), None, true), Cell::Null);
    }

    #[test]
    fn binary_protocol_values() {
        let m = text_col();
        assert_eq!(decode(Value::Int(-5), &m, None, false), Cell::Signed { v: -5 });
        assert_eq!(decode(Value::UInt(7), &m, None, false), Cell::Unsigned { v: 7 });
        assert_eq!(decode(Value::Double(2.25), &m, None, false), Cell::Real { v: 2.25 });
        assert_eq!(
            decode(Value::Date(2026, 10, 8, 14, 22, 8, 0), &m, None, false),
            Cell::Temporal { v: "2026-10-08 14:22:08".into() }
        );
        assert_eq!(
            decode(Value::Time(true, 1, 2, 3, 4, 500), &m, None, false),
            Cell::Temporal { v: "-26:03:04.000500".into() }
        );
    }

    #[test]
    fn as_len_reads_text_and_numbers() {
        assert_eq!(as_len(&bytes("5000")), Some(5000));
        assert_eq!(as_len(&Value::Int(12)), Some(12));
        assert_eq!(as_len(&Value::NULL), None);
    }
}
