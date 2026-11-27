package com.intrudersnap.prototype.domain

/**
 * Pure incident logic (no Android types) so it can be unit tested exhaustively.
 *
 * Android's DevicePolicyManager counter (getCurrentFailedPasswordAttempts) is the source of
 * truth when it can be read. It only resets after a successful CREDENTIAL entry - not after a
 * fingerprint/face unlock. The tracker therefore measures each incident relative to a
 * baseline instead of using the raw system count.
 *
 * Rules:
 *  1. DUPLICATE: same system count as the previous failure of the open incident. A genuine
 *     new failure always increments Android's counter, so an equal count is a duplicate or
 *     delayed delivery of the same failure -> recorded but not counted again.
 *  2. A new incident starts when there is no open incident, when the previous failure is
 *     older than [INCIDENT_GAP_MS] (covers biometric unlocks, which send no success
 *     callback), or when the system counter went DOWN without a success callback.
 *  3. MISSED_CALLBACKS: inside an incident, the system count jumped by more than one;
 *     the difference is the number of failures Android counted but did not deliver.
 *  4. Incident count = systemCount - baseline (system available) or the app's own count.
 *  5. The threshold triggers at most once per incident.
 */
object IncidentTracker {

    const val INCIDENT_GAP_MS = 15 * 60_000L

    data class State(
        val incidentOpen: Boolean = false,
        /** System count just before the incident's first failure. */
        val baseline: Int = 0,
        /** Failures counted by the app in this incident (fallback when system count is unavailable). */
        val appCount: Int = 0,
        /** Last system count seen on a failure (-1 = unknown). */
        val lastSystemCount: Int = -1,
        /** Wall-clock time of the last counted failure (0 = none). */
        val lastFailedAt: Long = 0L,
        val triggered: Boolean = false,
    )

    enum class Classification(val label: String) {
        NORMAL("Normal"),
        DUPLICATE_SUSPECT("Duplicate (not counted)"),
        MISSED_CALLBACKS("Missed callbacks reconciled"),
        COUNTER_RESET_UNSEEN("System counter reset without a success callback"),
        SYSTEM_COUNT_UNAVAILABLE("System counter unavailable"),
    }

    data class FailureDecision(
        val classification: Classification,
        val newIncident: Boolean,
        val incidentCount: Int,
        val missedCallbacks: Int,
        val trigger: Boolean,
        val state: State,
    )

    fun onFailed(state: State, now: Long, systemCount: Int?, threshold: Int): FailureDecision {
        val sys = systemCount?.takeIf { it >= 0 }

        // 1. Duplicate delivery of the same failure.
        if (sys != null && sys > 0 && state.incidentOpen && sys == state.lastSystemCount &&
            now - state.lastFailedAt in 0 until INCIDENT_GAP_MS
        ) {
            return FailureDecision(
                classification = Classification.DUPLICATE_SUSPECT,
                newIncident = false,
                incidentCount = countFor(state, sys),
                missedCallbacks = 0,
                trigger = false,
                state = state,
            )
        }

        val gapExpired = state.lastFailedAt > 0 && now - state.lastFailedAt >= INCIDENT_GAP_MS
        val counterWentDown = sys != null && state.incidentOpen && state.lastSystemCount >= 0 &&
            sys <= state.lastSystemCount
        val newIncident = !state.incidentOpen || gapExpired || counterWentDown

        val missed = if (!newIncident && sys != null && state.lastSystemCount >= 0 && sys > state.lastSystemCount + 1) {
            sys - state.lastSystemCount - 1
        } else {
            0
        }

        val next = if (newIncident) {
            State(
                incidentOpen = true,
                baseline = if (sys != null) (sys - 1).coerceAtLeast(0) else 0,
                appCount = 1,
                lastSystemCount = sys ?: -1,
                lastFailedAt = now,
                triggered = false,
            )
        } else {
            state.copy(
                appCount = state.appCount + 1 + missed,
                lastSystemCount = sys ?: state.lastSystemCount,
                lastFailedAt = now,
            )
        }

        val count = countFor(next, sys)
        val trigger = ThresholdPolicy.shouldTrigger(count, threshold, next.triggered)
        val classification = when {
            sys == null -> Classification.SYSTEM_COUNT_UNAVAILABLE
            counterWentDown && !gapExpired -> Classification.COUNTER_RESET_UNSEEN
            missed > 0 -> Classification.MISSED_CALLBACKS
            else -> Classification.NORMAL
        }
        return FailureDecision(
            classification = classification,
            newIncident = newIncident,
            incidentCount = count,
            missedCallbacks = missed,
            trigger = trigger,
            state = if (trigger) next.copy(triggered = true) else next,
        )
    }

    /** A successful credential entry closes the incident; Android resets its counter to 0. */
    fun onSucceeded(state: State): State =
        State(incidentOpen = false, baseline = 0, appCount = 0, lastSystemCount = 0, lastFailedAt = state.lastFailedAt, triggered = false)

    private fun countFor(state: State, sys: Int?): Int =
        if (sys != null) (sys - state.baseline).coerceAtLeast(1) else state.appCount

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
