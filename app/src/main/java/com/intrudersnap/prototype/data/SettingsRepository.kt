package com.intrudersnap.prototype.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.intrudersnap.prototype.domain.CameraTestKey
import com.intrudersnap.prototype.domain.IncidentTracker
import com.intrudersnap.prototype.domain.RetentionPolicy
import com.intrudersnap.prototype.domain.TestOutcome
import com.intrudersnap.prototype.domain.TestResult
import com.intrudersnap.prototype.domain.ThresholdPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThresholdMode(val label: String, val preset: Int?) {
    THREE("3 attempts", 3),
    FIVE("5 attempts", 5),
    SEVEN("7 attempts", 7),
    CUSTOM("Custom", null);

    companion object {
        fun fromName(name: String?): ThresholdMode = entries.firstOrNull { it.name == name } ?: THREE
    }
}

/** Setup steps the owner confirms manually because Android offers no API to read them. */
enum class ManualCheck(val label: String) {
    AUTOSTART("Autostart allowed for IntruderSnap"),
    BATTERY_SAVER("Battery saver set to \"No restrictions\""),
    LOCKED_IN_RECENTS("IntruderSnap locked in Recents"),
    LOCK_SCREEN_NOTIFICATIONS("Lock-screen notifications allowed"),
}

data class AppSettings(
    val thresholdMode: ThresholdMode = ThresholdMode.THREE,
    val threshold: Int = ThresholdPolicy.DEFAULT_THRESHOLD,
    val retentionDays: Int = RetentionPolicy.DEFAULT_DAYS,
    val incident: IncidentTracker.State = IncidentTracker.State(),
    val lastFailedAt: Long = 0L,
    val lastSucceededAt: Long = 0L,
    /** Time of the latest lock-screen callback of either kind. */
    val lastCallbackAt: Long = 0L,
    /** Latest callback delivered while IntruderSnap's screen was not created in the process. */
    val lastColdDeliveryAt: Long = 0L,
    val lastReconciliationAt: Long = 0L,
    val lastReconciliationResult: String = "",
    /** Last time reconciliation found failures Android counted but did not deliver. */
    val lastMissedDetectedAt: Long = 0L,
    /** Remembered admin state, used to detect that admin access was removed. */
    val adminWasActive: Boolean = false,
    val lastAdminCheckAt: Long = 0L,
    val pendingAlertEventId: Long = 0L,
    val manualChecks: Set<ManualCheck> = emptySet(),
    val experimentalToolsEnabled: Boolean = false,
    /** One-shot: attempt background camera start on the NEXT failed callback (Labs test E). */
    val backgroundExperimentArmed: Boolean = false,
    val cameraResults: Map<CameraTestKey, TestResult> = emptyMap(),
    /** Milestone 1 acceptance items the tester explicitly marked as FAIL (by enum name). */
    val manualFailures: Set<String> = emptySet(),
) {
    fun cameraResult(key: CameraTestKey): TestResult = cameraResults[key] ?: TestResult()

    /** Kept for the Milestone 1 Labs screens. */
    val prototypeFailCount: Int get() = if (incident.incidentOpen) incident.appCount else 0
}

