package com.catatanku.app.domain.usecase

/**
 * Format ringan dan tautan antarcatatan. Murni Kotlin agar bisa diuji di JVM.
 *
 * Sintaks yang dikenali (sengaja sedikit, mirip Markdown agar tetap terbaca sebagai teks polos):
 * - `# ` / `## ` di awal baris: subjudul
 * - `**tebal**`, `_miring_`
 * - `> ` di awal baris: kutipan
 * - `[[Judul catatan]]`: tautan ke catatan lain
 *
 * Penanda tidak pernah disembunyikan dari teks (editor memakai pemetaan offset identitas); penanda hanya diredupkan.
 */
object TextFormat {
    enum class Kind { Heading1, Heading2, Bold, Italic, Quote, Link, Marker, Match }

    data class Span(val start: Int, val end: Int, val kind: Kind)

    /** Batas teks yang diberi gaya, agar jalur mengetik tetap murah pada catatan sangat panjang. */
    const val STYLE_LIMIT = 20_000

    private val BOLD = Regex("\\*\\*([^*\\n]+?)\\*\\*")
    private val ITALIC = Regex("(?<![\\w_])_([^_\\n]+?)_(?![\\w_])")
    private val LINK = Regex("\\[\\[([^\\[\\]\\n]{1,120})]]")

    fun spans(text: String, limit: Int = STYLE_LIMIT): List<Span> {
        val s = if (text.length > limit) text.substring(0, limit) else text
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        var pos = 0
        while (pos <= s.length) {
            val end = s.indexOf('\n', pos).let { if (it < 0) s.length else it }
            when {
                s.startsWith("## ", pos) && end > pos + 3 -> { out += Span(pos, pos + 3, Kind.Marker); out += Span(pos + 3, end, Kind.Heading2) }
                s.startsWith("# ", pos) && end > pos + 2 -> { out += Span(pos, pos + 2, Kind.Marker); out += Span(pos + 2, end, Kind.Heading1) }
                s.startsWith("> ", pos) && end > pos + 2 -> { out += Span(pos, pos + 2, Kind.Marker); out += Span(pos + 2, end, Kind.Quote) }
            }
            if (end >= s.length) break
            pos = end + 1
        }
        if ("**" in s) BOLD.findAll(s).forEach { m ->
            out += Span(m.range.first, m.range.first + 2, Kind.Marker)
            out += Span(m.range.first + 2, m.range.last - 1, Kind.Bold)
            out += Span(m.range.last - 1, m.range.last + 1, Kind.Marker)
        }
        if ('_' in s) ITALIC.findAll(s).forEach { m ->
            out += Span(m.range.first, m.range.first + 1, Kind.Marker)
            out += Span(m.range.first + 1, m.range.last, Kind.Italic)
            out += Span(m.range.last, m.range.last + 1, Kind.Marker)
        }
        if ("[[" in s) LINK.findAll(s).forEach { m ->
            out += Span(m.range.first, m.range.first + 2, Kind.Marker)
            out += Span(m.range.first + 2, m.range.last - 1, Kind.Link)
            out += Span(m.range.last - 1, m.range.last + 1, Kind.Marker)
        }
        return out
    }

    /** Teks tanpa penanda format, untuk cuplikan di daftar dan pratinjau. */
    fun plain(text: String): String = text
        .replace(Regex("(?m)^#{1,2} "), "")
        .replace(Regex("(?m)^> "), "")
        .replace(BOLD, "$1")
        .replace(ITALIC, "$1")
        .replace(LINK, "$1")

    // ---------- Perintah format (dipakai bilah alat editor) ----------

    data class Edit(val text: String, val selStart: Int, val selEnd: Int)

    private fun lineStart(text: String, i: Int) = text.lastIndexOf('\n', (i - 1).coerceAtLeast(-1)).let { if (i == 0) 0 else it + 1 }

    /** Menyalakan/mematikan awalan baris (`## `, `> `) pada baris tempat kursor berada. */
    fun toggleLinePrefix(text: String, cursor: Int, prefix: String): Edit {
        val c = cursor.coerceIn(0, text.length)
        val ls = lineStart(text, c)
        val others = listOf("## ", "# ", "> ").filter { it != prefix }
        val existing = (listOf(prefix) + others).firstOrNull { text.startsWith(it, ls) }
        return when (existing) {
            prefix -> Edit(text.removeRange(ls, ls + prefix.length), (c - prefix.length).coerceAtLeast(ls), (c - prefix.length).coerceAtLeast(ls))
            null -> Edit(text.substring(0, ls) + prefix + text.substring(ls), c + prefix.length, c + prefix.length)
            else -> {
                val t = text.substring(0, ls) + prefix + text.substring(ls + existing.length)
                val nc = (c - existing.length + prefix.length).coerceAtLeast(ls + prefix.length)
                Edit(t, nc, nc)
            }
        }
    }

