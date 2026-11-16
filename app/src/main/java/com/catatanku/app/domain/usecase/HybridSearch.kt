package com.catatanku.app.domain.usecase

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Kontrak penyedia embedding lokal (murni Kotlin agar logika hybrid search dapat diuji di JVM).
 */
interface EmbeddingProvider {
    val isAvailable: Boolean
    val modelId: String
    suspend fun embedDocument(title: String, body: String, tags: List<String>): FloatArray?
    suspend fun embedQuery(query: String): FloatArray?
}

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

    fun documentText(title: String, body: String, tags: List<String>): String = buildString {
        if (title.isNotBlank()) append(title.trim()).append('\n')
        if (tags.isNotEmpty()) append(tags.joinToString(", ")).append('\n')
        append(body.trim().take(4000))
    }.trim()
}

/**
 * Indeks vektor semantik di memori (imutable per snapshot, diperbarui inkremental).
 */
class SemanticIndex(
    private val vectors: Map<Long, FloatArray> = emptyMap(),
    val modelId: String = "",
) {
    val size: Int get() = vectors.size
    val isEmpty: Boolean get() = vectors.isEmpty()

    fun score(noteId: Long, queryVector: FloatArray): Double {
        val docVec = vectors[noteId] ?: return 0.0
        return EmbeddingCodec.cosineNormalized(docVec, queryVector)
    }

    fun topMatches(queryVector: FloatArray, minSimilarity: Double = 0.30, limit: Int = 100): Map<Long, Double> {
        if (vectors.isEmpty() || queryVector.isEmpty()) return emptyMap()
        val normQ = EmbeddingCodec.normalize(queryVector)
        val out = LinkedHashMap<Long, Double>()
        for ((id, vec) in vectors) {
            val sim = EmbeddingCodec.cosineNormalized(vec, normQ)
            if (sim >= minSimilarity) out[id] = sim
        }
        if (out.size <= limit) return out
        return out.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
    }
}

/**
 * Pencarian hibrida: menggabungkan skor TF-IDF + trigram dan similarity embedding lokal.
 * Bila model embedding tidak tersedia, selalu jatuh kembali murni ke [SearchIndex].
 */
object HybridSearch {
    // Bobot dievaluasi lewat HybridSearchTest pada korpus kueri persis, sinonim, dan parafrasa bahasa Indonesia.
    const val DEFAULT_ALPHA = 0.65
    const val DEFAULT_BETA = 0.35
    const val MIN_SEMANTIC_ONLY_SIMILARITY = 0.35

    fun search(
        tfidf: SearchIndex,
        semantic: SemanticIndex?,
        query: String,
        queryVector: FloatArray?,
        extraTerms: List<String> = emptyList(),
        alpha: Double = DEFAULT_ALPHA,
        beta: Double = DEFAULT_BETA,
        limit: Int = 100,
    ): List<SearchIndex.Hit> {
        val lexicalHits = tfidf.search(query, extraTerms, limit = maxOf(limit, 200))
        if (semantic == null || semantic.isEmpty || queryVector == null || queryVector.isEmpty()) {
            return lexicalHits.take(limit)
        }
        val normQ = EmbeddingCodec.normalize(queryVector)
        val lexicalMap = HashMap<Long, Double>(lexicalHits.size * 2)
        val maxLexical = lexicalHits.maxOfOrNull { it.score }?.coerceAtLeast(1.0) ?: 1.0
        for (h in lexicalHits) lexicalMap[h.id] = (h.score / maxLexical).coerceIn(0.0, 1.0)

        val semanticHits = semantic.topMatches(normQ, minSimilarity = MIN_SEMANTIC_ONLY_SIMILARITY, limit = 200)
        val candidateIds = LinkedHashSet<Long>(lexicalMap.size + semanticHits.size)
        candidateIds.addAll(lexicalMap.keys)
        candidateIds.addAll(semanticHits.keys)

        val combined = ArrayList<SearchIndex.Hit>(candidateIds.size)
        for (id in candidateIds) {
            val tfidfScore = lexicalMap[id] ?: 0.0
            val semanticScore = semantic.score(id, normQ)
            val hybridScore = alpha * tfidfScore + beta * semanticScore
            if (hybridScore > 0.05) {
                combined += SearchIndex.Hit(id, hybridScore)
            }
        }
        return combined.sortedByDescending { it.score }.take(limit)
    }
}
