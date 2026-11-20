package com.intrudersnap.prototype.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intrudersnap.prototype.domain.CameraTestKey
import com.intrudersnap.prototype.domain.TestOutcome
import com.intrudersnap.prototype.domain.TestResult
import com.intrudersnap.prototype.ui.components.InfoRow
import com.intrudersnap.prototype.ui.components.OutcomeChip
import com.intrudersnap.prototype.ui.components.SectionCard
import com.intrudersnap.prototype.ui.components.formatTime

@Composable
fun CameraTestScreen(vm: CameraTestViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var previewOpen by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { previewOpen = false }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        val permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        vm.onPermissionResult(granted, permanentlyDenied)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "IntruderSnap never activates the camera secretly. Every test below is started by you, shows a " +
                "notification or preview, triggers Android's privacy indicator, and discards the frame. No photos are stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // A
        TestCard(CameraTestKey.A_PERMISSION, settings.cameraResult(CameraTestKey.A_PERMISSION)) {
            InfoRow("Current status", if (state.cameraGranted) "Granted" else "Not granted")
            if (!state.cameraGranted) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Request camera permission")
                }
            }
        }

        // B
        TestCard(CameraTestKey.B_VISIBLE_CAMERAX, settings.cameraResult(CameraTestKey.B_VISIBLE_CAMERAX)) {
            if (!state.cameraGranted) {
                Text("Grant camera permission (A) first.")
            } else if (!previewOpen) {
                Button(onClick = { previewOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Open camera preview") }
            } else {
                VisibleCameraTester(onResult = vm::onVisibleCaptureResult)
                OutlinedButton(onClick = { previewOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Close preview") }
            }
        }

        // C
        TestCard(CameraTestKey.C_FGS_VISIBLE, settings.cameraResult(CameraTestKey.C_FGS_VISIBLE)) {
            Text(
                "Starts CameraFeasibilityService while IntruderSnap is on screen. It shows a notification, captures one frame and discards it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { vm.startForegroundServiceTest() },
                enabled = state.cameraGranted && !previewOpen,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (previewOpen) "Close the preview first" else "Run foreground-service camera test") }
        }

        // D
        TestCard(CameraTestKey.D_FGS_REQUIREMENTS, settings.cameraResult(CameraTestKey.D_FGS_REQUIREMENTS)) {
            val r = state.requirements
            InfoRow("Device API / app targetSdk", "${r.deviceApi} / ${r.targetSdk}")
            InfoRow("FOREGROUND_SERVICE declared", yesNo(r.declaresForegroundService))
            InfoRow("FOREGROUND_SERVICE_CAMERA declared", yesNo(r.declaresForegroundServiceCamera))
            InfoRow("Service foregroundServiceType=camera", yesNo(r.serviceTypeIsCamera))
            InfoRow("CAMERA granted before start", yesNo(r.cameraGranted))
            Text(
                "Android 14 also treats camera as a while-in-use permission: a camera foreground service may only be " +
                    "started while the app is visible (or under a narrow system exemption). Running test C verifies the visible case.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // E
        TestCard(CameraTestKey.E_BACKGROUND_AFTER_CALLBACK, settings.cameraResult(CameraTestKey.E_BACKGROUND_AFTER_CALLBACK)) {
            Text(
                "One-shot experiment. When armed, the NEXT onPasswordFailed callback tries to start the camera foreground " +
                    "service from the background. If Android refuses, the result is recorded as BLOCKED. It never runs after " +
                    "a successful unlock.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Armed for next failed attempt", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.backgroundExperimentArmed,
                    onCheckedChange = { vm.setBackgroundExperimentArmed(it) },
                    enabled = state.cameraGranted && state.adminActive,
                )
            }
            if (!state.adminActive) Text("Requires an active device administrator.", style = MaterialTheme.typography.bodySmall)
            if (!state.cameraGranted) Text("Requires camera permission (A).", style = MaterialTheme.typography.bodySmall)
        }

        // F
        TestCard(CameraTestKey.F_PRIVACY_INDICATOR, settings.cameraResult(CameraTestKey.F_PRIVACY_INDICATOR)) {
            Text(
                "Manual observation: during test B or C, look for Android's green camera dot in the status bar. " +
                    "The app cannot read this indicator itself.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.recordPrivacyIndicator(true, "test B/C") }, modifier = Modifier.weight(1f)) { Text("Indicator seen") }
                OutlinedButton(onClick = { vm.recordPrivacyIndicator(false, "test B/C") }, modifier = Modifier.weight(1f)) { Text("Not seen") }
            }
        }

        // G
        TestCard(CameraTestKey.G_DENIAL_BEHAVIOUR, settings.cameraResult(CameraTestKey.G_DENIAL_BEHAVIOUR)) {
            Text(
                "Filled in automatically when Android refuses camera access (permission denied in A, or a refusal in C/E). " +
                    "Shows the exact exception or camera error that was received.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TestCard(key: CameraTestKey, result: TestResult, content: @Composable () -> Unit) {
    SectionCard("${key.code}. ${key.title}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutcomeChip(result.outcome)
            if (result.timestampMillis > 0) {
                Text(
                    formatTime(result.timestampMillis),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (result.detail.isNotBlank()) {
            Text(result.detail, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        content()
    }
}

/** Test B: CameraX preview + one in-memory capture while the app is visible. */
@Composable
private fun VisibleCameraTester(onResult: (TestOutcome, String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        future.addListener({
            try {
                val p = future.get()
                provider = p
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val selector = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                ready = true
            } catch (e: Exception) {
                currentOnResult(TestOutcome.FAIL, "CameraX could not bind while visible: ${e.javaClass.name}: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbind(preview, imageCapture)
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(260.dp))
    Button(
        onClick = {
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val size = "${image.width}x${image.height}"
                        image.close() // discarded; never written to storage
                        currentOnResult(
                            TestOutcome.PASS,
                            "CameraX preview and capture worked while IntruderSnap was visible ($size frame). " +
                                "Owner test frame - not intruder evidence, not stored.",
                        )
                    }

                    override fun onError(exception: ImageCaptureException) {
                        currentOnResult(TestOutcome.FAIL, "ImageCaptureException(error=${exception.imageCaptureError}): ${exception.message}")
                    }
                },
            )
        },
        enabled = ready,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Capture one test frame (discarded)") }
}

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
