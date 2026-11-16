package com.catatanku.app

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Pengingat, riwayat versi, cadangan, dan migrasi basis data, dijalankan pada Android tiruan (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val db get() = NoteDb.get(app)
    private fun receive(action: String, id: Long) =
        ReminderReceiver().onReceive(app, Intent(action).putExtra(Reminders.EXTRA_ID, id))
    private fun note(at: Long, repeat: Repeat = Repeat.None, alarm: Boolean = false): Note {
        val n = Note(title = "Bayar listrik", body = "Token 200 ribu sebelum malam", remindAt = at, remindRepeat = repeat, remindAlarm = alarm)
        return n.copy(id = db.upsert(n))
    }
    private val hour = 3600_000L

    @Before fun setUp() {
        NoteDb.resetForTest()
        app.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Reminders.channels(app)
    }
    @After fun tearDown() = NoteDb.resetForTest()

    @Test fun schedulesFiresAndCompletes() {
        val at = System.currentTimeMillis() + hour
        val n = note(at)
        Reminders.sync(app, n)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(at, alarms.scheduledAlarms.first().triggerAtMs)

        receive(Reminders.ACT_FIRE, n.id)
        val shown = nm.activeNotifications.single()
        assertEquals("Bayar listrik", shown.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Reminders.CH_NOTIFY, shown.notification.channelId)
        assertEquals(2, shown.notification.actions.size)
        assertTrue("sudah berbunyi, belum selesai", db.get(n.id)!!.remindFired)

        // Menyetel ulang (mis. HP dinyalakan lagi) tidak membunyikan lagi pengingat yang sudah berbunyi.
        Reminders.rescheduleAll(app)
        assertEquals(0, alarms.scheduledAlarms.size)

        receive(Reminders.ACT_DONE, n.id)
        assertEquals(0, nm.activeNotifications.size)
        assertFalse(db.get(n.id)!!.hasReminder)
    }

    @Test fun robustModeUsesAlarmClockForEveryReminder() {
        val at = System.currentTimeMillis() + hour
        val n = note(at)
        Reminders.setRobust(app, false); Reminders.sync(app, n)
        assertNull("pengingat biasa bukan alarm jam", alarms.scheduledAlarms.single().alarmClockInfo)
        // Jenis alarm yang paling jarang ditahan penghemat baterai; setRobust menjadwal ulang semua pengingat.
        Reminders.setRobust(app, true)
        val a = alarms.scheduledAlarms.single()
        assertEquals(at, a.alarmClockInfo!!.triggerTime)
        assertEquals(at, a.triggerAtMs)
    }

    @Test fun lateReminderIsRecordedButPhoneOffIsNot() {
        // HP "menyala" 2 jam: pengingat yang berbunyi 10 menit terlambat berarti ditahan sistem.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(2))
        val n = note(System.currentTimeMillis() - 10 * 60_000)
        assertEquals(0L, Reminders.lastLate(app).first)
        receive(Reminders.ACT_FIRE, n.id)
        assertTrue(Reminders.lastLate(app).first >= 10 * 60_000)
        Reminders.clearLate(app)
        // Terlewat lebih lama daripada HP menyala = HP sedang mati saat waktunya: bukan salah penghemat baterai.
        val off = note(System.currentTimeMillis() - 30 * hour)
        receive(Reminders.ACT_FIRE, off.id)
        assertEquals(0L, Reminders.lastLate(app).first)
        // Tepat waktu tidak dicatat.
        val ok = note(System.currentTimeMillis() - 5_000)
        receive(Reminders.ACT_FIRE, ok.id)
        assertEquals(0L, Reminders.lastLate(app).first)
    }

    @Test fun selfTestSchedulesAndReports() {
        val due = Reminders.startTest(app)
        assertEquals(due, alarms.scheduledAlarms.single().triggerAtMs)
        assertEquals(due to null, Reminders.testState(app))
        receive(Reminders.ACT_TEST, Reminders.TEST_ID)
        assertEquals("Uji pengingat berhasil", nm.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0L, Reminders.testState(app).second)
    }

    @Test fun whenLabelIsRelative() {
        val base = java.time.LocalDateTime.of(2026, 11, 16, 9, 0)
        fun l(t: java.time.LocalDateTime) = whenLabel(t.toMillis(), base.toMillis())
        assertEquals("Hari ini 19.00", l(base.withHour(19)))
        assertEquals("Besok 07.00", l(base.plusDays(1).withHour(7)))
        assertEquals("Kemarin 09.00", l(base.minusDays(1)))
        assertEquals("Sel, 24 Nov 10.00", l(java.time.LocalDateTime.of(2026, 11, 24, 10, 0)))
        assertEquals("2 Jan 2027 08.30", l(java.time.LocalDateTime.of(2027, 1, 2, 8, 30)))
        assertEquals("Besok 00.15", whenLabel(java.time.LocalDateTime.of(2027, 1, 1, 0, 15).toMillis(), java.time.LocalDateTime.of(2026, 12, 31, 23, 0).toMillis()))
    }

    @Test fun noteSavedFromOutsideGetsItsAlarm() {
        val at = System.currentTimeMillis() + hour
        val id = saveFromOutside(app, Note(body = "Rapat besok", remindAt = at))
        assertEquals("Rapat besok", db.get(id)!!.body)
        assertEquals(at, alarms.scheduledAlarms.single().triggerAtMs)
        assertTrue(app.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("dirty", false))
    }

    @Test fun repeatingReminderMovesForward() {
        val at = System.currentTimeMillis() - 60_000
        val n = note(at, Repeat.Daily)
        receive(Reminders.ACT_FIRE, n.id)
        val after = db.get(n.id)!!
        assertEquals(at + 24 * hour, after.remindAt)
        assertFalse(after.remindFired)
        assertEquals(after.remindAt, alarms.scheduledAlarms.single().triggerAtMs)
        receive(Reminders.ACT_DONE, n.id)                                   // "Selesai" hanya menutup notifikasi
        assertEquals(after.remindAt, db.get(n.id)!!.remindAt)
    }

    @Test fun alarmStyleRingsThenBecomesMissed() {
        val n = note(System.currentTimeMillis() - 1000, alarm = true)
        receive(Reminders.ACT_FIRE, n.id)
        val ringing = nm.activeNotifications.single().notification
        assertEquals(Reminders.CH_ALARM, ringing.channelId)
        assertTrue(ringing.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals("senyap otomatis dijadwalkan", 1, alarms.scheduledAlarms.size)
        receive(Reminders.ACT_QUIET, n.id)
        val missed = nm.activeNotifications.single().notification
        assertEquals(Reminders.CH_NOTIFY, missed.channelId)
        assertEquals("Terlewat: Bayar listrik", missed.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, missed.flags and Notification.FLAG_INSISTENT)
    }

    @Test fun snoozeTrashAndMissedWhileOff() {
        val n = note(System.currentTimeMillis() - 1000)
        receive(Reminders.ACT_FIRE, n.id)
        val before = System.currentTimeMillis()
        receive(Reminders.ACT_SNOOZE, n.id)
        assertEquals(0, nm.activeNotifications.size)
        val snoozed = db.get(n.id)!!
        assertTrue(snoozed.remindAt >= before + Reminders.SNOOZE_MINUTES * 60_000L && !snoozed.remindFired)
        assertEquals(snoozed.remindAt, alarms.scheduledAlarms.single().triggerAtMs)

        // Masuk Sampah: alarm dibatalkan. Dipulihkan: disetel lagi.
        Reminders.sync(app, snoozed.copy(deletedAt = 1))
        assertEquals(0, alarms.scheduledAlarms.size)
        Reminders.sync(app, snoozed)
        assertEquals(1, alarms.scheduledAlarms.size)

        // Terlewat selagi HP mati: berbunyi segera setelah dinyalakan, bukan hilang.
        val late = note(System.currentTimeMillis() - 5 * hour)
        ReminderReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(2, alarms.scheduledAlarms.size)
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs <= System.currentTimeMillis() + 2000 })
        assertFalse(db.get(late.id)!!.remindFired)
    }

    @Test fun lockHidesNoteTextInNotification() {
        app.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("lock", true).commit()
        val n = note(System.currentTimeMillis() - 1000)
        receive(Reminders.ACT_FIRE, n.id)
        val text = nm.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(text.contains("Token"))
    }

    @Test fun versionsAreKeptAndTrimmed() {
        val id = db.upsert(Note(title = "A", body = "isi 0"))
        repeat(NoteDb.KEEP_VERSIONS + 5) { i -> db.addVersion(id, "A", "isi $i", 1000L + i) }
        val vs = db.versions(id)
        assertEquals(NoteDb.KEEP_VERSIONS, vs.size)
        assertEquals("isi ${NoteDb.KEEP_VERSIONS + 4}", vs.first().body)        // terbaru di atas
        db.delete(id)
        assertEquals(0, db.versions(id).size)
    }

    @Test fun backupKeepsTrashAndReminders() {
        val src = listOf(
            Note(title = "Aktif", body = "a", tags = listOf("x", "y"), pinned = true, folder = "Kerja", remindAt = 123456L, remindRepeat = Repeat.Weekly, remindAlarm = true),
            Note(title = "Dibuang", body = "b", deletedAt = 99L, archived = true),
        )
        val back = Backup.fromJson(Backup.toJson(src))
        assertEquals(src.map { it.copy(id = 0) }, back)
        // Cadangan dari versi lama (tanpa kolom baru) tetap terbaca.
        val old = Backup.fromJson("""{"app":"Catatanku","version":2,"notes":[{"title":"Lama","body":"z","tags":["t"],"pinned":false,"folder":"","archived":false,"created":1,"updated":2}]}""").single()
        assertEquals("Lama", old.title); assertFalse(old.hasReminder); assertFalse(old.inTrash)
    }

    @Test fun upgradeFromVersion2KeepsNotes() {
        NoteDb.resetForTest()
        val path = app.getDatabasePath("catatan.db").apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL(
                "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, " +
                    "tags TEXT NOT NULL, pinned INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
                    "folder TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)"
            )
            old.execSQL("INSERT INTO notes(title,body,tags,pinned,created,updated,folder) VALUES('Lama','isi lama','a\u001fb',1,10,20,'Kerja')")
            old.version = 2
        }
        val n = db.all().single()
        assertEquals("Lama", n.title); assertEquals(listOf("a", "b"), n.tags); assertEquals("Kerja", n.folder); assertTrue(n.pinned)
        assertFalse(n.hasReminder)
        db.setReminder(n.id, 555L, fired = true)
        assertEquals(555L, db.get(n.id)!!.remindAt)
        assertEquals("isi lama", db.get(n.id)!!.body)
        db.addVersion(n.id, "Lama", "isi lama", 20)
        assertEquals(1, db.versions(n.id).size)
        assertNull(db.get(9999))
    }
}
