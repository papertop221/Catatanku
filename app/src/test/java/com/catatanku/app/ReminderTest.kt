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
import com.catatanku.app.data.backup.BackupCodec
import com.catatanku.app.data.database.NoteDb
import com.catatanku.app.domain.model.BackupNoteRecord
import com.catatanku.app.domain.model.BackupVersionEntry
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.domain.usecase.EmbeddingCodec
import com.catatanku.app.domain.usecase.StoredEmbedding
import com.catatanku.app.media.Attachments
import com.catatanku.app.reminders.Reminders
import com.catatanku.app.reminders.saveFromOutside
import com.catatanku.app.reminders.toMillis
import com.catatanku.app.reminders.whenLabel
import com.catatanku.app.security.DeviceVaultKey
import com.catatanku.app.security.InMemoryKeyProvider
import com.catatanku.app.security.SecurityPrefs
import com.catatanku.app.security.VaultCrypto
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
        DeviceVaultKey.setTestProviderForUnitTest(InMemoryKeyProvider())
        app.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("sec_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Reminders.channels(app)
    }
    @After fun tearDown() {
        NoteDb.resetForTest()
        DeviceVaultKey.setTestProviderForUnitTest(null)
    }

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
        SecurityPrefs.setLockEnabled(app, true)
        val n = note(System.currentTimeMillis() - 1000)
        receive(Reminders.ACT_FIRE, n.id)
        val extras = nm.activeNotifications.single().notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(title.contains("Bayar listrik"))
        assertFalse(text.contains("Token"))
    }

    @Test fun vaultNoteHidesTitleAndBodyInNotificationEvenWhenUnlocked() {
        SecurityPrefs.setLockEnabled(app, false)
        val vn = Note(title = "PIN Bank Rahasia", body = "Kode 998877", remindAt = System.currentTimeMillis() - 1000, vault = true)
        val id = db.upsert(vn)
        receive(Reminders.ACT_FIRE, id)
        val extras = nm.activeNotifications.single().notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(title.contains("Bank"))
        assertFalse(text.contains("998877"))
        assertTrue(title.contains("Brankas"))
    }

    @Test fun notesAreEncryptedAtRestAndLegacyV5MigratesToV6Encrypted() {
        val id = db.upsert(Note(title = "Rahasia Besar", body = "Isi sangat sensitif", tags = listOf("privasi"), folder = "Pribadi", vault = true))
        val rawCursor = db.readableDatabase.rawQuery("SELECT title,body,tags,folder,vault FROM notes WHERE id=?", arrayOf(id.toString()))
        rawCursor.use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(VaultCrypto.isEncryptedString(c.getString(0)))
            assertTrue(VaultCrypto.isEncryptedString(c.getString(1)))
            assertTrue(VaultCrypto.isEncryptedString(c.getString(2)))
            assertTrue(VaultCrypto.isEncryptedString(c.getString(3)))
            assertFalse(c.getString(0).contains("Rahasia"))
            assertFalse(c.getString(1).contains("sensitif"))
            assertEquals(1, c.getInt(4))
        }

        // Uji migrasi v5 polos -> v6 terenkripsi otomatis saat onOpen.
        NoteDb.resetForTest()
        val path = app.getDatabasePath("catatan.db").apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL(
                "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, " +
                    "tags TEXT NOT NULL, pinned INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
                    "folder TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "remind_at INTEGER NOT NULL DEFAULT 0, remind_repeat INTEGER NOT NULL DEFAULT 0, " +
                    "remind_alarm INTEGER NOT NULL DEFAULT 0, remind_fired INTEGER NOT NULL DEFAULT 0, " +
                    "attachments TEXT NOT NULL DEFAULT '')"
            )
            old.execSQL("CREATE TABLE versions(id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, saved INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE embeddings(note_id INTEGER PRIMARY KEY, content_hash TEXT NOT NULL, model_id TEXT NOT NULL, vec BLOB NOT NULL, updated INTEGER NOT NULL)")
            old.execSQL("INSERT INTO notes(title,body,tags,pinned,created,updated,folder) VALUES('Polos Lama','isi polos v5','tag1',0,10,20,'Kerja')")
            old.version = 5
        }
        val migrated = db.all().single()
        assertEquals("Polos Lama", migrated.title)
        assertEquals("isi polos v5", migrated.body)
        assertFalse(migrated.vault)
        db.readableDatabase.rawQuery("SELECT title,body FROM notes", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(VaultCrypto.isEncryptedString(c.getString(0)))
            assertTrue(VaultCrypto.isEncryptedString(c.getString(1)))
        }
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
            Note(title = "Aktif", body = "a", tags = listOf("x", "y"), pinned = true, folder = "Kerja", remindAt = 123456L, remindRepeat = Repeat.Weekly, remindAlarm = true, attachments = listOf("img_1.jpg", "aud_2.wav")),
            Note(title = "Dibuang", body = "b", deletedAt = 99L, archived = true),
        )
        val back = BackupCodec.fromJson(BackupCodec.toJson(src))
        assertEquals(src.map { it.copy(id = 0) }, back)
        // Cadangan dari versi lama (tanpa kolom baru) tetap terbaca.
        val old = BackupCodec.fromJson("""{"app":"Catatanku","version":2,"notes":[{"title":"Lama","body":"z","tags":["t"],"pinned":false,"folder":"","archived":false,"created":1,"updated":2}]}""").single()
        assertEquals("Lama", old.title); assertFalse(old.hasReminder); assertFalse(old.inTrash); assertTrue(old.attachments.isEmpty())
    }

    @Test fun upgradeFromVersion3AddsAttachments() {
        NoteDb.resetForTest()
        val path = app.getDatabasePath("catatan.db").apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL(
                "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, " +
                    "tags TEXT NOT NULL, pinned INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
                    "folder TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "remind_at INTEGER NOT NULL DEFAULT 0, remind_repeat INTEGER NOT NULL DEFAULT 0, " +
                    "remind_alarm INTEGER NOT NULL DEFAULT 0, remind_fired INTEGER NOT NULL DEFAULT 0)"
            )
            old.execSQL("INSERT INTO notes(title,body,tags,pinned,created,updated,remind_at) VALUES('Versi3','isi v3','',0,10,20,777)")
            old.version = 3
        }
        val n = db.all().single()
        assertEquals("Versi3", n.title); assertEquals(777L, n.remindAt); assertTrue(n.attachments.isEmpty())
        db.upsert(n.copy(attachments = listOf("img_test.jpg", "aud_test.wav")))
        assertEquals(listOf("img_test.jpg", "aud_test.wav"), db.get(n.id)!!.attachments)
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

    @Test fun streamingBackupAndAttachmentSanitization() {
        val src = listOf(
            Note(title = "Streaming", body = "Baris\nKedua", tags = listOf("a"), attachments = listOf("img_ok.jpg", "../catatan.db", "aud_1.wav")),
        )
        val stream = java.io.ByteArrayOutputStream()
        BackupCodec.writeJson(src, stream, pretty = false)
        val back = BackupCodec.fromJson(stream.toByteArray().decodeToString()).single()
        assertEquals("Streaming", back.title)
        assertEquals(listOf("img_ok.jpg", "aud_1.wav"), back.attachments)

        // URI eksternal: hanya content:// dari penyedia luar; file:// dan FileProvider internal ditolak.
        assertFalse(Attachments.isSafeExternalUri(app, android.net.Uri.parse("file:///data/data/${app.packageName}/databases/catatan.db")))
        assertFalse(Attachments.isSafeExternalUri(app, android.net.Uri.parse("content://${app.packageName}.files/secret")))
        assertTrue(Attachments.isSafeExternalUri(app, android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3A1.jpg")))
    }

    @Test fun openTokenRejectsForgedIntentAndAcceptsAppToken() {
        val valid = Reminders.authToken(app)
        assertTrue(Reminders.isValidOpenToken(app, valid))
        assertFalse(Reminders.isValidOpenToken(app, null))
        assertFalse(Reminders.isValidOpenToken(app, ""))
        assertFalse(Reminders.isValidOpenToken(app, "0".repeat(valid.length)))
    }

    @Test fun duplicateFireAndStaleDueAreIgnored() {
        val due = System.currentTimeMillis() - 10_000L
        val n = note(due)
        ReminderReceiver().onReceive(
            app,
            Intent(Reminders.ACT_FIRE).putExtra(Reminders.EXTRA_ID, n.id).putExtra(Reminders.EXTRA_DUE, due - 5_000L),
        )
        assertEquals(0, nm.activeNotifications.size)
        assertFalse(db.get(n.id)!!.remindFired)

        ReminderReceiver().onReceive(
            app,
            Intent(Reminders.ACT_FIRE).putExtra(Reminders.EXTRA_ID, n.id).putExtra(Reminders.EXTRA_DUE, due),
        )
        assertEquals(1, nm.activeNotifications.size)
        assertTrue(db.get(n.id)!!.remindFired)

        nm.cancelAll()
        ReminderReceiver().onReceive(
            app,
            Intent(Reminders.ACT_FIRE).putExtra(Reminders.EXTRA_ID, n.id).putExtra(Reminders.EXTRA_DUE, due),
        )
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun clockChangeSuppressesFalseLatenessAndReschedules() {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(2))
        val futureAt = System.currentTimeMillis() + hour
        val upcoming = note(futureAt)
        ReminderReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtMs == futureAt })

        val justAdjusted = note(System.currentTimeMillis() - 10 * 60_000L)
        receive(Reminders.ACT_FIRE, justAdjusted.id)
        assertEquals(0L, Reminders.lastLate(app).first)
        Reminders.clear(app, upcoming.id)
    }

    @Test fun exactAlarmPermissionStateChangeReschedules() {
        val at = System.currentTimeMillis() + hour
        val n = note(at)
        assertEquals(0, alarms.scheduledAlarms.size)
        ReminderReceiver().onReceive(app, Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
        assertEquals(at, alarms.scheduledAlarms.single().triggerAtMs)
        Reminders.clear(app, n.id)
    }

    @Test fun upgradeFromVersion4AddsEmbeddingsAndTransactionRollsBack() {
        NoteDb.resetForTest()
        val path = app.getDatabasePath("catatan.db").apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL(
                "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, " +
                    "tags TEXT NOT NULL, pinned INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
                    "folder TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "remind_at INTEGER NOT NULL DEFAULT 0, remind_repeat INTEGER NOT NULL DEFAULT 0, " +
                    "remind_alarm INTEGER NOT NULL DEFAULT 0, remind_fired INTEGER NOT NULL DEFAULT 0, " +
                    "attachments TEXT NOT NULL DEFAULT '')"
            )
            old.execSQL(
                "CREATE TABLE versions(id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER NOT NULL, " +
                    "title TEXT NOT NULL, body TEXT NOT NULL, saved INTEGER NOT NULL)"
            )
            old.execSQL("CREATE INDEX versions_note ON versions(note_id)")
            old.execSQL("INSERT INTO notes(title,body,tags,pinned,created,updated) VALUES('Versi4','isi v4','',0,10,20)")
            old.version = 4
        }
        val n = db.all().single()
        assertEquals("Versi4", n.title)
        assertTrue(db.embeddings("gemma-emb").isEmpty())
        db.upsertEmbedding(
            StoredEmbedding(
                noteId = n.id,
                contentHash = EmbeddingCodec.contentHash(n.title, n.body, n.tags),
                modelId = "gemma-emb",
                vector = floatArrayOf(1f, 0f, 0f),
            ),
        )
        assertEquals(1, db.embeddings("gemma-emb").size)

        db.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER abort_fail_ver BEFORE INSERT ON versions WHEN NEW.saved=11 " +
                "BEGIN SELECT RAISE(ABORT, 'simulated error'); END"
        )
        val attempt = runCatching {
            db.importInTransaction(
                listOf(
                    BackupNoteRecord(Note(title = "Calon1", body = "ok"), listOf(BackupVersionEntry("V1", "b", 10L))),
                    BackupNoteRecord(Note(title = "Calon2", body = "gagal"), listOf(BackupVersionEntry("__ABORT__", "x", 11L))),
                ),
            )
        }
        assertTrue(attempt.isFailure)
        assertEquals(listOf("Versi4"), db.all().map { it.title })
    }
}
