package com.intrudersnap.prototype.ui.camera

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.camera.CameraFeasibilityService
import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.domain.CameraTestKey
import com.intrudersnap.prototype.domain.TestOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Static checks for test D (Android 14 camera foreground-service requirements). */
data class FgsRequirements(
    val targetSdk: Int = 0,
    val deviceApi: Int = Build.VERSION.SDK_INT,
    val declaresForegroundService: Boolean = false,
    val declaresForegroundServiceCamera: Boolean = false,
    val serviceTypeIsCamera: Boolean = false,
    val cameraGranted: Boolean = false,
) {
    val allMet: Boolean
        get() = declaresForegroundService && declaresForegroundServiceCamera && serviceTypeIsCamera && cameraGranted
}

data class CameraScreenState(
    val cameraGranted: Boolean = false,
    val adminActive: Boolean = false,
    val requirements: FgsRequirements = FgsRequirements(),
)

class CameraTestViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository.get(app)
    private val events = EventRepository.get(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val _state = MutableStateFlow(CameraScreenState())
    val state: StateFlow<CameraScreenState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.reload()
            val ctx = getApplication<Application>()
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            _state.value = CameraScreenState(
                cameraGranted = granted,
                adminActive = AdminController.isAdminActive(ctx),
                requirements = readRequirements(granted),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readRequirements(cameraGranted: Boolean): FgsRequirements {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager
        val requested = try {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val typeIsCamera = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getServiceInfo(ComponentName(ctx, CameraFeasibilityService::class.java), 0)
                (info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
        return FgsRequirements(
            targetSdk = ctx.applicationInfo.targetSdkVersion,
            declaresForegroundService = Manifest.permission.FOREGROUND_SERVICE in requested,
            declaresForegroundServiceCamera = "android.permission.FOREGROUND_SERVICE_CAMERA" in requested,
            serviceTypeIsCamera = typeIsCamera,
            cameraGranted = cameraGranted,
        )
    }

    /** Test A (+ G when denied). */
    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (granted) {
                record(CameraTestKey.A_PERMISSION, TestOutcome.PASS, "CAMERA runtime permission granted by the owner.")
            } else {
                val extra = if (permanentlyDenied) " Android will no longer show the dialog; grant it from App info › Permissions." else ""
                record(CameraTestKey.A_PERMISSION, TestOutcome.BLOCKED, "CAMERA permission denied by the owner.$extra")
                record(
                    CameraTestKey.G_DENIAL_BEHAVIOUR,
                    TestOutcome.BLOCKED,
                    "Permission denied: the app cannot open any camera. CameraX/foreground-service tests report BLOCKED " +
                        "and the app keeps recording lock-screen events normally.",
                )
            }
            refresh()
        }
    }

    /** Test B result from the in-app CameraX preview. */
    fun onVisibleCaptureResult(outcome: TestOutcome, detail: String) {
        viewModelScope.launch(Dispatchers.IO) { record(CameraTestKey.B_VISIBLE_CAMERAX, outcome, detail) }
    }

    /** Tests C + D: started from the visible activity, which Android permits. */
    fun startForegroundServiceTest() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, CameraFeasibilityService::class.java)
            .putExtra(CameraFeasibilityService.EXTRA_MODE, CameraFeasibilityService.MODE_VISIBLE)
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            viewModelScope.launch(Dispatchers.IO) {
                record(CameraTestKey.C_FGS_VISIBLE, TestOutcome.FAIL, "startForegroundService() failed while visible: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    /** Test E: arm / disarm the one-shot background experiment. */
    fun setBackgroundExperimentArmed(armed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setBackgroundExperimentArmed(armed)
            if (armed) {
                record(
                    CameraTestKey.E_BACKGROUND_AFTER_CALLBACK,
                    TestOutcome.NOT_TESTED,
                    "Armed. Lock the phone and enter ONE wrong credential; the next onPasswordFailed will attempt the start.",
                )
            }
        }
    }

    /** Test F: manual observation of Android's camera privacy indicator. */
    fun recordPrivacyIndicator(seen: Boolean, during: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (seen) {
                record(CameraTestKey.F_PRIVACY_INDICATOR, TestOutcome.PASS, "Tester observed the green camera privacy indicator during $during.")
            } else {
                record(CameraTestKey.F_PRIVACY_INDICATOR, TestOutcome.FAIL, "Tester did NOT observe a camera privacy indicator during $during.")
            }
        }
    }

    private fun record(key: CameraTestKey, outcome: TestOutcome, detail: String) {
        settingsRepo.setCameraResult(key, outcome, detail)
        events.record(type = EventType.CAMERA_TEST, note = "Test ${key.code} ${outcome.label}: $detail")
    }
}
