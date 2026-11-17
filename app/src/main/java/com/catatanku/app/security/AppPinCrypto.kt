package com.catatanku.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pengelola hash & pembatasan coba ulang PIN/sandi mandiri aplikasi (PBKDF2WithHmacSHA256).
 * Sandi mentah tidak pernah disimpan; hanya salt dan turunan hash yang disimpan.
 */
object AppPinCrypto {
    const val MIN_PIN_LEN = 4
    const val MAX_PIN_LEN = 64
    const val DEFAULT_ITERATIONS = 600_000
    const val DEFAULT_WIPE_ATTEMPTS = 10
    private const val SALT_LEN = 16
    private const val PREFIX = "PINV1"

    private val rng = SecureRandom()

    fun isValidFormat(pin: CharArray): Boolean =
        pin.size in MIN_PIN_LEN..MAX_PIN_LEN && pin.any { !it.isWhitespace() }

    fun hashPin(pin: CharArray, iterations: Int = DEFAULT_ITERATIONS): String {
        require(isValidFormat(pin)) { "PIN/sandi harus $MIN_PIN_LEN–$MAX_PIN_LEN karakter." }
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val hash = derive(pin, salt, iterations)
        return try {
            val enc = Base64.getEncoder()
            "$PREFIX:$iterations:${enc.encodeToString(salt)}:${enc.encodeToString(hash)}"
        } finally {
            Arrays.fill(hash, 0.toByte())
        }
    }

    fun verifyPin(pin: CharArray, storedRecord: String?): Boolean {
        if (pin.isEmpty() || storedRecord.isNullOrBlank()) return false
        val parts = storedRecord.split(':')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..2_000_000 } ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull()?.takeIf { it.size == SALT_LEN } ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull()?.takeIf { it.size == 32 } ?: return false
        val actual = derive(pin, salt, iterations)
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            Arrays.fill(actual, 0.toByte())
        }
    }

    fun lockoutDelayMs(failedAttempts: Int): Long = when {
        failedAttempts < 5 -> 0L
        failedAttempts <= 6 -> 30_000L
        failedAttempts <= 8 -> 120_000L
        else -> 300_000L
    }

    fun shouldTriggerWipe(failedAttempts: Int, wipeAfterFailuresEnabled: Boolean, maxFailures: Int = DEFAULT_WIPE_ATTEMPTS): Boolean =
        wipeAfterFailuresEnabled && maxFailures > 0 && failedAttempts >= maxFailures

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
