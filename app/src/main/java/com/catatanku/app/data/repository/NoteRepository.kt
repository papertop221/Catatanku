package com.catatanku.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.catatanku.app.data.backup.BackupArchive
import com.catatanku.app.data.database.NoteDb
import com.catatanku.app.domain.model.BackupNoteRecord
import com.catatanku.app.domain.model.BackupPhase
import com.catatanku.app.domain.model.BackupProgress
import com.catatanku.app.domain.model.BackupRestoreReport
import com.catatanku.app.domain.model.BackupVersionEntry
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.Version
import com.catatanku.app.media.Attachments
import com.catatanku.app.reminders.Reminders
import com.catatanku.app.reminders.toLocal
import com.catatanku.app.reminders.toMillis
import com.catatanku.app.security.DeviceVaultKey
import com.catatanku.app.security.SecurityPrefs
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * Repositori terpusat untuk akses catatan, versi, lampiran terenkripsi, embedding, dan cadangan/pemulihan atomik.
 */
class NoteRepository(private val ctx: Context) {
    val db: NoteDb = NoteDb.get(ctx)
    private val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun allNotes(includeVault: Boolean = true): List<Note> = db.all(includeVault)
    fun nonVaultNotes(): List<Note> = db.nonVault()
    fun vaultNotes(): List<Note> = db.vaultOnly()
    fun vaultCount(): Int = db.vaultCount()
    fun getNote(id: Long): Note? = db.get(id)
    fun flush() = db.flush()
    fun purgeOldTrash(): Boolean = db.purgeOldTrash()

    fun emptyTrash(vaultOnly: Boolean = false) {
        db.emptyTrash(vaultOnly)
        Attachments.cleanup(ctx, db.all(includeVault = true))
    }

    fun deleteForever(id: Long) {
        db.delete(id)
        Reminders.clear(ctx, id)
        Attachments.cleanup(ctx, db.all(includeVault = true))
    }

    fun moveFolder(from: String, to: String) = db.moveFolder(from, to)
    fun versions(noteId: Long): List<Version> = db.versions(noteId)
    fun addVersion(noteId: Long, title: String, body: String, saved: Long) = db.addVersion(noteId, title, body, saved)

    fun persist(n: Note, before: Note? = null): Long {
        val id = db.upsert(n)
        val alarmSame = before != null && before.remindAt == n.remindAt && before.remindRepeat == n.remindRepeat &&
            before.remindAlarm == n.remindAlarm && before.remindFired == n.remindFired &&
            before.inTrash == n.inTrash && before.vault == n.vault
        val newWithoutReminder = n.id == 0L && !n.hasReminder
        if (!alarmSame && !newWithoutReminder) Reminders.sync(ctx, n.copy(id = id))
        prefs.edit().putBoolean("dirty", true).apply()
        return id
    }

    fun buildBackupRecords(includeVault: Boolean = true): List<BackupNoteRecord> {
        val all = db.all(includeVault)
        val versionsByNote = db.allVersionsByNoteId()
        return all.map { n ->
            BackupNoteRecord(
                note = n,
                versions = versionsByNote[n.id].orEmpty().map { BackupVersionEntry(it.title, it.body, it.saved) },
            )
        }
    }

