//! The current database's tables, views and columns, read in one round trip and cached on
//! the phone so the Explorer draws before the network answers (spec §6 rule 6).

use std::collections::HashMap;

use mysql_async::{Row, Value};
use serde::{Deserialize, Serialize};

/// Four result sets: the database name; its tables and views; every column with its
/// primary-key position; the number of routines.
pub const SCHEMA_SQL: &str = "SELECT DATABASE(); \
    SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS, DATA_LENGTH + INDEX_LENGTH \
    FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME; \
    SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.COLUMN_TYPE, c.CHARACTER_MAXIMUM_LENGTH, \
           c.IS_NULLABLE, s.SEQ_IN_INDEX \
    FROM information_schema.COLUMNS c \
    LEFT JOIN information_schema.STATISTICS s \
      ON s.TABLE_SCHEMA = c.TABLE_SCHEMA AND s.TABLE_NAME = c.TABLE_NAME \
     AND s.COLUMN_NAME = c.COLUMN_NAME AND s.INDEX_NAME = 'PRIMARY' \
    WHERE c.TABLE_SCHEMA = DATABASE() \
    ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION; \
    SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Schema {
    pub database: String,
    /// Tables and views, by name.
    pub tables: Vec<TableInfo>,
    /// Stored procedures and functions.
    pub routines: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct TableInfo {
    pub name: String,
    pub is_view: bool,
    /// `information_schema.TABLES.TABLE_ROWS`: an estimate for InnoDB, shown with `~`.
    pub rows_estimate: Option<u64>,
    /// Data plus index length.
    pub bytes: Option<u64>,
    pub columns: Vec<ColumnDef>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ColumnDef {
    pub name: String,
    /// Lowercase base type, e.g. `varchar`.
    pub data_type: String,
    /// The type as declared, e.g. `varchar(80)` or `enum('a','b')`.
    pub column_type: String,
    pub nullable: bool,
    /// Position in the primary key, from 1; None when the column is not part of it.
    pub pk_seq: Option<u32>,
    pub max_len: Option<u64>,
    /// An ENUM column's allowed values; empty for every other type.
    pub enum_values: Vec<String>,
}

pub fn from_rows(database: &[Row], tables: &[Row], columns: &[Row], routines: &[Row]) -> Schema {
    let mut by_table: HashMap<String, Vec<ColumnDef>> = HashMap::new();
    for r in columns {
        let column_type = text_at(r, 3).unwrap_or_default();
        by_table.entry(text_at(r, 0).unwrap_or_default()).or_default().push(ColumnDef {
            name: text_at(r, 1).unwrap_or_default(),
            data_type: text_at(r, 2).unwrap_or_default().to_ascii_lowercase(),
            enum_values: enum_values(&column_type),
            column_type,
            max_len: number_at(r, 4),
            nullable: text_at(r, 5).as_deref() == Some("YES"),
            pk_seq: number_at(r, 6).and_then(|n| u32::try_from(n).ok()),
        });
    }
    let tables = tables
        .iter()
        .map(|r| {
            let name = text_at(r, 0).unwrap_or_default();
            let kind = text_at(r, 1).unwrap_or_default();
            TableInfo {
                columns: by_table.remove(&name).unwrap_or_default(),
                is_view: kind.ends_with("VIEW"),
                rows_estimate: number_at(r, 2),
                bytes: number_at(r, 3),
                name,
            }
        })
        .collect();
    Schema {
        database: database.first().and_then(|r| text_at(r, 0)).unwrap_or_default(),
        tables,
        routines: routines.first().and_then(|r| number_at(r, 0)).unwrap_or(0) as u32,
    }
}

/// `enum('new','it''s')` → `["new", "it's"]`; anything else → empty.
pub fn enum_values(column_type: &str) -> Vec<String> {
    let is_enum = column_type.get(..5).is_some_and(|p| p.eq_ignore_ascii_case("enum("));
    let Some(inner) = column_type.get(5..).filter(|_| is_enum).and_then(|s| s.strip_suffix(')')) else {
        return Vec::new();
    };
    let mut values = Vec::new();
    let mut chars = inner.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '\'' {
            continue; // the commas between values
        }
        let mut value = String::new();
        loop {
            match chars.next() {
                Some('\'') if chars.peek() == Some(&'\'') => {
                    chars.next();
                    value.push('\'');
                }
                Some('\'') | None => break,
                Some(other) => value.push(other),
            }
        }
        values.push(value);
    }
    values
}

pub(crate) fn text_at(row: &Row, index: usize) -> Option<String> {
    match row.as_ref(index)? {
        Value::Bytes(b) => Some(String::from_utf8_lossy(b).into_owned()),
        Value::Int(v) => Some(v.to_string()),
        Value::UInt(v) => Some(v.to_string()),
        _ => None,
    }
}

pub(crate) fn number_at(row: &Row, index: usize) -> Option<u64> {
    text_at(row, index)?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use mysql_async::consts::ColumnType;
    use mysql_async::Column;
    use std::sync::Arc;

    /// A text-protocol row: every value arrives as bytes, NULL as NULL.
    fn row(values: &[Option<&str>]) -> Row {
        let columns: Arc<[Column]> = values.iter().map(|_| Column::new(ColumnType::MYSQL_TYPE_VAR_STRING)).collect();
        let values = values.iter().map(|v| v.map_or(Value::NULL, |s| Value::Bytes(s.as_bytes().to_vec()))).collect();
        mysql_common::row::new_row(values, columns)
    }

    #[test]
    fn enum_values_are_unquoted() {
        assert_eq!(enum_values("enum('new','used','it''s')"), ["new", "used", "it's"]);
        assert_eq!(enum_values("ENUM('a')"), ["a"]);
        assert!(enum_values("varchar(20)").is_empty());
        assert!(enum_values("enum").is_empty());
    }

    #[test]
    fn columns_land_under_their_tables_in_table_order() {
        let schema = from_rows(
            &[row(&[Some("shop")])],
            &[
                row(&[Some("b_view"), Some("VIEW"), None, None]),
                row(&[Some("orders"), Some("BASE TABLE"), Some("100"), Some("4096")]),
            ],
            &[
                row(&[Some("b_view"), Some("id"), Some("bigint"), Some("bigint"), None, Some("NO"), None]),
                row(&[Some("orders"), Some("id"), Some("BIGINT"), Some("bigint unsigned"), None, Some("NO"), Some("1")]),
                row(&[Some("orders"), Some("state"), Some("enum"), Some("enum('a','b')"), Some("1"), Some("YES"), None]),
            ],
            &[row(&[Some("2")])],
        );
        assert_eq!(schema.database, "shop");
        assert_eq!(schema.routines, 2);
        assert_eq!(schema.tables.iter().map(|t| t.name.as_str()).collect::<Vec<_>>(), ["b_view", "orders"]);
        assert!(schema.tables[0].is_view);
        assert!(!schema.tables[1].is_view);
        assert_eq!(schema.tables[1].rows_estimate, Some(100));
        assert_eq!(schema.tables[1].bytes, Some(4096));
        let id = &schema.tables[1].columns[0];
        assert_eq!((id.data_type.as_str(), id.pk_seq, id.nullable), ("bigint", Some(1), false));
        assert_eq!(schema.tables[1].columns[1].enum_values, ["a", "b"]);
        assert!(schema.tables[1].columns[1].nullable);
    }
}
