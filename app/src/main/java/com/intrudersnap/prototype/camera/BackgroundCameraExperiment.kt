package com.intrudersnap.prototype.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.domain.CameraTestKey
import com.intrudersnap.prototype.domain.TestOutcome

/**
 * Feasibility test E: can a camera foreground service be started from the background
 * right after a native lock-screen callback?
 *
 * Safeguards:
 *  - Opt-in and ONE-SHOT: the tester arms it on the Camera screen; it disarms itself on use.
 *  - Only on onPasswordFailed - never after a successful unlock (that would photograph the owner).
 *  - If Android allows the start, the service shows a visible notification, Android shows its
 *    camera privacy indicator, and the single frame is measured and discarded (not stored).
 *  - No attempt is made to work around a refusal; a refusal is recorded as BLOCKED.
 */
object BackgroundCameraExperiment {

    private const val TAG = "IntruderSnap/CamExp"

    fun attemptFromBackground(context: Context) {
        val settings = SettingsRepository.get(context)
        val events = EventRepository.get(context)
        settings.setBackgroundExperimentArmed(false)

        val key = CameraTestKey.E_BACKGROUND_AFTER_CALLBACK
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            val detail = "Not attempted: CAMERA permission is not granted. Grant it in test A and re-arm."
            settings.setCameraResult(key, TestOutcome.INCONCLUSIVE, detail)
            events.record(type = EventType.CAMERA_TEST, note = "Test E: $detail")
            return
        }

        val intent = Intent(context, CameraFeasibilityService::class.java)
            .putExtra(CameraFeasibilityService.EXTRA_MODE, CameraFeasibilityService.MODE_BACKGROUND)
        try {
            ContextCompat.startForegroundService(context, intent)
            val detail = "startForegroundService() accepted from background; waiting for the service to report " +
                "whether startForeground(type=camera) and frame capture succeed."
            settings.setCameraResult(key, TestOutcome.INCONCLUSIVE, detail)
            events.record(type = EventType.CAMERA_TEST, note = "Test E: $detail")
            Log.i(TAG, "Background startForegroundService accepted")
        } catch (e: Exception) {
            // On Android 12+ this is normally ForegroundServiceStartNotAllowedException.
            val detail = "BLOCKED at startForegroundService(): ${e.javaClass.name}: ${e.message}. " +
                "Android does not allow this app to start a foreground service from the background " +
                "after a device-admin broadcast."
            settings.setCameraResult(key, TestOutcome.BLOCKED, detail)
            settings.setCameraResult(CameraTestKey.G_DENIAL_BEHAVIOUR, TestOutcome.BLOCKED, "Test E: $detail The app caught the exception and continued; the lock-screen event was still recorded.")
            events.record(type = EventType.CAMERA_TEST, note = "Test E: $detail")
            Log.w(TAG, "Background start blocked: ${e.javaClass.simpleName}")
        }
    }
}
