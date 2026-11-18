package simplemodelview

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp

/**
 * The surface analysis page: NOAA's Real-Time and Un-Restricted Mesoscale Analyses (RTMA, URMA) on their 2.5 km grid, from the open data on AWS.
 *
 *  - RTMA rapid update: every 15 minutes, posted about 15 minutes after its time, from 2017-12-13 (`noaa-rtma-pds/rtma2p5_ru.<day>/`).
 *  - RTMA: every hour, posted within the hour, from 2013 (`noaa-rtma-pds/rtma2p5.<day>/`).
 *  - URMA: every hour, the same analysis made again about six hours later with the observations that came in late, from 2014 (`noaa-urma-pds/urma2p5.<day>/`).
 *
 * A loop inside the last six hours steps every 15 minutes (rapid update); anything else steps every hour, from URMA where it is there, else RTMA.
 */
enum class AnalysisSource(val label: String) { RU("RTMA-RU"), RTMA("RTMA"), URMA("URMA") }

/** One frame of the page: a valid time and the sources to try for it, in order. */
data class AnalysisFrame(val valid: Long, val sources: List<AnalysisSource>) {
    val quarterHourly: Boolean get() = sources.firstOrNull() == AnalysisSource.RU
}

object AnalysisBuckets {
    const val RTMA = "https://noaa-rtma-pds.s3.amazonaws.com"
    const val URMA = "https://noaa-urma-pds.s3.amazonaws.com"

    /** The first hour with files: RTMA from 2013-03-19, URMA from 2014-01-28, the rapid update from 2017-12-13. */
    val FIRST_RTMA = epochOf(2013, 3, 19, 0, 0, 0)
    val FIRST_URMA = epochOf(2014, 1, 28, 0, 0, 0)
    val FIRST_RU = epochOf(2017, 12, 13, 0, 0, 0)

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /**
     * The analysis files that may hold [source] at [valid], in the order to try: the rapid update has one name; the hourly ones were `_ext` until 2017
     * and `_wexp` since (the newer name first for recent hours, the older first before 2017).
     */
    fun urls(source: AnalysisSource, valid: Long): List<String> {
        val c = civilFromEpoch(valid)
        val day = "${c[0]}${two(c[1])}${two(c[2])}"
        return when (source) {
            AnalysisSource.RU -> listOf("$RTMA/rtma2p5_ru.$day/rtma2p5_ru.t${two(c[3])}${two(c[4])}z.2dvaranl_ndfd.grb2")
            AnalysisSource.RTMA, AnalysisSource.URMA -> {
                val (bucket, name) = if (source == AnalysisSource.RTMA) RTMA to "rtma2p5" else URMA to "urma2p5"
                val base = "$bucket/$name.$day/$name.t${two(c[3])}z.2dvaranl_ndfd.grb2"
                if (c[0] >= 2017) listOf("${base}_wexp", "${base}_ext") else listOf("${base}_ext", "${base}_wexp")
            }
        }
    }

    /** The hourly sources for a past hour: URMA first once it is surely posted (eight hours on), RTMA first before that (and as the fallback). */
    fun hourlySources(valid: Long, now: Long): List<AnalysisSource> = buildList {
        val urmaOk = valid >= FIRST_URMA
        if (urmaOk && now - valid >= 8 * 3600L) add(AnalysisSource.URMA)
        add(AnalysisSource.RTMA)
        if (urmaOk && now - valid < 8 * 3600L) add(AnalysisSource.URMA)
    }

    /** The frames of the last [hours] hours (15-minute steps, rapid update) ending at [newest]. */
    fun recentFrames(newest: Long, hours: Int = 6): List<AnalysisFrame> =
        (0..hours * 4).map { k ->
            val t = newest - (hours * 4 - k) * 900L
            AnalysisFrame(t, if (floorMod(t, 3600L) == 0L) listOf(AnalysisSource.RU, AnalysisSource.RTMA) else listOf(AnalysisSource.RU))   // only an hour's frame can fall back to the hourly file
        }
            .filter { it.valid >= FIRST_RU }

