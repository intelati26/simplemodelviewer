package simplemodelview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An observing station near a point, as api.weather.gov lists them (nearest first). */
data class NwsStation(val id: String, val name: String, val lat: Double, val lon: Double)

/** Where api.weather.gov puts a point: the forecast office's grid and the link to the stations nearest it. */
class NwsPoint(val office: String, val gridX: Int, val gridY: Int, val stationsUrl: String, val place: String)

/** The reply of `/points/<lat>,<lon>`, or null when it is not one. */
fun parseNwsPoint(text: String): NwsPoint? = try {
    val p = Json.parseToJsonElement(text).jsonObject["properties"]?.jsonObject ?: return null
    val relative = p["relativeLocation"]?.jsonObject?.get("properties")?.jsonObject
    val place = listOfNotNull(relative?.get("city")?.jsonPrimitive?.content, relative?.get("state")?.jsonPrimitive?.content).joinToString(", ")
    NwsPoint(
        p["gridId"]!!.jsonPrimitive.content, p["gridX"]!!.jsonPrimitive.content.toInt(), p["gridY"]!!.jsonPrimitive.content.toInt(),
        p["observationStations"]!!.jsonPrimitive.content, place,
    )
} catch (e: Exception) { null }

/** The reply of the stations link: the stations in the order given (nearest first). */
fun parseNwsStations(text: String): List<NwsStation> = try {
    Json.parseToJsonElement(text).jsonObject["features"]!!.jsonArray.mapNotNull { f ->
        val o = f.jsonObject
        val p = o["properties"]?.jsonObject ?: return@mapNotNull null
        val c = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
        val id = p["stationIdentifier"]?.jsonPrimitive?.content ?: return@mapNotNull null
        NwsStation(id, p["name"]?.jsonPrimitive?.content.orEmpty(), c?.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: Double.NaN, c?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: Double.NaN)
    }
} catch (e: Exception) { emptyList() }

/**
 * Finds the bulletin station for a point: api.weather.gov lists the observing stations nearest it, and the first the NBM carries ([has]) is the one. Needs the
 * point's coordinates sent to api.weather.gov once; the answer is the caller's to keep (a place's station does not change).
 */
class NwsStationFinder(private val http: Http) {
    /** [maxTried]: how many of the nearest stations are asked [has] about. Null when the point is outside the NWS's area or none of them is in the blend. */
    suspend fun find(lat: Double, lon: Double, maxTried: Int = 6, has: suspend (String) -> Boolean): NwsStation? {
        fun coordinate(v: Double): String {      // api.weather.gov wants four decimals at most (it answers with a redirect to them otherwise)
            val scaled = (v * 10000).toLong()
            val a = if (scaled < 0) -scaled else scaled
            return (if (scaled < 0) "-" else "") + (a / 10000) + "." + (a % 10000).toString().padStart(4, '0')
        }
        val point = http.get("https://api.weather.gov/points/${coordinate(lat)},${coordinate(lon)}")
        if (point.status != 200) return null
        val stationsUrl = parseNwsPoint(point.body.decodeToString())?.stationsUrl ?: return null
        val list = http.get(stationsUrl)
        if (list.status != 200) return null
        for (station in parseNwsStations(list.body.decodeToString()).take(maxTried)) if (has(station.id)) return station
        return null
    }
}
