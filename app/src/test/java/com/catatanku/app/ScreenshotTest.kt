package com.catatanku.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Merender tiap layar ke PNG (app/build/shots) agar desain bisa ditinjau tanpa perangkat:
 *   ./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -Pshots=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class Shots(private val suffix: String) {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val vm get() = rule.activity.vm

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/$name-$suffix.png")
    }

    private fun seed() = rule.runOnUiThread {
        fun add(n: Note) { vm.open(n); vm.closeEditor() }
        add(Note(title = "Rapat tim Senin", body = "Bahas peluncuran aplikasi kasir bulan depan.\n☑ Perbaiki bug login sebelum Jumat\n☐ Rina siapkan materi promosi\n☐ Dodi hubungi vendor printer struk\n\nAnggaran iklan disetujui 5 juta. Rapat berikutnya tanggal 24 jam 10 pagi di ruang lantai 2.", tags = listOf("kerja", "rapat"), folder = "Kerja", pinned = true))
        val hour = 3600_000L
        add(Note(title = "Servis motor", body = "Ganti oli terakhir 3 Oktober di bengkel Jaya Motor, km 24.500. Servis berikutnya di km 27.000 atau awal Januari.", tags = listOf("kendaraan"), folder = "Pribadi",
            remindAt = System.currentTimeMillis() + 26 * hour, remindRepeat = Repeat.Monthly))
        add(Note(title = "Bayar listrik", body = "Token 200 ribu sebelum malam.", tags = listOf("rumah"),
            remindAt = System.currentTimeMillis() - 3 * hour, remindFired = true, remindAlarm = true))
        add(Note(title = "Resep nasi goreng", body = "Bawang putih 3 siung, kecap manis 2 sdm, telur 2, nasi sisa semalam. Tumis bawang dulu sampai harum.", tags = listOf("masak")))
        add(Note(title = "", body = "Ide: aplikasi catatan offline dengan AI lokal yang ringan dan cepat."))
        add(Note(title = "Rute lari pagi", body = "Simpang Lima – Pleburan – Tugu Muda, 5 km. Target pace 6:30.", tags = listOf("olahraga"), folder = "Pribadi"))
    }

    /** Menaruh berkas model tiruan agar layar "AI aktif" bisa dirender (model tidak pernah dimuat). */
    private fun fakeModel() {
        val f = java.io.File(rule.activity.filesDir, "models/model.litertlm").apply { parentFile!!.mkdirs(); writeText("x") }
        kotlinx.coroutines.runBlocking { vm.ai.activate(ModelCatalog.IMPORT_ID) }
        check(f.exists() && vm.ai.hasModel)
    }

    @Test fun screens() {
        shot("01-kosong")
        seed()
        rule.waitUntil(10_000) { vm.notes.size >= 6 }      // muat ulang dari DB berjalan asinkron
        Thread.sleep(300); rule.waitForIdle()
        check(vm.notes.size == 6) { "Harus tepat 6 catatan, ternyata ${vm.notes.size}" }      // tidak ada duplikat
        check(vm.reminderCount == 2)
        shot("02-daftar")
        rule.runOnUiThread { vm.open(null) }
        shot("03-editor-baru")
        rule.runOnUiThread { vm.closeEditor(); vm.go(Screen.Settings) }
        shot("04-pengaturan")
        rule.runOnUiThread { vm.go(Screen.List) }
        fakeModel()
        rule.runOnUiThread { vm.updateQuery("lari") }
        shot("05-cari")
        rule.runOnUiThread { vm.updateQuery(""); vm.open(vm.notes.first { it.title == "Rapat tim Senin" }) }
        shot("06-editor")
        rule.runOnUiThread { vm.aiSheet = true }
        shot("07-menu-ai")
        rule.runOnUiThread { vm.debugAiJob(AiJob(AiAction.Summarize, "• Rapat tim membahas peluncuran aplikasi kasir bulan depan.\n• Tiga tugas dibagi ke anggota tim.\n• Anggaran iklan 5 juta disetujui.\n• Rapat berikutnya tanggal 24 pukul 10 pagi.", running = false)) }
        shot("08-hasil-ai")
        rule.runOnUiThread { vm.dismissAi(); vm.closeEditor(); vm.openAsk() }
        shot("09-tanya")
        rule.runOnUiThread { vm.closeAsk(); vm.go(Screen.Settings) }
        shot("10-pengaturan-aktif")

        // ---- 1.4: pengingat, riwayat versi, rekap, kunci ----
        rule.runOnUiThread { vm.go(Screen.List); vm.view = NoteView.Reminders }
        shot("11-tampilan-pengingat")
        rule.runOnUiThread { vm.view = NoteView.All; vm.open(vm.notes.first { it.title == "Rapat tim Senin" }) }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()      // saran pengingat muncul setelah jeda singkat
        val hint = vm.reminderHint()
        check(hint != null && hint.hasTime) { "Waktu di catatan rapat harus dikenali: $hint" }
        shot("12-editor-saran-pengingat")
        rule.runOnUiThread { vm.openReminder(seed = hint!!.at.toMillis()) }
        shot("13-lembar-pengingat")
        rule.runOnUiThread { vm.saveReminder(hint!!.at.toMillis(), Repeat.None, alarm = true) }
        check(vm.draft.hasReminder && vm.draft.remindAlarm)
        shot("14-editor-berpengingat")
        // Mengubah isi lalu menutup menyimpan keadaan lama ke riwayat versi.
        rule.runOnUiThread { vm.edit { it.copy(body = it.body + "\nTambahan setelah rapat.") }; vm.closeEditor() }
        rule.waitUntil(10_000) { vm.notes.any { it.body.endsWith("Tambahan setelah rapat.") } }
        rule.runOnUiThread { vm.open(vm.notes.first { it.title == "Rapat tim Senin" }); vm.openVersions() }
        check(vm.versions?.size == 1) { "Harus ada 1 versi lama, ternyata ${vm.versions?.size}" }
        shot("15-riwayat-versi")
        rule.runOnUiThread { vm.restoreVersion(vm.versions!!.first()) }
        check(!vm.draft.body.contains("Tambahan")) { "Versi lama harus dipulihkan" }
        rule.runOnUiThread { vm.closeEditor(); vm.openAsk(); vm.askOpenTasks() }
        check(vm.askSources.size == 1 && vm.askAnswer.contains("☐ Rina"))
        shot("16-tugas-belum-selesai")
        rule.runOnUiThread { vm.closeAsk() }

        // ---- 1.5: asisten perintah (tanpa model: fakeModel hanya berkas tiruan, aturan yang bekerja) ----
        rule.runOnUiThread { vm.updateQuery("ingatkan saya bayar pajak motor besok jam 7 malam") }
        shot("18-perintah-di-kolom-cari")
        rule.runOnUiThread { vm.startAgent(vm.query) }
        check(vm.agentReady) { "Rencana harus siap: ${vm.agent}" }
        shot("19-rencana-asisten")
        val count = vm.notes.size
        rule.runOnUiThread { vm.runAgent() }
        rule.waitUntil(10_000) { vm.agent?.results != null }
        val made = vm.notes.single { it.title == "Bayar pajak motor" }
        check(vm.notes.size == count + 1 && made.hasReminder && made.remindAt.toLocal().hour == 19) { "Catatan berpengingat harus dibuat: $made" }
        check(vm.query.isEmpty())
        shot("20-hasil-asisten")
        rule.runOnUiThread { vm.undoAgent() }
        rule.waitUntil(10_000) { vm.agent == null && vm.notes.size == count }
        check(vm.notes.none { it.title == "Bayar pajak motor" }) { "Urungkan harus menghapus catatan yang dibuat" }

        // Tiga langkah atas catatan yang sudah ada; sasaran dicari, isi lama masuk riwayat versi, dan semuanya bisa diurungkan.
        val motor = vm.notes.first { it.title == "Servis motor" }
        rule.runOnUiThread { vm.startAgent("tambahkan tugas beli oli ke catatan servis motor, beri tag penting ke catatan itu, lalu sematkan") }
        check(vm.agentReady && vm.agent!!.items.size == 3 && vm.agent!!.items[0].target?.id == motor.id) { "Rencana 3 langkah: ${vm.agent}" }
        shot("21-rencana-tiga-langkah")
        rule.runOnUiThread { vm.runAgent() }
        rule.waitUntil(10_000) { vm.agent?.results != null }
        val after = vm.notes.first { it.id == motor.id }
        check(after.body.endsWith("\n☐ Beli oli") && "penting" in after.tags && after.pinned && after.hasReminder) { "Tiga langkah harus diterapkan: $after" }
        check(NoteDb.get(rule.activity).versions(motor.id).any { it.body == motor.body }) { "Isi lama harus masuk riwayat versi" }
        rule.runOnUiThread { vm.startAgent("centang beli oli di catatan servis motor") }
        check(vm.agentReady)
        rule.runOnUiThread { vm.runAgent() }
        rule.waitUntil(10_000) { vm.agent?.results != null && vm.notes.first { it.id == motor.id }.body.endsWith("☑ Beli oli") }
        rule.runOnUiThread { vm.undoAgent() }
        rule.waitUntil(10_000) { vm.agent == null && vm.notes.first { it.id == motor.id }.body.endsWith("☐ Beli oli") }

        // Yang tidak bisa dijalankan dijelaskan, bukan ditebak.
        rule.runOnUiThread { vm.startAgent("ingatkan saya bayar pajak") }
        check(!vm.agentReady && vm.agent!!.items.single().problem != null)
        rule.runOnUiThread { vm.startAgent("hapus catatan zzzz tidak ada") }
        check(!vm.agentReady)
        shot("22-rencana-bermasalah")
        rule.runOnUiThread { vm.closeAgent(); vm.debugLock() }
        shot("17-terkunci")
    }
}

@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class ShotsLight : Shots("terang")

@Config(sdk = [36], qualifiers = "w411dp-h891dp-night-xhdpi")
class ShotsDark : Shots("gelap")

/** Layar sangat tinggi, agar halaman yang panjang (Pengaturan) terlihat utuh dalam satu gambar. */
@Config(sdk = [36], qualifiers = "w411dp-h2200dp-xhdpi")
class ShotsTall : Shots("tinggi")
