package io.github.intelati26.simplemodelview

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import simplemodelview.RainDay

/**
 * The finished rainfall days a phone has downloaded (see [RainDayStore]) as one zip file, to take along with the settings export: a phone that reads it back
 * has those days at hand and skips the download. The days are already compressed, so the zip only stores them.
 */
internal object RainHistory {
    /** Days and bytes kept, or null with none. */
    fun summary(store: RainDayStore = AppContainer.rainDays): Pair<Int, Long>? = store.size().let { (bytes, days) -> if (days == 0) null else days to bytes }

    /** Writes every kept day to [out]; how many. */
    fun write(store: RainDayStore, out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { zip ->
            for ((end, bytes) in store.rawDays()) {
                zip.putNextEntry(ZipEntry("day-$end.rd"))
                zip.write(bytes)
                zip.closeEntry()
                n += 1
            }
        }
        return n
    }

    /** What reading an archive did: days added, days that were already there (or too old to keep), entries that were not days. */
    data class Result(val added: Int, val had: Int, val bad: Int)

    private val entryName = Regex("""day-(\d{9,12})\.rd""")
    private const val MAX_ENTRY = 64L * 1024 * 1024

    /** Reads an archive from [input] into [store]: only well-formed days, never one already kept, never one older than the store keeps. */
    fun read(store: RainDayStore, input: InputStream, now: Long = System.currentTimeMillis() / 1000, keepDays: Int = 90): Result {
        var added = 0; var had = 0; var bad = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val end = entryName.matchEntire(e.name.substringAfterLast('/'))?.groupValues?.get(1)?.toLongOrNull()
                val bytes = readCapped(zip)
                if (end == null || bytes == null) { bad += 1; continue }
                if (end < now - keepDays * 86400L) { had += 1; continue }
                if (store.has(end)) { had += 1; continue }
                val day = try { RainDay.fromBytes(bytes) } catch (_: Exception) { null }
                if (day == null) { bad += 1; continue }
                store.write(end, day)
                added += 1
            }
        }
        return Result(added, had, bad)
    }

    private fun readCapped(zip: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val k = zip.read(buf)
            if (k < 0) break
            total += k
            if (total > MAX_ENTRY) return null
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    }

    /** A zip starts with "PK\u0003\u0004". */
    private fun isZip(resolver: ContentResolver, uri: Uri): Boolean =
        try { resolver.openInputStream(uri)?.use { s -> val h = ByteArray(4); s.read(h) == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2] == 3.toByte() && h[3] == 4.toByte() } == true } catch (_: Exception) { false }

    /** When [uri] is a rain history archive: reads it and says what it did. Null: it is not one (a settings file, probably). */
    suspend fun importIfArchive(resolver: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) {
        if (!isZip(resolver, uri)) return@withContext null
        try {
            val r = resolver.openInputStream(uri)?.use { read(AppContainer.rainDays, it) } ?: return@withContext "Could not read that file."
            when {
                r.added == 0 && r.bad > 0 && r.had == 0 -> "That file holds no rainfall days."
                else -> "Rain history: ${r.added} days added" + (if (r.had > 0) ", ${r.had} already kept or too old" else "") + (if (r.bad > 0) ", ${r.bad} skipped" else "") + "."
            }
        } catch (e: Exception) { "Could not read the rain history file." }
    }
}
