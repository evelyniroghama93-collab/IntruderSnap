package com.intrudersnap.prototype.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.domain.CameraTestKey
import com.intrudersnap.prototype.domain.TestOutcome
import com.intrudersnap.prototype.notify.Notifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Visible camera foreground service used ONLY for feasibility tests C/D (started while the app
 * is on screen) and E (opt-in, started from the background after onPasswordFailed).
 *
 * It never captures silently:
 *  - startForeground() with a visible notification is called first;
 *  - Android shows its own camera privacy indicator while the camera is open;
 *  - exactly one frame is captured IN MEMORY, its size is recorded, and it is discarded.
 *
 * Android 14 requirements exercised here (test D):
 *  - manifest: <service android:foregroundServiceType="camera">
 *  - permissions: FOREGROUND_SERVICE and FOREGROUND_SERVICE_CAMERA
 *  - runtime: CAMERA permission granted before startForeground(type = CAMERA)
 *  - camera is a "while-in-use" permission: a camera FGS started while the app is in the
 *    background is expected to be refused (SecurityException) or denied camera access.
 *
 * Note: if startForeground() is refused after startForegroundService(), Android may later kill
 * the process ("did not call startForeground"). The result is saved before that can happen.
 */
class CameraFeasibilityService : LifecycleService() {

    private val finished = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var lastCameraStateError: String? = null
    private var mode: String = MODE_VISIBLE
    private var triggerContext: String = ""
    private var msSinceUiVisible: Long = -1L

    private val key: CameraTestKey
        get() = if (mode == MODE_BACKGROUND) CameraTestKey.E_BACKGROUND_AFTER_CALLBACK else CameraTestKey.C_FGS_VISIBLE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (running.getAndSet(true)) {
            Log.i(TAG, "Test already running; ignoring duplicate start")
            return START_NOT_STICKY
        }
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_VISIBLE
        triggerContext = intent?.getStringExtra(EXTRA_TRIGGER_CONTEXT).orEmpty()
        msSinceUiVisible = intent?.getLongExtra(EXTRA_MS_SINCE_UI_VISIBLE, -1L) ?: -1L
        val background = mode == MODE_BACKGROUND

        // 1. Promote to a camera-type foreground service.
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(
                this,
                Notifier.ID_CAMERA_SERVICE,
                Notifier.cameraServiceNotification(this, background),
                type,
            )
        } catch (e: Exception) {
            val detail = "startForeground(type=camera) was refused: ${e.javaClass.name}: ${e.message}"
            if (!background) {
                settings().setCameraResult(CameraTestKey.D_FGS_REQUIREMENTS, TestOutcome.FAIL, detail)
            }
            finish(TestOutcome.BLOCKED, detail, denial = true)
            return START_NOT_STICKY
        }

        if (!background) {
            settings().setCameraResult(
                CameraTestKey.D_FGS_REQUIREMENTS,
                TestOutcome.PASS,
                "startForeground(type=FOREGROUND_SERVICE_TYPE_CAMERA) accepted on API ${Build.VERSION.SDK_INT} " +
                    "with FOREGROUND_SERVICE_CAMERA declared and CAMERA granted, while the app was visible.",
            )
        }

        // 2. Runtime permission check (never request from a service).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish(TestOutcome.BLOCKED, "CAMERA permission not granted; camera not opened.", denial = true)
            return START_NOT_STICKY
        }

        // 3. Timeout guard.
        handler.postDelayed({
            val reason = lastCameraStateError?.let { " Last camera state error: $it." } ?: ""
            finish(
                TestOutcome.INCONCLUSIVE,
                "No frame and no error within ${TIMEOUT_MS / 1000}s.$reason",
                denial = lastCameraStateError != null,
            )
        }, TIMEOUT_MS)

        // 4. Capture exactly one in-memory frame.
        captureOneFrame(background)
        return START_NOT_STICKY
    }

    private fun captureOneFrame(background: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val camera = provider.bindToLifecycle(this, selector, imageCapture)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    state.error?.let { lastCameraStateError = "code ${it.code} (type ${state.type})" }
                }
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val size = "${image.width}x${image.height}"
                            image.close() // discarded, never written to storage
                            provider.unbind(imageCapture)
                            val context = if (background) "from the background after a lock-screen callback" else "while the app was visible"
                            finish(TestOutcome.PASS, "Camera delivered one $size frame $context through a camera foreground service. Frame discarded.", denial = false)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            provider.unbind(imageCapture)
                            val stateInfo = lastCameraStateError?.let { " Camera state error: $it." } ?: ""
                            val outcome = if (background) TestOutcome.BLOCKED else TestOutcome.FAIL
                            finish(
                                outcome,
                                "ImageCaptureException(error=${exception.imageCaptureError}): ${exception.message}.$stateInfo",
                                denial = background,
                            )
                        }
                    },
                )
            } catch (e: Exception) {
                val outcome = if (background) TestOutcome.BLOCKED else TestOutcome.FAIL
                finish(outcome, "Camera could not be opened: ${e.javaClass.name}: ${e.message}", denial = background)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finish(rawOutcome: TestOutcome, rawDetail: String, denial: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        var outcome = rawOutcome
        var detail = rawDetail
        if (mode == MODE_BACKGROUND) {
            if (triggerContext.isNotBlank()) detail += " Context at callback: $triggerContext."
            // Android lets an app start foreground services / use while-in-use permissions for a
            // few seconds after it leaves the screen. A success inside that window does not prove
            // true background access, so it is not counted as PASS.
            if (outcome == TestOutcome.PASS && msSinceUiVisible in 0L until GRACE_WINDOW_MS) {
                outcome = TestOutcome.INCONCLUSIVE
                detail += " Not counted as PASS: IntruderSnap was on screen less than ${GRACE_WINDOW_MS / 1000} s " +
                    "before the callback (Android grace period). Repeat with the app closed and the phone locked for 1+ minute."
            }
        }
        handler.removeCallbacksAndMessages(null)
        val label = if (mode == MODE_BACKGROUND) "Test E" else "Test C"
        Log.i(TAG, "$label finished: ${outcome.name}")
        // Persist synchronously BEFORE stopping, in case Android kills the process.
        settings().setCameraResult(key, outcome, detail)
        if (denial) {
            settings().setCameraResult(
                CameraTestKey.G_DENIAL_BEHAVIOUR,
                TestOutcome.BLOCKED,
                "$label: $detail The app caught the refusal, recorded it and stopped the service without crashing.",
            )
        }
        EventRepository.get(this).record(type = EventType.CAMERA_TEST, note = "$label ${outcome.label}: $detail")
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Was never promoted to foreground.
        }
        running.set(false)
        stopSelf()
    }

    private fun settings() = SettingsRepository.get(this)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running.set(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IntruderSnap/CamFGS"
        const val EXTRA_MODE = "mode"
        const val MODE_VISIBLE = "visible"
        const val MODE_BACKGROUND = "background"
        const val EXTRA_TRIGGER_CONTEXT = "trigger_context"
        const val EXTRA_MS_SINCE_UI_VISIBLE = "ms_since_ui_visible"
        private const val GRACE_WINDOW_MS = 30_000L
        private const val TIMEOUT_MS = 15_000L
        private val running = AtomicBoolean(false)
    }
}
