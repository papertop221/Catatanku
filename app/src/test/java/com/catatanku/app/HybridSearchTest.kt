package com.catatanku.app

import com.catatanku.app.domain.usecase.EmbeddingCodec
import com.catatanku.app.domain.usecase.HybridSearch
import com.catatanku.app.domain.usecase.SearchIndex
import com.catatanku.app.domain.usecase.SemanticIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridSearchTest {
    private val docs = listOf(
        SearchIndex.Doc(1L, "Tagihan listrik rumah", "Bayar token PLN prabayar 200 ribu sebelum tanggal 20.", listOf("rumah", "tagihan")),
        SearchIndex.Doc(2L, "Servis sepeda motor", "Ganti oli mesin, periksa rem, dan cek tekanan ban di bengkel.", listOf("kendaraan")),
        SearchIndex.Doc(3L, "Belanja kebutuhan dapur", "Beli beras 5 kg, bawang merah, kecap manis, minyak goreng, dan telur.", listOf("masak")),
        SearchIndex.Doc(4L, "Pembayaran BPJS kesehatan", "Iuran bulanan BPJS ditransfer lewat mobile banking tiap awal bulan.", listOf("keuangan")),
        SearchIndex.Doc(5L, "Jadwal kontrol dokter", "Konsultasi pemeriksaan kesehatan dan cek darah hari Rabu pagi.", listOf("kesehatan")),
        SearchIndex.Doc(6L, "Rencana lari maraton", "Latihan lari pagi 5 km empat kali seminggu menjelang lomba.", listOf("olahraga")),
    )
    private val tfidf = SearchIndex(docs)

    @Test fun embeddingCodecRoundtripAndNormalization() {
        val raw = floatArrayOf(3f, 4f, 0f)
        val norm = EmbeddingCodec.normalize(raw)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f, 0f), norm, 1e-5f)

        val encoded = EmbeddingCodec.encode(norm)
        assertEquals(12, encoded.size)
        assertArrayEquals(norm, EmbeddingCodec.decode(encoded), 1e-6f)
        assertEquals(1.0, EmbeddingCodec.cosineNormalized(norm, norm), 1e-6)
        assertEquals(0.0, EmbeddingCodec.cosineNormalized(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6)

        val h1 = EmbeddingCodec.contentHash("A", "isi", listOf("t1"))
        val h2 = EmbeddingCodec.contentHash("A", "isi diubah", listOf("t1"))
        assertNotEquals(h1, h2)
    }

    @Test fun fallsBackCleanlyToTfIdfWhenSemanticUnavailable() {
        val q = "token pln"
        val baseline = tfidf.search(q)
        assertEquals(baseline, HybridSearch.search(tfidf, null, q, null))
        assertEquals(baseline, HybridSearch.search(tfidf, SemanticIndex(), q, floatArrayOf(1f, 0f)))
    }

    @Test fun hybridSearchFindsIndonesianParaphrasesWhileKeepingExactPrecision() {
        // Basis 4 konsep semantik: [tagihan/keuangan, kendaraan, masak/dapur, kesehatan/olahraga]
        val semantic = SemanticIndex(
            vectors = mapOf(
                1L to EmbeddingCodec.normalize(floatArrayOf(0.92f, 0.05f, 0.02f, 0.01f)),
                2L to EmbeddingCodec.normalize(floatArrayOf(0.03f, 0.96f, 0.01f, 0.02f)),
                3L to EmbeddingCodec.normalize(floatArrayOf(0.04f, 0.01f, 0.95f, 0.02f)),
                4L to EmbeddingCodec.normalize(floatArrayOf(0.95f, 0.02f, 0.01f, 0.40f)),
                5L to EmbeddingCodec.normalize(floatArrayOf(0.02f, 0.01f, 0.02f, 0.96f)),
                6L to EmbeddingCodec.normalize(floatArrayOf(0.01f, 0.02f, 0.01f, 0.88f)),
            ),
            modelId = "test-emb",
        )

        // Kueri parafrasa tanpa kecocokan kata atau akar kata persis: "vespa matic mogok" -> catatan #2
        val qVehicleVec = EmbeddingCodec.normalize(floatArrayOf(0.02f, 0.94f, 0.01f, 0.02f))
        assertTrue("TF-IDF murni tidak menemukan sinonim tanpa tumpang tindih leksikal", tfidf.search("vespa matic mogok").isEmpty())
        val vehicleHits = HybridSearch.search(tfidf, semantic, "vespa matic mogok", qVehicleVec)
        assertEquals(2L, vehicleHits.first().id)

        // Kueri parafrasa dapur tanpa kata atau akar kata tumpang tindih: "kulkas kosong menu sup" -> catatan #3
        val qKitchenVec = EmbeddingCodec.normalize(floatArrayOf(0.03f, 0.01f, 0.97f, 0.02f))
        assertTrue(tfidf.search("kulkas kosong menu sup").isEmpty())
        val kitchenHits = HybridSearch.search(tfidf, semantic, "kulkas kosong menu sup", qKitchenVec)
        assertEquals(3L, kitchenHits.first().id)

        // Kueri persis "token PLN" tetap menempatkan catatan #1 di peringkat #1 meski #4 sama-sama topik tagihan.
        val qElectricityVec = EmbeddingCodec.normalize(floatArrayOf(0.93f, 0.01f, 0.01f, 0.05f))
        val exactHits = HybridSearch.search(tfidf, semantic, "token PLN", qElectricityVec)
        assertEquals(1L, exactHits.first().id)
    }
}
