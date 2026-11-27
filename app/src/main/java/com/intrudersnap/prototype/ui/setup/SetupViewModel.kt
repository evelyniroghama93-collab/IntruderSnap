package com.intrudersnap.prototype.ui.setup

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.ManualCheck
import com.intrudersnap.prototype.data.RuntimeEnvironment
import com.intrudersnap.prototype.data.SecurityEvent
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.device.DeviceInfo
import com.intrudersnap.prototype.device.DeviceInfoProvider
import com.intrudersnap.prototype.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SetupSnapshot(
    val device: DeviceInfo? = null,
    val deviceSecure: Boolean = false,
    val adminActive: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val power: RuntimeEnvironment.PowerState? = null,
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository.get(app)
    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val events: StateFlow<List<SecurityEvent>> = EventRepository.get(app).events

    private val _snapshot = MutableStateFlow(SetupSnapshot())
    val snapshot: StateFlow<SetupSnapshot> = _snapshot.asStateFlow()

    /** Wall-clock start of the self-test (0 = not started). Kept in memory only. */
    private val _selfTestStartedAt = MutableStateFlow(0L)
    val selfTestStartedAt: StateFlow<Long> = _selfTestStartedAt.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            EventRepository.get(ctx).reload()
            settingsRepo.reload()
            _snapshot.value = SetupSnapshot(
                device = DeviceInfoProvider.get(),
                deviceSecure = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure,
                adminActive = AdminController.isAdminActive(ctx),
                notificationsAllowed = Notifier.canPostNotifications(ctx),
                power = RuntimeEnvironment.powerState(ctx),
            )
        }
    }

    fun startSelfTest() {
        _selfTestStartedAt.value = System.currentTimeMillis()
    }

    fun setManualCheck(check: ManualCheck, confirmed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.setManualCheck(check, confirmed) }
    }

    // ---- Settings shortcuts. Xiaomi screens are not public APIs; each has a fallback.

    fun securitySettingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun batteryOptimizationIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun xiaomiAutostartIntent(): Intent =
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))

    fun xiaomiBatterySaverIntent(context: Context): Intent =
        Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "IntruderSnap")
}
