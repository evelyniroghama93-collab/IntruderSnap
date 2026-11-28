package com.intrudersnap.prototype.domain

/**
 * Pure incident logic (no Android types) so it can be unit tested exhaustively.
 *
 * Two sources of truth are combined:
 *  - every onPasswordFailed broadcast is ONE failed attempt (Android sends exactly one per
 *    failure), counted in [State.appCount];
 *  - Android's counter (getCurrentFailedPasswordAttempts) is read when the broadcast is
 *    PROCESSED, not when the failure happened. After a cold start (several seconds) it may
 *    already include later failures whose broadcasts are still on their way.
 *
 * So the counter is never used to discard a broadcast. The incident count is
 * max(appCount, systemCount - baseline): the counter can only ADD failures that were never
 * delivered, never remove delivered ones. (Device finding, Redmi 12C 2026-11-28: an earlier
 * "same count = duplicate" rule dropped a real failure after a cold start and missed an alert.)
 *
 * Rules:
 *  1. A new incident starts when there is no open incident, when the previous failure is
 *     older than [INCIDENT_GAP_MS] (covers biometric unlocks, which send no success callback
 *     and do not reset Android's counter), or when Android's counter went DOWN without a
 *     success callback.
 *  2. Baseline = Android's counter just before the incident: the last value seen if it is
 *     lower than now, 0 after a reset, otherwise (nothing known) current - 1.
 *  3. DELAYED_DELIVERY: the counter did not advance since the previous broadcast, meaning the
 *     previous broadcast was processed late. It is still counted.
 *  4. COUNTER_AHEAD: Android counted more failures than broadcasts received so far (some may
 *     still be arriving, or were never delivered); the incident count follows Android.
 *  5. The threshold triggers at most once per incident.
 */
object IncidentTracker {

    const val INCIDENT_GAP_MS = 15 * 60_000L

    data class State(
        val incidentOpen: Boolean = false,
        /** Android's counter just before the incident's first failure. */
        val baseline: Int = 0,
        /** Failed-attempt broadcasts received in this incident. */
        val appCount: Int = 0,
        /** Last value of Android's counter seen (-1 = unknown). */
        val lastSystemCount: Int = -1,
        /** Wall-clock time of the last failure broadcast (0 = none). */
        val lastFailedAt: Long = 0L,
        val triggered: Boolean = false,
    )

    enum class Classification(val label: String) {
        NORMAL("Normal"),
        DELAYED_DELIVERY("Delayed delivery (counted)"),
        COUNTER_AHEAD("Android counted more failures than received (count follows Android)"),
        COUNTER_RESET_UNSEEN("System counter reset without a success callback"),
        SYSTEM_COUNT_UNAVAILABLE("System counter unavailable"),
        // Labels for records written by builds 9-17 (kept so old history still reads correctly).
        DUPLICATE_SUSPECT("Duplicate (not counted - superseded rule)"),
        MISSED_CALLBACKS("Missed callbacks reconciled"),
    }

    data class FailureDecision(
        val classification: Classification,
        val newIncident: Boolean,
        val incidentCount: Int,
        /** Failures Android counted beyond the broadcasts received so far (0 if none). */
        val missedCallbacks: Int,
        val trigger: Boolean,
        val state: State,
    )

