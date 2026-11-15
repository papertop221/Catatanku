package com.catatanku.app

/** Aksi bantu-tulis. [replaces] = hasilnya wajar menggantikan isi (bukan ditambahkan). */
enum class AiAction(val label: String, val hint: String, val replaces: Boolean = false, val forTitle: Boolean = false) {
    Continue("Lanjutkan tulisan", "Menulis satu–dua paragraf berikutnya"),
    Fix("Perbaiki ejaan", "Ejaan dan tata bahasa, makna tetap", replaces = true),
    Tidy("Rapikan struktur", "Dikelompokkan dengan subjudul dan poin", replaces = true),
    Summarize("Ringkas", "Menjadi 3–5 poin"),
    Tasks("Ambil daftar tugas", "Tugas dijadikan daftar centang"),
    Title("Buat judul", "Maksimal enam kata", forTitle = true),
    Custom("Instruksi bebas", ""),
}

/** Prompt dan pengurai keluaran LLM. Murni Kotlin agar bisa diuji tanpa perangkat. */
object AiText {
    /** Batas teks catatan yang dikirim ke model; model kecil punya jendela konteks sempit. */
    @Volatile var maxInput = 2800

    fun clip(text: String, max: Int = maxInput, fromEnd: Boolean = false): String =
        if (text.length <= max) text else if (fromEnd) text.takeLast(max) else text.take(max)

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

    /** Membuang blok penalaran (<think>…</think>) yang dikeluarkan sebagian model. */
    fun strip(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?(</think>|$)"), "").trim()

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
            .replace(Regex("^(judul|title)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trim { it in " \"'`*#“”" }.trimEnd('.').trim { it in " \"'`*#“”" }

    /** Menjadikan keluaran model teks polos: buang penalaran, pagar kode, dan sisa Markdown. */
    fun cleanBlock(raw: String): String =
        strip(raw).removePrefix("```").removeSuffix("```").lines().joinToString("\n") { line ->
            val indent = line.takeWhile { it == ' ' }.length
            var t = line.trim()
            t = t.replace(Regex("^#{1,6}\\s+"), "")                      // judul Markdown
            t = t.replace(Regex("^[*-]\\s+"), "• ")                      // poin Markdown
            t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
            t = t.replace(Regex("__(.+?)__"), "$1").replace("`", "")
            (if (indent >= 2 && t.startsWith("• ")) "   " else "") + t
        }.trim()

    fun askPrompt(question: String, sources: List<Pair<String, String>>): String {
        val per = ((if (maxInput > 4000) 4000 else 2400) / sources.size.coerceAtLeast(1)).coerceAtMost(if (maxInput > 4000) 1500 else 900)
        val ctx = sources.mapIndexed { i, (t, b) -> "[${i + 1}] ${t.ifBlank { "(tanpa judul)" }.take(80)}\n${clip(b, per)}" }
            .joinToString("\n\n")
        return "Jawab pertanyaan HANYA berdasarkan catatan pribadi di bawah. Tulis jawabannya dalam satu atau dua " +
            "kalimat lengkap yang memuat faktanya, lalu akhiri dengan nomor catatan sumbernya, misalnya [1]. " +
            "Jika jawabannya tidak ada di catatan, katakan bahwa itu tidak ditemukan di catatan; jangan mengarang. " +
            "Gunakan bahasa pertanyaan, teks polos.\n\n$ctx\n\nPertanyaan: ${question.take(300)}\nJawaban lengkap:"
    }

    fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

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
