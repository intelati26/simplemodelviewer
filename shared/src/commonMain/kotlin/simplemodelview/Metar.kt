package simplemodelview

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Surface observations (METAR / SPECI) from the Aviation Weather Center (aviationweather.gov): the latest report of every station from its bulk file
 * (`/data/cache/metars.cache.csv.gz`, about 250 kB for some 5000 stations, renewed every minute or so), and a station's reports of the last two days from
 * its data API. The 5-minute wind of a station (which AWC does not have) comes from the Iowa Environmental Mesonet.
 */
data class Metar(
    val id: String, val time: Long, val lat: Double, val lon: Double,
    val tempC: Double?, val dewC: Double?,
    val windDir: Int?,              // degrees the wind comes from; null when calm or variable
    val windKt: Int?, val gustKt: Int?,
    val visibility: String?,        // statute miles as reported ("10+", "1/2", "3")
    val weather: String?,           // present weather ("-SN BR", "TSRA")
    val category: String?,          // VFR / MVFR / IFR / LIFR
    val raw: String,
    val special: Boolean = false,
)

object MetarFeed {
    const val AWC = "https://aviationweather.gov"
    const val BULK = "$AWC/data/cache/metars.cache.csv.gz"
    fun historyUrl(id: String, hours: Int) = "$AWC/api/data/metar?ids=$id&format=json&hours=$hours"

    /** The 5-minute reports of a station (wind, gust, pressure; temperature only in the hourly ones) from the IEM, [start] to [end] (epoch seconds). */
    fun fiveMinuteUrl(id: String, start: Long, end: Long): String {
        val a = civilFromEpoch(start); val b = civilFromEpoch(end)
        val station = if (id.length == 4 && id.startsWith("K")) id.substring(1) else id
        return "https://mesonet.agron.iastate.edu/cgi-bin/request/asos.py?station=$station&data=tmpf&data=dwpf&data=sknt&data=drct&data=gust" +
            "&year1=${a[0]}&month1=${a[1]}&day1=${a[2]}&hour1=${a[3]}&minute1=${a[4]}&year2=${b[0]}&month2=${b[1]}&day2=${b[2]}&hour2=${b[3]}&minute2=${b[4]}" +
            "&tz=Etc/UTC&format=onlycomma&latlon=no&missing=M&report_type=1&report_type=3&report_type=4"
    }
}

/** Splits one CSV line, keeping commas inside quotes. */
internal fun csvFields(line: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i += 1 }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
            else -> cur.append(c)
        }
        i += 1
    }
    out += cur.toString()
    return out
}

/** The reports of AWC's bulk CSV (a header line names the columns); lines without a place or time are skipped. */
fun parseMetarCsv(text: String): List<Metar> {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    val headerAt = lines.indexOfFirst { it.startsWith("raw_text,") }
    if (headerAt < 0) return emptyList()
    val names = csvFields(lines[headerAt]).withIndex().associate { it.value to it.index }
    fun col(f: List<String>, name: String) = names[name]?.let { f.getOrNull(it) }?.takeIf { it.isNotEmpty() }
    val out = ArrayList<Metar>()
    for (line in lines.drop(headerAt + 1)) {
        val f = csvFields(line)
        val id = col(f, "station_id") ?: continue
        val time = col(f, "observation_time")?.let { isoToEpoch(it) } ?: continue
        val lat = col(f, "latitude")?.toDoubleOrNull() ?: continue
        val lon = col(f, "longitude")?.toDoubleOrNull() ?: continue
        out += Metar(
            id, time, lat, lon, col(f, "temp_c")?.toDoubleOrNull(), col(f, "dewpoint_c")?.toDoubleOrNull(),
            col(f, "wind_dir_degrees")?.toIntOrNull()?.takeIf { it in 1..360 }, col(f, "wind_speed_kt")?.toIntOrNull(), col(f, "wind_gust_kt")?.toIntOrNull(),
            col(f, "visibility_statute_mi"), col(f, "wx_string"), col(f, "flight_category"), col(f, "raw_text") ?: "", col(f, "metar_type") == "SPECI",
        )
    }
    return out
}

/** The reports of AWC's data API (format=json), newest first as it sends them. */
fun parseMetarJson(text: String): List<Metar> {
    val array = try { Json.parseToJsonElement(text) as? JsonArray } catch (e: Exception) { null } ?: return emptyList()
    return array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        val id = s("icaoId") ?: return@mapNotNull null
        val time = (o["obsTime"] as? JsonPrimitive)?.longOrNull ?: s("reportTime")?.let { isoToEpoch(it) } ?: return@mapNotNull null
        Metar(
            id, time, d("lat") ?: return@mapNotNull null, d("lon") ?: return@mapNotNull null, d("temp"), d("dewp"),
            (o["wdir"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..360 }, (o["wspd"] as? JsonPrimitive)?.intOrNull, (o["wgst"] as? JsonPrimitive)?.intOrNull,
            s("visib"), s("wxString"), s("fltCat"), s("rawOb") ?: "", s("metarType") == "SPECI",
        )
    }
}

/** One 5-minute report of the IEM: time, temperature and dewpoint (F, often missing), wind and gust (kt), direction. */
class FiveMinute(val time: Long, val tempF: Double?, val dewF: Double?, val windKt: Double?, val windDir: Double?, val gustKt: Double?)

