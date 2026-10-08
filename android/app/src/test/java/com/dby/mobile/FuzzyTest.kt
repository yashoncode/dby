package com.dby.mobile

import com.dby.mobile.data.fuzzyScore
import com.dby.mobile.data.fuzzySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyTest {
    private val tables = listOf(
        "activity_log", "addresses", "agreement_has_assigned_users", "agreement_parties", "agreements",
        "big_orders", "customers", "order_items", "orders",
    )

    private fun search(q: String) = fuzzySearch(tables, q) { listOf(it) }

    @Test
    fun blank_query_keeps_everything_in_order() {
        assertEquals(tables, search("  "))
    }

    @Test
    fun closer_matches_come_first() {
        assertEquals(listOf("orders", "order_items", "big_orders"), search("order"))
    }

    @Test
    fun separators_and_case_are_ignored() {
        assertEquals("activity_log", search("ActivityLog").first())
        assertEquals("activity_log", search("activity log").first())
    }

    @Test
    fun missing_letters_still_match() {
        assertEquals("addresses", search("adress").first())
        assertEquals("customers", search("custmer").first())
        assertEquals("agreements", search("agrmnts").first())
    }

    @Test
    fun typos_still_match() {
        assertEquals("customers", search("cutsomers").first())
        assertEquals("agreements", search("agreemnets").first())
        assertEquals("addresses", search("addresess").first())
    }

    @Test
    fun initials_match() {
        assertEquals("agreement_has_assigned_users", search("ahau").first())
        assertEquals("activity_log", search("al").first())
    }

    @Test
    fun unrelated_text_does_not_match() {
        assertEquals(emptyList<String>(), search("zebra"))
        assertNull(fuzzyScore("xyz", "orders"))
        // A short query is not stretched across a long name.
        assertNull(fuzzyScore("aes", "agreement_has_assigned_users"))
        assertNotNull(fuzzyScore("ord", "big_orders"))
    }
}
