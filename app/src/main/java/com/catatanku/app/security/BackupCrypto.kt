package com.catatanku.app.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enkripsi cadangan terautentikasi memakai AES-256-GCM streaming per blok (64 KB) dengan derivasi kunci
 * PBKDF2WithHmacSHA256. Kata sandi dan kunci tidak pernah disimpan ke penyimpanan.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte(), 1)
    private const val SALT_LEN = 16
    private const val NONCE_PREFIX_LEN = 8
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val DEFAULT_ITERATIONS = 120_000
    private const val CHUNK_BYTES = 64 * 1024
    private const val MAX_CHUNK_BYTES = 1024 * 1024

    fun isEncryptedHeader(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && MAGIC.indices.all { prefix[it] == MAGIC[it] }

    fun encrypt(passphrase: CharArray, plainIn: InputStream, cipherOut: OutputStream) {
        if (passphrase.isEmpty()) throw IllegalArgumentException("Kata sandi cadangan tidak boleh kosong.")
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { rng.nextBytes(it) }
        val keyBytes = deriveKey(passphrase, salt, DEFAULT_ITERATIONS)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        try {
            val out = DataOutputStream(cipherOut)
            out.write(MAGIC)
            out.writeInt(DEFAULT_ITERATIONS)
            out.write(salt)
            out.write(noncePrefix)
            out.writeInt(CHUNK_BYTES)

            val buf = ByteArray(CHUNK_BYTES)
            var chunkIndex = 0
            while (true) {
                val read = readFullChunk(plainIn, buf)
                if (read == 0 && chunkIndex > 0) break
                val isFinal = read < CHUNK_BYTES
                // Pastikan blok terakhir eksplisit; kalau pas kelipatan CHUNK_BYTES, baca 1 byte intip.
                val nextByte = if (!isFinal) plainIn.read() else -1
                val reallyFinal = isFinal || nextByte == -1
                writeChunk(out, keySpec, salt, noncePrefix, chunkIndex, reallyFinal, buf, read)
                chunkIndex++
                if (reallyFinal) break
                buf[0] = nextByte.toByte()
                val rest = readFullChunk(plainIn, buf, offset = 1, maxLen = CHUNK_BYTES - 1)
                val total = 1 + rest
                val nextFinal = total < CHUNK_BYTES
                writeChunk(out, keySpec, salt, noncePrefix, chunkIndex, nextFinal, buf, total)
                chunkIndex++
                if (nextFinal) break
            }
            out.flush()
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    fun decrypt(passphrase: CharArray, cipherIn: InputStream, plainOut: OutputStream) {
        if (passphrase.isEmpty()) throw GeneralSecurityException("Kata sandi cadangan tidak boleh kosong.")
        val inp = DataInputStream(cipherIn)
        val magic = ByteArray(MAGIC.size)
        inp.readFully(magic)
        if (!isEncryptedHeader(magic)) throw GeneralSecurityException("Format berkas terenkripsi tidak dikenali.")
        val iterations = inp.readInt()
        if (iterations !in 10_000..1_000_000) throw GeneralSecurityException("Parameter derivasi kunci tidak valid.")
        val salt = ByteArray(SALT_LEN).also { inp.readFully(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { inp.readFully(it) }
        val chunkSize = inp.readInt()
        if (chunkSize !in 1024..MAX_CHUNK_BYTES) throw GeneralSecurityException("Ukuran blok enkripsi tidak valid.")

        val keyBytes = deriveKey(passphrase, salt, iterations)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        try {
            var chunkIndex = 0
            var sawFinal = false
            while (!sawFinal) {
                val finalFlag = try {
                    inp.readUnsignedByte()
                } catch (_: EOFException) {
                    throw GeneralSecurityException("Berkas terenkripsi terpotong sebelum blok akhir.")
                }
                val isFinal = when (finalFlag) {
                    0 -> false
                    1 -> true
                    else -> throw GeneralSecurityException("Penanda blok enkripsi rusak.")
                }
                val ctLen = try {
                    inp.readInt()
                } catch (_: EOFException) {
                    throw GeneralSecurityException("Berkas terenkripsi terpotong di tengah blok.")
                }
                if (ctLen < (TAG_BITS / 8) || ctLen > chunkSize + 64) {
                    throw GeneralSecurityException("Panjang blok enkripsi tidak wajar.")
                }
                val ct = ByteArray(ctLen)
                try {
                    inp.readFully(ct)
                } catch (_: EOFException) {
                    throw GeneralSecurityException("Berkas terenkripsi terpotong sebelum blok selesai.")
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, chunkIv(noncePrefix, chunkIndex)))
                cipher.updateAAD(chunkAad(salt, chunkIndex, isFinal))
                val plain = try {
                    cipher.doFinal(ct)
                } catch (e: GeneralSecurityException) {
                    throw GeneralSecurityException("Kata sandi salah atau berkas cadangan rusak.", e)
                }
                plainOut.write(plain)
                chunkIndex++
                sawFinal = isFinal
            }
            if (inp.read() != -1) {
                throw GeneralSecurityException("Terdapat data berlebih setelah akhir berkas terenkripsi.")
            }
            plainOut.flush()
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    private fun writeChunk(
        out: DataOutputStream,
        key: SecretKeySpec,
        salt: ByteArray,
        noncePrefix: ByteArray,
        index: Int,
        isFinal: Boolean,
        plain: ByteArray,
        len: Int,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkIv(noncePrefix, index)))
        cipher.updateAAD(chunkAad(salt, index, isFinal))
        val ct = cipher.doFinal(plain, 0, len)
        out.writeByte(if (isFinal) 1 else 0)
        out.writeInt(ct.size)
        out.write(ct)
    }

    private fun chunkIv(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(IV_LEN).put(prefix).putInt(index).array()

    private fun chunkAad(salt: ByteArray, index: Int, isFinal: Boolean): ByteArray =
        ByteBuffer.allocate(MAGIC.size + SALT_LEN + 5)
            .put(MAGIC)
            .put(salt)
            .putInt(index)
            .put(if (isFinal) 1.toByte() else 0.toByte())
            .array()

    private fun readFullChunk(input: InputStream, buf: ByteArray, offset: Int = 0, maxLen: Int = buf.size): Int {
        var total = 0
        while (total < maxLen) {
            val n = input.read(buf, offset + total, maxLen - total)
            if (n < 0) break
            total += n
        }
        return total
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
