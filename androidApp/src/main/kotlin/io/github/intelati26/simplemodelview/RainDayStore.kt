package io.github.intelati26.simplemodelview

import simplemodelview.Packer
import simplemodelview.RainDay
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Deflate for the day cache's strips. */
object DeflatePacker : Packer {
    override fun pack(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(raw); d.finish()
        val out = java.io.ByteArrayOutputStream(raw.size / 4 + 64)
        val buf = ByteArray(16384)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    override fun unpack(packed: ByteArray, rawSize: Int): ByteArray {
        val i = Inflater()
        i.setInput(packed)
        val out = ByteArray(rawSize)
        var n = 0
        while (n < rawSize && !i.finished()) { val k = i.inflate(out, n, rawSize - n); if (k == 0 && (i.needsInput() || i.needsDictionary())) break; n += k }
        i.end()
        require(n == rawSize) { "damaged day cache entry" }
        return out
    }
}

/**
 * The finished days of rainfall (see [RainDay]), one small file per UTC day, apart from the HTTP cache: a day is final, so it is kept until the folder passes
 * [capBytes] (then the days not used for longest go). The system may empty it like any cache folder; a missing day is simply downloaded again.
 */
class RainDayStore(private val dir: File, private val keepDays: Int = 90, private val capBytes: Long = 400L * 1024 * 1024, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private fun fileOf(dayEnd: Long) = File(dir, "day-$dayEnd.rd")

    fun read(dayEnd: Long): RainDay? {
        val f = fileOf(dayEnd)
        if (!f.isFile) return null
        return try { RainDay.fromBytes(f.readBytes()).also { f.setLastModified(System.currentTimeMillis()) } } catch (_: Exception) { f.delete(); null }
    }

    fun has(dayEnd: Long): Boolean = fileOf(dayEnd).isFile

    /** Every kept day as its end time and file bytes, oldest first (for the rain history export). */
    fun rawDays(): List<Pair<Long, ByteArray>> =
        dir.listFiles { f -> f.name.endsWith(".rd") }.orEmpty()
            .mapNotNull { f -> f.name.removePrefix("day-").removeSuffix(".rd").toLongOrNull()?.let { it to f } }
            .sortedBy { it.first }
            .mapNotNull { (end, f) -> try { end to f.readBytes() } catch (_: Exception) { null } }

    fun write(dayEnd: Long, day: RainDay) {
        dir.mkdirs()
        val tmp = File(dir, "day-$dayEnd.tmp")
        tmp.writeBytes(day.toBytes())
        if (!tmp.renameTo(fileOf(dayEnd))) tmp.delete()
        expire()
        trim()
    }

    /** The bytes the cache holds, and how many days. */
    fun size(): Pair<Long, Int> = dir.listFiles { f -> f.name.endsWith(".rd") }.orEmpty().let { fs -> fs.sumOf { it.length() } to fs.size }

    /** When the oldest kept day ends (epoch seconds), or null with none. */
    fun oldestEnd(): Long? = dir.listFiles { f -> f.name.endsWith(".rd") }.orEmpty().mapNotNull { it.name.removePrefix("day-").removeSuffix(".rd").toLongOrNull() }.minOrNull()

    /** Deletes the days older than [keepDays] (a day's file is named by the time it ends). */
    fun expire() {
        val oldest = clock() - keepDays * 86400L
        dir.listFiles { f -> f.name.endsWith(".rd") }.orEmpty().forEach { f ->
            val end = f.name.removePrefix("day-").removeSuffix(".rd").toLongOrNull()
            if (end != null && end < oldest) f.delete()
        }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".rd") }.orEmpty().sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (total > capBytes && files.size > 1) { val f = files.removeAt(0); total -= f.length(); f.delete() }
    }
}
