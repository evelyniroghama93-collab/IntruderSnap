package com.intrudersnap.prototype.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.intrudersnap.prototype.auth.OwnerProof
import com.intrudersnap.prototype.data.db.SecurityStore
import com.intrudersnap.prototype.data.security.CryptoBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Encrypted security history.
 *
 * Storage: SQLite in device-protected storage ([SecurityStore]); each record's content is an
 * AES-256-GCM blob from [CryptoBox] (key in Android Keystore), bound to its timestamp.
 *
 * Fallback: if the Keystore cannot be used at the moment of writing (not expected, but
 * possible on some devices before the first unlock), the record is stored unencrypted and
 * flagged, then re-encrypted automatically the next time the repository is opened with a
 * working Keystore. This is logged as a STORAGE_NOTICE so it is never silent.
 *
 * Deleting all history requires an [OwnerProof] and leaves a HISTORY_CLEARED record.
 */
class EventRepository private constructor(
    context: Context,
    private val store: SecurityStore,
) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val _events = MutableStateFlow<List<SecurityEvent>>(emptyList())

    /** Newest first. */
    val events: StateFlow<List<SecurityEvent>> = _events.asStateFlow()

    private val _storageError = MutableStateFlow<String?>(null)

    /**
     * Last storage failure (write or read), or null when the last operation succeeded.
     * Shown on the Health screen so a failing store is never silent.
     */
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    init {
        synchronized(lock) {
            migrateLegacyJsonl()
            reencryptFallbackRows()
            _events.value = readAll()
        }
    }

    fun reload() {
        synchronized(lock) {
            reencryptFallbackRows()
            _events.value = readAll()
        }
    }

    /**
     * Records an event, filling in environment fields (model, boot count, process diagnostics).
     * Blocking I/O: call off the main thread.
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
        source: String = "app",
        classification: String = "",
        incidentCount: Int = -1,
    ): SecurityEvent {
        val draft = SecurityEvent(
            id = 0L,
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
            uiInProcess = RuntimeEnvironment.uiCreatedInProcess,
            processAgeMs = RuntimeEnvironment.processAgeMs(),
            backgroundState = RuntimeEnvironment.backgroundState(appContext),
            source = source,
            classification = classification,
            incidentCount = incidentCount,
        )
        synchronized(lock) {
            val saved = insert(draft)
            if (saved.id > 0) _events.value = listOf(saved) + _events.value
            Log.i(TAG, "Recorded event #${saved.id} ${type.name} source=$source")
            return saved
        }
    }

    /** Deletes records older than [cutoffMillis] (automatic retention) and records the purge. */
    fun purgeOlderThan(cutoffMillis: Long, retentionDays: Int): Int {
        val removed = synchronized(lock) {
            val n = try {
                store.deleteOlderThan(cutoffMillis)
            } catch (e: Exception) {
                Log.e(TAG, "Retention purge failed", e)
                0
            }
            _events.value = readAll()
            n
        }
        if (removed > 0) {
            record(
                type = EventType.RETENTION_PURGE,
                source = "RetentionPolicy",
                note = "$removed record(s) older than $retentionDays days were deleted automatically.",
            )
        }
        return removed
    }

    /** Owner-only. Deletes every record, then records that the history was cleared. */
    fun clearAll(@Suppress("UNUSED_PARAMETER") proof: OwnerProof): Int {
        val removed = synchronized(lock) {
            val n = store.deleteAll()
            _events.value = emptyList()
            n
        }
        record(
            type = EventType.HISTORY_CLEARED,
            source = "Owner",
            note = "The owner cleared $removed record(s) after authenticating.",
        )
        return removed
    }

    // ------------------------------------------------------------------ internals

    private fun insert(event: SecurityEvent): SecurityEvent {
        val obj = event.toJson()
        obj.remove("id")
        val json = obj.toString().toByteArray(Charsets.UTF_8)
        var encrypted = true
        val payload = try {
            CryptoBox.encrypt(json, CryptoBox.aadFor(event.timestampMillis))
        } catch (e: Exception) {
            Log.w(TAG, "Keystore unavailable; storing flagged fallback record (${e.javaClass.simpleName})")
            encrypted = false
            json
        }
        val id = try {
            store.insert(event.timestampMillis, encrypted, payload).also { _storageError.value = null }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist event type=${event.type.name}", e)
            _storageError.value = "Could not save a ${event.type.label} record (${e.javaClass.simpleName})."
            -1L
        }
        return event.copy(id = id)
    }

    private fun readAll(): List<SecurityEvent> = try {
        store.queryAll().map { row ->
            try {
                val bytes = if (row.encrypted) {
                    CryptoBox.decrypt(row.payload, CryptoBox.aadFor(row.timestampMillis))
                } else {
                    row.payload
                }
                val obj = JSONObject(String(bytes, Charsets.UTF_8))
                obj.put("id", row.id) // the database row id is authoritative
                val event = SecurityEvent.fromJson(obj)
                if (row.encrypted) event else event.copy(note = event.note + " [stored unencrypted - pending re-encryption]")
            } catch (e: Exception) {
                unreadable(row.id, row.timestampMillis, e)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read history", e)
        _storageError.value = "Could not read the security history (${e.javaClass.simpleName})."
        emptyList()
    }

    private fun unreadable(id: Long, ts: Long, e: Exception) = notice(
        text = "Record could not be decrypted (${e.javaClass.simpleName}). It may have been modified, " +
            "or the Keystore key was reset.",
        timestampMillis = ts,
    ).copy(id = id)

    private fun reencryptFallbackRows() {
        val pending = try {
            store.queryAll().filter { !it.encrypted }
        } catch (e: Exception) {
            return
        }
        if (pending.isEmpty() || !CryptoBox.isUsable()) return
        pending.forEach { row ->
            try {
                store.update(row.id, true, CryptoBox.encrypt(row.payload, CryptoBox.aadFor(row.timestampMillis)))
            } catch (e: Exception) {
                Log.w(TAG, "Re-encryption failed for row ${row.id}")
            }
        }
        insert(notice("${pending.size} record(s) written while the Keystore was unavailable were re-encrypted."))
    }

    /** Moves Milestone 1's plaintext JSON-lines history into the encrypted store, then wipes it. */
    private fun migrateLegacyJsonl() {
        val legacy = File(appContext.createDeviceProtectedStorageContext().filesDir, LEGACY_FILE)
        if (!legacy.exists()) return
        var migrated = 0
        try {
            legacy.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.forEach { line ->
                try {
                    val old = SecurityEvent.fromJson(JSONObject(line))
                    insert(old.copy(source = old.source.ifBlank { "Milestone 1 history (legacy #${old.id})" }))
                    migrated++
                } catch (e: Exception) {
                    // skip a corrupt line
                }
            }
            secureDelete(legacy)
            insert(
                notice(
                    "Migrated $migrated Milestone 1 record(s) into encrypted storage; " +
                        "the plaintext file was overwritten and deleted.",
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Legacy migration failed", e)
        }
    }

    private fun notice(text: String, timestampMillis: Long = System.currentTimeMillis()) = SecurityEvent(
        id = 0L,
        type = EventType.STORAGE_NOTICE,
        timestampMillis = timestampMillis,
        systemFailedCount = -1,
        systemCountError = null,
        callbackReceived = false,
        callbackName = "",
        deviceModel = Build.MODEL ?: "",
        prototypeFailCount = -1,
        previousFailedCount = -1,
        threshold = -1,
        userUnlocked = RuntimeEnvironment.isUserUnlocked(appContext),
        bootCount = RuntimeEnvironment.bootCount(appContext),
        sessionId = RuntimeEnvironment.processSessionId,
        note = text,
        source = "EventRepository",
    )

    private fun secureDelete(file: File) {
        try {
            RandomAccessFile(file, "rws").use { raf ->
                val zeros = ByteArray(4096)
                var remaining = raf.length()
                raf.seek(0)
                while (remaining > 0) {
                    val n = minOf(remaining, zeros.size.toLong()).toInt()
                    raf.write(zeros, 0, n)
                    remaining -= n
                }
            }
        } catch (e: Exception) {
            // Best effort: flash storage may retain old blocks regardless.
        }
        file.delete()
    }

    companion object {
        private const val TAG = "IntruderSnap/Events"
        private const val LEGACY_FILE = "security_events.jsonl"

        @Volatile
        private var instance: EventRepository? = null

        fun get(context: Context): EventRepository =
            instance ?: synchronized(this) {
                instance ?: EventRepository(context, SecurityStore(context.applicationContext)).also { instance = it }
            }

        /** Test hook: a repository over a separate database file. */
        internal fun createForTest(context: Context, dbName: String): EventRepository =
            EventRepository(context, SecurityStore(context.applicationContext, dbName))
    }
}
