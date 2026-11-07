package io.github.intelati26.simplemodelview

import simplemodelview.GRIB_KEEP_SECONDS
import simplemodelview.GribStore
import simplemodelview.gribSourceInitEpoch
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The GRIB messages downloaded from one big file, kept together on disk as one file of concatenated messages (still a valid GRIB2 file) with a small map of where
 * each field's message sits. They are kept 24 hours from the model run's initialization time (read from the file's url; else from the download) and dropped after
 * that, and the oldest files go sooner when the folder passes [capBytes].
 */
class DiskGribStore(private val dir: File, private val capBytes: () -> Long, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) : GribStore {
    private val lock = Any()

    init { synchronized(lock) { purgeExpired() } }

    private fun name(source: String) = MessageDigest.getInstance("SHA-1").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun data(source: String) = File(dir, name(source) + ".grib2")
    private fun map(source: String) = File(dir, name(source) + ".map")

    /** The map file: a first line "created<TAB>epoch" (the download time), then "field<TAB>offset<TAB>length" for each message. */
    private fun readMap(source: String): Pair<Long, Map<String, Pair<Long, Int>>> {
        val f = map(source)
        if (!f.exists()) return 0L to emptyMap()
        var created = 0L
        val out = HashMap<String, Pair<Long, Int>>()
        for (line in f.readLines()) {
            val p = line.split('\t')
            if (p.size == 2 && p[0] == "created") created = p[1].toLongOrNull() ?: 0L
            else if (p.size == 3) out[p[0]] = (p[1].toLongOrNull() ?: continue) to (p[2].toIntOrNull() ?: continue)
        }
        return created to out
    }

    private fun expired(source: String, created: Long): Boolean = clock() > (gribSourceInitEpoch(source) ?: created) + GRIB_KEEP_SECONDS

    override fun get(source: String, message: String): ByteArray? = synchronized(lock) {
        val (created, map) = readMap(source)
        if (map.isEmpty()) return null
        if (created > 0 && expired(source, created)) { remove(source); return null }
        val (offset, length) = map[message] ?: return null
        val file = data(source)
        if (!file.exists() || file.length() < offset + length) return null
        try {
            val bytes = RandomAccessFile(file, "r").use { it.seek(offset); ByteArray(length).also { b -> it.readFully(b) } }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (e: java.io.IOException) { null }
    }

    override fun put(source: String, message: String, bytes: ByteArray): Unit = synchronized(lock) {
        try {
            dir.mkdirs()
            val file = data(source)
            val mapFile = map(source)
            if (!mapFile.exists()) mapFile.writeText("created\t${clock()}\n")
            val offset = file.length()
            java.io.FileOutputStream(file, true).use { it.write(bytes) }
            mapFile.appendText("$message\t$offset\t${bytes.size}\n")
            purgeExpired()
            trim()
        } catch (e: java.io.IOException) { /* a full or unwritable cache only costs a download next time */ }
    }

    /** The data file of [source] (every message downloaded from it so far), or null: for sharing the subset. */
    fun file(source: String): File? = data(source).takeIf { it.exists() }

    private fun remove(source: String) { data(source).delete(); map(source).delete() }

    /** Files whose model run is more than 24 hours old (or, without a run in the name, whose download is). */
    private fun purgeExpired() {
        val maps = dir.listFiles { f -> f.name.endsWith(".map") } ?: return
        for (m in maps) {
            val lines = try { m.readLines() } catch (e: java.io.IOException) { continue }
            val created = lines.firstOrNull()?.split('\t')?.takeIf { it.size == 2 && it[0] == "created" }?.get(1)?.toLongOrNull() ?: 0L
            // the url is not in the map: the run time is not known here, so an old download is what counts (a run's data is never downloaded long after the run)
            if (created > 0 && clock() > created + GRIB_KEEP_SECONDS + 6 * 3600) {
                m.delete()
                File(dir, m.name.removeSuffix(".map") + ".grib2").delete()
            }
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".grib2") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= capBytes()) break
            total -= f.length()
            f.delete()
            File(dir, f.name.removeSuffix(".grib2") + ".map").delete()
        }
    }
}
