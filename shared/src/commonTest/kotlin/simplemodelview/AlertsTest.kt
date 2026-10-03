package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertsTest {
    private val now = epochOf(2026, 10, 2, 21, 0, 0)

    // a real SPC page (md2345, 1 Oct 2026), shortened
    private val spcPage = """<html><body><pre>

   Mesoscale Discussion 2345
   NWS Storm Prediction Center Norman OK
   0132 PM CDT Thu Oct 01 2026

   Areas affected...portions of southwestern Kansas into far
   northwestern Oklahoma

   Concerning...Severe potential...Watch unlikely 

   Valid 011832Z - 012100Z

   Probability of Watch Issuance...5 percent

   SUMMARY...A couple of severe gusts &amp; perhaps a brief tornado are
   possible this afternoon.

   ATTN...WFO...ICT...OUN...DDC...AMA...

   LAT...LON   37280194 37750101 37860010 37899898 37729842 37419805
               37219792 36729788 36349817 36149863 36149921 36229986
               36410038 36710124 36960184 37280194 

   MOST PROBABLE PEAK TORNADO INTENSITY...UP TO 90 MPH
</pre></body></html>"""

    private val wpcPage = """<pre>
Mesoscale Precipitation Discussion 1429
NWS Weather Prediction Center College Park MD

Areas affected...Central TX

Concerning...Heavy rainfall...Flash flooding possible

Valid 021900Z - 030100Z

SUMMARY...Locally slow-moving showers.

LAT...LON   32279675 31549647 30229670 29249734 29029813
            29389881 30199892 31099862 32209766
</pre>"""

    @Test fun latLonDigitsAreHundredthsOfDegreesWithTheHundredsOfLongitudeDropped() {
        val ring = parseLatLon(preText(spcPage)!!).single()
        assertEquals(16 * 2, ring.size)
        assertEquals(37.28, ring[0], 1e-9); assertEquals(-101.94, ring[1], 1e-9)       // 0194 is 101.94 W
        assertEquals(37.75, ring[2], 1e-9); assertEquals(-101.01, ring[3], 1e-9)
        assertEquals(37.86, ring[4], 1e-9); assertEquals(-100.10, ring[5], 1e-9)
        val wpc = parseLatLon(preText(wpcPage)!!).single()
        assertEquals(32.27, wpc[0], 1e-9); assertEquals(-96.75, wpc[1], 1e-9)           // 9675 is already 96.75 W
        assertEquals(emptyList(), parseLatLon("no block here"))
        assertEquals(emptyList(), parseLatLon("LAT...LON  1234 5678"))                  // too short to be an outline
    }

    @Test fun validRangeIsResolvedAgainstTheClock() {
        val (s, e) = parseValidRange("Valid 021900Z - 030100Z", now)!!
        assertEquals(epochOf(2026, 10, 2, 19, 0, 0), s)
        assertEquals(epochOf(2026, 10, 3, 1, 0, 0), e)                                  // the end is in the next day
        val (s2, e2) = parseValidRange("Valid 312330Z - 010130Z", epochOf(2026, 11, 1, 0, 0, 0))!!   // across a month end
        assertEquals(epochOf(2026, 10, 31, 23, 30, 0), s2); assertEquals(epochOf(2026, 11, 1, 1, 30, 0), e2)
        assertNull(parseValidRange("nothing", now))
    }

    @Test fun anSpcDiscussionBecomesADashedOutlineWithItsText() {
        val shape = parseDiscussionPage(spcPage, "2345", spc = true, nowEpochSeconds = epochOf(2026, 10, 1, 19, 0, 0))!!
        assertEquals(AlertKind.Discussion, shape.kind)
        assertEquals("SPC MD 2345", shape.title)
        assertEquals(epochOf(2026, 10, 1, 18, 32, 0), shape.issued); assertEquals(epochOf(2026, 10, 1, 21, 0, 0), shape.expires)
        assertTrue(shape.dashed && shape.rings.size == 1)
        assertTrue("Severe potential" in shape.summary && "southwestern Kansas" in shape.summary, shape.summary)
        assertTrue("severe gusts & perhaps" in shape.text!!)                           // entities undone
        assertNull(parseDiscussionPage("<pre>Valid 011832Z - 012100Z no outline</pre>", "1", true, now))
        assertNull(parseDiscussionPage("<html>no pre</html>", "1", true, now))
    }

    @Test fun aWpcDiscussionUsesItsOwnColor() {
        val shape = parseDiscussionPage(wpcPage, "1429", spc = false, nowEpochSeconds = now)!!
        assertEquals("WPC MPD 1429", shape.title); assertEquals(AlertTypes.WPC_DISCUSSION, shape.color)
        assertEquals(epochOf(2026, 10, 3, 1, 0, 0), shape.expires)
    }

    @Test fun indexPagesNameTheDiscussions() {
        assertEquals(listOf("2346", "2345"), spcDiscussionNumbers("""<a href="/products/md/md2345.html">2345</a> <a href="md2346.html">x</a> md2345.html"""))
        val wpc = """<a href="/metwatch/metwatch_mpd_multi.php?md=1429&amp;yr=2026">1429</a> <a href="metwatch_mpd_multi.php?md=1430&yr=2026">"""
        assertEquals(listOf("1430" to "2026", "1429" to "2026"), wpcDiscussionLinks(wpc))
    }

    // the NOAA map service's GeoJSON (real shape: a flash flood warning polygon), plus an advisory and an expired warning
    private fun feature(type: String, issued: String, expires: String) =
        """{"type":"Feature","properties":{"prod_type":"$type","issuance":"$issued","expiration":"$expires","url":"https://api.weather.gov/alerts/x"},""" +
            """"geometry":{"type":"Polygon","coordinates":[[[-81.81,37.28],[-81.79,37.28],[-81.79,37.30],[-81.81,37.30],[-81.81,37.28]]]}}"""

    @Test fun warningsAndWatchesAreReadFromTheServiceReply() {
        val reply = """{"type":"FeatureCollection","features":[""" + listOf(
            feature("Flash Flood Warning", "2026-10-02T20:17:00-04:00", "2026-10-02T21:45:00-04:00"),
            feature("Tornado Watch", "2026-10-02T15:00:00-05:00", "2026-10-02T23:00:00-05:00"),
            feature("Wind Advisory", "2026-10-02T15:00:00-05:00", "2026-10-03T23:00:00-05:00"),          // not drawn
            feature("Tornado Warning", "2026-10-02T10:00:00-05:00", "2026-10-02T10:30:00-05:00"),       // expired
        ).joinToString(",") + "]}"
        val shapes = parseWatchWarnGeoJson(reply, epochOf(2026, 10, 3, 0, 30, 0))                        // 00:30Z on the 3rd = 20:30 EDT on the 2nd
        assertEquals(listOf("Flash Flood Warning", "Tornado Watch"), shapes.map { it.title })
        val ffw = shapes[0]
        assertEquals(AlertKind.Warning, ffw.kind); assertEquals(0x8B0000, ffw.color)
        assertEquals(epochOf(2026, 10, 3, 0, 17, 0), ffw.issued)                                         // 20:17 at -04:00 is 00:17Z
        assertEquals(epochOf(2026, 10, 3, 1, 45, 0), ffw.expires)
        assertEquals(1, ffw.rings.size); assertEquals(37.28, ffw.rings[0][0], 1e-9); assertEquals(-81.81, ffw.rings[0][1], 1e-9)
        assertEquals(AlertKind.Watch, shapes[1].kind)
        assertEquals(emptyList(), parseWatchWarnGeoJson("not json", now))
        assertEquals(emptyList(), parseWatchWarnGeoJson("""{"features":[{"properties":{}}]}""", now))
    }

    @Test fun timestampsWithOffsets() {
        assertEquals(epochOf(2026, 10, 3, 0, 17, 0), parseIsoWithOffset("2026-10-02T20:17:00-04:00"))
        assertEquals(epochOf(2026, 10, 2, 9, 0, 0), parseIsoWithOffset("2026-10-02T18:30:00+09:30"))
        assertEquals(epochOf(2026, 10, 2, 12, 0, 0), parseIsoWithOffset("2026-10-02T12:00:00Z"))
        assertNull(parseIsoWithOffset("tomorrow"))
    }

    @Test fun tappingFindsTheOutlinesThatContainThePoint() {
        val warning = parseWatchWarnGeoJson("""{"features":[""" + feature("Tornado Warning", "2026-10-02T20:00:00-04:00", "2026-10-02T23:00:00-04:00") + "]}", now).single()
        val md = parseDiscussionPage(spcPage, "2345", true, epochOf(2026, 10, 1, 19, 0, 0))!!
        assertEquals(listOf(warning), shapesAt(listOf(md, warning), 37.29, -81.80))
        assertEquals(listOf(md), shapesAt(listOf(md, warning), 36.9, -100.5))                           // inside the discussion's area
        assertEquals(emptyList(), shapesAt(listOf(md, warning), 45.0, -100.0))
        // warnings come before discussions when both contain the point
        val both = listOf(md, AlertShape(AlertKind.Warning, "w", 0, md.rings, null, null, ""))
        assertEquals(listOf(AlertKind.Warning, AlertKind.Discussion), shapesAt(both, 36.9, -100.5).map { it.kind })
    }

    @Test fun queryTextIsPercentEncoded() {
        assertEquals("prod_type%20IN%20%28%27Tornado%20Warning%27%29", urlEncode("prod_type IN ('Tornado Warning')"))
        assertEquals("a-b_c.d~e", urlEncode("a-b_c.d~e"))
        assertEquals("%C3%A9", urlEncode("\u00e9"))
    }

    @Test fun alertDetailTextIsHeadlineDescriptionInstruction() {
        val reply = """{"properties":{"headline":"Flash Flood Warning issued","description":"Heavy rain.","instruction":"Turn around, don't drown."}}"""
        assertEquals("Flash Flood Warning issued\n\nHeavy rain.\n\nTurn around, don't drown.", parseAlertDetail(reply))
        assertNull(parseAlertDetail("""{"properties":{}}"""))
        assertNull(parseAlertDetail("junk"))
    }

    @Test fun theRepositoryKeepsOnlyActiveDiscussionsAndSurvivesAFailingSource() = runTest {
        val http = object : Http {
            override suspend fun get(url: String): HttpResult = when {
                url.endsWith("/products/md/") -> HttpResult(200, "md2345.html md2344.html".encodeToByteArray())
                url.endsWith("md2345.html") -> HttpResult(200, spcPage.encodeToByteArray())
                url.contains("metwatch_mpd.php") -> HttpResult(503, ByteArray(0))             // WPC is down
                else -> HttpResult(404, ByteArray(0))
            }
        }
        val repo = AlertsRepository(http)
        assertEquals(listOf("SPC MD 2345"), repo.discussions(epochOf(2026, 10, 1, 19, 0, 0)).map { it.title })     // inside 1832Z-2100Z
        assertEquals(emptyList(), repo.discussions(epochOf(2026, 10, 1, 23, 0, 0)))                                // expired by then
    }

    @Test fun theRepositoryAsksForOnlyTheDrawnProducts() = runTest {
        var url = ""
        val http = object : Http { override suspend fun get(url0: String): HttpResult { url = url0; return HttpResult(200, """{"features":[]}""".encodeToByteArray()) } }
        assertEquals(emptyList(), AlertsRepository(http).warningsAndWatches(now))
        assertTrue("watch_warn_adv/MapServer/1/query" in url && "f=geojson" in url && "outSR=4326" in url, url)
        assertTrue("Tornude" !in url && "Tornado%20Warning" in url && "Wind%20Advisory" !in url, url)
        val failing = object : Http { override suspend fun get(url: String) = HttpResult(500, ByteArray(0)) }
        var failed = false
        try { AlertsRepository(failing).warningsAndWatches(now) } catch (e: RefsException) { failed = true }
        assertTrue(failed)
    }
}
