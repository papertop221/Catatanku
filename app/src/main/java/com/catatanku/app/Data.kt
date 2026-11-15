package com.catatanku.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

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
) {
    val isBlank get() = title.isBlank() && body.isBlank()
    val inTrash get() = deletedAt != 0L
}

/** Penyimpanan lokal SQLite. Semua data ada di penyimpanan internal aplikasi. */
class NoteDb(context: Context) : SQLiteOpenHelper(context, "catatan.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, " +
                "tags TEXT NOT NULL, pinned INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, " +
                "folder TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {   // v1.0 -> v1.1: catatan lama tetap utuh
            db.execSQL("ALTER TABLE notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun all(): List<Note> = readableDatabase
        .rawQuery("SELECT id,title,body,tags,pinned,created,updated,folder,archived,deleted FROM notes", null)
        .use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Note(
                        id = c.getLong(0), title = c.getString(1), body = c.getString(2),
                        tags = c.getString(3).split('\u001f').filter { it.isNotBlank() },
                        pinned = c.getInt(4) != 0, created = c.getLong(5), updated = c.getLong(6),
                        folder = c.getString(7), archived = c.getInt(8) != 0, deletedAt = c.getLong(9),
                    )
                )
            }
        }

    /** Menyimpan catatan; mengembalikan id (baru bila id == 0). */
    fun upsert(n: Note): Long {
        val v = ContentValues().apply {
            put("title", n.title); put("body", n.body); put("tags", n.tags.joinToString("\u001f"))
            put("pinned", if (n.pinned) 1 else 0); put("created", n.created); put("updated", n.updated)
            put("folder", n.folder); put("archived", if (n.archived) 1 else 0); put("deleted", n.deletedAt)
        }
        return if (n.id == 0L) writableDatabase.insertOrThrow("notes", null, v)
        else { writableDatabase.update("notes", v, "id=?", arrayOf(n.id.toString())); n.id }
    }

    fun delete(id: Long) { writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) }

    fun emptyTrash() { writableDatabase.delete("notes", "deleted>0", null) }

    /** Sampah dibersihkan otomatis setelah 30 hari. */
    fun purgeOldTrash() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        writableDatabase.delete("notes", "deleted>0 AND deleted<?", arrayOf(cutoff.toString()))
    }
}

object Backup {
    fun toJson(notes: List<Note>): String = JSONObject().apply {
        put("app", "Catatanku"); put("version", 2)
        put("notes", JSONArray().apply {
            notes.forEach { n ->
                put(JSONObject().apply {
                    put("title", n.title); put("body", n.body); put("tags", JSONArray(n.tags))
                    put("pinned", n.pinned); put("folder", n.folder); put("archived", n.archived); put("created", n.created); put("updated", n.updated)
                })
            }
        })
    }.toString(2)

    fun fromJson(text: String): List<Note> {
        val arr = JSONObject(text).getJSONArray("notes")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.optJSONArray("tags") ?: JSONArray()
            Note(
                title = o.optString("title"), body = o.optString("body"),
                tags = (0 until t.length()).map { t.getString(it) },
                pinned = o.optBoolean("pinned"), folder = o.optString("folder"), archived = o.optBoolean("archived"),
                created = o.optLong("created", System.currentTimeMillis()),
                updated = o.optLong("updated", System.currentTimeMillis()),
            )
        }
    }
}
