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

    /** Membersihkan token kendali model dan karakter null agar teks luar diperlakukan sebagai data, bukan perintah. */
    private val MODEL_TOKENS = Regex("(?i)<(?:/?think|/?start_of_turn|/?end_of_turn|\\|im_start\\||\\|im_end\\||\\|endoftext\\||bos|eos)>")

    fun sanitizeInput(text: String): String =
        text.replace("\u0000", "").replace(MODEL_TOKENS, " ")

    fun clip(text: String, max: Int = maxInput, fromEnd: Boolean = false): String {
        val clean = sanitizeInput(text)
        return if (clean.length <= max) clean else if (fromEnd) clean.takeLast(max) else clean.take(max)
    }

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
        val text = sanitizeInput(part).trim()
        val safeTitle = sanitizeInput(title).take(120)
        return when (action) {
            AiAction.Summarize ->
                "Berikut bagian ${index + 1} dari $total sebuah catatan panjang" + (if (safeTitle.isNotBlank()) " berjudul \"$safeTitle\"" else "") +
                    ". Ringkas bagian ini menjadi 2 sampai 3 poin singkat, tiap poin diawali \"• \". Gunakan bahasa yang sama " +
                    "dengan catatan, teks polos tanpa tanda * atau #. Tulis HANYA poin-poinnya.\n\n$text\n\nRingkasan:"
            else -> prompt(action, "", text)
        }
    }

    fun mergeSummaryPrompt(title: String, points: String): String {
        val safeTitle = sanitizeInput(title).take(120)
        return "Berikut poin-poin ringkasan dari bagian-bagian sebuah catatan panjang" + (if (safeTitle.isNotBlank()) " berjudul \"$safeTitle\"" else "") +
            ". Gabungkan menjadi 3 sampai 5 poin terpenting tanpa mengulang, tiap poin diawali \"• \". Jangan menambah " +
            "informasi baru. Gunakan bahasa yang sama, teks polos tanpa tanda * atau #. Tulis HANYA poin-poinnya.\n\n" +
            clip(points) + "\n\nRingkasan akhir:"
    }

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
            "Catatan:\n" + (if (title.isNotBlank()) "Judul: ${sanitizeInput(title).take(120)}\n" else "") + clip(body, 2400) + "\n\nWAKTU:"

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
        val ctx = notes.mapIndexed { i, (t, b) -> "[${i + 1}] ${sanitizeInput(t).ifBlank { "(tanpa judul)" }.take(80)}\n${clip(b, per)}" }.joinToString("\n\n")
        return "Berikut catatan pribadi yang ditulis atau diubah dalam 7 hari terakhir. Buat rekap singkat: 3 sampai 6 poin " +
            "tentang hal terpenting dan yang masih perlu dikerjakan, tiap poin diawali \"• \" dan diakhiri nomor catatannya, " +
            "misalnya [2]. Pakai hanya isi catatan; jangan mengarang. Bahasa sama dengan catatan, teks polos tanpa tanda * atau #.\n\n" +
            "$ctx\n\nRekap:"
    }

    fun prompt(action: AiAction, title: String, body: String, custom: String = ""): String {
        val safeTitle = sanitizeInput(title).take(120)
        val note = (if (safeTitle.isNotBlank()) "Judul: $safeTitle\n" else "") + clip(body)
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
                    "Instruksi: ${sanitizeInput(custom).take(300)}\n\nCatatan:\n$note\n\nHasil:"
        }
    }

    fun tagsPrompt(title: String, body: String, existing: List<String>): String {
        val known = if (existing.isEmpty()) "" else
            "Jika cocok, utamakan tag yang sudah ada: ${existing.take(30).joinToString(", ")}.\n"
        return "Kamu memberi tag pada catatan pribadi. Beri 3 sampai 5 tag topik yang singkat " +
            "(1-2 kata, huruf kecil, bahasa yang sama dengan catatan).\n$known" +
            "Jawab HANYA dengan daftar tag dipisahkan koma, tanpa penjelasan.\n\n" +
            "Judul: ${sanitizeInput(title).take(120)}\nIsi: ${clip(body, 1500)}\n\nTag:"
    }

    fun expandPrompt(query: String): String =
        "Seseorang mencari di catatan pribadinya dengan kata: \"${sanitizeInput(query).take(100)}\".\n" +
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
            "Catatan:\n" + (if (title.isNotBlank()) "Judul saat ini: ${sanitizeInput(title).take(120)}\n" else "") +
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
        val ctx = sources.mapIndexed { i, (t, b) -> "[${i + 1}] ${sanitizeInput(t).ifBlank { "(tanpa judul)" }.take(80)}\n${bestWindow(sanitizeInput(b), question, per)}" }
            .joinToString("\n\n")
        return "Jawab pertanyaan HANYA berdasarkan catatan pribadi di bawah. Tulis jawabannya dalam satu atau dua " +
            "kalimat lengkap yang memuat faktanya, lalu akhiri dengan nomor catatan sumbernya, misalnya [1]. " +
            "Jika jawabannya tidak ada di catatan, katakan bahwa itu tidak ditemukan di catatan; jangan mengarang. " +
            "Gunakan bahasa pertanyaan, teks polos.\n\n$ctx\n\nPertanyaan: ${sanitizeInput(question).take(300)}\nJawaban lengkap:"
    }

    fun wordCount(text: String): Int = text.split(SPACES).count { it.isNotBlank() }

    /** Mengganti awalan baris tempat kursor berada: tanpa → ☐ → ☑ → tanpa (atau poin). */
    fun toggleLinePrefix(text: String, cursor: Int, checklist: Boolean): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val start = if (c <= 0) 0 else text.lastIndexOf('\n', c - 1) + 1
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

    /**
     * Bila ketukan ([offset]) jatuh pada kotak centang (☐ atau ☑) di awal baris, langsung ubah ☐ ↔ ☑
     * tanpa menghapus kotaknya, lalu kembalikan (teks baru, posisi kursor sesudah kotak).
     * Mengembalikan null bila ketukan bukan pada kotak centang.
     */
    fun toggleCheckAt(text: String, offset: Int): Pair<String, Int>? {
        if (text.isEmpty() || offset !in 0..text.length) return null
        val start = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        var boxPos = start
        while (boxPos < end && text[boxPos] == ' ') boxPos++
        if (boxPos >= end || offset !in start..(boxPos + 1)) return null
        val flipped = when (text[boxPos]) {
            '☐' -> '☑'
            '☑' -> '☐'
            else -> return null
        }
        val nextText = text.substring(0, boxPos) + flipped + text.substring(boxPos + 1)
        val nextCursor = (boxPos + 2).coerceAtMost( end)
        return nextText to nextCursor
    }

    /**
     * Saat pengguna menekan Enter di akhir/tengah baris daftar (☐, ☑, atau •):
     * - Bila baris daftar itu kosong (hanya "☐ " atau "• "), hapus awalannya (keluar dari daftar).
     * - Bila baris daftar itu ada isinya, otomatis mulai baris baru dengan "☐ " atau "• ".
     * Mengembalikan null bila perubahan teks bukan penekanan Enter pada baris daftar.
     */
    fun continueListOnEnter(before: String, after: String, cursorAfter: Int): Pair<String, Int>? {
        if (after.length != before.length + 1 || cursorAfter !in 1..after.length) return null
        val nlPos = cursorAfter - 1
        if (after[nlPos] != '\n') return null
        if (!after.regionMatches(0, before, 0, nlPos) || !after.regionMatches(cursorAfter, before, nlPos, before.length - nlPos)) return null
        val lineStart = if (nlPos <= 0) 0 else before.lastIndexOf('\n', nlPos - 1) + 1
        val prevLine = before.substring(lineStart, nlPos)
        val indent = prevLine.takeWhile { it == ' ' }
        val trimmed = prevLine.substring(indent.length)
        val prefix = listOf("☐ ", "☑ ", "• ").firstOrNull { trimmed.startsWith(it) } ?: return null
        val content = trimmed.substring(prefix.length)
        if (content.isBlank()) {
            // Enter pada butir kosong: hapus awalan daftar dan batalkan baris baru.
            val cleaned = before.substring(0, lineStart) + before.substring(nlPos)
            return cleaned to lineStart
        }
        val nextPrefix = indent + if (prefix == "• ") "• " else "☐ "
        val updated = after.substring(0, cursorAfter) + nextPrefix + after.substring(cursorAfter)
        return updated to (cursorAfter + nextPrefix.length)
    }

    /** Mengubah status centang satu baris tugas tertentu di dalam [body]; null bila barisnya tidak ditemukan. */
    fun setTaskChecked(body: String, taskLine: String, checked: Boolean): String? {
        val target = taskLine.trim()
        val from = if (checked) "☐" else "☑"
        val to = if (checked) "☑" else "☐"
        val expected = if (target.startsWith("☐") || target.startsWith("☑")) from + target.substring(1) else "$from $target"
        var done = false
        val out = body.lines().joinToString("\n") { line ->
            if (!done && line.trim() == expected) {
                done = true
                line.replaceFirst(from, to)
            } else line
        }
        return if (done) out else null
    }

    /** Hasil ekstraksi tugas dari gambar/suara/teks: daftar ceklis `☐ ...` dan waktu pengingat bila disebut. */
    data class ExtractedTasks(
        val tasks: List<String>,
        val remindAt: java.time.LocalDateTime? = null,
        val timePhrase: String? = null,
    ) {
        val text: String get() = tasks.joinToString("\n")
        val isEmpty: Boolean get() = tasks.isEmpty()
    }

    /** Prompt membaca gambar (salin tulisan bila ada, atau jelaskan foto bila tidak ada teks). */
    fun imageReadPrompt(): String =
        "Jika gambar ini memuat tulisan (seperti catatan, struk, daftar, jadwal, atau tangkapan layar pesan), " +
            "salin semua tulisan tersebut apa adanya dengan rapi.\n" +
            "Jika gambar tidak memuat tulisan, jelaskan isi gambar secara singkat dalam satu atau dua kalimat.\n" +
            "Tulis teks polos tanpa tanda * atau #.\n\nHasil:"

    /** Prompt OCR ketat untuk langkah pertama "Ambil tugas" dari gambar: dilarang mendeskripsikan foto. */
    fun imageOcrPrompt(): String =
        "Periksa gambar ini. Apakah ada tulisan atau teks yang jelas terbaca " +
            "(misalnya catatan tangan, daftar belanja, papan tulis, struk, jadwal, atau tangkapan layar pesan)?\n" +
            "Jika TIDAK ADA tulisan yang terbaca (misalnya hanya foto orang, pemandangan, atau benda tanpa teks tugas), jawab TEPAT: TIDAK ADA TEKS\n" +
            "Jika ADA tulisan, salin HANYA tulisan yang terlihat di gambar apa adanya. Jangan mendeskripsikan orang, pakaian, atau pemandangan.\n\n" +
            "Tulisan:"

    /** Prompt transkripsi suara yang singkat dan berakhiran penanda agar model kecil tidak memantulkan instruksi. */
    fun audioTranscribePrompt(): String =
        "Tuliskan persis ucapan dalam rekaman suara ini, kata demi kata, tanpa menambah penjelasan.\n\nUcapan:"

    /** Prompt ekstraksi tugas berbasis teks (dipakai sesudah OCR atau transkripsi suara). */
    fun tasksFromTextPrompt(text: String): String =
        "Ekstrak HANYA tugas nyata yang harus dikerjakan dari teks berikut.\n" +
            "Aturan:\n" +
            "- Satu tugas singkat per baris, diawali \"☐ \".\n" +
            "- Pecah beberapa tugas dalam satu kalimat menjadi baris terpisah.\n" +
            "- Buang kata keterangan waktu (besok, nanti sore, jam 7) dari kalimat tugas.\n" +
            "- Jika teks hanya cerita, deskripsi pemandangan/orang, atau tidak memuat tugas nyata, jawab persis: Tidak ada tugas.\n\n" +
            "Contoh:\n" +
            "Teks: nanti sore buat catatan pribadi\n" +
            "Daftar tugas:\n☐ Buat catatan pribadi\n\n" +
            "Teks: besok jam 7 malam bayar listrik, beli gas, terus hubungi Pak RT\n" +
            "Daftar tugas:\n☐ Bayar listrik\n☐ Beli gas\n☐ Hubungi Pak RT\n\n" +
            "Teks: Seorang wanita berdiri di tepi pantai saat matahari terbenam.\n" +
            "Daftar tugas:\nTidak ada tugas.\n\n" +
            "Teks: ${clip(text, 2000)}\nDaftar tugas:"

    private val AUDIO_ECHO_LEAD = Regex("(?i)^(?:transkripsi(?:kan)?|ucapan|kalimat yang diucapkan|tulisan|hasil(?:nya)?)\\s*[:\\-]?\\s*")
    private val AUDIO_ECHO_TAIL = Regex("(?i)\\s*(?:(?:dengan|dalam)\\s+bahasa\\s+indonesia|tanpa\\s+(?:tambahan\\s+)?(?:kata|penjelasan)\\s*(?:apa\\s+pun)?|kata\\s+demi\\s+kata)\\.?$")
    private val NO_TEXT_ABSENT = Regex(
        "(?i)^(?:nihil|tidak ada(?: tulisan| teks| tugas)?|kosong|none|-+)\\.?$|" +
            "tidak (?:memuat|ada|terdapat|ditemukan|terlihat) (?:tulisan|teks|tugas)"
    )
    private val SCENE_DESC = Regex(
        "(?i)^(?:(?:ini\\s+adalah|terlihat)\\s+)?(?:gambar|foto|potret|ilustrasi|adegan)\\b.*?(?:menampilkan|menunjukkan|memperlihatkan|menggambarkan|berisi|adalah|di\\s+pantai|di\\s+luar)|" +
            "^(?:di dalam )?(?:gambar|foto) ini[, ]|" +
            "^(?:terlihat|tampak|ada)\\s+seorang\\s+(?:wanita|perempuan|pria|laki-laki|gadis|anak|orang)|" +
            "^seorang\\s+(?:wanita|perempuan|pria|laki-laki|gadis|anak|model)\\s+(?:yang\\s+)?(?:sedang|berdiri|duduk|berpose|tersenyum|berada|memakai|mengenakan)|" +
            "^(?:pemandangan|suasana)\\s+(?:pantai|laut|gunung|alam)"
    )
    private val SPOKEN_LEAD = Regex(
        "(?i)^(?:(?:tolong|coba|mohon|jangan lupa|ingatkan(?: saya| aku| kami| kita)?|ingetin(?: saya| aku)?|" +
            "pasang pengingat|buat pengingat|setel pengingat|catat(?:kan)?|catet(?:in)?|saya mau|aku mau|saya harus|aku harus|harus|mau|nanti|ntar)\\s+)+"
    )
    private val SPOKEN_TAIL = Regex("(?i)\\s+(?:ya|dong|donk|deh|saja|aja)$")
    private val DANGLING_TIME_PREP = Regex("(?i)\\s+(?:pada|pukul|jam|di|sebelum|sampai)$")
    private val LEADING_TIME_PREP = Regex("(?i)^(?:pada|di|untuk)\\s+")
    private val PROSE_MARKERS = Regex("(?i)\\b(?:tadi|kemarin|katanya|bilang|sepertinya|kayaknya|karena|menurut|padahal|kalau|jika|apakah|bagaimana|gimana|kenapa|siapa|berapa|dimana|sedang|tampak|terlihat|foto|gambar)\\b")
    private val TASK_VERB =
        "(?:buat(?:kan)?|bikin(?:kan)?|beli(?:kan)?|bayar(?:kan)?|cek|periksa|telepon|hubungi|kabari|kirim(?:kan)?|ambil(?:kan)?|" +
            "antar(?:kan)?|jemput|siapkan|rapikan|bersihkan|cuci|ganti|isi|tulis|catat|baca|pelajari|kerjakan|selesaikan|" +
            "transfer|pesan|unduh|cetak|masak|potong|angkat|kunci|matikan|nyalakan|bawa|pakai|minum|datang|pergi|berangkat|" +
            "kumpul|rapat|meeting|belanja|olahraga|latihan|belajar|kontrol|wawancara|presentasi|ketemu|" +
            "kembalikan|perbaiki|servis|service|urus|daftar|perpanjang|tagih|konfirmasi|tanyakan|lanjutkan|ingatkan)\\b"
    private val TASK_SPLIT = Regex(
        "(?i)(?:\\s*[,;\\n]\\s*(?:(?:dan|sama|serta|lalu|kemudian|terus|habis itu|setelah itu)\\s+)?|" +
            "\\s+(?:lalu|kemudian|terus|habis itu|setelah itu)\\s+|" +
            "\\s+(?:dan|sama|serta)\\s+(?=$TASK_VERB))"
    )
    private val LEADING_CONJ = Regex("(?i)^(?:dan|sama|serta|lalu|kemudian|terus)\\s+")
    private val TASK_LINE_PREFIX = Regex("^(?:\\d+[.)]|[-*•☐☑])\\s*")
    private val NON_TASK_CHAT = Regex("(?i)^(?:halo|hai|tes|test|cek satu dua|apa kabar|terima kasih|oke|ok|siap)\\b")

    /** Membersihkan pantulan instruksi prompt dan kutip dari hasil transkripsi model suara. */
    fun cleanTranscript(raw: String): String {
        var s = cleanBlock(raw).trim().trim('"', '“', '”', '\'')
        repeat(3) {
            val prev = s
            s = AUDIO_ECHO_LEAD.replace(s, "").trim()
            s = AUDIO_ECHO_TAIL.replace(s, "").trim()
            s = s.trim('"', '“', '”', '\'')
            if (s == prev) return s
        }
        return s
    }

    /** True bila keluaran OCR menyatakan tidak ada teks di gambar atau model justru mendeskripsikan foto biasa. */
    fun isNoTextOrSceneDescription(raw: String): Boolean {
        val s = cleanBlock(raw).trim()
        if (s.length < 2) return true
        return NO_TEXT_ABSENT.containsMatchIn(s) || SCENE_DESC.containsMatchIn(s)
    }

    /** True bila keluaran ekstraksi tugas menyatakan tidak ada tugas. */
    fun isNoTasks(raw: String): Boolean {
        val s = cleanBlock(raw).trim()
        if (s.isEmpty()) return true
        val lines = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.isEmpty() || lines.all { line ->
            val bare = line.removePrefix("☐").removePrefix("•").trim()
            bare.length < 2 || NO_TEXT_ABSENT.containsMatchIn(bare) || SCENE_DESC.containsMatchIn(bare)
        }
    }

    /** Menghapus frasa waktu [phrase] dari satu kalimat atau baris tugas beserta kata depan yang menggantung. */
    private fun stripTimePhrase(text: String, phrase: String?): String {
        if (phrase.isNullOrBlank()) return text
        val escaped = Regex.escape(phrase.trim())
        var s = text.replace(Regex("(?i)\\b$escaped\\b"), " ")
        if (s == text) s = text.replace(phrase.trim(), " ", ignoreCase = true)
        s = s.replace(Regex("\\s+"), " ").trim()
        s = DANGLING_TIME_PREP.replace(s, "").trim()
        s = LEADING_TIME_PREP.replace(s, "").trim()
        return s.trim(',', ';', '-', ':', ' ')
    }

    /**
     * Ekstraksi cepat tanpa LLM tambahan untuk ucapan atau perintah langsung (mis. "nanti sore buat catatan pribadi"
     * atau "besok jam 7 malam bayar listrik dan beli token"). Mengembalikan null untuk teks naratif/panjang
     * agar ditangani oleh LLM teks.
     */
    fun extractDirectTasksAndTime(raw: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): ExtractedTasks? {
        val cleaned = cleanTranscript(raw)
        if (cleaned.isEmpty() || isNoTextOrSceneDescription(cleaned) || NON_TASK_CHAT.containsMatchIn(cleaned)) return null
        val found = TimeParse.best(cleaned, now)
        var rest = stripTimePhrase(cleaned, found?.phrase)
        rest = SPOKEN_TAIL.replace(SPOKEN_LEAD.replace(rest, ""), "").trim()
        // "ingatkan buat bayar listrik" -> buang "buat/untuk" hanya jika diikuti kata kerja tugas lain ("bayar"),
        // sedangkan "buat catatan pribadi" tetap mempertahankan "buat".
        rest = rest.replace(Regex("(?i)^(?:untuk|buat)\\s+(?=$TASK_VERB)"), "")
        if (rest.isBlank()) return null

        val wordCount = wordCount(rest)
        if (wordCount > 14 || PROSE_MARKERS.containsMatchIn(rest)) return null
        val startsWithAction = Regex("(?i)^$TASK_VERB").containsMatchIn(rest)
        if (!startsWithAction) return null

        val items = rest.split(TASK_SPLIT)
            .map { TASK_LINE_PREFIX.replace(it.trim(), "") }
            .map { LEADING_CONJ.replace(it.trim(), "") }
            .map { SPOKEN_TAIL.replace(SPOKEN_LEAD.replace(it.trim(',', ';', '.', '-', ' '), ""), "").trim() }
            .filter { it.length >= 2 && !NO_TEXT_ABSENT.containsMatchIn(it) }
            .map { "☐ " + it.replaceFirstChar { ch -> ch.uppercase() } }
            .distinct()
        return if (items.isEmpty()) null else ExtractedTasks(items, found?.at, found?.phrase)
    }

    /**
     * Membersihkan keluaran LLM dari [tasksFromTextPrompt] dan memasangkannya dengan waktu dari [foundTime].
     */
    fun parseExtractedTasks(raw: String, foundTime: TimeParse.Found? = null): ExtractedTasks {
        val cleaned = cleanBlock(raw)
        if (isNoTasks(cleaned)) return ExtractedTasks(emptyList(), foundTime?.at, foundTime?.phrase)
        val items = cleaned.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Daftar tugas:", ignoreCase = true) }
            .map { TASK_LINE_PREFIX.replace(it, "").trim() }
            .map { stripTimePhrase(it, foundTime?.phrase) }
            .map { AUDIO_ECHO_TAIL.replace(it, "").trim() }
            .filter { it.length >= 2 && !NO_TEXT_ABSENT.containsMatchIn(it) && !SCENE_DESC.containsMatchIn(it) }
            .map { "☐ " + it.replaceFirstChar { ch -> ch.uppercase() } }
            .distinct()
        return ExtractedTasks(items, foundTime?.at, foundTime?.phrase)
    }
}
