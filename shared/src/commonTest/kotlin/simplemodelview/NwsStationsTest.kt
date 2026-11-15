package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NwsStationsTest {
    private val point = """{"properties":{"gridId":"OUN","gridX":100,"gridY":83,"observationStations":"https://api.weather.gov/gridpoints/OUN/100,83/stations","forecastGridData":"https://api.weather.gov/gridpoints/OUN/100,83","timeZone":"America/Chicago",
        "relativeLocation":{"properties":{"city":"Norman","state":"OK"}}}}"""
    private val stations = """{"features":[
        {"geometry":{"type":"Point","coordinates":[-97.4708,35.2435]},"properties":{"stationIdentifier":"KOUN","name":"Norman / Max Westheimer"}},
        {"geometry":{"type":"Point","coordinates":[-97.38333,35.41667]},"properties":{"stationIdentifier":"KTIK","name":"Tinker Air Force Base"}},
        {"geometry":{"type":"Point","coordinates":[-97.60028,35.38861]},"properties":{"stationIdentifier":"KOKC","name":"Oklahoma City, Will Rogers World Airport"}}]}"""

    private class Fake(private val replies: Map<String, String>) : Http {
        val asked = ArrayList<String>()
        override suspend fun get(url: String): HttpResult {
            asked += url
            return replies[url]?.let { HttpResult(200, it.encodeToByteArray()) } ?: HttpResult(404, ByteArray(0))
        }
    }

    @Test fun aPointNamesItsGridAndItsStationsLink() {
        val p = parseNwsPoint(point)!!
        assertEquals("OUN", p.office)
        assertEquals(100, p.gridX)
        assertEquals("https://api.weather.gov/gridpoints/OUN/100,83/stations", p.stationsUrl)
        assertEquals("Norman, OK", p.place)
        assertEquals("America/Chicago", p.timeZone)
        assertEquals("https://api.weather.gov/gridpoints/OUN/100,83", p.gridUrl)
        assertNull(parseNwsPoint("{}"))
        assertNull(parseNwsPoint("not json"))
    }

    @Test fun stationsComeNearestFirstWithTheirCoordinates() {
        val s = parseNwsStations(stations)
        assertEquals(listOf("KOUN", "KTIK", "KOKC"), s.map { it.id })
        assertEquals(35.2435, s[0].lat)
        assertEquals(-97.4708, s[0].lon)
        assertEquals(emptyList(), parseNwsStations("nope"))
    }

    @Test fun theNearestStationTheBlendCarriesIsTheOne() = runTest {
        val http = Fake(mapOf("https://api.weather.gov/points/35.2220,-97.4395" to point, "https://api.weather.gov/gridpoints/OUN/100,83/stations" to stations))
        val found = NwsPointClient(http).let { c -> c.point(35.22204, -97.43951)?.let { c.station(it) { id -> id == "KOKC" } } }   // KOUN and KTIK are not in the blend
        assertNotNull(found)
        assertEquals("KOKC", found.id)
        // the coordinates are cut (not rounded) to four decimals, as the API asks
        assertEquals("https://api.weather.gov/points/35.2220,-97.4395", http.asked.first())
        assertNull(NwsPointClient(http).let { c -> c.point(35.2220, -97.4395)?.let { c.station(it) { false } } })
        assertNull(NwsPointClient(Fake(emptyMap())).point(10.0, 10.0))
    }
}
