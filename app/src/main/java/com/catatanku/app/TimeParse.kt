package com.catatanku.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Pengulangan pengingat. Urutan dipakai sebagai nilai di basis data; jangan diubah. */
enum class Repeat(val label: String) {
    None("Sekali"), Daily("Setiap hari"), Weekly("Setiap minggu"), Monthly("Setiap bulan");

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

/**
 * Mengenali waktu dalam catatan berbahasa Indonesia, mis. "besok jam 7 malam", "tanggal 24 jam 10 pagi",
 * "Jumat pukul 15.30", "3 hari lagi". Murni Kotlin dan deterministik: tanpa model, instan, dan bisa diuji.
 * Hasilnya hanya SARAN; pengguna selalu memastikan sebelum pengingat disetel.
 */
object TimeParse {
    data class Found(val at: LocalDateTime, val phrase: String, val hasTime: Boolean)

    private val MONTHS = listOf(
        "januari|jan", "februari|feb|pebruari", "maret|mar", "april|apr", "mei", "juni|jun", "juli|jul",
        "agustus|agu|agt|ags", "september|sept|sep", "oktober|okt", "november|nov|nopember|nop", "desember|des",
    )
    private val MONTH_RE = MONTHS.joinToString("|")
    private fun month(s: String): Int = MONTHS.indexOfFirst { s in it.split('|') } + 1

    private val DAYS = mapOf(
        "senin" to DayOfWeek.MONDAY, "selasa" to DayOfWeek.TUESDAY, "rabu" to DayOfWeek.WEDNESDAY,
        "kamis" to DayOfWeek.THURSDAY, "jumat" to DayOfWeek.FRIDAY, "jum'at" to DayOfWeek.FRIDAY,
        "sabtu" to DayOfWeek.SATURDAY, "ahad" to DayOfWeek.SUNDAY, "minggu" to DayOfWeek.SUNDAY,
    )
    private const val PART = "pagi|siang|sore|petang|malam"

    private class DateHit(val range: IntRange, val date: LocalDate, val part: String? = null, val exact: LocalDateTime? = null, val todayRel: Boolean = false)
    private class TimeHit(val range: IntRange, val time: LocalTime, val keyword: Boolean, val withPart: (String) -> LocalTime?)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val R_DMY = re("(?<![\\d.,/])(\\d{1,2})\\s+($MONTH_RE)\\b\\.?(?:\\s+(\\d{4}))?")
    private val R_TGL = re("\\b(?:tanggal|tgl)\\.?\\s*(\\d{1,2})(?![\\d/.-]|\\s+(?:$MONTH_RE)\\b)")
    private val R_NUM = re("(?<![\\d.,/-])(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{4}|\\d{2}))?(?![\\d/-])")
    private val R_PERIOD = re("\\b(awal|pertengahan|akhir)\\s+(?:bulan\\s+)?($MONTH_RE)\\b")
    private val R_TODAY = re("\\b(?:hari ini|(?:nanti|ntar)\\s+($PART)|($PART)\\s+(?:ini|nanti|ntar)|(?:nanti|ntar)(?=\\s+(?:jam|pukul)))")
    private val R_TOMORROW = re("\\b(besok lusa|lusa|besok|bsk|esok)(?:\\s+($PART))?")
    private val R_NEXT = re("\\b(minggu|pekan|bulan)\\s+depan\\b")
    private val R_IN = re("\\b(\\d{1,3})\\s+(menit|jam|hari|minggu|pekan|bulan)\\s+lagi\\b")
    private val R_DAY = re("\\b(?:hari\\s+)?(senin|selasa|rabu|kamis|jum'?at|sabtu|ahad)\\b(?:\\s+($PART))?")
    private val R_SUNDAY = re("\\b(?:(?:hari|malam)\\s+minggu\\b|minggu(?=\\s+(?:$PART|jam|pukul)\\b))(?:\\s+($PART))?")
    private val R_TIME_KW = re("\\b(?:jam|pukul|pkl|jm)\\.?\\s*(setengah\\s+)?(\\d{1,2})(?:[.:](\\d{2}))?(?!\\d)(?:\\s*(?:wib|wita|wit)\\b)?(?:\\s*($PART))?")
    private val R_TIME_BARE = re("(?<![\\d.:,])(\\d{1,2})(?::(\\d{2})(?![\\d:])|\\.(\\d{2})(?=\\s*(?:wib|wita|wit)\\b))(?:\\s*(?:wib|wita|wit)\\b)?(?:\\s*($PART))?")

