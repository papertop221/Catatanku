package com.catatanku.app.domain.usecase

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Templat catatan. Murni Kotlin (tanpa android.*) agar bisa diuji di JVM.
 *
 * Isian waktu (`{tanggal}`, `{hari}`, `{jam}`) selalu diisi oleh kode saat templat dipakai, bukan oleh LLM,
 * sesuai aturan "tanggal dan waktu dihitung oleh kode".
 */
data class NoteTemplate(
    val id: String,
    val name: String,
    val title: String,
    val body: String,
    val builtIn: Boolean = false,
)

object Templates {
    private val ID: Locale = Locale.forLanguageTag("id-ID")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", ID)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", ID)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH.mm", ID)

    const val MAX_NAME = 40
    const val MAX_BODY = 4000
    const val MAX_USER_TEMPLATES = 30

    /** Templat bawaan: struktur kerja yang umum dipakai, tanpa data contoh fiktif. */
    val builtIn: List<NoteTemplate> = listOf(
        NoteTemplate(
            "b-rapat", "Notulen rapat", "Rapat {tanggal}",
            "Waktu: {hari}, {tanggal} · {jam}\nPeserta: \nAgenda: \n\n## Pembahasan\n• \n\n## Keputusan\n• \n\n## Tindak lanjut\n☐ ",
            builtIn = true,
        ),
        NoteTemplate(
            "b-proyek", "Rencana proyek", "",
            "## Tujuan\n\n\n## Lingkup\n• \n\n## Tonggak\n☐ \n\n## Risiko\n• \n\n## Sumber daya\n• ",
            builtIn = true,
        ),
        NoteTemplate(
            "b-laporan", "Laporan kerja", "Laporan {tanggal}",
            "## Selesai\n☑ \n\n## Sedang dikerjakan\n☐ \n\n## Kendala\n• \n\n## Rencana berikutnya\n☐ ",
            builtIn = true,
        ),
        NoteTemplate(
            "b-jurnal", "Jurnal harian", "Jurnal {tanggal}",
            "## Hari ini\n\n\n## Yang disyukuri\n• \n\n## Pelajaran\n• \n\n## Rencana besok\n☐ ",
            builtIn = true,
        ),
        NoteTemplate(
            "b-belajar", "Catatan belajar", "",
            "Topik: \nSumber: \nTanggal: {tanggal}\n\n## Poin utama\n• \n\n## Pertanyaan\n• \n\n## Ringkasan\n",
            builtIn = true,
        ),
        NoteTemplate(
            "b-klien", "Brief klien", "",
            "Klien: \nKontak: \nTanggal: {tanggal}\n\n## Kebutuhan\n• \n\n## Batasan & anggaran\n• \n\n## Tenggat\n\n\n## Langkah berikutnya\n☐ ",
            builtIn = true,
        ),
        NoteTemplate(
            "b-belanja", "Daftar belanja", "Belanja {tanggal}",
            "## Kebutuhan pokok\n☐ \n\n## Dapur\n☐ \n\n## Lain-lain\n☐ \n\nAnggaran: Rp ",
            builtIn = true,
        ),
        NoteTemplate(
            "b-evaluasi", "Evaluasi", "",
            "## Yang berjalan baik\n• \n\n## Yang perlu diperbaiki\n• \n\n## Aksi\n☐ ",
            builtIn = true,
        ),
    )

    /** Mengganti isian waktu dengan nilai saat ini. Isian yang tidak dikenal dibiarkan apa adanya. */
    fun fill(text: String, now: LocalDateTime = LocalDateTime.now()): String {
        if ('{' !in text) return text
        return text
            .replace("{tanggal}", DATE.format(now))
            .replace("{hari}", DAY.format(now).replaceFirstChar { it.titlecase(ID) })
            .replace("{jam}", TIME.format(now))
    }

    /** Judul dan isi siap pakai. */
    fun apply(t: NoteTemplate, now: LocalDateTime = LocalDateTime.now()): Pair<String, String> =
        fill(t.title, now).trim() to fill(t.body, now)

    /**
     * Menyisipkan templat ke catatan yang sudah ada: catatan kosong diganti, catatan berisi ditambah di bawah.
     * Judul hanya diisi bila masih kosong.
     */
    fun insertInto(title: String, body: String, t: NoteTemplate, now: LocalDateTime = LocalDateTime.now()): Pair<String, String> {
        val (tt, tb) = apply(t, now)
        val nextTitle = title.ifBlank { tt }
        val nextBody = if (body.isBlank()) tb else body.trimEnd() + "\n\n" + tb
        return nextTitle to nextBody
    }

    /** Membersihkan templat buatan pengguna/AI sebelum disimpan. */
    fun sanitize(name: String, title: String, body: String): Triple<String, String, String>? {
        val n = name.replace('\n', ' ').trim().take(MAX_NAME)
        val b = body.replace("\r\n", "\n").trim('\n').take(MAX_BODY)
        if (n.isEmpty() || b.isBlank()) return null
        return Triple(n, title.replace('\n', ' ').trim().take(120), b)
    }

    // ---------- Penyimpanan: daftar templat pengguna sebagai teks berbaris (dienkripsi di lapisan data) ----------

    private const val SEP_FIELD = '\u001F'
    private const val SEP_ITEM = '\u001E'

    fun encode(list: List<NoteTemplate>): String =
        list.filter { !it.builtIn }.joinToString(SEP_ITEM.toString()) { listOf(it.id, it.name, it.title, it.body).joinToString(SEP_FIELD.toString()) { f -> f.replace(SEP_FIELD, ' ').replace(SEP_ITEM, ' ') } }

    fun decode(raw: String): List<NoteTemplate> =
        if (raw.isBlank()) emptyList()
        else raw.split(SEP_ITEM).mapNotNull { item ->
            val f = item.split(SEP_FIELD)
            if (f.size != 4 || f[0].isBlank() || f[1].isBlank()) null else NoteTemplate(f[0], f[1], f[2], f[3])
        }.take(MAX_USER_TEMPLATES)
}
