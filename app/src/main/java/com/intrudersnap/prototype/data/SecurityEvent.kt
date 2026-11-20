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
) {
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
        )
    }
}