    // Waktu salat sebagai penanda jam yang lazim di catatan sehari-hari ("lusa habis magrib"). Jamnya perkiraan.
    private val PRAYER = mapOf(
        "subuh" to LocalTime.of(4, 30), "zuhur" to LocalTime.of(12, 0), "dzuhur" to LocalTime.of(12, 0), "dhuhur" to LocalTime.of(12, 0),
        "lohor" to LocalTime.of(12, 0), "asar" to LocalTime.of(15, 30), "ashar" to LocalTime.of(15, 30),
        "magrib" to LocalTime.of(18, 0), "maghrib" to LocalTime.of(18, 0), "isya" to LocalTime.of(19, 30),
    )
    private val R_PRAYER = re("\\b(?:(habis|abis|setelah|sesudah|selepas|ba'?da|bakda)\\s+)?(${PRAYER.keys.joinToString("|")})\\b")

    private fun clock(h0: Int, m: Int, part: String?, half: Boolean, plain: Boolean): LocalTime? {
        if (h0 > 24 || m > 59) return null
        var h = h0
        when (part?.lowercase()) {
            "pagi" -> if (h == 12) h = 0
            "siang" -> if (h in 1..5) h += 12
            "sore", "petang" -> if (h in 1..11) h += 12
            "malam" -> if (h in 6..11) h += 12 else if (h == 12) return LocalTime.of(23, 59)
            // "jam 3" tanpa keterangan hampir selalu berarti sore, bukan dini hari.
            null -> if (plain && h in 1..6) h += 12
        }
        if (h == 24) return LocalTime.of(23, 59)
        val t = LocalTime.of(h, m)
        return if (half) t.minusMinutes(30) else t
    }

