package com.catatanku.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AgentTest {
    /** Minggu, 15 November 2026, 18.30 */
    private val now = LocalDateTime.of(2026, 11, 15, 18, 30)
    private fun plan(text: String) = Agent.parse(text, now)
    private fun one(text: String) = plan(text)!!.single()
    private fun dt(mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(2026, mo, d, h, mi)

    @Test fun reminders() {
        assertEquals(AgentStep.Create("Bayar listrik", at = dt(11, 16, 19), wantsReminder = true), one("ingatkan saya bayar listrik besok jam 7 malam"))
        assertEquals(
            AgentStep.Create("Minum obat", at = dt(11, 16, 8), repeat = Repeat.Daily, wantsReminder = true),
            one("Tolong ingatkan aku untuk minum obat tiap hari jam 8 pagi ya"),
        )
        assertEquals(
            AgentStep.Create("Setor laporan", at = dt(11, 16, 7), repeat = Repeat.Weekly, wantsReminder = true),
            one("ingatkan setor laporan setiap senin jam 7 pagi"),
        )
        assertEquals(
            AgentStep.Create("Bayar kos", at = dt(12, 1, 8), repeat = Repeat.Monthly, wantsReminder = true),
            one("ingatkan bayar kos setiap tanggal 1 jam 8 pagi"),
        )
        assertEquals(AgentStep.Create("Bangun sahur", at = dt(11, 16, 5), alarm = true, wantsReminder = true), one("pasang alarm besok jam 5 pagi bangun sahur"))
        assertEquals(AgentStep.Create("Jemput ibu", at = dt(11, 15, 20, 30), wantsReminder = true), one("ingatkan 2 jam lagi jemput ibu"))
        assertEquals(AgentStep.Remind("servis motor", dt(11, 20, 9)), one("ingatkan catatan servis motor tanggal 20 jam 9 pagi"))
        assertEquals(AgentStep.Create("Servis motor", at = dt(11, 20, 9), wantsReminder = true), one("bikin pengingat buat servis motor tanggal 20 jam 9 pagi"))
        assertEquals(AgentStep.Unremind("bayar listrik"), one("hapus pengingat catatan bayar listrik"))
        // Tanpa waktu: langkahnya tetap dikenali, dan layar meminta waktunya.
        assertEquals(AgentStep.Create("Bayar pajak", wantsReminder = true), one("ingatkan saya bayar pajak"))
        // Satu kalimat menjadi beberapa objek: pengingat + daftar centang + tag + folder.
        assertEquals(
            AgentStep.Create(
                "Rapat RT", "☐ Siapkan laporan\n☐ Beli konsumsi",
                tags = listOf("rt"), folder = "Warga", at = dt(11, 16, 19), wantsReminder = true,
            ),
            one("ingatkan saya besok jam 7 malam rapat RT: siapkan laporan, beli konsumsi #rt di folder Warga"),
        )
        assertEquals(
            AgentStep.Create("Bayar listrik", "☐ Bayar listrik\n☐ Beli token 250rb", at = dt(11, 16, 19), wantsReminder = true),
            one("ingatkan besok jam 7 malam bayar listrik, beli token 250rb"),
        )
    }

    @Test fun createNotes() {
        assertEquals(AgentStep.Create("Beli susu dan roti"), one("catat beli susu dan roti"))
        assertEquals(AgentStep.Create("Belanja", "☐ Susu\n☐ Telur\n☐ Roti"), one("catat belanja: susu, telur dan roti"))
        assertEquals(AgentStep.Create("Ide lagu baru", tags = listOf("musik"), folder = "Hobi"), one("catat ide lagu baru #musik di folder Hobi"))
        assertEquals(AgentStep.Create("Ide lagu baru", tags = listOf("musik", "gitar"), folder = "Hobi"), one("buat catatan ide lagu baru di folder Hobi tag musik, gitar"))
        assertEquals(AgentStep.Create("Kata sandi wifi", "Rumah2026"), one("Catat kata sandi wifi: rumah2026"))
        val long = one("catat " + "kalimat panjang sekali ".repeat(5)) as AgentStep.Create
        assertTrue(long.title.isEmpty() && long.body.startsWith("Kalimat panjang"))
        // Waktu di catatan biasa tetap isi catatan, bukan pengingat.
        assertEquals(AgentStep.Create("Rapat vendor besok jam 10"), one("catat rapat vendor besok jam 10"))
    }

    @Test fun editNotes() {
        assertEquals(AgentStep.Append("servis motor", "beli oli", task = true), one("tambahkan tugas beli oli ke catatan servis motor"))
        assertEquals(AgentStep.Append("resep", "garam secukupnya", task = false), one("tambahkan garam secukupnya ke catatan resep"))
        assertEquals(AgentStep.Check("servis", "beli oli"), one("centang beli oli di catatan servis"))
        assertEquals(AgentStep.Tag("rapat tim", listOf("kerja", "penting")), one("beri tag kerja, penting ke catatan rapat tim"))
        assertEquals(AgentStep.Tag("resep nasi goreng", listOf("masak")), one("tandai catatan resep nasi goreng dengan tag masak"))
        assertEquals(AgentStep.Move("resep", "Masak"), one("pindahkan catatan resep ke folder Masak"))
        assertEquals(AgentStep.Pin("rute lari", true), one("sematkan catatan rute lari"))
        assertEquals(AgentStep.Pin("rute lari", false), one("lepas sematan catatan rute lari"))
        assertEquals(AgentStep.Archive("resep nasi goreng"), one("arsipkan resep nasi goreng"))
        assertEquals(AgentStep.Trash("resep nasi goreng"), one("hapus catatan resep nasi goreng"))
        assertEquals(AgentStep.Trash("rute lari pagi"), one("buang aja catatan rute lari pagi"))
        assertEquals(AgentStep.Move("ide aplikasi", "kerja"), one("pindahin catatan ide aplikasi ke folder kerja"))
    }

    @Test fun multipleSteps() {
        assertEquals(
            listOf(AgentStep.Create("Rapat vendor"), AgentStep.Remind(Agent.PREV, dt(11, 16, 10))),
            plan("catat rapat vendor, ingatkan besok jam 10"),
        )
        assertEquals(
            listOf(AgentStep.Tag("rapat tim", listOf("kerja")), AgentStep.Pin(Agent.PREV, true)),
            plan("beri tag kerja ke catatan rapat tim lalu sematkan"),
        )
        assertEquals(
            listOf(AgentStep.Create("Ide aplikasi kasir"), AgentStep.Tag(Agent.PREV, listOf("ide")), AgentStep.Pin(Agent.PREV, true)),
            plan("catat ide aplikasi kasir dan beri tag ide ke catatan itu, lalu sematkan"),
        )
        // "dan tambah …" yang bukan perintah utuh tetap bagian dari isi.
        assertEquals(listOf(AgentStep.Create("Beli gula dan tambah minyak")), plan("catat beli gula dan tambah minyak"))
        // Lebih dari tiga langkah ditolak, bukan dipotong diam-diam.
        assertNull(plan("catat a lalu sematkan lalu arsipkan lalu hapus"))
    }

    @Test fun notCommands() {
        listOf("lari", "catatan rapat", "resep nasi goreng", "kapan servis motor berikutnya?", "ingatkan", "sematkan", "hapus itu", "arsipkan catatan itu",
            "ingatkan saya bayar listrik besok?", "").forEach { assertNull(it, plan(it)) }
    }

    @Test fun modelOutput() {
        assertEquals(
            listOf(AgentStep.Create("Bayar kos", at = dt(12, 1, 8), wantsReminder = true), AgentStep.Pin(Agent.PREV, true)),
            Agent.parseModel("<think>hmm</think>1. ingatkan bayar kos tanggal 1 jam 8 pagi\n- `sematkan catatan itu`\nPenjelasan: selesai.", "bayar kos tgl 1 jam 8 pagi jangan lupa, taruh di atas", now),
        )
        // Tempat isian yang tersalin apa adanya dibuang.
        assertTrue(Agent.parseModel("tambahkan <isi catatan> ke catatan servis motor\ntambahkan TEKS ke catatan KATA KUNCI", "masukin ke servis motor", now).isEmpty())
        // "catat X" + "ingatkan X" menjadi satu catatan berpengingat, bukan dua catatan.
        assertEquals(
            listOf(AgentStep.Create("Besok ada rapat vendor jam 10"), AgentStep.Remind(Agent.PREV, dt(11, 16, 10))),
            Agent.parseModel("catat besok ada rapat vendor jam 10\ningatkan rapat vendor jam 10 besok", "besok ada rapat vendor jam 10, tolong dicatat dan diingatkan", now),
        )
        // Model kecil suka mengubah pencarian atau pertanyaan menjadi "catat …"; itu ditolak.
        assertTrue(Agent.parseModel("catat resep nasi goreng", "resep nasi goreng", now).isEmpty())
        assertTrue(Agent.parseModel("catat cara bikin nasi goreng", "gimana cara bikin nasi goreng", now).isEmpty())
        assertEquals(listOf(AgentStep.Create("Nomor rekening kos", "BCA 1234567")), Agent.parseModel("catat nomor rekening kos: BCA 1234567", "simpan nomor rekening kos: BCA 1234567", now))
        assertTrue(Agent.parseModel("-", "apa saja", now).isEmpty())
        assertTrue(Agent.parseModel("Maaf, itu sebuah pertanyaan.", "halo halo halo", now).isEmpty())
        assertTrue(Agent.plannerPrompt("x").contains("ingatkan catatan"))
    }

    @Test fun bodyEdits() {
        val body = "Servis berkala\n☐ Beli oli\n☑ Cek rem\n☐ Ganti busi"
        assertEquals("Servis berkala\n☑ Beli oli\n☑ Cek rem\n☐ Ganti busi", Agent.check(body, "beli oli"))
        assertEquals("Servis berkala\n☐ Beli oli\n☑ Cek rem\n☑ Ganti busi", Agent.check(body, "busi"))
        assertNull(Agent.check(body, "cek rem"))          // sudah dicentang
        assertNull(Agent.check(body, "cuci motor"))
        assertEquals("$body\n☐ Cuci motor", Agent.append(body + "\n\n", "cuci motor", task = true))
        assertEquals("Catatan baru", Agent.append("", "catatan baru", task = false))
    }
}
