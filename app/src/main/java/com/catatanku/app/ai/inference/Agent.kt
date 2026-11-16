package com.catatanku.app.ai.inference

import com.catatanku.app.domain.model.AgentStep
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.domain.usecase.TextTools
import com.catatanku.app.domain.usecase.TimeParse
import java.time.LocalDateTime

/**
 * Asisten perintah ("agen ringan"): kalimat seperti "ingatkan saya bayar listrik besok jam 7 malam" diubah menjadi
 * paling banyak [MAX_STEPS] langkah yang ditinjau pengguna sebelum dijalankan.
 */
object Agent {
    const val PREV = AgentStep.PREV_TARGET
    const val MAX_STEPS = 3

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val DAY = "senin|selasa|rabu|kamis|jum'?at|sabtu|ahad|minggu"
    private const val VERB =
        "(?:catat(?:kan|in)?|catet(?:in)?|tulis(?:kan|in)?|(?:buat(?:kan)?|bikin(?:kan)?|pasang|setel)\\s+(?:catatan|alarm|pengingat)|" +
            "ingatkan|ingetin|ingatin|tambah(?:kan|in)?|masukkan|masukin|sisipkan|centang|ceklis|selesaikan|(?:beri|kasih|pasang)\\s+tag|" +
            "tandai|pindah(?:kan|in)?|sematkan|lepas(?:kan)?\\s+sematan|arsipkan|hapus|buang|batalkan|matikan)\\b"
    private val JOIN = re("(?:\\s*[,;]\\s*(?:(?:dan|lalu|kemudian|terus)\\s+)?|\\s+(?:dan|lalu|kemudian|terus)\\s+)(?=$VERB)")
    private val POLITE = re("^(?:(?:tolong|coba|mohon|please|plis|ayo)\\s+)+")
    private val POLITE_END = re("(?:\\s+(?:ya|dong|donk|deh|please|plis))+$")

    private val R_ALARM = re("^(?:pasang|setel|buat(?:kan)?|bikin(?:kan)?)\\s+(alarm|pengingat)\\s+(?:(?:untuk|buat)\\s+)?(.+)$")
    private val R_UNREMIND = re("^(?:hapus|batalkan|matikan)\\s+(?:pengingat|alarm)\\s+(?:(?:di|dari|untuk|pada)\\s+)?(?:catatan\\s+)?(.+)$")
    private val R_REMIND = re("^(?:ingatkan|ingetin|ingatin)\\s+(?:(?:saya|aku|gue|gw)\\s+)?(.+)$")
    private val R_CREATE = re("^(?:catat(?:kan|in)?|catet(?:in)?|tulis(?:kan|in)?|(?:buat(?:kan)?|bikin(?:kan)?|tambah(?:kan|in)?)\\s+catatan(?:\\s+baru)?|catatan\\s+baru)\\b\\s*[:,-]?\\s*(.+)$")
    private val R_CHECK = re("^(?:centang|ceklis|selesaikan|tandai\\s+selesai)\\s+(?:tugas\\s+)?(.+)\\s+(?:di|pada|dari)\\s+(?:catatan\\s+)?(.+)$")
    private val R_TAG_A = re("^(?:beri|kasih|tambah(?:kan)?|pasang)\\s+tag\\s+(.+?)\\s+(?:ke|di|pada|untuk)\\s+(?:catatan\\s+)?(.+)$")
    private val R_TAG_B = re("^(?:tandai|tag)\\s+(?:catatan\\s+)?(.+?)\\s+(?:dengan|sebagai)\\s+(?:tag\\s+)?(.+)$")
    private val R_MOVE = re("^(?:pindah(?:kan|in)?|masukkan|masukin)\\s+(?:catatan\\s+)?(.+?)\\s+ke\\s+(?:dalam\\s+)?folder\\s+(.+)$")
    private val R_APPEND = re("^(?:tambah(?:kan|in)?|masukkan|masukin|sisipkan)\\s+(tugas\\s+)?(.+)\\s+(?:ke|di|pada)\\s+catatan\\s+(.+)$")
    private val R_APPEND_KE = re("^(?:tambah(?:kan|in)?|masukkan|masukin|sisipkan)\\s+(tugas\\s+)?(.+)\\s+ke\\s+(.+)$")
    private val R_UNPIN = re("^(?:lepas(?:kan)?\\s+(?:sematan|pin)|unpin)(?:\\s+(?:(?:dari|di)\\s+)?(?:catatan\\s+)?(.+))?$")
    private val R_PIN = re("^(?:sematkan|pin)(?:\\s+(?:catatan\\s+)?(.+))?$")
    private val R_ARCHIVE = re("^(?:arsipkan|arsip)(?:\\s+(?:catatan\\s+)?(.+))?$")
    private val R_TRASH = re("^(?:hapus|buang)(?:\\s+(?:catatan\\s+)?(.+))?$")

