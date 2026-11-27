package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceSearchTest {
    private val wiki = """{"batchcomplete":"","query":{"pages":{
        "11":{"pageid":11,"title":"Ralph Wilson Stadium","index":3,"coordinates":[{"lat":42.774,"lon":-78.787,"primary":"","globe":"earth"}],"description":"Defunct football stadium in Orchard Park, New York"},
        "12":{"pageid":12,"title":"2026 Buffalo Bills season","index":1,"description":"American football team season"},
        "13":{"pageid":13,"title":"Highmark Stadium","index":2,"coordinates":[{"lat":42.77305556,"lon":-78.79222222,"primary":"","globe":"earth"}],"description":"Stadium in Orchard Park, New York"},
        "14":{"pageid":14,"title":"Mare Tranquillitatis","index":4,"coordinates":[{"lat":8.5,"lon":31.4,"primary":"","globe":"moon"}]}}}}"""

    private val osm = """[{"name":"Norman","lat":"35.2225668","lon":"-97.4394777","display_name":"Norman, Cleveland County, Oklahoma, United States","type":"city"},
        {"name":"","lat":"35.2","lon":"-97.4","display_name":"73069, Norman, Cleveland County, Oklahoma, United States"},
        {"name":"Bad","lat":"x","lon":"1","display_name":"Bad"}]"""

    @Test fun wikipediaKeepsEarthArticlesWithAPositionInRankOrder() {
        val hits = parseWikipedia(wiki)
        assertEquals(listOf("Highmark Stadium", "Ralph Wilson Stadium"), hits.map { it.name })        // no position, and the moon, are dropped
        assertEquals("Stadium in Orchard Park, New York", hits[0].detail)
        assertEquals("Wikipedia", hits[0].source)
        assertEquals(emptyList(), parseWikipedia("""{"batchcomplete":""}"""))
    }

    @Test fun nominatimNamesAndShortensTheAddress() {
        val hits = parseNominatim(osm)
        assertEquals(2, hits.size)                                                                   // the one with a bad latitude is dropped
        assertEquals("Norman", hits[0].name); assertEquals("Cleveland County, Oklahoma, United States", hits[0].detail)
        assertEquals("73069", hits[1].name)                                                          // no name: the first part of the address
        assertEquals(35.2225668, hits[0].lat, 1e-9); assertEquals(-97.4394777, hits[0].lon, 1e-9)
    }

    @Test fun theSameStadiumFromBothIsListedOnce() {
        val w = listOf(PlaceHit("Highmark Stadium", "Stadium", 42.7730, -78.7922, "Wikipedia"))
        val o = listOf(PlaceHit("Highmark Stadium", "Orchard Park", 42.7737, -78.7905, "OpenStreetMap"), PlaceHit("Buffalo", "New York", 42.88, -78.88, "OpenStreetMap"))
        val merged = mergeHits("buffalo bills stadium", w, o)
        assertEquals(listOf("Wikipedia", "OpenStreetMap"), merged.map { it.source })
        assertEquals(2, merged.size)
    }

    @Test fun aQueryWithADigitListsOpenStreetMapAlone() {
        val w = listOf(PlaceHit("A", "", 1.0, 1.0, "Wikipedia")); val o = listOf(PlaceHit("B", "", 2.0, 2.0, "OpenStreetMap"))
        assertEquals(listOf("OpenStreetMap"), mergeHits("73069", w, o).map { it.source })        // Wikipedia's matches for a number are noise
        assertEquals(listOf("Wikipedia"), mergeHits("73069", w, emptyList()).map { it.source })
        assertEquals("Wikipedia", mergeHits("norman", w, o).first().source)
    }

    @Test fun theQueryIsEncodedAsUtf8() {
        assertEquals("buffalo%20bills%20stadium", urlEncode("buffalo bills stadium"))
        assertEquals("M%C3%BCnchen%20%26%20co", urlEncode("München & co"))
        assertTrue(nominatimUrl("a b").contains("q=a%20b"))
    }

    @Test fun aServiceThatFailsIsReportedAndTheOtherStillAnswers() = kotlinx.coroutines.test.runTest {
        val http = object : Http {
            override suspend fun get(url: String) = if ("wikipedia" in url) HttpResult(200, wiki.encodeToByteArray()) else HttpResult(0, ByteArray(0))
        }
        val r = PlaceSearch(http).search("buffalo bills stadium")
        assertEquals(listOf("OpenStreetMap"), r.failed)
        assertEquals("Highmark Stadium", r.hits.first().name)
        assertEquals(PlaceSearchResult(emptyList(), emptyList()), PlaceSearch(http).search("a"))      // too short to ask
    }
}
