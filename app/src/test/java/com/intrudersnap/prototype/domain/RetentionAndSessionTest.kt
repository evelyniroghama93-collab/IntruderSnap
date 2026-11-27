package com.intrudersnap.prototype.domain

import com.intrudersnap.prototype.auth.SessionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionAndSessionTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun retentionCutoff() {
        val now = 1_700_000_000_000L
        assertEquals(now - 90 * day, RetentionPolicy.cutoff(now, 90))
        assertEquals(now - 30 * day, RetentionPolicy.cutoff(now, 30))
    }

    @Test
    fun invalidRetentionFallsBackToDefault() {
        assertEquals(RetentionPolicy.DEFAULT_DAYS, RetentionPolicy.sanitize(7))
        assertEquals(RetentionPolicy.DEFAULT_DAYS, RetentionPolicy.sanitize(-1))
        assertEquals(365, RetentionPolicy.sanitize(365))
    }

    @Test
    fun sessionExpires() {
        val at = 10_000L
        assertTrue(SessionPolicy.isActive(at, at + 1))
        assertTrue(SessionPolicy.isActive(at, at + SessionPolicy.SESSION_MS - 1))
        assertFalse(SessionPolicy.isActive(at, at + SessionPolicy.SESSION_MS))
        assertFalse(SessionPolicy.isActive(-1, at))
    }

    @Test
    fun destructiveActionsNeedFreshAuthentication() {
        val at = 10_000L
        assertTrue(SessionPolicy.isFresh(at, at + 30_000))
        assertFalse(SessionPolicy.isFresh(at, at + SessionPolicy.FRESH_PROOF_MS))
    }

    @Test
    fun clockGoingBackwardsIsNotTrusted() {
        assertFalse(SessionPolicy.isActive(10_000L, 5_000L))
        assertFalse(SessionPolicy.isFresh(10_000L, 5_000L))
    }
}
