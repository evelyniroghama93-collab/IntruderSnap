package com.intrudersnap.prototype.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One stored row. [payload] is AES-GCM ciphertext unless [encrypted] is false (fallback only). */
data class StoredRow(val id: Long, val timestampMillis: Long, val encrypted: Boolean, val payload: ByteArray)

/**
 * Minimal SQLite store (framework API, no extra libraries).
 *
 *  - Lives in DEVICE-PROTECTED storage so the lock-screen receiver can write during Direct Boot.
 *  - Only the timestamp is stored in clear (needed for retention); everything else is ciphertext.
 *  - All statements use bound parameters; no string-built SQL with user data.
 *  - PRAGMA secure_delete=ON overwrites deleted content; WAL is disabled so deleted
 *    data does not linger in a write-ahead log.
 */
class SecurityStore(context: Context, name: String = DB_NAME) :
    SQLiteOpenHelper(context.createDeviceProtectedStorageContext(), name, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(false)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // PRAGMA returns a row, so it must run through rawQuery (execSQL rejects it on Android).
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, " +
                "encrypted INTEGER NOT NULL, " +
                "payload BLOB NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_ts ON $TABLE(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Future versions must migrate explicitly, never drop data.
    }

    fun insert(timestampMillis: Long, encrypted: Boolean, payload: ByteArray): Long {
        val values = ContentValues().apply {
            put("ts", timestampMillis)
            put("encrypted", if (encrypted) 1 else 0)
            put("payload", payload)
        }
        return writableDatabase.insertOrThrow(TABLE, null, values)
    }

    fun update(id: Long, encrypted: Boolean, payload: ByteArray) {
        val values = ContentValues().apply {
            put("encrypted", if (encrypted) 1 else 0)
            put("payload", payload)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    /** Newest first. */
    fun queryAll(): List<StoredRow> {
        val rows = mutableListOf<StoredRow>()
        readableDatabase.query(TABLE, arrayOf("id", "ts", "encrypted", "payload"), null, null, null, null, "id DESC").use { c ->
            while (c.moveToNext()) {
                rows += StoredRow(c.getLong(0), c.getLong(1), c.getInt(2) == 1, c.getBlob(3))
            }
        }
        return rows
    }

    fun deleteOlderThan(cutoffMillis: Long): Int =
        writableDatabase.delete(TABLE, "ts < ?", arrayOf(cutoffMillis.toString()))

    fun deleteAll(): Int = writableDatabase.delete(TABLE, "1", null)

    fun count(): Long = android.database.DatabaseUtils.queryNumEntries(readableDatabase, TABLE)

    companion object {
        const val DB_NAME = "intrudersnap_security.db"
        private const val DB_VERSION = 1
        private const val TABLE = "events"
    }
}
