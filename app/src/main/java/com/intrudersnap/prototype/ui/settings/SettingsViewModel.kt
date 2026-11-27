package com.intrudersnap.prototype.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrudersnap.prototype.admin.AdminController
import com.intrudersnap.prototype.auth.OwnerSession
import com.intrudersnap.prototype.data.AppSettings
import com.intrudersnap.prototype.data.EventRepository
import com.intrudersnap.prototype.data.EventType
import com.intrudersnap.prototype.data.SettingsRepository
import com.intrudersnap.prototype.data.ThresholdMode
import com.intrudersnap.prototype.data.security.CryptoBox
import com.intrudersnap.prototype.domain.RetentionPolicy
import com.intrudersnap.prototype.domain.ThresholdPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageInfo(val recordCount: Int = 0, val keystoreUsable: Boolean? = null, val adminActive: Boolean = false)

/** Every change requires an active owner session and is itself recorded in the history. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository.get(app)
    private val events = EventRepository.get(app)
    val settings: StateFlow<AppSettings> = repo.settings

    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.reload()
            _storage.value = StorageInfo(
                recordCount = events.events.value.size,
                keystoreUsable = CryptoBox.isUsable(),
                adminActive = AdminController.isAdminActive(getApplication()),
            )
        }
    }

    /** @return null on success, otherwise an error message. */
    fun saveThreshold(mode: ThresholdMode, customText: String): String? {
        if (OwnerSession.proof() == null) return "Session expired. Unlock again."
        val custom = if (mode == ThresholdMode.CUSTOM) {
            val value = customText.trim().toIntOrNull()
            if (value == null || !ThresholdPolicy.isValidCustomThreshold(value)) {
                return "Enter a whole number from ${ThresholdPolicy.MIN_CUSTOM_THRESHOLD} to ${ThresholdPolicy.MAX_CUSTOM_THRESHOLD}."
            }
            value
        } else {
            null
        }
        val before = repo.current.threshold
        viewModelScope.launch(Dispatchers.IO) {
            repo.setThreshold(mode, custom)
            audit("Threshold changed from $before to ${repo.current.threshold}.")
        }
        return null
    }

    fun saveRetention(days: Int): String? {
        if (OwnerSession.proof() == null) return "Session expired. Unlock again."
        val before = repo.current.retentionDays
        viewModelScope.launch(Dispatchers.IO) {
            repo.setRetentionDays(days)
            audit("Retention changed from $before to ${RetentionPolicy.sanitize(days)} days.")
        }
        return null
    }

    fun setExperimental(enabled: Boolean): String? {
        if (OwnerSession.proof() == null) return "Session expired. Unlock again."
        viewModelScope.launch(Dispatchers.IO) {
            repo.setExperimentalToolsEnabled(enabled)
            audit("Experimental Labs tools ${if (enabled) "enabled" else "disabled"}.")
        }
        return null
    }

    /** Destructive: requires an authentication within the last minute. */
    fun deactivateAdmin(): String? {
        if (OwnerSession.freshProof() == null) return "Confirm it's you again to deactivate."
        viewModelScope.launch(Dispatchers.IO) {
            audit("Owner deactivated the device administrator from IntruderSnap.")
            AdminController.deactivate(getApplication())
        }
        return null
    }

    private fun audit(text: String) {
        events.record(type = EventType.SETTINGS_CHANGED, source = "Owner", note = text)
    }
}
