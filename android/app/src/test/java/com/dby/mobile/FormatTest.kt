package com.dby.mobile

import com.dby.core.Cell
import com.dby.core.DbyException
import com.dby.mobile.data.ago
import com.dby.mobile.data.bytes
import com.dby.mobile.data.count
import com.dby.mobile.data.display
import com.dby.mobile.data.isTrimmed
import com.dby.mobile.data.rawText
import com.dby.mobile.data.sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun cells_display_exactly() {
        assertEquals("NULL", Cell.Null.display())
        assertEquals("18446744073709551615", Cell.Unsigned(ULong.MAX_VALUE).display())
        assertEquals("-9223372036854775808", Cell.Signed(Long.MIN_VALUE).display())
        assertEquals("12345678901234567890.123456789", Cell.Exact("12345678901234567890.123456789").display())
        assertEquals("abc…", Cell.Text("abc", 9u).display())
        assertEquals("<1,000 bytes>", Cell.Bytes(1000u, byteArrayOf(1)).display())
        assertEquals("0000-00-00 00:00:00", Cell.Temporal("0000-00-00 00:00:00").display())
    }

    @Test
    fun trimming_counts_characters_not_utf16_units() {
        assertFalse(Cell.Text("😀x", 2u).isTrimmed())
        assertTrue(Cell.Text("😀x", 3u).isTrimmed())
        assertTrue(Cell.Bytes(9u, byteArrayOf(1)).isTrimmed())
        assertFalse(Cell.Exact("1").isTrimmed())
    }

    @Test
    fun raw_text_is_what_an_edit_field_starts_with() {
        assertEquals("abc", Cell.Text("abc", 9u).rawText())
        assertNull(Cell.Null.rawText())
        assertNull(Cell.Bytes(1u, byteArrayOf(1)).rawText())
        assertEquals("4820.00", Cell.Exact("4820.00").rawText())
    }

    @Test
    fun sizes_and_counts_read_like_the_canvas() {
        assertEquals("1,248,302", count(1_248_302))
        assertEquals("412 MB", bytes(412L * 1024 * 1024))
        assertEquals("9.8 MB", bytes((9.8 * 1024 * 1024).toLong()))
        assertEquals("64 KB", bytes(64L * 1024))
        assertEquals("512 B", bytes(512))
        assertEquals("1 KB", bytes(1024))
    }

    @Test
    fun times_are_relative_then_dated() {
        val now = 1_760_000_000_000L
        assertEquals("just now", ago(now - 10_000, now))
        assertEquals("5 min ago", ago(now - 5 * 60_000, now))
        assertEquals("3 h ago", ago(now - 3 * 3_600_000, now))
        assertEquals("2 d ago", ago(now - 2 * 86_400_000L, now))
    }

    @Test
    fun errors_become_one_sentence() {
        assertEquals("Wrong user or password.", DbyException.Auth("denied").sentence())
        assertEquals(
            "Cannot execute statement in a READ ONLY transaction.",
            DbyException.ReadOnlyBlocked("Cannot execute statement in a READ ONLY transaction.").sentence(),
        )
        assertEquals("This statement changes data; confirm it first.", DbyException.ReadOnlyBlocked("this statement changes data; confirm it first").sentence())
        assertEquals("The server refused it (error 1146).", DbyException.Server(1146u, "Table 'x' doesn't exist").sentence())
        assertEquals("boom", IllegalStateException("boom").sentence())
    }
}
