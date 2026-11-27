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
import com.intrudersnap.prototype.domain.ThresholdPolicy
import com.intrudersnap.prototype.notify.Notifier

/**
 * Handles watch-login callbacks. Called from IntruderSnapAdminReceiver on a background
 * thread (inside goAsync()), so blocking I/O is fine here.
 *
 * Logs never contain credentials - Android does not provide them to admin apps.
 */
object LoginEventProcessor {

    private const val TAG = "IntruderSnap/Login"

    const val CALLBACK_FAILED = "onPasswordFailed(Context, Intent, UserHandle)"
    const val CALLBACK_SUCCEEDED = "onPasswordSucceeded(Context, Intent, UserHandle)"

    fun onPasswordFailed(context: Context, user: UserHandle) {
        val events = EventRepository.get(context)
        val settings = SettingsRepository.get(context)
        settings.reload()

        val now = System.currentTimeMillis()
        val system = AdminController.readSystemFailedAttempts(context)
        val prototypeCount = settings.registerFailure(system.countOrMinusOne, now)
        val threshold = settings.current.threshold

        Log.i(
            TAG,
            "onPasswordFailed received: systemCount=${system.countOrNull ?: "unavailable"} " +
                "prototypeCount=$prototypeCount threshold=$threshold " +
                "uiInProcess=${RuntimeEnvironment.uiCreatedInProcess} processAgeMs=${RuntimeEnvironment.processAgeMs()} " +
                RuntimeEnvironment.backgroundState(context)
        )

        events.record(
            type = EventType.PASSWORD_FAILED,
            systemFailedCount = system.countOrMinusOne,
            systemCountError = system.errorOrNull,
            callbackReceived = true,
            callbackName = CALLBACK_FAILED,
            prototypeFailCount = prototypeCount,
            threshold = threshold,
            note = userNote(user),
        )

        val effective = ThresholdPolicy.effectiveCount(system.countOrNull, prototypeCount)
        if (ThresholdPolicy.shouldTrigger(effective, threshold, settings.current.incidentTriggered)) {
            val notificationStatus = Notifier.showIntrusion(context, effective, threshold, now)
            val source = if (system.countOrNull != null) "system counter" else "prototype counter (system counter unavailable)"
            val intrusion = events.record(
                type = EventType.INTRUSION_THRESHOLD_REACHED,
                systemFailedCount = system.countOrMinusOne,
                systemCountError = system.errorOrNull,
                callbackReceived = true,
                callbackName = CALLBACK_FAILED,
                prototypeFailCount = prototypeCount,
                threshold = threshold,
                note = "Effective count $effective >= threshold $threshold (source: $source). $notificationStatus.",
            )
            settings.markIncidentTriggered(intrusion.id)
            Log.i(TAG, "Intrusion threshold reached: effective=$effective threshold=$threshold")
        }

        // Feasibility test E (opt-in, one-shot). Does nothing unless the tester armed it.
        if (settings.current.backgroundExperimentArmed) {
            BackgroundCameraExperiment.attemptFromBackground(context)
        }
    }

    fun onPasswordSucceeded(context: Context, user: UserHandle) {
        val events = EventRepository.get(context)
        val settings = SettingsRepository.get(context)
        settings.reload()

        val now = System.currentTimeMillis()
        // By the time this callback arrives the system has already reset its counter
        // (normally 0), so the "previous" count comes from what we stored on the last failure.
        val system = AdminController.readSystemFailedAttempts(context)
        val before = settings.current
        val previous = if (before.lastSystemFailedCount >= 0) before.lastSystemFailedCount else before.prototypeFailCount

        Log.i(TAG, "onPasswordSucceeded received: previousFailed=$previous systemCountNow=${system.countOrNull ?: "unavailable"}")

        events.record(
            type = EventType.PASSWORD_SUCCEEDED,
            systemFailedCount = system.countOrMinusOne,
            systemCountError = system.errorOrNull,
            callbackReceived = true,
            callbackName = CALLBACK_SUCCEEDED,
            prototypeFailCount = before.prototypeFailCount,
            previousFailedCount = previous,
            threshold = before.threshold,
            note = "Incident counter reset. " + userNote(user),
        )
        settings.resetAfterSuccess(now)
    }

    private fun userNote(user: UserHandle): String =
        if (user == Process.myUserHandle()) "Callback for this app's user." else "Callback for a different user profile."
}
