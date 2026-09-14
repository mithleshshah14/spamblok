package com.spamblok.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SpamNumberListStoreTest {

    @Test
    fun parseLines_extractsDigitsOnly() {
        val lines = listOf("+91 98765 43210", "9876543211")
        assertEquals(listOf("919876543210", "9876543211"), SpamNumberListStore.parseLines(lines))
    }

    @Test
    fun parseLines_skipsBlankLinesAndComments() {
        val lines = listOf(
            "# Yet Another Call Blocker export",
            "",
            "  ",
            "9876543210",
            "# another comment",
        )
        assertEquals(listOf("9876543210"), SpamNumberListStore.parseLines(lines))
    }

    @Test
    fun parseLines_supportsInlineComments() {
        val lines = listOf("9876543210 # known scam")
        assertEquals(listOf("9876543210"), SpamNumberListStore.parseLines(lines))
    }

    @Test
    fun parseLines_discardsTooShortJunk() {
        val lines = listOf("123", "not a number", "9876543210")
        assertEquals(listOf("9876543210"), SpamNumberListStore.parseLines(lines))
    }
}
