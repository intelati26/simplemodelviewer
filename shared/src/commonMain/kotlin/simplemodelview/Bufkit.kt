package simplemodelview

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One level of a model sounding. Missing values are NaN. Pressure in mb, temperatures in C, wind in knots from [dirDeg], height in meters above sea level. */
data class SoundingLevel(val pressure: Double, val tempC: Double, val wetBulbC: Double, val dewC: Double, val dirDeg: Double, val speedKt: Double, val heightM: Double) {
    /** The wind's components in knots (u east, v north), or null when direction or speed is missing. */
    fun uv(): Pair<Double, Double>? {
        if (dirDeg.isNaN() || speedKt.isNaN()) return null
        val r = dirDeg * PI / 180.0
        return -speedKt * sin(r) to -speedKt * cos(r)
    }
}

/** A model profile at one forecast hour: [indices] are the file's own numbers (CAPE, CINS, LCLP, PWAT, KINX ...), surface to top. */
class Sounding(val valid: Long, val forecastHour: Int, val levels: List<SoundingLevel>, val indices: Map<String, Double>)

/** A BUFKIT station file: every forecast hour of one model run at one station. */
class BufkitFile(val id: String, val lat: Double, val lon: Double, val elevationM: Double, val soundings: List<Sounding>) {
    /** The model run's time (epoch seconds): the first profile's valid time less its forecast hour. */
    val run: Long? get() = soundings.firstOrNull()?.let { it.valid - it.forecastHour * 3600L }
}

/** The models of Iowa State's BUFKIT feed that the viewer reads. */
enum class BufkitModel(val label: String, private val directory: String, private val prefix: String) {
    RRFS("RRFS", "rrfs", "rrfs"), GFS("GFS", "gfs", "gfs3");

    /** The file of the ASOS [id] ("KOKC"): one request, every forecast hour. */
    fun url(id: String) = "https://meteor.geol.iastate.edu/data/bufkit/$directory/${prefix}_${id.lowercase()}.buf"

    /** The directory page listing every station file of the model. */
    val listingUrl get() = "https://meteor.geol.iastate.edu/data/bufkit/$directory/"

    /** The station ids ("KOKC") a directory page lists, from its file links. */
    fun stationsIn(listing: String): Set<String> =
        Regex("""href="${prefix}_([a-z0-9]+)\.buf"""").findAll(listing).map { it.groupValues[1].uppercase() }.toSet()
}

/** A station with a BUFKIT file: its ASOS [id], [name], position and how far it is from the point asked about. */
data class BufkitStation(val id: String, val name: String, val lat: Double, val lon: Double, val km: Double)

object Bufkit {
    private const val MISSING = -9999.0
    private val head = Regex("""STID\s*=\s*(\w*)\s+STNM\s*=\s*(\d+)\s+TIME\s*=\s*(\d{6})/(\d{4})""")
    private val pair = Regex("""([A-Z]+)\s*=\s*(-?\d+(?:\.\d+)?)""")
    private val number = Regex("""-?\d+(?:\.\d+)?""")

    private fun value(v: Double) = if (v <= MISSING + 1.0) Double.NaN else v