    /** Hourly frames from [start] to [end] (rounded to whole hours), thinned to at most [limit] frames (every n-th hour). */
    fun historyFrames(start: Long, end: Long, now: Long, limit: Int = 240): List<AnalysisFrame> {
        val a = maxOf(start - floorMod(start, 3600L), FIRST_RTMA)
        val b = minOf(end - floorMod(end, 3600L), now - floorMod(now, 3600L))
        if (b < a) return emptyList()
        val hours = ((b - a) / 3600L).toInt() + 1
        val step = (hours + limit - 1) / limit
        return (0 until hours step step).map { h -> val t = a + h * 3600L; AnalysisFrame(t, hourlySources(t, now)) }
    }
}

/** One message of an analysis file: its parameter name ("TMP", "DPT", ...), where it starts and how long it is. */
class AnalysisMessage(val name: String, val offset: Long, val length: Long)

/** The name of a GRIB2 parameter the analyses carry (discipline, category, number, first surface type), or "d/c/n" for any other. */
fun analysisParameterName(discipline: Int, category: Int, number: Int, surface: Int): String = when {
    discipline == 0 && category == 0 && number == 0 -> "TMP"
    discipline == 0 && category == 0 && number == 6 -> "DPT"
    discipline == 0 && category == 1 && number == 0 -> "SPFH"
    discipline == 0 && category == 2 && number == 0 -> "WDIR"
    discipline == 0 && category == 2 && number == 1 -> "WIND"
    discipline == 0 && category == 2 && number == 2 -> "UGRD"
    discipline == 0 && category == 2 && number == 3 -> "VGRD"
    discipline == 0 && category == 2 && number == 22 -> "GUST"
    discipline == 0 && category == 3 && number == 0 && surface == 1 -> "PRES"
    discipline == 0 && category == 3 && number == 5 && surface == 1 -> "HGT"
    discipline == 0 && category == 19 && number == 0 -> "VIS"
    discipline == 0 && category == 6 && number == 13 -> "CEIL"
    discipline == 0 && category == 6 && number == 1 -> "TCDC"
    discipline == 10 && category == 0 && number == 3 -> "HTSGW"
    else -> "$discipline/$category/$number"
}

/** The parameter and total length of the GRIB2 message at the start of [bytes] (its first few hundred bytes are enough), or null when it is not one. */
fun analysisMessageHeader(bytes: ByteArray): Pair<String, Long>? {
    if (bytes.size < 16 || bytes.decodeToString(0, 4) != "GRIB" || bytes[7].toInt() != 2) return null
    fun u(at: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (bytes[at + i].toLong() and 255); return v }
    val discipline = bytes[6].toInt() and 255
    val total = u(8, 8)
    var at = 16
    while (at + 5 <= bytes.size) {
        val length = u(at, 4).toInt()
        val section = bytes[at + 4].toInt()
        if (length < 5) return null
        if (section == 4) {
            if (at + 23 > bytes.size) return null
            val category = bytes[at + 9].toInt() and 255
            val number = bytes[at + 10].toInt() and 255
            val surface = bytes[at + 22].toInt() and 255
            return analysisParameterName(discipline, category, number, surface) to total
        }
        at += length
    }
    return null
}

/** A field of the page and how it is made from the file's messages (in the units shown). */
class AnalysisProduct(val id: String, val title: String, val style: Style, val needs: List<String>, val make: (List<FloatArray>) -> FloatArray)

object AnalysisCatalog {
    private fun map(v: FloatArray, f: (Float) -> Float) = FloatArray(v.size) { f(v[it]) }
    private fun kToF(k: Float) = (k - 273.15f) * 1.8f + 32f
    private fun es(c: Double) = 6.112 * exp(17.67 * c / (c + 243.5))
    private fun nbm(id: String, fallback: Style) = NbmCatalog.byId(id)?.style ?: fallback

    private val temperature = Style(Kind.Blended, Palettes.temperature(true), units = "F", digits = 0)

