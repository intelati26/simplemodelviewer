package io.github.intelati26.simplemodelview

import simplemodelview.GribStore
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The GRIB messages downloaded from one big file, kept together on disk as one file of concatenated messages (still a valid GRIB2 file) with a small map of where
 * each field's message sits. Messages are appended as they are first needed; the oldest files are removed when the folder passes [capBytes].
 */
class DiskGribStore(private val dir: File, private val capBytes: () -> Long) : GribStore {
    private val lock = Any()

    private fun name(source: String) = MessageDigest.getInstance("SHA-1").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun data(source: String) = File(dir, name(source) + ".grib2")
    private fun map(source: String) = File(dir, name(source) + ".map")

    /** field -> (offset, length) of its message in the data file */
    private fun read(source: String): Map<String, Pair<Long, Int>> {
        val f = map(source)
        if (!f.exists()) return emptyMap()
        val out = HashMap<String, Pair<Long, Int>>()
        for (line in f.readLines()) {
            val p = line.split('\t')
            if (p.size == 3) out[p[0]] = (p[1].toLongOrNull() ?: continue) to (p[2].toIntOrNull() ?: continue)
        }
        return out
    }

    override fun get(source: String, message: String): ByteArray? = synchronized(lock) {
        val (offset, length) = read(source)[message] ?: return null
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
            val offset = file.length()
            java.io.FileOutputStream(file, true).use { it.write(bytes) }
            map(source).appendText("$message\t$offset\t${bytes.size}\n")
            trim()
        } catch (e: java.io.IOException) { /* a full or unwritable cache only costs a download next time */ }
    }

    /** The data file of [source] (every message downloaded from it so far), or null: for sharing the subset. */
    fun file(source: String): File? = data(source).takeIf { it.exists() }

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
