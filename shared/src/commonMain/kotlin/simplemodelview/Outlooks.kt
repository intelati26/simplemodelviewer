package simplemodelview

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What an SPC outlook map shows: the categorical risk, or the chance of one hazard (days 1 and 2), or the total severe chance (day 3 and days 4-8). */
enum class OutlookType(val label: String) {
    Categorical("Categorical risk"), Tornado("Tornado"), Wind("Damaging wind"), Hail("Hail"), Probability("Severe chance"),
}

/** One outlook of the Storm Prediction Center that can be drawn over a map: a [day] (1-8) and what it shows. */
data class OutlookChoice(val day: Int, val type: OutlookType, val archive: List<ArchivedOutlook> = emptyList()) {
    /** The name of the file in SPC's public GIS data (`https://www.spc.noaa.gov/products/outlook/<name>.lyr.geojson`). */
    val fileName: String
        get() = when {
            day >= 4 -> "day${day}prob"
            type == OutlookType.Probability -> "day${day}otlk_prob"
            else -> "day${day}otlk_" + when (type) {
                OutlookType.Categorical -> "cat"; OutlookType.Tornado -> "torn"; OutlookType.Wind -> "wind"; else -> "hail"
            }
        }

    /** The file to read: SPC's current file, or for an [archive] choice the first archived outlook (see [OutlookArchive]). */
    val url: String get() = archive.firstOrNull()?.let { OutlookArchive.url(it) } ?: "$BASE/$fileName.lyr.geojson"

    /** The key it is saved under (an archived outlook by its date and cycle). */
    val id: String get() = "$day:${type.name}" + (archive.firstOrNull()?.let { "@${it.validDate}/${it.cycle}" } ?: "")

    /** "Day 1 tornado" (an archived one says when it was issued) */
    val label: String get() = (if (day >= 4) "Day $day severe chance" else "Day $day ${type.label.replaceFirstChar { it.lowercase() }}") +
        (archive.firstOrNull()?.let { " (issued ${formatUtc(it.issued)})" } ?: "")

    companion object {
        const val BASE = "https://www.spc.noaa.gov/products/outlook"

        /** Every outlook the app offers, in menu order. */
        val all: List<OutlookChoice> = buildList {
            for (day in 1..2) for (t in listOf(OutlookType.Categorical, OutlookType.Tornado, OutlookType.Wind, OutlookType.Hail)) add(OutlookChoice(day, t))
            add(OutlookChoice(3, OutlookType.Categorical))
            add(OutlookChoice(3, OutlookType.Probability))
            for (day in 4..8) add(OutlookChoice(day, OutlookType.Probability))
        }

        fun byId(id: String?): OutlookChoice? = all.firstOrNull { it.id == id }
    }
}

/** One area of an outlook: a risk level or a probability, as polygons (each: the outer ring, then its holes; every ring lat0, lon0, lat1, lon1, ...). */
class OutlookShape(
    val title: String,
    val rank: Int,                       // higher = more severe; the more severe areas are drawn (and win a tap) on top
    val color: Int,                      // 0xRRGGBB
    val polygons: List<List<DoubleArray>>,
    val hatched: Boolean = false,        // the "significant severe" / intensity area: drawn dashed, in black hatching instead of a color
    val intensity: Int = 1,              // hatched areas: 1 "/" , 2 "\\" , 3 both (SPC's tornado intensity groups CIG1 to CIG3)
)

/** A loaded outlook: its areas and times (epoch seconds, any may be unknown). */
class OutlookData(val shapes: List<OutlookShape>, val issued: Long?, val valid: Long?, val expires: Long?)

/**
 * The 12Z that starts the convective day containing [epoch] (the most recent 12Z at or before it). SPC's outlooks cover convective days, 12Z to 12Z: Day 1 is the
 * one that contains "now", Day 2 the next, and so on to Day 8.
 */
fun convectiveDayStart(epoch: Long): Long = floorDiv(epoch - 12 * 3600L, 86400L) * 86400L + 12 * 3600L

