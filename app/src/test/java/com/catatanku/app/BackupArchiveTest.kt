package com.catatanku.app

import com.catatanku.app.data.backup.BackupArchive
import com.catatanku.app.data.backup.BackupCodec
import com.catatanku.app.domain.model.BackupNoteRecord
import com.catatanku.app.domain.model.BackupPhase
import com.catatanku.app.domain.model.BackupProgress
import com.catatanku.app.domain.model.BackupVersionEntry
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.security.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun seedMedia(attachDir: File): Map<String, ByteArray> {
        attachDir.mkdirs()
        val files = LinkedHashMap<String, ByteArray>()
        for (i in 0 until 10) {
            val imgName = "img_2026_$i.jpg"
            val imgBytes = ByteArray(1024 + i * 37) { b -> ((i * 17 + b) and 0xFF).toByte() }
            File(attachDir, imgName).writeBytes(imgBytes)
            files[imgName] = imgBytes

            val audName = "aud_2026_$i.wav"
            val audBytes = ("RIFF" + "X".repeat(256 + i * 19)).toByteArray()
            File(attachDir, audName).writeBytes(audBytes)
            files[audName] = audBytes
        }
        return files
    }

    private fun sample100Records(): List<BackupNoteRecord> = List(100) { idx ->
        val atts = if (idx < 10) listOf("img_2026_$idx.jpg", "aud_2026_$idx.wav") else emptyList()
        val note = Note(
            id = idx + 1L,
            title = "Catatan ke-$idx",
            body = "Isi catatan $idx dengan baris\n☐ Tugas $idx",
            tags = listOf("tag${idx % 5}", "kerja"),
            pinned = idx % 10 == 0,
            folder = if (idx % 3 == 0) "Kantor" else "",
            archived = idx % 7 == 0,
            deletedAt = if (idx % 15 == 0) 1_700_000_000L + idx else 0L,
            created = 1_700_000_000L + idx,
            updated = 1_700_001_000L + idx,
            remindAt = if (idx % 4 == 0) 1_800_000_000_000L + idx * 60_000L else 0L,
            remindRepeat = if (idx % 8 == 0) Repeat.Weekly else Repeat.None,
            remindAlarm = idx % 16 == 0,
            remindFired = idx % 32 == 0,
            attachments = atts,
        )
        val versions = if (idx % 2 == 0) {
            listOf(BackupVersionEntry("Judul lama $idx", "Isi lama $idx", 1_699_000_000L + idx))
        } else emptyList()
        BackupNoteRecord(note = note, versions = versions)
    }

    @Test fun zipExportAndRestoreRoundtripFor100NotesWithMediaAndVersions() {
        val attachDir = tmp.newFolder("attachments")
        val media = seedMedia(attachDir)
        val records = sample100Records()
        val out = ByteArrayOutputStream()
        val phases = ArrayList<BackupPhase>()

        BackupArchive.exportZip(
            records = records,
            attachmentsDir = attachDir,
            out = out,
            appVersion = "1.8",
            passphrase = null,
            onProgress = { phases += it.phase },
        )

        assertTrue(phases.contains(BackupPhase.PREPARING))
        assertTrue(phases.contains(BackupPhase.WRITING))
        assertEquals(BackupPhase.DONE, phases.last())

        val zipBytes = out.toByteArray()
        val entryNames = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entryNames += e.name
                zis.closeEntry()
            }
        }
        assertTrue(BackupArchive.ENTRY_MANIFEST in entryNames)
        assertTrue(BackupArchive.ENTRY_NOTES in entryNames)
        assertTrue(BackupArchive.ENTRY_CHECKSUMS in entryNames)
        assertTrue("attachments/image/img_2026_0.jpg" in entryNames)
        assertTrue("attachments/audio/aud_2026_0.wav" in entryNames)

        val stageDir = File(tmp.root, "stage_roundtrip")
        val staged = BackupArchive.stageRestore(ByteArrayInputStream(zipBytes), stageDir)
        val manifest = staged.manifest
        assertNotNull(manifest)
        manifest!!
        assertEquals("1.8", manifest.appVersion)
        assertEquals(100, manifest.noteCount)
        assertEquals(20, manifest.attachmentCount)
        assertEquals(50, manifest.versionCount)
        assertFalse(manifest.encrypted)
        assertEquals(100, staged.records.size)
        assertEquals(media.size, staged.stagedFiles.size)

        for (i in 0 until 100) {
            assertEquals(records[i].note.copy(id = 0L), staged.records[i].note)
            assertEquals(records[i].versions, staged.records[i].versions)
        }
        for ((name, origBytes) in media) {
            val stagedFile = staged.stagedFiles[name]!!
            assertTrue(stagedFile.isFile)
            assertArrayEquals(origBytes, stagedFile.readBytes())
        }
    }

    @Test fun encryptedZipRoundtripRejectsWrongPasswordAndTruncation() {
        val attachDir = tmp.newFolder("att_enc")
        val media = seedMedia(attachDir)
        val records = sample100Records().take(12)
        val pass = "KataSandiKuat#2026".toCharArray()
        val out = ByteArrayOutputStream()

        BackupArchive.exportZip(records, attachDir, out, appVersion = "1.8", passphrase = pass)
        val encryptedBytes = out.toByteArray()
        assertTrue(BackupCrypto.isEncryptedHeader(encryptedBytes.copyOf(8)))

        // Tanpa passphrase -> ditolak dengan pesan meminta sandi.
        val noPassErr = runCatching {
            BackupArchive.stageRestore(ByteArrayInputStream(encryptedBytes), File(tmp.root, "stage_nopass"), passphraseProvider = null)
        }.exceptionOrNull()
        assertTrue(noPassErr is IllegalStateException)

        // Passphrase salah -> gagal autentikasi GCM & membersihkan staging.
        val wrongStage = File(tmp.root, "stage_wrongpass")
        val wrongErr = runCatching {
            BackupArchive.stageRestore(ByteArrayInputStream(encryptedBytes), wrongStage, passphraseProvider = { "SalahBanget".toCharArray() })
        }.exceptionOrNull()
        assertTrue(wrongErr is GeneralSecurityException)
        assertFalse(wrongStage.exists())

        // Terpotong (anti-truncation AAD) -> ditolak.
        val truncBytes = encryptedBytes.copyOf(encryptedBytes.size - 24)
        val truncStage = File(tmp.root, "stage_trunc")
        val truncErr = runCatching {
            BackupArchive.stageRestore(ByteArrayInputStream(truncBytes), truncStage, passphraseProvider = { pass.copyOf() })
        }.exceptionOrNull()
        assertTrue(truncErr is GeneralSecurityException)
        assertFalse(truncStage.exists())

        // Passphrase benar -> seluruh catatan dan lampiran pulih utuh.
        val okStage = File(tmp.root, "stage_ok")
        val restored = BackupArchive.stageRestore(ByteArrayInputStream(encryptedBytes), okStage, passphraseProvider = { pass.copyOf() })
        assertEquals(12, restored.records.size)
        assertTrue(restored.manifest!!.encrypted)
        assertArrayEquals(media["img_2026_0.jpg"], restored.stagedFiles["img_2026_0.jpg"]!!.readBytes())
    }

    @Test fun sha256TamperRejectsArchiveAndCleansStaging() {
        val attachDir = tmp.newFolder("att_tamper")
        seedMedia(attachDir)
        val out = ByteArrayOutputStream()
        BackupArchive.exportZip(sample100Records().take(3), attachDir, out)

        // Salin ulang ZIP tetapi ubah 1 byte di dalam gambar tanpa memperbarui checksums.json.
        val tampered = ByteArrayOutputStream()
        ZipOutputStream(tampered).use { zos ->
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val data = zis.readBytes()
                    if (e.name.endsWith(".jpg") && data.isNotEmpty()) {
                        data[0] = (data[0].toInt() xor 0xFF).toByte()
                    }
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(data)
                    zos.closeEntry()
                    zis.closeEntry()
                }
            }
        }

        val stageDir = File(tmp.root, "stage_tampered")
        val err = runCatching {
            BackupArchive.stageRestore(ByteArrayInputStream(tampered.toByteArray()), stageDir)
        }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("Checksum", ignoreCase = true))
        assertFalse(stageDir.exists())
    }

    @Test fun zipPathTraversalAndOversizedEntryAreRejected() {
        assertTrue(BackupArchive.isSafeZipEntryName("manifest.json"))
        assertTrue(BackupArchive.isSafeZipEntryName("attachments/image/img_123.jpg"))
        assertFalse(BackupArchive.isSafeZipEntryName("../catatan.db"))
        assertFalse(BackupArchive.isSafeZipEntryName("attachments/image/../../catatan.db"))
        assertFalse(BackupArchive.isSafeZipEntryName("attachments\\image\\img_1.jpg"))
        assertFalse(BackupArchive.isSafeZipEntryName("/etc/passwd"))
        assertFalse(BackupArchive.isSafeZipEntryName("notes.json\u0000.txt"))

        val traversalZip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("attachments/image/../../evil.txt"))
                zos.write("bad".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        val stageTrav = File(tmp.root, "stage_trav")
        assertTrue(runCatching { BackupArchive.stageRestore(ByteArrayInputStream(traversalZip), stageTrav) }.isFailure)
        assertFalse(stageTrav.exists())

        // Simulasi ZIP bomb pada entri lampiran (> 32 MB tak terkompresi) yang terkompresi sangat kecil.
        val bombZip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("attachments/audio/aud_bomb.wav"))
                val chunk = ByteArray(1024 * 1024)
                repeat(34) { zos.write(chunk) }
                zos.closeEntry()
            }
        }.toByteArray()
        assertTrue(bombZip.size < 256 * 1024)
        val stageBomb = File(tmp.root, "stage_bomb")
        val err = runCatching { BackupArchive.stageRestore(ByteArrayInputStream(bombZip), stageBomb) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("melebihi batas", ignoreCase = true))
        assertFalse(stageBomb.exists())
    }

    @Test fun legacyJsonAndMissingAttachmentsAreHandledSafely() {
        val legacyJson = """{"app":"Catatanku","version":2,"notes":[{"title":"Jadwal Lama","body":"Tes","tags":["lama"],"pinned":true,"folder":"Arsip","archived":false,"created":10,"updated":20}]}"""
        val staged = BackupArchive.stageRestore(ByteArrayInputStream(legacyJson.toByteArray()), File(tmp.root, "stage_legacy"))
        assertEquals(null, staged.manifest)
        assertEquals(1, staged.records.size)
        assertEquals("Jadwal Lama", staged.records.single().note.title)

        val noteWithMissing = Note(title = "Foto hilang", body = "", attachments = listOf("img_ada.jpg", "img_hilang.jpg", "aud_hilang.wav"))
        assertEquals(listOf("img_hilang.jpg", "aud_hilang.wav"), noteWithMissing.missingAttachments(setOf("img_ada.jpg")))
        assertTrue(BackupCodec.SAFE_ATTACHMENT.matches("img_ada.jpg"))
        assertFalse(BackupCodec.SAFE_ATTACHMENT.matches("../evil.jpg"))
    }

    @Test fun cancellationDuringRestoreCleansStaging() {
        val attachDir = tmp.newFolder("att_cancel")
        seedMedia(attachDir)
        val out = ByteArrayOutputStream()
        BackupArchive.exportZip(sample100Records().take(5), attachDir, out)

        var checks = 0
        val stageCancel = File(tmp.root, "stage_cancel")
        val err = runCatching {
            BackupArchive.stageRestore(
                input = ByteArrayInputStream(out.toByteArray()),
                stagingDir = stageCancel,
                onCheckCancelled = {
                    checks++
                    if (checks >= 4) throw InterruptedException("dibatalkan pengguna")
                },
            )
        }.exceptionOrNull()
        assertTrue(err is InterruptedException)
        assertFalse(stageCancel.exists())
    }

    @Test fun backupProgressFractionBounds() {
        assertEquals(0f, BackupProgress(BackupPhase.PREPARING, 0, 0).fraction, 0.0001f)
        assertEquals(0.5f, BackupProgress(BackupPhase.WRITING, 5, 10).fraction, 0.0001f)
        assertEquals(1f, BackupProgress(BackupPhase.DONE, 15, 10).fraction, 0.0001f)
    }
}
