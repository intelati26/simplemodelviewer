package simplemodelview

/**
 * NOAA / CIMSS ProbSevere: the storm objects of MRMS with their chances of severe weather, published as a GRLevelX placefile. Like LightningCast the app
 * does not carry the placefile's link: the user copies it from CIMSS's page and pastes it in.
 */
object ProbSevere {
    /** The page that publishes the placefile links. */
    const val INFO_PAGE = "https://cimss.ssec.wisc.edu/probsevere/"
}

/** One outline of a placefile: its color (0xRRGGBB), its points as lat0, lon0, lat1, lon1, ..., and whether the last point joins the first. */
class PlaceShape(val color: Int, val points: DoubleArray, val closed: Boolean)

/**
 * The outlines of a GRLevelX placefile: `Line:` blocks (their points, as they are) and `Polygon:` blocks (closed), each with the `Color: r g b` in force or a
 * color on its first point ("lat, lon, r, g, b"). Text, icons, `Object:` blocks (their points are pixel offsets, not places), `Triangles:` and the rest are
 * skipped, and damaged text gives fewer outlines, never an exception.
 */
fun parsePlacefileShapes(text: String): List<PlaceShape> {
    val shapes = ArrayList<PlaceShape>()
    var color = 0xFFFFFF
    var kind = ' '                        // 'L' line, 'P' polygon, 'S' a block that is skipped, ' ' none
    var depth = 0                         // nesting of skipped blocks (an Object holds its own Line / Polygon blocks)
    var points = ArrayList<Double>()
    var blockColor: Int? = null
    fun rgb(p: List<Int>) = ((p.getOrElse(0) { 0 } and 255) shl 16) or ((p.getOrElse(1) { 0 } and 255) shl 8) or (p.getOrElse(2) { 0 } and 255)
    fun finish() {
        if (kind == 'L' || kind == 'P') if (points.size >= 4) shapes += PlaceShape(blockColor ?: color, points.toDoubleArray(), kind == 'P')
        points = ArrayList(); blockColor = null; kind = ' '
    }
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith(";") || line.startsWith("//")) continue
        val head = line.substringBefore(':').trim().lowercase()
        val isKeyword = ':' in line && head.all { it.isLetter() }
        when {
            isKeyword && head == "color" -> {
                val p = line.substringAfter(':').trim().split(' ', ',').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
                if (kind == 'L' || kind == 'P') blockColor = rgb(p) else color = rgb(p)
            }
            isKeyword && (head == "object" || head == "triangles" || head == "lines") -> {
                if (kind == ' ' && depth == 0) { kind = 'S'; depth = 1 } else depth++
            }
            isKeyword && (head == "line" || head == "polygon") -> {
                if (depth > 0) depth++ else { finish(); kind = if (head == "line") 'L' else 'P' }
            }
            isKeyword && head == "end" -> {
                if (depth > 0) { depth--; if (depth == 0) kind = ' ' } else finish()
            }
            isKeyword -> { }               // Title, RefreshSeconds, Threshold, Text, Place, IconFile, Font, ...
            (kind == 'L' || kind == 'P') && depth == 0 -> {
                val f = line.split(',').map { it.trim() }
                val lat = f.getOrNull(0)?.toDoubleOrNull()
                val lon = f.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    if (points.isEmpty() && blockColor == null && f.size >= 5) blockColor = rgb(f.subList(2, 5).map { it.toIntOrNull() ?: 0 })
                    points += lat; points += lon
                }
            }
        }
    }
    finish()
    return shapes
}

/** Reads the ProbSevere placefile over [http] from the link the user pasted. */
class ProbSevereRepository(private val http: Http, private val url: () -> String?) {
    suspend fun load(): List<PlaceShape> {
        val feed = url() ?: throw RefsException("ProbSevere needs the placefile link (see the ProbSevere overlay)")
        val result = http.get(feed)
        if (result.status != 200) throw RefsException("the ProbSevere placefile could not be read (HTTP ${result.status})")
        return parsePlacefileShapes(result.body.decodeToString())
    }
}
