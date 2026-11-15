package simplemodelview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** One value of a gridded element: valid from [start] up to [end] (epoch seconds), in the unit the element came in. */
class NwsInterval(val start: Long, val end: Long, val value: Double)

/**
 * The NWS's gridded forecast of a point (`/gridpoints/<office>/<x>,<y>`): each element as a list of intervals (the API run-length encodes it: a value
 * stands until the next, from one hour to a day). Units are the API's: temperatures in degC, wind in km/h, direction in degrees, sky cover in percent.
 */
class NwsGridData(val updated: Long, val elements: Map<String, List<NwsInterval>>) {
    /** The value in force at [t], or null when the element has none then. */
    fun valueAt(element: String, t: Long): Double? = elements[element]?.firstOrNull { t >= it.start && t < it.end }?.value
}

/** "PT1H" -> 3600, "P1DT6H" -> 108000, "PT30M" -> 1800 (seconds); null for text that is not a duration. */
fun parseIsoDuration(text: String): Long? {
    val m = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").find(text) ?: return null
    if (text == "P" || text.endsWith("T")) return null
    fun n(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
    return n(1) * 86400 + n(2) * 3600 + n(3) * 60 + n(4)
}

/** The reply of a gridpoints link, or null when it is not one. Times in the reply are UTC ("2026-11-12T00:00:00+00:00/PT1H"). */
fun parseNwsGrid(text: String): NwsGridData? = try {
    val p = Json.parseToJsonElement(text).jsonObject["properties"]?.jsonObject ?: return null
    val updated = p["updateTime"]?.jsonPrimitive?.content?.let { isoToEpoch(it) } ?: 0L
    val elements = LinkedHashMap<String, List<NwsInterval>>()
    for ((name, element) in p) {
        val values = (element as? JsonObject)?.get("values")?.jsonArray ?: continue
        val list = values.mapNotNull { v ->
            val o = v.jsonObject
            val value = (o["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null      // (weather and hazards carry lists, not numbers)
            val (from, length) = o["validTime"]?.jsonPrimitive?.content?.split('/')?.takeIf { it.size == 2 } ?: return@mapNotNull null
            val start = isoToEpoch(from) ?: return@mapNotNull null
            NwsInterval(start, start + (parseIsoDuration(length) ?: return@mapNotNull null), value)
        }
        if (list.isNotEmpty()) elements[name] = list.sortedBy { it.start }
    }
    NwsGridData(updated, elements)
} catch (e: Exception) { null }

/**
 * The hours the bulletins do not cover, from the gridded forecast: the instants from [fromEpoch] (up to the next 3-hour UTC step) up to [untilEpoch], every
 * three hours, as a table in the bulletins' own codes and units (TMP, DPT in F, SKY in %, WDR in tens of degrees, WSP and GST in knots) so it sits in front of the
 * short-range bulletin's columns. Kind "API". There are no spreads, chances or amounts here: the first bulletin column carries those for the hours before it.
 * Null when there is no column to fill.
 */
fun nwsGapTable(grid: NwsGridData, fromEpoch: Long, untilEpoch: Long, station: String = ""): NbmTextTable? {
    val step = 3 * 3600L
    val times = generateSequence(((fromEpoch + step - 1) / step) * step) { it + step }.takeWhile { it < untilEpoch }.toList()
    if (times.isEmpty()) return null
    fun f(c: Double) = (c * 9 / 5 + 32).roundToInt()
    fun kt(kmh: Double) = (kmh * 0.539957).roundToInt()
    fun column(element: String, convert: (Double) -> Int) = times.map { t -> grid.valueAt(element, t)?.let(convert) }
    val rows = linkedMapOf(
        NbmTextElements.TEMP to column("temperature", ::f),
        NbmTextElements.DEW to column("dewpoint", ::f),
        NbmTextElements.SKY to column("skyCover") { it.roundToInt() },
        NbmTextElements.WIND_DIR to column("windDirection") { d -> ((d + 5) / 10).toInt().let { if (it == 0) 36 else it } },
        NbmTextElements.WIND to column("windSpeed", ::kt),
        NbmTextElements.GUST to column("windGust", ::kt),
    )
    return NbmTextTable(station, "API", times.first(), times.map { ((it - times.first()) / 3600).toInt() }, rows)
}
