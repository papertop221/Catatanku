package com.catatanku.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.catatanku.app.MainActivity
import com.catatanku.app.R
import com.catatanku.app.ReminderReceiver
import com.catatanku.app.data.database.NoteDb
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.Repeat
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun Long.toLocal(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

private val WHEN_LOCALE: java.util.Locale = java.util.Locale.forLanguageTag("id-ID")
private val WHEN_TIME = java.time.format.DateTimeFormatter.ofPattern("HH.mm", WHEN_LOCALE)
private val WHEN_DAY = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", WHEN_LOCALE)
private val WHEN_FULL = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", WHEN_LOCALE)

fun whenLabel(t: Long, now: Long = System.currentTimeMillis()): String {
    val at = t.toLocal()
    val day = at.toLocalDate(); val today = now.toLocal().toLocalDate()
    val time = WHEN_TIME.format(at)
    return when {
        day == today -> "Hari ini $time"
        day == today.plusDays(1) -> "Besok $time"
        day == today.minusDays(1) -> "Kemarin $time"
        day.year == today.year -> WHEN_DAY.format(at) + " $time"
        else -> WHEN_FULL.format(at) + " $time"
    }
}

/**
 * Pengingat catatan: alarm sistem (AlarmManager) yang memunculkan notifikasi, juga saat aplikasi tertutup
 * dan setelah HP dinyalakan ulang. Mencegah notifikasi ganda lewat pemeriksaan status `remindFired`
 * dan pencocokan `EXTRA_DUE`, serta jatuh ke `setAndAllowWhileIdle` bila izin alarm tepat waktu dicabut.
 */
object Reminders {
    const val CH_NOTIFY = "pengingat"
    const val CH_ALARM = "pengingat_alarm"
    const val ACT_FIRE = "com.catatanku.app.REMIND"
    const val ACT_SNOOZED = "com.catatanku.app.REMIND_AGAIN"
    const val ACT_DONE = "com.catatanku.app.REMIND_DONE"
    const val ACT_SNOOZE = "com.catatanku.app.REMIND_SNOOZE"
    const val ACT_QUIET = "com.catatanku.app.REMIND_QUIET"
    const val ACT_TEST = "com.catatanku.app.REMIND_TEST"
    const val TEST_ID = -7L
    const val TEST_DELAY_MS = 2 * 60_000L
    const val LATE_MS = 3 * 60_000L
    const val EXTRA_ID = "note_id"
    const val EXTRA_DUE = "due_at"
    const val EXTRA_OPEN = "open_note"
    const val EXTRA_TOKEN = "open_token"
    const val SNOOZE_MINUTES = 10
    const val RING_MINUTES = 2

    val changed = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun nid(id: Long) = (id % Int.MAX_VALUE).toInt()
    private fun requestCode(action: String, id: Long): Int = ((id.toInt() * 31) xor action.hashCode()) and 0x7FFFFFFF

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    fun canNotify(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun channels(ctx: Context) {
        val vibrate = longArrayOf(0, 400, 250, 400)
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_NOTIFY, "Pengingat", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifikasi pengingat catatan"
                enableVibration(true); vibrationPattern = vibrate
            }
        )
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_ALARM, "Pengingat gaya alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Berdering dengan nada alarm sampai ditanggapi"
                enableVibration(true); vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                setSound(
                    if (RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM) != null)
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun broadcast(ctx: Context, action: String, id: Long, dueAt: Long = 0L): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("catatanku://note/$id"))
            .putExtra(EXTRA_ID, id)
        if (dueAt > 0L) intent.putExtra(EXTRA_DUE, dueAt)
        return PendingIntent.getBroadcast(
            ctx,
            requestCode(action, id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openNote(ctx: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        ctx,
        requestCode("OPEN", id),
        Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("catatanku://note/$id"))
            .putExtra(EXTRA_OPEN, id).putExtra(EXTRA_TOKEN, authToken(ctx))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    internal fun authToken(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("open_token", null)?.takeIf { it.length >= 16 }?.let { return it }
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.also { p.edit().putString("open_token", it).apply() }
    }

    internal fun isValidOpenToken(ctx: Context, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val expected = authToken(ctx).toByteArray(Charsets.UTF_8)
        val actual = token.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(expected, actual)
    }

    fun robust(ctx: Context): Boolean = prefs(ctx).getBoolean("robust_alarm", Oem.aggressive)
    fun setRobust(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("robust_alarm", on).apply(); rescheduleAll(ctx) }

    fun lastLate(ctx: Context): Pair<Long, Long> = prefs(ctx).getLong("late_ms", 0) to prefs(ctx).getLong("late_at", 0)
    fun clearLate(ctx: Context) { prefs(ctx).edit().remove("late_ms").remove("late_at").apply() }

    /** Meredam deteksi terlambat sesaat setelah jam sistem diubah manual agar tidak salah dianggap ditahan baterai. */
    fun suppressLatenessBriefly(ctx: Context) {
        prefs(ctx).edit().putLong("suppress_late_until", android.os.SystemClock.elapsedRealtime() + 2 * 60_000L).apply()
    }

    private fun noteLateness(ctx: Context, due: Long) {
        val suppressUntil = prefs(ctx).getLong("suppress_late_until", 0L)
        if (suppressUntil > 0L && android.os.SystemClock.elapsedRealtime() < suppressUntil) return
        val late = System.currentTimeMillis() - due
        if (late < LATE_MS || android.os.SystemClock.elapsedRealtime() < late + 60_000L) return
        prefs(ctx).edit().putLong("late_ms", late).putLong("late_at", System.currentTimeMillis()).apply()
    }

    fun startTest(ctx: Context): Long {
        val due = System.currentTimeMillis() + TEST_DELAY_MS
        prefs(ctx).edit().putLong("test_due", due).remove("test_late").apply()
        channels(ctx)
        setAlarm(ctx, due, broadcast(ctx, ACT_TEST, TEST_ID, due), false, TEST_ID)
        return due
    }

    fun testState(ctx: Context): Pair<Long, Long?> =
        prefs(ctx).getLong("test_due", 0) to prefs(ctx).getLong("test_late", -1).takeIf { it >= 0 }

    internal fun fireTest(ctx: Context) {
        val due = prefs(ctx).getLong("test_due", 0)
        if (due == 0L) return
        val late = (System.currentTimeMillis() - due).coerceAtLeast(0)
        prefs(ctx).edit().putLong("test_late", late).apply()
        channels(ctx)
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_NOTIFY).setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Uji pengingat berhasil")
            .setContentText(if (late < 60_000L) "Berbunyi tepat waktu." else "Terlambat ${late / 60_000} menit. Buka Catatanku → Pengaturan → Pengingat.")
            .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { nm(ctx).notify(nid(TEST_ID), n) } catch (_: SecurityException) {}
        changed.tryEmit(TEST_ID)
    }

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent, alarmStyle: Boolean, id: Long) {
        val a = am(ctx)
        try {
            when {
                !canExact(ctx) -> a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                alarmStyle || robust(ctx) -> a.setAlarmClock(AlarmManager.AlarmClockInfo(at, openNote(ctx, id)), pi)
                else -> a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            runCatching { a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        } catch (_: RuntimeException) {
            // Sebagian ROM pabrikan melempar IllegalStateException bila batas alarm harian/500 penuh.
            runCatching { a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    fun sync(ctx: Context, n: Note) {
        if (n.id == 0L) return
        val fire = broadcast(ctx, ACT_FIRE, n.id, n.remindAt)
        runCatching { am(ctx).cancel(fire) }
        if (!n.hasReminder || n.inTrash) { clear(ctx, n.id); return }
        if (n.remindFired) return
        setAlarm(ctx, maxOf(n.remindAt, System.currentTimeMillis() + 1000), fire, n.remindAlarm, n.id)
    }

    fun clear(ctx: Context, id: Long) {
        listOf(ACT_FIRE, ACT_SNOOZED, ACT_QUIET).forEach { runCatching { am(ctx).cancel(broadcast(ctx, it, id)) } }
        nm(ctx).cancel(nid(id))
    }

    fun rescheduleAll(ctx: Context) {
        channels(ctx)
        NoteDb.get(ctx).withReminder().forEach { sync(ctx, it) }
    }

    fun fire(ctx: Context, id: Long, snoozed: Boolean = false, expectedDue: Long = 0L) {
        val db = NoteDb.get(ctx)
        val n = db.get(id) ?: return
        if (!n.hasReminder || n.inTrash) return
        // Cegah notifikasi ganda bila siaran alarm sama terkirim ulang atau jadwal sudah maju.
        if (!snoozed && n.remindFired) return
        if (!snoozed && expectedDue > 0L && expectedDue != n.remindAt) return

        channels(ctx)
        if (!snoozed) noteLateness(ctx, n.remindAt)
        show(ctx, n, missed = false)
        if (n.remindAlarm) setAlarm(ctx, System.currentTimeMillis() + RING_MINUTES * 60_000L, broadcast(ctx, ACT_QUIET, id), false, id)
        if (snoozed) return
        val next = n.remindRepeat.next(n.remindAt.toLocal(), LocalDateTime.now())
        if (next != null) {
            val nextMs = next.toMillis()
            db.setReminder(id, nextMs, fired = false)
            sync(ctx, n.copy(remindAt = nextMs, remindFired = false))
        } else db.setReminder(id, n.remindAt, fired = true)
        changed.tryEmit(id)
    }

    fun done(ctx: Context, id: Long) {
        val db = NoteDb.get(ctx)
        runCatching { am(ctx).cancel(broadcast(ctx, ACT_SNOOZED, id)) }
        runCatching { am(ctx).cancel(broadcast(ctx, ACT_QUIET, id)) }
        nm(ctx).cancel(nid(id))
        val n = db.get(id) ?: return
        if (n.hasReminder && n.remindRepeat == Repeat.None && n.remindFired) {
            db.setReminder(id, 0, fired = false)
            changed.tryEmit(id)
        }
    }

    fun snooze(ctx: Context, id: Long) {
        val db = NoteDb.get(ctx)
        runCatching { am(ctx).cancel(broadcast(ctx, ACT_QUIET, id)) }
        nm(ctx).cancel(nid(id))
        val n = db.get(id) ?: return
        if (!n.hasReminder || n.inTrash) return
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        if (n.remindRepeat == Repeat.None) {
            db.setReminder(id, at, fired = false)
            sync(ctx, n.copy(remindAt = at, remindFired = false))
            changed.tryEmit(id)
        } else setAlarm(ctx, at, broadcast(ctx, ACT_SNOOZED, id), n.remindAlarm, id)
    }

    fun quiet(ctx: Context, id: Long) {
        val active = runCatching { nm(ctx).activeNotifications.any { it.id == nid(id) } }.getOrDefault(false)
        if (!active) return
        nm(ctx).cancel(nid(id))
        NoteDb.get(ctx).get(id)?.takeIf { it.hasReminder && !it.inTrash }?.let { show(ctx, it, missed = true) }
    }

    private fun show(ctx: Context, n: Note, missed: Boolean) {
        if (!canNotify(ctx)) return
        val ringing = n.remindAlarm && !missed
        val locked = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("lock", false)
        val title = n.heading.take(80).ifBlank { "Pengingat catatan" }
        val rest = n.body.trim().lines().filter { it.isNotBlank() }.let { if (n.title.isBlank()) it.drop(1) else it }.joinToString("\n").take(400)
        val b = NotificationCompat.Builder(ctx, if (ringing) CH_ALARM else CH_NOTIFY)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(if (missed) "Terlewat: $title" else title)
            .setContentIntent(openNote(ctx, n.id))
            .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(n.remindAt).setShowWhen(true)
            .setAutoCancel(true)
            .setOngoing(ringing)
            .addAction(0, "Selesai", broadcast(ctx, ACT_DONE, n.id))
            .addAction(0, "Tunda $SNOOZE_MINUTES mnt", broadcast(ctx, ACT_SNOOZE, n.id))
        if (locked) b.setContentText("Ketuk untuk membuka catatan")
        else if (rest.isNotBlank()) b.setContentText(rest.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(rest))
        else b.setContentText("Pengingat " + whenLabel(n.remindAt))
        val notification = b.build()
        if (ringing) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        try { nm(ctx).notify(nid(n.id), notification) } catch (_: SecurityException) {}
    }
}

object Oem {
    private val maker = Build.MANUFACTURER.orEmpty().lowercase()

    val family: String? = when (maker) {
        "oppo", "realme", "oneplus" -> "oppo"
        "vivo", "iqoo" -> "vivo"
        "xiaomi", "redmi", "poco" -> "xiaomi"
        "huawei", "honor" -> "huawei"
        "tecno", "infinix", "itel" -> "transsion"
        else -> null
    }
    val aggressive get() = family != null
    val brand: String get() = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }

    val steps: List<String> = when (family) {
        "oppo" -> listOf(
            "Setelan → Aplikasi → Luncurkan otomatis: nyalakan Catatanku.",
            "Setelan → Aplikasi → Catatanku → Penggunaan baterai: izinkan aktivitas latar belakang.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok) agar tidak ikut dibersihkan.",
        )
        "vivo" -> listOf(
            "Setelan → Baterai → Manajemen konsumsi daya latar belakang → Catatanku: jangan batasi.",
            "Setelan → Aplikasi → Akses aplikasi khusus → Mulai otomatis: izinkan Catatanku.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok) agar tidak ikut dibersihkan.",
        )
        "xiaomi" -> listOf(
            "Setelan → Aplikasi → Kelola aplikasi → Catatanku → Mulai otomatis: nyalakan.",
            "Di halaman yang sama → Penghemat baterai: Tanpa pembatasan.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok).",
        )
        "huawei" -> listOf(
            "Setelan → Baterai → Peluncuran aplikasi → Catatanku: kelola manual, lalu nyalakan ketiga pilihannya.",
            "Di layar aplikasi terbaru, kunci Catatanku (ikon gembok).",
        )
        "transsion" -> listOf(
            "Phone Master → Kelola mulai otomatis: izinkan Catatanku.",
            "Setelan → Baterai: keluarkan Catatanku dari pembatasan latar belakang.",
        )
        else -> emptyList()
    }

    private val pages: List<Pair<String, String>> = when (family) {
        "oppo" -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        "vivo" -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )
        "xiaomi" -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
        "huawei" -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        "transsion" -> listOf("com.transsion.phonemaster" to "com.itel.autobootmanager.activity.AutoBootMgrActivity")
        else -> emptyList()
    }

    fun openAutostart(ctx: Context): Boolean {
        for ((pkg, cls) in pages) {
            val ok = runCatching { ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return true
        }
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) != false

    fun requestBatteryUnrestricted(ctx: Context) {
        val direct = runCatching {
            @Suppress("BatteryLife")
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!direct) runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

fun addToCalendar(ctx: Context, title: String, body: String, at: Long, repeat: Repeat): Boolean = runCatching {
    val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title.take(120))
        .putExtra(CalendarContract.Events.DESCRIPTION, body.take(1000))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 30 * 60_000L)
    when (repeat) {
        Repeat.Daily -> "FREQ=DAILY"; Repeat.Weekly -> "FREQ=WEEKLY"; Repeat.Monthly -> "FREQ=MONTHLY"; Repeat.None -> null
    }?.let { i.putExtra(CalendarContract.Events.RRULE, it) }
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

fun saveFromOutside(ctx: Context, note: Note): Long {
    val id = NoteDb.get(ctx).upsert(note)
    if (note.hasReminder) Reminders.sync(ctx, note.copy(id = id))
    ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("dirty", true).apply()
    Reminders.changed.tryEmit(id)
    return id
}
