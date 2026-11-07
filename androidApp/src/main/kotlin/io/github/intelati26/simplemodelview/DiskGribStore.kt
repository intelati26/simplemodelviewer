package io.github.intelati26.simplemodelview

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import simplemodelview.GRIB_KEEP_SECONDS
import simplemodelview.GribStore
import simplemodelview.gribSourceInitEpoch
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The GRIB messages downloaded from one big file, kept together on disk as one file of concatenated messages (still a valid GRIB2 file), and a small SQLite
 * database of what is in them: for each message its source file, field, place in the file, the model run's time, when it was downloaded and when it was last used.
 * Messages are kept 24 hours from the model run's initialization (read from the file's url; else from the download); the oldest files go sooner when the
 * folder passes [capBytes]. The table also gives the figures of Settings and clears by model.
 */
class DiskGribStore(context: Context, private val dir: File, private val capBytes: () -> Long, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) : GribStore {
    private val lock = Any()

    private class Helper(context: Context, dir: File) : SQLiteOpenHelper(context, File(dir.apply { mkdirs() }, "cache.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE entries (source TEXT NOT NULL, message TEXT NOT NULL, file TEXT NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL, " +
                "init_epoch INTEGER, downloaded INTEGER NOT NULL, last_used INTEGER NOT NULL, PRIMARY KEY (source, message))")
            db.execSQL("CREATE INDEX entries_file ON entries (file)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val db: SQLiteDatabase by lazy { Helper(context, dir).writableDatabase }

    init { synchronized(lock) { try { purgeExpired(); removeOrphans() } catch (e: Exception) { /* a damaged cache is only a cache */ } } }

    private fun fileName(source: String) = MessageDigest.getInstance("SHA-1").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }.take(24) + ".grib2"

    private fun kindOf(source: String) = when { "noaa-gfs" in source -> "GFS"; "noaa-nbm" in source -> "NBM"; else -> "Other" }

    override fun get(source: String, message: String): ByteArray? = synchronized(lock) {
        try {
            val row = db.rawQuery("SELECT file, offset, length, init_epoch, downloaded FROM entries WHERE source = ? AND message = ?", arrayOf(source, message)).use { c ->
                if (!c.moveToFirst()) return null
                listOf(c.getString(0), c.getLong(1), c.getInt(2).toLong(), if (c.isNull(3)) 0L else c.getLong(3), c.getLong(4))
            }
            val init = row[3] as Long
            val expiry = (if (init > 0) init else row[4] as Long) + GRIB_KEEP_SECONDS
            if (clock() > expiry) { removeSource(source); return null }
            val file = File(dir, row[0] as String)
            val offset = row[1] as Long
            val length = (row[2] as Long).toInt()
            if (!file.exists() || file.length() < offset + length) return null
            val bytes = RandomAccessFile(file, "r").use { it.seek(offset); ByteArray(length).also { b -> it.readFully(b) } }
            val values = ContentValues().apply { put("last_used", clock()) }
            db.update("entries", values, "source = ? AND message = ?", arrayOf(source, message))
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (e: Exception) { null }
    }

    override fun put(source: String, message: String, bytes: ByteArray): Unit = synchronized(lock) {
        try {
            dir.mkdirs()
            val name = fileName(source)
            val file = File(dir, name)
            val offset = file.length()
            java.io.FileOutputStream(file, true).use { it.write(bytes) }
            val values = ContentValues().apply {
                put("source", source); put("message", message); put("file", name); put("offset", offset); put("length", bytes.size)
                gribSourceInitEpoch(source)?.let { put("init_epoch", it) }
                put("downloaded", clock()); put("last_used", clock())
            }
            db.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            purgeExpired()
            trim()
        } catch (e: Exception) { /* a full or unwritable cache only costs a download next time */ }
    }

    /** The data file of [source] (every message downloaded from it so far), or null: for sharing the subset. */
    fun file(source: String): File? = File(dir, fileName(source)).takeIf { it.exists() }

    private fun removeSource(source: String) {
        db.delete("entries", "source = ?", arrayOf(source))
        File(dir, fileName(source)).delete()
    }

    /** Sources whose model run is more than 24 hours old (or, without a run in the url, whose download is). */
    private fun purgeExpired() {
        val now = clock()
        val expired = ArrayList<String>()
        db.rawQuery("SELECT source, MAX(init_epoch), MIN(downloaded) FROM entries GROUP BY source", null).use { c ->
            while (c.moveToNext()) {
                val init = if (c.isNull(1)) 0L else c.getLong(1)
                if (now > (if (init > 0) init else c.getLong(2)) + GRIB_KEEP_SECONDS) expired += c.getString(0)
            }
        }
        for (s in expired) removeSource(s)
    }

    /** Files that nothing in the table points to (an older build's, or a crash's) are removed. */
    private fun removeOrphans() {
        val known = HashSet<String>()
        db.rawQuery("SELECT DISTINCT file FROM entries", null).use { c -> while (c.moveToNext()) known += c.getString(0) }
        dir.listFiles { f -> f.name.endsWith(".grib2") || f.name.endsWith(".map") }?.forEach { if (it.name !in known) it.delete() }
    }

    /** The oldest-used files go first until the folder is within the size set in Settings. */
    private fun trim() {
        val files = ArrayList<Pair<String, Long>>()
        db.rawQuery("SELECT file, MAX(last_used) FROM entries GROUP BY file ORDER BY MAX(last_used) ASC", null).use { c -> while (c.moveToNext()) files += c.getString(0) to c.getLong(1) }
        var total = files.sumOf { File(dir, it.first).length() }
        for ((name, _) in files) {
            if (total <= capBytes()) break
            total -= File(dir, name).length()
            File(dir, name).delete()
            db.delete("entries", "file = ?", arrayOf(name))
        }
    }

    /** What is kept: bytes and files per model ("GFS", "NBM"), for Settings. */
    class Stats(val bytes: Long, val files: Int, val byModel: Map<String, Long>)

    fun stats(): Stats = synchronized(lock) {
        try {
            val byModel = HashMap<String, Long>()
            val seen = HashSet<String>()
            db.rawQuery("SELECT source, file, SUM(length) FROM entries GROUP BY source, file", null).use { c ->
                while (c.moveToNext()) { byModel.merge(kindOf(c.getString(0)), c.getLong(2), Long::plus); seen += c.getString(1) }
            }
            Stats(byModel.values.sum(), seen.size, byModel)
        } catch (e: Exception) { Stats(0L, 0, emptyMap()) }
    }

    /** Removes what is kept for a model ("GFS", "NBM"), or everything when [model] is null. */
    fun clear(model: String? = null): Unit = synchronized(lock) {
        try {
            val sources = ArrayList<String>()
            db.rawQuery("SELECT DISTINCT source FROM entries", null).use { c -> while (c.moveToNext()) if (model == null || kindOf(c.getString(0)) == model) sources += c.getString(0) }
            for (s in sources) removeSource(s)
        } catch (e: Exception) { /* nothing to clear */ }
    }
}
