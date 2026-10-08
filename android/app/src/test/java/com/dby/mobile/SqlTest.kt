package com.dby.mobile

import com.dby.mobile.ui.Span
import com.dby.mobile.ui.Token
import com.dby.mobile.ui.sqlSpans
import org.junit.Assert.assertEquals
import org.junit.Test

class SqlTest {
    private fun tokens(sql: String) = sqlSpans(sql).map { sql.substring(it.start, it.end) to it.token }

    @Test
    fun keywords_strings_numbers_and_comments() {
        assertEquals(
            listOf(
                "SELECT" to Token.Keyword, "'it''s'" to Token.Text, "FROM" to Token.Keyword,
                "where" to Token.Keyword, "12.5" to Token.Number, "-- note" to Token.Comment,
            ),
            tokens("SELECT 'it''s' FROM t1 where n = 12.5 -- note"),
        )
    }

    @Test
    fun unterminated_string_runs_to_the_end_and_words_with_digits_stay_plain() {
        assertEquals(listOf("SELECT" to Token.Keyword, "'abc" to Token.Text), tokens("SELECT 'abc"))
        assertEquals(emptyList<Pair<String, Token>>(), tokens("t1 x2y `select`"))
    }

    @Test
    fun block_comments_and_backslash_escapes() {
        assertEquals(listOf("/* a */" to Token.Comment, "'a\\'b'" to Token.Text), tokens("/* a */ 'a\\'b'"))
        assertEquals(listOf(Span(0, 6, Token.Keyword)), sqlSpans("UPDATE"))
    }
}
