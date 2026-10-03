package simplemodelview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The three kinds of alert layers a map can show. */
enum class AlertKind(val label: String) { Warning("Warnings"), Watch("Watches"), Discussion("Discussions") }

/**
 * One outline to draw and tap: a warning, a watch or a discussion area. [rings] are closed outlines, each as lat0, lon0, lat1, lon1, ... (west-negative
 * longitudes). [text] is the full text when it is already known (a discussion); for a warning it is fetched from [detailUrl] when asked for.
 */
class AlertShape(
    val kind: AlertKind,
    val title: String,
    val color: Int,                 // 0xRRGGBB
    val rings: List<DoubleArray>,
    val issued: Long?,              // epoch seconds
    val expires: Long?,
    val summary: String,
    val detailUrl: String? = null,
    val text: String? = null,
    val dashed: Boolean = false,
)

/** The products drawn from the National Weather Service's watch / warning service, with the colors the NWS map uses. */
object AlertTypes {
    val warnings = mapOf(
        "Tornado Warning" to 0xFF0000, "Severe Thunderstorm Warning" to 0xFFA500, "Flash Flood Warning" to 0x8B0000,
        "Extreme Wind Warning" to 0xFF8C00, "Special Marine Warning" to 0xFFA500, "Snow Squall Warning" to 0xC71585,
    )
    val watches = mapOf("Tornado Watch" to 0xFFFF00, "Severe Thunderstorm Watch" to 0xDB7093, "Flash Flood Watch" to 0x2E8B57)
    const val SPC_DISCUSSION = 0x00A850
    const val WPC_DISCUSSION = 0x1E90FF

