//! Builds the SQL for one page of a table. Pure: no I/O, so it is unit-tested directly.

use std::collections::HashMap;
use std::sync::Arc;

use crate::error::DbyError;
use crate::value::Cell;

/// Characters (text) or bytes (binary) a page keeps per cell; the rest stays on the server.
pub const TRIM: u64 = 256;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Cursor {
    /// Primary-key values of the last row shown, in key order. Empty for tables without a key.
    pub after: Vec<Cell>,
    /// Row offset, used only for tables without a primary key.
    pub offset: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ColumnInfo {
    pub name: String,
    /// Lowercase `information_schema.COLUMNS.DATA_TYPE`, e.g. `varchar`.
    pub data_type: String,
    pub max_len: Option<u64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TableMeta {
    pub columns: Vec<ColumnInfo>,
    /// Indexes into `columns`, in primary-key order. Empty when the table has no primary key.
    pub pk: Vec<usize>,
}

/// One row of the schema query: a column, and its position in the primary key if it has one.
#[derive(Debug, Clone, PartialEq)]
pub struct ColumnRow {
    pub table: String,
    pub column: String,
    pub data_type: String,
    pub max_len: Option<u64>,
    pub pk_seq: Option<u32>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PageSql {
    pub sql: String,
    /// Per table column: true when the query returns it as two values, the trimmed value and
    /// its full length.
    pub trimmed: Vec<bool>,
}

/// Groups schema rows (already in column order) into one `TableMeta` per table.
pub fn build_tables(rows: Vec<ColumnRow>) -> HashMap<String, Arc<TableMeta>> {
    // Per table: its columns, and (key sequence, column index) for primary-key columns.
    type Building = (Vec<ColumnInfo>, Vec<(u32, usize)>);
    let mut building: HashMap<String, Building> = HashMap::new();
    for row in rows {
        let (columns, keys) = building.entry(row.table).or_default();
        if let Some(seq) = row.pk_seq {
            keys.push((seq, columns.len()));
        }
        columns.push(ColumnInfo { name: row.column, data_type: row.data_type.to_ascii_lowercase(), max_len: row.max_len });
    }
    building
        .into_iter()
        .map(|(table, (columns, mut keys))| {
            keys.sort_unstable();
            let pk = keys.into_iter().map(|(_, index)| index).collect();
            (table, Arc::new(TableMeta { columns, pk }))
        })
        .collect()
}

pub fn quote_ident(name: &str) -> String {
    format!("`{}`", name.replace('`', "``"))
}

/// The length function for a column the page should trim, or None to send it whole.
fn trim_with(c: &ColumnInfo) -> Option<&'static str> {
    let long = c.max_len.unwrap_or(0) > TRIM;
    match c.data_type.as_str() {
        "tinytext" | "text" | "mediumtext" | "longtext" | "json" => Some("CHAR_LENGTH"),
        "varchar" | "char" if long => Some("CHAR_LENGTH"),
        "tinyblob" | "blob" | "mediumblob" | "longblob" => Some("LENGTH"),
        "varbinary" | "binary" if long => Some("LENGTH"),
        _ => None,
    }
}

/// One page, fetching `limit + 1` rows: the extra row only says whether another page exists.
pub fn page_sql(table: &str, meta: &TableMeta, cursor: Option<&Cursor>, limit: u32) -> Result<PageSql, DbyError> {
    let mut select = Vec::with_capacity(meta.columns.len());
    let mut trimmed = Vec::with_capacity(meta.columns.len());
    for (index, column) in meta.columns.iter().enumerate() {
        let quoted = quote_ident(&column.name);
        // Key columns are sent whole: the next cursor is built from them.
        match trim_with(column).filter(|_| !meta.pk.contains(&index)) {
            Some(length_fn) => {
                select.push(format!("LEFT({quoted}, {TRIM}), {length_fn}({quoted})"));
                trimmed.push(true);
            }
            None => {
                select.push(quoted);
                trimmed.push(false);
            }
        }
    }
    let mut sql = format!("SELECT {} FROM {}", select.join(", "), quote_ident(table));
    let fetch = u64::from(limit) + 1;
    if meta.pk.is_empty() {
        let offset = cursor.map_or(0, |c| c.offset);
        sql.push_str(&format!(" LIMIT {fetch} OFFSET {offset}"));
        return Ok(PageSql { sql, trimmed });
    }
    let keys: Vec<String> = meta.pk.iter().map(|&i| quote_ident(&meta.columns[i].name)).collect();
    if let Some(cursor) = cursor {
        if cursor.after.len() != keys.len() {
            return Err(DbyError::internal("cursor does not match the table's primary key"));
        }
        let values = cursor.after.iter().map(literal).collect::<Result<Vec<_>, _>>()?;
        if keys.len() == 1 {
            sql.push_str(&format!(" WHERE {} > {}", keys[0], values[0]));
        } else {
            sql.push_str(&format!(" WHERE ({}) > ({})", keys.join(", "), values.join(", ")));
        }
    }
    sql.push_str(&format!(" ORDER BY {} LIMIT {fetch}", keys.join(", ")));
    Ok(PageSql { sql, trimmed })
}

/// The cursor for the page after `rows`. Call only when the server returned an extra row.
pub fn next_cursor(meta: &TableMeta, current: Option<&Cursor>, rows: &[Vec<Cell>], limit: u32) -> Option<Cursor> {
    if meta.pk.is_empty() {
        let offset = current.map_or(0, |c| c.offset) + u64::from(limit);
        return Some(Cursor { after: vec![], offset });
    }
    let last = rows.last()?;
    Some(Cursor { after: meta.pk.iter().map(|&i| last[i].clone()).collect(), offset: 0 })
}

/// A SQL literal for a key value that cannot carry SQL and does not depend on `sql_mode`.
/// Strings use an introducer, so the comparison runs under the column's own collation.
pub fn literal(cell: &Cell) -> Result<String, DbyError> {
    match cell {
        Cell::Signed { v } => Ok(v.to_string()),
        Cell::Unsigned { v } => Ok(v.to_string()),
        Cell::Real { v } if v.is_finite() => Ok(format!("{v:?}")),
        Cell::Exact { v } if is_plain_decimal(v) => Ok(v.clone()),
        Cell::Text { v, full_len } if *full_len == v.chars().count() as u64 => Ok(format!("_utf8mb4 X'{}'", hex(v.as_bytes()))),
        Cell::Temporal { v } => Ok(format!("_utf8mb4 X'{}'", hex(v.as_bytes()))),
        Cell::Bytes { len, preview } if *len == preview.len() as u64 => Ok(format!("X'{}'", hex(preview))),
        other => Err(DbyError::internal(format!("cannot page on key value {other:?}"))),
    }
}

fn is_plain_decimal(s: &str) -> bool {
    let unsigned = s.strip_prefix('-').unwrap_or(s);
    let (int, frac) = match unsigned.split_once('.') {
        Some((int, frac)) => (int, Some(frac)),
        None => (unsigned, None),
    };
    let digits = |part: &str| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit());
    digits(int) && frac.is_none_or(digits)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn col(name: &str, data_type: &str, max_len: Option<u64>) -> ColumnInfo {
        ColumnInfo { name: name.into(), data_type: data_type.into(), max_len }
    }

    fn orders() -> TableMeta {
        TableMeta {
            columns: vec![
                col("id", "bigint", None),
                col("customer", "varchar", Some(80)),
                col("status", "varchar", Some(16)),
                col("total", "decimal", None),
                col("notes", "text", Some(65535)),
                col("created_at", "datetime", None),
            ],
            pk: vec![0],
        }
    }

    #[test]
    fn quote_ident_doubles_backticks() {
        assert_eq!(quote_ident("a`b"), "`a``b`");
    }

    #[test]
    fn first_page_orders_by_key_and_trims_long_text() {
        let built = page_sql("orders", &orders(), None, 50).unwrap();
        assert_eq!(
            built.sql,
            "SELECT `id`, `customer`, `status`, `total`, LEFT(`notes`, 256), CHAR_LENGTH(`notes`), `created_at` \
             FROM `orders` ORDER BY `id` LIMIT 51"
        );
        assert_eq!(built.trimmed, vec![false, false, false, false, true, false]);
    }

    #[test]
    fn next_page_starts_after_the_cursor() {
        let cursor = Cursor { after: vec![Cell::Unsigned { v: 50 }], offset: 0 };
        let built = page_sql("orders", &orders(), Some(&cursor), 50).unwrap();
        assert!(built.sql.contains(" FROM `orders` WHERE `id` > 50 ORDER BY `id` LIMIT 51"), "{}", built.sql);
    }

    #[test]
    fn composite_key_uses_a_row_comparison() {
        let meta = TableMeta { columns: vec![col("a", "int", None), col("b", "int", None), col("v", "varchar", Some(10))], pk: vec![0, 1] };
        let cursor = Cursor { after: vec![Cell::Signed { v: 1 }, Cell::Signed { v: 9 }], offset: 0 };
        let built = page_sql("pairs", &meta, Some(&cursor), 7).unwrap();
        assert_eq!(built.sql, "SELECT `a`, `b`, `v` FROM `pairs` WHERE (`a`, `b`) > (1, 9) ORDER BY `a`, `b` LIMIT 8");
    }

    #[test]
    fn table_without_key_pages_by_offset() {
        let meta = TableMeta { columns: vec![col("v", "int", None)], pk: vec![] };
        let cursor = Cursor { after: vec![], offset: 30 };
        let built = page_sql("nopk", &meta, Some(&cursor), 30).unwrap();
        assert_eq!(built.sql, "SELECT `v` FROM `nopk` LIMIT 31 OFFSET 30");
    }

    #[test]
    fn key_columns_are_never_trimmed() {
        let meta = TableMeta { columns: vec![col("code", "varchar", Some(512)), col("body", "longtext", None)], pk: vec![0] };
        let built = page_sql("docs", &meta, None, 10).unwrap();
        assert_eq!(built.sql, "SELECT `code`, LEFT(`body`, 256), CHAR_LENGTH(`body`) FROM `docs` ORDER BY `code` LIMIT 11");
        assert_eq!(built.trimmed, vec![false, true]);
    }

    #[test]
    fn blobs_are_trimmed_by_byte_length() {
        let meta = TableMeta { columns: vec![col("id", "int", None), col("data", "longblob", None)], pk: vec![0] };
        let built = page_sql("files", &meta, None, 10).unwrap();
        assert!(built.sql.contains("LEFT(`data`, 256), LENGTH(`data`)"), "{}", built.sql);
    }

    #[test]
    fn literals_cannot_carry_sql() {
        assert_eq!(literal(&Cell::Signed { v: -3 }).unwrap(), "-3");
        assert_eq!(literal(&Cell::Unsigned { v: u64::MAX }).unwrap(), "18446744073709551615");
        assert_eq!(literal(&Cell::Real { v: 1.5 }).unwrap(), "1.5");
        assert_eq!(literal(&Cell::Exact { v: "-12.50".into() }).unwrap(), "-12.50");
        assert_eq!(literal(&Cell::Text { v: "Äpfel".into(), full_len: 5 }).unwrap(), "_utf8mb4 X'c3847066656c'");
        assert_eq!(literal(&Cell::Text { v: "".into(), full_len: 0 }).unwrap(), "_utf8mb4 X''");
        assert_eq!(literal(&Cell::Temporal { v: "2026-01-01".into() }).unwrap(), "_utf8mb4 X'323032362d30312d3031'");
        assert_eq!(literal(&Cell::Bytes { len: 2, preview: vec![0x00, 0xff] }).unwrap(), "X'00ff'");
    }

    #[test]
    fn unsafe_or_partial_key_values_are_rejected() {
        assert!(literal(&Cell::Null).is_err());
        assert!(literal(&Cell::Exact { v: "1; DROP TABLE x".into() }).is_err());
        assert!(literal(&Cell::Exact { v: "1e5".into() }).is_err());
        assert!(literal(&Cell::Exact { v: ".".into() }).is_err());
        assert!(literal(&Cell::Text { v: "abc".into(), full_len: 9 }).is_err());
        assert!(literal(&Cell::Bytes { len: 9, preview: vec![1] }).is_err());
        assert!(literal(&Cell::Real { v: f64::NAN }).is_err());
    }

    #[test]
    fn cursor_length_must_match_the_key() {
        let cursor = Cursor { after: vec![Cell::Signed { v: 1 }, Cell::Signed { v: 2 }], offset: 0 };
        assert!(page_sql("orders", &orders(), Some(&cursor), 50).is_err());
    }

    #[test]
    fn build_tables_orders_the_key_by_its_sequence() {
        let rows = vec![
            ColumnRow { table: "t".into(), column: "a".into(), data_type: "INT".into(), max_len: None, pk_seq: Some(2) },
            ColumnRow { table: "t".into(), column: "b".into(), data_type: "VARCHAR".into(), max_len: Some(10), pk_seq: Some(1) },
            ColumnRow { table: "u".into(), column: "x".into(), data_type: "int".into(), max_len: None, pk_seq: None },
        ];
        let tables = build_tables(rows);
        let t = &tables["t"];
        assert_eq!(t.columns.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(), ["a", "b"]);
        assert_eq!(t.columns[1].data_type, "varchar");
        assert_eq!(t.pk, vec![1, 0]);
        assert!(tables["u"].pk.is_empty());
    }

    #[test]
    fn next_cursor_takes_the_last_rows_key_or_advances_the_offset() {
        let rows = vec![
            vec![Cell::Unsigned { v: 99 }, Cell::Null, Cell::Null, Cell::Null, Cell::Null, Cell::Null],
            vec![Cell::Unsigned { v: 100 }, Cell::Null, Cell::Null, Cell::Null, Cell::Null, Cell::Null],
        ];
        assert_eq!(
            next_cursor(&orders(), None, &rows, 2),
            Some(Cursor { after: vec![Cell::Unsigned { v: 100 }], offset: 0 })
        );
        let nopk = TableMeta { columns: vec![col("v", "int", None)], pk: vec![] };
        let current = Cursor { after: vec![], offset: 30 };
        assert_eq!(next_cursor(&nopk, Some(&current), &[vec![Cell::Signed { v: 1 }]], 30), Some(Cursor { after: vec![], offset: 60 }));
    }
}
