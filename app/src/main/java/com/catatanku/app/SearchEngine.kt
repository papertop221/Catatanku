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

    /** Huruf atau angka menurut Unicode (setara kelas regex `\p{L}\p{N}`). */
    private fun isWordChar(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    /**
     * Kata-kata dalam huruf kecil, tanpa kata umum. Dipindai per karakter alih-alih dengan regex:
     * fungsi ini dipanggil untuk setiap catatan dan setiap ketikan di kolom cari.
     */
    fun tokenize(text: String): List<String> {
        val s = text.lowercase()
        val out = ArrayList<String>()
        var start = -1
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isWordChar(cp)) { if (start < 0) start = i }
            else if (start >= 0) { keep(s, start, i, out); start = -1 }
            i += Character.charCount(cp)
        }
        if (start >= 0) keep(s, start, s.length, out)
        return out
    }

    private fun keep(s: String, from: Int, to: Int, out: MutableList<String>) {
        if (to - from < 2) return
        val w = s.substring(from, to)
        if (w !in STOP) out += w
    }

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

    private val LIST_NUMBER = Regex("^\\d+[.)]\\s*")
    private val SPACES = Regex("\\s+")

    /** Mengurai jawaban LLM ("tag1, tag2\n- tag3") menjadi daftar bersih. */
    fun parseList(raw: String, max: Int = 6): List<String> =
        raw.split(',', '\n', ';', '•', '|')
            .map { it.substringAfterLast(':').trim().trimStart('-', '*', '#', ' ').replace(LIST_NUMBER, "") }
            .map { it.trim(' ', '"', '\'', '`', '.', ':', '*', '#').lowercase() }
            .filter { it.length in 2..28 && it.split(' ').size <= 3 }
            .distinct()
            .take(max)

    fun normalizeTag(s: String): String =
        s.trim().trimStart('#').lowercase().replace(SPACES, " ").take(28)
}

/**
 * Indeks pencarian di memori. Tidak bisa diubah setelah dibuat, jadi aman dibaca dari thread mana pun;
 * perubahan catatan menghasilkan indeks baru lewat [update].
 *
 * Bentuknya ringkas: tiap fitur (kata atau trigram) diberi nomor, tiap catatan menyimpan larik nomor fitur
 * dan bobotnya, dan untuk tiap fitur ada daftar catatan yang memuatnya (daftar terbalik). Dengan begitu
 * pencarian hanya menyentuh catatan yang memuat kata kuncinya, bukan seluruh catatan.
 */
class SearchIndex private constructor(docs: List<Doc>, previous: SearchIndex?) {
    constructor(docs: List<Doc>) : this(docs, null)

    data class Doc(val id: Long, val title: String, val body: String, val tags: List<String>)
    data class Hit(val id: Long, val score: Double)

    /** Satu catatan: nomor fiturnya, bobot TF (sudah 1 + ln(1 + jumlah)), dan teks huruf kecil untuk kecocokan utuh. */
    private class Entry(val doc: Doc, val feats: IntArray, val tf: FloatArray, val plain: String)

    private val dict: HashMap<String, Int>      // fitur → nomor; hanya bertambah
    private val entries: Array<Entry>           // urutan sama dengan daftar catatan
    private val idf: DoubleArray                // per nomor fitur; 0 = tidak dipakai catatan mana pun
    private val maxIdf: Double
    private val norm: DoubleArray               // panjang vektor tiap catatan
    private val postStart: IntArray             // daftar terbalik: fitur f ada di postDoc[postStart[f] until postStart[f + 1]]
    private val postDoc: IntArray
    private val postW: FloatArray
    private val live: Int                       // jumlah fitur yang masih dipakai

    /** Jumlah catatan di dalam indeks. */
    val size: Int get() = entries.size

    init {
        // Kamus lama dipakai ulang selama tidak penuh fitur usang (sisa kata yang sudah dihapus atau salah ketik
        // selagi mengetik); kalau sudah, semuanya dibangun dari awal.
        val base = previous?.takeIf { it.dict.size <= 2 * it.live + 5000 }
        val old = HashMap<Long, Entry>()
        base?.entries?.forEach { old[it.doc.id] = it }
        // Catatan yang tidak berubah memakai hasil lama. Kamus disalin hanya bila ada yang perlu dihitung ulang,
        // karena indeks lama mungkin sedang dibaca thread lain.
        val changed = base == null || docs.any { old[it.id]?.doc != it }
        dict = if (base == null) HashMap() else if (changed) HashMap(base.dict) else base.dict
        entries = Array(docs.size) { i -> val d = docs[i]; old[d.id]?.takeIf { it.doc == d } ?: entry(d) }

        val df = IntArray(dict.size)
        var total = 0
        for (e in entries) { for (f in e.feats) df[f]++; total += e.feats.size }
        val n = docs.size.coerceAtLeast(1).toDouble()
        idf = DoubleArray(df.size) { if (df[it] > 0) ln(1.0 + n / df[it]) else 0.0 }
        maxIdf = ln(1.0 + n)
        live = df.count { it > 0 }
        norm = DoubleArray(entries.size) { i ->
            val e = entries[i]
            var sum = 0.0
            for (j in e.feats.indices) { val w = e.tf[j] * idf[e.feats[j]]; sum += w * w }
            sqrt(sum)
        }
        postStart = IntArray(df.size + 1)
        for (f in df.indices) postStart[f + 1] = postStart[f] + df[f]
        postDoc = IntArray(total)
        postW = FloatArray(total)
        val next = postStart.copyOf(df.size)
        entries.forEachIndexed { i, e ->
            for (j in e.feats.indices) { val p = next[e.feats[j]]++; postDoc[p] = i; postW[p] = e.tf[j] }
        }
    }