    fun onFailed(state: State, now: Long, systemCount: Int?, threshold: Int): FailureDecision {
        val sys = systemCount?.takeIf { it >= 0 }
        val last = state.lastSystemCount

        val gapExpired = state.lastFailedAt > 0 && now - state.lastFailedAt >= INCIDENT_GAP_MS
        val counterWentDown = sys != null && state.incidentOpen && last >= 0 && sys < last
        val newIncident = !state.incidentOpen || gapExpired || counterWentDown
        val counterStalled = sys != null && !newIncident && sys == last

        val next = if (newIncident) {
            val baseline = when {
                sys == null -> 0
                last in 0 until sys -> last
                last >= sys -> 0
                else -> sys - 1 // nothing known about the counter before this failure
            }
            State(
                incidentOpen = true,
                baseline = baseline,
                appCount = 1,
                lastSystemCount = sys ?: -1,
                lastFailedAt = now,
                triggered = false,
            )
        } else {
            state.copy(
                appCount = state.appCount + 1,
                lastSystemCount = sys ?: last,
                lastFailedAt = now,
            )
        }

        val systemCountInIncident = sys?.let { it - next.baseline }
        val count = countFor(next, sys)
        val ahead = ((systemCountInIncident ?: 0) - next.appCount).coerceAtLeast(0)
        val trigger = ThresholdPolicy.shouldTrigger(count, threshold, next.triggered)
        val classification = when {
            sys == null -> Classification.SYSTEM_COUNT_UNAVAILABLE
            counterWentDown && !gapExpired -> Classification.COUNTER_RESET_UNSEEN
            counterStalled -> Classification.DELAYED_DELIVERY
            ahead > 0 -> Classification.COUNTER_AHEAD
            else -> Classification.NORMAL
        }
        return FailureDecision(
            classification = classification,
            newIncident = newIncident,
            incidentCount = count,
            missedCallbacks = ahead,
            trigger = trigger,
            state = if (trigger) next.copy(triggered = true) else next,
        )
    }

    /** A successful credential entry closes the incident; Android resets its counter to 0. */
    fun onSucceeded(state: State): State =
        State(incidentOpen = false, baseline = 0, appCount = 0, lastSystemCount = 0, lastFailedAt = state.lastFailedAt, triggered = false)

    private fun countFor(state: State, sys: Int?): Int =
        if (sys != null) maxOf(state.appCount, sys - state.baseline) else state.appCount

    // ---------------------------------------------------------------- reconciliation

    enum class ReconciliationResult(val label: String) {
        CONSISTENT("Consistent with Android's counter"),
        MISSED_FAILURES("Android counted failures that were not delivered to IntruderSnap"),
        RESET_OBSERVED("Android's counter was reset (successful credential entry not delivered as a callback)"),
        UNAVAILABLE("Android's counter could not be read"),
    }

    data class Reconciliation(
        val result: ReconciliationResult,
        val missedFailures: Int,
        val incidentCount: Int,
        val trigger: Boolean,
        val state: State,
    )

    /**
     * Compares the stored state with the current system counter outside any callback
     * (app open, reboot, periodic job). Missed failures are added to the incident and can
     * trigger the threshold.
     */
    fun reconcile(state: State, systemCount: Int?, threshold: Int, now: Long): Reconciliation {
        val sys = systemCount?.takeIf { it >= 0 }
            ?: return Reconciliation(ReconciliationResult.UNAVAILABLE, 0, 0, false, state)
        val last = state.lastSystemCount
        if (last < 0 || sys == last) {
            val s = state.copy(lastSystemCount = sys)
            return Reconciliation(ReconciliationResult.CONSISTENT, 0, if (s.incidentOpen) countFor(s, sys) else 0, false, s)
        }
        if (sys == 0) {
            return Reconciliation(ReconciliationResult.RESET_OBSERVED, 0, 0, false, onSucceeded(state))
        }
        // sys > last: failures counted by Android but not delivered.
        // 0 < sys < last: counter was reset and then new failures were not delivered.
        val missed = if (sys > last) sys - last else sys
        val base = when {
            sys > last && state.incidentOpen -> state
            sys > last -> State(incidentOpen = true, baseline = last, appCount = 0, lastSystemCount = last, lastFailedAt = now)
            else -> State(incidentOpen = true, baseline = 0, appCount = 0, lastSystemCount = 0, lastFailedAt = now)
        }
        val next = base.copy(appCount = base.appCount + missed, lastSystemCount = sys)
        val count = countFor(next, sys)
        val trigger = ThresholdPolicy.shouldTrigger(count, threshold, next.triggered)
        return Reconciliation(
            ReconciliationResult.MISSED_FAILURES,
            missed,
            count,
            trigger,
            if (trigger) next.copy(triggered = true) else next,
        )
    }
}