fun parseFiveMinute(text: String): List<FiveMinute> {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.isEmpty() || !lines[0].startsWith("station,")) return emptyList()
    val names = lines[0].split(',').withIndex().associate { it.value to it.index }
    return lines.drop(1).mapNotNull { line ->
        val f = line.split(',')
        fun n(k: String) = names[k]?.let { f.getOrNull(it) }?.toDoubleOrNull()
        val t = names["valid"]?.let { f.getOrNull(it) }?.let { isoToEpoch(it.replace(' ', 'T') + ":00Z") } ?: return@mapNotNull null
        FiveMinute(t, n("tmpf"), n("dwpf"), n("sknt"), n("drct"), n("gust"))
    }
}

/**
 * The reports the page knows: the bulk file read now and then, kept as a few hours of reports per station (so a loop over the last hours finds the report
 * in force at each frame), and the history of a station when it is asked for. [gunzip] opens the bulk file (the platform's gzip).
 */
class MetarRepository(private val http: Http, private val gunzip: (ByteArray) -> ByteArray) {
    private val lock = Mutex()
    private val byStation = HashMap<String, MutableList<Metar>>()      // oldest first
    private var lastBulk = 0L

    /** Reads the bulk file again when it is older than [maxAge] seconds; the reports are added to those kept (the last [KEEP_SECONDS]). */
    suspend fun refresh(now: Long, maxAge: Long = 180): Boolean {
        if (lock.withLock { now - lastBulk < maxAge }) return true
        val r = try { http.get(MetarFeed.BULK) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null } ?: return false
        if (r.status != 200 || r.body.isEmpty()) return false
        val text = try { gunzip(r.body).decodeToString() } catch (e: Exception) { return false }
        val reports = parseMetarCsv(text)
        if (reports.isEmpty()) return false
        lock.withLock { reports.forEach { add(it) }; prune(now); lastBulk = now }
        return true
    }

    private fun add(m: Metar) {
        val list = byStation.getOrPut(m.id) { ArrayList() }
        if (list.any { it.time == m.time }) return
        list += m
        list.sortBy { it.time }
    }

    private fun prune(now: Long) {
        for (list in byStation.values) list.removeAll { now - it.time > KEEP_SECONDS }
        byStation.values.removeAll { it.isEmpty() }
    }

    /** The report in force at [time] at each station inside the box: its newest at or before [time], no older than [maxAge] seconds. */
    suspend fun at(time: Long, west: Double, south: Double, east: Double, north: Double, maxAge: Long = 5400): List<Metar> = lock.withLock {
        byStation.values.mapNotNull { list ->
            val m = list.lastOrNull { it.time <= time + 60 } ?: return@mapNotNull null
            if (time - m.time > maxAge || m.lat !in south..north || m.lon !in west..east) null else m
        }
    }

    /** A station's reports of the last [hours] hours (AWC), oldest first; they are also kept for the loop. */
    suspend fun history(id: String, hours: Int, now: Long): List<Metar> {
        val r = http.get(MetarFeed.historyUrl(id, hours.coerceIn(1, 48)))
        if (r.status != 200) throw RefsException("the station's reports could not be read (HTTP ${r.status})")
        val list = parseMetarJson(r.body.decodeToString()).sortedBy { it.time }
        lock.withLock { list.forEach { add(it) }; prune(now) }
        return list
    }

    /** The 5-minute reports of a station (IEM: AWC does not carry them), [start] to [end]; empty when they cannot be had. */
    suspend fun fiveMinute(id: String, start: Long, end: Long): List<FiveMinute> {
        val r = try { http.get(MetarFeed.fiveMinuteUrl(id, start, end)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        return if (r?.status == 200) parseFiveMinute(r.body.decodeToString()) else emptyList()
    }

    companion object {
        /** How long reports are kept for loops: six hours. */
        const val KEEP_SECONDS = 6 * 3600L
    }
}

/** Temperature in the shown unit, rounded ("72", "-3"). */
fun metarTemp(c: Double?, celsius: Boolean): String? = c?.let { kotlin.math.round(if (celsius) it else it * 1.8 + 32.0).toInt().toString() }

/**
 * Which stations get a plot: the picture positions [xy] (x0, y0, x1, y1, ...) of the candidates, taken in order (the caller puts the ones to keep first)
 * and kept when no kept one is nearer than [spacing] pixels. Returns the kept indexes.
 */
fun thinStations(xy: FloatArray, spacing: Float): List<Int> {
    val kept = ArrayList<Int>()
    val cell = spacing.coerceAtLeast(1f)
    val grid = HashMap<Long, MutableList<Int>>()
    fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    for (i in 0 until xy.size / 2) {
        val x = xy[2 * i]; val y = xy[2 * i + 1]
        val cx = kotlin.math.floor(x / cell).toInt(); val cy = kotlin.math.floor(y / cell).toInt()
        var clash = false
        loop@ for (dx in -1..1) for (dy in -1..1) {
            for (j in grid[key(cx + dx, cy + dy)].orEmpty()) {
                val ox = xy[2 * j] - x; val oy = xy[2 * j + 1] - y
                if (ox * ox + oy * oy < spacing * spacing) { clash = true; break@loop }
            }
        }
        if (!clash) { kept += i; grid.getOrPut(key(cx, cy)) { ArrayList() } += i }
    }
    return kept
}
