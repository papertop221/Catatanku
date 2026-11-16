package com.catatanku.app.data.backup

import com.catatanku.app.domain.model.BackupManifest
import com.catatanku.app.domain.model.BackupNoteRecord
import com.catatanku.app.domain.model.BackupPhase
import com.catatanku.app.domain.model.BackupProgress
import com.catatanku.app.security.BackupCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pembaca dan penulis arsip cadangan lengkap (`catatanku-backup.zip`) dengan validasi SHA-256,
 * perlindungan ZIP path traversal & ZIP bomb, ekstraksi ke direktori staging, serta kompatibilitas JSON lama.
 */
object BackupArchive {
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_NOTES = "notes.json"
    const val ENTRY_CHECKSUMS = "checksums.json"
    const val DIR_IMAGES = "attachments/image/"
    const val DIR_AUDIO = "attachments/audio/"

    const val MAX_ARCHIVE_ENTRIES = 25_000
    const val MAX_JSON_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_ATTACHMENT_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024

    data class StagedRestore(
        val manifest: BackupManifest?,
        val records: List<BackupNoteRecord>,
        val stagedFiles: Map<String, File>, // nama lampiran -> berkas terverifikasi di stagingDir
    )

    fun attachmentEntryPath(name: String): String {
        require(BackupCodec.SAFE_ATTACHMENT.matches(name)) { "Nama lampiran tidak aman: $name" }
        return if (name.startsWith("img_")) "$DIR_IMAGES$name" else "$DIR_AUDIO$name"
    }

    fun isSafeZipEntryName(entryName: String): Boolean {
        if (entryName.isEmpty() || entryName.length > 260) return false
        if ('\u0000' in entryName || '\\' in entryName || entryName.startsWith('/')) return false
        val parts = entryName.split('/')
        if (parts.any { it == ".." || it == "." }) return false
        return when (entryName) {
            ENTRY_MANIFEST, ENTRY_NOTES, ENTRY_CHECKSUMS -> true
            DIR_IMAGES, DIR_AUDIO, "attachments/" -> true
            else -> {
                val fileName = when {
                    entryName.startsWith(DIR_IMAGES) -> entryName.removePrefix(DIR_IMAGES)
                    entryName.startsWith(DIR_AUDIO) -> entryName.removePrefix(DIR_AUDIO)
                    else -> return false
                }
                '/' !in fileName && BackupCodec.SAFE_ATTACHMENT.matches(fileName)
            }
        }
    }

    /**
     * Menulis arsip `catatanku-backup.zip` secara streaming ke [out].
     * Bila [passphrase] disediakan, seluruh arsip ZIP dienkripsi menggunakan AES-256-GCM ([BackupCrypto]).
     */
    fun exportZip(
        records: List<BackupNoteRecord>,
        attachmentsDir: File,
        out: OutputStream,
        appVersion: String = "",
        passphrase: CharArray? = null,
        onCheckCancelled: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
    ) {
        val attachmentNames = records.flatMap { it.note.attachments }
            .filter { BackupCodec.SAFE_ATTACHMENT.matches(it) }
            .distinct()
            .filter { File(attachmentsDir, it).isFile }
        val totalSteps = attachmentNames.size + 3
        onProgress(BackupProgress(BackupPhase.PREPARING, 0, totalSteps, "Menyiapkan ${records.size} catatan…"))

        val writePlainZip: (OutputStream) -> Unit = { zipTarget ->
            val checksums = LinkedHashMap<String, String>()
            var done = 0
            ZipOutputStream(BufferedOutputStream(zipTarget, 32 * 1024)).use { zos ->
                onCheckCancelled()
                // 1. notes.json
                val notesBytes = ByteArrayOutputStream().also { BackupCodec.writeRecordsJson(records, it, pretty = false) }.toByteArray()
                checksums[ENTRY_NOTES] = sha256(notesBytes)
                writeZipBytes(zos, ENTRY_NOTES, notesBytes)
                done++
                onProgress(BackupProgress(BackupPhase.WRITING, done, totalSteps, "Menulis catatan…"))

                // 2. manifest.json
                val manifest = BackupManifest(
                    appVersion = appVersion,
                    createdAt = System.currentTimeMillis(),
                    noteCount = records.size,
                    attachmentCount = attachmentNames.size,
                    versionCount = records.sumOf { it.versions.size },
                    includesVersions = true,
                    encrypted = passphrase != null && passphrase.isNotEmpty(),
                )
                val manifestBytes = ByteArrayOutputStream().also { BackupCodec.writeManifestJson(manifest, it) }.toByteArray()
                checksums[ENTRY_MANIFEST] = sha256(manifestBytes)
                writeZipBytes(zos, ENTRY_MANIFEST, manifestBytes)
                done++
                onProgress(BackupProgress(BackupPhase.WRITING, done, totalSteps, "Menulis manifest…"))

                // 3. attachments (streaming per file, O(1) RAM)
                for (name in attachmentNames) {
                    onCheckCancelled()
                    val file = File(attachmentsDir, name)
                    if (!file.isFile) continue
                    val entryPath = attachmentEntryPath(name)
                    zos.putNextEntry(ZipEntry(entryPath))
                    val digest = streamFileAndHash(file, zos, onCheckCancelled)
                    zos.closeEntry()
                    checksums[entryPath] = digest
                    done++
                    onProgress(BackupProgress(BackupPhase.WRITING, done, totalSteps, "Menyalin lampiran ($done/$totalSteps)…"))
                }

                onCheckCancelled()
                // 4. checksums.json
                val sumBytes = ByteArrayOutputStream().also { BackupCodec.writeChecksumsJson(checksums, it) }.toByteArray()
                writeZipBytes(zos, ENTRY_CHECKSUMS, sumBytes)
                done++
                onProgress(BackupProgress(BackupPhase.DONE, totalSteps, totalSteps))
            }
        }

        if (passphrase != null && passphrase.isNotEmpty()) {
            // Gunakan file/piped streaming? Untuk menghindari deadlock pipe pada satu thread, pakai temp file atau chunked pipe.
            // Di Android, temp file dihapus segera dalam finally; di JVM tanpa tempDir, buat temp file sistem.
            val tmpZip = File.createTempFile("catatanku_enc_", ".zip")
            try {
                FileOutputStream(tmpZip).use { writePlainZip(it) }
                onCheckCancelled()
                FileInputStream(tmpZip).use { plainIn -> BackupCrypto.encrypt(passphrase, plainIn, out) }
            } finally {
                tmpZip.delete()
            }
        } else {
            writePlainZip(out)
        }
    }