    // ids starting t2m / td2m get the usual temperature contour lines (every 10 F, 32 F heavier)
    val all: List<AnalysisProduct> = listOf(
        AnalysisProduct("t2m_anl", "Temperature", temperature, listOf("TMP")) { (t) -> map(t, ::kToF) },
        AnalysisProduct("td2m_anl", "Dewpoint", nbm("dewpoint", temperature), listOf("DPT")) { (d) -> map(d, ::kToF) },
        AnalysisProduct("rh_anl", "Relative humidity", Style(Kind.Banded, Palettes.relativeHumidity, units = "%", digits = 0), listOf("TMP", "DPT")) { (t, d) ->
            FloatArray(t.size) { i -> if (t[i].isNaN() || d[i].isNaN()) Float.NaN else (100.0 * es(d[i] - 273.15) / es(t[i] - 273.15)).toFloat().coerceIn(0f, 100f) }
        },
        AnalysisProduct("dd_anl", "Dewpoint depression", Style(Kind.Blended, Palettes.rainbow(0.0, 50.0), units = "F", digits = 0), listOf("TMP", "DPT")) { (t, d) ->
            FloatArray(t.size) { i -> (t[i] - d[i]) * 1.8f }
        },
        AnalysisProduct("wind_anl", "Wind speed", nbm("windspd", Style(Kind.Banded, Palettes.windSpeed(1.0), hideBelow = 10.0, units = "kt", digits = 0)), listOf("WIND")) { (w) -> map(w) { it * 1.9438445f } },
        AnalysisProduct("gust_anl", "Wind gust", nbm("windgust", Style(Kind.Banded, Palettes.windSpeed(1.0), hideBelow = 10.0, units = "kt", digits = 0)), listOf("GUST")) { (w) -> map(w) { it * 1.9438445f } },
        AnalysisProduct("pres_anl", "Surface pressure", Style(Kind.Blended, Palettes.rainbow(620.0, 1040.0), units = "hPa", digits = 0), listOf("PRES")) { (p) -> map(p) { it / 100f } },
        AnalysisProduct("spfh_anl", "Specific humidity", Style(Kind.Blended, Palettes.rainbow(0.0, 22.0), units = "g/kg", digits = 1), listOf("SPFH")) { (q) -> map(q) { it * 1000f } },
        AnalysisProduct("vis_anl", "Visibility", nbm("vis", Style(Kind.Blended, Palettes.rainbow(0.0, 10.0), units = "mi", digits = 1)), listOf("VIS")) { (v) -> map(v) { it / 1609.344f } },
        AnalysisProduct("ceil_anl", "Ceiling", nbm("ceil", Style(Kind.Blended, Palettes.rainbow(0.0, 120.0), units = "100 ft", digits = 0)), listOf("CEIL")) { (c) -> map(c) { it / 30.48f } },
        AnalysisProduct("sky_anl", "Cloud cover", nbm("sky", Style(Kind.Blended, Palettes.cloud(0x808080), units = "%", digits = 0)), listOf("TCDC")) { (c) -> c },
        AnalysisProduct("hgt_anl", "Ground height", Style(Kind.Blended, Palettes.rainbow(0.0, 12000.0), units = "ft", digits = 0), listOf("HGT")) { (h) -> map(h) { it * 3.28084f } },
    )

    val default = all[0]
    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

/**
 * Finds and downloads the messages of the analysis files. Each file's messages are found once per kind of file (the hourly RTMA files of a year are laid
 * out alike: the packing gives every message a fixed length), by reading the head of each message in turn (a few hundred bytes each, about fourteen
 * requests); after that a field is one request, checked against what it should be and found afresh when the layout has changed. The messages are kept in
 * [store] like the other GRIB messages.
 */
class AnalysisRepository(private val http: Http, private val core: Core, private val store: GribStore = MemoryGribStore()) {
    private val lock = Mutex()
    private val layouts = HashMap<String, List<AnalysisMessage>>()       // per kind of file ("RU", "RTMA_wexp", ...): its messages
    private val missing = HashMap<String, Long>()                         // url -> when it was not there

    private fun kind(url: String) = when {
        "rtma2p5_ru." in url -> "RU"
        "urma2p5." in url -> "URMA_" + url.substringAfterLast('_')
        else -> "RTMA_" + url.substringAfterLast('_')
    }