    fun exportZipTo(
        uri: Uri,
        appVersion: String,
        passphrase: CharArray?,
        onCheckCancelled: () -> Unit,
        onProgress: (BackupProgress) -> Unit,
    ): Int {
        val encrypted = passphrase != null && passphrase.isNotEmpty()
        val records = buildBackupRecords(includeVault = encrypted)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            BackupArchive.exportZip(
                records = records,
                attachmentsDir = Attachments.dir(ctx),
                out = out,
                appVersion = appVersion,
                passphrase = passphrase,
                excludeVaultWhenPlain = !encrypted,
                attachmentOpener = { file -> Attachments.openDecryptedStream(ctx, file) },
                onCheckCancelled = onCheckCancelled,
                onProgress = onProgress,
            )
        } ?: error("Berkas tujuan tidak bisa ditulisi.")
        return records.size
    }

    /**
     * Memulihkan cadangan (ZIP lengkap, ZIP terenkripsi `CTKE`/`CTKH`, atau JSON lama) melalui direktori staging
     * dan transaksi SQLite sehingga kegagalan atau pembatalan tidak pernah merusak data yang sudah ada.
     */
    fun restoreFromUri(
        uri: Uri,
        passphraseProvider: (() -> CharArray?)?,
        onCheckCancelled: () -> Unit,
        onProgress: (BackupProgress) -> Unit,
    ): BackupRestoreReport {
        if (!Attachments.isSafeExternalUri(ctx, uri)) error("Sumber berkas tidak diizinkan.")
        val stagingDir = File(ctx.cacheDir, "restore_staging_${System.currentTimeMillis()}")
        val copiedNewFiles = ArrayList<File>()
        try {
            val staged = ctx.contentResolver.openInputStream(uri)?.use { inp ->
                BackupArchive.stageRestore(inp, stagingDir, passphraseProvider, onCheckCancelled, onProgress)
            } ?: error("Berkas cadangan tidak bisa dibuka.")

            onCheckCancelled()
            val existing = db.all(includeVault = true)
            val seen = existing.map { Triple(it.title, it.body, it.attachments) }.toSet()
            val now = System.currentTimeMillis()
            val toInsert = staged.records.filter {
                Triple(it.note.title, it.note.body, it.note.attachments) !in seen
            }.map { rec ->
                val n = rec.note
                val fixed = if (!n.hasReminder || n.remindAt > now) n
                else n.remindRepeat.next(n.remindAt.toLocal(), LocalDateTime.now())?.let { n.copy(remindAt = it.toMillis(), remindFired = false) }
                    ?: n.copy(remindFired = true)
                rec.copy(note = fixed)
            }

            onProgress(BackupProgress(BackupPhase.COMMITTING, 0, 1, "Menyimpan catatan & lampiran terenkripsi…"))
            var restoredAttachCount = 0
            for ((name, srcFile) in staged.stagedFiles) {
                onCheckCancelled()
                val safeName = File(name).name
                val dst = Attachments.file(ctx, safeName)
                if (!dst.exists()) {
                    FileInputStream(srcFile).use { inp -> Attachments.writeEncryptedFromStream(ctx, safeName, inp) }
                    copiedNewFiles += dst
                    restoredAttachCount++
                }
            }

            val inserted = db.importInTransaction(toInsert)
            for (n in inserted) {
                if (n.hasReminder && !n.inTrash && !n.remindFired) Reminders.sync(ctx, n)
            }

            val existingFiles = Attachments.existingNames(ctx)
            val missing = inserted.flatMap { it.missingAttachments(existingFiles) }.distinct()
            val restoredVersions = toInsert.sumOf { it.versions.size }
            onProgress(BackupProgress(BackupPhase.DONE, 1, 1))
            return BackupRestoreReport(
                importedNotes = inserted.size,
                skippedDuplicates = staged.records.size - toInsert.size,
                restoredAttachments = restoredAttachCount,
                restoredVersions = restoredVersions,
                missingAttachments = missing,
            )
        } catch (t: Throwable) {
            copiedNewFiles.forEach { DeviceVaultKey.shredFile(it) }
            throw t
        } finally {
            stagingDir.listFiles()?.forEach { if (it.isFile) DeviceVaultKey.shredFile(it) }
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Pencarian semantik dihapus di 1.10: vektor lama di tabel `embeddings` dan berkas model embedding
     * dimusnahkan sekali, agar tidak ada sisa data turunan catatan yang tertinggal. Skema tabel tetap (migrasi).
     */
    fun purgeSemanticData() {
        if (prefs.getBoolean("semantic_purged", false)) return
        db.deleteEmbeddingsExcept(emptySet())
        val dir = java.io.File(ctx.filesDir, "models")
        listOf("embedding.litertlm", "embedding_import.tmp").forEach { name ->
            java.io.File(dir, name).takeIf { it.exists() }?.let { DeviceVaultKey.shredFile(it); it.delete() }
        }
        prefs.edit().putBoolean("semantic_purged", true).apply()
    }
}

object AutoBackup {
    const val KEEP = 7

    fun write(ctx: Context, repo: NoteRepository, tree: Uri, appVersion: String = ""): String {
        val hybridKeys = SecurityPrefs.autoBackupHybridKeys(ctx)
        if (hybridKeys == null && SecurityPrefs.isLockEnabled(ctx)) {
            error("Sandi pemulihan belum disetel; cadangan otomatis ditahan agar tidak menyimpan berkas polos.")
        }
        val cr = ctx.contentResolver
        val dirId = DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val existing = cr.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1).orEmpty()) } }.orEmpty()
            .filter {
                it.second.startsWith("catatanku-") &&
                    (it.second.endsWith(".enc.zip") || it.second.endsWith(".zip") || it.second.endsWith(".json"))
            }

        val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val encrypted = hybridKeys != null
        val name = if (encrypted) "catatanku-$datePart.enc.zip" else "catatanku-$datePart.zip"
        val mime = if (encrypted) "application/octet-stream" else "application/zip"
        val target = existing.firstOrNull { it.second == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
            ?: DocumentsContract.createDocument(cr, dir, mime, name) ?: error("berkas tidak bisa dibuat")

        val records = repo.buildBackupRecords(includeVault = encrypted)
        val hybrid = hybridKeys?.let { BackupArchive.HybridEncryption(it.publicKeyBase64, it.wrappedPrivateKeyBase64) }
        cr.openOutputStream(target, "wt")!!.use { out ->
            BackupArchive.exportZip(
                records = records,
                attachmentsDir = Attachments.dir(ctx),
                out = out,
                appVersion = appVersion,
                hybridEncryption = hybrid,
                excludeVaultWhenPlain = !encrypted,
                attachmentOpener = { file -> Attachments.openDecryptedStream(ctx, file) },
            )
        }
        existing.filter { it.second != name }.sortedByDescending { it.second }.drop(KEEP - 1).forEach {
            runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }
        }
        return name
    }

    fun folderLabel(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Penyimpanan" } }.getOrDefault("Folder terpilih")
}