    /**
     * Membaca dan memverifikasi cadangan (ZIP baru, ZIP terenkripsi, atau JSON lama) ke [stagingDir]
     * tanpa mengubah data live. Apabila ada checksum gagal, ZIP bomb, path traversal, atau pembatalan,
     * [stagingDir] dibersihkan bersih sebelum melempar pengecualian.
     */
    fun stageRestore(
        input: InputStream,
        stagingDir: File,
        passphraseProvider: (() -> CharArray?)? = null,
        onCheckCancelled: () -> Unit = {},
        onProgress: (BackupProgress) -> Unit = {},
    ): StagedRestore {
        stagingDir.mkdirs()
        var decryptedTemp: File? = null
        try {
            val buffered = BufferedInputStream(input, 32 * 1024)
            buffered.mark(16)
            val header = ByteArray(8)
            val nRead = readAtMost(buffered, header)
            buffered.reset()

            val streamToParse: InputStream = if (BackupCrypto.isEncryptedHeader(header.copyOf(nRead))) {
                val pass = passphraseProvider?.invoke()
                    ?: throw IllegalStateException("Cadangan ini terenkripsi; masukkan kata sandi untuk membukanya.")
                val tmp = File(stagingDir, "_decrypted.tmp")
                decryptedTemp = tmp
                FileOutputStream(tmp).use { out -> BackupCrypto.decrypt(pass, buffered, out) }
                BufferedInputStream(FileInputStream(tmp), 32 * 1024).also {
                    it.mark(16)
                }
            } else {
                buffered
            }

            streamToParse.mark(16)
            val innerHead = ByteArray(4)
            val innerRead = readAtMost(streamToParse, innerHead)
            streamToParse.reset()

            val isZip = innerRead >= 2 && innerHead[0] == 'P'.code.toByte() && innerHead[1] == 'K'.code.toByte()
            val result = if (isZip) {
                readAndVerifyZip(streamToParse, stagingDir, onCheckCancelled, onProgress)
            } else {
                // Kompatibilitas mundur cadangan JSON versi lama (v1–v3)
                onProgress(BackupProgress(BackupPhase.VERIFYING, 0, 1, "Membaca cadangan JSON…"))
                val jsonText = readBoundedBytes(streamToParse, BackupCodec.MAX_IMPORT_BYTES, onCheckCancelled).toString(Charsets.UTF_8)
                val records = BackupCodec.readRecordsJson(jsonText)
                onProgress(BackupProgress(BackupPhase.VERIFYING, 1, 1))
                StagedRestore(manifest = null, records = records, stagedFiles = emptyMap())
            }
            decryptedTemp?.delete()
            return result
        } catch (t: Throwable) {
            decryptedTemp?.delete()
            stagingDir.deleteRecursively()
            throw t
        }
    }

