package com.catatanku.app.security

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Penyedia kunci enkripsi penyimpanan lokal (di Android memakai Android Keystore; di JVM uji memakai kunci memori).
 */
interface VaultKeyProvider {
    fun getDataKey(): ByteArray
    fun destroyKeys()
}

class InMemoryKeyProvider(initialKey: ByteArray? = null) : VaultKeyProvider {
    private val lock = Any()
    private var key: ByteArray = initialKey?.copyOf(32) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    override fun getDataKey(): ByteArray = synchronized(lock) {
        if (key.all { it == 0.toByte() }) {
            key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        key.copyOf()
    }

    override fun destroyKeys() = synchronized(lock) {
        Arrays.fill(key, 0.toByte())
    }
}

/**
 * Enkripsi AES-256-GCM di tempat penyimpanan untuk isi catatan, riwayat versi, vektor embedding, dan lampiran.
 * Murni JVM agar seluruh sifat kriptografi dapat diuji tanpa perangkat.
 */
object VaultCrypto {
    private const val STR_PREFIX = "CTKV1:"
    private val BLOB_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(), 1)
    private val ATT_MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte(), 1)
    private const val IV_LEN = 12
    private const val NONCE_PREFIX_LEN = 8
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val ATT_CHUNK_BYTES = 64 * 1024

    private val rng = SecureRandom()

    fun isEncryptedString(value: String): Boolean = value.startsWith(STR_PREFIX)

    fun isEncryptedBlob(bytes: ByteArray): Boolean =
        bytes.size >= BLOB_MAGIC.size && BLOB_MAGIC.indices.all { bytes[it] == BLOB_MAGIC[it] }

    fun isEncryptedAttachmentHeader(prefix: ByteArray): Boolean =
        prefix.size >= ATT_MAGIC.size && ATT_MAGIC.indices.all { prefix[it] == ATT_MAGIC[it] }

    fun encryptString(key: ByteArray, aadContext: String, plain: String): String {
        val raw = encryptRaw(key, aadContext.toByteArray(Charsets.UTF_8), plain.toByteArray(Charsets.UTF_8))
        return STR_PREFIX + Base64.getEncoder().encodeToString(raw)
    }

    fun decryptStringIfNeeded(key: ByteArray, aadContext: String, stored: String): String {
        if (!isEncryptedString(stored)) return stored
        val raw = try {
            Base64.getDecoder().decode(stored.substring(STR_PREFIX.length))
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Format ciphertext catatan tidak valid.", e)
        }
        val plain = decryptRaw(key, aadContext.toByteArray(Charsets.UTF_8), raw)
        return plain.toString(Charsets.UTF_8)
    }

    fun encryptBlob(key: ByteArray, aadContext: String, plain: ByteArray): ByteArray {
        val body = encryptRaw(key, aadContext.toByteArray(Charsets.UTF_8), plain)
        return ByteArray(BLOB_MAGIC.size + body.size).also { out ->
            System.arraycopy(BLOB_MAGIC, 0, out, 0, BLOB_MAGIC.size)
            System.arraycopy(body, 0, out, BLOB_MAGIC.size, body.size)
        }
    }

    fun decryptBlobIfNeeded(key: ByteArray, aadContext: String, stored: ByteArray): ByteArray {
        if (!isEncryptedBlob(stored)) return stored
        val body = stored.copyOfRange(BLOB_MAGIC.size, stored.size)
        return decryptRaw(key, aadContext.toByteArray(Charsets.UTF_8), body)
    }

    private fun encryptRaw(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 32) { "Kunci AES-256 harus 32 byte." }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plain)
        return ByteArray(IV_LEN + ct.size).also { out ->
            System.arraycopy(iv, 0, out, 0, IV_LEN)
            System.arraycopy(ct, 0, out, IV_LEN, ct.size)
        }
    }

    private fun decryptRaw(key: ByteArray, aad: ByteArray, ivAndCt: ByteArray): ByteArray {
        require(key.size == 32) { "Kunci AES-256 harus 32 byte." }
        if (ivAndCt.size < IV_LEN + TAG_BYTES) throw GeneralSecurityException("Data terenkripsi terpotong.")
        val iv = ivAndCt.copyOfRange(0, IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ivAndCt, IV_LEN, ivAndCt.size - IV_LEN)
    }

    fun encryptAttachment(key: ByteArray, fileName: String, plainIn: InputStream, cipherOut: OutputStream) {
        openAttachmentEncryptingStream(key, fileName, cipherOut).use { out ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = plainIn.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }

    fun openAttachmentEncryptingStream(key: ByteArray, fileName: String, cipherOut: OutputStream): OutputStream {
        require(key.size == 32) { "Kunci AES-256 harus 32 byte." }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { rng.nextBytes(it) }
        val aadBase = "CTKA1:$fileName".toByteArray(Charsets.UTF_8)
        cipherOut.write(ATT_MAGIC)
        cipherOut.write(noncePrefix)
        cipherOut.write(ByteBuffer.allocate(4).putInt(ATT_CHUNK_BYTES).array())
        return ChunkedAeadOutputStream(
            keyBytes = key.copyOf(),
            aadHeader = aadBase,
            noncePrefix = noncePrefix,
            chunkSize = ATT_CHUNK_BYTES,
            out = cipherOut,
            closeUnderlying = false,
        )
    }

    fun openAttachmentDecryptingStream(key: ByteArray, fileName: String, rawIn: InputStream): InputStream {
        val buffered = rawIn.buffered(16 * 1024)
        buffered.mark(ATT_MAGIC.size + 4)
        val prefix = ByteArray(ATT_MAGIC.size)
        val read = readAtMost(buffered, prefix)
        buffered.reset()
        if (read < ATT_MAGIC.size || !isEncryptedAttachmentHeader(prefix)) {
            return buffered
        }
        require(key.size == 32) { "Kunci AES-256 harus 32 byte." }
        val header = ByteArray(ATT_MAGIC.size + NONCE_PREFIX_LEN + 4)
        readFully(buffered, header)
        val noncePrefix = header.copyOfRange(ATT_MAGIC.size, ATT_MAGIC.size + NONCE_PREFIX_LEN)
        val chunkSize = ByteBuffer.wrap(header, ATT_MAGIC.size + NONCE_PREFIX_LEN, 4).int
        if (chunkSize !in 1024..1024 * 1024) {
            throw GeneralSecurityException("Ukuran blok lampiran tidak valid.")
        }
        val aadBase = "CTKA1:$fileName".toByteArray(Charsets.UTF_8)
        return ChunkedAeadInputStream(
            keyBytes = key.copyOf(),
            aadHeader = aadBase,
            noncePrefix = noncePrefix,
            chunkSize = chunkSize,
            inp = buffered,
        )
    }

    fun decryptAttachmentBytes(key: ByteArray, fileName: String, rawIn: InputStream, maxBytes: Int = 32 * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        openAttachmentDecryptingStream(key, fileName, rawIn).use { dec ->
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = dec.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("Lampiran melebihi batas ukuran aman.")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun readAtMost(inp: InputStream, dst: ByteArray): Int {
        var total = 0
        while (total < dst.size) {
            val n = inp.read(dst, total, dst.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readFully(inp: InputStream, dst: ByteArray) {
        if (readAtMost(inp, dst) != dst.size) {
            throw EOFException("Header lampiran terenkripsi terpotong.")
        }
    }
}

/**
 * OutputStream AES-256-GCM per blok agar enkripsi streaming berjalan di RAM O(1) tanpa berkas sementara polos.
 */
internal class ChunkedAeadOutputStream(
    private val keyBytes: ByteArray,
    private val aadHeader: ByteArray,
    private val noncePrefix: ByteArray,
    private val chunkSize: Int,
    private val out: OutputStream,
    private val closeUnderlying: Boolean = true,
) : OutputStream() {
    private val keySpec = SecretKeySpec(keyBytes, "AES")
    private val buf = ByteArray(chunkSize)
    private var pos = 0
    private var chunkIndex = 0
    private var closed = false

    override fun write(b: Int) {
        if (closed) throw IOException("Stream sudah ditutup.")
        if (pos == buf.size) flushChunk(isFinal = false)
        buf[pos++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("Stream sudah ditutup.")
        if (len == 0) return
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (pos == buf.size) flushChunk(isFinal = false)
            val space = buf.size - pos
            val copy = minOf(space, remaining)
            System.arraycopy(b, offset, buf, pos, copy)
            pos += copy
            offset += copy
            remaining -= copy
        }
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            flushChunk(isFinal = true)
            out.flush()
        } finally {
            Arrays.fill(buf, 0.toByte())
            Arrays.fill(keyBytes, 0.toByte())
            if (closeUnderlying) out.close()
        }
    }

    private fun flushChunk(isFinal: Boolean) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteBuffer.allocate(12).put(noncePrefix).putInt(chunkIndex).array()
        val aad = ByteBuffer.allocate(aadHeader.size + 5).put(aadHeader).putInt(chunkIndex).put(if (isFinal) 1 else 0).array()
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(buf, 0, pos)
        out.write(if (isFinal) 1 else 0)
        out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
        out.write(ct)
        chunkIndex++
        pos = 0
    }
}

/**
 * InputStream AES-256-GCM per blok yang mengautentikasi blok awal seketika dan mencegah berkas terpotong/berlebih.
 */
internal class ChunkedAeadInputStream(
    private val keyBytes: ByteArray,
    private val aadHeader: ByteArray,
    private val noncePrefix: ByteArray,
    private val chunkSize: Int,
    private val inp: InputStream,
) : FilterInputStream(inp) {
    private val keySpec = SecretKeySpec(keyBytes, "AES")
    private var currentPlain = ByteArray(0)
    private var plainPos = 0
    private var chunkIndex = 0
    private var sawFinal = false
    private var closed = false

    init {
        // Autentikasi blok pertama segera agar sandi/kunci salah gagal cepat sebelum ZIP parser membaca apa pun.
        readNextChunk()
    }

    override fun read(): Int {
        if (closed) return -1
        while (plainPos >= currentPlain.size) {
            if (sawFinal) return -1
            readNextChunk()
        }
        return currentPlain[plainPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (closed) return -1
        while (plainPos >= currentPlain.size) {
            if (sawFinal) return -1
            readNextChunk()
        }
        val avail = currentPlain.size - plainPos
        val count = minOf(len, avail)
        System.arraycopy(currentPlain, plainPos, b, off, count)
        plainPos += count
        return count
    }

    override fun close() {
        if (closed) return
        closed = true
        Arrays.fill(currentPlain, 0.toByte())
        Arrays.fill(keyBytes, 0.toByte())
        super.close()
    }

    private fun readNextChunk() {
        val flag = inp.read()
        if (flag < 0) throw GeneralSecurityException("Berkas terenkripsi terpotong sebelum blok akhir.")
        val isFinal = when (flag) {
            0 -> false
            1 -> true
            else -> throw GeneralSecurityException("Penanda blok enkripsi rusak.")
        }
        val lenBuf = ByteArray(4)
        var readLen = 0
        while (readLen < 4) {
            val n = inp.read(lenBuf, readLen, 4 - readLen)
            if (n < 0) throw GeneralSecurityException("Berkas terenkripsi terpotong di tengah blok.")
            readLen += n
        }
        val ctLen = ByteBuffer.wrap(lenBuf).int
        if (ctLen < 16 || ctLen > chunkSize + 64) {
            throw GeneralSecurityException("Panjang blok enkripsi tidak wajar.")
        }
        val ct = ByteArray(ctLen)
        var readCt = 0
        while (readCt < ctLen) {
            val n = inp.read(ct, readCt, ctLen - readCt)
            if (n < 0) throw GeneralSecurityException("Berkas terenkripsi terpotong sebelum blok selesai.")
            readCt += n
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteBuffer.allocate(12).put(noncePrefix).putInt(chunkIndex).array()
        val aad = ByteBuffer.allocate(aadHeader.size + 5).put(aadHeader).putInt(chunkIndex).put(if (isFinal) 1 else 0).array()
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plain = try {
            cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            throw GeneralSecurityException("Kata sandi salah atau berkas cadangan rusak.", e)
        }
        Arrays.fill(currentPlain, 0.toByte())
        currentPlain = plain
        plainPos = 0
        chunkIndex++
        if (isFinal) {
            sawFinal = true
            if (inp.read() != -1) {
                throw GeneralSecurityException("Terdapat data berlebih setelah akhir berkas terenkripsi.")
            }
        }
    }
}