/**
 * The outlook that covers a frame valid at [valid] when [now] is the current time, for the kind of map the user chose ([type]): the day is the convective day
 * of [valid] counted from today's, and the file is the kind's own where SPC issues one. The hazard maps (tornado, wind, hail) exist for Days 1 and 2 only, and
 * the categorical map for Days 1 to 3; every later day, and the hazards on Day 3, show the total severe chance. Null when no outlook covers the time (earlier
 * than today's convective day, or past Day 8).
 */
fun outlookChoiceFor(type: OutlookType, valid: Long, now: Long): OutlookChoice? {
    val day = ((convectiveDayStart(valid) - convectiveDayStart(now)) / 86400L).toInt() + 1
    if (day !in 1..8) return null
    val actual = when {
        day >= 4 -> OutlookType.Probability
        day == 3 -> if (type == OutlookType.Categorical) OutlookType.Categorical else OutlookType.Probability
        type == OutlookType.Probability -> OutlookType.Categorical
        else -> type
    }
    return OutlookChoice(day, actual)
}

object OutlookColors {
    private val categories = listOf("TSTM", "MRGL", "SLGT", "ENH", "MDT", "HIGH")

    /** The categorical risk names, lowest first. */
    fun categoryRank(label: String): Int = categories.indexOf(label.uppercase()).let { if (it < 0) -1 else it + 1 }

    /** The colors of wxqt's day 1 map (and SPC's line colors): general thunder is left to the data's own color. */
    val category = mapOf("MRGL" to 0x006400, "SLGT" to 0xFFFF00, "ENH" to 0xFF8C00, "MDT" to 0xFF0000, "HIGH" to 0xFF00FF, "TSTM" to 0x55BB55)

    /** A color for a probability level (percent) when the data gives none: SPC's probability line colors, light green to purple. */
    fun probability(percent: Int): Int = when {
        percent < 5 -> 0x008B00
        percent < 10 -> 0x8B4726
        percent < 15 -> 0xFFC800
        percent < 30 -> 0xFF0000
        percent < 45 -> 0xFF00FF
        percent < 60 -> 0x912CEE
        else -> 0x104E8B
    }
}

private val outlookJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** "202610301200" (yyyyMMddHHmm, UTC) -> epoch seconds, or null. */
internal fun outlookTime(text: String?): Long? {
    if (text == null || text.length != 12 || !text.all { it.isDigit() }) return null
    return epochOf(text.substring(0, 4).toInt(), text.substring(4, 6).toInt(), text.substring(6, 8).toInt(), text.substring(8, 10).toInt(), text.substring(10, 12).toInt(), 0)
}

/** "#FF8C00" -> 0xFF8C00, or null. */
internal fun hexColor(text: String?): Int? = text?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)

private fun polygonsOf(geometry: JsonObject?): List<List<DoubleArray>> {
    if (geometry == null) return emptyList()
    val polygons: List<JsonArray> = when (geometry["type"]?.jsonPrimitive?.contentOrNull) {
        "Polygon" -> listOf(geometry["coordinates"]?.jsonArray ?: return emptyList())
        "MultiPolygon" -> (geometry["coordinates"]?.jsonArray ?: return emptyList()).map { it.jsonArray }
        else -> return emptyList()
    }
    val out = ArrayList<List<DoubleArray>>()
    for (polygon in polygons) {
        val rings = ArrayList<DoubleArray>()
        for (ring in polygon) {
            val points = ring.jsonArray
            val values = DoubleArray(points.size * 2)
            var ok = points.size >= 3
            points.forEachIndexed { i, p ->
                val pair = p.jsonArray
                val lon = pair[0].jsonPrimitive.doubleOrNull; val lat = pair[1].jsonPrimitive.doubleOrNull
                if (lon == null || lat == null) ok = false else { values[2 * i] = lat; values[2 * i + 1] = lon }
            }
            if (ok) rings += values
        }
        if (rings.isNotEmpty()) out += rings
    }
    return out
}