    private fun readAndVerifyZip(
        zipStream: InputStream,
        stagingDir: File,
        onCheckCancelled: () -> Unit,
        onProgress: (BackupProgress) -> Unit,
    ): StagedRestore {
        val canonicalStage = stagingDir.canonicalFile
        var entryCount = 0
        var totalBytes = 0L

        var manifestBytes: ByteArray? = null
        var notesBytes: ByteArray? = null
        var checksumsBytes: ByteArray? = null
        val actualHashes = LinkedHashMap<String, String>()
        val stagedFiles = LinkedHashMap<String, File>()

        ZipInputStream(zipStream).use { zis ->
            while (true) {
                onCheckCancelled()
                val entry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) error("Arsip melebihi batas jumlah berkas ($MAX_ARCHIVE_ENTRIES).")
                val name = entry.name
                if (!isSafeZipEntryName(name)) error("Nama berkas tidak aman dalam arsip: $name")
                if (entry.isDirectory) { zis.closeEntry(); continue }

                when (name) {
                    ENTRY_MANIFEST -> {
                        val data = readBoundedBytes(zis, MAX_JSON_ENTRY_BYTES, onCheckCancelled)
                        totalBytes += data.size
                        checkTotal(totalBytes)
                        manifestBytes = data
                        actualHashes[name] = sha256(data)
                    }
                    ENTRY_NOTES -> {
                        val data = readBoundedBytes(zis, MAX_JSON_ENTRY_BYTES, onCheckCancelled)
                        totalBytes += data.size
                        checkTotal(totalBytes)
                        notesBytes = data
                        actualHashes[name] = sha256(data)
                    }
                    ENTRY_CHECKSUMS -> {
                        val data = readBoundedBytes(zis, MAX_JSON_ENTRY_BYTES, onCheckCancelled)
                        totalBytes += data.size
                        checkTotal(totalBytes)
                        checksumsBytes = data
                    }
                    else -> {
                        val fileName = name.substringAfterLast('/')
                        val outFile = File(stagingDir, fileName).canonicalFile
                        if (outFile.parentFile != canonicalStage) error("Jalur lampiran keluar dari direktori staging.")
                        val (bytesWritten, hash) = copyZipEntryToFileAndHash(zis, outFile, MAX_ATTACHMENT_ENTRY_BYTES, onCheckCancelled)
                        totalBytes += bytesWritten
                        checkTotal(totalBytes)
                        actualHashes[name] = hash
                        stagedFiles[fileName] = outFile
                        onProgress(BackupProgress(BackupPhase.STAGING, stagedFiles.size, stagedFiles.size, "Menyiapkan lampiran ($fileName)…"))
                    }
                }
                zis.closeEntry()
            }
        }

        onCheckCancelled()
        onProgress(BackupProgress(BackupPhase.VERIFYING, 0, actualHashes.size, "Memverifikasi checksum SHA-256…"))

        val sumRaw = checksumsBytes ?: error("Arsip tidak memuat checksums.json.")
        val notesRaw = notesBytes ?: error("Arsip tidak memuat notes.json.")
        val manifestRaw = manifestBytes ?: error("Arsip tidak memuat manifest.json.")

        val expectedSums = BackupCodec.readChecksumsJson(sumRaw.toString(Charsets.UTF_8))
        if (expectedSums[ENTRY_MANIFEST] != actualHashes[ENTRY_MANIFEST]) {
            error("Checksum manifest.json tidak cocok; arsip mungkin rusak.")
        }
        if (expectedSums[ENTRY_NOTES] != actualHashes[ENTRY_NOTES]) {
            error("Checksum notes.json tidak cocok; arsip mungkin rusak.")
        }
        for ((path, expectedHash) in expectedSums) {
            val actual = actualHashes[path] ?: error("Berkas $path hilang dari arsip.")
            if (actual != expectedHash) error("Checksum $path tidak cocok; berkas rusak.")
        }
        for (path in actualHashes.keys) {
            if (path !in expectedSums) error("Berkas tak terverifikasi di dalam arsip: $path")
        }

        val manifest = BackupCodec.readManifestJson(manifestRaw.toString(Charsets.UTF_8))
        val records = BackupCodec.readRecordsJson(notesRaw.toString(Charsets.UTF_8))
        onProgress(BackupProgress(BackupPhase.VERIFYING, actualHashes.size, actualHashes.size))
        return StagedRestore(manifest = manifest, records = records, stagedFiles = stagedFiles)
    }

    private fun checkTotal(total: Long) {
        if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
            error("Ukuran arsip setelah diekstrak melebihi batas aman (${MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)} MB).")
        }
    }

    private fun writeZipBytes(zos: ZipOutputStream, entryName: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun streamFileAndHash(file: File, out: OutputStream, onCheckCancelled: () -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { inp ->
            while (true) {
                onCheckCancelled()
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyZipEntryToFileAndHash(
        zis: InputStream,
        outFile: File,
        maxBytes: Long,
        onCheckCancelled: () -> Unit,
    ): Pair<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        var total = 0L
        BufferedOutputStream(FileOutputStream(outFile), 16 * 1024).use { out ->
            while (true) {
                onCheckCancelled()
                val n = zis.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) error("Berkas lampiran di arsip melebihi batas ukuran.")
                md.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        return total to md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readBoundedBytes(inp: InputStream, maxBytes: Long, onCheckCancelled: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            onCheckCancelled()
            val n = inp.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) error("Berkas di dalam arsip terlalu besar (maks ${maxBytes / (1024 * 1024)} MB).")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readAtMost(inp: InputStream, dst: ByteArray): Int {
        var total = 0
        while (total < dst.size) {
            val n = inp.read(dst, total, dst.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
