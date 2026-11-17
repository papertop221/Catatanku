package com.catatanku.app.data.backup

import com.catatanku.app.domain.model.BackupManifest
import com.catatanku.app.domain.model.BackupNoteRecord
import com.catatanku.app.domain.model.BackupVersionEntry
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.domain.usecase.TextTools
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Serialisasi & deserialisasi JSON cadangan tanpa ketergantungan Android, mendukung format lama (v1–v3)
 * maupun format lengkap (v4 dengan riwayat versi dan lampiran).
 */
object BackupCodec {
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    const val MAX_NOTES = 20_000
    const val MAX_VERSIONS_PER_NOTE = 20
    val SAFE_ATTACHMENT = Regex("^(?:img|aud)_[A-Za-z0-9_.-]{1,80}$")

    fun writeJson(notes: List<Note>, out: OutputStream, pretty: Boolean = false) {
        writeRecordsJson(notes.map { BackupNoteRecord(it) }, out, pretty = pretty)
    }

    fun writeRecordsJson(records: List<BackupNoteRecord>, out: OutputStream, pretty: Boolean = false) {
        JsonStreamWriter(out, pretty = pretty).use { w ->
            w.beginObject()
            w.name("app").value("Catatanku")
            w.name("version").value(4)
            w.name("notes").beginArray()
            records.forEach { rec ->
                val n = rec.note
                w.beginObject()
                w.name("title").value(n.title)
                w.name("body").value(n.body)
                w.name("tags").beginArray(); n.tags.forEach { w.value(it) }; w.endArray()
                w.name("pinned").value(n.pinned)
                w.name("folder").value(n.folder)
                w.name("archived").value(n.archived)
                w.name("created").value(n.created)
                w.name("updated").value(n.updated)
                if (n.inTrash) w.name("deleted").value(n.deletedAt)
                if (n.vault) w.name("vault").value(true)
                if (n.hasReminder) {
                    w.name("remindAt").value(n.remindAt)
                    w.name("remindRepeat").value(n.remindRepeat.name)
                    w.name("remindAlarm").value(n.remindAlarm)
                    w.name("remindFired").value(n.remindFired)
                }
                if (n.attachments.isNotEmpty()) {
                    w.name("attachments").beginArray(); n.attachments.forEach { w.value(it) }; w.endArray()
                }
                if (rec.versions.isNotEmpty()) {
                    w.name("versions").beginArray()
                    rec.versions.take(MAX_VERSIONS_PER_NOTE).forEach { v ->
                        w.beginObject()
                        w.name("title").value(v.title)
                        w.name("body").value(v.body)
                        w.name("saved").value(v.saved)
                        w.endObject()
                    }
                    w.endArray()
                }
                w.endObject()
            }
            w.endArray()
            w.endObject()
            w.flush()
        }
    }

    fun toJson(notes: List<Note>): String =
        ByteArrayOutputStream().also { writeJson(notes, it, pretty = true) }.toString(Charsets.UTF_8.name())

    fun fromJson(text: String): List<Note> = readRecordsJson(text).map { it.note }

