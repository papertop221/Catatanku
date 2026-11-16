package com.catatanku.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.io.OutputStreamWriter

data class Note(
    val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val folder: String = "",
    val archived: Boolean = false,
    val deletedAt: Long = 0,          // 0 = tidak di sampah
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val remindAt: Long = 0,           // 0 = tanpa pengingat
    val remindRepeat: Repeat = Repeat.None,
    val remindAlarm: Boolean = false, // berdering seperti alarm sampai ditanggapi
    val remindFired: Boolean = false, // sudah berbunyi tetapi belum ditandai selesai
    val attachments: List<String> = emptyList(), // berkas lampiran di filesDir/attachments (img_*, aud_*)
) {
    val isBlank get() = title.isBlank() && body.isBlank() && attachments.isEmpty()
    val inTrash get() = deletedAt != 0L
    val hasReminder get() = remindAt != 0L
    val imageCount get() = attachments.count { it.startsWith("img_") }
    val audioCount get() = attachments.count { it.startsWith("aud_") }
    /** Judul untuk ditampilkan: judul, atau baris pertama isi bila judul kosong. */
    val heading get() = title.ifBlank {
        body.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: when {
                audioCount > 0 && imageCount > 0 -> "Catatan suara & gambar"
                audioCount > 0 -> "Catatan suara"
                imageCount > 0 -> "Catatan gambar"
                else -> ""
            }
    }
}

/** Salinan lama sebuah catatan, disimpan otomatis sebelum isinya berubah. */
data class Version(val id: Long, val noteId: Long, val title: String, val body: String, val saved: Long)

/** Penyimpanan lokal SQLite. Semua data ada di penyimpanan internal aplikasi. */
class NoteDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "catatan.db", null, 4) {
    companion object {
        const val KEEP_VERSIONS = 20
        @Volatile private var instance: NoteDb? = null
        /** Satu koneksi untuk seluruh aplikasi: layar dan penerima alarm memakai basis data yang sama. */
        fun get(context: Context): NoteDb = instance ?: synchronized(this) { instance ?: NoteDb(context).also { instance = it } }
        internal fun resetForTest() { instance?.close(); instance = null }
        private const val COLS = "id,title,body,tags,pinned,created,updated,folder,archived,deleted,remind_at,remind_repeat,remind_alarm,remind_fired,attachments"
        private const val VERSIONS_SQL =
            "CREATE TABLE IF NOT EXISTS versions(id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER NOT NULL, " +
                "title TEXT NOT NULL, body TEXT NOT NULL, saved INTEGER NOT NULL)"
    }

    init {
        // WAL: pembacaan di thread latar tidak menunggu penulisan, dan satu simpan cukup satu kali tulis.
        setWriteAheadLoggingEnabled(true)
    }

    /**
     * Simpan otomatis berjalan selagi mengetik, jadi tiap simpan harus murah. Dengan `synchronous=NORMAL`,
     * WAL tidak dipaksa ke penyimpanan pada tiap simpan. Basis data tetap konsisten bila aplikasi atau HP mati
     * mendadak; yang bisa hilang hanya suntingan beberapa detik terakhir, dan itu pun hanya bila HP mati mendadak
     * (bukan bila aplikasinya saja yang ditutup). [flush] memaksanya tertulis saat aplikasi ditinggalkan.
     */
    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

