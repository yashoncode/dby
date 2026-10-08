package com.dby.mobile.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved passwords, encrypted with an AES-256-GCM Android Keystore key (spec §7). A blob is
 * [kind][12-byte IV][ciphertext + tag]. Kind 0 uses the plain key; kind 1 the key that needs a
 * fingerprint or screen-lock unlock in the last five minutes, used while App lock is on.
 */
class Secrets {
    private val keystore = KeyStore.getInstance(STORE).apply { load(null) }

    /** Throws `UserNotAuthenticatedException` when the locked key needs an unlock first. */
    fun encrypt(password: String, locked: Boolean): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(locked))
        return byteArrayOf(if (locked) 1 else 0) + cipher.iv + cipher.doFinal(password.toByteArray())
    }

    /**
     * Throws `IllegalArgumentException` for an empty blob (no saved password),
     * `UserNotAuthenticatedException` (unlock, then retry) and `KeyPermanentlyInvalidatedException`
     * (the screen lock or fingerprints changed: ask for the password again).
     */
    fun decrypt(blob: ByteArray): String {
        require(blob.size > HEADER) { "no saved password" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(blob[0] == 1.toByte()), GCMParameterSpec(128, blob, 1, IV))
        return String(cipher.doFinal(blob, HEADER, blob.size - HEADER))
    }

    fun forget(locked: Boolean) = keystore.deleteEntry(alias(locked))

    private fun alias(locked: Boolean) = if (locked) "dby.passwords.locked" else "dby.passwords"

    private fun key(locked: Boolean): SecretKey {
        (keystore.getKey(alias(locked), null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias(locked), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (locked) {
            spec.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                spec.setUserAuthenticationParameters(
                    UNLOCK_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(UNLOCK_SECONDS)
            }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec.build()) }.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
        const val HEADER = 1 + IV
        const val UNLOCK_SECONDS = 300
    }
}