    private fun defaultTime(part: String?): LocalTime = when (part?.lowercase()) {
        "siang" -> LocalTime.of(12, 0)
        "sore", "petang" -> LocalTime.of(16, 0)
        "malam" -> LocalTime.of(19, 0)
        else -> LocalTime.of(7, 0)
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        if (m in 1..12 && d in 1..YearMonth.of(y, m).lengthOfMonth()) LocalDate.of(y, m, d) else null

    /** Tanggal tanpa tahun: yang baru saja lewat dianggap masa lalu, yang sudah lama lewat berarti tahun depan. */
    private fun resolveYear(m: Int, d: Int, today: LocalDate): LocalDate? {
        val thisYear = safeDate(today.year, m, d) ?: return null
        return when {
            !thisYear.isBefore(today) -> thisYear
            ChronoUnit.DAYS.between(thisYear, today) > 180 -> safeDate(today.year + 1, m, d)
            else -> thisYear
        }
    }

    private fun dates(s: String, now: LocalDateTime): List<DateHit> {
        val today = now.toLocalDate()
        val out = ArrayList<DateHit>()
        R_DMY.findAll(s).forEach { m ->
            val d = m.groupValues[1].toInt(); val mo = month(m.groupValues[2].lowercase())
            val date = m.groupValues[3].toIntOrNull()?.let { safeDate(it, mo, d) } ?: resolveYear(mo, d, today)
            if (date != null) out += DateHit(m.range, date)
        }
        R_TGL.findAll(s).forEach { m ->
            val d = m.groupValues[1].toInt()
            var ym = YearMonth.from(today)
            if (d < today.dayOfMonth) ym = ym.plusMonths(1)
            if (d in 1..ym.lengthOfMonth()) out += DateHit(m.range, ym.atDay(d))
        }
        R_NUM.findAll(s).forEach { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val date = if (y != null) safeDate(y, mo, d) else if (mo in 1..12) resolveYear(mo, d, today) else null
            if (date != null) out += DateHit(m.range, date)
        }
        R_PERIOD.findAll(s).forEach { m ->
            val mo = month(m.groupValues[2].lowercase())
            var ym = YearMonth.of(today.year, mo)
            if (ym.atEndOfMonth().isBefore(today)) ym = ym.plusYears(1)
            val day = when (m.groupValues[1].lowercase()) { "awal" -> 1; "pertengahan" -> 15; else -> ym.lengthOfMonth() }
            out += DateHit(m.range, ym.atDay(day))
        }
        R_TODAY.findAll(s).forEach { m ->
            val part = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { null }
            val hasNanti = m.value.contains("nanti", ignoreCase = true) || m.value.contains("ntar", ignoreCase = true)
            out += DateHit(m.range, today, part, todayRel = hasNanti)
        }
        R_TOMORROW.findAll(s).forEach { m ->
            val plus = if (m.groupValues[1].lowercase().contains("lusa")) 2L else 1L
            out += DateHit(m.range, today.plusDays(plus), m.groupValues[2].ifEmpty { null })
        }
        R_NEXT.findAll(s).forEach { m ->
            out += DateHit(m.range, if (m.groupValues[1].lowercase() == "bulan") today.plusMonths(1) else today.plusWeeks(1))
        }
        R_IN.findAll(s).forEach { m ->
            val n = m.groupValues[1].toLong()
            when (m.groupValues[2].lowercase()) {
                "menit" -> out += DateHit(m.range, today, exact = now.plusMinutes(n))
                "jam" -> out += DateHit(m.range, today, exact = now.plusHours(n))
                "hari" -> out += DateHit(m.range, today.plusDays(n))
                "bulan" -> out += DateHit(m.range, today.plusMonths(n))
                else -> out += DateHit(m.range, today.plusWeeks(n))
            }
        }
        R_DAY.findAll(s).forEach { m ->
            val dow = DAYS[m.groupValues[1].lowercase()] ?: return@forEach
            out += DateHit(m.range, today.with(TemporalAdjusters.nextOrSame(dow)), m.groupValues[2].ifEmpty { null })
        }
        R_SUNDAY.findAll(s).forEach { m ->
            out += DateHit(m.range, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), m.groupValues[1].ifEmpty { null })
        }
        // Buang yang tumpang-tindih; yang lebih panjang (lebih spesifik) menang.
        val kept = ArrayList<DateHit>()
        out.sortedByDescending { it.range.last - it.range.first }.forEach { h ->
            if (kept.none { it.range.first <= h.range.last && h.range.first <= it.range.last }) kept += h
        }
        return kept.sortedBy { it.range.first }
    }

    private fun times(s: String): List<TimeHit> {
        val out = ArrayList<TimeHit>()
        R_TIME_KW.findAll(s).forEach { m ->
            val g = m.groupValues
            val h = g[2].toInt(); val min = g[3].toIntOrNull() ?: 0; val half = g[1].isNotEmpty()
            clock(h, min, g[4].ifEmpty { null }, half, plain = !g[2].startsWith("0"))?.let { t ->
                out += TimeHit(m.range, t, true) { part -> if (g[4].isEmpty()) clock(h, min, part, half, false) else t }
            }
        }
        R_TIME_BARE.findAll(s).forEach { m ->
            if (out.any { it.range.first <= m.range.last && m.range.first <= it.range.last }) return@forEach
            val g = m.groupValues
            val h = g[1].toInt(); val min = (g[2].ifEmpty { g[3] }).toInt()
            clock(h, min, g[4].ifEmpty { null }, half = false, plain = false)?.let { t ->
                out += TimeHit(m.range, t, false) { part -> if (g[4].isEmpty()) clock(h, min, part, false, false) else t }
            }
        }
        // Hanya berlaku bila menempel pada tanggal ("besok habis isya"); sendirian terlalu sering bukan janji.
        R_PRAYER.findAll(s).forEach { m ->
            val t = PRAYER.getValue(m.groupValues[2].lowercase()).plusMinutes(if (m.groupValues[1].isEmpty()) 0 else 30)
            out += TimeHit(m.range, t, false) { t }
        }
        return out
    }

