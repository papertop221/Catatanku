package com.catatanku.app.data.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.catatanku.app.domain.model.BackupNoteRecord
import com.catatanku.app.domain.model.Note
import com.catatanku.app.domain.model.Repeat
import com.catatanku.app.domain.model.Version
import com.catatanku.app.domain.usecase.EmbeddingCodec
import com.catatanku.app.domain.usecase.StoredEmbedding

/** Penyimpanan lokal SQLite. Semua data ada di penyimpanan internal aplikasi. */
class NoteDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "catatan.db", null, 5) {
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
        private const val EMBEDDINGS_SQL =
            "CREATE TABLE IF NOT EXISTS embeddings(note_id INTEGER PRIMARY KEY, content_hash TEXT NOT NULL, " +
                "model_id TEXT NOT NULL, vec BLOB NOT NULL, updated INTEGER NOT NULL)"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

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
        db.execSQL(EMBEDDINGS_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_repeat INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_alarm INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN remind_fired INTEGER NOT NULL DEFAULT 0")
            db.execSQL(VERSIONS_SQL)
            db.execSQL("CREATE INDEX IF NOT EXISTS versions_note ON versions(note_id)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE notes ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) {
            db.execSQL(EMBEDDINGS_SQL)
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
    fun withReminder(): List<Note> = query("remind_at>0 AND deleted=0")

    private fun contentValues(n: Note): ContentValues = ContentValues().apply {
        put("title", n.title); put("body", n.body); put("tags", n.tags.joinToString("\u001f"))
        put("pinned", if (n.pinned) 1 else 0); put("created", n.created); put("updated", n.updated)
        put("folder", n.folder); put("archived", if (n.archived) 1 else 0); put("deleted", n.deletedAt)
        put("remind_at", n.remindAt); put("remind_repeat", n.remindRepeat.ordinal)
        put("remind_alarm", if (n.remindAlarm) 1 else 0); put("remind_fired", if (n.remindFired) 1 else 0)
        put("attachments", n.attachments.joinToString("\u001f"))
    }

    fun upsert(n: Note): Long {
        val v = contentValues(n)
        return if (n.id == 0L) writableDatabase.insertOrThrow("notes", null, v)
        else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
    }

    /**
     * Mengimpor daftar catatan beserta riwayat versinya dalam satu transaksi SQLite agar kegagalan
     * di tengah pemulihan tidak pernah merusak data yang sudah ada.
     */
    fun importInTransaction(records: List<BackupNoteRecord>): List<Note> {
        val db = writableDatabase
        val inserted = ArrayList<Note>(records.size)
        db.beginTransaction()
        try {
            for (rec in records) {
                val id = db.insertOrThrow("notes", null, contentValues(rec.note.copy(id = 0L)))
                for (ver in rec.versions.take(KEEP_VERSIONS)) {
                    val vv = ContentValues().apply {
                        put("note_id", id); put("title", ver.title); put("body", ver.body); put("saved", ver.saved)
                    }
                    db.insertOrThrow("versions", null, vv)
                }
                inserted += rec.note.copy(id = id)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun setReminder(id: Long, at: Long, fired: Boolean) {
        val v = ContentValues().apply { put("remind_at", at); put("remind_fired", if (fired) 1 else 0) }
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("notes", "id=?", arrayOf(id.toString()))
            db.delete("versions", "note_id=?", arrayOf(id.toString()))
            db.delete("embeddings", "note_id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun dropOrphanChildren() {
        writableDatabase.execSQL("DELETE FROM versions WHERE note_id NOT IN (SELECT id FROM notes)")
        writableDatabase.execSQL("DELETE FROM embeddings WHERE note_id NOT IN (SELECT id FROM notes)")
    }

    fun emptyTrash() { writableDatabase.delete("notes", "deleted>0", null); dropOrphanChildren() }

    fun purgeOldTrash(): Boolean {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val purged = writableDatabase.delete("notes", "deleted>0 AND deleted<?", arrayOf(cutoff.toString())) > 0
        if (purged) dropOrphanChildren()
        return purged
    }

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

    fun allVersionsByNoteId(): Map<Long, List<Version>> = readableDatabase
        .rawQuery("SELECT id,note_id,title,body,saved FROM versions ORDER BY saved DESC, id DESC", emptyArray())
        .use { c ->
            val map = HashMap<Long, MutableList<Version>>()
            while (c.moveToNext()) {
                val v = Version(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))
                val list = map.getOrPut(v.noteId) { ArrayList() }
                if (list.size < KEEP_VERSIONS) list += v
            }
            map
        }

    fun addVersion(noteId: Long, title: String, body: String, saved: Long) {
        val v = ContentValues().apply { put("note_id", noteId); put("title", title); put("body", body); put("saved", saved) }
        writableDatabase.insertOrThrow("versions", null, v)
        writableDatabase.execSQL(
            "DELETE FROM versions WHERE note_id=? AND id NOT IN (SELECT id FROM versions WHERE note_id=? ORDER BY saved DESC, id DESC LIMIT $KEEP_VERSIONS)",
            arrayOf<Any>(noteId, noteId),
        )
    }

    // ---------- Embedding semantik (v5) ----------

    fun embeddings(modelId: String): Map<Long, StoredEmbedding> = readableDatabase
        .rawQuery("SELECT note_id,content_hash,model_id,vec FROM embeddings WHERE model_id=?", arrayOf(modelId))
        .use { c ->
            val map = HashMap<Long, StoredEmbedding>()
            while (c.moveToNext()) {
                val vec = EmbeddingCodec.decode(c.getBlob(3))
                if (vec.isNotEmpty()) {
                    val noteId = c.getLong(0)
                    map[noteId] = StoredEmbedding(noteId, c.getString(1), c.getString(2), vec)
                }
            }
            map
        }

    fun upsertEmbedding(entry: StoredEmbedding) {
        val v = ContentValues().apply {
            put("note_id", entry.noteId)
            put("content_hash", entry.contentHash)
            put("model_id", entry.modelId)
            put("vec", EmbeddingCodec.encode(entry.vector))
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("embeddings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteEmbeddingsExcept(liveNoteIds: Set<Long>) {
        if (liveNoteIds.isEmpty()) {
            writableDatabase.delete("embeddings", null, null)
            return
        }
        writableDatabase.execSQL("DELETE FROM embeddings WHERE note_id NOT IN (SELECT id FROM notes WHERE deleted=0)")
    }
}
