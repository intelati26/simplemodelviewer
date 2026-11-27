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
}

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

    /** The great-circle distance in kilometers (a station's distance from the point asked about). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = PI / 180.0
        val a = sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
        return 2 * 6371.0 * kotlin.math.asin(sqrt(a))
    }
}