    /** Indeks untuk daftar catatan yang baru; catatan yang tidak berubah tidak dihitung ulang. */
    fun update(docs: List<Doc>): SearchIndex = SearchIndex(docs, this)

    private fun entry(d: Doc): Entry {
        val m = HashMap<String, Double>()
        TextTools.addFeatures(d.title, 2.5, m)   // judul lebih penting
        TextTools.addFeatures(d.tags.joinToString(" "), 2.0, m)
        TextTools.addFeatures(d.body, 1.0, m)
        val feats = IntArray(m.size)
        val tf = FloatArray(m.size)
        var i = 0
        for ((k, c) in m) {
            feats[i] = dict.getOrPut(k) { dict.size }
            tf[i] = (1.0 + ln(1.0 + c)).toFloat()
            i++
        }
        return Entry(d, feats, tf, (d.title + " " + d.tags.joinToString(" ") + " " + d.body).lowercase())
    }

    private inline fun postings(feature: String, visit: (doc: Int) -> Unit) {
        val f = dict[feature] ?: return
        for (p in postStart[f] until postStart[f + 1]) visit(postDoc[p])
    }

    /**
     * Kemiripan kosinus antara vektor [raw] (fitur → jumlah berbobot) dan tiap catatan, lewat daftar terbalik.
     * Fitur yang tidak ada di catatan mana pun dibuang lebih dulu, karena tak mungkin cocok.
     * Mengembalikan null bila tidak ada fitur yang tersisa.
     */
    private fun cosines(raw: Map<String, Double>): DoubleArray? {
        val ids = IntArray(raw.size)
        val weights = DoubleArray(raw.size)
        var k = 0
        var sum = 0.0
        for ((feature, count) in raw) {
            val f = dict[feature] ?: continue
            if (idf[f] == 0.0) continue
            val w = (1.0 + ln(1.0 + count)) * idf[f]
            ids[k] = f; weights[k] = w; sum += w * w; k++
        }
        if (k == 0) return null
        val length = sqrt(sum)
        val scores = DoubleArray(entries.size)
        for (j in 0 until k) {
            val f = ids[j]
            val w = weights[j] / length * idf[f]
            for (p in postStart[f] until postStart[f + 1]) scores[postDoc[p]] += w * postW[p]
        }
        for (i in scores.indices) if (norm[i] > 0) scores[i] /= norm[i]
        return scores
    }

    /** [extraTerms]: kata kunci tambahan (mis. hasil perluasan LLM), bobotnya lebih kecil. */
    fun search(query: String, extraTerms: List<String> = emptyList(), limit: Int = 100): List<Hit> {
        if (entries.isEmpty()) return emptyList()
        val raw = HashMap<String, Double>()
        TextTools.addFeatures(query, 1.0, raw)
        extraTerms.forEach { TextTools.addFeatures(it, 0.5, raw) }
        val scores = cosines(raw)
        val needle = query.trim().lowercase()
        val words = (TextTools.tokenize(query) + extraTerms.flatMap(TextTools::tokenize)).distinct()

        // Gerbang relevansi: satu trigram yang kebetulan sama ("jogging" vs "tuning") bukan kecocokan.
        // Kata cocok bila ada persis, atau minimal separuh trigramnya ada (imbuhan / salah ketik) dan
        // sedikitnya dua: satu trigram kebetulan ("lari" vs "Januari") tidak dihitung.
        val pass = BooleanArray(entries.size)
        val count = IntArray(entries.size)
        for (w in words) {
            postings(w) { pass[it] = true }
            if (w.length < 4) continue
            val grams = w.length - 2
            count.fill(0)
            for (i in 0 until grams) postings("#" + w.substring(i, i + 3)) { count[it]++ }
            for (i in count.indices) if (count[i] >= 2 && count[i] * 2 >= grams) pass[i] = true
        }

        val hits = ArrayList<Hit>()
        for (i in entries.indices) {
            val whole = needle.length >= 2 && entries[i].plain.contains(needle)
            if (!whole && !pass[i]) continue
            val s = (scores?.get(i) ?: 0.0) + (if (whole) 0.3 else 0.0)
            if (s > 0.02) hits += Hit(entries[i].doc.id, s)
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    /** Catatan yang topiknya mirip dengan teks draf yang sedang disunting. */
    fun related(title: String, body: String, tags: List<String>, excludeId: Long, limit: Int = 4): List<Hit> {
        val raw = HashMap<String, Double>()
        TextTools.addFeatures(title, 2.5, raw)
        TextTools.addFeatures(tags.joinToString(" "), 2.0, raw)
        TextTools.addFeatures(body, 1.0, raw)
        val scores = cosines(raw) ?: return emptyList()
        val hits = ArrayList<Hit>()
        for (i in entries.indices) {
            val id = entries[i].doc.id
            if (id != excludeId && scores[i] > 0.08) hits += Hit(id, scores[i])
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    /** Kata kunci paling khas dari sebuah teks; dipakai sebagai saran tag saat LLM belum ada. */
    fun keywords(title: String, body: String, limit: Int = 5): List<String> {
        val counts = HashMap<String, Double>()
        TextTools.tokenize(title).forEach { counts[it] = (counts[it] ?: 0.0) + 2.5 }
        TextTools.tokenize(body).forEach { counts[it] = (counts[it] ?: 0.0) + 1.0 }
        return counts.filter { it.key.length >= 4 && it.key.any(Char::isLetter) }
            .map { (word, c) -> word to (1.0 + ln(1.0 + c)) * (dict[word]?.let { idf[it] }?.takeIf { it > 0 } ?: maxIdf) }
            .sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