    /** The profiles of a BUFKIT file's text, or null when it holds none (the layout is the one NCEP's BUFKIT exports have: a column list, then a block per hour). */
    fun parse(text: String): BufkitFile? {
        val lines = text.lines()
        val columns = lines.firstOrNull { it.trimStart().startsWith("SNPARM") }?.substringAfter('=')?.trim()?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return null
        val col = columns.withIndex().associate { it.value to it.index }
        val iP = col["PRES"] ?: return null
        val iT = col["TMPC"] ?: return null
        val iD = col["DWPC"] ?: return null
        val iH = col["HGHT"] ?: return null
        val n = columns.size

        var id = ""; var lat = Double.NaN; var lon = Double.NaN; var elev = Double.NaN
        val out = ArrayList<Sounding>()
        var i = 0
        while (i < lines.size) {
            val m = head.find(lines[i])
            if (m == null) { i++; continue }
            if (id.isEmpty()) id = m.groupValues[1]
            val t = m.groupValues[3]; val hm = m.groupValues[4]
            val valid = epochOf(2000 + t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt(), hm.substring(0, 2).toInt(), hm.substring(2, 4).toInt(), 0)
            var stim = 0
            val indices = LinkedHashMap<String, Double>()
            val data = ArrayList<Double>()
            var inData = false
            var done = false                     // the profile's numbers end at the first line with letters (the file's last block is followed by a table of surface data)
            i++
            while (i < lines.size && head.find(lines[i]) == null) {
                val line = lines[i].trim()
                when {
                    done -> {}
                    inData -> if (line.any { it.isLetter() }) done = true else number.findAll(line).forEach { data += it.value.toDouble() }
                    line.startsWith("PRES") -> { inData = true; i++ /* the column names go over two lines */ }
                    else -> for (p in pair.findAll(line)) {
                        val v = p.groupValues[2].toDouble()
                        when (p.groupValues[1]) {
                            "SLAT" -> lat = v; "SLON" -> lon = v; "SELV" -> elev = v
                            "STIM" -> stim = v.toInt()
                            "STNM" -> {}
                            else -> indices[p.groupValues[1]] = value(v)
                        }
                    }
                }
                i++
            }
            val levels = (0 until data.size / n).mapNotNull { k ->
                val r = k * n
                val p = value(data[r + iP])
                if (p.isNaN()) null else SoundingLevel(
                    p, value(data[r + iT]), col["TMWC"]?.let { value(data[r + it]) } ?: Double.NaN, value(data[r + iD]),
                    col["DRCT"]?.let { value(data[r + it]) } ?: Double.NaN, col["SKNT"]?.let { value(data[r + it]) } ?: Double.NaN, value(data[r + iH]),
                )
            }
            if (levels.size >= 3) out += Sounding(valid, stim, levels, indices)
        }
        if (out.isEmpty()) return null
        return BufkitFile(id, lat, lon, elev, out)
    }

    /**
     * The [max] nearest of the [stations] (the NWS observing stations near a point) that [available] (the ids a model's directory lists) holds, nearest first. A
     * station is an airport's ASOS: its id is the BUFKIT file's name.
     */
    fun nearest(stations: List<NwsStation>, available: Set<String>, lat: Double, lon: Double, max: Int = 5): List<BufkitStation> =
        stations.filter { it.id.uppercase() in available }
            .map { BufkitStation(it.id.uppercase(), it.name, it.lat, it.lon, distanceKm(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.km }.take(max)

    /** The great-circle distance in kilometers (a station's distance from the point asked about). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = PI / 180.0
        val a = sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
        return 2 * 6371.0 * kotlin.math.asin(sqrt(a))
    }
}

/**
 * Finds the stations with a model's BUFKIT file near a point: the NWS list of observing stations near it (api.weather.gov, as the Extended page uses), and the
 * model's directory page (one request, kept for [KEEP_SECONDS]) for which of them have a file. Nothing of the profiles is downloaded here.
 */
class BufkitStations(private val http: Http, private val nws: NwsPointClient, private val now: () -> Long) {
    private class Listing(val ids: Set<String>, val at: Long)
    private val listings = HashMap<BufkitModel, Listing>()

    private suspend fun available(model: BufkitModel): Set<String>? {
        listings[model]?.takeIf { now() - it.at < KEEP_SECONDS }?.let { return it.ids }
        val r = http.get(model.listingUrl)
        if (r.status != 200) return listings[model]?.ids
        return model.stationsIn(r.body.decodeToString()).also { listings[model] = Listing(it, now()) }
    }

    /** The nearest stations of [model] to the point, or null when the point or the listing could not be had (a point outside the U.S. has no NWS stations). */
    suspend fun near(lat: Double, lon: Double, model: BufkitModel, max: Int = 5): List<BufkitStation>? {
        val point = nws.point(lat, lon) ?: return null
        val list = http.get(point.stationsUrl)
        if (list.status != 200) return null
        val ids = available(model) ?: return null
        return Bufkit.nearest(parseNwsStations(list.body.decodeToString()), ids, lat, lon, max)
    }

    companion object { const val KEEP_SECONDS = 1800L }
}
