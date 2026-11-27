package com.intrudersnap.prototype.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intrudersnap.prototype.auth.OwnerProof
import com.intrudersnap.prototype.data.db.SecurityStore
import com.intrudersnap.prototype.data.security.CryptoBox
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventRepositoryTest {

    private lateinit var context: Context
    private val dbName = "intrudersnap_test_${System.nanoTime()}.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        context.createDeviceProtectedStorageContext().deleteDatabase(dbName)
    }

    private fun repo() = EventRepository.createForTest(context, dbName)
    private fun rawStore() = SecurityStore(context, dbName)

    @Test
    fun recordsAreEncryptedAtRest() {
        val repo = repo()
        repo.record(type = EventType.PASSWORD_FAILED, systemFailedCount = 2, note = "secret-note-marker", source = "test")
        val rows = rawStore().queryAll()
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertTrue("row ${row.id} must be encrypted", row.encrypted)
            val raw = String(row.payload, Charsets.ISO_8859_1)
            assertFalse(raw.contains("secret-note-marker"))
            assertFalse(raw.contains("PASSWORD_FAILED"))
        }
    }

    @Test
    fun secureDeleteIsEnabledAndWalIsOff() {
        val db = rawStore().readableDatabase
        db.rawQuery("PRAGMA secure_delete", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.rawQuery("PRAGMA journal_mode", null).use { c ->
            assertTrue(c.moveToFirst())
            assertFalse(c.getString(0).equals("wal", ignoreCase = true))
        }
    }

    @Test
    fun recordsSurviveANewRepositoryInstance() {
        repo().record(type = EventType.PASSWORD_FAILED, systemFailedCount = 1, source = "test", classification = "NORMAL", incidentCount = 1)
        val reloaded = repo().events.value
        val e = reloaded.first { it.type == EventType.PASSWORD_FAILED }
        assertEquals(1, e.systemFailedCount)
        assertEquals("test", e.source)
        assertEquals("NORMAL", e.classification)
        assertEquals(1, e.incidentCount)
    }

    @Test
    fun tamperedRecordIsReportedNotSilentlyTrusted() {
        repo().record(type = EventType.PASSWORD_FAILED, systemFailedCount = 1, source = "test")
        val store = rawStore()
        val row = store.queryAll().first()
        val modified = row.payload.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        store.update(row.id, true, modified)
        val events = repo().events.value
        val e = events.first { it.id == row.id }
        assertEquals(EventType.STORAGE_NOTICE, e.type)
        assertTrue(e.note.contains("could not be decrypted"))
    }

    @Test
    fun retentionDeletesOnlyOldRecordsAndLogsThePurge() {
        val now = System.currentTimeMillis()
        val old = now - 100L * 24 * 60 * 60 * 1000
        val payload = JSONObject().put("type", "PASSWORD_FAILED").put("timestampMillis", old).toString().toByteArray()
        rawStore().insert(old, true, CryptoBox.encrypt(payload, CryptoBox.aadFor(old)))
        val repo = repo()
        repo.record(type = EventType.PASSWORD_FAILED, systemFailedCount = 1, source = "test")

        val removed = repo.purgeOlderThan(now - 90L * 24 * 60 * 60 * 1000, 90)

        assertEquals(1, removed)
        val events = repo.events.value
        assertTrue(events.none { it.timestampMillis == old })
        assertTrue(events.any { it.type == EventType.PASSWORD_FAILED })
        assertTrue(events.any { it.type == EventType.RETENTION_PURGE })
    }

    @Test
    fun clearAllRequiresProofAndLeavesAuditRecord() {
        val repo = repo()
        repo.record(type = EventType.PASSWORD_FAILED, source = "test")
        repo.record(type = EventType.INTRUSION_THRESHOLD_REACHED, source = "test")

        val removed = repo.clearAll(OwnerProof(1L))

        assertEquals(2, removed)
        val events = repo().events.value
        assertEquals(1, events.size)
        assertEquals(EventType.HISTORY_CLEARED, events.first().type)
    }
}
