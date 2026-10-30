package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlookTest {
    private fun square(w: Double, s: Double, e: Double, n: Double) = "[[$w,$s],[$e,$s],[$e,$n],[$w,$n],[$w,$s]]"

    private fun feature(props: String, geometry: String) = """{"type":"Feature","properties":{$props},"geometry":$geometry}"""

    private val cat = """{"type":"FeatureCollection","features":[
        ${feature(""""DN":2,"VALID":"202610301200","EXPIRE":"202610311200","ISSUE":"202610301630","LABEL":"MRGL","LABEL2":"Marginal Risk","stroke":"#005500","fill":"#66A366"""",
        """{"type":"Polygon","coordinates":[${square(-100.0, 30.0, -90.0, 40.0)}]}""")},
        ${feature(""""DN":3,"VALID":"202610301200","EXPIRE":"202610311200","ISSUE":"202610301630","LABEL":"SLGT","LABEL2":"Slight Risk","stroke":"#DDAA00"""",
        """{"type":"MultiPolygon","coordinates":[[${square(-98.0, 32.0, -92.0, 38.0)},${square(-96.0, 34.0, -94.0, 36.0)}]]}""")},
        ${feature(""""DN":5,"LABEL":"MDT","LABEL2":"Moderate Risk"""", """{"type":"Polygon","coordinates":[${square(-95.5, 34.5, -94.5, 35.5)}]}""")},
        ${feature(""""LABEL":"MRGL"""", """{"type":"Point","coordinates":[-95.0,35.0]}""")}
    ]}"""

    @Test fun parsesCategoriesLowestFirstWithTimesAndColors() {
        val data = parseOutlookGeoJson(cat)
        assertEquals(listOf("Marginal Risk", "Slight Risk", "Moderate Risk"), data.shapes.map { it.title })      // the label point is skipped
        assertEquals(listOf(2, 3, 5), data.shapes.map { it.rank })
        assertEquals(0x005500, data.shapes[0].color)
        assertEquals(0xDDAA00, data.shapes[1].color)
        assertEquals(0xFF0000, data.shapes[2].color)                       // no stroke given: the category's own color
        assertEquals(epochOf(2026, 10, 30, 12, 0, 0), data.valid)
        assertEquals(epochOf(2026, 10, 31, 12, 0, 0), data.expires)
        assertEquals(epochOf(2026, 10, 30, 16, 30, 0), data.issued)
        assertEquals(2, data.shapes[1].polygons[0].size)                   // the outer ring and its hole
    }

    @Test fun tapFindsTheMostSevereAreaAndRespectsHoles() {
        val shapes = parseOutlookGeoJson(cat).shapes
        assertEquals("Marginal Risk", outlookAt(shapes, 31.0, -99.0)?.title)
        assertEquals("Slight Risk", outlookAt(shapes, 33.0, -97.0)?.title)
        // inside the Slight polygon's hole (which is the small inner square) only the Marginal area is left, until the Moderate area inside the hole
        assertEquals("Marginal Risk", outlookAt(shapes, 34.2, -95.8)?.title)
        assertEquals("Moderate Risk", outlookAt(shapes, 35.0, -95.0)?.title)
        assertNull(outlookAt(shapes, 45.0, -80.0))
        assertEquals("SPC Day 1 categorical risk: Slight Risk", outlookReadout(OutlookChoice(1, OutlookType.Categorical), shapes[1]))
        assertEquals("SPC Day 2 tornado: no risk area here", outlookReadout(OutlookChoice(2, OutlookType.Tornado), null))
    }

    @Test fun parsesProbabilitiesAndTheHatchedArea() {
        val torn = """{"type":"FeatureCollection","features":[
            ${feature(""""DN":2,"LABEL":"0.02","LABEL2":"2% Tornado Risk","stroke":"#008B00"""", """{"type":"Polygon","coordinates":[${square(-100.0, 30.0, -90.0, 40.0)}]}""")},
            ${feature(""""DN":10,"LABEL":"0.10"""", """{"type":"Polygon","coordinates":[${square(-98.0, 32.0, -92.0, 38.0)}]}""")},
            ${feature(""""DN":10,"LABEL":"SIGN","LABEL2":"10% Significant Tornado Risk"""", """{"type":"Polygon","coordinates":[${square(-97.0, 33.0, -93.0, 37.0)}]}""")}
        ]}"""
        val data = parseOutlookGeoJson(torn)
        assertEquals(listOf("2% Tornado Risk", "10%", "10% Significant Tornado Risk"), data.shapes.map { it.title })
        assertEquals(0xFFC800, data.shapes[1].color)                       // no stroke: the probability's own color
        assertTrue(data.shapes[2].hatched)
        assertEquals("10%", outlookAt(data.shapes, 35.0, -95.0)?.title)    // the hatched area is not a level
        assertEquals(null, data.valid)
    }

    @Test fun damagedFilesGiveNoAreas() {
        assertEquals(0, parseOutlookGeoJson("not json").shapes.size)
        assertEquals(0, parseOutlookGeoJson("""{"features":[{"properties":{"LABEL":"SLGT"},"geometry":{"type":"Polygon","coordinates":[[[1,2]]]}}]}""").shapes.size)
        assertEquals(1, parseOutlookGeoJson("""{"features":[7, {"properties":{"LABEL":"ENH"},"geometry":{"type":"Polygon","coordinates":[${square(-100.0, 30.0, -90.0, 40.0)}]}}]}""").shapes.size)
        assertEquals(0, parseOutlookGeoJson("""{"type":"FeatureCollection","features":[]}""").shapes.size)
    }

    @Test fun choicesAndUrls() {
        assertEquals("https://www.spc.noaa.gov/products/outlook/day1otlk_cat.lyr.geojson", OutlookChoice(1, OutlookType.Categorical).url)
        assertEquals("day2otlk_torn", OutlookChoice(2, OutlookType.Tornado).fileName)
        assertEquals("day2otlk_wind", OutlookChoice(2, OutlookType.Wind).fileName)
        assertEquals("day1otlk_hail", OutlookChoice(1, OutlookType.Hail).fileName)
        assertEquals("day3otlk_prob", OutlookChoice(3, OutlookType.Probability).fileName)
        assertEquals("day3otlk_cat", OutlookChoice(3, OutlookType.Categorical).fileName)
        assertEquals("day5prob", OutlookChoice(5, OutlookType.Probability).fileName)
        assertEquals("Day 1 tornado", OutlookChoice(1, OutlookType.Tornado).label)
        assertEquals("Day 6 severe chance", OutlookChoice(6, OutlookType.Probability).label)
        assertEquals(4 + 4 + 2 + 5, OutlookChoice.all.size)
        assertEquals(OutlookChoice.all.size, OutlookChoice.all.map { it.id }.toSet().size)
        assertEquals(OutlookChoice(3, OutlookType.Probability), OutlookChoice.byId("3:Probability"))
        assertNull(OutlookChoice.byId("9:Hail"))
        assertNull(OutlookChoice.byId(null))
    }

    private class FakeHttp(val status: Int, val body: String) : Http {
        var url = ""
        override suspend fun get(url: String): HttpResult { this.url = url; return HttpResult(status, body.encodeToByteArray()) }
    }

    @Test fun loadsAnOutlook() = runTest {
        val choice = OutlookChoice(1, OutlookType.Categorical)
        val http = FakeHttp(200, cat)
        val data = OutlookRepository(http).load(choice)
        assertEquals(choice.url, http.url)
        assertEquals(3, data.shapes.size)
        assertNotNull(data.valid)
        assertFailsWith<RefsException> { OutlookRepository(FakeHttp(404, "")).load(choice) }
        assertFailsWith<RefsException> { OutlookRepository(FakeHttp(500, "")).load(choice) }
        assertEquals(0, OutlookRepository(FakeHttp(200, """{"features":[]}""")).load(choice).shapes.size)
    }
}
