package com.catatanku.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun Long.toLocal(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

/** "Hari ini 19.00", "Besok 07.00", "Sel, 24 Nov 10.00"; tahun hanya ditulis bila berbeda. */
fun whenLabel(t: Long, now: Long = System.currentTimeMillis()): String {
    val id = Locale.forLanguageTag("id-ID")
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val n = Calendar.getInstance().apply { timeInMillis = now }
    val time = SimpleDateFormat("HH.mm", id).format(Date(t))
    fun dayIndex(x: Calendar) = x[Calendar.YEAR] * 366L + x[Calendar.DAY_OF_YEAR]
    return when {
        c[Calendar.YEAR] == n[Calendar.YEAR] && dayIndex(c) - dayIndex(n) == 0L -> "Hari ini $time"
        c[Calendar.YEAR] == n[Calendar.YEAR] && dayIndex(c) - dayIndex(n) == 1L -> "Besok $time"
        c[Calendar.YEAR] == n[Calendar.YEAR] && dayIndex(c) - dayIndex(n) == -1L -> "Kemarin $time"
        c[Calendar.YEAR] == n[Calendar.YEAR] -> SimpleDateFormat("EEE, d MMM", id).format(Date(t)) + " $time"
        else -> SimpleDateFormat("d MMM yyyy", id).format(Date(t)) + " $time"
    }
}

/**
 * Pengingat catatan: alarm sistem (AlarmManager) yang memunculkan notifikasi, juga saat aplikasi tertutup
 * dan setelah HP dinyalakan ulang. Tidak memakai jaringan sama sekali.
 *
 * Dua gaya: notifikasi biasa (sekali bunyi) dan gaya alarm (nada alarm berulang sampai ditanggapi,
 * lalu senyap sendiri setelah [RING_MINUTES] menit dan berubah menjadi notifikasi "terlewat").
 */
object Reminders {
    const val CH_NOTIFY = "pengingat"
    const val CH_ALARM = "pengingat_alarm"
    const val ACT_FIRE = "com.catatanku.app.REMIND"
    const val ACT_SNOOZED = "com.catatanku.app.REMIND_AGAIN"
    const val ACT_DONE = "com.catatanku.app.REMIND_DONE"
    const val ACT_SNOOZE = "com.catatanku.app.REMIND_SNOOZE"
    const val ACT_QUIET = "com.catatanku.app.REMIND_QUIET"
    const val EXTRA_ID = "note_id"
    const val EXTRA_OPEN = "open_note"
    const val SNOOZE_MINUTES = 10
    const val RING_MINUTES = 2

    /** Memberi tahu layar bahwa pengingat sebuah catatan berubah dari luar (alarm berbunyi, tombol notifikasi). */
    val changed = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun nid(id: Long) = (id % Int.MAX_VALUE).toInt()

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
                    // HP tanpa nada alarm bawaan akan senyap; pakai nada notifikasi sebagai gantinya.
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

    private fun broadcast(ctx: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        // Data unik per catatan membuat tiap PendingIntent berbeda, sehingga alarm antar-catatan tidak saling menimpa.
        Intent(ctx, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("catatanku://note/$id")).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openNote(ctx: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("catatanku://note/$id"))
            .putExtra(EXTRA_OPEN, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent, alarmStyle: Boolean, id: Long) {
        val a = am(ctx)
        try {
            when {
                !canExact(ctx) -> a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)      // bisa terlambat beberapa menit
                alarmStyle -> a.setAlarmClock(AlarmManager.AlarmClockInfo(at, openNote(ctx, id)), pi)
                else -> a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Menyamakan alarm sistem dengan keadaan catatan: setel bila ada pengingat yang belum berbunyi, kalau tidak batalkan. */
    fun sync(ctx: Context, n: Note) {
        if (n.id == 0L) return
        val fire = broadcast(ctx, ACT_FIRE, n.id)
        am(ctx).cancel(fire)
        if (!n.hasReminder || n.inTrash) { clear(ctx, n.id); return }
        if (n.remindFired) return
        // Pengingat yang terlewat selagi HP mati berbunyi segera setelah dinyalakan.
        setAlarm(ctx, maxOf(n.remindAt, System.currentTimeMillis() + 1000), fire, n.remindAlarm, n.id)
    }

    /** Membatalkan semua alarm dan notifikasi milik satu catatan. */
    fun clear(ctx: Context, id: Long) {
        listOf(ACT_FIRE, ACT_SNOOZED, ACT_QUIET).forEach { am(ctx).cancel(broadcast(ctx, it, id)) }
        nm(ctx).cancel(nid(id))
    }

    fun rescheduleAll(ctx: Context) {
        channels(ctx)
        NoteDb.get(ctx).withReminder().forEach { sync(ctx, it) }
    }

    /** Alarm berbunyi: tampilkan notifikasi, lalu majukan pengingat berulang atau tandai yang sekali sebagai sudah berbunyi. */
    fun fire(ctx: Context, id: Long, snoozed: Boolean = false) {
        val db = NoteDb.get(ctx)
        val n = db.get(id) ?: return
        if (!n.hasReminder || n.inTrash) return
        channels(ctx)
        show(ctx, n, missed = false)
        if (n.remindAlarm) setAlarm(ctx, System.currentTimeMillis() + RING_MINUTES * 60_000L, broadcast(ctx, ACT_QUIET, id), false, id)
        if (snoozed) return
        val next = n.remindRepeat.next(n.remindAt.toLocal(), LocalDateTime.now())
        if (next != null) {
            db.setReminder(id, next.toMillis(), fired = false)
            sync(ctx, n.copy(remindAt = next.toMillis(), remindFired = false))
        } else db.setReminder(id, n.remindAt, fired = true)
        changed.tryEmit(id)
    }

    /** "Selesai" atau catatan dibuka dari notifikasi: pengingat sekali dihapus, yang berulang tetap berjalan. */
    fun done(ctx: Context, id: Long) {
        val db = NoteDb.get(ctx)
        am(ctx).cancel(broadcast(ctx, ACT_SNOOZED, id)); am(ctx).cancel(broadcast(ctx, ACT_QUIET, id))
        nm(ctx).cancel(nid(id))
        val n = db.get(id) ?: return
        if (n.hasReminder && n.remindRepeat == Repeat.None && n.remindFired) {
            db.setReminder(id, 0, fired = false)
            changed.tryEmit(id)
        }
    }

    fun snooze(ctx: Context, id: Long) {
        val db = NoteDb.get(ctx)
        am(ctx).cancel(broadcast(ctx, ACT_QUIET, id))
        nm(ctx).cancel(nid(id))
        val n = db.get(id) ?: return
        if (!n.hasReminder || n.inTrash) return
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        if (n.remindRepeat == Repeat.None) {
            // Pengingat sekali digeser, sehingga tundaan ikut bertahan bila HP dinyalakan ulang.
            db.setReminder(id, at, fired = false)
            sync(ctx, n.copy(remindAt = at, remindFired = false))
            changed.tryEmit(id)
        } else setAlarm(ctx, at, broadcast(ctx, ACT_SNOOZED, id), n.remindAlarm, id)
    }

    /** Gaya alarm yang tidak ditanggapi: hentikan dering dan tinggalkan notifikasi "terlewat". */
    fun quiet(ctx: Context, id: Long) {
        if (nm(ctx).activeNotifications.none { it.id == nid(id) }) return
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
        // Dengan kunci aplikasi aktif, isi catatan tidak ditampilkan di notifikasi.
        if (locked) b.setContentText("Ketuk untuk membuka catatan")
        else if (rest.isNotBlank()) b.setContentText(rest.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(rest))
        else b.setContentText("Pengingat " + whenLabel(n.remindAt))
        val notification = b.build()
        if (ringing) notification.flags = notification.flags or Notification.FLAG_INSISTENT      // nada diulang sampai ditanggapi
        try { nm(ctx).notify(nid(n.id), notification) } catch (_: SecurityException) {}
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, 0)
        when (intent.action) {
            Reminders.ACT_FIRE -> Reminders.fire(ctx, id)
            Reminders.ACT_SNOOZED -> Reminders.fire(ctx, id, snoozed = true)
            Reminders.ACT_DONE -> Reminders.done(ctx, id)
            Reminders.ACT_SNOOZE -> Reminders.snooze(ctx, id)
            Reminders.ACT_QUIET -> Reminders.quiet(ctx, id)
            // HP dinyalakan ulang, aplikasi diperbarui, atau jam/zona waktu diubah: alarm sistem hilang dan harus disetel lagi.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Reminders.rescheduleAll(ctx)
        }
    }
}
