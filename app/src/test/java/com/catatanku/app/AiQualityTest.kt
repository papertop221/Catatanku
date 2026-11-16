package com.catatanku.app

import com.catatanku.app.ai.inference.Agent
import com.catatanku.app.ai.inference.AiText
import com.catatanku.app.ai.performance.ContextProfile
import com.catatanku.app.domain.model.AgentStep
import com.catatanku.app.domain.model.AiAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Uji kualitas alur AI bahasa Indonesia: prompt anti-halusinasi, pembersihan keluaran polos,
 * konsistensi anggaran konteks 2048/3072/4096, serta akurasi ekstraksi perintah & tugas lokal.
 */
class AiQualityTest {
    private val now = LocalDateTime.of(2026, 11, 16, 10, 0)

    @Test fun indonesianPromptsEnforceGroundingAndPlainText() {
        val sample = "Rapat evaluasi vendor printer kasir di ruang lantai 2. Rina kirim penawaran sebelum Jumat."
        AiAction.entries.forEach { action ->
            val p = AiText.prompt(action, "Rapat Vendor", sample, "buat poin singkat")
            assertTrue(action.name, p.isNotBlank())
            if (action != AiAction.Custom && action != AiAction.Tasks) {
                assertTrue(action.name, p.contains("teks polos", ignoreCase = true) || p.contains("tanpa tanda cuti", ignoreCase = true) || p.contains("tanpa tanda kutip", ignoreCase = true) || p.contains("tanpa tanda *", ignoreCase = true))
            }
        }
        assertTrue(AiText.prompt(AiAction.Tidy, "Catatan", sample).contains("Jangan menambah informasi"))
        assertTrue(AiText.mergeSummaryPrompt("Catatan", "• Poin 1").contains("Jangan menambah informasi baru"))
        assertTrue(AiText.recapPrompt(listOf("Catatan" to sample)).contains("Pakai hanya isi catatan; jangan mengarang"))
    }

    @Test fun plainTextSanitizationPreservesIndonesianChecklistsAndBullets() {
        val messy = """
            <think>analisis internal</think>
            **Ringkasan Rapat**

            * **Keputusan:** Anggaran *disetujui* 5 juta
            - Hubungi vendor mesin kasir
            ## Tindak Lanjut
            1. Buat jadwal uji coba
        """.trimIndent()
        val cleaned = AiText.cleanBlock(messy)
        assertFalse(cleaned.contains("<think>"))
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("##"))
        assertTrue(cleaned.contains("• Keputusan: Anggaran disetujui 5 juta"))
        assertTrue(cleaned.contains("• Hubungi vendor mesin kasir"))
        assertTrue(cleaned.contains("Tindak Lanjut"))
    }

    @Test fun contextProfilesBoundPromptLengths() {
        val hugeBody = ("Catatan rapat teknis proyek Catatanku. ").repeat(400)
        ContextProfile.entries.forEach { profile ->
            AiText.maxInput = profile.maxInputChars
            val pSum = AiText.prompt(AiAction.Summarize, "Rapat Besar", hugeBody)
            val pAsk = AiText.askPrompt("Apa keputusan rapat?", listOf("Rapat Besar" to hugeBody))
            assertTrue(profile.id, pSum.length <= profile.maxInputChars + 600)
            assertTrue(profile.id, pAsk.length <= profile.maxInputChars + 900)
        }
        AiText.maxInput = ContextProfile.HEMAT.maxInputChars
    }

    @Test fun indonesianColloquialCommandsAndTasksAreParsedConsistently() {
        val c1 = Agent.parse("tolong ingatkan aku nanti sore buat bayar iuran air sama perpanjang STNK", now)!!.single() as AgentStep.Create
        assertTrue(c1.wantsReminder)
        assertEquals(LocalDateTime.of(2026, 11, 16, 16, 0), c1.at)

        val direct = AiText.extractDirectTasksAndTime("Ucapan: \"besok pagi jam 8 kirim invoice ke klien dan beli tinta\"", now)!!
        assertEquals(LocalDateTime.of(2026, 11, 17, 8, 0), direct.remindAt)
        assertEquals(listOf("☐ Kirim invoice ke klien", "☐ Beli tinta"), direct.tasks)

        assertNull(AiText.parseReminder("WAKTU: -\nHAL: -"))
        assertEquals("besok jam 09.00", AiText.parseReminder("WAKTU: besok jam 09.00\nHAL: servis motor"))
    }
}
