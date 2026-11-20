package com.intrudersnap.prototype.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.intrudersnap.prototype.domain.CameraTestKey
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

data class AppSettings(
    val thresholdMode: ThresholdMode = ThresholdMode.THREE,
    val threshold: Int = ThresholdPolicy.DEFAULT_THRESHOLD,
    /** Consecutive failures counted by the prototype since the last success. */
    val prototypeFailCount: Int = 0,
    /** Last system count read inside onPasswordFailed (-1 if never read). */
    val lastSystemFailedCount: Int = -1,
    /** True once an intrusion was recorded for the current incident. */
    val incidentTriggered: Boolean = false,
    val lastFailedAt: Long = 0L,
    val lastSucceededAt: Long = 0L,
    /** Intrusion event awaiting acknowledgement in the in-app alert banner (0 = none). */
    val pendingAlertEventId: Long = 0L,
    /** One-shot: attempt background camera start on the NEXT failed callback (test E). */
    val backgroundExperimentArmed: Boolean = false,
    val cameraResults: Map<CameraTestKey, TestResult> = emptyMap(),
    /** Acceptance items the tester explicitly marked as FAIL (by enum name). */
    val manualFailures: Set<String> = emptySet(),
) {
    fun cameraResult(key: CameraTestKey): TestResult = cameraResults[key] ?: TestResult()
}

/**
 * Small key-value settings store in DEVICE-PROTECTED storage (see EventRepository for why).
 * All writes use commit() so they are on disk before a receiver's goAsync() finishes.
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

    fun setThreshold(mode: ThresholdMode, customValue: Int?) {
        val value = mode.preset ?: customValue ?: ThresholdPolicy.DEFAULT_THRESHOLD
        write {
            putString(K_THRESHOLD_MODE, mode.name)
            putInt(K_THRESHOLD, value)
        }
    }

    /** Atomically increments the prototype counter and stores the failure state. Returns the new count. */
    fun registerFailure(systemCount: Int, now: Long): Int = synchronized(lock) {
        val next = prefs.getInt(K_PROTO_COUNT, 0) + 1
        prefs.edit(commit = true) {
            putInt(K_PROTO_COUNT, next)
            putInt(K_LAST_SYSTEM_COUNT, systemCount)
            putLong(K_LAST_FAILED_AT, now)
        }
        _settings.value = load()
        next
    }

    fun markIncidentTriggered(eventId: Long) = write {
        putBoolean(K_INCIDENT_TRIGGERED, true)
        putLong(K_PENDING_ALERT, eventId)
    }

    /** Reset after onPasswordSucceeded, per the documented incident logic. */
    fun resetAfterSuccess(now: Long) = write {
        putInt(K_PROTO_COUNT, 0)
        putBoolean(K_INCIDENT_TRIGGERED, false)
        putLong(K_LAST_SUCCEEDED_AT, now)
    }

    fun acknowledgeAlert() = write { putLong(K_PENDING_ALERT, 0L) }

    fun setBackgroundExperimentArmed(armed: Boolean) = write {
        putBoolean(K_BG_EXPERIMENT_ARMED, armed)
    }

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

    /** Clears counters and test results; keeps the threshold. Used together with clearing history. */
    fun resetTestState() = write {
        putInt(K_PROTO_COUNT, 0)
        putInt(K_LAST_SYSTEM_COUNT, -1)
        putBoolean(K_INCIDENT_TRIGGERED, false)
        putLong(K_LAST_FAILED_AT, 0L)
        putLong(K_LAST_SUCCEEDED_AT, 0L)
        putLong(K_PENDING_ALERT, 0L)
        putBoolean(K_BG_EXPERIMENT_ARMED, false)
        remove(K_MANUAL_FAILURES)
        CameraTestKey.entries.forEach { key ->
            remove(K_CAMERA_PREFIX + key.name + "_outcome")
            remove(K_CAMERA_PREFIX + key.name + "_detail")
            remove(K_CAMERA_PREFIX + key.name + "_time")
        }
    }

    private inline fun write(crossinline block: SharedPreferences.Editor.() -> Unit) {
        synchronized(lock) {
            prefs.edit(commit = true) { block() }
            _settings.value = load()
        }
    }

    private fun load(): AppSettings {
        val camera = CameraTestKey.entries.associateWith { key ->
            TestResult(
                outcome = TestOutcome.fromName(prefs.getString(K_CAMERA_PREFIX + key.name + "_outcome", null)),
                detail = prefs.getString(K_CAMERA_PREFIX + key.name + "_detail", "") ?: "",
                timestampMillis = prefs.getLong(K_CAMERA_PREFIX + key.name + "_time", 0L),
            )
        }
        return AppSettings(
            thresholdMode = ThresholdMode.fromName(prefs.getString(K_THRESHOLD_MODE, null)),
            threshold = prefs.getInt(K_THRESHOLD, ThresholdPolicy.DEFAULT_THRESHOLD),
            prototypeFailCount = prefs.getInt(K_PROTO_COUNT, 0),
            lastSystemFailedCount = prefs.getInt(K_LAST_SYSTEM_COUNT, -1),
            incidentTriggered = prefs.getBoolean(K_INCIDENT_TRIGGERED, false),
            lastFailedAt = prefs.getLong(K_LAST_FAILED_AT, 0L),
            lastSucceededAt = prefs.getLong(K_LAST_SUCCEEDED_AT, 0L),
            pendingAlertEventId = prefs.getLong(K_PENDING_ALERT, 0L),
            backgroundExperimentArmed = prefs.getBoolean(K_BG_EXPERIMENT_ARMED, false),
            cameraResults = camera,
            manualFailures = prefs.getStringSet(K_MANUAL_FAILURES, emptySet())?.toSet() ?: emptySet(),
        )
    }

    companion object {
        private const val PREFS_NAME = "intrudersnap_settings"
        private const val K_THRESHOLD_MODE = "threshold_mode"
        private const val K_THRESHOLD = "threshold"
        private const val K_PROTO_COUNT = "prototype_fail_count"
        private const val K_LAST_SYSTEM_COUNT = "last_system_failed_count"
        private const val K_INCIDENT_TRIGGERED = "incident_triggered"
        private const val K_LAST_FAILED_AT = "last_failed_at"
        private const val K_LAST_SUCCEEDED_AT = "last_succeeded_at"
        private const val K_PENDING_ALERT = "pending_alert_event_id"
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
