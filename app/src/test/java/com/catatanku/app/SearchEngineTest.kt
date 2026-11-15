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
        assertEquals("gemma4-e2b", ModelCatalog.recommended(8).id)
        assertEquals("qwen25-15b", ModelCatalog.recommended(6).id)
        assertEquals("qwen25-15b", ModelCatalog.recommended(3).id)
        assertEquals("gemma4-e4b", ModelCatalog.recommended(12).id)
        ModelCatalog.all.forEach {
            assertTrue(it.url.startsWith("https://huggingface.co/") && it.url.contains("/resolve/") && it.sha256.length == 64)
            assertTrue(it.fileName.endsWith(".litertlm") && it.minRamGb <= it.recRamGb)
        }
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
    }

    @Test fun wordCount() = assertEquals(3, AiText.wordCount("  satu dua\n tiga "))
}
