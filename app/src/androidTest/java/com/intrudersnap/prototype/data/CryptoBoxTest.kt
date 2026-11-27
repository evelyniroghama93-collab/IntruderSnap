package com.intrudersnap.prototype.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intrudersnap.prototype.data.security.CryptoBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class CryptoBoxTest {

    private val plain = "PASSWORD_FAILED sys=3".toByteArray()
    private val aad = CryptoBox.aadFor(1_700_000_000_000L)

    @Test
    fun roundTrip() {
        val blob = CryptoBox.encrypt(plain, aad)
        assertArrayEquals(plain, CryptoBox.decrypt(blob, aad))
    }

    @Test
    fun ciphertextDoesNotContainPlaintextAndIsRandomized() {
        val a = CryptoBox.encrypt(plain, aad)
        val b = CryptoBox.encrypt(plain, aad)
        assertFalse(String(a, Charsets.ISO_8859_1).contains("PASSWORD_FAILED"))
        assertFalse(a.contentEquals(b)) // fresh IV each time
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val blob = CryptoBox.encrypt(plain, aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        try {
            CryptoBox.decrypt(blob, aad)
            fail("Tampered blob must not decrypt")
        } catch (expected: Exception) {
            // AEADBadTagException
        }
    }

    @Test
    fun wrongAssociatedDataIsRejected() {
        val blob = CryptoBox.encrypt(plain, aad)
        try {
            CryptoBox.decrypt(blob, CryptoBox.aadFor(1_700_000_000_001L))
            fail("Blob moved to another timestamp must not decrypt")
        } catch (expected: Exception) {
            // AEADBadTagException
        }
    }

    @Test
    fun keyLivesInAndroidKeystoreAndIsNotExportable() {
        CryptoBox.encrypt(plain, aad)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(ks.containsAlias(CryptoBox.KEY_ALIAS))
        val key = ks.getKey(CryptoBox.KEY_ALIAS, null)
        // Keystore keys never expose their raw material.
        assertTrue(key.encoded == null)
        assertTrue(CryptoBox.isUsable())
    }
}