    /** The newest rapid-update analysis that is posted (its time), looking back two hours from [now]; null when there is none. */
    suspend fun newestQuarterHour(now: Long): Long? {
        var t = now - floorMod(now, 900L)
        repeat(9) {
            val url = AnalysisBuckets.urls(AnalysisSource.RU, t).first()
            val r = try { http.get("$url.idx") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (r?.status == 200) return t
            t -= 900L
        }
        return null
    }

    /** What a frame was read from: the source and the file. */
    class Read(val source: AnalysisSource, val url: String, val fields: List<GridField>)

    /** The messages [names] of the first file of [frame] that has them all (decoded, raw units, on their grid); null when no file of it is there. */
    suspend fun load(frame: AnalysisFrame, names: List<String>, nowEpoch: Long, status: (String) -> Unit = {}): Read? {
        for (source in frame.sources) {
            for (url in AnalysisBuckets.urls(source, frame.valid)) {
                if (lock.withLock { missing[url]?.let { nowEpoch - it < 300 } == true }) continue
                val bytes = try { names.map { message(url, it, status) ?: return@map null } } catch (e: Missing) { lock.withLock { missing[url] = nowEpoch }; continue }
                if (bytes.any { it == null }) continue
                status("Decoding…")
                val fields = bytes.map { core.decodeGrib(it!!) }
                return Read(source, url, fields)
            }
        }
        return null
    }

    /** Downloads the messages of a frame without decoding them (a loop's prefetch); false when no file of it is there. */
    suspend fun prefetch(frame: AnalysisFrame, names: List<String>, nowEpoch: Long): Boolean {
        for (source in frame.sources) for (url in AnalysisBuckets.urls(source, frame.valid)) {
            if (lock.withLock { missing[url]?.let { nowEpoch - it < 300 } == true }) continue
            try { if (names.all { message(url, it) {} != null }) return true } catch (e: Missing) { lock.withLock { missing[url] = nowEpoch } }
        }
        return false
    }

    private class Missing : Exception()

    /** The bytes of message [name] of [url] (kept, or downloaded); null when the file has no such message; [Missing] when the file is not there. */
    private suspend fun message(url: String, name: String, status: (String) -> Unit): ByteArray? {
        store.get(url, name)?.let { return it }
        val kind = kind(url)
        val known = lock.withLock { layouts[kind] }
        known?.firstOrNull { it.name == name }?.let { m ->
            status("Downloading $name (${m.length / 1000} kB)…")
            val part = http.getRange(url, m.offset, m.offset + m.length - 1)
            if (part.status == 404 || part.status == 403) throw Missing()
            if ((part.status == 200 || part.status == 206) && part.body.size.toLong() == m.length && analysisMessageHeader(part.body)?.let { it.first == name && it.second == m.length } == true) {
                store.put(url, name, part.body)
                return part.body
            }
        }
        // not laid out as expected (or not yet known): read the heads of the messages and try again
        status("Reading the file's layout…")
        val layout = walk(url) ?: throw Missing()
        lock.withLock { layouts[kind] = layout }
        val m = layout.firstOrNull { it.name == name } ?: return null
        status("Downloading $name (${m.length / 1000} kB)…")
        val part = http.getRange(url, m.offset, m.offset + m.length - 1)
        if ((part.status != 200 && part.status != 206) || part.body.size.toLong() != m.length) throw RefsException("the $name message could not be downloaded (HTTP ${part.status})")
        store.put(url, name, part.body)
        return part.body
    }

    /** The messages of [url] from the head of each in turn; null when the file is not there. */
    private suspend fun walk(url: String): List<AnalysisMessage>? {
        val out = ArrayList<AnalysisMessage>()
        var offset = 0L
        for (k in 0 until 24) {
            val head = http.getRange(url, offset, offset + 511)
            if (head.status == 404 || head.status == 403) return if (k == 0) null else out
            if (head.status == 416 || head.body.isEmpty()) break
            if (head.status != 200 && head.status != 206) return if (k == 0) null else out
            val (name, length) = analysisMessageHeader(head.body) ?: break
            out += AnalysisMessage(name, offset, length)
            offset += length
        }
        return out.ifEmpty { null }
    }
}
