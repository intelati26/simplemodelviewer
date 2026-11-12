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
class NwsPoint(val office: String, val gridX: Int, val gridY: Int, val stationsUrl: String, val gridUrl: String, val place: String)

/** The reply of `/points/<lat>,<lon>`, or null when it is not one. */
fun parseNwsPoint(text: String): NwsPoint? = try {
    val p = Json.parseToJsonElement(text).jsonObject["properties"]?.jsonObject ?: return null
    val relative = p["relativeLocation"]?.jsonObject?.get("properties")?.jsonObject
    val place = listOfNotNull(relative?.get("city")?.jsonPrimitive?.content, relative?.get("state")?.jsonPrimitive?.content).joinToString(", ")
    NwsPoint(
        p["gridId"]!!.jsonPrimitive.content, p["gridX"]!!.jsonPrimitive.content.toInt(), p["gridY"]!!.jsonPrimitive.content.toInt(),
        p["observationStations"]!!.jsonPrimitive.content, p["forecastGridData"]?.jsonPrimitive?.content.orEmpty(), place,
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
 * api.weather.gov for a point: where the point is on the NWS grid, the observing station nearest it that the blend carries, and the gridded forecast
 * (hourly, to about 8 days, for the hours the bulletins do not cover). The point's coordinates go to api.weather.gov for this, so a place is asked once
 * and the answer kept by the caller.
 */
class NwsPointClient(private val http: Http) {
    private fun coordinate(v: Double): String {      // api.weather.gov wants four decimals at most (it answers with a redirect to them otherwise)
        val scaled = (v * 10000).toLong()
        val a = if (scaled < 0) -scaled else scaled
        return (if (scaled < 0) "-" else "") + (a / 10000) + "." + (a % 10000).toString().padStart(4, '0')
    }

    suspend fun point(lat: Double, lon: Double): NwsPoint? {
        val reply = http.get("https://api.weather.gov/points/${coordinate(lat)},${coordinate(lon)}")
        return if (reply.status == 200) parseNwsPoint(reply.body.decodeToString()) else null
    }

    /** The first of the [maxTried] nearest observing stations that [has] accepts (the blend carries it), or null. */
    suspend fun station(point: NwsPoint, maxTried: Int = 6, has: suspend (String) -> Boolean): NwsStation? {
        val list = http.get(point.stationsUrl)
        if (list.status != 200) return null
        for (station in parseNwsStations(list.body.decodeToString()).take(maxTried)) if (has(station.id)) return station
        return null
    }

    /** The gridded forecast of the point, or null when it cannot be had. */
    suspend fun grid(point: NwsPoint): NwsGridData? {
        if (point.gridUrl.isEmpty()) return null
        val reply = http.get(point.gridUrl)
        return if (reply.status == 200) parseNwsGrid(reply.body.decodeToString()) else null
    }
}
