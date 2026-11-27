package simplemodelview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cos

/** One match of a place search: a [name] to save it under, a short [detail] (where it is), the position and which service found it. */
data class PlaceHit(val name: String, val detail: String, val lat: Double, val lon: Double, val source: String)

/** The matches, and the services that could not be reached (so "no results" and "no network" read differently). */
data class PlaceSearchResult(val hits: List<PlaceHit>, val failed: List<String>)

/**
 * Finds a place by name from Wikipedia (landmarks, nicknames and earlier names: "buffalo bills stadium") and OpenStreetMap's Nominatim (towns, zip codes, addresses).
 * Only the typed text is sent. Both are asked on an explicit search, never while typing.
 */
class PlaceSearch(private val http: Http) {
    suspend fun search(query: String): PlaceSearchResult {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < 2) return PlaceSearchResult(emptyList(), emptyList())
        return coroutineScope {
            val wiki = async { fetch(wikipediaUrl(q), ::parseWikipedia) }
            val osm = async { fetch(nominatimUrl(q), ::parseNominatim) }
            val w = wiki.await(); val o = osm.await()
            PlaceSearchResult(mergeHits(q, w ?: emptyList(), o ?: emptyList()), listOfNotNull("Wikipedia".takeIf { w == null }, "OpenStreetMap".takeIf { o == null }))
        }
    }

    private suspend fun <T> fetch(url: String, parse: (String) -> T): T? = try {
        val r = http.get(url)
        if (r.status == 200) parse(r.body.decodeToString()) else null
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}

fun nominatimUrl(q: String) = "https://nominatim.openstreetmap.org/search?q=${urlEncode(q)}&format=jsonv2&limit=5"

fun wikipediaUrl(q: String) =
    "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=${urlEncode(q)}&gsrlimit=8&gsrnamespace=0&prop=coordinates%7Cdescription&colimit=max&format=json"

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Wikipedia's search with coordinates: the articles that have a position on Earth, in the order the search ranked them. */
fun parseWikipedia(body: String): List<PlaceHit> {
    val pages = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
    return pages.values.mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        val c = (o["coordinates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
        val lat = c["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lon = c["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        if (c["globe"]?.jsonPrimitive?.content.let { it != null && it != "earth" } || !validLatLon(lat, lon)) return@mapNotNull null
        val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        (o["index"]?.jsonPrimitive?.intOrNull ?: 99) to PlaceHit(title, o["description"]?.jsonPrimitive?.content.orEmpty(), lat, lon, "Wikipedia")
    }.sortedBy { it.first }.map { it.second }
}

/** Nominatim's answer: the name (or the first part of the address), where it is, and the position. */
fun parseNominatim(body: String): List<PlaceHit> {
    val list = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
    return list.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        if (!validLatLon(lat, lon)) return@mapNotNull null
        val display = o["display_name"]?.jsonPrimitive?.content.orEmpty()
        val parts = display.split(", ")
        val name = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: parts.firstOrNull().orEmpty()
        if (name.isBlank()) return@mapNotNull null
        // the rest of the address, short: the first two parts after the name, and the country when it is not the first of them
        val rest = parts.drop(if (parts.firstOrNull() == name) 1 else 0)
        val detail = (rest.take(2) + listOfNotNull(rest.lastOrNull()).filter { it !in rest.take(2) }).joinToString(", ")
        PlaceHit(name, detail, lat, lon, "OpenStreetMap")
    }
}

/**
 * Both services' matches as one short list. A match within about a kilometre of one already listed is dropped (the same stadium twice). A query with a digit in
 * it (a zip code, an address) lists OpenStreetMap's alone (Wikipedia only when it found nothing: its matches for numbers are noise), any other Wikipedia first.
 */
fun mergeHits(query: String, wiki: List<PlaceHit>, osm: List<PlaceHit>, max: Int = 6): List<PlaceHit> {
    val addressLike = query.any { it.isDigit() }
    val ordered = if (addressLike) osm.take(5).ifEmpty { wiki.take(3) } else wiki.take(3) + osm.take(4)
    val out = ArrayList<PlaceHit>()
    for (h in ordered) if (out.none { near(it, h) }) out += h
    return out.take(max)
}

private fun near(a: PlaceHit, b: PlaceHit): Boolean {
    val dLat = abs(a.lat - b.lat) * 111.0
    val dLon = abs(a.lon - b.lon) * 111.0 * cos((a.lat + b.lat) / 2 * kotlin.math.PI / 180.0)
    return dLat < 1.0 && dLon < 1.0
}
