//! Builds the SQL for table pages, counts, facets, the cell viewer and the Query builder.
//! Pure: no I/O, so it is unit-tested directly.

use std::collections::HashMap;
use std::sync::Arc;

use crate::error::DbyError;
use crate::schema::{Schema, TableInfo};
use crate::value::Cell;

/// Characters (text) or bytes (binary) a page keeps per cell; the rest stays on the server.
pub const TRIM: u64 = 256;
/// The most the cell viewer loads: characters of text, bytes of binary.
pub const FULL_TEXT: u64 = 1_000_000;
pub const FULL_BYTES: u64 = 65_536;

/// MySQL 8.4's reserved words: an identifier spelled like one must be quoted.
const RESERVED: &str = "ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB \
BOTH BY CALL CASCADE CASE CHANGE CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONSTRAINT CONTINUE CONVERT CREATE \
CROSS CUBE CUME_DIST CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DATABASE DATABASES DAY_HOUR \
DAY_MICROSECOND DAY_MINUTE DAY_SECOND DEC DECIMAL DECLARE DEFAULT DELAYED DELETE DENSE_RANK DESC DESCRIBE \
DETERMINISTIC DISTINCT DISTINCTROW DIV DOUBLE DROP DUAL EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED EXCEPT EXISTS EXIT \
EXPLAIN FALSE FETCH FIRST_VALUE FLOAT FLOAT4 FLOAT8 FOR FORCE FOREIGN FROM FULLTEXT FUNCTION GENERATED GET GRANT \
GROUP GROUPING GROUPS HAVING HIGH_PRIORITY HOUR_MICROSECOND HOUR_MINUTE HOUR_SECOND IF IGNORE IN INDEX INFILE INNER \
INOUT INSENSITIVE INSERT INT INT1 INT2 INT3 INT4 INT8 INTEGER INTERSECT INTERVAL INTO IO_AFTER_GTIDS IO_BEFORE_GTIDS \
IS ITERATE JOIN JSON_TABLE KEY KEYS KILL LAG LAST_VALUE LATERAL LEAD LEADING LEAVE LEFT LIKE LIMIT LINEAR LINES LOAD \
LOCALTIME LOCALTIMESTAMP LOCK LONG LONGBLOB LONGTEXT LOOP LOW_PRIORITY MASTER_BIND MASTER_SSL_VERIFY_SERVER_CERT \
MATCH MAXVALUE MEDIUMBLOB MEDIUMINT MEDIUMTEXT MIDDLEINT MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT \
NO_WRITE_TO_BINLOG NTH_VALUE NTILE NULL NUMERIC OF ON OPTIMIZE OPTIMIZER_COSTS OPTION OPTIONALLY OR ORDER OUT OUTER \
OUTFILE OVER PARTITION PERCENT_RANK PRECISION PRIMARY PROCEDURE PURGE QUALIFY RANGE RANK READ READS READ_WRITE REAL \
RECURSIVE REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE REQUIRE RESIGNAL RESTRICT RETURN REVOKE RIGHT RLIKE ROW \
ROWS ROW_NUMBER SCHEMA SCHEMAS SECOND_MICROSECOND SELECT SENSITIVE SEPARATOR SET SHOW SIGNAL SMALLINT SPATIAL \
SPECIFIC SQL SQLEXCEPTION SQLSTATE SQLWARNING SQL_BIG_RESULT SQL_CALC_FOUND_ROWS SQL_SMALL_RESULT SSL STARTING \
STORED STRAIGHT_JOIN SYSTEM TABLE TABLESAMPLE TERMINATED THEN TINYBLOB TINYINT TINYTEXT TO TRAILING TRIGGER TRUE \
UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE USE USING UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VARBINARY VARCHAR \
VARCHARACTER VARYING VIRTUAL WHEN WHERE WHILE WINDOW WITH WRITE XOR YEAR_MONTH ZEROFILL";

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Cursor {
    /// Values of the ordering columns in the last row shown. Empty when paging by offset.
    pub after: Vec<Cell>,
    /// Row offset, used only when paging by offset.
    pub offset: u64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Sort {
    pub column: String,
    pub descending: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FilterOp {
    Eq,
    NotEq,
    Gt,
    Ge,
    Lt,
    Le,
    /// Text contains the value; `%`, `_` and `!` in it match literally.
    Contains,
    IsNull,
    IsNotNull,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Filter {
    pub column: String,
    pub op: FilterOp,
    /// Ignored by `IsNull` and `IsNotNull`.
    pub value: String,
}

/// The Query tab's builder: what to select, written out as SQL by `build_select`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SelectSpec {
    pub table: String,
    /// Empty selects every column.
    pub columns: Vec<String>,
    pub filters: Vec<Filter>,
    /// AND between filters when true, OR when false.
    pub match_all: bool,
    pub sort: Option<Sort>,
    pub limit: Option<u32>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ColumnInfo {
    pub name: String,
    /// Lowercase `information_schema.COLUMNS.DATA_TYPE`, e.g. `varchar`.
    pub data_type: String,
    pub max_len: Option<u64>,
    pub nullable: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TableMeta {
    pub columns: Vec<ColumnInfo>,
    /// Indexes into `columns`, in primary-key order. Empty when the table has no primary key.
    pub pk: Vec<usize>,
}

impl TableMeta {
    pub fn from_info(table: &TableInfo) -> TableMeta {
        let mut keys: Vec<(u32, usize)> =
            table.columns.iter().enumerate().filter_map(|(i, c)| c.pk_seq.map(|seq| (seq, i))).collect();
        keys.sort_unstable();
        TableMeta {
            columns: table
                .columns
                .iter()
                .map(|c| ColumnInfo { name: c.name.clone(), data_type: c.data_type.clone(), max_len: c.max_len, nullable: c.nullable })
                .collect(),
            pk: keys.into_iter().map(|(_, i)| i).collect(),
        }
    }

    pub fn index_of(&self, column: &str) -> Result<usize, DbyError> {
        self.columns
            .iter()
            .position(|c| c.name == column)
            .ok_or_else(|| DbyError::NotFound { detail: format!("column {column}") })
    }
}

pub fn tables_from(schema: &Schema) -> HashMap<String, Arc<TableMeta>> {
    schema.tables.iter().map(|t| (t.name.clone(), Arc::new(TableMeta::from_info(t)))).collect()
}

/// How a page is ordered: by these columns, all in one direction. `keyset` pages by the last
/// row's values; otherwise by offset, for tables without a key and for sort columns that allow
/// NULL (a NULL makes the row comparison unknown, which would skip rows).
#[derive(Debug, Clone, PartialEq)]
pub struct Order {
    pub columns: Vec<usize>,
    pub descending: bool,
    pub keyset: bool,
}

pub fn order_of(meta: &TableMeta, sort: Option<&Sort>) -> Result<Order, DbyError> {
    let key_safe = !meta.pk.is_empty() && meta.pk.iter().all(|&i| keyset_safe(&meta.columns[i].data_type));
    let Some(sort) = sort else {
        return Ok(Order { columns: meta.pk.clone(), descending: false, keyset: key_safe });
    };
    let first = meta.index_of(&sort.column)?;
    let mut columns = vec![first];
    if meta.pk.is_empty() {
        // No key to break ties, and tied rows may come back in any order between pages: break
        // them with every other short column.
        columns.extend((0..meta.columns.len()).filter(|&i| i != first && trim_with(&meta.columns[i]).is_none()));
    } else {
        columns.extend(meta.pk.iter().copied().filter(|&i| i != first));
    }
    let column = &meta.columns[first];
    let keyset = key_safe && !column.nullable && keyset_safe(&column.data_type);
    Ok(Order { columns, descending: sort.descending, keyset })
}

/// Types whose ORDER BY order is the order a row comparison against a literal gives. ENUM and
/// SET sort by position but compare as text, a FLOAT literal is not the stored value, and JSON
/// compares by type: those page by offset.
fn keyset_safe(data_type: &str) -> bool {
    matches!(
        data_type,
        "tinyint" | "smallint" | "mediumint" | "int" | "integer" | "bigint" | "decimal" | "numeric" | "date" | "datetime"
            | "timestamp" | "time" | "year" | "char" | "varchar" | "binary" | "varbinary"
    )
}

#[derive(Debug, Clone, PartialEq)]
pub struct PageSql {
    pub sql: String,
    /// Per selected table column: true when the query returns it as two values, the trimmed
    /// value and its full length.
    pub trimmed: Vec<bool>,
}

pub struct PageSpec<'a> {
    pub table: &'a str,
    pub filters: &'a [Filter],
    pub order: &'a Order,
    pub cursor: Option<&'a Cursor>,
    pub limit: u32,
}

/// One page, fetching `limit + 1` rows: the extra row only says whether another page exists.
pub fn page_sql(meta: &TableMeta, spec: &PageSpec) -> Result<PageSql, DbyError> {
    let order = spec.order;
    let mut select = Vec::with_capacity(meta.columns.len());
    let mut trimmed = Vec::with_capacity(meta.columns.len());
    for (index, column) in meta.columns.iter().enumerate() {
        let quoted = quote_ident(&column.name);
        // Keyset ordering columns are sent whole: the next cursor is built from them.
        match trim_with(column).filter(|_| !(order.keyset && order.columns.contains(&index))) {
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
    let mut conditions = conditions(meta, spec.filters, Style::Hex)?;
    if let (true, Some(cursor)) = (order.keyset, spec.cursor) {
        if cursor.after.len() != order.columns.len() {
            return Err(DbyError::internal("cursor does not match the page order"));
        }
        let keys: Vec<String> = order.columns.iter().map(|&i| quote_ident(&meta.columns[i].name)).collect();
        let values = cursor.after.iter().map(literal).collect::<Result<Vec<_>, _>>()?;
        let op = if order.descending { "<" } else { ">" };
        conditions.push(if keys.len() == 1 {
            format!("{} {op} {}", keys[0], values[0])
        } else {
            format!("({}) {op} ({})", keys.join(", "), values.join(", "))
        });
    }
    let mut sql = format!("SELECT {} FROM {}", select.join(", "), quote_ident(spec.table));
    push_where(&mut sql, &conditions);
    if !order.columns.is_empty() {
        let direction = if order.descending { " DESC" } else { "" };
        let keys: Vec<String> =
            order.columns.iter().map(|&i| format!("{}{direction}", quote_ident(&meta.columns[i].name))).collect();
        sql.push_str(&format!(" ORDER BY {}", keys.join(", ")));
    }
    sql.push_str(&format!(" LIMIT {}", u64::from(spec.limit) + 1));
    if !order.keyset {
        sql.push_str(&format!(" OFFSET {}", spec.cursor.map_or(0, |c| c.offset)));
    }
    Ok(PageSql { sql, trimmed })
}

/// The cursor for the page after `rows`. Call only when the server returned an extra row.
pub fn next_cursor(order: &Order, current: Option<&Cursor>, rows: &[Vec<Cell>], limit: u32) -> Option<Cursor> {
    if !order.keyset {
        let offset = current.map_or(0, |c| c.offset) + u64::from(limit);
        return Some(Cursor { after: vec![], offset });
    }
    let last = rows.last()?;
    Some(Cursor { after: order.columns.iter().map(|&i| last[i].clone()).collect(), offset: 0 })
}

pub fn count_sql(meta: &TableMeta, table: &str, filters: &[Filter]) -> Result<String, DbyError> {
    let mut sql = format!("SELECT COUNT(*) FROM {}", quote_ident(table));
    push_where(&mut sql, &conditions(meta, filters, Style::Hex)?);
    Ok(sql)
}

/// Rows per value of `column`, most common first, for the quick-filter chips.
pub fn facet_sql(meta: &TableMeta, table: &str, column: &str, filters: &[Filter]) -> Result<String, DbyError> {
    let name = quote_ident(&meta.columns[meta.index_of(column)?].name);
    let mut sql = format!("SELECT {name}, COUNT(*) FROM {}", quote_ident(table));
    push_where(&mut sql, &conditions(meta, filters, Style::Hex)?);
    sql.push_str(&format!(" GROUP BY {name} ORDER BY COUNT(*) DESC LIMIT 50"));
    Ok(sql)
}

/// Caps a count at two seconds (spec §6 rule 10); the server then fails it with a timeout.
pub fn time_limited(sql: &str, mariadb: bool) -> String {
    if mariadb {
        return format!("SET STATEMENT max_statement_time=2 FOR {sql}");
    }
    match sql.strip_prefix("SELECT ") {
        Some(rest) => format!("SELECT /*+ MAX_EXECUTION_TIME(2000) */ {rest}"),
        None => sql.to_string(),
    }
}

/// One cell of one row, long values up to the viewer's cap with their full length.
pub fn cell_sql(meta: &TableMeta, table: &str, column: &str, key: &[Cell]) -> Result<PageSql, DbyError> {
    let index = meta.index_of(column)?;
    let quoted = quote_ident(&meta.columns[index].name);
    let (select, trimmed) = match trim_with(&meta.columns[index]) {
        Some("LENGTH") => (format!("LEFT({quoted}, {FULL_BYTES}), LENGTH({quoted})"), true),
        Some(length_fn) => (format!("LEFT({quoted}, {FULL_TEXT}), {length_fn}({quoted})"), true),
        None => (quoted, false),
    };
    let sql = format!("SELECT {select} FROM {} WHERE {}", quote_ident(table), key_match(meta, key)?);
    Ok(PageSql { sql, trimmed: vec![trimmed] })
}

/// `pk1 = v1 AND pk2 = v2` addressing one row, with injection-proof literals.
pub fn key_match(meta: &TableMeta, key: &[Cell]) -> Result<String, DbyError> {
    if meta.pk.is_empty() {
        return Err(DbyError::ReadOnlyBlocked { detail: "this table has no primary key, so single rows cannot be addressed".into() });
    }
    if key.len() != meta.pk.len() {
        return Err(DbyError::internal("the row key does not match the table's primary key"));
    }
    let parts = meta
        .pk
        .iter()
        .zip(key)
        .map(|(&i, cell)| Ok(format!("{} = {}", quote_ident(&meta.columns[i].name), literal(cell)?)))
        .collect::<Result<Vec<_>, DbyError>>()?;
    Ok(parts.join(" AND "))
}

/// The Query builder's SQL, laid out the way a person would write it.
pub fn build_select(meta: &TableMeta, spec: &SelectSpec) -> Result<String, DbyError> {
    for column in &spec.columns {
        meta.index_of(column)?;
    }
    let columns = if spec.columns.is_empty() {
        "*".to_string()
    } else {
        spec.columns.iter().map(|c| ident(c)).collect::<Vec<_>>().join(", ")
    };
    let mut sql = format!("SELECT {columns}\nFROM {}", ident(&spec.table));
    let conditions = conditions(meta, &spec.filters, Style::Readable)?;
    if !conditions.is_empty() {
        let joiner = if spec.match_all { "\n  AND " } else { "\n   OR " };
        sql.push_str(&format!("\nWHERE {}", conditions.join(joiner)));
    }
    if let Some(sort) = &spec.sort {
        meta.index_of(&sort.column)?;
        sql.push_str(&format!("\nORDER BY {}{}", ident(&sort.column), if sort.descending { " DESC" } else { "" }));
    }
    if let Some(limit) = spec.limit {
        sql.push_str(&format!("\nLIMIT {limit}"));
    }
    sql.push(';');
    Ok(sql)
}

/// How literals are written: hex for SQL the core runs (no dependence on `sql_mode` or
/// escaping), quoted for SQL a person reads.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Style {
    Hex,
    Readable,
}

fn conditions(meta: &TableMeta, filters: &[Filter], style: Style) -> Result<Vec<String>, DbyError> {
    filters.iter().map(|f| condition(meta, f, style)).collect()
}

fn push_where(sql: &mut String, conditions: &[String]) {
    if !conditions.is_empty() {
        sql.push_str(" WHERE ");
        sql.push_str(&conditions.join(" AND "));
    }
}

pub fn condition(meta: &TableMeta, filter: &Filter, style: Style) -> Result<String, DbyError> {
    let column = &meta.columns[meta.index_of(&filter.column)?];
    let name = match style {
        Style::Hex => quote_ident(&column.name),
        Style::Readable => ident(&column.name),
    };
    let op = match filter.op {
        FilterOp::IsNull => return Ok(format!("{name} IS NULL")),
        FilterOp::IsNotNull => return Ok(format!("{name} IS NOT NULL")),
        FilterOp::Contains => {
            let pattern = format!("%{}%", escape_like(&filter.value));
            return Ok(format!("{name} LIKE {} ESCAPE '!'", text_literal(&pattern, style)));
        }
        FilterOp::Eq => "=",
        FilterOp::NotEq => "<>",
        FilterOp::Gt => ">",
        FilterOp::Ge => ">=",
        FilterOp::Lt => "<",
        FilterOp::Le => "<=",
    };
    let value = if is_numeric_type(&column.data_type) && is_plain_decimal(&filter.value) {
        filter.value.clone()
    } else {
        text_literal(&filter.value, style)
    };
    Ok(format!("{name} {op} {value}"))
}

/// A string literal. Readable quoting doubles `'` and `\`, which is safe with or without
/// `NO_BACKSLASH_ESCAPES`.
pub fn text_literal(value: &str, style: Style) -> String {
    match style {
        Style::Hex => format!("_utf8mb4 X'{}'", hex(value.as_bytes())),
        Style::Readable => format!("'{}'", value.replace('\\', "\\\\").replace('\'', "''")),
    }
}

/// LIKE's wildcards matched literally, with `!` as the escape character (`ESCAPE '!'`), so it
/// does not depend on how `sql_mode` treats backslashes.
fn escape_like(value: &str) -> String {
    value.replace('!', "!!").replace('%', "!%").replace('_', "!_")
}

pub fn is_numeric_type(data_type: &str) -> bool {
    matches!(
        data_type,
        "tinyint" | "smallint" | "mediumint" | "int" | "integer" | "bigint" | "decimal" | "numeric" | "float" | "double" | "year"
    )
}

pub fn quote_ident(name: &str) -> String {
    format!("`{}`", name.replace('`', "``"))
}

/// An identifier as a person would write it: bare when that is unambiguous, quoted otherwise.
pub fn ident(name: &str) -> String {
    let simple = name.starts_with(|c: char| c.is_ascii_alphabetic() || c == '_')
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_');
    let upper = name.to_ascii_uppercase();
    if simple && !RESERVED.split_ascii_whitespace().any(|w| w == upper) {
        name.to_string()
    } else {
        quote_ident(name)
    }
}

/// The length function for a column a page should trim, or None to send it whole.
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

/// A SQL literal for a key value that cannot carry SQL and does not depend on `sql_mode`.
/// Strings use an introducer, so the comparison runs under the column's own collation.
pub fn literal(cell: &Cell) -> Result<String, DbyError> {
    match cell {
        Cell::Signed { v } => Ok(v.to_string()),
        Cell::Unsigned { v } => Ok(v.to_string()),
        Cell::Real { v } if v.is_finite() => Ok(format!("{v:?}")),
        Cell::Exact { v } if is_plain_decimal(v) => Ok(v.clone()),
        Cell::Text { v, full_len } if *full_len == v.chars().count() as u64 => Ok(text_literal(v, Style::Hex)),
        Cell::Temporal { v } => Ok(text_literal(v, Style::Hex)),
        Cell::Bytes { len, preview } if *len == preview.len() as u64 => Ok(format!("X'{}'", hex(preview))),
        other => Err(DbyError::internal(format!("cannot match on value {other:?}"))),
    }
}

pub fn is_plain_decimal(s: &str) -> bool {
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
    use crate::schema::ColumnDef;

    fn col(name: &str, data_type: &str, max_len: Option<u64>) -> ColumnInfo {
        ColumnInfo { name: name.into(), data_type: data_type.into(), max_len, nullable: false }
    }

    fn orders() -> TableMeta {
        let mut notes = col("notes", "text", Some(65535));
        notes.nullable = true;
        TableMeta {
            columns: vec![
                col("id", "bigint", None),
                col("customer", "varchar", Some(80)),
                col("status", "varchar", Some(16)),
                col("total", "decimal", None),
                notes,
                col("created_at", "datetime", None),
            ],
            pk: vec![0],
        }
    }

    fn page(meta: &TableMeta, table: &str, filters: &[Filter], sort: Option<&Sort>, cursor: Option<&Cursor>, limit: u32) -> PageSql {
        let order = order_of(meta, sort).unwrap();
        page_sql(meta, &PageSpec { table, filters, order: &order, cursor, limit }).unwrap()
    }

    fn filter(column: &str, op: FilterOp, value: &str) -> Filter {
        Filter { column: column.into(), op, value: value.into() }
    }

    #[test]
    fn quote_ident_doubles_backticks() {
        assert_eq!(quote_ident("a`b"), "`a``b`");
    }

    #[test]
    fn ident_quotes_only_when_needed() {
        assert_eq!(ident("customer"), "customer");
        assert_eq!(ident("status"), "status");
        assert_eq!(ident("order"), "`order`");
        assert_eq!(ident("Key"), "`Key`");
        assert_eq!(ident("first name"), "`first name`");
        assert_eq!(ident("2fa"), "`2fa`");
        assert_eq!(ident("a`b"), "`a``b`");
    }

    #[test]
    fn first_page_orders_by_key_and_trims_long_text() {
        let built = page(&orders(), "orders", &[], None, None, 50);
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
        let built = page(&orders(), "orders", &[], None, Some(&cursor), 50);
        assert!(built.sql.ends_with(" FROM `orders` WHERE `id` > 50 ORDER BY `id` LIMIT 51"), "{}", built.sql);
    }

    #[test]
    fn composite_key_uses_a_row_comparison() {
        let meta = TableMeta { columns: vec![col("a", "int", None), col("b", "int", None), col("v", "varchar", Some(10))], pk: vec![0, 1] };
        let cursor = Cursor { after: vec![Cell::Signed { v: 1 }, Cell::Signed { v: 9 }], offset: 0 };
        let built = page(&meta, "pairs", &[], None, Some(&cursor), 7);
        assert_eq!(built.sql, "SELECT `a`, `b`, `v` FROM `pairs` WHERE (`a`, `b`) > (1, 9) ORDER BY `a`, `b` LIMIT 8");
    }

    #[test]
    fn table_without_key_pages_by_offset() {
        let meta = TableMeta { columns: vec![col("v", "int", None)], pk: vec![] };
        let cursor = Cursor { after: vec![], offset: 30 };
        assert_eq!(page(&meta, "nopk", &[], None, Some(&cursor), 30).sql, "SELECT `v` FROM `nopk` LIMIT 31 OFFSET 30");
    }

    #[test]
    fn key_columns_are_never_trimmed() {
        let meta = TableMeta { columns: vec![col("code", "varchar", Some(512)), col("body", "longtext", None)], pk: vec![0] };
        let built = page(&meta, "docs", &[], None, None, 10);
        assert_eq!(built.sql, "SELECT `code`, LEFT(`body`, 256), CHAR_LENGTH(`body`) FROM `docs` ORDER BY `code` LIMIT 11");
        assert_eq!(built.trimmed, vec![false, true]);
    }

    #[test]
    fn blobs_are_trimmed_by_byte_length() {
        let meta = TableMeta { columns: vec![col("id", "int", None), col("data", "longblob", None)], pk: vec![0] };
        assert!(page(&meta, "files", &[], None, None, 10).sql.contains("LEFT(`data`, 256), LENGTH(`data`)"));
    }

    #[test]
    fn sorting_descending_adds_the_key_as_tie_breaker() {
        let sort = Sort { column: "created_at".into(), descending: true };
        let order = order_of(&orders(), Some(&sort)).unwrap();
        assert_eq!(order, Order { columns: vec![5, 0], descending: true, keyset: true });
        let cursor = Cursor { after: vec![Cell::Temporal { v: "2026-01-02".into() }, Cell::Unsigned { v: 9 }], offset: 0 };
        let built = page(&orders(), "orders", &[], Some(&sort), Some(&cursor), 2);
        assert!(
            built.sql.ends_with(
                " FROM `orders` WHERE (`created_at`, `id`) < (_utf8mb4 X'323032362d30312d3032', 9) \
                 ORDER BY `created_at` DESC, `id` DESC LIMIT 3"
            ),
            "{}",
            built.sql
        );
    }

    #[test]
    fn sorting_by_the_key_itself_does_not_repeat_it() {
        let order = order_of(&orders(), Some(&Sort { column: "id".into(), descending: true })).unwrap();
        assert_eq!(order, Order { columns: vec![0], descending: true, keyset: true });
    }

    #[test]
    fn sorting_by_a_nullable_column_falls_back_to_offset_and_still_trims_it() {
        let sort = Sort { column: "notes".into(), descending: false };
        assert!(!order_of(&orders(), Some(&sort)).unwrap().keyset);
        let cursor = Cursor { after: vec![], offset: 20 };
        let built = page(&orders(), "orders", &[], Some(&sort), Some(&cursor), 10);
        assert_eq!(
            built.sql,
            "SELECT `id`, `customer`, `status`, `total`, LEFT(`notes`, 256), CHAR_LENGTH(`notes`), `created_at` \
             FROM `orders` ORDER BY `notes`, `id` LIMIT 11 OFFSET 20"
        );
    }

    #[test]
    fn enum_float_and_json_sorts_page_by_offset() {
        let meta = TableMeta {
            columns: vec![col("id", "int", None), col("state", "enum", Some(7)), col("score", "float", None), col("doc", "json", None)],
            pk: vec![0],
        };
        for column in ["state", "score", "doc"] {
            let order = order_of(&meta, Some(&Sort { column: column.into(), descending: false })).unwrap();
            assert!(!order.keyset, "{column}");
            assert_eq!(order.columns[1], 0, "{column}: the key still breaks ties");
        }
        let float_key = TableMeta { columns: vec![col("x", "double", None)], pk: vec![0] };
        assert!(!order_of(&float_key, None).unwrap().keyset);
    }

    #[test]
    fn tables_without_a_key_break_sort_ties_with_their_other_short_columns() {
        let meta = TableMeta {
            columns: vec![col("a", "int", None), col("b", "varchar", Some(10)), col("body", "text", None), col("c", "date", None)],
            pk: vec![],
        };
        let order = order_of(&meta, Some(&Sort { column: "b".into(), descending: true })).unwrap();
        assert_eq!(order, Order { columns: vec![1, 0, 3], descending: true, keyset: false });
    }

    #[test]
    fn unknown_sort_or_filter_column_is_not_found() {
        assert!(matches!(order_of(&orders(), Some(&Sort { column: "nope".into(), descending: false })), Err(DbyError::NotFound { .. })));
        assert!(matches!(count_sql(&orders(), "orders", &[filter("nope", FilterOp::Eq, "1")]), Err(DbyError::NotFound { .. })));
    }

    #[test]
    fn filters_use_hex_literals_and_bare_numbers() {
        let filters = [filter("status", FilterOp::Eq, "shipped"), filter("total", FilterOp::Gt, "500"), filter("notes", FilterOp::IsNull, "")];
        assert_eq!(
            count_sql(&orders(), "orders", &filters).unwrap(),
            "SELECT COUNT(*) FROM `orders` WHERE `status` = _utf8mb4 X'73686970706564' AND `total` > 500 AND `notes` IS NULL"
        );
        let built = page(&orders(), "orders", &filters[..1], None, Some(&Cursor { after: vec![Cell::Unsigned { v: 4 }], offset: 0 }), 5);
        assert!(built.sql.contains(" WHERE `status` = _utf8mb4 X'73686970706564' AND `id` > 4 ORDER BY"), "{}", built.sql);
    }

    #[test]
    fn numeric_column_with_non_numeric_value_is_compared_as_text() {
        let sql = count_sql(&orders(), "orders", &[filter("total", FilterOp::Eq, "5; DROP TABLE x")]).unwrap();
        assert!(sql.ends_with("WHERE `total` = _utf8mb4 X'353b2044524f50205441424c452078'"), "{sql}");
    }

    #[test]
    fn contains_escapes_like_wildcards() {
        let readable = condition(&orders(), &filter("customer", FilterOp::Contains, "50%_!"), Style::Readable).unwrap();
        assert_eq!(readable, "customer LIKE '%50!%!_!!%' ESCAPE '!'");
        let hex = condition(&orders(), &filter("customer", FilterOp::Contains, "a"), Style::Hex).unwrap();
        assert_eq!(hex, "`customer` LIKE _utf8mb4 X'256125' ESCAPE '!'");
    }

    #[test]
    fn readable_text_doubles_quotes_and_backslashes() {
        assert_eq!(text_literal("O'Brien \\ x", Style::Readable), "'O''Brien \\\\ x'");
    }

    #[test]
    fn facets_group_and_counts_are_time_limited_per_server() {
        assert_eq!(
            facet_sql(&orders(), "orders", "status", &[]).unwrap(),
            "SELECT `status`, COUNT(*) FROM `orders` GROUP BY `status` ORDER BY COUNT(*) DESC LIMIT 50"
        );
        assert_eq!(time_limited("SELECT COUNT(*) FROM `t`", false), "SELECT /*+ MAX_EXECUTION_TIME(2000) */ COUNT(*) FROM `t`");
        assert_eq!(time_limited("SELECT COUNT(*) FROM `t`", true), "SET STATEMENT max_statement_time=2 FOR SELECT COUNT(*) FROM `t`");
    }

    #[test]
    fn cell_sql_loads_long_values_up_to_the_viewer_cap() {
        let key = [Cell::Unsigned { v: 10 }];
        let notes = cell_sql(&orders(), "orders", "notes", &key).unwrap();
        assert_eq!(notes.sql, "SELECT LEFT(`notes`, 1000000), CHAR_LENGTH(`notes`) FROM `orders` WHERE `id` = 10");
        assert_eq!(notes.trimmed, vec![true]);
        let total = cell_sql(&orders(), "orders", "total", &key).unwrap();
        assert_eq!(total.sql, "SELECT `total` FROM `orders` WHERE `id` = 10");
        assert_eq!(total.trimmed, vec![false]);
        let nopk = TableMeta { columns: vec![col("v", "int", None)], pk: vec![] };
        assert!(matches!(cell_sql(&nopk, "nopk", "v", &[]), Err(DbyError::ReadOnlyBlocked { .. })));
    }

    #[test]
    fn build_select_reads_like_hand_written_sql() {
        let mut spec = SelectSpec {
            table: "orders".into(),
            columns: vec!["id".into(), "customer".into(), "status".into()],
            filters: vec![filter("created_at", FilterOp::Ge, "2026-10-01"), filter("total", FilterOp::Gt, "500")],
            match_all: true,
            sort: Some(Sort { column: "created_at".into(), descending: true }),
            limit: Some(50),
        };
        assert_eq!(
            build_select(&orders(), &spec).unwrap(),
            "SELECT id, customer, status\nFROM orders\nWHERE created_at >= '2026-10-01'\n  AND total > 500\nORDER BY created_at DESC\nLIMIT 50;"
        );
        spec.columns.clear();
        spec.match_all = false;
        spec.sort = None;
        spec.limit = None;
        assert_eq!(
            build_select(&orders(), &spec).unwrap(),
            "SELECT *\nFROM orders\nWHERE created_at >= '2026-10-01'\n   OR total > 500;"
        );
        spec.columns = vec!["nope".into()];
        assert!(build_select(&orders(), &spec).is_err());
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
    fn cursor_length_must_match_the_order() {
        let order = order_of(&orders(), None).unwrap();
        let cursor = Cursor { after: vec![Cell::Signed { v: 1 }, Cell::Signed { v: 2 }], offset: 0 };
        assert!(page_sql(&orders(), &PageSpec { table: "orders", filters: &[], order: &order, cursor: Some(&cursor), limit: 50 }).is_err());
    }

    #[test]
    fn from_info_orders_the_key_by_its_sequence() {
        let def = |name: &str, pk_seq, nullable| ColumnDef {
            name: name.into(),
            data_type: "int".into(),
            column_type: "int".into(),
            nullable,
            pk_seq,
            max_len: None,
            enum_values: vec![],
        };
        let info = TableInfo { name: "t".into(), is_view: false, rows_estimate: None, bytes: None, columns: vec![def("a", Some(2), false), def("b", Some(1), true), def("c", None, true)] };
        let meta = TableMeta::from_info(&info);
        assert_eq!(meta.pk, vec![1, 0]);
        assert!(meta.columns[1].nullable);
        assert_eq!(meta.index_of("c").unwrap(), 2);
    }

    #[test]
    fn next_cursor_takes_the_last_rows_order_values_or_advances_the_offset() {
        let rows = vec![
            vec![Cell::Unsigned { v: 99 }, Cell::Null, Cell::Null, Cell::Null, Cell::Null, Cell::Temporal { v: "a".into() }],
            vec![Cell::Unsigned { v: 100 }, Cell::Null, Cell::Null, Cell::Null, Cell::Null, Cell::Temporal { v: "b".into() }],
        ];
        let by_key = order_of(&orders(), None).unwrap();
        assert_eq!(next_cursor(&by_key, None, &rows, 2), Some(Cursor { after: vec![Cell::Unsigned { v: 100 }], offset: 0 }));
        let by_date = order_of(&orders(), Some(&Sort { column: "created_at".into(), descending: true })).unwrap();
        assert_eq!(
            next_cursor(&by_date, None, &rows, 2),
            Some(Cursor { after: vec![Cell::Temporal { v: "b".into() }, Cell::Unsigned { v: 100 }], offset: 0 })
        );
        let by_offset = Order { columns: vec![], descending: false, keyset: false };
        let current = Cursor { after: vec![], offset: 30 };
        assert_eq!(next_cursor(&by_offset, Some(&current), &rows, 30), Some(Cursor { after: vec![], offset: 60 }));
    }
}
