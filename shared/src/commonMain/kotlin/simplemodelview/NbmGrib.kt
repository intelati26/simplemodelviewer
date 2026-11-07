package simplemodelview

/**
 * NOAA's other copy of the National Blend of Models: the GRIB2 files of the bucket `noaa-nbm-grib2-pds`, one file per run and forecast hour
 * (`blend.<yyyyMMdd>/<HH>/core/blend.t<HH>z.core.f<FFF>.co.grib2`) with a text index (`.idx`) of the messages' byte ranges. It is the fallback for when
 * the rendered GeoTIFFs of [NbmBucket] fall behind (they stopped for days when version 5.1 started), so only the fields the GRIB files have as they are
 * (not the 6 and 12 h accumulations, daily extremes and probabilities, which the GeoTIFFs make from other messages) can be shown from it.
 */
object NbmGribBucket {
    const val URL = "https://noaa-nbm-grib2-pds.s3.amazonaws.com"

    /** The marker in [NbmCycle.version] of a run found in this bucket. */
    const val VERSION = "grib"

    /** A GRIB run is preferred over the GeoTIFFs when it is at least this many hours newer than the newest GeoTIFF run. */
    const val PREFER_AFTER_HOURS = 3
}

/** One field of the GRIB files: its message in the index (variable, level, extra text after the time) and how to turn its numbers into the GeoTIFF's units. */
class GribField(val variable: String, val level: String, val convert: (Float) -> Float = { it }, val extra: String = "")

object NbmGribFields {
    private fun kelvinToF(k: Float) = (k - 273.15f) * 1.8f + 32f
    private fun metersPerSecondToKnots(v: Float) = v * 1.9438445f
    private fun millimetersToInches(v: Float) = v / 25.4f
    private fun metersToMiles(v: Float) = v / 1609.344f
    private fun metersToHundredsOfFeet(v: Float) = v / 30.48f

    /** The products (by [NbmProduct.id]) that have a message in the GRIB files, with the units of the GeoTIFFs (see [NbmCatalog]). */
    val byId: Map<String, GribField> = mapOf(
        "temp" to GribField("TMP", "2 m above ground", ::kelvinToF),
        "appttemp" to GribField("APTMP", "2 m above ground", ::kelvinToF),
        "dewpoint" to GribField("DPT", "2 m above ground", ::kelvinToF),
        "windspd" to GribField("WIND", "10 m above ground", ::metersPerSecondToKnots),
        "windgust" to GribField("GUST", "10 m above ground", ::metersPerSecondToKnots),
        "sky" to GribField("TCDC", "surface"),
        "rh" to GribField("RH", "2 m above ground"),
        "pwat" to GribField("PWAT", "entire atmosphere (considered as a single layer)", ::millimetersToInches),
        "vis" to GribField("VIS", "surface", ::metersToMiles),
        "ceil" to GribField("CEIL", "cloud ceiling", ::metersToHundredsOfFeet),
        "sbcape" to GribField("CAPE", "surface"),
    )

    fun supports(id: String) = id in byId
}

/** One message of a `.idx` file: its first byte, and the variable, level, time text ("12 hour fcst") and anything after it ("10% level", "ens std dev"). */
class GribIndexEntry(val offset: Long, val variable: String, val level: String, val time: String, val extra: String)

/** The messages of a `.idx` text ("133:129432216:d=2026110423:TMP:2 m above ground:12 hour fcst:"), in file order; lines that do not look like one are skipped. */
fun parseGribIndex(text: String): List<GribIndexEntry> {
    val out = ArrayList<GribIndexEntry>()
    for (line in text.lineSequence()) {
        val parts = line.split(':')
        if (parts.size < 6) continue
        val offset = parts[1].toLongOrNull() ?: continue
        out += GribIndexEntry(offset, parts[3], parts[4], parts[5], parts.drop(6).joinToString(":"))
    }
    return out
}

/** The byte range of [field]'s message at [leadHours] after the run: first byte and last byte (null for the last message of the file), or null when it is not there. */
fun findGribMessage(index: List<GribIndexEntry>, field: GribField, leadHours: Int): Pair<Long, Long?>? {
    val at = index.indexOfFirst { it.variable == field.variable && it.level == field.level && it.time == "$leadHours hour fcst" && it.extra == field.extra }
    if (at < 0) return null
    val next = index.getOrNull(at + 1)?.offset
    return index[at].offset to next?.let { it - 1 }
}

/** The run folders ("00" to "23") of a day's listing of the GRIB bucket made with a `/` delimiter, as hours. */
fun parseGribRunHours(xml: String): List<Int> {
    val hours = ArrayList<Int>()
    var at = 0
    while (true) {
        val start = xml.indexOf("<Prefix>", at)
        if (start < 0) break
        val end = xml.indexOf("</Prefix>", start)
        if (end < 0) break
        at = end
        val folder = xml.substring(start + 8, end).trimEnd('/').substringAfterLast('/')
        if (folder.length == 2 && folder.all { it.isDigit() }) folder.toInt().takeIf { it in 0..23 }?.let { hours += it }
    }
    return hours.distinct().sorted()
}

/** The frames of one run's `core/` folder listing (the `.grib2` files, not their indexes), as [NbmFile]s of [fieldId], ordered by lead. */
fun parseGribListing(xml: String, cycle: NbmCycle, fieldId: String): List<NbmFile> {
    val files = ArrayList<NbmFile>()
    var at = 0
    while (true) {
        val start = xml.indexOf("<Contents>", at)
        if (start < 0) break
        val end = xml.indexOf("</Contents>", start)
        if (end < 0) break
        val block = xml.substring(start, end)
        at = end
        val key = block.substringAfter("<Key>", "").substringBefore("</Key>")
        if (!key.endsWith(".co.grib2")) continue
        val lead = key.substringAfterLast(".core.f", "").substringBefore('.').toIntOrNull() ?: continue
        val uploaded = isoToEpoch(block.substringAfter("<LastModified>", "").substringBefore("</LastModified>"))
        val size = block.substringAfter("<Size>", "").substringBefore("</Size>").toLongOrNull() ?: 0L
        files += NbmFile(key, cycle.epoch, cycle.epoch + lead * 3600L, uploaded, size, grib = true, field = fieldId)
    }
    return files.sortedBy { it.valid }
}

/**
 * Where downloaded GRIB messages are kept, per source file: the messages fetched from one big file go together (the app keeps them as one file of concatenated
 * messages, which is itself a valid GRIB2 file). [source] is the file's url, [message] names the field.
 */
interface GribStore {
    fun get(source: String, message: String): ByteArray?
    fun put(source: String, message: String, bytes: ByteArray)
}

/** The last few messages in memory (what a platform without a disk store uses, and the tests). */
class MemoryGribStore(private val limit: Int = 12) : GribStore {
    private val messages = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    override fun get(source: String, message: String): ByteArray? = messages["$source#$message"]
    override fun put(source: String, message: String, bytes: ByteArray) {
        messages["$source#$message"] = bytes
        while (messages.size > limit) messages.remove(messages.keys.first())
    }
}

/**
 * Byte ranges (first, last inclusive) merged where they touch or overlap, in order of position: messages that are next to each other in a file (the U and V
 * wind of a level, for one) are then one request. The result remembers which ranges it covers so the reply can be cut back into the messages.
 */
fun coalesceRanges(ranges: List<LongRange>): List<LongRange> {
    val sorted = ranges.sortedBy { it.first }
    val out = ArrayList<LongRange>()
    for (r in sorted) {
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
    }
    return out
}
