package com.catatanku.app.security

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enkripsi cadangan terautentikasi memakai AES-256-GCM streaming per blok (64 KB) tanpa berkas sementara polos:
 * - Format `CTKE\x01`: enkripsi langsung memakai kata sandi via PBKDF2WithHmacSHA256 (600.000 iterasi, kompatibel 120.000).
 * - Format `CTKH\x01`: amplop hibrida EC P-256 + ECDH + AES-256-GCM agar Cadangan Otomatis dapat berjalan
 *   di latar hanya dengan kunci publik, sementara dekripsi tetap hanya bisa dilakukan memakai sandi pemulihan.
 */
object BackupCrypto {
    private val MAGIC_CTKE = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte(), 1)
    private val MAGIC_CTKH = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'H'.code.toByte(), 1)
    private const val SALT_LEN = 16
    private const val NONCE_PREFIX_LEN = 8
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val CHUNK_BYTES = 64 * 1024
    private const val MAX_CHUNK_BYTES = 1024 * 1024
    private val PRIV_WRAP_AAD = "CTKH1-PRIV".toByteArray(Charsets.UTF_8)
    private val KEY_WRAP_AAD_PREFIX = "CTKH1-WRAP".toByteArray(Charsets.UTF_8)
    private val KEK_PREFIX = "CTKH1-KEK".toByteArray(Charsets.UTF_8)

    data class AutoBackupKeys(
        val publicKeyBase64: String,
        val wrappedPrivateKeyBase64: String,
    )

    fun isEncryptedHeader(prefix: ByteArray): Boolean =
        matchesMagic(prefix, MAGIC_CTKE) || matchesMagic(prefix, MAGIC_CTKH)

    private fun matchesMagic(prefix: ByteArray, magic: ByteArray): Boolean =
        prefix.size >= magic.size && magic.indices.all { prefix[it] == magic[it] }

    /**
     * Membuka aliran tulis terenkripsi `CTKE\x01` langsung di RAM (tanpa berkas ZIP polos sementara).
     * Pemanggil wajib memanggil `close()` pada stream hasil agar blok akhir terautentikasi ditulis.
     */
    fun openEncryptingStream(
        passphrase: CharArray,
        cipherOut: OutputStream,
        iterations: Int = DEFAULT_ITERATIONS,
    ): OutputStream {
        if (passphrase.isEmpty()) throw IllegalArgumentException("Kata sandi cadangan tidak boleh kosong.")
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Jumlah iterasi tidak valid." }
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { rng.nextBytes(it) }
        val keyBytes = deriveKey(passphrase, salt, iterations)
        return try {
            val out = DataOutputStream(cipherOut)
            out.write(MAGIC_CTKE)
            out.writeInt(iterations)
            out.write(salt)
            out.write(noncePrefix)
            out.writeInt(CHUNK_BYTES)
            out.flush()
            ChunkedAeadOutputStream(
                keyBytes = keyBytes,
                aadHeader = concat(MAGIC_CTKE, salt),
                noncePrefix = noncePrefix,
                chunkSize = CHUNK_BYTES,
                out = cipherOut,
                closeUnderlying = false,
            )
        } catch (t: Throwable) {
            Arrays.fill(keyBytes, 0.toByte())
            throw t
        }
    }

    fun encrypt(passphrase: CharArray, plainIn: InputStream, cipherOut: OutputStream) {
        openEncryptingStream(passphrase, cipherOut).use { encOut ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = plainIn.read(buf)
                if (n < 0) break
                encOut.write(buf, 0, n)
            }
        }
        cipherOut.flush()
    }

    /**
     * Membuat pasangan kunci EC P-256 untuk Cadangan Otomatis terenkripsi:
     * kunci publik disimpan polos (hanya bisa mengenkripsi), sedangkan kunci privat dibungkus sandi pemulihan.
     */
    fun generateAutoBackupKeys(
        recoveryPassphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): AutoBackupKeys {
        if (recoveryPassphrase.isEmpty()) throw IllegalArgumentException("Kata sandi pemulihan tidak boleh kosong.")
        val rng = SecureRandom()
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        val kp = kpg.generateKeyPair()
        val pubBytes = kp.public.encoded
        val privBytes = kp.private.encoded
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val kek = deriveKey(recoveryPassphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(PRIV_WRAP_AAD)
            val ct = cipher.doFinal(privBytes)
            val wrapped = ByteBuffer.allocate(1 + 4 + SALT_LEN + IV_LEN + ct.size)
                .put(1.toByte())
                .putInt(iterations)
                .put(salt)
                .put(iv)
                .put(ct)
                .array()
            val enc = Base64.getEncoder()
            AutoBackupKeys(
                publicKeyBase64 = enc.encodeToString(pubBytes),
                wrappedPrivateKeyBase64 = enc.encodeToString(wrapped),
            )
        } finally {
            Arrays.fill(privBytes, 0.toByte())
            Arrays.fill(kek, 0.toByte())
        }
    }

    fun verifyRecoveryPassphrase(recoveryPassphrase: CharArray, wrappedPrivateKeyBase64: String): Boolean {
        if (recoveryPassphrase.isEmpty() || wrappedPrivateKeyBase64.isBlank()) return false
        return try {
            val wrappedBytes = Base64.getDecoder().decode(wrappedPrivateKeyBase64)
            val unwrapped = unwrapPrivateKeyBytes(recoveryPassphrase, wrappedBytes)
            Arrays.fill(unwrapped, 0.toByte())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Membuka aliran enkripsi amplop `CTKH\x01` memakai kunci publik statis (tanpa sandi pemulihan di RAM/penyimpanan).
     */
    fun openHybridEncryptingStream(
        publicKeyBase64: String,
        wrappedPrivateKeyBase64: String,
        cipherOut: OutputStream,
    ): OutputStream {
        val dec = Base64.getDecoder()
        val staticPubBytes = dec.decode(publicKeyBase64)
        val wrappedPrivBytes = dec.decode(wrappedPrivateKeyBase64)
        val staticPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(staticPubBytes))

        val rng = SecureRandom()
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        val ephKp = kpg.generateKeyPair()
        val ephPubBytes = ephKp.public.encoded

        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(ephKp.private)
            doPhase(staticPub, true)
            generateSecret()
        }
        val wrapKey = deriveHybridKek(sharedSecret, ephPubBytes, wrappedPrivBytes)
        val fileKey = ByteArray(32).also { rng.nextBytes(it) }
        val wrapIv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { rng.nextBytes(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(TAG_BITS, wrapIv))
            cipher.updateAAD(concat(KEY_WRAP_AAD_PREFIX, ephPubBytes, wrappedPrivBytes))
            val wrappedFileKey = cipher.doFinal(fileKey)

            val out = DataOutputStream(cipherOut)
            out.write(MAGIC_CTKH)
            out.writeInt(wrappedPrivBytes.size)
            out.write(wrappedPrivBytes)
            out.writeInt(ephPubBytes.size)
            out.write(ephPubBytes)
            out.write(wrapIv)
            out.writeInt(wrappedFileKey.size)
            out.write(wrappedFileKey)
            out.write(salt)
            out.write(noncePrefix)
            out.writeInt(CHUNK_BYTES)
            out.flush()

            ChunkedAeadOutputStream(
                keyBytes = fileKey,
                aadHeader = concat(MAGIC_CTKH, salt),
                noncePrefix = noncePrefix,
                chunkSize = CHUNK_BYTES,
                out = cipherOut,
                closeUnderlying = false,
            )
        } catch (t: Throwable) {
            Arrays.fill(fileKey, 0.toByte())
            throw t
        } finally {
            Arrays.fill(sharedSecret, 0.toByte())
            Arrays.fill(wrapKey, 0.toByte())
        }
    }

    /**
     * Membuka aliran dekripsi streaming (`CTKE\x01` maupun `CTKH\x01`) di RAM tanpa menulis berkas dekripsi sementara.
     */
    fun openDecryptingStream(passphrase: CharArray, cipherIn: InputStream): InputStream {
        if (passphrase.isEmpty()) throw GeneralSecurityException("Kata sandi cadangan tidak boleh kosong.")
        val inp = BufferedInputStream(cipherIn, 32 * 1024)
        val dataIn = DataInputStream(inp)
        val magic = ByteArray(MAGIC_CTKE.size)
        try {
            dataIn.readFully(magic)
        } catch (e: EOFException) {
            throw GeneralSecurityException("Format berkas terenkripsi terpotong.", e)
        }

        return when {
            matchesMagic(magic, MAGIC_CTKE) -> openCtkeDecryptingStream(passphrase, dataIn, inp)
            matchesMagic(magic, MAGIC_CTKH) -> openCtkhDecryptingStream(passphrase, dataIn, inp)
            else -> throw GeneralSecurityException("Format berkas terenkripsi tidak dikenali.")
        }
    }

    fun decrypt(passphrase: CharArray, cipherIn: InputStream, plainOut: OutputStream) {
        openDecryptingStream(passphrase, cipherIn).use { decIn ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = decIn.read(buf)
                if (n < 0) break
                plainOut.write(buf, 0, n)
            }
        }
        plainOut.flush()
    }

    private fun openCtkeDecryptingStream(
        passphrase: CharArray,
        dataIn: DataInputStream,
        rawIn: InputStream,
    ): InputStream {
        val iterations = dataIn.readInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw GeneralSecurityException("Parameter derivasi kunci tidak valid.")
        }
        val salt = ByteArray(SALT_LEN).also { dataIn.readFully(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { dataIn.readFully(it) }
        val chunkSize = dataIn.readInt()
        if (chunkSize !in 1024..MAX_CHUNK_BYTES) {
            throw GeneralSecurityException("Ukuran blok enkripsi tidak valid.")
        }
        val keyBytes = deriveKey(passphrase, salt, iterations)
        return try {
            ChunkedAeadInputStream(
                keyBytes = keyBytes,
                aadHeader = concat(MAGIC_CTKE, salt),
                noncePrefix = noncePrefix,
                chunkSize = chunkSize,
                inp = rawIn,
            )
        } catch (t: Throwable) {
            Arrays.fill(keyBytes, 0.toByte())
            throw t
        }
    }

    private fun openCtkhDecryptingStream(
        passphrase: CharArray,
        dataIn: DataInputStream,
        rawIn: InputStream,
    ): InputStream {
        val wrappedPrivLen = dataIn.readInt()
        if (wrappedPrivLen !in 32..4096) throw GeneralSecurityException("Header kunci pemulihan tidak valid.")
        val wrappedPrivBytes = ByteArray(wrappedPrivLen).also { dataIn.readFully(it) }

        val ephPubLen = dataIn.readInt()
        if (ephPubLen !in 32..512) throw GeneralSecurityException("Header kunci sementara tidak valid.")
        val ephPubBytes = ByteArray(ephPubLen).also { dataIn.readFully(it) }

        val wrapIv = ByteArray(IV_LEN).also { dataIn.readFully(it) }
        val wrappedFileKeyLen = dataIn.readInt()
        if (wrappedFileKeyLen !in 32..256) throw GeneralSecurityException("Header kunci berkas tidak valid.")
        val wrappedFileKey = ByteArray(wrappedFileKeyLen).also { dataIn.readFully(it) }

        val salt = ByteArray(SALT_LEN).also { dataIn.readFully(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { dataIn.readFully(it) }
        val chunkSize = dataIn.readInt()
        if (chunkSize !in 1024..MAX_CHUNK_BYTES) throw GeneralSecurityException("Ukuran blok enkripsi tidak valid.")

        val privBytes = unwrapPrivateKeyBytes(passphrase, wrappedPrivBytes)
        var sharedSecret: ByteArray? = null
        var wrapKey: ByteArray? = null
        val fileKey: ByteArray = try {
            val kf = KeyFactory.getInstance("EC")
            val staticPriv = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
            val ephPub = kf.generatePublic(X509EncodedKeySpec(ephPubBytes))
            sharedSecret = KeyAgreement.getInstance("ECDH").run {
                init(staticPriv)
                doPhase(ephPub, true)
                generateSecret()
            }
            wrapKey = deriveHybridKek(sharedSecret, ephPubBytes, wrappedPrivBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(TAG_BITS, wrapIv))
            cipher.updateAAD(concat(KEY_WRAP_AAD_PREFIX, ephPubBytes, wrappedPrivBytes))
            try {
                cipher.doFinal(wrappedFileKey)
            } catch (e: GeneralSecurityException) {
                throw GeneralSecurityException("Kata sandi salah atau berkas cadangan rusak.", e)
            }
        } finally {
            Arrays.fill(privBytes, 0.toByte())
            sharedSecret?.let { Arrays.fill(it, 0.toByte()) }
            wrapKey?.let { Arrays.fill(it, 0.toByte()) }
        }
        return try {
            ChunkedAeadInputStream(
                keyBytes = fileKey,
                aadHeader = concat(MAGIC_CTKH, salt),
                noncePrefix = noncePrefix,
                chunkSize = chunkSize,
                inp = rawIn,
            )
        } catch (t: Throwable) {
            Arrays.fill(fileKey, 0.toByte())
            throw t
        }
    }

    private fun unwrapPrivateKeyBytes(passphrase: CharArray, wrapped: ByteArray): ByteArray {
        if (wrapped.size < 1 + 4 + SALT_LEN + IV_LEN + 32) {
            throw GeneralSecurityException("Data kunci pemulihan tidak valid.")
        }
        val buf = ByteBuffer.wrap(wrapped)
        val version = buf.get().toInt()
        if (version != 1) throw GeneralSecurityException("Versi kunci pemulihan tidak didukung.")
        val iterations = buf.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw GeneralSecurityException("Parameter derivasi kunci pemulihan tidak valid.")
        }
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = ByteArray(IV_LEN).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val kek = deriveKey(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(PRIV_WRAP_AAD)
            try {
                cipher.doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw GeneralSecurityException("Kata sandi salah atau berkas cadangan rusak.", e)
            }
        } finally {
            Arrays.fill(kek, 0.toByte())
        }
    }

    private fun deriveHybridKek(sharedSecret: ByteArray, ephPubBytes: ByteArray, wrappedPrivBytes: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(KEK_PREFIX)
        md.update(sharedSecret)
        md.update(ephPubBytes)
        md.update(wrappedPrivBytes)
        return md.digest()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
