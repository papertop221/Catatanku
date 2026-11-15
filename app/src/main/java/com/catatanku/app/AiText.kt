package com.catatanku.app

/** Aksi bantu-tulis. [replaces] = hasilnya wajar menggantikan isi (bukan ditambahkan). */
enum class AiAction(
    val label: String, val hint: String, val replaces: Boolean = false, val forTitle: Boolean = false,
    /** Catatan yang lebih panjang dari jendela model diproses bagian demi bagian, bukan dipotong. */
    val wholeNote: Boolean = false,
) {
    Continue("Lanjutkan tulisan", "Menulis satu–dua paragraf berikutnya"),
    Fix("Perbaiki ejaan", "Ejaan dan tata bahasa, makna tetap", replaces = true, wholeNote = true),
    Tidy("Rapikan struktur", "Dikelompokkan dengan subjudul dan poin", replaces = true),
    Summarize("Ringkas", "Menjadi 3–5 poin", wholeNote = true),
    Tasks("Ambil daftar tugas", "Tugas dijadikan daftar centang", wholeNote = true),
    Title("Buat judul", "Maksimal enam kata", forTitle = true),
    Custom("Instruksi bebas", ""),
}

/** Prompt dan pengurai keluaran LLM. Murni Kotlin agar bisa diuji tanpa perangkat. */
object AiText {
    /** Batas teks catatan yang dikirim ke model; model kecil punya jendela konteks sempit. */
    @Volatile var maxInput = 2800

    fun clip(text: String, max: Int = maxInput, fromEnd: Boolean = false): String =
        if (text.length <= max) text else if (fromEnd) text.takeLast(max) else text.take(max)

    const val MAX_PARTS = 8

