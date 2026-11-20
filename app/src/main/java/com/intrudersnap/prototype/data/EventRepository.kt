package com.intrudersnap.prototype.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only security history stored as JSON lines in DEVICE-PROTECTED storage.
 *
 * Why device-protected (DE) storage:
 *  After a reboot, Android keeps credential-encrypted (CE) storage locked until the user
 *  enters the correct credential once. A failed attempt on the very first lock screen after
 *  boot happens while CE is locked, so writing it to CE would fail. DE storage is available
 *  during Direct Boot. The records contain no credentials, so DE is acceptable for them.
 *
 * Process death: every record is fsync'd before record() returns, and the list is re-read
 * from disk whenever a new process creates the repository or the UI calls reload().
 */
class EventRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val storageContext: Context = appContext.createDeviceProtectedStorageContext()
    private val file = File(storageContext.filesDir, FILE_NAME)
    private val lock = Any()

    private val _events = MutableStateFlow<List<SecurityEvent>>(emptyList())

    /** Newest first. */
    val events: StateFlow<List<SecurityEvent>> = _events.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        synchronized(lock) {
            _events.value = readAll()
        }
    }

    /**
     * Records an event, filling in environment fields (model, boot count, process ID, ...).
     * Safe to call from any thread; blocking file I/O, so call off the main thread.
     */
    fun record(
        type: EventType,
        systemFailedCount: Int = -1,
        systemCountError: String? = null,
        callbackReceived: Boolean = false,
        callbackName: String = "",
        prototypeFailCount: Int = -1,
        previousFailedCount: Int = -1,
        threshold: Int = -1,
        note: String = "",
    ): SecurityEvent {
        synchronized(lock) {
            val current = readAll()
            val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
            val event = SecurityEvent(
                id = nextId,
                type = type,
                timestampMillis = System.currentTimeMillis(),
                systemFailedCount = systemFailedCount,
                systemCountError = systemCountError,
                callbackReceived = callbackReceived,
                callbackName = callbackName,
                deviceModel = Build.MODEL ?: "unknown",
                prototypeFailCount = prototypeFailCount,
                previousFailedCount = previousFailedCount,
                threshold = threshold,
                userUnlocked = RuntimeEnvironment.isUserUnlocked(appContext),
                bootCount = RuntimeEnvironment.bootCount(appContext),
                sessionId = RuntimeEnvironment.processSessionId,
                note = note,
            )
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { out ->
                    out.write((event.toJson().toString() + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist event type=${type.name}", e)
            }
            _events.value = listOf(event) + current
            Log.i(TAG, "Recorded event #${event.id} ${type.name} systemCount=$systemFailedCount")
            return event
        }
    }

    fun clear() {
        synchronized(lock) {
            if (file.exists()) file.delete()
            _events.value = emptyList()
        }
    }

    private fun readAll(): List<SecurityEvent> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8)
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        SecurityEvent.fromJson(JSONObject(line))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupt history line")
                        null
                    }
                }
                .sortedByDescending { it.id }
                .toList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read history", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "IntruderSnap/Events"
        private const val FILE_NAME = "security_events.jsonl"

        @Volatile
        private var instance: EventRepository? = null

        fun get(context: Context): EventRepository =
            instance ?: synchronized(this) {
                instance ?: EventRepository(context).also { instance = it }
            }
    }
}
