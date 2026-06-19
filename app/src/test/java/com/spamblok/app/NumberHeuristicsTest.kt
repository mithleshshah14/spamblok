package com.spamblok.app

import com.spamblok.app.NumberHeuristics.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberHeuristicsTest {

    private fun verdict(raw: String) = NumberHeuristics.classify(raw).verdict

    @Test
    fun telemarketer140_isLikelySpam() {
        assertEquals(Verdict.LIKELY_SPAM, verdict("1401234567"))
        assertEquals(Verdict.LIKELY_SPAM, verdict("+91 140 123 4567"))
        assertEquals(Verdict.LIKELY_SPAM, verdict("0140-123-4567"))
    }

    @Test
    fun service160_isLikelyService() {
        assertEquals(Verdict.LIKELY_SERVICE, verdict("1601234567"))
        assertEquals(Verdict.LIKELY_SERVICE, verdict("+911600123456"))
    }

    @Test
    fun standardMobile_isNeutral() {
        assertEquals(Verdict.NEUTRAL, verdict("9876543210"))
        assertEquals(Verdict.NEUTRAL, verdict("+91 98765 43210"))
        assertEquals(Verdict.NEUTRAL, verdict("098765 43210"))
    }

    @Test
    fun bengaluruLandline_isNeutral_notMisflagged() {
        // The eKart number from our Phase 1 test: 080 3503 7777 (a landline).
        // We don't try to name it, but it must NOT be flagged as spam.
        assertEquals(Verdict.NEUTRAL, verdict("+918035037777"))
        assertEquals(Verdict.NEUTRAL, verdict("08035037777"))
    }

    @Test
    fun foreignNumber_isSuspicious() {
        assertEquals(Verdict.SUSPICIOUS, verdict("+1 408 555 0199"))
        assertEquals(Verdict.SUSPICIOUS, verdict("00442071234567"))
    }

    @Test
    fun shortCode_isNeutral() {
        assertEquals(Verdict.NEUTRAL, verdict("121"))
        assertEquals(Verdict.NEUTRAL, verdict("198"))
    }

    @Test
    fun garbage_isSuspicious() {
        assertEquals(Verdict.SUSPICIOUS, verdict("12"))
        assertEquals(Verdict.SUSPICIOUS, verdict("1234567890123456"))
    }
}
