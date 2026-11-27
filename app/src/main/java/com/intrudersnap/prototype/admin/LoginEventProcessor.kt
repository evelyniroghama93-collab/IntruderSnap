package com.intrudersnap.prototype.admin

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.intrudersnap.prototype.camera.BackgroundCameraExperiment
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.RuntimeEnvironment
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.domain.IncidentTracker
import com.intrudersnap.prototype.notify.Notifier

/**
 * Handles watch-login callbacks (called on a background thread inside goAsync()).
 * Android never provides the entered credential, and nothing here logs one.
 */
object LoginEventProcessor {

    private const val TAG = "IntruderSnap/Login"

    const val CALLBACK_FAILED = "onPasswordFailed(Context, Intent, UserHandle)"
    const val CALLBACK_SUCCEEDED = "onPasswordSucceeded(Context, Intent, UserHandle)"
    const val SOURCE_FAILED = "DeviceAdminReceiver.onPasswordFailed"
    const val SOURCE_SUCCEEDED = "DeviceAdminReceiver.onPasswordSucceeded"

    fun onPasswordFailed(context: Context, user: UserHandle) {
        val events = EventRepository.get(context)
        val settings = SettingsRepository.get(context)
        settings.reload()

        val now = System.currentTimeMillis()
        val system = AdminController.readSystemFailedAttempts(context)
        val threshold = settings.current.threshold
        val cold = !RuntimeEnvironment.uiCreatedInProcess

        val decision = settings.updateIncident { s ->
            val d = IncidentTracker.onFailed(s.incident, now, system.countOrNull, threshold)
            d.state to d
        }
        settings.markCallback(now, failed = true, cold = cold)

        Log.i(
            TAG,
            "onPasswordFailed received: systemCount=${system.countOrNull ?: "unavailable"} " +
                "class=${decision.classification.name} incidentCount=${decision.incidentCount} threshold=$threshold " +
                "newIncident=${decision.newIncident} missed=${decision.missedCallbacks} " +
                "uiInProcess=${RuntimeEnvironment.uiCreatedInProcess} processAgeMs=${RuntimeEnvironment.processAgeMs()} " +
                RuntimeEnvironment.backgroundState(context)
        )

        val note = buildString {
            append(userNote(user))
            if (decision.newIncident) append(" New incident.")
            if (decision.missedCallbacks > 0) {
                append(" Android counted ${decision.missedCallbacks} failed attempt(s) that were not delivered as callbacks; reconciled.")
            }
            if (decision.classification == IncidentTracker.Classification.DUPLICATE_SUSPECT) {
                append(" Same system count as the previous failure: treated as a duplicate/delayed delivery and not counted again.")
            }
            if (decision.classification == IncidentTracker.Classification.COUNTER_RESET_UNSEEN) {
                append(" Android's counter went down without a success callback; a new incident was started.")
            }
        }
        events.record(
            type = EventType.PASSWORD_FAILED,
            systemFailedCount = system.countOrMinusOne,
            systemCountError = system.errorOrNull,
            callbackReceived = true,
            callbackName = CALLBACK_FAILED,
            prototypeFailCount = decision.state.appCount,
            threshold = threshold,
            note = note,
            source = SOURCE_FAILED,
            classification = decision.classification.name,
            incidentCount = decision.incidentCount,
        )

        if (decision.trigger) {
            raiseIntrusion(context, decision.incidentCount, threshold, system.countOrMinusOne, SOURCE_FAILED, now)
        }

        // Labs feasibility test E (opt-in, one-shot, experimental tools only).
        val s = settings.current
        if (s.experimentalToolsEnabled && s.backgroundExperimentArmed) {
            BackgroundCameraExperiment.attemptFromBackground(context)
        }
    }

    fun onPasswordSucceeded(context: Context, user: UserHandle) {
        val events = EventRepository.get(context)
        val settings = SettingsRepository.get(context)
        settings.reload()

        val now = System.currentTimeMillis()
        val system = AdminController.readSystemFailedAttempts(context)
        val before = settings.current.incident
        // Android has already reset its counter, so the previous count comes from stored state.
        val previous = if (before.lastSystemCount > 0) before.lastSystemCount else before.appCount
        settings.updateIncident { IncidentTracker.onSucceeded(it.incident) to Unit }
        settings.markCallback(now, failed = false, cold = !RuntimeEnvironment.uiCreatedInProcess)

        Log.i(TAG, "onPasswordSucceeded received: previousFailed=$previous systemCountNow=${system.countOrNull ?: "unavailable"}")

        events.record(
            type = EventType.PASSWORD_SUCCEEDED,
            systemFailedCount = system.countOrMinusOne,
            systemCountError = system.errorOrNull,
            callbackReceived = true,
            callbackName = CALLBACK_SUCCEEDED,
            prototypeFailCount = before.appCount,
            previousFailedCount = previous,
            threshold = settings.current.threshold,
            note = "Incident closed. " + userNote(user),
            source = SOURCE_SUCCEEDED,
        )
    }

    /** Shared by callbacks and reconciliation. */
    fun raiseIntrusion(context: Context, incidentCount: Int, threshold: Int, systemCount: Int, source: String, now: Long) {
        val status = Notifier.showIntrusion(context, incidentCount, threshold, now)
        val intrusion = EventRepository.get(context).record(
            type = EventType.INTRUSION_THRESHOLD_REACHED,
            systemFailedCount = systemCount,
            callbackReceived = source == SOURCE_FAILED,
            callbackName = if (source == SOURCE_FAILED) CALLBACK_FAILED else "",
            threshold = threshold,
            note = "Incident count $incidentCount >= threshold $threshold. $status.",
            source = source,
            incidentCount = incidentCount,
        )
        SettingsRepository.get(context).setPendingAlert(intrusion.id)
        Log.i(TAG, "Intrusion threshold reached: incidentCount=$incidentCount threshold=$threshold source=$source")
    }

    private fun userNote(user: UserHandle): String =
        if (user == Process.myUserHandle()) "Callback for this app's user." else "Callback for a different user profile."
}
