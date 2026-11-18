package com.catatanku.app.domain.model

import java.time.LocalDateTime

/** Pengulangan pengingat. Urutan dipakai sebagai nilai di basis data; jangan diubah. */
enum class Repeat(val label: String) {
    None("Sekali"), Daily("Harian"), Weekly("Mingguan"), Monthly("Bulanan");

    /** Jadwal berikutnya setelah [now] untuk pengingat yang semula jatuh pada [at]. */
    fun next(at: LocalDateTime, now: LocalDateTime): LocalDateTime? {
        if (this == None) return null
        var t = at
        var i = 0L
        while (!t.isAfter(now)) {
            i++
            t = when (this) {
                Daily -> at.plusDays(i)
                Weekly -> at.plusWeeks(i)
                else -> at.plusMonths(i)      // dihitung dari tanggal asal agar tanggal 31 tidak bergeser permanen
            }
        }
        return t
    }

    companion object { fun of(i: Int) = entries.getOrElse(i) { None } }
}

data class Note(
    val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val folder: String = "",
    val archived: Boolean = false,
    val deletedAt: Long = 0,          // 0 = tidak di sampah
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val remindAt: Long = 0,           // 0 = tanpa pengingat
    val remindRepeat: Repeat = Repeat.None,
    val remindAlarm: Boolean = false, // berdering seperti alarm sampai ditanggapi
    val remindFired: Boolean = false, // sudah berbunyi tetapi belum ditandai selesai
    val attachments: List<String> = emptyList(), // berkas lampiran di filesDir/attachments (img_*, aud_*)
    val vault: Boolean = false,       // catatan rahasia Brankas: tersembunyi dari daftar & pencarian biasa
) {
    val isBlank get() = title.isBlank() && body.isBlank() && attachments.isEmpty()
    val inTrash get() = deletedAt != 0L
    val hasReminder get() = remindAt != 0L
    val imageCount get() = attachments.count { it.startsWith("img_") }
    val audioCount get() = attachments.count { it.startsWith("aud_") }

    /** Judul untuk ditampilkan: judul, atau baris pertama isi bila judul kosong. */
    val heading get() = title.ifBlank {
        body.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: when {
                audioCount > 0 && imageCount > 0 -> "Catatan suara & gambar"
                audioCount > 0 -> "Catatan suara"
                imageCount > 0 -> "Catatan gambar"
                else -> ""
            }
    }

    fun missingAttachments(existingNames: Set<String>): List<String> =
        attachments.filter { it !in existingNames }
}

/** Salinan lama sebuah catatan, disimpan otomatis sebelum isinya berubah. */
data class Version(val id: Long, val noteId: Long, val title: String, val body: String, val saved: Long)

enum class Screen { List, Editor, Settings }
enum class Sort(val label: String) { Updated("Terakhir diubah"), Created("Tanggal dibuat"), Title("Judul A–Z") }

/** Tampilan daftar: semua catatan aktif, satu folder, yang berpengingat, arsip, atau sampah. */
sealed interface NoteView {
    data object All : NoteView
    data class Folder(val name: String) : NoteView
    data object Reminders : NoteView
    data object Archive : NoteView
    data object Trash : NoteView
    data object Vault : NoteView
}

/** Aksi bantu-tulis. [replaces] = hasilnya wajar menggantikan isi (bukan ditambahkan). */
enum class AiAction(
    val label: String,
    val hint: String,
    val replaces: Boolean = false,
    val forTitle: Boolean = false,
    /** Catatan yang lebih panjang dari jendela model diproses bagian demi bagian, bukan dipotong. */
    val wholeNote: Boolean = false,
) {
    Continue("Lanjutkan", "Satu–dua paragraf berikutnya"),
    Plan("Rencana dari tujuan", "Langkah centang berurutan", wholeNote = false),
    Extract("Poin penting", "Jadwal, keputusan, angka, kontak"),
    Formal("Nada formal", "Profesional, makna tetap", replaces = true),
    Casual("Nada santai", "Ramah, makna tetap", replaces = true),
    Translate("Terjemahkan", "Indonesia ↔ Inggris", replaces = true),
    Explain("Jelaskan", "Bahasa sederhana"),
    Fix("Koreksi ejaan", "Ejaan dan tata bahasa", replaces = true, wholeNote = true),
    Tidy("Susun ulang", "Subjudul dan poin", replaces = true),
    Summarize("Ringkasan", "3–5 poin", wholeNote = true),
    Tasks("Ekstrak tugas", "Daftar centang", wholeNote = true),
    Title("Buat judul", "Maks. enam kata", forTitle = true),
    Custom("Instruksi khusus", ""),
}

