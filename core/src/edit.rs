//! Row changes from the Edit row sheet: the SQL shown to the person, and the prepared
//! statement that actually runs, both built from the same inputs (spec §7).

use mysql_async::Value;

use crate::error::DbyError;
use crate::paging::{ident, is_numeric_type, is_plain_decimal, literal, quote_ident, text_literal, Style, TableMeta};
use crate::value::Cell;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChangeKind {
    Update,
    Insert,
    Delete,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FieldValue {
    pub column: String,
    /// None writes NULL. Text is sent as typed; the server converts it to the column's type.
    pub value: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RowChange {
    pub table: String,
    pub kind: ChangeKind,
    /// The row's primary-key values in key order, as the page returned them. Empty for inserts.
    pub key: Vec<Cell>,
    /// Columns to set (update) or fill (insert). Ignored for deletes.
    pub values: Vec<FieldValue>,
}

pub struct Prepared {
    pub sql: String,
    pub params: Vec<Value>,
}

pub fn prepare(meta: &TableMeta, change: &RowChange) -> Result<Prepared, DbyError> {
    check(meta, change)?;
    let table = quote_ident(&change.table);
    let key_where = meta.pk.iter().map(|&i| format!("{} = ?", quote_ident(&meta.columns[i].name))).collect::<Vec<_>>().join(" AND ");
    let values = change.values.iter().map(|f| field_param(meta, f));
    let key = change.key.iter().map(cell_param);
    Ok(match change.kind {
        ChangeKind::Update => {
            let sets: Vec<String> = change.values.iter().map(|f| format!("{} = ?", quote_ident(&f.column))).collect();
            Prepared { sql: format!("UPDATE {table} SET {} WHERE {key_where}", sets.join(", ")), params: values.chain(key).collect() }
        }
        ChangeKind::Insert => {
            let columns: Vec<String> = change.values.iter().map(|f| quote_ident(&f.column)).collect();
            let marks = vec!["?"; columns.len()].join(", ");
            Prepared { sql: format!("INSERT INTO {table} ({}) VALUES ({marks})", columns.join(", ")), params: values.collect() }
        }
        ChangeKind::Delete => Prepared { sql: format!("DELETE FROM {table} WHERE {key_where}"), params: key.collect() },
    })
}

pub fn preview(meta: &TableMeta, change: &RowChange) -> Result<String, DbyError> {
    check(meta, change)?;
    let table = ident(&change.table);
    let key = meta
        .pk
        .iter()
        .zip(&change.key)
        .map(|(&i, cell)| Ok(format!("{} = {}", ident(&meta.columns[i].name), cell_text(cell)?)))
        .collect::<Result<Vec<_>, DbyError>>()?
        .join("\n  AND ");
    Ok(match change.kind {
        ChangeKind::Update => {
            let sets: Vec<String> = change.values.iter().map(|f| format!("{} = {}", ident(&f.column), field_text(meta, f))).collect();
            format!("UPDATE {table}\nSET {}\nWHERE {key};", sets.join(",\n    "))
        }
        ChangeKind::Insert => {
            let columns: Vec<String> = change.values.iter().map(|f| ident(&f.column)).collect();
            let values: Vec<String> = change.values.iter().map(|f| field_text(meta, f)).collect();
            format!("INSERT INTO {table} ({})\nVALUES ({});", columns.join(", "), values.join(", "))
        }
        ChangeKind::Delete => format!("DELETE FROM {table}\nWHERE {key};"),
    })
}

fn check(meta: &TableMeta, change: &RowChange) -> Result<(), DbyError> {
    if change.kind != ChangeKind::Insert {
        if meta.pk.is_empty() {
            return Err(DbyError::ReadOnlyBlocked { detail: "this table has no primary key, so its rows cannot be changed here".into() });
        }
        if change.key.len() != meta.pk.len() {
            return Err(DbyError::internal("the row key does not match the table's primary key"));
        }
        for cell in &change.key {
            literal(cell)?; // rejects NULL and values the page trimmed
        }
    }
    if change.kind != ChangeKind::Delete && change.values.is_empty() {
        return Err(DbyError::internal("nothing to change"));
    }
    for field in &change.values {
        meta.index_of(&field.column)?;
    }
    Ok(())
}

/// BIT takes a number: sent as text, `0` would arrive as the byte 0x30.
fn is_bit(meta: &TableMeta, column: &str) -> bool {
    meta.index_of(column).is_ok_and(|i| meta.columns[i].data_type == "bit")
}

fn field_param(meta: &TableMeta, field: &FieldValue) -> Value {
    match &field.value {
        None => Value::NULL,
        Some(v) => match v.parse::<u64>() {
            Ok(n) if is_bit(meta, &field.column) => Value::UInt(n),
            _ => Value::Bytes(v.as_bytes().to_vec()),
        },
    }
}

fn field_text(meta: &TableMeta, field: &FieldValue) -> String {
    let Some(v) = &field.value else { return "NULL".into() };
    let numeric = meta.index_of(&field.column).is_ok_and(|i| is_numeric_type(&meta.columns[i].data_type)) || is_bit(meta, &field.column);
    if numeric && is_plain_decimal(v) {
        v.clone()
    } else {
        text_literal(v, Style::Readable)
    }
}

fn cell_text(cell: &Cell) -> Result<String, DbyError> {
    Ok(match cell {
        Cell::Text { v, .. } | Cell::Temporal { v } => text_literal(v, Style::Readable),
        other => literal(other)?,
    })
}

fn cell_param(cell: &Cell) -> Value {
    match cell {
        Cell::Null => Value::NULL,
        Cell::Signed { v } => Value::Int(*v),
        Cell::Unsigned { v } => Value::UInt(*v),
        Cell::Real { v } => Value::Double(*v),
        Cell::Exact { v } | Cell::Text { v, .. } | Cell::Temporal { v } => Value::Bytes(v.as_bytes().to_vec()),
        Cell::Bytes { preview, .. } => Value::Bytes(preview.clone()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::paging::ColumnInfo;

    fn col(name: &str, data_type: &str, nullable: bool) -> ColumnInfo {
        ColumnInfo { name: name.into(), data_type: data_type.into(), max_len: None, nullable }
    }

    fn edits() -> TableMeta {
        TableMeta { columns: vec![col("id", "int", false), col("nick", "varchar", false), col("qty", "int", true)], pk: vec![0] }
    }

    fn set(column: &str, value: Option<&str>) -> FieldValue {
        FieldValue { column: column.into(), value: value.map(Into::into) }
    }

    fn change(kind: ChangeKind, key: Vec<Cell>, values: Vec<FieldValue>) -> RowChange {
        RowChange { table: "edits".into(), kind, key, values }
    }

    #[test]
    fn update_binds_placeholders_and_previews_readable_sql() {
        let c = change(ChangeKind::Update, vec![Cell::Signed { v: 7 }], vec![set("nick", Some("O'Brien")), set("qty", None)]);
        let p = prepare(&edits(), &c).unwrap();
        assert_eq!(p.sql, "UPDATE `edits` SET `nick` = ?, `qty` = ? WHERE `id` = ?");
        assert_eq!(p.params, vec![Value::Bytes(b"O'Brien".to_vec()), Value::NULL, Value::Int(7)]);
        assert_eq!(preview(&edits(), &c).unwrap(), "UPDATE edits\nSET nick = 'O''Brien',\n    qty = NULL\nWHERE id = 7;");
    }

    #[test]
    fn insert_previews_numbers_bare_and_text_quoted() {
        let c = change(ChangeKind::Insert, vec![], vec![set("id", Some("12")), set("nick", Some("a\\b")), set("qty", Some("3"))]);
        let p = prepare(&edits(), &c).unwrap();
        assert_eq!(p.sql, "INSERT INTO `edits` (`id`, `nick`, `qty`) VALUES (?, ?, ?)");
        assert_eq!(p.params.len(), 3);
        assert_eq!(preview(&edits(), &c).unwrap(), "INSERT INTO edits (id, nick, qty)\nVALUES (12, 'a\\\\b', 3);");
    }

    #[test]
    fn bit_values_bind_as_numbers() {
        let meta = TableMeta { columns: vec![col("id", "int", false), col("flag", "bit", true)], pk: vec![0] };
        let c = RowChange { table: "t".into(), kind: ChangeKind::Update, key: vec![Cell::Signed { v: 1 }], values: vec![set("flag", Some("0"))] };
        assert_eq!(prepare(&meta, &c).unwrap().params[0], Value::UInt(0));
        assert_eq!(preview(&meta, &c).unwrap(), "UPDATE t\nSET flag = 0\nWHERE id = 1;");
    }

    #[test]
    fn delete_matches_the_key() {
        let c = change(ChangeKind::Delete, vec![Cell::Signed { v: 7 }], vec![]);
        let p = prepare(&edits(), &c).unwrap();
        assert_eq!(p.sql, "DELETE FROM `edits` WHERE `id` = ?");
        assert_eq!(p.params, vec![Value::Int(7)]);
        assert_eq!(preview(&edits(), &c).unwrap(), "DELETE FROM edits\nWHERE id = 7;");
    }

    #[test]
    fn composite_text_keys_preview_quoted_and_joined() {
        let meta = TableMeta { columns: vec![col("a", "varchar", false), col("b", "int", false), col("v", "int", true)], pk: vec![0, 1] };
        let c = RowChange {
            table: "pairs".into(),
            kind: ChangeKind::Delete,
            key: vec![Cell::Text { v: "x'y".into(), full_len: 3 }, Cell::Signed { v: 2 }],
            values: vec![],
        };
        assert_eq!(preview(&meta, &c).unwrap(), "DELETE FROM pairs\nWHERE a = 'x''y'\n  AND b = 2;");
        assert_eq!(prepare(&meta, &c).unwrap().sql, "DELETE FROM `pairs` WHERE `a` = ? AND `b` = ?");
    }

    #[test]
    fn unsafe_changes_are_refused() {
        let nopk = TableMeta { columns: vec![col("v", "int", true)], pk: vec![] };
        let on_nopk = RowChange { table: "nopk".into(), kind: ChangeKind::Update, key: vec![Cell::Signed { v: 1 }], values: vec![set("v", Some("2"))] };
        assert!(matches!(prepare(&nopk, &on_nopk), Err(DbyError::ReadOnlyBlocked { .. })));
        let short_key = change(ChangeKind::Update, vec![], vec![set("qty", Some("1"))]);
        assert!(matches!(prepare(&edits(), &short_key), Err(DbyError::Internal { .. })));
        let trimmed_key = change(ChangeKind::Delete, vec![Cell::Text { v: "ab".into(), full_len: 9 }], vec![]);
        assert!(prepare(&edits(), &trimmed_key).is_err());
        let nothing = change(ChangeKind::Update, vec![Cell::Signed { v: 1 }], vec![]);
        assert!(prepare(&edits(), &nothing).is_err());
        let unknown = change(ChangeKind::Update, vec![Cell::Signed { v: 1 }], vec![set("nope", Some("1"))]);
        assert!(matches!(prepare(&edits(), &unknown), Err(DbyError::NotFound { .. })));
    }
}