    /** Membungkus pilihan dengan penanda (`**`, `_`). Tanpa pilihan: menyisipkan sepasang penanda dan menaruh kursor di tengah. */
    fun wrap(text: String, selStart: Int, selEnd: Int, mark: String): Edit {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val m = mark.length
        if (a >= m && b + m <= text.length && text.substring(a - m, a) == mark && text.substring(b, b + m) == mark) {
            val t = text.removeRange(b, b + m).removeRange(a - m, a)
            return Edit(t, a - m, b - m)
        }
        val t = text.substring(0, a) + mark + text.substring(a, b) + mark + text.substring(b)
        return Edit(t, a + m, b + m)
    }

    // ---------- Tautan antarcatatan ----------

    /** Judul tautan `[[…]]` yang memuat posisi [offset], atau null. */
    fun linkAt(text: String, offset: Int): String? {
        if ("[[" !in text) return null
        val o = offset.coerceIn(0, text.length)
        val ls = text.lastIndexOf('\n', (o - 1).coerceAtLeast(0)).let { if (o == 0 || it < 0) 0 else it + 1 }
        val le = text.indexOf('\n', o).let { if (it < 0) text.length else it }
        return LINK.findAll(text.substring(ls, le)).firstOrNull { m -> o - ls in m.range.first..m.range.last + 1 }?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Teks yang sedang diketik setelah `[[` yang belum ditutup pada baris kursor, atau null. */
    fun partialLink(text: String, cursor: Int): String? {
        val c = cursor.coerceIn(0, text.length)
        val ls = text.lastIndexOf('\n', (c - 1).coerceAtLeast(0)).let { if (c == 0 || it < 0) 0 else it + 1 }
        val before = text.substring(ls, c)
        val open = before.lastIndexOf("[[")
        if (open < 0) return null
        val typed = before.substring(open + 2)
        if ("]]" in typed || typed.length > 60) return null
        return typed
    }

    /** Melengkapi `[[sebagian` di kursor menjadi `[[Judul]]`. */
    fun completeLink(text: String, cursor: Int, title: String): Edit {
        val typed = partialLink(text, cursor) ?: return Edit(text, cursor, cursor)
        val start = cursor - typed.length
        val after = text.substring(cursor)
        val closeSkip = if (after.startsWith("]]")) 2 else 0
        val t = text.substring(0, start) + title + "]]" + after.substring(closeSkip)
        val nc = start + title.length + 2
        return Edit(t, nc, nc)
    }

    /** Semua judul yang ditautkan dari teks (tanpa duplikat, huruf kecil-besar diabaikan). */
    fun links(text: String): List<String> =
        if ("[[" !in text) emptyList() else LINK.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.toList()

    fun linksTo(text: String, title: String): Boolean =
        title.isNotBlank() && "[[" in text && links(text).any { it.equals(title.trim(), ignoreCase = true) }

    // ---------- Cari & ganti ----------

    fun matches(text: String, query: String, ignoreCase: Boolean = true, max: Int = 500): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var i = text.indexOf(query, 0, ignoreCase)
        while (i >= 0 && out.size < max) {
            out += i until i + query.length
            i = text.indexOf(query, i + query.length, ignoreCase)
        }
        return out
    }

    fun replaceAll(text: String, query: String, replacement: String, ignoreCase: Boolean = true): Pair<String, Int> {
        if (query.isEmpty()) return text to 0
        val hits = matches(text, query, ignoreCase, Int.MAX_VALUE)
        if (hits.isEmpty()) return text to 0
        val sb = StringBuilder(text.length)
        var last = 0
        for (r in hits) { sb.append(text, last, r.first).append(replacement); last = r.last + 1 }
        sb.append(text, last, text.length)
        return sb.toString() to hits.size
    }

    // ---------- Ekspor ----------

    /** Isi berkas ekspor satu catatan. Markdown memakai judul `# `; teks polos membuang penanda format. */
    fun export(title: String, body: String, tags: List<String>, markdown: Boolean): String {
        val tagLine = if (tags.isEmpty()) "" else "\n\n" + tags.joinToString(" ") { "#$it" }
        return if (markdown) (if (title.isNotBlank()) "# ${title.trim()}\n\n" else "") + body.trimEnd() + tagLine + "\n"
        else (if (title.isNotBlank()) title.trim() + "\n\n" else "") + plain(body).trimEnd() + tagLine + "\n"
    }

    /** Nama berkas aman dari judul. */
    fun fileName(title: String, ext: String): String {
        val base = title.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
        return (base.ifEmpty { "catatan" }) + "." + ext
    }
}
