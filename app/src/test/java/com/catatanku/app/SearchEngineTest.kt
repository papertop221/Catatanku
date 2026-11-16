package com.catatanku.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    private val docs = listOf(
        SearchIndex.Doc(1, "Jadwal latihan", "Minggu ini berlari 5 km tiap pagi, lalu peregangan.", listOf("olahraga")),
        SearchIndex.Doc(2, "Resep nasi goreng", "Bawang putih, kecap manis, telur, nasi sisa semalam.", listOf("masak")),
        SearchIndex.Doc(3, "Tuning kernel Android", "Atur governor schedutil dan zram untuk performa.", listOf("android")),
        SearchIndex.Doc(4, "Rencana lari maraton", "Target pelari pemula: tingkatkan jarak lari bertahap.", listOf("olahraga")),
        SearchIndex.Doc(5, "Belanja bulanan", "Beras, telur, minyak goreng, sabun.", emptyList()),
    )
    private val idx = SearchIndex(docs)

    @Test fun exactWord() = assertEquals(3L, idx.search("governor").first().id)

    @Test fun morphology() {
        // "lari" harus menemukan catatan yang hanya memuat "berlari"
        val ids = idx.search("lari").map { it.id }
        assertTrue(ids.toString(), 1L in ids && 4L in ids)
        assertEquals(4L, ids.first())
    }

    @Test fun typoTolerance() = assertEquals(3L, idx.search("andorid kernal").first().id)

    @Test fun noFalsePositive() {
        assertTrue(idx.search("xyzzyq").isEmpty())
        // "lari" tidak boleh cocok dengan "Januari" hanya karena berbagi satu trigram
        val i = SearchIndex(docs + SearchIndex.Doc(9, "Servis motor", "Servis berikutnya awal Januari.", emptyList()))
        assertTrue(i.search("lari").none { it.id == 9L })
    }

    @Test fun extraTermsExpand() {
        assertTrue(idx.search("jogging").isEmpty())
        assertEquals(setOf(1L, 4L), idx.search("jogging", listOf("lari", "olahraga")).map { it.id }.toSet())
    }

    @Test fun related() {
        val r = idx.related("Rencana lari maraton", "Target pelari pemula", listOf("olahraga"), excludeId = 4)
        assertEquals(1L, r.first().id)
        assertTrue(r.none { it.id == 4L || it.id == 3L })
    }

    @Test fun keywords() {
        val k = idx.keywords("Tuning kernel Android", "Atur governor schedutil dan zram untuk performa.")
        assertTrue(k.toString(), "kernel" in k && "dan" !in k)
    }

    @Test fun parseLlmList() {
        assertEquals(listOf("olahraga", "lari pagi", "kesehatan"),
            TextTools.parseList("Tag: \n- Olahraga\n- #lari pagi\n3. **Kesehatan**."))
        assertEquals(listOf("android", "kernel"), TextTools.parseList("android, kernel, android"))
    }

    @Test fun emptyIndex() = assertTrue(SearchIndex(emptyList()).search("apa saja").isEmpty())
}

class AiTextTest {
    private val folders = listOf("Kerja", "Pribadi", "Resep")

    @Test fun organizeWellFormed() {
        // Prompt berakhir dengan "JUDUL:", jadi jawaban model langsung dimulai dari judulnya.
        val o = AiText.parseOrganize(" Nasi Goreng Kampung\nFOLDER: resep\nTAG: masak, nasi goreng, Makan Malam", folders)
        assertEquals("Nasi Goreng Kampung", o.title)
        assertEquals("Resep", o.folder)
        assertEquals(listOf("masak", "nasi goreng", "makan malam"), o.tags)
    }

    @Test fun organizeMessy() {
        val o = AiText.parseOrganize("**JUDUL:** \"Rapat Mingguan\".\n- Folder: Keuangan\n- Tags: #rapat, kerja", folders)
        assertEquals("Rapat Mingguan", o.title)
        assertEquals("", o.folder)                     // folder karangan model ditolak
        assertEquals(listOf("rapat", "kerja"), o.tags)
    }

    @Test fun organizeGarbage() {
        val o = AiText.parseOrganize("", folders)
        assertEquals(AiText.Organized("", "", emptyList()), o)
    }

    @Test fun cleanTitle() = assertEquals("Rencana Liburan", AiText.cleanLine("Judul: \"Rencana Liburan.\"\nPenjelasan lain"))