    private val R_REP_UNIT = re("\\b(?:se)?tiap\\s+(hari(?!\\s+(?:$DAY)\\b)|minggu(?!\\s+(?:pagi|siang|sore|petang|malam|jam|pukul)\\b)|pekan|bulan)\\b|\\b(harian|mingguan|bulanan)\\b")
    private val R_REP_DAY = re("\\b(?:se)?tiap\\s+(?=(?:hari\\s+)?(?:$DAY)\\b)")
    private val R_REP_DATE = re("\\b(?:se)?tiap\\s+(?=(?:tanggal|tgl)\\b)")
    private val R_ALARM_WORD = re("\\s*\\b(?:(?:pakai|dengan|gaya|bunyi|mode)\\s+)?alarm\\b")
    private val R_FOLDER = re("[,;]?\\s+(?:di|ke|dalam|pada)\\s+folder\\s+([\\p{L}\\p{N} _-]{1,30})$")
    private val R_TAGS = re("[,;]?\\s+(?:(?:dengan|pakai|beri|kasih)\\s+)?tag\\s+(.+)$")
    private val R_HASHTAG = re("(?:^|\\s)#([\\p{L}\\p{N}_-]{2,28})")
    private val R_HEAD = Regex("^([^:\\n]{2,40}):\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
    private val R_LEAD = re("^(?:untuk|buat|agar|supaya|tentang|soal|bahwa)\\s+")
    private val R_TAIL = re("\\s+(?:pada|di|untuk|buat|jam|pukul|tiap|setiap|nanti)$")
    private val R_TARGET_LEAD = re("^(?:(?:aja|saja|yang|tentang|soal|berjudul|judul|catatan)\\s+)+")
    private val R_TARGET_TAIL = re("\\s+(?:itu|ini|tadi|tersebut|(?:sudah|udah|telah)\\s+(?:selesai|beres|kelar))$")
    private val PRONOUNS = setOf("", "itu", "ini", "tadi", "tersebut", "nya", "catatan itu", "catatan ini", "catatan tadi", "catatannya", "catatan tersebut")

    private fun cap(s: String) = s.trim().replaceFirstChar { it.uppercase() }

    private fun tidy(text: String): String {
        var t = text.replace(Regex("\\s+"), " ")
        while (true) {
            val next = R_TAIL.replace(R_LEAD.replace(t.trim().trim(',', ';', '-', ':', '.', ' '), ""), "")
            if (next == t) return t
            t = next
        }
    }

    private fun target(text: String): String {
        val t = R_TARGET_TAIL.replace(R_TARGET_LEAD.replace(text.trim().trim('"', '\'', '“', '”', '.', ' '), ""), "").trim().trim('"', '\'', '“', '”')
        return if (t.lowercase() in PRONOUNS) PREV else t
    }

    private fun tags(text: String): List<String> =
        text.split(Regex("\\s*(?:,|;|\\sdan\\s|\\s(?=#))\\s*")).map(TextTools::normalizeTag).filter { it.length >= 2 }.distinct().take(5)

    private fun items(text: String): List<String> =
        text.split(Regex("\\s*(?:,|;|\\n|\\sdan\\s)\\s*")).map { it.trim().trim('.') }.filter { it.isNotEmpty() }

    private fun takeRepeat(text: String): Pair<Repeat, String> {
        R_REP_UNIT.find(text)?.let { m ->
            val unit = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
            val r = when { unit.startsWith("hari") -> Repeat.Daily; unit.startsWith("bulan") -> Repeat.Monthly; else -> Repeat.Weekly }
            return r to text.removeRange(m.range)
        }
        R_REP_DAY.find(text)?.let { return Repeat.Weekly to text.removeRange(it.range) }
        R_REP_DATE.find(text)?.let { return Repeat.Monthly to text.removeRange(it.range) }
        return Repeat.None to text
    }

    private fun noteOf(text: String): Pair<String, String> {
        val c = text.trim()
        R_HEAD.matchEntire(c)?.let { m ->
            val head = m.groupValues[1].trim(); val rest = m.groupValues[2].trim()
            val list = items(rest)
            return if (list.size >= 2) cap(head) to list.joinToString("\n") { "☐ " + cap(it) }
            else cap(head) to cap(rest)
        }
        if (',' in c) {
            val list = items(c)
            if (list.size >= 2 && list.all { it.length in 2..60 }) {
                return cap(list.first()) to list.joinToString("\n") { "☐ " + cap(it) }
            }
        }
        return if (c.length <= 60 && '\n' !in c) cap(c) to "" else "" to cap(c)
    }

    private fun takeMeta(raw: String): Triple<String, List<String>, String> {
        var c = raw.trim()
        val found = ArrayList<String>()
        R_HASHTAG.findAll(c).forEach { found += it.groupValues[1] }
        c = R_HASHTAG.replace(c, "").trim()
        var folder = ""
        repeat(2) {
            R_FOLDER.find(c)?.let { m -> folder = m.groupValues[1].trim(); c = c.removeRange(m.range).trim() }
            R_TAGS.find(c)?.takeIf { it.range.first > 0 }?.let { m -> found += tags(m.groupValues[1]); c = c.removeRange(m.range).trim() }
        }
        c = c.trim(',', ';', ' ')
        val cleanTags = found.map(TextTools::normalizeTag).filter { it.length >= 2 }.distinct().take(5)
        return Triple(c, cleanTags, folder)
    }

    private fun remindLike(rest: String, alarmAsked: Boolean, hasPrev: Boolean, now: LocalDateTime): AgentStep? {
        val (withoutMeta, foundTags, folder) = takeMeta(rest)
        val (repeat, r) = takeRepeat(withoutMeta)
        val alarm = alarmAsked || R_ALARM_WORD.containsMatchIn(r)
        val text = R_ALARM_WORD.replace(r, "")
        val found = TimeParse.best(text, now)
        val what = tidy(if (found != null) text.replaceFirst(found.phrase, " ") else text)
        if (what.lowercase().startsWith("catatan ") && what.length > 8 && foundTags.isEmpty() && folder.isEmpty())
            return AgentStep.Remind(target(what.substring(8)), found?.at, repeat, alarm)
        if (what.lowercase() in PRONOUNS) return if (hasPrev) AgentStep.Remind(PREV, found?.at, repeat, alarm) else null
        val (title, body) = noteOf(what)
        return AgentStep.Create(title, body, tags = foundTags, folder = folder, at = found?.at, repeat = repeat, alarm = alarm, wantsReminder = true)
    }

    private fun create(rest: String): AgentStep? {
        val (c, foundTags, folder) = takeMeta(rest)
        if (c.isEmpty()) return null
        val (title, body) = noteOf(c)
        return AgentStep.Create(title, body, foundTags, folder)
    }

    private fun one(raw: String, hasPrev: Boolean, now: LocalDateTime): AgentStep? =
        match(raw, hasPrev, now)?.takeIf { it.target != PREV || hasPrev }

    private fun match(raw: String, hasPrev: Boolean, now: LocalDateTime): AgentStep? {
        if (raw.trim().endsWith("?")) return null
        val s = POLITE_END.replace(POLITE.replace(raw.trim().trim('.', '!', ' '), ""), "").trim()
        fun m(r: Regex) = r.matchEntire(s)?.groupValues
        m(R_ALARM)?.let { return remindLike(it[2], it[1].equals("alarm", ignoreCase = true), hasPrev, now) }
        m(R_UNREMIND)?.let { return AgentStep.Unremind(target(it[1])) }
        m(R_REMIND)?.let { return remindLike(it[1], false, hasPrev, now) }
        m(R_CREATE)?.let { return create(it[1]) }
        m(R_CHECK)?.let { return AgentStep.Check(target(it[2]), it[1].trim().trim('"', '“', '”')) }
        m(R_TAG_A)?.let { g -> return tags(g[1]).takeIf { it.isNotEmpty() }?.let { AgentStep.Tag(target(g[2]), it) } }
        m(R_TAG_B)?.let { g -> return tags(g[2]).takeIf { it.isNotEmpty() }?.let { AgentStep.Tag(target(g[1]), it) } }
        m(R_MOVE)?.let { return AgentStep.Move(target(it[1]), it[2].trim().trim('"', '“', '”').take(30)) }
        (m(R_APPEND) ?: m(R_APPEND_KE))?.let { return AgentStep.Append(target(it[3]), it[2].trim().trim('"', '“', '”'), it[1].isNotEmpty()) }
        m(R_UNPIN)?.let { return AgentStep.Pin(target(it[1]), false) }
        m(R_PIN)?.let { return AgentStep.Pin(target(it[1]), true) }
        m(R_ARCHIVE)?.let { return AgentStep.Archive(target(it[1])) }
        m(R_TRASH)?.let { return AgentStep.Trash(target(it[1])) }
        return null
    }

    fun parse(command: String, now: LocalDateTime = LocalDateTime.now()): List<AgentStep>? {
        val text = command.trim().replace(Regex("\\s*\\n\\s*"), ", ")
        if (text.length !in 4..600) return null
        val parts = ArrayList<String>(); val seps = ArrayList<String>()
        var start = 0
        JOIN.findAll(text).forEach { m -> parts += text.substring(start, m.range.first); seps += m.value; start = m.range.last + 1 }
        parts += text.substring(start)
        val steps = ArrayList<AgentStep>()
        var raw = ""
        parts.forEachIndexed { i, p ->
            val step = one(p, steps.isNotEmpty(), now)
            when {
                step != null && steps.size < MAX_STEPS -> { steps += step; raw = p }
                step != null -> return null
                steps.isEmpty() -> return null
                else -> {
                    raw += seps[i - 1] + p
                    steps[steps.lastIndex] = one(raw, steps.size > 1, now) ?: return null
                }
            }
        }
        return steps.takeIf { it.isNotEmpty() }
    }

    fun plannerPrompt(command: String): String =
        "Ubah permintaan pengguna aplikasi catatan menjadi perintah baku. Satu perintah per baris, paling banyak $MAX_STEPS baris, " +
            "tanpa penjelasan. Bentuk yang boleh dipakai:\n" +
            "catat ISI\n" +
            "catat JUDUL: BUTIR, BUTIR, BUTIR (untuk daftar)\n" +
            "ingatkan HAL WAKTU\n" +
            "ingatkan catatan KATA KUNCI WAKTU\n" +
            "tambahkan TEKS ke catatan KATA KUNCI\n" +
            "tambahkan tugas TUGAS ke catatan KATA KUNCI\n" +
            "centang TUGAS di catatan KATA KUNCI (tugas sudah selesai)\n" +
            "beri tag TAG ke catatan KATA KUNCI\n" +
            "pindahkan catatan KATA KUNCI ke folder NAMA\n" +
            "sematkan catatan KATA KUNCI (taruh paling atas)\n" +
            "arsipkan catatan KATA KUNCI\n" +
            "hapus catatan KATA KUNCI\n" +
            "\"catatan itu\" berarti catatan dari baris sebelumnya. Salin kata waktu PERSIS seperti ditulis pengguna, termasuk kata " +
            "\"tanggal\", \"besok\", \"lusa\", nama hari, dan jamnya; jangan menghitung tanggal. Jangan menambah hal yang tidak diminta. " +
            "Jika permintaan itu pertanyaan, pencarian, atau tidak memuat perintah, jawab: -\n\n" +
            "Contoh:\n" +
            "Permintaan: jangan lupa lusa jam 3 sore ambil paket\nPerintah:\ningatkan ambil paket lusa jam 3 sore\n\n" +
            "Permintaan: tolong tulis daftar bawaan mendaki: tenda, kompor, senter\nPerintah:\ncatat bawaan mendaki: tenda, kompor, senter\n\n" +
            "Permintaan: di catatan proyek rumah, cat tembok udah kelar, terus kasih label penting dan taruh paling atas\nPerintah:\n" +
            "centang cat tembok di catatan proyek rumah\nberi tag penting ke catatan itu\nsematkan catatan itu\n\n" +
            "Permintaan: tanggal 5 ada arisan jam 4 sore, catat dan ingatkan\nPerintah:\n" +
            "catat arisan tanggal 5 jam 4 sore\ningatkan catatan itu tanggal 5 jam 4 sore\n\n" +
            "Permintaan: di catatan belanja bulanan tambahin sabun cuci\nPerintah:\ntambahkan sabun cuci ke catatan belanja bulanan\n\n" +
            "Permintaan: bayar spp di catatan sekolah anak sudah beres\nPerintah:\ncentang bayar spp di catatan sekolah anak\n\n" +
            "Permintaan: catatan ide lagu kasih label musik\nPerintah:\nberi tag musik ke catatan ide lagu\n\n" +
            "Permintaan: jadwal dokter gigi\nPerintah:\n-\n\n" +
            "Permintaan: bagaimana cara merawat tanaman\nPerintah:\n-\n\n" +
            "Permintaan: ${AiText.sanitizeInput(command).take(300)}\nPerintah:"

    private val R_PLACEHOLDER = Regex("[<>]|\\b(?:KATA KUNCI|WAKTU|BUTIR|JUDUL|TUGAS|TEKS|ISI|HAL|TAG|NAMA)\\b")
    private val R_QUESTION = re("^(?:apa|apakah|kapan|siapa|berapa|bagaimana|gimana|kenapa|mengapa|di\\s*mana|mana|adakah)\\b|\\?\\s*$")
    private val R_SAVE_WORD = re("catat|catet|tulis|simpan|buat|bikin|daftar|tambah|\\bnote")

    fun parseModel(raw: String, command: String, now: LocalDateTime = LocalDateTime.now()): List<AgentStep> {
        if (R_QUESTION.containsMatchIn(command.trim())) return emptyList()
        val mayCreate = R_SAVE_WORD.containsMatchIn(command)
        val steps = ArrayList<AgentStep>()
        AiText.strip(raw).lines()
            .map { it.trim().replace(Regex("^(?:\\d+[.)]|[-*•])\\s*"), "").trim('`', '"', '*', ' ') }
            .filter { it.length >= 4 && !R_PLACEHOLDER.containsMatchIn(it) }
            .forEach { line ->
                if (steps.size >= MAX_STEPS) return@forEach
                var step = one(line, steps.isNotEmpty(), now) ?: return@forEach
                if (step is AgentStep.Create && !step.wantsReminder && !mayCreate) return@forEach
                val prev = steps.lastOrNull()
                if (step is AgentStep.Create && step.wantsReminder && prev is AgentStep.Create && prev.at == null) {
                    val had = TextTools.tokenize(prev.title + " " + prev.body).toSet()
                    val words = TextTools.tokenize(step.title + " " + step.body)
                    if (words.isNotEmpty() && had.containsAll(words)) step = AgentStep.Remind(PREV, step.at, step.repeat, step.alarm)
                }
                steps += step
            }
        return steps
    }

    fun append(body: String, text: String, task: Boolean): String {
        val line = (if (task) "☐ " else "") + cap(text)
        return if (body.isBlank()) line else body.trimEnd() + "\n" + line
    }

    fun findTask(body: String, text: String): String? {
        val words = TextTools.tokenize(text).toSet()
        val needle = text.trim().lowercase()
        return AiText.openTasks(body).map { line ->
            val l = line.lowercase()
            line to (if (needle.length >= 2 && needle in l) 100 else 0) + words.count { it in l }
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun check(body: String, text: String): String? {
        val task = findTask(body, text) ?: return null
        var done = false
        return body.lines().joinToString("\n") { line ->
            if (!done && line.trim() == task) { done = true; line.replaceFirst("☐", "☑") } else line
        }
    }
}