/**
 * The areas of an SPC outlook GeoJSON file (`dayNotlk_cat.lyr.geojson` and its relatives): one feature per risk level or probability, with DN, LABEL,
 * LABEL2, VALID / EXPIRE / ISSUE (yyyyMMddHHmm) and the stroke color SPC draws it in. Features that are not polygons (label points) are skipped; damaged text
 * gives no areas, never an exception. Shapes come back lowest level first.
 */
fun parseOutlookGeoJson(text: String): OutlookData {
    val features = try { outlookJson.parseToJsonElement(text).jsonObject["features"]?.jsonArray } catch (e: Exception) { null }
        ?: return OutlookData(emptyList(), null, null, null)
    val shapes = ArrayList<OutlookShape>()
    var issued: Long? = null; var valid: Long? = null; var expires: Long? = null
    for (feature in features) {
        try {
            val obj = feature.jsonObject
            val props = obj["properties"]?.jsonObject ?: continue
            val polygons = polygonsOf(obj["geometry"] as? JsonObject)
            if (polygons.isEmpty()) continue
            fun str(key: String) = props[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val label = str("LABEL") ?: ""
            val label2 = str("LABEL2")
            val dn = str("DN")?.toDoubleOrNull()?.toInt()
            issued = issued ?: outlookTime(str("ISSUE")); valid = valid ?: outlookTime(str("VALID")); expires = expires ?: outlookTime(str("EXPIRE"))
            val hatched = label.equals("SIGN", ignoreCase = true) || label.startsWith("CIG", ignoreCase = true)   // significant severe / conditional intensity group
            val intensity = if (label.startsWith("CIG", ignoreCase = true)) label.last().digitToIntOrNull()?.coerceIn(1, 3) ?: 1 else 1
            val catRank = OutlookColors.categoryRank(label)
            // probabilities are given as a fraction ("0.05") or a percent; DN is the percent in SPC's files
            val percent = dn ?: label.removeSuffix("%").toDoubleOrNull()?.let { if (it <= 1.0) it * 100 else it }?.toInt()
            val rank = when {
                hatched -> 1000 + intensity
                catRank > 0 -> catRank
                percent != null -> percent
                else -> 0
            }
            val title = label2 ?: when {
                catRank > 0 -> label
                percent != null -> "$percent%"
                else -> label.ifEmpty { "Outlook area" }
            }
            val color = hexColor(str("stroke")) ?: OutlookColors.category[label.uppercase()] ?: if (percent != null) OutlookColors.probability(percent) else 0x808080
            shapes += OutlookShape(title, rank, if (hatched) 0x000000 else color, polygons, hatched, intensity)
        } catch (e: Exception) { /* one damaged feature does not hide the others */ }
    }
    return OutlookData(shapes.sortedBy { it.rank }, issued, valid, expires)
}

/** The most severe area of [shapes] that contains the point (outer ring in, no hole around it), or null. The hatched significant area is not a level and is passed over. */
fun outlookAt(shapes: List<OutlookShape>, lat: Double, lon: Double): OutlookShape? =
    shapes.filter { !it.hatched && it.polygons.any { rings -> pointInRing(lat, lon, rings[0]) && rings.drop(1).none { hole -> pointInRing(lat, lon, hole) } } }
        .maxByOrNull { it.rank }

/** "Slight Risk" -> "Day 1 categorical risk: Slight Risk", for the tap read-out. */
fun outlookReadout(choice: OutlookChoice, shape: OutlookShape?): String = "SPC ${choice.label}: " + (shape?.title ?: "no risk area here")

/** Loads SPC outlooks from SPC's public GIS data over [http]. */
class OutlookRepository(private val http: Http) {
    /** The outlook of [choice]. A 404 (not issued yet) throws; a file with no areas gives an empty list. */
    suspend fun load(choice: OutlookChoice): OutlookData {
        if (choice.archive.isNotEmpty()) return loadArchived(choice)
        val result = http.get(choice.url)
        when (result.status) {
            200 -> {}
            404 -> throw RefsException("${choice.label} has not been issued")
            else -> throw RefsException("the SPC outlook could not be read (HTTP ${result.status})")
        }
        return parseOutlookGeoJson(result.body.decodeToString())
    }

    /** An archived outlook: the first of the choice's candidates the archive holds (a few at a time, so a loop does not open dozens of connections). */
    private suspend fun loadArchived(choice: OutlookChoice): OutlookData = archiveGate.withPermit {
        var failure: Exception? = null
        for (a in choice.archive) {
            try {
                val result = http.get(OutlookArchive.url(a))
                if (result.status != 200) { failure = RefsException("the archived outlook could not be read (HTTP ${result.status})"); continue }
                parseArchivedOutlook(result.body.decodeToString(), a.type)?.let { return@withPermit it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failure = e }
        }
        throw failure ?: RefsException("no outlook was archived for that time")
    }
}


/** An outlook kept in the Iowa Environmental Mesonet's archive of SPC outlooks: the convective day it is valid for (its first calendar date), the issuance cycle (UTC hour), and what it is. */
data class ArchivedOutlook(val validDate: String, val cycle: Int, val day: Int, val type: OutlookType, val issued: Long)

/**
 * Which archived outlook was in place at a time. The archive (`https://mesonet.agron.iastate.edu/api/1/nws/spc_outlook.geojson?day=&valid=&cycle=`) has every
 * outlook back to the 2010s, one file per day, date and cycle holding every map of it (categorical, tornado, wind, hail, the total severe chance).
 */
object OutlookArchive {
    const val BASE = "https://mesonet.agron.iastate.edu/api/1/nws/spc_outlook.geojson"

    /** The cycles (UTC hours) of the issuances of each day's outlook. Day 1 is also updated at 01Z, which already belongs to the next calendar date. */
    private val cycles: Map<Int, List<Int>> = mapOf(1 to listOf(1, 6, 13, 16, 20), 2 to listOf(7, 17), 3 to listOf(8, 20)) + (4..8).associateWith { listOf(10) }

    /** The archive is not limited here: a date it does not hold simply has no outlook. */
    fun url(a: ArchivedOutlook) = "$BASE?day=${a.day}&valid=${a.validDate}&cycle=${a.cycle}"

    /** When the outlook of [day] for the convective day starting at [dayStart] (a 12Z) was issued at [cycle] (the hour), in epoch seconds. */
    fun issueTime(day: Int, dayStart: Long, cycle: Int): Long {
        val midnight = dayStart - 12 * 3600L            // 00Z of the date the convective day starts on
        val date = when {
            day == 1 -> if (cycle < 6) midnight + 86400L else midnight       // the 01Z update comes after midnight
            else -> midnight - (day - 1) * 86400L                              // a later day's outlook is issued the days before
        }
        return date + cycle * 3600L
    }

    private fun dateOf(dayStart: Long): String {
        val c = civilFromEpoch(dayStart)
        return "${c[0]}-${c[1].toString().padStart(2, '0')}-${c[2].toString().padStart(2, '0')}"
    }

    /**
     * The outlooks for the convective day of [valid] that existed at [asOf], the newest issuance first (at most [limit]; the first is the one that was in place,
     * the others are what to try when the archive lacks it). The map shown for each follows the same rules as [outlookChoiceFor].
     */
    fun candidates(type: OutlookType, valid: Long, asOf: Long, limit: Int = 4): List<ArchivedOutlook> {
        val dayStart = convectiveDayStart(valid)
        val date = dateOf(dayStart)
        val out = ArrayList<ArchivedOutlook>()
        for (day in 1..8) for (cycle in cycles.getValue(day)) {
            val issued = issueTime(day, dayStart, cycle)
            if (issued > asOf) continue
            val actual = when {
                day >= 4 -> OutlookType.Probability
                day == 3 -> if (type == OutlookType.Categorical) OutlookType.Categorical else OutlookType.Probability
                type == OutlookType.Probability -> OutlookType.Categorical
                else -> type
            }
            out += ArchivedOutlook(date, cycle, day, actual, issued)
        }
        return out.sortedByDescending { it.issued }.take(limit)
    }
}

/** The outlook of [type] for a frame valid at [valid] as it stood at [asOf] (the run's start, or the frame's own time), from the archive; null when none had been issued. */
fun outlookArchiveFor(type: OutlookType, valid: Long, asOf: Long): OutlookChoice? {
    val list = OutlookArchive.candidates(type, valid, asOf)
    val first = list.firstOrNull() ?: return null
    return OutlookChoice(first.day, first.type, list)
}

private fun archiveTitle(threshold: String): String = when (threshold.uppercase()) {
    "TSTM" -> "General Thunderstorms"; "MRGL" -> "Marginal Risk"; "SLGT" -> "Slight Risk"; "ENH" -> "Enhanced Risk"; "MDT" -> "Moderate Risk"; "HIGH" -> "High Risk"
    else -> threshold
}

/**
 * The areas of one map ([type]) of an archived outlook file: the features of its category, the categorical risks or the probabilities (given as fractions, "0.05"),
 * and the hatched "SIGN" / "CIG1 to 3" areas. Null when the text is not an outlook file or holds no features at all (the archive has nothing for that date and cycle);
 * an outlook that has no area of [type] gives an empty list of shapes.
 */
fun parseArchivedOutlook(text: String, type: OutlookType): OutlookData? {
    val features = try { outlookJson.parseToJsonElement(text).jsonObject["features"]?.jsonArray } catch (e: Exception) { null } ?: return null
    if (features.isEmpty()) return null
    val wanted = when (type) {
        OutlookType.Categorical -> "CATEGORICAL"; OutlookType.Tornado -> "TORNADO"; OutlookType.Wind -> "WIND"; OutlookType.Hail -> "HAIL"; OutlookType.Probability -> "ANY SEVERE"
    }
    val shapes = ArrayList<OutlookShape>()
    var issued: Long? = null; var expires: Long? = null
    for (feature in features) {
        try {
            val obj = feature.jsonObject
            val props = obj["properties"]?.jsonObject ?: continue
            fun str(key: String) = props[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            issued = issued ?: str("product_issue")?.let { isoToEpoch(it) }; expires = expires ?: str("expire")?.let { isoToEpoch(it) }
            if (!str("category").equals(wanted, ignoreCase = true)) continue
            val polygons = polygonsOf(obj["geometry"] as? JsonObject)
            if (polygons.isEmpty()) continue
            val threshold = str("threshold") ?: continue
            val hatched = threshold.equals("SIGN", ignoreCase = true) || threshold.startsWith("CIG", ignoreCase = true)
            val intensity = if (threshold.startsWith("CIG", ignoreCase = true)) threshold.last().digitToIntOrNull()?.coerceIn(1, 3) ?: 1 else 1
            val catRank = OutlookColors.categoryRank(threshold)
            val percent = threshold.toDoubleOrNull()?.let { if (it <= 1.0) (it * 100 + 0.5).toInt() else it.toInt() }
            val rank = when { hatched -> 1000 + intensity; catRank > 0 -> catRank; percent != null -> percent; else -> 0 }
            val title = when {
                hatched -> "Significant severe"
                catRank > 0 -> archiveTitle(threshold)
                percent != null -> "$percent%"
                else -> threshold
            }
            val color = OutlookColors.category[threshold.uppercase()] ?: if (percent != null) OutlookColors.probability(percent) else 0x808080
            shapes += OutlookShape(title, rank, if (hatched) 0x000000 else color, polygons, hatched, intensity)
        } catch (e: Exception) { /* one damaged feature does not hide the others */ }
    }
    return OutlookData(shapes.sortedBy { it.rank }, issued, null, expires)
}

private val archiveGate = Semaphore(4)
