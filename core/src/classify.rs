//! Read or write, decided before any SQL runs. It drives the PROD read-only guard, the
//! confirm-before-saving prompt, and whether a statement may be re-run after its connection
//! drops (spec §6 rule 1, §7). Anything not provably a read counts as a write.

use sqlparser::ast::{ObjectNamePart, Query, SetExpr, Statement, Use};
use sqlparser::dialect::MySqlDialect;
use sqlparser::parser::Parser;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SqlKind {
    Read,
    Write,
}

/// For the Query tab: whether to show the confirm sheet before running `sql`.
#[uniffi::export]
pub fn classify_sql(sql: String) -> SqlKind {
    classify(&sql)
}

pub fn classify(sql: &str) -> SqlKind {
    // MariaDB runs the inside of /*M! ... */, but the parser sees only a comment.
    if sql.contains("/*M!") {
        return SqlKind::Write;
    }
    match Parser::parse_sql(&MySqlDialect {}, sql) {
        // A lone USE changes no data; the session moves both connections for it (`use_target`).
        Ok(statements) if matches!(statements.as_slice(), [Statement::Use(_)]) => SqlKind::Read,
        Ok(statements) if !statements.is_empty() && statements.iter().all(is_read) => SqlKind::Read,
        Ok(_) => SqlKind::Write,
        // SHOW and DESCRIBE cannot change data, and the parser does not know every SHOW form.
        // Only for one statement: the server runs every statement of a batch.
        Err(_) if !is_batch(sql) && starts_with_word(sql, &["show", "describe", "desc"]) => SqlKind::Read,
        Err(_) => SqlKind::Write,
    }
}

/// The database a lone `USE db` switches to, so the session can move both of its connections.
pub fn use_target(sql: &str) -> Option<String> {
    let statements = Parser::parse_sql(&MySqlDialect {}, sql).ok()?;
    let [Statement::Use(Use::Object(name) | Use::Database(name) | Use::Schema(name))] = statements.as_slice() else {
        return None;
    };
    match name.0.as_slice() {
        [ObjectNamePart::Identifier(ident)] => Some(ident.value.clone()),
        _ => None,
    }
}

/// True when the SQL has a USE anywhere; inside a batch it would move only one connection.
pub fn contains_use(sql: &str) -> bool {
    Parser::parse_sql(&MySqlDialect {}, sql).is_ok_and(|statements| statements.iter().any(|s| matches!(s, Statement::Use(_))))
}

fn is_batch(sql: &str) -> bool {
    sql.trim().trim_end_matches(';').contains(';')
}

fn is_read(statement: &Statement) -> bool {
    match statement {
        Statement::Query(query) => query_is_read(query),
        // EXPLAIN ANALYZE runs the statement it explains.
        Statement::Explain { analyze, statement, .. } => !*analyze || is_read(statement),
        Statement::ExplainTable { .. }
        | Statement::ShowFunctions { .. }
        | Statement::ShowVariable { .. }
        | Statement::ShowStatus { .. }
        | Statement::ShowVariables { .. }
        | Statement::ShowCreate { .. }
        | Statement::ShowColumns { .. }
        | Statement::ShowCatalogs { .. }
        | Statement::ShowDatabases { .. }
        | Statement::ShowProcessList { .. }
        | Statement::ShowSchemas { .. }
        | Statement::ShowCharset(_)
        | Statement::ShowObjects(_)
        | Statement::ShowTables { .. }
        | Statement::ShowViews { .. }
        | Statement::ShowCollation { .. } => true,
        _ => false,
    }
}

fn query_is_read(query: &Query) -> bool {
    let ctes_read = query
        .with
        .as_ref()
        .is_none_or(|with| with.cte_tables.iter().all(|cte| query_is_read(&cte.query)));
    ctes_read && body_is_read(&query.body)
}

fn body_is_read(body: &SetExpr) -> bool {
    match body {
        // SELECT ... INTO OUTFILE writes a file on the server.
        SetExpr::Select(select) => select.into.is_none(),
        SetExpr::Query(query) => query_is_read(query),
        SetExpr::SetOperation { left, right, .. } => body_is_read(left) && body_is_read(right),
        SetExpr::Values(_) | SetExpr::Table(_) => true,
        _ => false,
    }
}

fn starts_with_word(sql: &str, words: &[&str]) -> bool {
    let first = sql.trim_start().split(|c: char| c.is_whitespace() || c == ';').next().unwrap_or("");
    words.iter().any(|w| first.eq_ignore_ascii_case(w))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads() {
        for sql in [
            "SELECT 1",
            "select * from t where a = 1",
            "  SELECT(1)",
            "/* note */ SELECT 1",
            "SELECT 1; SELECT 2",
            "(SELECT 1) UNION (SELECT 2)",
            "WITH a AS (SELECT 1 AS x) SELECT x FROM a",
            "SELECT * FROM t FOR UPDATE",
            "SHOW TABLES",
            "SHOW FULL PROCESSLIST",
            "SHOW ENGINE INNODB STATUS",
            "SHOW CREATE TABLE t",
            "DESCRIBE t",
            "DESC t",
            "EXPLAIN SELECT 1",
            "EXPLAIN DELETE FROM t",
            "USE shop",
        ] {
            assert_eq!(classify(sql), SqlKind::Read, "{sql}");
        }
    }

    #[test]
    fn writes() {
        for sql in [
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET x = 1",
            "DELETE FROM t",
            "REPLACE INTO t VALUES (1)",
            "SELECT 1; DELETE FROM t",
            "EXPLAIN ANALYZE DELETE FROM t WHERE id = 1",
            "SELECT * INTO OUTFILE '/tmp/x' FROM t",
            "SET SESSION TRANSACTION READ WRITE",
            "SET sql_select_limit = DEFAULT",
            "CALL p()",
            "DROP TABLE t",
            "CREATE TABLE x (a INT)",
            "TRUNCATE TABLE t",
            "ALTER TABLE t ADD c INT",
            "GRANT ALL ON *.* TO u",
            "WITH a AS (SELECT 1) DELETE FROM t",
            "SHOW TABLES; SET SESSION TRANSACTION READ WRITE; DELETE LOW_PRIORITY QUICK IGNORE FROM orders WHERE id = 1",
            "SHOW TABLES; LOAD DATA INFILE 'x' INTO TABLE t",
            "SHOW TABLES; XA START 'x'",
            "SELECT 1; /*M! SET SESSION TRANSACTION READ WRITE */; /*M! DELETE FROM orders */",
            "USE shop; DELETE FROM t",
            "USE shop; SELECT 1",
            "this is not sql",
            "",
        ] {
            assert_eq!(classify(sql), SqlKind::Write, "{sql}");
        }
    }

    #[test]
    fn a_lone_use_names_the_database_to_switch_to() {
        assert_eq!(use_target("USE shop"), Some("shop".to_string()));
        assert_eq!(use_target("use `my db`;"), Some("my db".to_string()));
        assert_eq!(use_target("USE shop; SELECT 1"), None);
        assert_eq!(use_target("SELECT 1"), None);
        assert!(contains_use("SELECT 1; USE shop"));
        assert!(!contains_use("SELECT 'USE x'"));
    }
}

