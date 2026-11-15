package simplemodelview

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * NBM text bulletins: the blend's forecast for a station as a table of 12-hourly (extended, NBE: hours 24-192; super-extended, NBX: 204-264) or
 * 3-hourly (short range, NBS: hours 6-72) columns. One element per row ([rows] is keyed by the bulletin's code: TMP, TSD, P12, ...); a missing value
 * (the bulletin's -99, or a blank field) is null. Units are the bulletin's own: temperatures in F, wind in knots, direction in tens of degrees, sky and
 * chances in percent, rain in 1/100 inch, snow in 1/10 inch, snow level in hundreds of feet (see [NbmTextElements]).
 */
class NbmTextTable(val station: String, val kind: String, val cycleEpoch: Long, val hours: List<Int>, val rows: Map<String, List<Int?>>) {
    /** Valid times (epoch seconds), one per column. */
    val validTimes: List<Long> get() = hours.map { cycleEpoch + it * 3600L }

    /** The values of one element, one per column (all null for an element the bulletin does not carry). */
    fun values(code: String): List<Int?> = rows[code] ?: List(hours.size) { null }
}

/** The elements of the bulletins that the extended view uses, with their units as the bulletins give them. */
object NbmTextElements {
    const val TEMP = "TMP"          // F
    const val TEMP_SPREAD = "TSD"   // F, standard deviation
    const val DEW = "DPT"           // F
    const val DEW_SPREAD = "DSD"
    const val MAX_MIN = "TXN"       // F: the 18-hour maximum (reported at 00Z) / minimum (12Z)
    const val SKY = "SKY"           // %
    const val WIND_DIR = "WDR"      // tens of degrees (36 = north, 9 = east)
    const val WIND = "WSP"          // knots
    const val GUST = "GST"          // knots
    const val POP12 = "P12"         // %
    const val QPF12 = "Q12"         // 1/100 inch
    const val QPF24 = "Q24"         // 1/100 inch
    const val THUNDER12 = "T12"     // % (NBE and NBS only)
    const val SNOW12 = "S12"        // 1/10 inch
    const val ICE12 = "I12"         // 1/100 inch
    const val SNOW_LEVEL = "SLV"    // hundreds of feet MSL
    const val RAIN = "PRA"          // % of the precipitation that falls as rain (and PSN snow, PZR freezing rain, PPL sleet)
    const val SNOW = "PSN"
    const val FREEZING_RAIN = "PZR"
    const val SLEET = "PPL"
}

private val headerPattern = Regex("""^\s*(\S+)\s+NBM\s+(NB[HSEXP])\s+GUIDANCE\s+(\d+)/(\d+)/(\d+)\s+(\d\d)(\d\d)\s+UTC""")
private val labelPattern = Regex("""^[A-Z][A-Z0-9]{2}$""")

/**
 * One station's block of a bulletin file (its header line, the DT / UTC / FHR lines and the element rows), or null for text that is not one. The values
 * are read by column position: each FHR number's last digit is the last character of its column, the field is the three characters ending there (the
 * columns of a short-range bulletin have no separator, so a snow level of 120 touches its neighbors).
 */
fun parseNbmTextBlock(text: String): NbmTextTable? {
    val lines = text.lines()
    val headerIndex = lines.indexOfFirst { "GUIDANCE" in it }
    if (headerIndex < 0) return null
    val m = headerPattern.find(lines[headerIndex]) ?: return null
    val cycle = epochOf(m.groupValues[5].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[6].toInt(), m.groupValues[7].toInt(), 0)
    val fhrIndex = (headerIndex + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("FHR") } ?: return null
    val fhr = lines[fhrIndex]
    val hours = ArrayList<Int>()
    val ends = ArrayList<Int>()
    var i = fhr.indexOf("FHR") + 3
    while (i < fhr.length) {
        if (fhr[i].isDigit()) {
            var j = i
            while (j < fhr.length && fhr[j].isDigit()) j++
            hours += fhr.substring(i, j).toInt()
            ends += j - 1
            i = j
        } else i++
    }
    if (hours.isEmpty()) return null
    val rows = LinkedHashMap<String, List<Int?>>()
    for (k in fhrIndex + 1 until lines.size) {
        val line = lines[k]
        if (line.length < 5) continue
        if (headerPattern.containsMatchIn(line)) break      // the next station's block
        val label = line.substring(0, 4).trim()
        if (!labelPattern.matches(label)) continue
        rows[label] = ends.map { e ->
            val field = line.substring(minOf(line.length, maxOf(0, e - 2)), minOf(line.length, e + 1)).trim()
            field.toIntOrNull()?.takeIf { it > -99 && it < 999 }      // -99: missing; 998: "998 or more"
        }
    }
    return NbmTextTable(m.groupValues[1], m.groupValues[2], cycle, hours, rows)
}

/** A bulletin file of the NBM bucket: [kind] is "NBS", "NBE" or "NBX". */
class NbmTextFile(val kind: String, val cycleEpoch: Long, val key: String, val size: Long) {
    val url: String get() = NbmGribBucket.URL + "/" + key
}

/**
 * Reads one station out of the bulletin files without downloading them (all 9,589 stations are in one file: about 10.6 MB for NBX, 20.9 MB for NBE).
 * The stations are sorted, but the file is really several sorted runs one after another (the main list of land stations, then lists of buoys and other
 * stations appended), so a plain bisection can go wrong. A coarse index of [SAMPLES] small windows spread over the file (kept per file) tells where the
 * runs break; the station is then looked for in the stretches where it can be: bisected where the sampled ids ascend through it, read in chunks where a
 * break lies inside.
 */
class NbmTextRepository(private val http: Http) {
    private val indexes = HashMap<String, List<Pair<Long, String>>>()
    private val gate = Semaphore(4)

    /** The newest [kind] bulletin file at or before [nowEpochSeconds] (looks back up to [hoursBack] hourly cycles), or null. */
    suspend fun newest(kind: String, nowEpochSeconds: Long, hoursBack: Int = 12): NbmTextFile? {
        val file = when (kind) { "NBS" -> "nbstx"; "NBE" -> "nbetx"; "NBX" -> "nbxtx"; else -> return null }
        val start = nowEpochSeconds - nowEpochSeconds % 3600
        for (back in 0..hoursBack) {
            val cycle = start - back * 3600L
            val c = civilFromEpoch(cycle)
            val day = "${c[0]}${c[1].toString().padStart(2, '0')}${c[2].toString().padStart(2, '0')}"
            val hour = c[3].toString().padStart(2, '0')
            val prefix = "blend.$day/$hour/text/blend_$file.t${hour}z"
            val reply = http.get("${NbmGribBucket.URL}/?list-type=2&max-keys=1&prefix=$prefix")
            if (reply.status != 200) continue
            val xml = reply.body.decodeToString()
            val key = xml.substringAfter("<Key>", "").substringBefore("</Key>")
            val size = xml.substringAfter("<Size>", "").substringBefore("</Size>").toLongOrNull() ?: continue
            if (key == prefix && size > 0) return NbmTextFile(kind, cycle, key, size)
        }
        return null
    }

    /** The station's table from [file] (null when the file does not have it, or cannot be read). [id] is the bulletin's id: "KOKC". */
    suspend fun station(file: NbmTextFile, id: String): NbmTextTable? {
        val samples = index(file) ?: return null
        // the stretches the station can be in: where the sampled ids ascend through it (bisected), or a break lies inside (read through)
        for (i in samples.indices) {
            val a = samples[i]
            val b = samples.getOrNull(i + 1)
            val end = b?.first ?: file.size
            val ascending = a.second <= id && (b == null || id <= b.second)
            val broken = b == null || a.second > b.second     // (the last stretch may hold a break too: the real file's last run starts near its end)
            if (!ascending && !broken) continue
            if (ascending) block(bisect(file, a.first, end, id), id)?.let { return parseNbmTextBlock(it) }
            if (broken || end - a.first <= SCAN_LIMIT) block(scan(file, a.first, end, id), id)?.let { return parseNbmTextBlock(it) }
        }
        return null
    }

    private suspend fun index(file: NbmTextFile): List<Pair<Long, String>>? {
        indexes[file.key]?.let { return it }
        val step = file.size / SAMPLES
        val found = coroutineScope {
            (0 until SAMPLES).map { n -> async { gate.withPermit { headers(read(file, n * step, n * step + WINDOW - 1)).firstOrNull()?.let { (o, id) -> n * step + o to id } } } }.awaitAll()
        }.filterNotNull().distinctBy { it.first }.sortedBy { it.first }
        if (found.size < SAMPLES / 2) return null
        return found.also { indexes[file.key] = it }
    }

    /**
     * The bytes [first]..[last] as text (one character a byte, so offsets in the text are offsets in the file); empty when they cannot be read. A read that
     * reaches the end of the file gets a header line of an imaginary station after it, so the last station's block has an end like any other.
     */
    private suspend fun read(file: NbmTextFile, first: Long, last: Long): String {
        val to = minOf(last, file.size - 1)
        val reply = http.getRange(file.url, first, to)
        if (reply.status != 200 && reply.status != 206) return ""
        val text = String(CharArray(reply.body.size) { (reply.body[it].toInt() and 255).toChar() })
        return if (to >= file.size - 1) text + "\n ~END~ NBM  ${file.kind} GUIDANCE\n" else text
    }

    /** A stretch with a break in it: read in overlapping chunks, a few at a time, until the station's block turns up. */
    private suspend fun scan(file: NbmTextFile, first: Long, end: Long, id: String): String {
        val starts = generateSequence(first) { it + CHUNK }.takeWhile { it < end }.toList()
        val texts = coroutineScope { starts.map { s -> async { gate.withPermit { read(file, s, minOf(end + WINDOW, s + CHUNK + 2 * WINDOW)) } } }.awaitAll() }
        return texts.firstOrNull { block(it, id) != null } ?: ""
    }

    /** Bisects an ascending stretch to the station's block and returns text that holds it (with the header line after it too). */
    private suspend fun bisect(file: NbmTextFile, first: Long, end: Long, id: String): String {
        var lo = first
        var hi = end
        while (hi - lo > 3L * WINDOW) {
            val mid = (lo + hi) / 2
            val at = headers(read(file, mid, mid + WINDOW - 1)).firstOrNull()
            if (at == null) { hi = mid; continue }
            val (o, found) = at
            when {
                found < id -> lo = mid + o
                found > id -> hi = mid
                else -> return read(file, mid + o, mid + o + 2L * WINDOW)
            }
        }
        return read(file, lo, hi + 2L * WINDOW)
    }

    private companion object {
        const val SAMPLES = 32
        const val WINDOW = 4096L
        const val CHUNK = 96L * 1024
        const val SCAN_LIMIT = 1024L * 1024   // the longest ascending stretch read through when bisecting did not find the station (a break hidden in it)

        /** (offset of the header line in the text, station id) for every header line in [text]. */
        fun headers(text: String): List<Pair<Long, String>> =
            Regex("""\n (\S+)\s+NBM\s+NB[HSEXP]\s+GUIDANCE""").findAll(text).map { (it.range.first + 1).toLong() to it.groupValues[1] }.toList()

        /** The block of station [id] in [text]: from its header line to the next header line (which must be in the text, or the block may be cut off: null). */
        fun block(text: String, id: String): String? {
            val hs = headers(if (text.startsWith(" ")) "\n$text" else text)
            val shift = if (text.startsWith(" ")) 1 else 0
            val at = hs.indexOfFirst { it.second == id }
            if (at < 0 || at + 1 >= hs.size) return null
            return text.substring(hs[at].first.toInt() - shift, hs[at + 1].first.toInt() - shift)
        }
    }
}
