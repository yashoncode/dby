package com.dby.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

enum class Token { Keyword, Text, Number, Comment }

data class Span(val start: Int, val end: Int, val token: Token)

private val KEYWORDS = setOf(
    "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "ORDER", "BY", "GROUP",
    "HAVING", "LIMIT", "OFFSET", "ASC", "DESC", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "JOIN", "LEFT",
    "RIGHT", "INNER", "OUTER", "CROSS", "ON", "AS", "DISTINCT", "UNION", "ALL", "CASE", "WHEN", "THEN", "ELSE", "END",
    "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "SHOW", "DESCRIBE", "EXPLAIN", "WITH", "EXISTS", "TRUE",
    "FALSE", "COUNT", "SUM", "AVG", "MIN", "MAX", "USE", "REPLACE", "TRUNCATE", "PRIMARY", "KEY", "ESCAPE", "FOR",
    "CALL", "GRANT", "REVOKE", "BEGIN", "COMMIT", "ROLLBACK", "START", "TRANSACTION", "DATABASE", "DATABASES",
    "TABLES", "COLUMNS", "PROCESSLIST", "STATUS", "VARIABLES", "IF", "INTERVAL", "DEFAULT", "UNIQUE", "FULL",
)

private val COLORS = mapOf(
    Token.Keyword to Color(0xFFFF8FB8),
    Token.Text to Color(0xFFFFC27A),
    Token.Number to Color(0xFFC9B3FF),
    Token.Comment to Color(0x80FFFFFF),
)

/** Splits SQL into coloured spans; everything else is plain. Pure, so it is unit-tested on the JVM. */
fun sqlSpans(sql: String): List<Span> {
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        val start = i
        when {
            c == '#' || sql.startsWith("--", i) -> {
                i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                spans += Span(start, i, Token.Comment)
            }
            sql.startsWith("/*", i) -> {
                i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                spans += Span(start, i, Token.Comment)
            }
            c == '\'' || c == '"' -> {
                i = endOfQuoted(sql, i, c)
                spans += Span(start, i, Token.Text)
            }
            c == '`' -> i = endOfQuoted(sql, i, '`')
            c.isDigit() -> {
                while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '.')) i++
                spans += Span(start, i, Token.Number)
            }
            isWord(c) -> {
                while (i < sql.length && isWord(sql[i])) i++
                if (sql.substring(start, i).uppercase() in KEYWORDS) spans += Span(start, i, Token.Keyword)
            }
            else -> i++
        }
    }
    return spans
}

private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

/** The index after the closing quote. A doubled quote or a backslash-escaped one does not close it. */
private fun endOfQuoted(sql: String, open: Int, quote: Char): Int {
    var i = open + 1
    while (i < sql.length) {
        when {
            sql[i] == '\\' && quote != '`' -> i += 2
            sql[i] == quote && i + 1 < sql.length && sql[i + 1] == quote -> i += 2
            sql[i] == quote -> return i + 1
            else -> i++
        }
    }
    return sql.length
}

fun highlightSql(sql: String): AnnotatedString = buildAnnotatedString {
    append(sql)
    for (span in sqlSpans(sql)) addStyle(SpanStyle(color = COLORS.getValue(span.token)), span.start, span.end)
}

/** Colours SQL as it is typed, without changing the text. */
object SqlTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(highlightSql(text.text), OffsetMapping.Identity)
}
