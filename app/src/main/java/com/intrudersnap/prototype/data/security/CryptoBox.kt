package com.intrudersnap.prototype.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a non-exportable key held in the Android Keystore.
 *
 * Blob format: [version:1][iv:12][ciphertext+tag]
 * AAD binds each blob to its database row timestamp, so ciphertexts cannot be swapped
 * between rows without detection.
 *
 * The key is NOT bound to user authentication or to "unlocked device required", because the
 * lock-screen receiver must be able to write records before the first unlock after a reboot
 * (Direct Boot). Viewing is protected separately by owner authentication in the UI.
 */
object CryptoBox {

    private const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "intrudersnap_records_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION: Byte = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private val lock = Any()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey = synchronized(lock) {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "Unexpected IV length ${iv.size}" }
        val ct = cipher.doFinal(plain)
        return byteArrayOf(VERSION) + iv + ct
    }

    /** @throws javax.crypto.AEADBadTagException if the blob or AAD was modified. */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > 1 + IV_LENGTH) { "Blob too short" }
        require(blob[0] == VERSION) { "Unsupported blob version ${blob[0]}" }
        val iv = blob.copyOfRange(1, 1 + IV_LENGTH)
        val ct = blob.copyOfRange(1 + IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    /** True when the key exists or can be created and used (e.g. Keystore available in Direct Boot). */
    fun isUsable(): Boolean = try {
        val aad = byteArrayOf(0)
        decrypt(encrypt(byteArrayOf(42), aad), aad).contentEquals(byteArrayOf(42))
    } catch (e: Exception) {
        false
    }

    fun aadFor(timestampMillis: Long): ByteArray =
        ByteArray(8) { i -> (timestampMillis ushr (56 - 8 * i)).toByte() }
}