/**
 * Key-value settings in DEVICE-PROTECTED storage (the receiver runs before first unlock).
 * Contains no security history and no credentials. All writes use commit().
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun reload() {
        synchronized(lock) { _settings.value = load() }
    }

    /** Atomically reads, transforms and stores the incident state. Returns the transform's result. */
    fun <T> updateIncident(transform: (AppSettings) -> Pair<IncidentTracker.State, T>): T = synchronized(lock) {
        val before = load()
        val (state, result) = transform(before)
        prefs.edit(commit = true) { putIncident(state) }
        _settings.value = load()
        result
    }

    fun markCallback(now: Long, failed: Boolean, cold: Boolean) = write {
        putLong(K_LAST_CALLBACK_AT, now)
        if (failed) putLong(K_LAST_FAILED_AT, now) else putLong(K_LAST_SUCCEEDED_AT, now)
        if (cold) putLong(K_LAST_COLD_DELIVERY_AT, now)
    }

    fun setThreshold(mode: ThresholdMode, customValue: Int?) {
        val value = mode.preset ?: customValue ?: ThresholdPolicy.DEFAULT_THRESHOLD
        write {
            putString(K_THRESHOLD_MODE, mode.name)
            putInt(K_THRESHOLD, value)
        }
    }

    fun setRetentionDays(days: Int) = write { putInt(K_RETENTION_DAYS, RetentionPolicy.sanitize(days)) }

    fun setPendingAlert(eventId: Long) = write { putLong(K_PENDING_ALERT, eventId) }

    fun acknowledgeAlert() = write { putLong(K_PENDING_ALERT, 0L) }

    fun setReconciliation(now: Long, result: String, missedDetected: Boolean) = write {
        putLong(K_LAST_RECON_AT, now)
        putString(K_LAST_RECON_RESULT, result)
        if (missedDetected) putLong(K_LAST_MISSED_AT, now)
    }

    fun setAdminState(active: Boolean, now: Long) = write {
        putBoolean(K_ADMIN_WAS_ACTIVE, active)
        putLong(K_LAST_ADMIN_CHECK_AT, now)
    }

    fun setManualCheck(check: ManualCheck, confirmed: Boolean) {
        synchronized(lock) {
            val set = prefs.getStringSet(K_MANUAL_CHECKS, emptySet())!!.toMutableSet()
            if (confirmed) set.add(check.name) else set.remove(check.name)
            prefs.edit(commit = true) { putStringSet(K_MANUAL_CHECKS, set) }
            _settings.value = load()
        }
    }

    fun setExperimentalToolsEnabled(enabled: Boolean) = write {
        putBoolean(K_EXPERIMENTAL, enabled)
        if (!enabled) putBoolean(K_BG_EXPERIMENT_ARMED, false)
    }

    fun setBackgroundExperimentArmed(armed: Boolean) = write { putBoolean(K_BG_EXPERIMENT_ARMED, armed) }

    fun setCameraResult(key: CameraTestKey, outcome: TestOutcome, detail: String) = write {
        putString(K_CAMERA_PREFIX + key.name + "_outcome", outcome.name)
        putString(K_CAMERA_PREFIX + key.name + "_detail", detail)
        putLong(K_CAMERA_PREFIX + key.name + "_time", System.currentTimeMillis())
    }

    fun setManualFailure(itemName: String, failed: Boolean) {
        synchronized(lock) {
            val set = prefs.getStringSet(K_MANUAL_FAILURES, emptySet())!!.toMutableSet()
            if (failed) set.add(itemName) else set.remove(itemName)
            prefs.edit(commit = true) { putStringSet(K_MANUAL_FAILURES, set) }
            _settings.value = load()
        }
    }

    // ------------------------------------------------------------------ internals

    private inline fun write(crossinline block: SharedPreferences.Editor.() -> Unit) {
        synchronized(lock) {
            prefs.edit(commit = true) { block() }
            _settings.value = load()
        }
    }

    private fun SharedPreferences.Editor.putIncident(s: IncidentTracker.State) {
        putBoolean(K_INC_OPEN, s.incidentOpen)
        putInt(K_INC_BASELINE, s.baseline)
        putInt(K_INC_APP_COUNT, s.appCount)
        putInt(K_INC_LAST_SYS, s.lastSystemCount)
        putLong(K_INC_LAST_FAILED, s.lastFailedAt)
        putBoolean(K_INC_TRIGGERED, s.triggered)
    }

    private fun load(): AppSettings {
        val camera = CameraTestKey.entries.associateWith { key ->
            TestResult(
                outcome = TestOutcome.fromName(prefs.getString(K_CAMERA_PREFIX + key.name + "_outcome", null)),
                detail = prefs.getString(K_CAMERA_PREFIX + key.name + "_detail", "") ?: "",
                timestampMillis = prefs.getLong(K_CAMERA_PREFIX + key.name + "_time", 0L),
            )
        }
        val manual = prefs.getStringSet(K_MANUAL_CHECKS, emptySet()).orEmpty()
            .mapNotNull { n -> ManualCheck.entries.firstOrNull { it.name == n } }
            .toSet()
        return AppSettings(
            thresholdMode = ThresholdMode.fromName(prefs.getString(K_THRESHOLD_MODE, null)),
            threshold = prefs.getInt(K_THRESHOLD, ThresholdPolicy.DEFAULT_THRESHOLD),
            retentionDays = RetentionPolicy.sanitize(prefs.getInt(K_RETENTION_DAYS, RetentionPolicy.DEFAULT_DAYS)),
            incident = IncidentTracker.State(
                incidentOpen = prefs.getBoolean(K_INC_OPEN, false),
                baseline = prefs.getInt(K_INC_BASELINE, 0),
                appCount = prefs.getInt(K_INC_APP_COUNT, 0),
                lastSystemCount = prefs.getInt(K_INC_LAST_SYS, -1),
                lastFailedAt = prefs.getLong(K_INC_LAST_FAILED, 0L),
                triggered = prefs.getBoolean(K_INC_TRIGGERED, false),
            ),
            lastFailedAt = prefs.getLong(K_LAST_FAILED_AT, 0L),
            lastSucceededAt = prefs.getLong(K_LAST_SUCCEEDED_AT, 0L),
            lastCallbackAt = prefs.getLong(K_LAST_CALLBACK_AT, 0L),
            lastColdDeliveryAt = prefs.getLong(K_LAST_COLD_DELIVERY_AT, 0L),
            lastReconciliationAt = prefs.getLong(K_LAST_RECON_AT, 0L),
            lastReconciliationResult = prefs.getString(K_LAST_RECON_RESULT, "") ?: "",
            lastMissedDetectedAt = prefs.getLong(K_LAST_MISSED_AT, 0L),
            adminWasActive = prefs.getBoolean(K_ADMIN_WAS_ACTIVE, false),
            lastAdminCheckAt = prefs.getLong(K_LAST_ADMIN_CHECK_AT, 0L),
            pendingAlertEventId = prefs.getLong(K_PENDING_ALERT, 0L),
            manualChecks = manual,
            experimentalToolsEnabled = prefs.getBoolean(K_EXPERIMENTAL, false),
            backgroundExperimentArmed = prefs.getBoolean(K_BG_EXPERIMENT_ARMED, false),
            cameraResults = camera,
            manualFailures = prefs.getStringSet(K_MANUAL_FAILURES, emptySet())?.toSet() ?: emptySet(),
        )
    }

    companion object {
        private const val PREFS_NAME = "intrudersnap_settings"
        private const val K_THRESHOLD_MODE = "threshold_mode"
        private const val K_THRESHOLD = "threshold"
        private const val K_RETENTION_DAYS = "retention_days"
        private const val K_INC_OPEN = "incident_open"
        private const val K_INC_BASELINE = "incident_baseline"
        private const val K_INC_APP_COUNT = "incident_app_count"
        private const val K_INC_LAST_SYS = "incident_last_system_count"
        private const val K_INC_LAST_FAILED = "incident_last_failed_at"
        private const val K_INC_TRIGGERED = "incident_triggered"
        private const val K_LAST_FAILED_AT = "last_failed_at"
        private const val K_LAST_SUCCEEDED_AT = "last_succeeded_at"
        private const val K_LAST_CALLBACK_AT = "last_callback_at"
        private const val K_LAST_COLD_DELIVERY_AT = "last_cold_delivery_at"
        private const val K_LAST_RECON_AT = "last_reconciliation_at"
        private const val K_LAST_RECON_RESULT = "last_reconciliation_result"
        private const val K_LAST_MISSED_AT = "last_missed_detected_at"
        private const val K_ADMIN_WAS_ACTIVE = "admin_was_active"
        private const val K_LAST_ADMIN_CHECK_AT = "last_admin_check_at"
        private const val K_PENDING_ALERT = "pending_alert_event_id"
        private const val K_MANUAL_CHECKS = "manual_checks"
        private const val K_EXPERIMENTAL = "experimental_tools_enabled"
        private const val K_BG_EXPERIMENT_ARMED = "bg_camera_experiment_armed"
        private const val K_MANUAL_FAILURES = "manual_failures"
        private const val K_CAMERA_PREFIX = "camera_"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
