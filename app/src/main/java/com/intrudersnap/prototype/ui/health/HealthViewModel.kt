package com.intrudersnap.prototype.ui.health

import android.app.Application
import android.app.KeyguardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.auth.OwnerSession
import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.RuntimeEnvironment
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.device.DeviceInfo
import com.intrudersnap.prototype.device.DeviceInfoProvider
import com.intrudersnap.prototype.domain.HealthAssessment
import com.intrudersnap.prototype.domain.HealthInputs
import com.intrudersnap.prototype.domain.MonitoringHealth
import com.intrudersnap.prototype.health.MonitoringChecks
import com.intrudersnap.prototype.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Values polled from Android (no change callbacks exist for them). */
data class SystemSnapshot(
    val loaded: Boolean = false,
    val device: DeviceInfo? = null,
    val adminActive: Boolean = false,
    val watchLoginGranted: Boolean = false,
    val deviceSecure: Boolean = false,
    val power: RuntimeEnvironment.PowerState? = null,
    val notificationsAllowed: Boolean = false,
    val systemCountNow: String = "-",
    val lastBootAt: Long = 0L,
    val bootCount: Int = -1,
)

data class HealthUiState(
    val snapshot: SystemSnapshot = SystemSnapshot(),
    val settings: AppSettings = AppSettings(),
    val assessment: HealthAssessment? = null,
    val lastCallback: SecurityEvent? = null,
    val lastColdDeliveryAt: Long = 0L,
    val callbackCount: Int = 0,
    val pendingAlert: SecurityEvent? = null,
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val events = EventRepository.get(app)
    private val settings = SettingsRepository.get(app)
    private val snapshot = MutableStateFlow(SystemSnapshot())

    val state: StateFlow<HealthUiState> =
        combine(events.events, settings.settings, snapshot) { ev, st, snap -> build(ev, st, snap) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState())

    /** Runs the app-open checks, then re-reads everything from Android. */
    fun refresh(runChecks: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (runChecks) {
                try {
                    MonitoringChecks.runAll(ctx, "app open")
                } catch (e: Exception) {
                    // Health screen still shows the last known state.
                }
            }
            events.reload()
            settings.reload()
            val system = AdminController.readSystemFailedAttempts(ctx)
            snapshot.value = SystemSnapshot(
                loaded = true,
                device = DeviceInfoProvider.get(),
                adminActive = AdminController.isAdminActive(ctx),
                watchLoginGranted = AdminController.isWatchLoginGranted(ctx),
                deviceSecure = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure,
                power = RuntimeEnvironment.powerState(ctx),
                notificationsAllowed = Notifier.canPostNotifications(ctx),
                systemCountNow = system.countOrNull?.toString() ?: "Unavailable",
                lastBootAt = RuntimeEnvironment.lastBootAt(),
                bootCount = RuntimeEnvironment.bootCount(ctx),
            )
        }
    }

    fun reconcileNow() {
        viewModelScope.launch(Dispatchers.IO) {
            MonitoringChecks.reconcile(getApplication(), "manual")
            refresh(runChecks = false)
        }
    }

    /** Owner-only: hides the in-app alert banner. */
    fun acknowledgeAlert(): Boolean {
        if (OwnerSession.proof() == null) return false
        viewModelScope.launch(Dispatchers.IO) { settings.acknowledgeAlert() }
        return true
    }

    private fun build(ev: List<SecurityEvent>, st: AppSettings, snap: SystemSnapshot): HealthUiState {
        val callbacks = ev.filter { it.type.isLockScreenCallback && it.callbackReceived }
        // Evidence that a callback reached IntruderSnap while it was not open:
        //  - newer builds record uiInProcess=false;
        //  - any callback recorded before the first unlock (Direct Boot) - the app's screen
        //    cannot run then, so the process was started for the callback.
        val coldFromEvents = callbacks
            .filter { (it.hasDiagnostics && !it.uiInProcess) || !it.userUnlocked }
            .maxOfOrNull { it.timestampMillis } ?: 0L
        val lastCold = maxOf(st.lastColdDeliveryAt, coldFromEvents)

        val assessment = if (!snap.loaded) {
            null
        } else {
            MonitoringHealth.assess(
                HealthInputs(
                    adminActive = snap.adminActive,
                    watchLoginGranted = snap.watchLoginGranted,
                    deviceSecure = snap.deviceSecure,
                    backgroundRestricted = snap.power?.backgroundRestricted,
                    standbyBucketRestricted = snap.power?.standbyBucketRestricted,
                    callbacksEverReceived = callbacks.size,
                    lastColdDeliveryAt = lastCold,
                    lastMissedDetectedAt = st.lastMissedDetectedAt,
                    now = System.currentTimeMillis(),
                ),
            )
        }
        return HealthUiState(
            snapshot = snap,
            settings = st,
            assessment = assessment,
            lastCallback = callbacks.firstOrNull(),
            lastColdDeliveryAt = lastCold,
            callbackCount = callbacks.size,
            pendingAlert = if (st.pendingAlertEventId > 0) ev.firstOrNull { it.id == st.pendingAlertEventId } else null,
        )
    }

    companion object {
        fun isFailedCallback(e: SecurityEvent) = e.type == EventType.PASSWORD_FAILED
    }
}
