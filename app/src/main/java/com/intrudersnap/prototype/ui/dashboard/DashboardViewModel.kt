package com.intrudersnap.prototype.ui.dashboard

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.device.DeviceInfo
import com.intrudersnap.prototype.device.DeviceInfoProvider
import com.intrudersnap.prototype.domain.AcceptanceItem
import com.intrudersnap.prototype.domain.CameraTestKey
import com.intrudersnap.prototype.domain.StatusLabel
import com.intrudersnap.prototype.domain.TestOutcome
import com.intrudersnap.prototype.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Values that must be polled from the system (they have no change callback). */
data class LiveStatus(
    val device: DeviceInfo? = null,
    val adminActive: Boolean = false,
    val watchLoginGranted: Boolean = false,
    val systemCountNow: String = "-",
    val cameraGranted: Boolean = false,
    val notificationsAllowed: Boolean = false,
)

data class DashboardUiState(
    val live: LiveStatus = LiveStatus(),
    val adminStatus: StatusLabel = StatusLabel.PERMISSION_REQUIRED,
    val monitoringStatus: StatusLabel = StatusLabel.NOT_YET_TESTED,
    val monitoringDetail: String = "",
    val successCallbackStatus: StatusLabel = StatusLabel.NOT_YET_TESTED,
    val cameraPermissionStatus: StatusLabel = StatusLabel.PERMISSION_REQUIRED,
    val backgroundCameraStatus: StatusLabel = StatusLabel.NOT_YET_TESTED,
    val backgroundCameraDetail: String = "",
    val notificationStatus: StatusLabel = StatusLabel.PERMISSION_REQUIRED,
    val totalFailed: Int = 0,
    val prototypeCount: Int = 0,
    val threshold: Int = 3,
    val lastFailed: SecurityEvent? = null,
    val lastSucceeded: SecurityEvent? = null,
    val pendingAlert: SecurityEvent? = null,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val events = EventRepository.get(app)
    private val settings = SettingsRepository.get(app)
    private val live = MutableStateFlow(LiveStatus())

    val state: StateFlow<DashboardUiState> =
        combine(events.events, settings.settings, live) { ev, st, lv -> build(ev, st, lv) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            events.reload()
            settings.reload()
            val system = AdminController.readSystemFailedAttempts(ctx)
            live.value = LiveStatus(
                device = DeviceInfoProvider.get(),
                adminActive = AdminController.isAdminActive(ctx),
                watchLoginGranted = AdminController.isWatchLoginGranted(ctx),
                systemCountNow = system.countOrNull?.toString() ?: "Unavailable (${system.errorOrNull})",
                cameraGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                notificationsAllowed = Notifier.canPostNotifications(ctx),
            )
        }
    }

    fun deactivateAdmin() {
        AdminController.deactivate(getApplication())
        // removeActiveAdmin() completes asynchronously; poll again shortly afterwards.
        viewModelScope.launch {
            delay(800)
            refresh()
        }
    }

    fun acknowledgeAlert() {
        viewModelScope.launch(Dispatchers.IO) { settings.acknowledgeAlert() }
    }

    private fun build(ev: List<SecurityEvent>, st: AppSettings, lv: LiveStatus): DashboardUiState {
        val failed = ev.filter { it.type == EventType.PASSWORD_FAILED }
        val succeeded = ev.filter { it.type == EventType.PASSWORD_SUCCEEDED }
        val markedFailed = AcceptanceItem.NATIVE_FAILED_CALLBACK.name in st.manualFailures

        val (monitoring, detail) = when {
            !lv.adminActive -> StatusLabel.PERMISSION_REQUIRED to "Activate the device administrator to start monitoring."
            !lv.watchLoginGranted -> StatusLabel.PERMISSION_REQUIRED to "Admin active but watch-login policy not granted."
            markedFailed -> StatusLabel.TEST_FAILED to "Tester recorded that no callback arrived after a wrong attempt."
            failed.isNotEmpty() -> StatusLabel.SUPPORTED to "Verified: ${failed.size} onPasswordFailed callback(s) received on this device."
            else -> StatusLabel.NOT_YET_TESTED to "Admin active. Lock the phone and enter one wrong credential to verify."
        }

        val bg = st.cameraResult(CameraTestKey.E_BACKGROUND_AFTER_CALLBACK)
        val bgStatus = when (bg.outcome) {
            TestOutcome.PASS -> StatusLabel.SUPPORTED
            TestOutcome.BLOCKED -> StatusLabel.UNSUPPORTED
            TestOutcome.FAIL -> StatusLabel.TEST_FAILED
            else -> if (lv.cameraGranted) StatusLabel.NOT_YET_TESTED else StatusLabel.PERMISSION_REQUIRED
        }

        return DashboardUiState(
            live = lv,
            adminStatus = if (lv.adminActive) StatusLabel.SUPPORTED else StatusLabel.PERMISSION_REQUIRED,
            monitoringStatus = monitoring,
            monitoringDetail = detail,
            successCallbackStatus = when {
                AcceptanceItem.SUCCESS_CALLBACK.name in st.manualFailures -> StatusLabel.TEST_FAILED
                succeeded.isNotEmpty() -> StatusLabel.SUPPORTED
                else -> StatusLabel.NOT_YET_TESTED
            },
            cameraPermissionStatus = if (lv.cameraGranted) StatusLabel.SUPPORTED else StatusLabel.PERMISSION_REQUIRED,
            backgroundCameraStatus = bgStatus,
            backgroundCameraDetail = bg.detail,
            notificationStatus = if (lv.notificationsAllowed) StatusLabel.SUPPORTED else StatusLabel.PERMISSION_REQUIRED,
            totalFailed = failed.size,
            prototypeCount = st.prototypeFailCount,
            threshold = st.threshold,
            lastFailed = failed.firstOrNull(),
            lastSucceeded = succeeded.firstOrNull(),
            pendingAlert = if (st.pendingAlertEventId > 0) ev.firstOrNull { it.id == st.pendingAlertEventId } else null,
        )
    }
}
