package com.catatanku.app

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Mesin pencarian & kemiripan catatan. Murni Kotlin, tanpa model, tanpa Android API,
 * sehingga instan di HP apa pun dan bisa diuji di JVM.
 *
 * Teknik: TF-IDF atas kata + trigram karakter. Trigram membuat "berlari" tetap cocok
 * dengan "lari" dan menoleransi salah ketik, tanpa perlu stemmer bahasa Indonesia.
 */
object TextTools {
    private val STOP = setOf(
        "yang", "dan", "di", "ke", "dari", "ini", "itu", "untuk", "dengan", "atau", "pada", "adalah",
        "juga", "akan", "sudah", "belum", "tidak", "bisa", "ada", "saya", "aku", "kamu", "kita", "kami",
        "mereka", "dia", "nya", "karena", "jadi", "agar", "supaya", "oleh", "dalam", "sebagai", "lebih",
        "saat", "ketika", "kalau", "jika", "tapi", "tetapi", "namun", "lagi", "saja", "pun", "per",
        "harus", "perlu", "buat", "sama", "seperti", "masih", "hanya", "bahwa", "serta", "para", "tiap",
        "the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "is", "are", "was", "be", "it",
        "this", "that", "with", "as", "at", "by", "from", "not", "but", "have", "has", "i", "you", "we",
    )

    fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in STOP }

    /** Menambahkan fitur (kata + trigram) dari [text] ke [into] dengan bobot [weight]. */
    fun addFeatures(text: String, weight: Double, into: MutableMap<String, Double>) {
        for (w in tokenize(text)) {
            into[w] = (into[w] ?: 0.0) + weight
            if (w.length >= 4) {
                for (i in 0..w.length - 3) {
                    val g = "#" + w.substring(i, i + 3)
                    into[g] = (into[g] ?: 0.0) + weight * 0.3
                }
            }
        }
    }

    /** Mengurai jawaban LLM ("tag1, tag2\n- tag3") menjadi daftar bersih. */
    fun parseList(raw: String, max: Int = 6): List<String> =
        raw.split(',', '\n', ';', '•', '|')
            .map { it.substringAfterLast(':').trim().trimStart('-', '*', '#', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }
            .map { it.trim(' ', '"', '\'', '`', '.', ':', '*', '#').lowercase() }
            .filter { it.length in 2..28 && it.split(' ').size <= 3 }
            .distinct()
            .take(max)

    fun normalizeTag(s: String): String =
        s.trim().trimStart('#').lowercase().replace(Regex("\\s+"), " ").take(28)
}

class SearchIndex(docs: List<Doc>) {
    data class Doc(val id: Long, val title: String, val body: String, val tags: List<String>)
    data class Hit(val id: Long, val score: Double)

    private val idf: Map<String, Double>
    private val maxIdf: Double
    private val vectors: Map<Long, Map<String, Double>>
    private val plain: Map<Long, String>

    init {
        val raw = docs.associate { d ->
            val m = HashMap<String, Double>()
            TextTools.addFeatures(d.title, 2.5, m)   // judul lebih penting
            TextTools.addFeatures(d.tags.joinToString(" "), 2.0, m)
            TextTools.addFeatures(d.body, 1.0, m)
            d.id to m
        }
        val df = HashMap<String, Int>()
        raw.values.forEach { m -> m.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size.coerceAtLeast(1).toDouble()
        idf = df.mapValues { ln(1.0 + n / it.value) }
        maxIdf = ln(1.0 + n)
        vectors = raw.mapValues { weigh(it.value) }
        plain = docs.associate { it.id to (it.title + " " + it.tags.joinToString(" ") + " " + it.body).lowercase() }
    }

    private fun weigh(raw: Map<String, Double>): Map<String, Double> {
        val v = HashMap<String, Double>()
        var norm = 0.0
        for ((k, c) in raw) {
            val w = (1.0 + ln(1.0 + c)) * (idf[k] ?: maxIdf)
            v[k] = w
            norm += w * w
        }
        norm = sqrt(norm)
        if (norm > 0) v.replaceAll { _, w -> w / norm }
        return v
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (s, l) = if (a.size <= b.size) a to b else b to a
        var dot = 0.0
        for ((k, w) in s) l[k]?.let { dot += w * it }
        return dot
    }

    /** [extraTerms]: kata kunci tambahan (mis. hasil perluasan LLM), bobotnya lebih kecil. */
    fun search(query: String, extraTerms: List<String> = emptyList(), limit: Int = 100): List<Hit> {
        val raw = HashMap<String, Double>()
        TextTools.addFeatures(query, 1.0, raw)
        extraTerms.forEach { TextTools.addFeatures(it, 0.5, raw) }
        raw.keys.retainAll(idf.keys)      // fitur yang tak ada di catatan mana pun tak mungkin cocok
        val needle = query.trim().lowercase()
        val q = weigh(raw)
        val words = (TextTools.tokenize(query) + extraTerms.flatMap(TextTools::tokenize)).distinct()
        return vectors.mapNotNull { (id, v) ->
            val substring = needle.length >= 2 && plain[id]?.contains(needle) == true
            // Gerbang relevansi: satu trigram yang kebetulan sama ("jogging" vs "tuning") bukan kecocokan.
            if (!substring && words.none { matchesWord(it, v) }) return@mapNotNull null
            val s = (if (q.isEmpty()) 0.0 else cosine(q, v)) + (if (substring) 0.3 else 0.0)
            if (s > 0.02) Hit(id, s) else null
        }.sortedByDescending { it.score }.take(limit)
    }

    /** Kata cocok bila ada persis, atau minimal separuh trigramnya ada (imbuhan / salah ketik). */
    private fun matchesWord(w: String, doc: Map<String, Double>): Boolean {
        if (w in doc) return true
        if (w.length < 4) return false
        val n = w.length - 2
        val hit = (0 until n).count { ("#" + w.substring(it, it + 3)) in doc }
        // Minimal dua trigram: satu trigram kebetulan ("lari" vs "Januari") bukan kecocokan.
        return hit >= 2 && hit * 2 >= n
    }

    /** Catatan yang topiknya mirip dengan teks draf yang sedang disunting. */
    fun related(title: String, body: String, tags: List<String>, excludeId: Long, limit: Int = 4): List<Hit> {
        val raw = HashMap<String, Double>()
        TextTools.addFeatures(title, 2.5, raw)
        TextTools.addFeatures(tags.joinToString(" "), 2.0, raw)
        TextTools.addFeatures(body, 1.0, raw)
        raw.keys.retainAll(idf.keys)
        if (raw.isEmpty()) return emptyList()
        val q = weigh(raw)
        return vectors.asSequence().filter { it.key != excludeId }
            .map { Hit(it.key, cosine(q, it.value)) }
            .filter { it.score > 0.08 }
            .sortedByDescending { it.score }.take(limit).toList()
    }

    /** Kata kunci paling khas dari sebuah teks; dipakai sebagai saran tag saat LLM belum ada. */
    fun keywords(title: String, body: String, limit: Int = 5): List<String> {
        val counts = HashMap<String, Double>()
        TextTools.tokenize(title).forEach { counts[it] = (counts[it] ?: 0.0) + 2.5 }
        TextTools.tokenize(body).forEach { counts[it] = (counts[it] ?: 0.0) + 1.0 }
        return counts.filter { it.key.length >= 4 && it.key.any(Char::isLetter) }
            .map { it.key to (1.0 + ln(1.0 + it.value)) * (idf[it.key] ?: maxIdf) }
            .sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
