package com.catatanku.app.domain.usecase

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Sisa pencarian semantik (dihapus di 1.10). Hanya dipakai jalur migrasi basis data lama (tabel `embeddings`,
 * skema v5–v6) dan pembersihan sekali jalan; tidak ada lagi yang menulis vektor baru.
 */
data class StoredEmbedding(
    val noteId: Long,
    val contentHash: String,
    val modelId: String,
    val vector: FloatArray,
)

object EmbeddingCodec {
    fun contentHash(title: String, body: String, tags: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(title.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(tags.joinToString("\u001f").toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(body.take(8000).toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val len = sqrt(sum).toFloat()
        if (len <= 1e-8f) return v.copyOf()
        return FloatArray(v.size) { v[it] / len }
    }

    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return FloatArray(0)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }

    fun cosineNormalized(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(0.0, 1.0)
    }
}
