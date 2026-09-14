package com.spamblok.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerRepositoryTest {

    @Test
    fun normalize_stripsNonDigits() {
        assertEquals("919876543210", CallerRepository.normalize("+91 98765 43210"))
        assertEquals("919876543210", CallerRepository.normalize("919876543210"))
    }

    @Test
    fun isMismatch_falseWhenEitherSideBlank() {
        assertFalse(CallerRepository.isMismatch(null, "VisaHunt"))
        assertFalse(CallerRepository.isMismatch("VisaHunt", null))
        assertFalse(CallerRepository.isMismatch("", "VisaHunt"))
        assertFalse(CallerRepository.isMismatch("VisaHunt", "  "))
    }

    @Test
    fun isMismatch_falseForCaseOrWhitespaceOnlyDifference() {
        assertFalse(CallerRepository.isMismatch("VisaHunt", "visahunt"))
        assertFalse(CallerRepository.isMismatch(" VisaHunt ", "VisaHunt"))
    }

    @Test
    fun isMismatch_trueForGenuinelyDifferentNames() {
        assertTrue(CallerRepository.isMismatch("VisaHunt", "Zomato Delivery"))
    }
}
