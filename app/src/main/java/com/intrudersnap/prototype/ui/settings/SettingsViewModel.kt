package com.intrudersnap.prototype.ui.settings

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.data.ThresholdMode
import com.intrudersnap.prototype.device.DeviceInfo
import com.intrudersnap.prototype.device.DeviceInfoProvider
import com.intrudersnap.prototype.domain.ThresholdPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository.get(app)
    val settings: StateFlow<AppSettings> = repo.settings

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.reload()
            _device.value = DeviceInfoProvider.get()
        }
    }

    /** @return null on success, or a validation error message. */
    fun saveThreshold(mode: ThresholdMode, customText: String): String? {
        val custom = if (mode == ThresholdMode.CUSTOM) {
            val value = customText.trim().toIntOrNull()
            if (value == null || !ThresholdPolicy.isValidCustomThreshold(value)) {
                return "Enter a whole number from ${ThresholdPolicy.MIN_CUSTOM_THRESHOLD} to ${ThresholdPolicy.MAX_CUSTOM_THRESHOLD}."
            }
            value
        } else {
            null
        }
        viewModelScope.launch(Dispatchers.IO) { repo.setThreshold(mode, custom) }
        return null
    }

    // ---- Xiaomi / Android settings shortcuts. Each falls back to App info if unavailable. ----

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun notificationSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetailsIntent(context)
        }

    /** MIUI / HyperOS Autostart manager (Security app). Not a public API - may not exist. */
    fun xiaomiAutostartIntent(): Intent =
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )

    fun deviceAdminSettingsIntent(): Intent =
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings"))
}