/** Hasil bantu-tulis AI yang menunggu keputusan pengguna (tidak pernah diterapkan otomatis). */
data class AiJob(
    val action: AiAction,
    val text: String = "",
    val running: Boolean = true,
    val error: String? = null,
    val progress: String? = null,        // mis. "Bagian 2 dari 4" untuk catatan panjang
    val customTitle: String? = null,     // mis. "Baca gambar" atau "Transkripsikan suara"
    val suggestedRemindAt: Long? = null, // waktu pengingat yang terdeteksi dari gambar/suara/teks
    /** Bila aksi dijalankan pada teks terpilih: posisi dan teks aslinya, agar hasil menggantikan bagian itu saja. */
    val selStart: Int = -1,
    val selEnd: Int = -1,
    val selText: String = "",
    /** Templat buatan AI: nama dan judul usulan, hasil ditaruh di [text]. */
    val templateName: String? = null,
    val templateTitle: String = "",
) {
    val onSelection: Boolean get() = selStart >= 0 && selEnd > selStart
    val isTemplate: Boolean get() = templateName != null
}

/** Satu langkah yang bisa dikerjakan asisten atas perintah pengguna. */
sealed interface AgentStep {
    /** Kata kunci catatan sasaran; [PREV_TARGET] = catatan dari langkah sebelumnya; null = tanpa sasaran. */
    val target: String? get() = null

    data class Create(
        val title: String,
        val body: String = "",
        val tags: List<String> = emptyList(),
        val folder: String = "",
        val at: LocalDateTime? = null,
        val repeat: Repeat = Repeat.None,
        val alarm: Boolean = false,
        /** Perintahnya meminta pengingat ("ingatkan …"), jadi [at] wajib dikenali. */
        val wantsReminder: Boolean = false,
    ) : AgentStep

    data class Remind(override val target: String, val at: LocalDateTime?, val repeat: Repeat = Repeat.None, val alarm: Boolean = false) : AgentStep
    data class Unremind(override val target: String) : AgentStep
    data class Append(override val target: String, val text: String, val task: Boolean) : AgentStep
    data class Check(override val target: String, val text: String) : AgentStep
    data class Tag(override val target: String, val tags: List<String>) : AgentStep
    data class Move(override val target: String, val folder: String) : AgentStep
    data class Pin(override val target: String, val on: Boolean) : AgentStep
    data class Archive(override val target: String) : AgentStep
    data class Trash(override val target: String) : AgentStep

    companion object {
        const val PREV_TARGET = "\u0001"
    }
}

/** Satu langkah rencana asisten: catatan sasaran yang ditemukan, pilihan lain, dan alasan bila belum bisa dijalankan. */
data class AgentItem(val step: AgentStep, val target: Note? = null, val options: List<Note> = emptyList(), val problem: String? = null)

/** Perintah yang sedang ditangani asisten: menyusun rencana → menunggu persetujuan → hasil. Tak pernah jalan tanpa persetujuan. */
data class AgentRun(
    val command: String,
    val items: List<AgentItem> = emptyList(),
    val planning: Boolean = false,
    val error: String? = null,
    val results: List<String>? = null,      // terisi setelah dijalankan
    val touched: Long = 0,                  // catatan terakhir yang dibuat/diubah, untuk tombol "Buka catatan"
)

/** Pesan singkat di dasar layar; [action] menambahkan satu tombol, mis. "Urungkan". */
class Notice(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** Ringkasan daftar catatan. Dihitung sekali tiap catatan berubah, bukan tiap kali layar digambar ulang. */
class Stats(notes: List<Note>, openTaskCounter: (String) -> Int = { defaultOpenTaskCount(it) }) {
    val byId: Map<Long, Note> = notes.associateBy { it.id }
    val tags: List<String>          // yang paling sering dipakai lebih dulu
    val folders: List<String>
    val trashCount: Int
    val reminderCount: Int
    val organizable: Int
    val openTaskCount: Int

    init {
        val publicNotes = notes.filter { !it.vault }
        val live = publicNotes.filter { !it.inTrash }
        tags = live.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        folders = live.map { it.folder }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        trashCount = publicNotes.size - live.size
        reminderCount = live.count { it.hasReminder }
        organizable = live.count { needsOrganizing(it, folders.isNotEmpty()) }
        openTaskCount = live.sumOf { if (it.archived) 0 else openTaskCounter(it.body) }
    }

    companion object {
        private fun defaultOpenTaskCount(body: String): Int =
            if ('☐' !in body) 0 else body.lineSequence().count { val t = it.trim(); t.startsWith("☐") && t.length > 2 }

        /** Catatan yang judul, tag, atau foldernya masih kosong dan bisa diisi AI. */
        fun needsOrganizing(n: Note, hasFolders: Boolean) =
            !n.inTrash && n.body.isNotBlank() && (n.title.isBlank() || n.tags.isEmpty() || (n.folder.isBlank() && hasFolders))
    }
}
