package simplemodelview

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
data class OutlookChoice(val day: Int, val type: OutlookType) {
    /** The name of the file in SPC's public GIS data (`https://www.spc.noaa.gov/products/outlook/<name>.lyr.geojson`). */
    val fileName: String
        get() = when {
            day >= 4 -> "day${day}prob"
            type == OutlookType.Probability -> "day${day}otlk_prob"
            else -> "day${day}otlk_" + when (type) {
                OutlookType.Categorical -> "cat"; OutlookType.Tornado -> "torn"; OutlookType.Wind -> "wind"; else -> "hail"
            }
        }

    val url: String get() = "$BASE/$fileName.lyr.geojson"

    /** The key it is saved under. */
    val id: String get() = "$day:${type.name}"

    /** "Day 1 tornado" */
    val label: String get() = if (day >= 4) "Day $day severe chance" else "Day $day ${type.label.replaceFirstChar { it.lowercase() }}"

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
    val hatched: Boolean = false,        // the "significant severe" hatched area: drawn dashed, not filled
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
            val hatched = label.equals("SIGN", ignoreCase = true)
            val catRank = OutlookColors.categoryRank(label)
            // probabilities are given as a fraction ("0.05") or a percent; DN is the percent in SPC's files
            val percent = dn ?: label.removeSuffix("%").toDoubleOrNull()?.let { if (it <= 1.0) it * 100 else it }?.toInt()
            val rank = when {
                hatched -> 1000
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
            shapes += OutlookShape(title, rank, if (hatched) 0x000000 else color, polygons, hatched)
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
        val result = http.get(choice.url)
        when (result.status) {
            200 -> {}
            404 -> throw RefsException("${choice.label} has not been issued")
            else -> throw RefsException("the SPC outlook could not be read (HTTP ${result.status})")
        }
        return parseOutlookGeoJson(result.body.decodeToString())
    }
}
