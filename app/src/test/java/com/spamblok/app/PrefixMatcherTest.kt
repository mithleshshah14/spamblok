package com.spamblok.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixMatcherTest {

    @Test
    fun normalizePrefix_stripsNonDigits() {
        assertEquals("9180", PrefixMatcher.normalizePrefix("+9180"))
        assertEquals("80", PrefixMatcher.normalizePrefix("80"))
        assertEquals("080", PrefixMatcher.normalizePrefix("080"))
        assertEquals("9180", PrefixMatcher.normalizePrefix("+91 80"))
    }

    @Test
    fun plainPrefix_matchesWithAndWithoutCountryCode() {
        val prefixes = listOf("80")
        assertEquals("80", PrefixMatcher.match(prefixes, "+918012345678"))
        assertEquals("80", PrefixMatcher.match(prefixes, "918012345678"))
        assertEquals("80", PrefixMatcher.match(prefixes, "8012345678"))
    }

    @Test
    fun countryCodePrefix_matchesRawNumber() {
        val prefixes = listOf("9180")
        assertEquals("9180", PrefixMatcher.match(prefixes, "+918012345678"))
        assertEquals("9180", PrefixMatcher.match(prefixes, "918012345678"))
    }

    @Test
    fun trunkZeroPrefix_matchesLocallyDialedNumber() {
        val prefixes = listOf("080")
        assertEquals("080", PrefixMatcher.match(prefixes, "08012345678"))
    }

    @Test
    fun bareTenDigitNumber_matchesCountryCodedPrefix() {
        val prefixes = listOf("9198")
        assertEquals("9198", PrefixMatcher.match(prefixes, "9812345678"))
    }

    @Test
    fun noMatch_returnsNull() {
        val prefixes = listOf("140", "160")
        assertNull(PrefixMatcher.match(prefixes, "+919876543210"))
    }

    @Test
    fun emptyPrefixList_returnsNull() {
        assertNull(PrefixMatcher.match(emptyList(), "+919876543210"))
    }
}