    @Test fun togglePrefix() {
        val text = "satu\ndua\ntiga"
        val (a, ca) = AiText.toggleLinePrefix(text, 6, checklist = true)        // kursor di "dua"
        assertEquals("satu\n☐ dua\ntiga", a); assertEquals(8, ca)
        val (b, _) = AiText.toggleLinePrefix(a, ca, checklist = true)
        assertEquals("satu\n☑ dua\ntiga", b)
        val (c, cc) = AiText.toggleLinePrefix(b, 8, checklist = true)
        assertEquals(text, c); assertEquals(6, cc)
        assertEquals("• x", AiText.toggleLinePrefix("☐ x", 3, checklist = false).first)
        assertEquals("☐ ", AiText.toggleLinePrefix("", 0, checklist = true).first)
    }

    @Test fun promptsRespectContextBudget() {
        val long = "kata ".repeat(5000)
        AiText.maxInput = 2800
        AiAction.entries.forEach { assertTrue(it.name, AiText.prompt(it, "Judul", long, "instruksi").length < 3600) }
        assertTrue(AiText.organizePrompt("", long, folders, listOf("a")).length < 2600)
        assertTrue(AiText.askPrompt("apa?", List(4) { "Judul" to long }).length < 3200)
    }

    @Test fun markdownBecomesPlainText() {
        val raw = "<think>hmm</think>**Rapat Tim**\n\n*   **Topik:** peluncuran\n    *   Aku: perbaiki *bug* login\n## Catatan\n- 2 * 3 = 6"
        assertEquals("Rapat Tim\n\n• Topik: peluncuran\n   • Aku: perbaiki bug login\nCatatan\n• 2 * 3 = 6", AiText.cleanBlock(raw))
        assertEquals("", AiText.strip("<think>belum selesai"))
    }