    /** Semua waktu mendatang yang dikenali, yang punya jam lebih dulu, lalu urut kemunculan. */
    fun find(text: String, now: LocalDateTime = LocalDateTime.now()): List<Found> {
        val result = ArrayList<Pair<Int, Found>>()
        var offset = 0
        for (line in text.take(20_000).lines()) {
            val ds = dates(line, now)
            val ts = times(line)
            val usedTimes = HashSet<TimeHit>()
            for (d in ds) {
                if (d.exact != null) {
                    result += (offset + d.range.first) to Found(d.exact.withSecond(0).withNano(0), line.substring(d.range).trim(), true)
                    continue
                }
                // Jam terdekat di baris yang sama: sesudah tanggal (≤ 40 huruf) atau tepat sebelumnya (≤ 20 huruf).
                val t = ts.filter {
                    val gapAfter = it.range.first - d.range.last
                    val gapBefore = d.range.first - it.range.last
                    gapAfter in 0..40 || gapBefore in 0..20
                }.minByOrNull { minOf(kotlin.math.abs(it.range.first - d.range.last), kotlin.math.abs(d.range.first - it.range.last)) }
                var at: LocalDateTime
                if (t != null) {
                    usedTimes += t
                    // "besok malam jam 8": keterangan waktu menempel pada tanggalnya, bukan pada jamnya.
                    val time = d.part?.let(t.withPart) ?: t.time
                    at = d.date.atTime(time)
                    // Nama hari yang jatuh hari ini tetapi jamnya sudah lewat berarti minggu depan.
                    if (!at.isAfter(now) && d.date == now.toLocalDate() && line.substring(d.range).lowercase().let { p -> DAYS.keys.any { it in p } }) at = at.plusWeeks(1)
                } else {
                    if (d.date == now.toLocalDate() && d.part == null) continue      // "hari ini" tanpa jam: tak ada yang bisa diingatkan
                    at = d.date.atTime(defaultTime(d.part))
                    // "nanti sore" saat masih sore (mis. pukul 16.15): geser 1 jam agar tetap menjadi pengingat hari ini.
                    if (!at.isAfter(now) && d.todayRel && d.date == now.toLocalDate() && d.part != null) {
                        val endHour = when (d.part.lowercase()) { "pagi" -> 11; "siang" -> 15; "sore", "petang" -> 18; else -> 23 }
                        if (now.hour < endHour) at = now.plusHours(1).withSecond(0).withNano(0)
                    }
                }
                val phrase = line.substring(minOf(d.range.first, t?.range?.first ?: d.range.first), maxOf(d.range.last, t?.range?.last ?: d.range.last) + 1).trim()
                if (at.isAfter(now)) result += (offset + d.range.first) to Found(at, phrase, t != null)
            }
            // Jam tanpa tanggal ("jam 7 malam") hanya dihitung bila memakai kata jam/pukul: hari ini, atau besok bila sudah lewat.
            for (t in ts) {
                if (!t.keyword || t in usedTimes) continue
                var at = now.toLocalDate().atTime(t.time)
                if (!at.isAfter(now)) at = at.plusDays(1)
                result += (offset + t.range.first) to Found(at, line.substring(t.range).trim(), true)
            }
            offset += line.length + 1
        }
        return result.sortedWith(compareByDescending<Pair<Int, Found>> { it.second.hasTime }.thenBy { it.first }).map { it.second }.distinctBy { it.at }
    }

    fun best(text: String, now: LocalDateTime = LocalDateTime.now()): Found? = find(text, now).firstOrNull()
}
