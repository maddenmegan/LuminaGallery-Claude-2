package com.lumina.gallery.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer

class IndexRow(val id: Long, val colors: String, val rgb: Int, val labels: String, val text: String)

class PersonRow(val pid: Long, val name: String, val hidden: Boolean, val thumb: ByteArray?, val mids: List<Long>)

class IndexDb(ctx: Context) : SQLiteOpenHelper(ctx, "lumina_index.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE idx(id INTEGER PRIMARY KEY, colors TEXT, rgb INTEGER, labels TEXT, txt TEXT)")
        db.execSQL("CREATE TABLE people(pid INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, hidden INTEGER DEFAULT 0, thumb BLOB)")
        db.execSQL("CREATE TABLE faces(fid INTEGER PRIMARY KEY AUTOINCREMENT, mid INTEGER, pid INTEGER, emb BLOB)")
        db.execSQL("CREATE INDEX faces_pid ON faces(pid)")
        db.execSQL("CREATE TABLE scanned(mid INTEGER PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- search index ----------
    fun loadIdx(): List<IndexRow> {
        val out = ArrayList<IndexRow>()
        readableDatabase.rawQuery("SELECT id, colors, rgb, labels, txt FROM idx", null).use { c ->
            while (c.moveToNext()) {
                out.add(IndexRow(c.getLong(0), c.getString(1) ?: "", c.getInt(2), c.getString(3) ?: "", c.getString(4) ?: ""))
            }
        }
        return out
    }

    fun upsertIdx(r: IndexRow) {
        val cv = ContentValues().apply {
            put("id", r.id); put("colors", r.colors); put("rgb", r.rgb); put("labels", r.labels); put("txt", r.text)
        }
        writableDatabase.insertWithOnConflict("idx", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteIdx(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("idx", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- faces ----------
    fun scannedIds(): MutableSet<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT mid FROM scanned", null).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun markScanned(mid: Long) {
        val cv = ContentValues().apply { put("mid", mid) }
        writableDatabase.insertWithOnConflict("scanned", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun allEmbeddings(): List<Pair<Long, FloatArray>> {
        val out = ArrayList<Pair<Long, FloatArray>>()
        readableDatabase.rawQuery("SELECT pid, emb FROM faces", null).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to toFloats(c.getBlob(1)))
        }
        return out
    }

    fun newPerson(thumb: ByteArray?): Long {
        val cv = ContentValues().apply { put("name", ""); put("hidden", 0); put("thumb", thumb) }
        return writableDatabase.insert("people", null, cv)
    }

    fun addFace(mid: Long, pid: Long, emb: FloatArray) {
        val cv = ContentValues().apply { put("mid", mid); put("pid", pid); put("emb", toBytes(emb)) }
        writableDatabase.insert("faces", null, cv)
    }

    fun loadPeople(existing: Set<Long>): List<PersonRow> {
        val db = readableDatabase
        val mids = HashMap<Long, MutableSet<Long>>()
        db.rawQuery("SELECT pid, mid FROM faces", null).use { c ->
            while (c.moveToNext()) {
                val mid = c.getLong(1)
                if (mid in existing) mids.getOrPut(c.getLong(0)) { HashSet() }.add(mid)
            }
        }
        val out = ArrayList<PersonRow>()
        db.rawQuery("SELECT pid, name, hidden, thumb FROM people", null).use { c ->
            while (c.moveToNext()) {
                val pid = c.getLong(0)
                val list = mids[pid]?.toList() ?: continue
                out.add(PersonRow(pid, c.getString(1) ?: "", c.getInt(2) == 1, c.getBlob(3), list))
            }
        }
        return out
    }

    fun rename(pid: Long, name: String) {
        writableDatabase.execSQL("UPDATE people SET name=? WHERE pid=?", arrayOf<Any>(name, pid))
    }

    fun setHidden(pids: Collection<Long>, hidden: Boolean) {
        val db = writableDatabase
        pids.forEach { db.execSQL("UPDATE people SET hidden=? WHERE pid=?", arrayOf<Any>(if (hidden) 1 else 0, it)) }
    }

    fun mergePeople(target: Long, sources: Collection<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            sources.filter { it != target }.forEach {
                db.execSQL("UPDATE faces SET pid=? WHERE pid=?", arrayOf<Any>(target, it))
                db.execSQL("DELETE FROM people WHERE pid=?", arrayOf<Any>(it))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        fun toBytes(f: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(f.size * 4)
            f.forEach { bb.putFloat(it) }
            return bb.array()
        }

        fun toFloats(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b)
            return FloatArray(b.size / 4) { bb.getFloat() }
        }
    }
}
