package com.catatanku.app.domain.model

enum class BackupPhase(val label: String) {
    PREPARING("Menyiapkan cadangan…"),
    WRITING("Menulis arsip cadangan…"),
    VERIFYING("Memeriksa keutuhan SHA-256…"),
    STAGING("Menyiapkan lampiran…"),
    COMMITTING("Menyimpan ke basis data…"),
    DONE("Selesai"),
    CANCELLED("Dibatalkan"),
}

data class BackupProgress(
    val phase: BackupPhase,
    val done: Int = 0,
    val total: Int = 0,
    val detail: String = "",
) {
    val fraction: Float
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Salinan riwayat versi yang disertakan dalam arsip cadangan. */
data class BackupVersionEntry(
    val title: String,
    val body: String,
    val saved: Long,
)

/** Satu catatan beserta riwayat versinya untuk ekspor/impor lengkap. */
data class BackupNoteRecord(
    val note: Note,
    val versions: List<BackupVersionEntry> = emptyList(),
)

data class BackupManifest(
    val app: String = "Catatanku",
    val formatVersion: Int = 4,
    val appVersion: String = "",
    val createdAt: Long = 0L,
    val noteCount: Int = 0,
    val attachmentCount: Int = 0,
    val versionCount: Int = 0,
    val includesVersions: Boolean = true,
    val encrypted: Boolean = false,
)

data class BackupRestoreReport(
    val importedNotes: Int,
    val skippedDuplicates: Int,
    val restoredAttachments: Int,
    val restoredVersions: Int,
    val missingAttachments: List<String>,
)
