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
import com.catatanku.app.security.DeviceVaultKey
import com.catatanku.app.security.VaultCrypto
import java.util.Arrays

/**
 * Penyimpanan lokal SQLite terenkripsi (AES-256-GCM dengan kunci pembungkus Android Keystore).
 * Isi catatan, tag, folder, riwayat versi, dan vektor semantik dienkripsi di penyimpanan internal.
 */
class NoteDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "catatan.db", null, 6) {
    private val appCtx = context.applicationContext

    companion object {
        const val KEEP_VERSIONS = 20
        @Volatile private var instance: NoteDb? = null

        /** Satu koneksi untuk seluruh aplikasi: layar dan penerima alarm memakai basis data yang sama. */
        fun get(context: Context): NoteDb = instance ?: synchronized(this) { instance ?: NoteDb(context).also { instance = it } }
        internal fun resetForTest() { instance?.close(); instance = null }

        private const val COLS =
            "id,title,body,tags,pinned,created,updated,folder,archived,deleted,remind_at,remind_repeat,remind_alarm,remind_fired,attachments,vault"
        private const val VERSIONS_SQL =
            "CREATE TABLE IF NOT EXISTS versions(id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER NOT NULL, " +
                "title TEXT NOT NULL, body TEXT NOT NULL, saved INTEGER NOT NULL)"
        private const val EMBEDDINGS_SQL =
            "CREATE TABLE IF NOT EXISTS embeddings(note_id INTEGER PRIMARY KEY, content_hash TEXT NOT NULL, " +
                "model_id TEXT NOT NULL, vec BLOB NOT NULL, updated INTEGER NOT NULL)"

        private const val AAD_NOTE_TITLE = "notes:title"
        private const val AAD_NOTE_BODY = "notes:body"
        private const val AAD_NOTE_TAGS = "notes:tags"
        private const val AAD_NOTE_FOLDER = "notes:folder"
        private const val AAD_VER_TITLE = "versions:title"
        private const val AAD_VER_BODY = "versions:body"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            migratePlaintextToEncrypted(db)
        }
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
                "attachments TEXT NOT NULL DEFAULT '', vault INTEGER NOT NULL DEFAULT 0)"
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
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE notes ADD COLUMN vault INTEGER NOT NULL DEFAULT 0")
        }
    }

    private inline fun <T> withKey(block: (ByteArray) -> T): T {
        val key = DeviceVaultKey.getDataKey(appCtx)
        return try {
            block(key)
        } finally {
            Arrays.fill(key, 0.toByte())
        }
    }

    private fun migratePlaintextToEncrypted(db: SQLiteDatabase) {
        val needsNote = db.rawQuery(
            "SELECT 1 FROM notes WHERE title NOT LIKE 'CTKV1:%' OR body NOT LIKE 'CTKV1:%' OR tags NOT LIKE 'CTKV1:%' OR folder NOT LIKE 'CTKV1:%' LIMIT 1",
            null,
        ).use { it.moveToFirst() }
        val needsVer = db.rawQuery("SELECT 1 FROM versions WHERE title NOT LIKE 'CTKV1:%' OR body NOT LIKE 'CTKV1:%' LIMIT 1", null)
            .use { it.moveToFirst() }
        val needsEmb = db.rawQuery("SELECT 1 FROM embeddings WHERE content_hash NOT LIKE 'CTKV1:%' LIMIT 1", null)
            .use { it.moveToFirst() }
        if (!needsNote && !needsVer && !needsEmb) return

        withKey { key ->
            db.beginTransaction()
            try {
                if (needsNote) {
                    db.rawQuery("SELECT id,title,body,tags,folder FROM notes", null).use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val rTitle = c.getString(1)
                            val rBody = c.getString(2)
                            val rTags = c.getString(3)
                            val rFolder = c.getString(4)
                            if (!VaultCrypto.isEncryptedString(rTitle) ||
                                !VaultCrypto.isEncryptedString(rBody) ||
                                !VaultCrypto.isEncryptedString(rTags) ||
                                !VaultCrypto.isEncryptedString(rFolder)
                            ) {
                                val cv = ContentValues().apply {
                                    put("title", if (VaultCrypto.isEncryptedString(rTitle)) rTitle else VaultCrypto.encryptString(key, AAD_NOTE_TITLE, rTitle))
                                    put("body", if (VaultCrypto.isEncryptedString(rBody)) rBody else VaultCrypto.encryptString(key, AAD_NOTE_BODY, rBody))
                                    put("tags", if (VaultCrypto.isEncryptedString(rTags)) rTags else VaultCrypto.encryptString(key, AAD_NOTE_TAGS, rTags))
                                    put("folder", if (VaultCrypto.isEncryptedString(rFolder)) rFolder else VaultCrypto.encryptString(key, AAD_NOTE_FOLDER, rFolder))
                                }
                                db.update("notes", cv, "id=?", arrayOf(id.toString()))
                            }
                        }
                    }
                }
                if (needsVer) {
                    db.rawQuery("SELECT id,title,body FROM versions", null).use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val rTitle = c.getString(1)
                            val rBody = c.getString(2)
                            if (!VaultCrypto.isEncryptedString(rTitle) || !VaultCrypto.isEncryptedString(rBody)) {
                                val cv = ContentValues().apply {
                                    put("title", if (VaultCrypto.isEncryptedString(rTitle)) rTitle else VaultCrypto.encryptString(key, AAD_VER_TITLE, rTitle))
                                    put("body", if (VaultCrypto.isEncryptedString(rBody)) rBody else VaultCrypto.encryptString(key, AAD_VER_BODY, rBody))
                                }
                                db.update("versions", cv, "id=?", arrayOf(id.toString()))
                            }
                        }
                    }
                }
                if (needsEmb) {
                    db.rawQuery("SELECT note_id,content_hash,model_id,vec FROM embeddings", null).use { c ->
                        while (c.moveToNext()) {
                            val noteId = c.getLong(0)
                            val rHash = c.getString(1)
                            val modelId = c.getString(2)
                            val rVec = c.getBlob(3)
                            if (!VaultCrypto.isEncryptedString(rHash) || !VaultCrypto.isEncryptedBlob(rVec)) {
                                val cv = ContentValues().apply {
                                    put("content_hash", if (VaultCrypto.isEncryptedString(rHash)) rHash else VaultCrypto.encryptString(key, "embeddings:hash:$modelId", rHash))
                                    put("vec", if (VaultCrypto.isEncryptedBlob(rVec)) rVec else VaultCrypto.encryptBlob(key, "embeddings:vec:$modelId", rVec))
                                }
                                db.update("embeddings", cv, "note_id=?", arrayOf(noteId.toString()))
                            }
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun Cursor.note(key: ByteArray): Note {
        val decTitle = VaultCrypto.decryptStringIfNeeded(key, AAD_NOTE_TITLE, getString(1))
        val decBody = VaultCrypto.decryptStringIfNeeded(key, AAD_NOTE_BODY, getString(2))
        val decTags = VaultCrypto.decryptStringIfNeeded(key, AAD_NOTE_TAGS, getString(3))
        val decFolder = VaultCrypto.decryptStringIfNeeded(key, AAD_NOTE_FOLDER, getString(7))
        return Note(
            id = getLong(0),
            title = decTitle,
            body = decBody,
            tags = decTags.split('\u001f').filter { it.isNotBlank() },
            pinned = getInt(4) != 0,
            created = getLong(5),
            updated = getLong(6),
            folder = decFolder,
            archived = getInt(8) != 0,
            deletedAt = getLong(9),
            remindAt = getLong(10),
            remindRepeat = Repeat.of(getInt(11)),
            remindAlarm = getInt(12) != 0,
            remindFired = getInt(13) != 0,
            attachments = getString(14).split('\u001f').filter { it.isNotBlank() },
            vault = getInt(15) != 0,
        )
    }

    private fun query(where: String?, vararg args: String): List<Note> = withKey { key ->
        readableDatabase
            .rawQuery("SELECT $COLS FROM notes" + (where?.let { " WHERE $it" } ?: ""), args)
            .use { c -> buildList { while (c.moveToNext()) add(c.note(key)) } }
    }

    fun all(includeVault: Boolean = true): List<Note> =
        if (includeVault) query(null) else query("vault=0")

    fun nonVault(): List<Note> = query("vault=0")
    fun vaultOnly(): List<Note> = query("vault=1")
    fun vaultCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE vault=1", null)
        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun get(id: Long): Note? = query("id=?", id.toString()).firstOrNull()
    fun withReminder(): List<Note> = query("remind_at>0 AND deleted=0")

    private fun contentValues(n: Note, key: ByteArray): ContentValues = ContentValues().apply {
        put("title", VaultCrypto.encryptString(key, AAD_NOTE_TITLE, n.title))
        put("body", VaultCrypto.encryptString(key, AAD_NOTE_BODY, n.body))
        put("tags", VaultCrypto.encryptString(key, AAD_NOTE_TAGS, n.tags.joinToString("\u001f")))
        put("pinned", if (n.pinned) 1 else 0)
        put("created", n.created)
        put("updated", n.updated)
        put("folder", VaultCrypto.encryptString(key, AAD_NOTE_FOLDER, n.folder))
        put("archived", if (n.archived) 1 else 0)
        put("deleted", n.deletedAt)
        put("remind_at", n.remindAt)
        put("remind_repeat", n.remindRepeat.ordinal)
        put("remind_alarm", if (n.remindAlarm) 1 else 0)
        put("remind_fired", if (n.remindFired) 1 else 0)
        put("attachments", n.attachments.joinToString("\u001f"))
        put("vault", if (n.vault) 1 else 0)
    }

    fun upsert(n: Note): Long = withKey { key ->
        val v = contentValues(n, key)
        if (n.id == 0L) writableDatabase.insertOrThrow("notes", null, v)
        else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
    }

    /**
     * Mengimpor daftar catatan beserta riwayat versinya dalam satu transaksi SQLite agar kegagalan
     * di tengah pemulihan tidak pernah merusak data yang sudah ada.
     */
    fun importInTransaction(records: List<BackupNoteRecord>): List<Note> = withKey { key ->
        val db = writableDatabase
        val inserted = ArrayList<Note>(records.size)
        db.beginTransaction()
        try {
            for (rec in records) {
                val id = db.insertOrThrow("notes", null, contentValues(rec.note.copy(id = 0L), key))
                for (ver in rec.versions.take(KEEP_VERSIONS)) {
                    val vv = ContentValues().apply {
                        put("note_id", id)
                        put("title", VaultCrypto.encryptString(key, AAD_VER_TITLE, ver.title))
                        put("body", VaultCrypto.encryptString(key, AAD_VER_BODY, ver.body))
                        put("saved", ver.saved)
                    }
                    db.insertOrThrow("versions", null, vv)
                }
                inserted += rec.note.copy(id = id)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        inserted
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

    fun emptyTrash(vaultOnly: Boolean = false) {
        writableDatabase.delete("notes", if (vaultOnly) "deleted>0 AND vault=1" else "deleted>0 AND vault=0", null)
        dropOrphanChildren()
    }

    fun purgeOldTrash(): Boolean {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val purged = writableDatabase.delete("notes", "deleted>0 AND deleted<?", arrayOf(cutoff.toString())) > 0
        if (purged) dropOrphanChildren()
        return purged
    }

    fun moveFolder(from: String, to: String) = withKey { key ->
        val db = writableDatabase
        db.beginTransaction()
        try {
            val encTo = VaultCrypto.encryptString(key, AAD_NOTE_FOLDER, to)
            db.rawQuery("SELECT id,folder FROM notes WHERE deleted=0 AND vault=0", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val cur = VaultCrypto.decryptStringIfNeeded(key, AAD_NOTE_FOLDER, c.getString(1))
                    if (cur == from) {
                        val v = ContentValues().apply { put("folder", encTo) }
                        db.update("notes", v, "id=?", arrayOf(id.toString()))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Riwayat versi ----------

    fun versions(noteId: Long): List<Version> = withKey { key ->
        readableDatabase
            .rawQuery("SELECT id,note_id,title,body,saved FROM versions WHERE note_id=? ORDER BY saved DESC, id DESC", arrayOf(noteId.toString()))
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Version(
                                id = c.getLong(0),
                                noteId = c.getLong(1),
                                title = VaultCrypto.decryptStringIfNeeded(key, AAD_VER_TITLE, c.getString(2)),
                                body = VaultCrypto.decryptStringIfNeeded(key, AAD_VER_BODY, c.getString(3)),
                                saved = c.getLong(4),
                            )
                        )
                    }
                }
            }
    }

    fun allVersionsByNoteId(): Map<Long, List<Version>> = withKey { key ->
        readableDatabase
            .rawQuery("SELECT id,note_id,title,body,saved FROM versions ORDER BY saved DESC, id DESC", emptyArray())
            .use { c ->
                val map = HashMap<Long, MutableList<Version>>()
                while (c.moveToNext()) {
                    val v = Version(
                        id = c.getLong(0),
                        noteId = c.getLong(1),
                        title = VaultCrypto.decryptStringIfNeeded(key, AAD_VER_TITLE, c.getString(2)),
                        body = VaultCrypto.decryptStringIfNeeded(key, AAD_VER_BODY, c.getString(3)),
                        saved = c.getLong(4),
                    )
                    val list = map.getOrPut(v.noteId) { ArrayList() }
                    if (list.size < KEEP_VERSIONS) list += v
                }
                map
            }
    }

    fun addVersion(noteId: Long, title: String, body: String, saved: Long) = withKey { key ->
        val v = ContentValues().apply {
            put("note_id", noteId)
            put("title", VaultCrypto.encryptString(key, AAD_VER_TITLE, title))
            put("body", VaultCrypto.encryptString(key, AAD_VER_BODY, body))
            put("saved", saved)
        }
        writableDatabase.insertOrThrow("versions", null, v)
        writableDatabase.execSQL(
            "DELETE FROM versions WHERE note_id=? AND id NOT IN (SELECT id FROM versions WHERE note_id=? ORDER BY saved DESC, id DESC LIMIT $KEEP_VERSIONS)",
            arrayOf<Any>(noteId, noteId),
        )
    }

    // ---------- Embedding semantik (v5+) ----------

    fun embeddings(modelId: String): Map<Long, StoredEmbedding> = withKey { key ->
        readableDatabase
            .rawQuery("SELECT note_id,content_hash,model_id,vec FROM embeddings WHERE model_id=?", arrayOf(modelId))
            .use { c ->
                val map = HashMap<Long, StoredEmbedding>()
                while (c.moveToNext()) {
                    val mId = c.getString(2)
                    val rawVec = VaultCrypto.decryptBlobIfNeeded(key, "embeddings:vec:$mId", c.getBlob(3))
                    val vec = EmbeddingCodec.decode(rawVec)
                    if (vec.isNotEmpty()) {
                        val noteId = c.getLong(0)
                        val hash = VaultCrypto.decryptStringIfNeeded(key, "embeddings:hash:$mId", c.getString(1))
                        map[noteId] = StoredEmbedding(noteId, hash, mId, vec)
                    }
                }
                map
            }
    }

    fun upsertEmbedding(entry: StoredEmbedding) = withKey { key ->
        val v = ContentValues().apply {
            put("note_id", entry.noteId)
            put("content_hash", VaultCrypto.encryptString(key, "embeddings:hash:${entry.modelId}", entry.contentHash))
            put("model_id", entry.modelId)
            put("vec", VaultCrypto.encryptBlob(key, "embeddings:vec:${entry.modelId}", EmbeddingCodec.encode(entry.vector)))
            put("updated", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("embeddings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteEmbeddingsExcept(liveNoteIds: Set<Long>) {
        if (liveNoteIds.isEmpty()) {
            writableDatabase.delete("embeddings", null, null)
            return
        }
        writableDatabase.execSQL("DELETE FROM embeddings WHERE note_id NOT IN (SELECT id FROM notes WHERE deleted=0 AND vault=0)")
    }
}