    fun readRecordsJson(text: String): List<BackupNoteRecord> {
        val root = BoundedJson.parseObject(text)
        val arr = root["notes"] as? List<*> ?: error("Berkas cadangan tidak memuat daftar catatan.")
        val now = System.currentTimeMillis()
        return arr.take(MAX_NOTES).map { item ->
            @Suppress("UNCHECKED_CAST")
            val o = item as? Map<String, Any?> ?: error("Entri catatan tidak valid.")
            val rawTags = (o["tags"] as? List<*>) ?: emptyList<Any?>()
            val rawAtt = (o["attachments"] as? List<*>) ?: emptyList<Any?>()
            val rawVer = (o["versions"] as? List<*>) ?: emptyList<Any?>()
            val note = Note(
                title = str(o["title"]).replace('\n', ' ').replace('\r', ' ').take(500),
                body = str(o["body"]).take(500_000),
                tags = rawTags.take(10).map { TextTools.normalizeTag(str(it)) }.filter { it.isNotEmpty() }.distinct(),
                pinned = bool(o["pinned"]),
                folder = str(o["folder"]).replace('\n', ' ').replace('\r', ' ').trim().take(30),
                archived = bool(o["archived"]),
                created = num(o["created"], now).coerceAtLeast(0L),
                updated = num(o["updated"], now).coerceAtLeast(0L),
                deletedAt = num(o["deleted"], 0L).coerceAtLeast(0L),
                remindAt = num(o["remindAt"], 0L).coerceAtLeast(0L),
                remindRepeat = runCatching { Repeat.valueOf(str(o["remindRepeat"], "None")) }.getOrDefault(Repeat.None),
                remindAlarm = bool(o["remindAlarm"]),
                remindFired = bool(o["remindFired"]),
                attachments = rawAtt.take(20).map { str(it) }.filter { SAFE_ATTACHMENT.matches(it) }.distinct(),
                vault = bool(o["vault"]),
            )
            val versions = rawVer.take(MAX_VERSIONS_PER_NOTE).mapNotNull { vItem ->
                @Suppress("UNCHECKED_CAST")
                val vo = vItem as? Map<String, Any?> ?: return@mapNotNull null
                BackupVersionEntry(
                    title = str(vo["title"]).replace('\n', ' ').take(500),
                    body = str(vo["body"]).take(500_000),
                    saved = num(vo["saved"], now).coerceAtLeast(0L),
                )
            }
            BackupNoteRecord(note, versions)
        }
    }

    fun writeManifestJson(m: BackupManifest, out: OutputStream) {
        JsonStreamWriter(out, pretty = true).use { w ->
            w.beginObject()
            w.name("app").value(m.app)
            w.name("formatVersion").value(m.formatVersion)
            w.name("appVersion").value(m.appVersion)
            w.name("createdAt").value(m.createdAt)
            w.name("noteCount").value(m.noteCount)
            w.name("attachmentCount").value(m.attachmentCount)
            w.name("versionCount").value(m.versionCount)
            w.name("includesVersions").value(m.includesVersions)
            w.name("encrypted").value(m.encrypted)
            w.endObject()
            w.flush()
        }
    }

    fun readManifestJson(text: String): BackupManifest {
        val o = BoundedJson.parseObject(text)
        val app = str(o["app"])
        if (app != "Catatanku") error("Manifest bukan milik Catatanku.")
        return BackupManifest(
            app = app,
            formatVersion = num(o["formatVersion"], 4).toInt(),
            appVersion = str(o["appVersion"]),
            createdAt = num(o["createdAt"], 0L).coerceAtLeast(0L),
            noteCount = num(o["noteCount"], 0L).toInt().coerceAtLeast(0),
            attachmentCount = num(o["attachmentCount"], 0L).toInt().coerceAtLeast(0),
            versionCount = num(o["versionCount"], 0L).toInt().coerceAtLeast(0),
            includesVersions = bool(o["includesVersions"], true),
            encrypted = bool(o["encrypted"], false),
        )
    }

    fun writeChecksumsJson(sums: Map<String, String>, out: OutputStream) {
        JsonStreamWriter(out, pretty = true).use { w ->
            w.beginObject()
            w.name("algorithm").value("SHA-256")
            w.name("files").beginObject()
            sums.toSortedMap().forEach { (path, hex) -> w.name(path).value(hex) }
            w.endObject()
            w.endObject()
            w.flush()
        }
    }

    fun readChecksumsJson(text: String): Map<String, String> {
        val o = BoundedJson.parseObject(text)
        val alg = str(o["algorithm"])
        if (!alg.equals("SHA-256", ignoreCase = true)) error("Algoritma checksum tidak didukung: $alg")
        @Suppress("UNCHECKED_CAST")
        val files = o["files"] as? Map<String, Any?> ?: error("Daftar checksum tidak ditemukan.")
        val out = LinkedHashMap<String, String>(files.size)
        for ((k, v) in files) {
            val hex = str(v).lowercase()
            if (hex.length != 64 || hex.any { it !in "0123456789abcdef" }) {
                error("Format checksum SHA-256 tidak valid untuk $k")
            }
            out[k] = hex
        }
        return out
    }

    private fun str(v: Any?, default: String = ""): String = (v as? String) ?: default
    private fun bool(v: Any?, default: Boolean = false): Boolean = (v as? Boolean) ?: default
    private fun num(v: Any?, default: Long): Long = when (v) {
        is Number -> v.toLong()
        else -> default
    }
}