    /**
     * Membagi teks panjang menjadi bagian ≤ [max] huruf di batas paragraf, baris, kalimat, atau kata.
     * Bagian-bagian itu bila disambung kembali persis sama dengan teks asal; yang melebihi [limit] bagian dibuang.
     */
    fun chunks(text: String, max: Int = maxInput, limit: Int = MAX_PARTS): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max && out.size < limit) {
            val w = rest.substring(0, max)
            val half = max / 2
            var cut = w.lastIndexOf("\n\n").let { if (it >= half) it + 2 else -1 }
            if (cut < 0) cut = w.lastIndexOf('\n').let { if (it >= half) it + 1 else -1 }
            if (cut < 0) cut = listOf(". ", "! ", "? ").maxOf { w.lastIndexOf(it) }.let { if (it >= half) it + 2 else -1 }
            if (cut < 0) cut = w.lastIndexOf(' ').let { if (it >= half) it + 1 else max }
            out += rest.substring(0, cut); rest = rest.substring(cut)
        }
        if (rest.isNotEmpty() && out.size < limit) out += rest
        return out
    }

    /** Prompt untuk satu bagian dari catatan panjang (lihat [AiAction.wholeNote]). */
    fun partPrompt(action: AiAction, title: String, part: String, index: Int, total: Int): String {
        val text = part.trim()
        return when (action) {
            AiAction.Summarize ->
                "Berikut bagian ${index + 1} dari $total sebuah catatan panjang" + (if (title.isNotBlank()) " berjudul \"${title.take(120)}\"" else "") +
                    ". Ringkas bagian ini menjadi 2 sampai 3 poin singkat, tiap poin diawali \"• \". Gunakan bahasa yang sama " +
                    "dengan catatan, teks polos tanpa tanda * atau #. Tulis HANYA poin-poinnya.\n\n$text\n\nRingkasan:"
            else -> prompt(action, "", text)
        }
    }

    fun mergeSummaryPrompt(title: String, points: String): String =
        "Berikut poin-poin ringkasan dari bagian-bagian sebuah catatan panjang" + (if (title.isNotBlank()) " berjudul \"${title.take(120)}\"" else "") +
            ". Gabungkan menjadi 3 sampai 5 poin terpenting tanpa mengulang, tiap poin diawali \"• \". Jangan menambah " +
            "informasi baru. Gunakan bahasa yang sama, teks polos tanpa tanda * atau #. Tulis HANYA poin-poinnya.\n\n" +
            clip(points) + "\n\nRingkasan akhir:"

    /** Baris daftar centang yang belum dicentang. */
    fun openTasks(body: String): List<String> =
        body.lineSequence().map { it.trim() }.filter { it.startsWith("☐") && it.length > 2 }.toList()

    /** Jumlah [openTasks], tanpa membuat daftarnya; kebanyakan catatan tidak punya daftar centang sama sekali. */
    fun openTaskCount(body: String): Int =
        if ('☐' !in body) 0 else body.lineSequence().count { val t = it.trim(); t.startsWith("☐") && t.length > 2 }

    /** Menggabungkan daftar tugas dari beberapa bagian: tanpa duplikat dan tanpa baris "Tidak ada tugas". */
    fun mergeTasks(parts: List<String>): String =
        parts.flatMap { it.lines() }.map { it.trim() }.filter { it.startsWith("☐") }.distinctBy { it.lowercase() }
            .joinToString("\n").ifBlank { "Tidak ada tugas." }

    /**
     * Potongan [size] huruf dari [body] yang paling banyak memuat kata-kata [query]. Catatan panjang sering
     * menyimpan jawabannya jauh dari awal, jadi mengambil bagian depan saja membuat AI "tidak menemukan".
     */
    fun bestWindow(body: String, query: String, size: Int): String {
        if (body.length <= size) return body
        val words = TextTools.tokenize(query).toSet()
        val lower = body.lowercase()
        val hits = words.flatMap { w -> generateSequence(lower.indexOf(w)) { i -> lower.indexOf(w, i + w.length).takeIf { it >= 0 } }.takeWhile { it >= 0 }.toList() }.sorted()
        if (hits.isEmpty()) return body.take(size)
        // Jendela geser atas posisi kemunculan: cari awal yang memuat kemunculan terbanyak dalam [size] huruf.
        var best = 0; var bestCount = 0; var j = 0
        for (i in hits.indices) {
            while (hits[j] < hits[i] - size * 3 / 4) j++
            if (i - j + 1 > bestCount) { bestCount = i - j + 1; best = hits[j] }
        }
        var start = (best - size / 8).coerceAtLeast(0)
        if (start > 0) start = (body.lastIndexOf('\n', start).takeIf { it >= start - 200 } ?: body.lastIndexOf(' ', start)).coerceAtLeast(0)
        start = start.coerceAtMost(body.length - size)
        return (if (start > 0) "… " else "") + body.substring(start, start + size).trim()
    }

    // ---------- Pengingat ----------

    /**
     * LLM hanya menulis ulang waktunya ke pola baku; tanggal sebenarnya dihitung [TimeParse].
     * Model kecil buruk dalam aritmetika tanggal, tetapi cukup baik menemukan dan merumuskan ulang frasa.
     */
    fun reminderPrompt(title: String, body: String): String =
        "Cari SATU jadwal atau tenggat terpenting yang akan datang di catatan berikut, yaitu yang paling perlu diingatkan.\n" +
            "Jawab tepat dua baris, tanpa penjelasan:\n" +
            "WAKTU: salin waktunya dari catatan lalu bakukan, seperti \"tanggal 24 November jam 10.00\", \"besok jam 19.00\", " +
            "\"lusa jam 18.00\", atau \"Jumat jam 15.00\". Jangan menghitung tanggal sendiri; pertahankan kata besok, lusa, atau nama harinya.\n" +
            "HAL: apa yang perlu diingat, maksimal 8 kata\n" +
            "Jika catatan tidak memuat jadwal atau tenggat, jawab: WAKTU: -\n\n" +
            "Catatan:\n" + (if (title.isNotBlank()) "Judul: ${title.take(120)}\n" else "") + clip(body, 2400) + "\n\nWAKTU:"

    /** Mengambil frasa waktu dari jawaban [reminderPrompt]; null bila model menyatakan tidak ada. */
    fun parseReminder(raw: String): String? {
        val lines = strip(raw).lines().map { it.trim().trim('*', '#', ' ') }.filter { it.isNotEmpty() }
        val line = lines.firstOrNull { it.startsWith("WAKTU:", ignoreCase = true) }?.substringAfter(':')
            ?: lines.firstOrNull()?.takeIf { !it.startsWith("HAL", ignoreCase = true) }
        return line?.trim { it in " *#\"'“”.`" }?.takeIf { it.length >= 3 }
    }

    // ---------- Rekap ----------

    fun recapPrompt(notes: List<Pair<String, String>>): String {
        val per = ((if (maxInput > 4000) 5000 else 2400) / notes.size.coerceAtLeast(1)).coerceAtMost(900)
        val ctx = notes.mapIndexed { i, (t, b) -> "[${i + 1}] ${t.ifBlank { "(tanpa judul)" }.take(80)}\n${clip(b, per)}" }.joinToString("\n\n")
        return "Berikut catatan pribadi yang ditulis atau diubah dalam 7 hari terakhir. Buat rekap singkat: 3 sampai 6 poin " +
            "tentang hal terpenting dan yang masih perlu dikerjakan, tiap poin diawali \"• \" dan diakhiri nomor catatannya, " +
            "misalnya [2]. Pakai hanya isi catatan; jangan mengarang. Bahasa sama dengan catatan, teks polos tanpa tanda * atau #.\n\n" +
            "$ctx\n\nRekap:"
    }

    fun prompt(action: AiAction, title: String, body: String, custom: String = ""): String {
        val note = (if (title.isNotBlank()) "Judul: ${title.take(120)}\n" else "") + clip(body)
        val same = "Gunakan bahasa yang sama dengan catatan. Tulis teks polos tanpa format Markdown (tanpa tanda * atau #)."
        return when (action) {
            AiAction.Continue ->
                "Lanjutkan tulisan berikut secara alami dengan gaya yang sama, satu sampai dua paragraf. $same " +
                    "Tulis HANYA lanjutannya, jangan mengulang teks yang sudah ada.\n\n" +
                    clip(body, 1600, fromEnd = true) + "\n\nLanjutan:"
            AiAction.Fix ->
                "Perbaiki ejaan, tanda baca, dan tata bahasa teks berikut tanpa mengubah makna, gaya, maupun " +
                    "bahasanya. Tulis HANYA teks hasil perbaikan sebagai teks polos tanpa tanda * atau #, tanpa penjelasan.\n\n${clip(body)}\n\nHasil perbaikan:"
            AiAction.Tidy ->
                "Rapikan struktur catatan berikut agar mudah dibaca: kelompokkan hal yang sejenis, beri subjudul " +
                    "singkat bila perlu, awali tiap poin dengan \"• \". Jangan menambah informasi, tafsiran, atau komentar " +
                    "apa pun dan jangan membuang isi. $same " +
                    "Tulis HANYA catatan yang sudah dirapikan.\n\n$note\n\nCatatan rapi:"
            AiAction.Summarize ->
                "Ringkas catatan berikut menjadi 3 sampai 5 poin singkat, tiap poin diawali \"• \". $same " +
                    "Tulis HANYA poin-poinnya.\n\n$note\n\nRingkasan:"
            AiAction.Tasks ->
                "Temukan tugas dalam catatan berikut, yaitu hal yang harus DIKERJAKAN seseorang. Keputusan, angka, " +
                    "anggaran, dan jadwal rapat bukan tugas, jadi jangan dimasukkan. Tulis satu tugas per baris dengan " +
                    "kalimat singkat, tiap baris diawali \"☐ \". Jika tidak ada tugas, tulis: Tidak ada tugas. $same\n\n$note\n\nDaftar tugas:"
            AiAction.Title ->
                "Buat satu judul singkat (maksimal 6 kata) untuk catatan berikut. $same " +
                    "Tulis HANYA judulnya, tanpa tanda kutip.\n\n${clip(body, 1500)}\n\nJudul:"
            AiAction.Custom ->
                "Kerjakan instruksi berikut terhadap catatan di bawah. Tulis HANYA hasilnya.\n\n" +
                    "Instruksi: ${custom.take(300)}\n\nCatatan:\n$note\n\nHasil:"
        }
    }

    fun tagsPrompt(title: String, body: String, existing: List<String>): String {
        val known = if (existing.isEmpty()) "" else
            "Jika cocok, utamakan tag yang sudah ada: ${existing.take(30).joinToString(", ")}.\n"
        return "Kamu memberi tag pada catatan pribadi. Beri 3 sampai 5 tag topik yang singkat " +
            "(1-2 kata, huruf kecil, bahasa yang sama dengan catatan).\n$known" +
            "Jawab HANYA dengan daftar tag dipisahkan koma, tanpa penjelasan.\n\n" +
            "Judul: ${title.take(120)}\nIsi: ${clip(body, 1500)}\n\nTag:"
    }

    fun expandPrompt(query: String): String =
        "Seseorang mencari di catatan pribadinya dengan kata: \"${query.take(100)}\".\n" +
            "Tuliskan 6 kata kunci lain yang berkaitan erat (sinonim, istilah terkait, " +
            "padanan bahasa Indonesia dan Inggris).\n" +
            "Jawab HANYA dengan kata kunci dipisahkan koma, tanpa penjelasan.\n\nKata kunci:"

    // Pola dikompilasi sekali: cleanBlock dipanggil untuk tiap potongan teks yang mengalir dari model.
    private val THINK = Regex("(?s)<think>.*?(</think>|$)")
    private val TITLE_PREFIX = Regex("^(judul|title)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val MD_HEADING = Regex("^#{1,6}\\s+")
    private val MD_BULLET = Regex("^[*-]\\s+")
    private val MD_BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val MD_ITALIC = Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])")
    private val MD_UNDERLINE = Regex("__(.+?)__")
    private val SPACES = Regex("\\s+")

    /** Membuang blok penalaran (<think>…</think>) yang dikeluarkan sebagian model. */
    fun strip(raw: String): String =
        raw.replace(THINK, "").trim()

    fun organizePrompt(title: String, body: String, folders: List<String>, tags: List<String>): String =
        "Kamu merapikan catatan pribadi. Isi tiga baris berikut untuk catatan di bawah, tanpa penjelasan lain.\n" +
            "JUDUL: judul singkat, maksimal 6 kata\n" +
            (if (folders.isEmpty()) "FOLDER: -\n"
            else "FOLDER: pilih SATU yang paling cocok dari [${folders.take(20).joinToString(", ")}], atau - bila tak ada yang cocok\n") +
            "TAG: 3 sampai 5 tag singkat huruf kecil dipisahkan koma" +
            (if (tags.isEmpty()) "" else ", utamakan dari [${tags.take(30).joinToString(", ")}]") + "\n\n" +
            "Catatan:\n" + (if (title.isNotBlank()) "Judul saat ini: ${title.take(120)}\n" else "") +
            clip(body, 1800) + "\n\nJUDUL:"

    data class Organized(val title: String, val folder: String, val tags: List<String>)

    /** Mengurai jawaban tiga-baris secara longgar; model kecil sering tidak patuh format. */
    fun parseOrganize(raw: String, folders: List<String>): Organized {
        // Prompt berakhir dengan "JUDUL:", jadi baris pertama jawaban biasanya langsung judulnya.
        val lines = raw.lines().map { it.trim().trim('*', '#', '-', ' ') }.filter { it.isNotEmpty() }
        fun field(vararg keys: String): String? = lines.firstNotNullOfOrNull { l ->
            keys.firstOrNull { l.startsWith("$it:", ignoreCase = true) }?.let { l.substring(it.length + 1).trim() }
        }
        val first = lines.firstOrNull()?.takeIf { l -> !l.contains(':') }
        val title = cleanLine(field("JUDUL", "TITLE") ?: first ?: "").take(80)
        val f = cleanLine(field("FOLDER") ?: "")
        val folder = folders.firstOrNull { it.equals(f, ignoreCase = true) } ?: ""
        val tags = TextTools.parseList(field("TAG", "TAGS") ?: "", 5).map(TextTools::normalizeTag)
        return Organized(title, folder, tags)
    }

    /** Membersihkan satu baris jawaban (judul): kutip, tanda bintang, awalan "Judul:". */
    fun cleanLine(raw: String): String =
        raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            .replace(TITLE_PREFIX, "")
            .trim { it in " \"'`*#“”" }.trimEnd('.').trim { it in " \"'`*#“”" }

    /** Menjadikan keluaran model teks polos: buang penalaran, pagar kode, dan sisa Markdown. */
    fun cleanBlock(raw: String): String =
        strip(raw).removePrefix("```").removeSuffix("```").lines().joinToString("\n") { line ->
            val indent = line.takeWhile { it == ' ' }.length
            var t = line.trim()
            t = t.replace(MD_HEADING, "")                               // judul Markdown
            t = t.replace(MD_BULLET, "• ")                              // poin Markdown
            t = t.replace(MD_BOLD, "$1").replace(MD_ITALIC, "$1")
            t = t.replace(MD_UNDERLINE, "$1").replace("`", "")
            (if (indent >= 2 && t.startsWith("• ")) "   " else "") + t
        }.trim()

    fun askPrompt(question: String, sources: List<Pair<String, String>>): String {
        val per = ((if (maxInput > 4000) 4000 else 2400) / sources.size.coerceAtLeast(1)).coerceAtMost(if (maxInput > 4000) 1500 else 900)
        val ctx = sources.mapIndexed { i, (t, b) -> "[${i + 1}] ${t.ifBlank { "(tanpa judul)" }.take(80)}\n${bestWindow(b, question, per)}" }
            .joinToString("\n\n")
        return "Jawab pertanyaan HANYA berdasarkan catatan pribadi di bawah. Tulis jawabannya dalam satu atau dua " +
            "kalimat lengkap yang memuat faktanya, lalu akhiri dengan nomor catatan sumbernya, misalnya [1]. " +
            "Jika jawabannya tidak ada di catatan, katakan bahwa itu tidak ditemukan di catatan; jangan mengarang. " +
            "Gunakan bahasa pertanyaan, teks polos.\n\n$ctx\n\nPertanyaan: ${question.take(300)}\nJawaban lengkap:"
    }

    fun wordCount(text: String): Int = text.split(SPACES).count { it.isNotBlank() }

    /** Mengganti awalan baris tempat kursor berada: tanpa → ☐ → ☑ → tanpa (atau poin). */
    fun toggleLinePrefix(text: String, cursor: Int, checklist: Boolean): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', c - 1) + 1
        val end = text.indexOf('\n', c).let { if (it < 0) text.length else it }
        val line = text.substring(start, end)
        val known = listOf("☐ ", "☑ ", "• ")
        val cur = known.firstOrNull { line.startsWith(it) }
        val bare = if (cur != null) line.substring(cur.length) else line
        val next = if (checklist) when (cur) { "☐ " -> "☑ "; "☑ " -> ""; else -> "☐ " }
        else if (cur == "• ") "" else "• "
        val newLine = next + bare
        val delta = newLine.length - line.length
        return (text.substring(0, start) + newLine + text.substring(end)) to (c + delta).coerceIn(start, start + newLine.length)
    }
}