    /** Memindahkan isi WAL ke berkas utama dan memastikannya tertulis ke penyimpanan. */
    fun flush() {
        runCatching { writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, " +
                "tags TEXT NOT NULL, pinned INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
                "folder TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                "remind_at INTEGER NOT NULL DEFAULT 0, remind_repeat INTEGER NOT NULL DEFAULT 0, " +
                "remind_alarm INTEGER NOT NULL DEFAULT 0, remind_fired INTEGER NOT NULL DEFAULT 0, " +
                "attachments TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(VERSIONS_SQL)
        db.execSQL("CREATE INDEX IF NOT EXISTS versions_note ON versions(note_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {   // v1.0 -> v1.1: catatan lama tetap utuh
            db.execSQL("ALTER TABLE notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {   // v1.3 -> v1.4: pengingat dan riwayat versi
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_repeat INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_alarm INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_fired INTEGER NOT NULL DEFAULT 0")
            db.execSQL(VERSIONS_SQL)
            db.execSQL("CREATE INDEX IF NOT EXISTS versions_note ON versions(note_id)")
        }
        if (oldVersion < 4) {   // v1.7 -> v1.8: lampiran gambar dan catatan suara
            db.execSQL("ALTER TABLE notes ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun Cursor.note() = Note(
        id = getLong(0), title = getString(1), body = getString(2),
        tags = getString(3).split('\u001f').filter { it.isNotBlank() },
        pinned = getInt(4) != 0, created = getLong(5), updated = getLong(6),
        folder = getString(7), archived = getInt(8) != 0, deletedAt = getLong(9),
        remindAt = getLong(10), remindRepeat = Repeat.of(getInt(11)), remindAlarm = getInt(12) != 0, remindFired = getInt(13) != 0,
        attachments = getString(14).split('\u001f').filter { it.isNotBlank() },
    )

    private fun query(where: String?, vararg args: String): List<Note> = readableDatabase
        .rawQuery("SELECT $COLS FROM notes" + (where?.let { " WHERE $it" } ?: ""), args)
        .use { c -> buildList { while (c.moveToNext()) add(c.note()) } }

    fun all(): List<Note> = query(null)
    fun get(id: Long): Note? = query("id=?", id.toString()).firstOrNull()
    /** Catatan aktif yang punya pengingat; dipakai untuk menyetel ulang alarm setelah HP dinyalakan. */
    fun withReminder(): List<Note> = query("remind_at>0 AND deleted=0")

    /** Menyimpan catatan; mengembalikan id (baru bila id == 0). */
    fun upsert(n: Note): Long {
        val v = ContentValues().apply {
            put("title", n.title); put("body", n.body); put("tags", n.tags.joinToString("\u001f"))
            put("pinned", if (n.pinned) 1 else 0); put("created", n.created); put("updated", n.updated)
            put("folder", n.folder); put("archived", if (n.archived) 1 else 0); put("deleted", n.deletedAt)
            put("remind_at", n.remindAt); put("remind_repeat", n.remindRepeat.ordinal)
            put("remind_alarm", if (n.remindAlarm) 1 else 0); put("remind_fired", if (n.remindFired) 1 else 0)
            put("attachments", n.attachments.joinToString("\u001f"))
        }
        return if (n.id == 0L) writableDatabase.insertOrThrow("notes", null, v)
        else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
    }

    /** Mengubah hanya kolom pengingat, agar penerima alarm tidak menimpa suntingan yang sedang berjalan. */
    fun setReminder(id: Long, at: Long, fired: Boolean) {
        val v = ContentValues().apply { put("remind_at", at); put("remind_fired", if (fired) 1 else 0) }
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
        writableDatabase.delete("versions", "note_id=?", arrayOf(id.toString()))
    }

    private fun dropOrphanVersions() = writableDatabase.execSQL("DELETE FROM versions WHERE note_id NOT IN (SELECT id FROM notes)")

    fun emptyTrash() { writableDatabase.delete("notes", "deleted>0", null); dropOrphanVersions() }

    /** Sampah dibersihkan otomatis setelah 30 hari. Mengembalikan true bila ada yang dibersihkan. */
    fun purgeOldTrash(): Boolean {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val purged = writableDatabase.delete("notes", "deleted>0 AND deleted<?", arrayOf(cutoff.toString())) > 0
        if (purged) dropOrphanVersions()
        return purged
    }

    /** Memindahkan semua catatan aktif dari satu folder ke folder lain dalam satu transaksi SQLite. */
    fun moveFolder(from: String, to: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply { put("folder", to) }
            db.update("notes", v, "deleted=0 AND folder=?", arrayOf(from))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Riwayat versi ----------

    fun versions(noteId: Long): List<Version> = readableDatabase
        .rawQuery("SELECT id,note_id,title,body,saved FROM versions WHERE note_id=? ORDER BY saved DESC, id DESC", arrayOf(noteId.toString()))
        .use { c -> buildList { while (c.moveToNext()) add(Version(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))) } }

    /** Menyimpan salinan lama; hanya [KEEP_VERSIONS] terbaru per catatan yang dipertahankan. */
    fun addVersion(noteId: Long, title: String, body: String, saved: Long) {
        val v = ContentValues().apply { put("note_id", noteId); put("title", title); put("body", body); put("saved", saved) }
        writableDatabase.insertOrThrow("versions", null, v)
        writableDatabase.execSQL(
            "DELETE FROM versions WHERE note_id=? AND id NOT IN (SELECT id FROM versions WHERE note_id=? ORDER BY saved DESC, id DESC LIMIT $KEEP_VERSIONS)",
            arrayOf<Any>(noteId, noteId),
        )
    }
}

object Backup {
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    private val SAFE_ATTACHMENT = Regex("^(?:img|aud)_[A-Za-z0-9_.-]{1,80}$")

    /** Menulis cadangan langsung ke stream lewat JsonWriter agar hemat RAM (O(1) memori, tanpa string raksasa). */
    fun writeJson(notes: List<Note>, out: OutputStream, pretty: Boolean = false) {
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            if (pretty) w.setIndent("  ")
            w.beginObject()
            w.name("app").value("Catatanku")
            w.name("version").value(3)
            w.name("notes").beginArray()
            notes.forEach { n ->
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
                if (n.hasReminder) {
                    w.name("remindAt").value(n.remindAt)
                    w.name("remindRepeat").value(n.remindRepeat.name)
                    w.name("remindAlarm").value(n.remindAlarm)
                    w.name("remindFired").value(n.remindFired)
                }
                if (n.attachments.isNotEmpty()) {
                    w.name("attachments").beginArray(); n.attachments.forEach { w.value(it) }; w.endArray()
                }
                w.endObject()
            }
            w.endArray()
            w.endObject()
            w.flush()
        }
    }

    /** Semua catatan, termasuk yang di Sampah dan pengingatnya. Riwayat versi tidak ikut agar berkas tetap kecil. */
    fun toJson(notes: List<Note>): String = java.io.ByteArrayOutputStream().also { writeJson(notes, it, pretty = true) }.toString("UTF-8")

    fun fromJson(text: String): List<Note> {
        val arr = JSONObject(text).getJSONArray("notes")
        return (0 until arr.length().coerceAtMost(20_000)).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.optJSONArray("tags") ?: JSONArray()
            val att = o.optJSONArray("attachments") ?: JSONArray()
            Note(
                title = o.optString("title").take(500),
                body = o.optString("body").take(500_000),
                tags = (0 until t.length().coerceAtMost(10)).map { t.getString(it).let(TextTools::normalizeTag) }.filter { it.isNotEmpty() }.distinct(),
                pinned = o.optBoolean("pinned"),
                folder = o.optString("folder").trim().take(30),
                archived = o.optBoolean("archived"),
                created = o.optLong("created", System.currentTimeMillis()),
                updated = o.optLong("updated", System.currentTimeMillis()),
                deletedAt = o.optLong("deleted", 0).coerceAtLeast(0),
                remindAt = o.optLong("remindAt", 0).coerceAtLeast(0),
                remindRepeat = runCatching { Repeat.valueOf(o.optString("remindRepeat", "None")) }.getOrDefault(Repeat.None),
                remindAlarm = o.optBoolean("remindAlarm"), remindFired = o.optBoolean("remindFired"),
                attachments = (0 until att.length().coerceAtMost(20)).map { att.getString(it) }.filter { SAFE_ATTACHMENT.matches(it) }.distinct(),
            )
        }
    }
}