    @Test fun catalogIsConsistent() {
        // Gemma 4 E2B paling hemat RAM pada pengukuran, jadi disarankan untuk semua ukuran RAM.
        listOf(3, 6, 8, 12).forEach { assertEquals("gemma4-e2b", ModelCatalog.recommended(it).id) }
        ModelCatalog.all.forEach {
            assertTrue(it.url.startsWith("https://huggingface.co/") && it.url.contains("/resolve/") && it.sha256.length == 64)
            assertTrue(it.fileName.endsWith(".litertlm") && it.minRamGb <= it.recRamGb && it.tier.isNotBlank())
        }
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.toSet().size)
        assertTrue(ModelCatalog.all.first { it.id == "qwen25-15b" }.legacy)      // tetap dikenali bagi yang sudah mengunduh
    }

    @Test fun chunksRebuildTheOriginal() {
        val text = (1..60).joinToString("\n\n") { "Paragraf $it. " + "Kalimat pengisi yang cukup panjang. ".repeat(6) }
        val parts = AiText.chunks(text, 1000, limit = 100)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1000 } && parts.size > 5)
        assertTrue("potong di batas paragraf", parts.dropLast(1).all { it.endsWith("\n\n") })
        assertEquals(listOf("pendek"), AiText.chunks("pendek", 1000))
        assertEquals(3, AiText.chunks("x".repeat(5000), 1000, limit = 3).size)                // batas jumlah bagian
        assertEquals("a".repeat(950) + " ", AiText.chunks("a".repeat(950) + " " + "b".repeat(500), 1000).first())
    }

    @Test fun partPromptsStayWithinBudget() {
        AiText.maxInput = 2800
        val parts = AiText.chunks("kata ".repeat(5000))
        assertEquals(AiText.MAX_PARTS, parts.size)
        listOf(AiAction.Summarize, AiAction.Tasks, AiAction.Fix).forEach { a ->
            assertTrue(a.wholeNote)
            parts.forEachIndexed { i, p -> assertTrue(AiText.partPrompt(a, "Judul", p, i, parts.size).length < 3600) }
        }
        assertTrue(AiText.mergeSummaryPrompt("Judul", "• poin\n".repeat(2000)).length < 3400)
        assertTrue(AiText.recapPrompt(List(8) { "Judul" to "kata ".repeat(5000) }).length < 3600)
        assertTrue(AiText.reminderPrompt("Judul", "kata ".repeat(5000)).length < 3400)
    }

    @Test fun tasksAreMergedWithoutDuplicates() {
        assertEquals("☐ Beli oli\n☐ Bayar pajak", AiText.mergeTasks(listOf("☐ Beli oli\nTidak ada tugas.", "Tidak ada tugas.", "☐ beli oli\n☐ Bayar pajak")))
        assertEquals("Tidak ada tugas.", AiText.mergeTasks(listOf("Tidak ada tugas.", "")))
        assertEquals(listOf("☐ Rina siapkan materi"), AiText.openTasks("☑ selesai\n  ☐ Rina siapkan materi\n☐\nteks biasa"))
    }

    @Test fun bestWindowFindsTheRelevantPassage() {
        val body = "Pembuka yang tidak penting. ".repeat(80) + "\nServis motor berikutnya awal Januari di bengkel Jaya.\n" + "Penutup lain. ".repeat(80)
        val w = AiText.bestWindow(body, "kapan servis motor berikutnya?", 400)
        assertTrue(w, w.contains("awal Januari") && w.length <= 410)
        assertEquals("pendek", AiText.bestWindow("pendek", "apa saja", 400))
        assertEquals(body.take(400), AiText.bestWindow(body, "zzzz", 400))                       // tak ada kecocokan: bagian awal
        AiText.maxInput = 2800
        assertTrue(AiText.askPrompt("kapan servis motor berikutnya?", listOf("Motor" to body)).contains("awal Januari"))
    }

    @Test fun reminderAnswerIsParsed() {
        assertEquals("tanggal 24 November jam 10.00", AiText.parseReminder(" tanggal 24 November jam 10.00\nHAL: rapat tim"))
        assertEquals("besok jam 19.00", AiText.parseReminder("**WAKTU:** \"besok jam 19.00\"\nHAL: jemput ibu"))
        assertEquals(null, AiText.parseReminder("WAKTU: -"))
        assertEquals(null, AiText.parseReminder("-"))
        assertEquals(null, AiText.parseReminder("<think>mungkin besok</think>"))
    }

    @Test fun wordCount() = assertEquals(3, AiText.wordCount("  satu dua\n tiga "))

    @Test fun tapCheckboxDirectlyAndContinueListOnEnter() {
        val src = "Judul bagian\n☐ Beli beras\n  ☑ Cuci motor\nBiasa"
        // Ketuk tepat di kotak ☐ pada baris kedua (indeks 13 atau 14) -> berubah jadi ☑ tanpa menghapus kotak.
        val (checked, cur1) = AiText.toggleCheckAt(src, 13)!!
        assertEquals("Judul bagian\n☑ Beli beras\n  ☑ Cuci motor\nBiasa", checked)
        assertEquals(15, cur1)
        // Ketuk lagi di kotak ☑ yang sama -> kembali jadi ☐.
        val (unchecked, _) = AiText.toggleCheckAt(checked, 14)!!
        assertEquals(src, unchecked)
        // Ketuk pada kotak berindentasi "  ☑" -> jadi "  ☐".
        val (unindent, _) = AiText.toggleCheckAt(src, 28)!!
        assertEquals("Judul bagian\n☐ Beli beras\n  ☐ Cuci motor\nBiasa", unindent)
        // Ketuk di tengah teks tugas bukan ketukan kotak centang.
        assertEquals(null, AiText.toggleCheckAt(src, 18))

        // Tekan Enter setelah "☐ Beli beras" -> baris baru otomatis "☐ ".
        val before = "☐ Beli beras"
        val after = "☐ Beli beras\n"
        val (cont, cCont) = AiText.continueListOnEnter(before, after, after.length)!!
        assertEquals("☐ Beli beras\n☐ ", cont)
        assertEquals(cont.length, cCont)

        // Tekan Enter pada baris "☐ " kosong -> keluar dari mode daftar (awalan dihapus).
        val (exit, cExit) = AiText.continueListOnEnter(cont, cont + "\n", cont.length + 1)!!
        assertEquals("☐ Beli beras\n", exit)
        assertEquals(exit.length, cExit)

        // Centang/buka centang tugas dari daftar tugas layar utama.
        val toggled = AiText.setTaskChecked(src, "☐ Beli beras", checked = true)!!
        assertEquals("Judul bagian\n☑ Beli beras\n  ☑ Cuci motor\nBiasa", toggled)
        assertEquals(src, AiText.setTaskChecked(toggled, "☐ Beli beras", checked = false))

        assertTrue(AiText.imagePrompt(true).contains("☐"))
        assertTrue(AiText.audioPrompt(false).contains("Transkripsikan"))
    }
}
