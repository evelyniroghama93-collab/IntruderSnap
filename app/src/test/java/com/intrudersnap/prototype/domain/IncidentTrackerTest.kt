package com.intrudersnap.prototype.domain

import com.intrudersnap.prototype.domain.IncidentTracker.Classification
import com.intrudersnap.prototype.domain.IncidentTracker.ReconciliationResult
import com.intrudersnap.prototype.domain.IncidentTracker.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentTrackerTest {

    private val t0 = 1_700_000_000_000L
    private val threshold = 3

    /** Applies a sequence of (secondsOffset, systemCount) failures; returns all decisions. */
    private fun run(vararg failures: Pair<Long, Int?>, start: State = State()): List<IncidentTracker.FailureDecision> {
        var s = start
        return failures.map { (sec, sys) ->
            IncidentTracker.onFailed(s, t0 + sec * 1000, sys, threshold).also { s = it.state }
        }
    }

    @Test
    fun threeConsecutiveFailuresTriggerOnceAtThreshold() {
        val d = run(0L to 1, 2L to 2, 4L to 3, 6L to 4)
        assertEquals(listOf(1, 2, 3, 4), d.map { it.incidentCount })
        assertEquals(listOf(false, false, true, false), d.map { it.trigger })
        assertTrue(d.all { it.classification == Classification.NORMAL })
        assertTrue(d[0].newIncident)
        assertFalse(d[1].newIncident)
    }

    @Test
    fun stalledCounterIsDelayedDeliveryAndStillCounted() {
        val d = run(0L to 1, 1L to 1, 2L to 2)
        assertEquals(Classification.DELAYED_DELIVERY, d[1].classification)
        assertEquals(2, d[1].incidentCount)
        assertEquals(3, d[2].incidentCount)
        assertTrue(d[2].trigger)
    }

    @Test
    fun counterAheadIsFollowedAndCanTrigger() {
        val d = run(0L to 1, 5L to 3)
        assertEquals(Classification.COUNTER_AHEAD, d[1].classification)
        assertEquals(1, d[1].missedCallbacks)
        assertEquals(3, d[1].incidentCount)
        assertTrue(d[1].trigger)
    }

    /**
     * Replays the Redmi 12C run of 2026-11-28 20:35: 3 failures -> alert, fingerprint unlock
     * (Android's counter stays 3), 16+ minutes later 3 more failures. The first broadcast of
     * the second group was processed ~5 s late (cold start), when Android already counted 5.
     * Observed with the old rule: incident counts 1, 1 (dropped as "duplicate"), 2 -> no alert.
     * Required: the second alert fires on the third failure.
     */
    @Test
    fun coldStartDelayAfterBiometricGapStillAlerts() {
        val first = run(0L to 1, 2L to 2, 4L to 3)
        assertTrue(first[2].trigger)
        val second = run(1000L to 5, 1001L to 5, 1045L to 6, start = first.last().state)
        assertTrue(second[0].newIncident)
        assertEquals(Classification.DELAYED_DELIVERY, second[1].classification)
        assertEquals(listOf(2, 2, 3), second.map { it.incidentCount })
        assertEquals(listOf(false, false, true), second.map { it.trigger })
    }

    @Test
    fun biometricUnlockGapStartsNewIncidentAndCanAlertAgain() {
        // 3 failures -> alert; owner unlocks with fingerprint (no success callback, counter stays 3);
        // 20 minutes later 3 more failures must alert again.
        val first = run(0L to 1, 2L to 2, 4L to 3)
        assertTrue(first[2].trigger)
        val later = run(1204L to 4, 1206L to 5, 1208L to 6, start = first.last().state)
        assertTrue(later[0].newIncident)
        assertEquals(listOf(1, 2, 3), later.map { it.incidentCount })
        assertTrue(later[2].trigger)
    }

    @Test
    fun counterGoingDownWithoutSuccessStartsNewIncident() {
        val first = run(0L to 1, 2L to 2)
        val d = IncidentTracker.onFailed(first.last().state, t0 + 10_000, 1, threshold)
        assertEquals(Classification.COUNTER_RESET_UNSEEN, d.classification)
        assertTrue(d.newIncident)
        assertEquals(1, d.incidentCount)
    }

    @Test
    fun successClosesIncidentAndNextFailureStartsAtOne() {
        val first = run(0L to 1, 2L to 2, 4L to 3)
        val afterSuccess = IncidentTracker.onSucceeded(first.last().state)
        assertFalse(afterSuccess.incidentOpen)
        assertFalse(afterSuccess.triggered)
        val d = IncidentTracker.onFailed(afterSuccess, t0 + 30_000, 1, threshold)
        assertTrue(d.newIncident)
        assertEquals(1, d.incidentCount)
    }

    @Test
    fun withoutSystemCountTheAppCountIsUsed() {
        val d = run(0L to null, 2L to null, 4L to null)
        assertEquals(listOf(1, 2, 3), d.map { it.incidentCount })
        assertTrue(d.all { it.classification == Classification.SYSTEM_COUNT_UNAVAILABLE })
        assertTrue(d[2].trigger)
    }

    @Test
    fun reconcileConsistent() {
        val s = run(0L to 1, 2L to 2).last().state
        val r = IncidentTracker.reconcile(s, 2, threshold, t0 + 60_000)
        assertEquals(ReconciliationResult.CONSISTENT, r.result)
        assertFalse(r.trigger)
    }

    @Test
    fun reconcileDetectsMissedFailuresAndTriggers() {
        val s = run(0L to 1).last().state
        val r = IncidentTracker.reconcile(s, 4, threshold, t0 + 60_000)
        assertEquals(ReconciliationResult.MISSED_FAILURES, r.result)
        assertEquals(3, r.missedFailures)
        assertEquals(4, r.incidentCount)
        assertTrue(r.trigger)
        assertTrue(r.state.triggered)
    }

    @Test
    fun reconcileDoesNotTriggerTwice() {
        val s = run(0L to 1, 2L to 2, 4L to 3).last().state // already triggered
        val r = IncidentTracker.reconcile(s, 5, threshold, t0 + 60_000)
        assertEquals(ReconciliationResult.MISSED_FAILURES, r.result)
        assertFalse(r.trigger)
    }

    @Test
    fun reconcileObservesResetToZero() {
        val s = run(0L to 1, 2L to 2).last().state
        val r = IncidentTracker.reconcile(s, 0, threshold, t0 + 60_000)
        assertEquals(ReconciliationResult.RESET_OBSERVED, r.result)
        assertFalse(r.state.incidentOpen)
    }

    @Test
    fun reconcileWithMissedFailuresWhenNoIncidentOpen() {
        val closed = IncidentTracker.onSucceeded(run(0L to 1).last().state) // lastSystemCount = 0
        val r = IncidentTracker.reconcile(closed, 2, threshold, t0 + 60_000)
        assertEquals(ReconciliationResult.MISSED_FAILURES, r.result)
        assertEquals(2, r.missedFailures)
        assertEquals(2, r.incidentCount)
        assertTrue(r.state.incidentOpen)
        assertFalse(r.trigger)
    }

    @Test
    fun reconcileUnavailable() {
        val s = State()
        val r = IncidentTracker.reconcile(s, null, threshold, t0)
        assertEquals(ReconciliationResult.UNAVAILABLE, r.result)
        assertEquals(s, r.state)
    }
}
