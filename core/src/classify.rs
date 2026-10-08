//! Read or write, decided before any SQL runs. It drives the PROD read-only guard, the
//! confirm-before-saving prompt, and whether a statement may be re-run after its connection
//! drops (spec §6 rule 1, §7). Anything not provably a read counts as a write.

use sqlparser::ast::{Query, SetExpr, Statement};
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
    match Parser::parse_sql(&MySqlDialect {}, sql) {
        Ok(statements) if !statements.is_empty() && statements.iter().all(is_read) => SqlKind::Read,
        Ok(_) => SqlKind::Write,
        // SHOW and DESCRIBE cannot change data, and the parser does not know every SHOW form.
        Err(_) if starts_with_word(sql, &["show", "describe", "desc"]) => SqlKind::Read,
        Err(_) => SqlKind::Write,
    }
}

fn is_read(statement: &Statement) -> bool {
    match statement {
        Statement::Query(query) => query_is_read(query),
        // EXPLAIN ANALYZE runs the statement it explains.
        Statement::Explain { analyze, statement, .. } => !*analyze || is_read(statement),
        Statement::ExplainTable { .. }
        | Statement::Use(_)
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
            "this is not sql",
            "",
        ] {
            assert_eq!(classify(sql), SqlKind::Write, "{sql}");
        }
    }
}
