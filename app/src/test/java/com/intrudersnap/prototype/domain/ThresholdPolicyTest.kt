package com.intrudersnap.prototype.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdPolicyTest {

    @Test
    fun systemCountIsPreferredWhenAvailable() {
        assertEquals(4, ThresholdPolicy.effectiveCount(systemCount = 4, prototypeCount = 2))
    }

    @Test
    fun prototypeCountIsUsedWhenSystemCountUnavailable() {
        assertEquals(2, ThresholdPolicy.effectiveCount(systemCount = null, prototypeCount = 2))
        assertEquals(2, ThresholdPolicy.effectiveCount(systemCount = -1, prototypeCount = 2))
    }

    @Test
    fun triggersExactlyAtThreshold() {
        assertFalse(ThresholdPolicy.shouldTrigger(effectiveCount = 2, threshold = 3, alreadyTriggered = false))
        assertTrue(ThresholdPolicy.shouldTrigger(effectiveCount = 3, threshold = 3, alreadyTriggered = false))
    }

    @Test
    fun triggersOnlyOncePerIncident() {
        assertFalse(ThresholdPolicy.shouldTrigger(effectiveCount = 4, threshold = 3, alreadyTriggered = true))
    }

    @Test
    fun triggersWhenCountJumpsPastThreshold() {
        // e.g. system counter already above the threshold because the app missed earlier callbacks
        assertTrue(ThresholdPolicy.shouldTrigger(effectiveCount = 6, threshold = 5, alreadyTriggered = false))
    }

    @Test
    fun customThresholdValidation() {
        assertFalse(ThresholdPolicy.isValidCustomThreshold(0))
        assertTrue(ThresholdPolicy.isValidCustomThreshold(1))
        assertTrue(ThresholdPolicy.isValidCustomThreshold(30))
        assertFalse(ThresholdPolicy.isValidCustomThreshold(31))
    }
}