    fun kindOf(productType: String): AlertKind? = when (productType) { in warnings -> AlertKind.Warning; in watches -> AlertKind.Watch; else -> null }
    fun colorOf(productType: String): Int = warnings[productType] ?: watches[productType] ?: 0x808080
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** "2026-10-02T20:17:00-04:00" (any UTC offset, or Z) -> epoch seconds, or null. */
fun parseIsoWithOffset(text: String): Long? {
    if (text.length < 19) return null
    return try {
        val local = epochOf(text.substring(0, 4).toInt(), text.substring(5, 7).toInt(), text.substring(8, 10).toInt(),
            text.substring(11, 13).toInt(), text.substring(14, 16).toInt(), text.substring(17, 19).toInt())
        val tail = text.substring(19).substringAfter('.', "").dropWhile { it.isDigit() }.ifEmpty { text.substring(19).dropWhile { it == '.' || it.isDigit() } }
        val offset = if (tail.length >= 6 && (tail[0] == '+' || tail[0] == '-')) {
            val sign = if (tail[0] == '-') -1 else 1
            sign * (tail.substring(1, 3).toInt() * 3600 + tail.substring(4, 6).toInt() * 60)
        } else 0
        local - offset
    } catch (e: NumberFormatException) { null }
}

/** The part of a GeoJSON geometry that is outline: every ring of a Polygon / MultiPolygon, as lat, lon pairs. */
private fun ringsOf(geometry: JsonObject?): List<DoubleArray> {
    if (geometry == null) return emptyList()
    val type = geometry["type"]?.jsonPrimitive?.contentOrNull
    val polygons: List<JsonArray> = when (type) {
        "Polygon" -> listOf(geometry["coordinates"]?.jsonArray ?: return emptyList())
        "MultiPolygon" -> (geometry["coordinates"]?.jsonArray ?: return emptyList()).map { it.jsonArray }
        else -> return emptyList()
    }
    val rings = ArrayList<DoubleArray>()
    for (polygon in polygons) for (ring in polygon) {
        val points = ring.jsonArray
        val out = DoubleArray(points.size * 2)
        var ok = true
        points.forEachIndexed { i, p ->
            val pair = p.jsonArray
            val lon = pair[0].jsonPrimitive.doubleOrNull; val lat = pair[1].jsonPrimitive.doubleOrNull
            if (lon == null || lat == null) ok = false else { out[2 * i] = lat; out[2 * i + 1] = lon }
        }
        if (ok && points.size >= 3) rings += out
    }
    return rings
}

/**
 * The warnings and watches of the NWS service's GeoJSON reply (one feature per polygon, with prod_type, issuance, expiration and url). Products that are
 * not drawn (advisories, statements, ...) and expired ones are dropped. Damaged text gives an empty list, never an exception.
 */
fun parseWatchWarnGeoJson(text: String, nowEpochSeconds: Long): List<AlertShape> {
    val features = try { json.parseToJsonElement(text).jsonObject["features"]?.jsonArray } catch (e: Exception) { null } ?: return emptyList()
    val shapes = ArrayList<AlertShape>()
    for (feature in features) {
        try {
            val obj = feature.jsonObject
            val props = obj["properties"]?.jsonObject ?: continue
            val type = props["prod_type"]?.jsonPrimitive?.contentOrNull ?: continue
            val kind = AlertTypes.kindOf(type) ?: continue
            val expires = props["expiration"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoWithOffset)
            if (expires != null && expires < nowEpochSeconds) continue
            val rings = ringsOf(obj["geometry"] as? JsonObject)
            if (rings.isEmpty()) continue
            val issued = props["issuance"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoWithOffset)
            shapes += AlertShape(
                kind, type, AlertTypes.colorOf(type), rings, issued, expires,
                summary = listOfNotNull(issued?.let { "issued ${formatClock(it, nowEpochSeconds)}" }, expires?.let { "until ${formatClock(it, nowEpochSeconds)}" }).joinToString(", "),
                detailUrl = props["url"]?.jsonPrimitive?.contentOrNull,
            )
        } catch (e: Exception) { /* one damaged feature does not hide the others */ }
    }
    return shapes
}

/** The headline, description and instruction of an NWS alert (its JSON as the alerts API returns it); null when there is none. */
fun parseAlertDetail(text: String): String? = try {
    val props = json.parseToJsonElement(text).jsonObject["properties"]?.jsonObject
    listOfNotNull("headline", "description", "instruction").mapNotNull { props?.get(it)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .joinToString("\n\n").ifEmpty { null }
} catch (e: Exception) { null }

// ---- SPC mesoscale discussions and WPC mesoscale precipitation discussions: text pages ----

/** The text of the first <pre> block of a page, with the HTML entities undone. */
fun preText(html: String): String? {
    val m = Regex("<pre[^>]*>(.*?)</pre>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html) ?: return null
    return m.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("<[^>]+>"), "").trim()
}

/**
 * The outline in a discussion's "LAT...LON" block: pairs of four-digit numbers (hundredths of a degree of latitude, then of west longitude, with 100 added
 * back to a longitude under 50 so 0194 is 101.94 W). The ring is closed by the caller's drawing. Empty when there is no block.
 */
fun parseLatLon(text: String): List<DoubleArray> {
    val at = text.indexOf("LAT...LON")
    if (at < 0) return emptyList()
    val values = ArrayList<Double>()
    for (token in Regex("""\S+""").findAll(text.substring(at + 9))) {
        val t = token.value
        if (t.length != 8 || !t.all { it.isDigit() }) break
        val lon = t.substring(4).toInt() / 100.0
        values += t.substring(0, 4).toInt() / 100.0
        values += -(if (lon < 50.0) lon + 100.0 else lon)
    }
    return if (values.size >= 6) listOf(values.toDoubleArray()) else emptyList()
}

/**
 * "Valid 021900Z - 030100Z" (day, hour, minute) as epoch seconds, resolved against [now]: the month is the one that puts the start closest to now.
 * Returns null when the text has no validity.
 */
fun parseValidRange(text: String, nowEpochSeconds: Long): Pair<Long, Long>? {
    val m = Regex("""Valid\s+(\d{6})Z\s*-\s*(\d{6})Z""").find(text) ?: return null
    val now = civilFromEpoch(nowEpochSeconds)
    fun at(token: String, year: Int, month: Int): Long? {
        val day = token.substring(0, 2).toInt()
        if (day < 1 || day > Cycle.daysIn(year, month)) return null
        return epochOf(year, month, day, token.substring(2, 4).toInt(), token.substring(4, 6).toInt(), 0)
    }
    val candidates = listOf(-1, 0, 1).mapNotNull { shift ->
        var month = now[1] + shift; var year = now[0]
        if (month < 1) { month = 12; year -= 1 }
        if (month > 12) { month = 1; year += 1 }
        at(m.groupValues[1], year, month)
    }
    val start = candidates.minByOrNull { kotlin.math.abs(it - nowEpochSeconds) } ?: return null
    val startCivil = civilFromEpoch(start)
    val endCandidates = listOf(0, 1).mapNotNull { shift ->
        var month = startCivil[1] + shift; var year = startCivil[0]
        if (month > 12) { month = 1; year += 1 }
        at(m.groupValues[2], year, month)
    }.filter { it >= start }
    val end = endCandidates.minOrNull() ?: return null
    return start to end
}

/** One discussion page as an outline. [spc]: an SPC mesoscale discussion, otherwise a WPC precipitation discussion. Null without an outline or a validity. */
fun parseDiscussionPage(html: String, number: String, spc: Boolean, nowEpochSeconds: Long): AlertShape? {
    val text = preText(html) ?: return null
    val (start, end) = parseValidRange(text, nowEpochSeconds) ?: return null
    val rings = parseLatLon(text)
    if (rings.isEmpty()) return null
    val areas = Regex("""Areas affected\.\.\.(.*?)\n\s*\n""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    val concerning = Regex("""Concerning\.\.\.([^\n]*)""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
    return AlertShape(
        AlertKind.Discussion, (if (spc) "SPC MD " else "WPC MPD ") + number, if (spc) AlertTypes.SPC_DISCUSSION else AlertTypes.WPC_DISCUSSION, rings, start, end,
        summary = listOf(concerning, areas).filter { it.isNotEmpty() }.joinToString(": "), text = text, dashed = true,
    )
}

/** The numbers of the SPC discussions an index page links ("md2345.html"), newest first. */
fun spcDiscussionNumbers(indexHtml: String): List<String> = Regex("""md(\d{4})\.html""").findAll(indexHtml).map { it.groupValues[1] }.toSet().sortedDescending()

/** The (number, year) of the WPC discussions its index page links ("metwatch_mpd_multi.php?md=1429&yr=2026"), newest first. */
fun wpcDiscussionLinks(indexHtml: String): List<Pair<String, String>> =
    Regex("""metwatch_mpd_multi\.php\?md=(\d+)&(?:amp;)?yr=(\d+)""").findAll(indexHtml).map { it.groupValues[1] to it.groupValues[2] }.toSet().sortedByDescending { it.first.toIntOrNull() ?: 0 }

// ---- hit testing ----

/** True when the point is inside the ring (lat0, lon0, lat1, lon1, ...), by the even-odd rule on a flat latitude / longitude plane (fine at these sizes). */
fun pointInRing(lat: Double, lon: Double, ring: DoubleArray): Boolean {
    var inside = false
    val n = ring.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val yi = ring[2 * i]; val xi = ring[2 * i + 1]; val yj = ring[2 * j]; val xj = ring[2 * j + 1]
        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** The shapes whose outline contains the point, warnings first, then watches, then discussions (smaller areas first within a kind is left to the caller). */
fun shapesAt(shapes: List<AlertShape>, lat: Double, lon: Double): List<AlertShape> =
    shapes.filter { s -> s.rings.any { pointInRing(lat, lon, it) } }.sortedBy { it.kind.ordinal }

/** Percent-encodes a query-string component (UTF-8). */
fun urlEncode(text: String): String = buildString {
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) append(c.toChar())
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

/** Reads the watches, warnings and discussions from NOAA's public services over [http]. Never holds state. */
class AlertsRepository(private val http: Http) {
    /** Tornado / severe thunderstorm / flash flood / ... warnings and the convective and flash flood watches in force at [nowEpochSeconds]. */
    suspend fun warningsAndWatches(nowEpochSeconds: Long): List<AlertShape> {
        val types = (AlertTypes.warnings.keys + AlertTypes.watches.keys).joinToString(",") { "'$it'" }
        val url = "$SERVICE/1/query?where=${urlEncode("prod_type IN ($types)")}&outFields=${urlEncode("prod_type,issuance,expiration,url")}&outSR=4326&f=geojson"
        val result = http.get(url)
        if (result.status != 200) throw RefsException("could not read the watches and warnings (HTTP ${result.status})")
        return parseWatchWarnGeoJson(result.body.decodeToString(), nowEpochSeconds)
    }

    /**
     * The active SPC mesoscale discussions and WPC mesoscale precipitation discussions: each index page names the recent ones, each is read and kept
     * when its validity covers now. A source that cannot be read is skipped (the other still shows).
     */
    suspend fun discussions(nowEpochSeconds: Long): List<AlertShape> {
        val found = ArrayList<AlertShape>()
        val spcIndex = try { http.get("https://www.spc.noaa.gov/products/md/") } catch (e: Exception) { null }
        if (spcIndex != null && spcIndex.status == 200) {
            for (number in spcDiscussionNumbers(spcIndex.body.decodeToString()).take(6)) {
                val page = try { http.get("https://www.spc.noaa.gov/products/md/md$number.html") } catch (e: Exception) { null } ?: continue
                if (page.status == 200) parseDiscussionPage(page.body.decodeToString(), number, spc = true, nowEpochSeconds = nowEpochSeconds)?.takeIf { active(it, nowEpochSeconds) }?.let { found += it }
            }
        }
        val wpcIndex = try { http.get("https://www.wpc.ncep.noaa.gov/metwatch/metwatch_mpd.php") } catch (e: Exception) { null }
        if (wpcIndex != null && wpcIndex.status == 200) {
            for ((number, year) in wpcDiscussionLinks(wpcIndex.body.decodeToString()).take(6)) {
                val page = try { http.get("https://www.wpc.ncep.noaa.gov/metwatch/metwatch_mpd_multi.php?md=$number&yr=$year") } catch (e: Exception) { null } ?: continue
                if (page.status == 200) parseDiscussionPage(page.body.decodeToString(), number, spc = false, nowEpochSeconds = nowEpochSeconds)?.takeIf { active(it, nowEpochSeconds) }?.let { found += it }
            }
        }
        return found
    }

    private fun active(shape: AlertShape, now: Long) = (shape.expires ?: Long.MAX_VALUE) >= now - 300 && (shape.issued ?: 0L) <= now + 3600

    /** The full text of a shape: a discussion's own text, or a warning's headline / description / instruction fetched from the alerts API. Null when there is none. */
    suspend fun text(shape: AlertShape): String? {
        shape.text?.let { return it }
        val url = shape.detailUrl ?: return null
        val result = try { http.get(url) } catch (e: Exception) { return null }
        return if (result.status == 200) parseAlertDetail(result.body.decodeToString()) else null
    }

    private companion object {
        const val SERVICE = "https://mapservices.weather.noaa.gov/eventdriven/rest/services/WWA/watch_warn_adv/MapServer"
    }
}
