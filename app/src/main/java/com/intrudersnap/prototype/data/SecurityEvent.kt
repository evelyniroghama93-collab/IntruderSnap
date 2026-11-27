package com.intrudersnap.prototype.data

import org.json.JSONObject

enum class EventType(val label: String) {
    PASSWORD_FAILED("Password failed"),
    PASSWORD_SUCCEEDED("Password succeeded"),
    INTRUSION_THRESHOLD_REACHED("Intrusion threshold reached"),
    ADMIN_ENABLED("Device admin enabled"),
    ADMIN_DISABLE_REQUESTED("Device admin disable requested"),
    ADMIN_DISABLED("Device admin disabled"),
    BOOT_COMPLETED("Boot broadcast received"),
    CAMERA_TEST("Camera feasibility test"),
    RECONCILIATION("Reconciliation with Android's counter"),
    ADMIN_LOST("Device admin found inactive"),
    HISTORY_CLEARED("History cleared by owner"),
    RETENTION_PURGE("Old records deleted (retention)"),
    SETTINGS_CHANGED("Setting changed by owner"),
    STORAGE_NOTICE("Storage notice"),
    UNKNOWN("Unknown");

    val isLockScreenCallback: Boolean
        get() = this == PASSWORD_FAILED || this == PASSWORD_SUCCEEDED

    companion object {
        fun fromName(name: String?): EventType = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * One security history record. Contains NO credentials: Android never gives the
 * entered PIN / pattern / password to device admin apps, and nothing here asks for it.
 *
 * @property systemFailedCount value of getCurrentFailedPasswordAttempts(), or -1 if unavailable.
 * @property systemCountError why the system counter could not be read (null if it was read).
 * @property callbackReceived true when the event was created by a real system callback.
 * @property prototypeFailCount the app's own consecutive-failure counter at the time of the event.
 * @property previousFailedCount for PASSWORD_SUCCEEDED: failures recorded before this success.
 * @property userUnlocked false if recorded during Direct Boot (before first unlock).
 * @property bootCount Settings.Global.BOOT_COUNT at the time of the event.
 * @property sessionId ID of the app process that wrote the event.
 * @property uiInProcess true if IntruderSnap's screen had been created in the process that
 *   wrote the event. False means the system started/kept the process only for the broadcast.
 * @property processAgeMs how long the writing process had been alive (small = cold start).
 * @property backgroundState power-management snapshot (standby bucket, restrictions).
 *   Blank for records written by builds before this field existed.
 * @property source what produced the record (e.g. "DeviceAdminReceiver.onPasswordFailed",
 *   "Reconciler(app-open)", "BootReceiver", "Owner").
 * @property classification incident classification for failures (IncidentTracker.Classification name).
 * @property incidentCount failures in the current incident after reconciliation (-1 = n/a).
 */
data class SecurityEvent(
    val id: Long,
    val type: EventType,
    val timestampMillis: Long,
    val systemFailedCount: Int,
    val systemCountError: String?,
    val callbackReceived: Boolean,
    val callbackName: String,
    val deviceModel: String,
    val prototypeFailCount: Int,
    val previousFailedCount: Int,
    val threshold: Int,
    val userUnlocked: Boolean,
    val bootCount: Int,
    val sessionId: String,
    val note: String,
    val uiInProcess: Boolean = true,
    val processAgeMs: Long = -1L,
    val backgroundState: String = "",
    val source: String = "",
    val classification: String = "",
    val incidentCount: Int = -1,
) {
    /** True when the background diagnostics were recorded (newer builds only). */
    val hasDiagnostics: Boolean get() = backgroundState.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("timestampMillis", timestampMillis)
        put("systemFailedCount", systemFailedCount)
        if (systemCountError != null) put("systemCountError", systemCountError)
        put("callbackReceived", callbackReceived)
        put("callbackName", callbackName)
        put("deviceModel", deviceModel)
        put("prototypeFailCount", prototypeFailCount)
        put("previousFailedCount", previousFailedCount)
        put("threshold", threshold)
        put("userUnlocked", userUnlocked)
        put("bootCount", bootCount)
        put("sessionId", sessionId)
        put("note", note)
        put("uiInProcess", uiInProcess)
        put("processAgeMs", processAgeMs)
        put("backgroundState", backgroundState)
        put("source", source)
        put("classification", classification)
        put("incidentCount", incidentCount)
    }

    companion object {
        fun fromJson(o: JSONObject): SecurityEvent = SecurityEvent(
            id = o.getLong("id"),
            type = EventType.fromName(o.optString("type")),
            timestampMillis = o.optLong("timestampMillis", 0L),
            systemFailedCount = o.optInt("systemFailedCount", -1),
            systemCountError = if (o.has("systemCountError") && !o.isNull("systemCountError")) {
                o.getString("systemCountError")
            } else {
                null
            },
            callbackReceived = o.optBoolean("callbackReceived", false),
            callbackName = o.optString("callbackName", ""),
            deviceModel = o.optString("deviceModel", ""),
            prototypeFailCount = o.optInt("prototypeFailCount", -1),
            previousFailedCount = o.optInt("previousFailedCount", -1),
            threshold = o.optInt("threshold", -1),
            userUnlocked = o.optBoolean("userUnlocked", true),
            bootCount = o.optInt("bootCount", -1),
            sessionId = o.optString("sessionId", ""),
            note = o.optString("note", ""),
            uiInProcess = o.optBoolean("uiInProcess", true),
            processAgeMs = o.optLong("processAgeMs", -1L),
            backgroundState = o.optString("backgroundState", ""),
            source = o.optString("source", ""),
            classification = o.optString("classification", ""),
            incidentCount = o.optInt("incidentCount", -1),
        )
    }
}
