package simplemodelview

/**
 * NOAA / CIMSS LightningCast: the chance of lightning in the next hour from GOES-East, as contours at 10, 30, 50, 70 and 90 percent, one set per 5-minute scan
 * for the last hour. Read from the GRLevelX placefile CIMSS publishes (the feed and its parsing are those of wxqt's LightningCastLayer).
 */
object LightningCast {
    const val FEED = "https://cimss.ssec.wisc.edu/severe_conv/NOAACIMSS_PLTG_GOES-East_CONUS_LOOP"
}

/** One contour of a scan: the chance in percent, its color in the placefile (0xRRGGBB) and its points as lat0, lon0, lat1, lon1, ... */
class LightningContour(val percent: Int, val color: Int, val points: DoubleArray)

/** One scan: its start (epoch seconds) and the contours of that scan. */
class LightningFrame(val start: Long, val contours: List<LightningContour>)

private val percentPattern = Regex(""">=\s*(\d+)%""")

/**
 * The scans of a LightningCast placefile. `TimeRange: start end` opens a scan, `Color: r g b` and `Line: width, flags, "text >= 10%"` open a contour whose
 * points ("lat, lon") follow, and `End:` closes it. Anything else is ignored; damaged text gives fewer scans, never an exception. Oldest scan first.
 */
fun parseLightningCast(text: String): List<LightningFrame> {
    val frames = ArrayList<Pair<Long, ArrayList<LightningContour>>>()
    var color = 0xFFFFFF
    var percent = 0
    var inLine = false
    val points = ArrayList<Double>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("TimeRange:") -> {
                val start = isoToEpoch(line.removePrefix("TimeRange:").trim().substringBefore(' '))
                if (start != null) frames += start to ArrayList()
            }
            line.startsWith("Color:") -> {
                val p = line.removePrefix("Color:").trim().split(' ').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
                color = ((p.getOrElse(0) { 0 } and 255) shl 16) or ((p.getOrElse(1) { 0 } and 255) shl 8) or (p.getOrElse(2) { 0 } and 255)
            }
            line.startsWith("Line:") -> {
                percent = percentPattern.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                inLine = true
                points.clear()
            }
            line.startsWith("End:") -> {
                if (inLine && percent > 0 && points.size >= 4 && frames.isNotEmpty()) frames.last().second += LightningContour(percent, color, points.toDoubleArray())
                inLine = false
            }
            inLine && line.isNotEmpty() && (line[0].isDigit() || line[0] == '-') -> {
                val p = line.split(',')
                if (p.size == 2) {
                    val lat = p[0].trim().toDoubleOrNull(); val lon = p[1].trim().toDoubleOrNull()
                    if (lat != null && lon != null) { points += lat; points += lon }
                }
            }
        }
    }
    return frames.map { LightningFrame(it.first, it.second) }.sortedBy { it.start }
}

/** The scan to show for [seconds]: the newest scan that began at or before it (the first one if none did), or the newest when [seconds] is null. */
fun lightningFrameFor(frames: List<LightningFrame>, seconds: Long?): LightningFrame? {
    if (frames.isEmpty()) return null
    if (seconds == null) return frames.last()
    return frames.lastOrNull { it.start <= seconds } ?: frames.first()
}

/** Reads the LightningCast placefile over [http]. */
class LightningCastRepository(private val http: Http) {
    suspend fun load(): List<LightningFrame> {
        val result = http.get(LightningCast.FEED)
        if (result.status != 200) throw RefsException("the LightningCast feed could not be read (HTTP ${result.status})")
        return parseLightningCast(result.body.decodeToString()).also { if (it.isEmpty()) throw RefsException("the LightningCast feed has no scans") }
    }
}
