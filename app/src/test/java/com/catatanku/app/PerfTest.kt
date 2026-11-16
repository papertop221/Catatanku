package com.catatanku.app

import com.catatanku.app.domain.usecase.SearchIndex
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random
import kotlin.system.measureNanoTime

/**
 * Pengukuran kasar mesin pencarian dengan catatan sintetis. Tidak ikut uji biasa; jalankan dengan:
 *   ./gradlew testDebugUnitTest --tests '*PerfTest*' -Pperf=true -i
 * Angkanya dari JVM desktop: HP kelas menengah kira-kira 3–6× lebih lambat. Gunanya membandingkan sebelum/sesudah.
 */
class PerfTest {
    private val syll = listOf(
        "ba", "bi", "bu", "be", "bo", "ca", "ci", "cu", "da", "di", "du", "de", "ga", "gi", "gu", "ha", "hi", "ja", "ju",
        "ka", "ki", "ku", "ke", "ko", "la", "li", "lu", "le", "ma", "mi", "mu", "me", "na", "ni", "nu", "nga", "nya",
        "pa", "pi", "pu", "pe", "ra", "ri", "ru", "re", "sa", "si", "su", "se", "ta", "ti", "tu", "te", "wa", "ya",
        "an", "ang", "ar", "as", "at", "in", "ing", "ir", "is", "un", "ung", "ur", "us",
    )

    private fun vocab(rnd: Random, n: Int) = List(n) { (1..(2 + rnd.nextInt(3))).joinToString("") { syll[rnd.nextInt(syll.size)] } }

    /** Kata dipilih condong ke yang sering (mirip bahasa sungguhan), agar IDF dan trigram berperilaku wajar. */
    private fun text(rnd: Random, words: List<String>, count: Int) = buildString {
        repeat(count) { i ->
            val r = rnd.nextDouble()
            append(words[(r * r * r * words.size).toInt()])
            append(if (i % 14 == 13) ".\n" else " ")
        }
    }

    private fun docs(n: Int, seed: Long = 7): List<SearchIndex.Doc> {
        val rnd = Random(seed)
        val words = vocab(rnd, 4000)
        return List(n) { i ->
            SearchIndex.Doc(
                i + 1L, text(rnd, words, 2 + rnd.nextInt(4)).trim(), text(rnd, words, 40 + rnd.nextInt(260)),
                List(rnd.nextInt(3)) { words[rnd.nextInt(60)] },
            )
        }
    }

    private fun ms(block: () -> Unit): Double { val t = measureNanoTime(block); return t / 1e6 }
    private fun median(runs: Int, block: () -> Unit): Double = List(runs) { ms(block) }.sorted()[runs / 2]

    @Test fun measure() {
        assumeTrue(System.getProperty("perf") == "true")
        for (n in listOf(300, 1000, 3000)) {
            val d = docs(n)
            val chars = d.sumOf { it.title.length + it.body.length }
            repeat(2) { SearchIndex(d) }                                   // pemanasan JIT
            val build = median(5) { SearchIndex(d) }
            val idx = SearchIndex(d)
            val q = d[n / 2].body.split(' ').filter { it.length >= 4 }.take(2).joinToString(" ")
            repeat(20) { idx.search(q) }
            val search = median(31) { idx.search(q) }
            val related = median(15) { idx.related(d[3].title, d[3].body, d[3].tags, d[3].id) }
            // Satu catatan berubah (simpan otomatis): versi lama membangun ulang semuanya.
            val changed = d.toMutableList().also { it[n / 3] = it[n / 3].copy(body = it[n / 3].body + " tambahan kata baru") }
            val update = median(5) { PerfHook.update(idx, changed) }
            println("PERF n=%d teks=%.1f MB | bangun=%.0f ms | satu catatan berubah=%.1f ms | cari=%.2f ms | terkait=%.2f ms"
                .format(n, chars / 1e6, build, update, search, related))
        }
    }
}

/** Titik sambung agar uji yang sama bisa mengukur versi lama (bangun ulang penuh) dan versi baru (inkremental). */
object PerfHook {
    fun update(old: SearchIndex, docs: List<SearchIndex.Doc>): SearchIndex = old.update(docs)
}
