package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlookArchiveTest {
    private val dayStart = epochOf(2025, 5, 20, 12, 0, 0)      // the convective day of 20 May 2025

    @Test fun issueTimesFollowTheCyclesOfEachDay() {
        assertEquals(epochOf(2025, 5, 20, 16, 0, 0), OutlookArchive.issueTime(1, dayStart, 16))
        assertEquals(epochOf(2025, 5, 21, 1, 0, 0), OutlookArchive.issueTime(1, dayStart, 1))       // the 01Z update is already the next date
        assertEquals(epochOf(2025, 5, 19, 17, 0, 0), OutlookArchive.issueTime(2, dayStart, 17))
        assertEquals(epochOf(2025, 5, 18, 8, 0, 0), OutlookArchive.issueTime(3, dayStart, 8))
        assertEquals(epochOf(2025, 5, 17, 10, 0, 0), OutlookArchive.issueTime(4, dayStart, 10))
    }

    @Test fun theOutlookInPlaceIsTheNewestIssuedAtOrBeforeTheTime() {
        val valid = epochOf(2025, 5, 20, 22, 0, 0)
        val afternoon = assertNotNull(outlookArchiveFor(OutlookType.Categorical, valid, epochOf(2025, 5, 20, 18, 0, 0)))
        assertEquals(1, afternoon.day); assertEquals(16, afternoon.archive.first().cycle); assertEquals("2025-05-20", afternoon.archive.first().validDate)
        assertTrue(afternoon.archive.size > 1 && afternoon.archive.zipWithNext().all { (a, b) -> a.issued > b.issued })       // the older ones follow, for a gap in the archive
        val evening = assertNotNull(outlookArchiveFor(OutlookType.Categorical, valid, epochOf(2025, 5, 20, 21, 0, 0)))
        assertEquals(20, evening.archive.first().cycle)
        // before the convective day began, the Day 2 outlook of the day before is what was in place
        val before = assertNotNull(outlookArchiveFor(OutlookType.Wind, valid, epochOf(2025, 5, 20, 3, 0, 0)))
        assertEquals(2, before.day); assertEquals(17, before.archive.first().cycle); assertEquals(OutlookType.Wind, before.type)
        // the same rules as the current files: a hazard map has no Day 3, and the total severe chance is the categorical on days 1 and 2
        val dayThree = assertNotNull(outlookArchiveFor(OutlookType.Tornado, valid, epochOf(2025, 5, 18, 12, 0, 0)))
        assertEquals(3, dayThree.day); assertEquals(OutlookType.Probability, dayThree.type)
        assertEquals(OutlookType.Categorical, assertNotNull(outlookArchiveFor(OutlookType.Probability, valid, epochOf(2025, 5, 20, 18, 0, 0))).type)
        // nothing had been issued more than eight days ahead
        assertNull(outlookArchiveFor(OutlookType.Categorical, valid, epochOf(2025, 5, 5, 0, 0, 0)))
    }

    @Test fun anArchivedChoiceHasItsOwnIdAndUrl() {
        val c = assertNotNull(outlookArchiveFor(OutlookType.Categorical, epochOf(2025, 5, 20, 22, 0, 0), epochOf(2025, 5, 20, 18, 0, 0)))
        assertEquals("1:Categorical@2025-05-20/16", c.id)
        assertEquals("https://mesonet.agron.iastate.edu/api/1/nws/spc_outlook.geojson?day=1&valid=2025-05-20&cycle=16", c.url)
        assertTrue("issued" in c.label)
        assertEquals("1:Categorical", OutlookChoice(1, OutlookType.Categorical).id)         // a current outlook is as before
    }

    private fun feature(category: String, threshold: String, extra: String = "") =
        """{"type":"Feature","properties":{"issue":"2025-05-20T16:30:00Z","product_issue":"2025-05-20T16:15:00Z","expire":"2025-05-21T12:00:00Z","threshold":"$threshold","category":"$category"$extra},
            "geometry":{"type":"MultiPolygon","coordinates":[[[[-100,35],[-95,35],[-95,40],[-100,40],[-100,35]]]]}}"""

    private val file = """{"type":"FeatureCollection","features":[${listOf(
        feature("CATEGORICAL", "SLGT"), feature("CATEGORICAL", "MRGL"), feature("TORNADO", "0.05"), feature("TORNADO", "SIGN"), feature("WIND", "0.15"), feature("ANY SEVERE", "0.30"),
    ).joinToString(",")}]}"""

    @Test fun eachMapIsReadFromItsOwnCategory() {
        val cat = assertNotNull(parseArchivedOutlook(file, OutlookType.Categorical))
        assertEquals(listOf("Marginal Risk", "Slight Risk"), cat.shapes.map { it.title })           // lowest first
        val torn = assertNotNull(parseArchivedOutlook(file, OutlookType.Tornado))
        assertEquals(listOf("5%", "Significant severe"), torn.shapes.map { it.title })
        assertTrue(torn.shapes.last().hatched)
        assertEquals(listOf("15%"), assertNotNull(parseArchivedOutlook(file, OutlookType.Wind)).shapes.map { it.title })
        assertEquals(listOf("30%"), assertNotNull(parseArchivedOutlook(file, OutlookType.Probability)).shapes.map { it.title })
        assertEquals(0, assertNotNull(parseArchivedOutlook(file, OutlookType.Hail)).shapes.size)       // an outlook without hail areas: no risk area, not "missing"
        assertNull(parseArchivedOutlook("""{"type":"FeatureCollection","features":[]}""", OutlookType.Categorical))      // nothing archived
        assertNull(parseArchivedOutlook("not json", OutlookType.Categorical))
        assertEquals(epochOf(2025, 5, 21, 12, 0, 0), cat.expires)
    }

    @Test fun anArchivedOutlookFallsBackToTheNextOneThatIsThere() = runTest {
        val asked = ArrayList<String>()
        val http = object : Http {
            override suspend fun get(url: String): HttpResult {
                asked += url
                return if ("cycle=16" in url) HttpResult(200, """{"type":"FeatureCollection","features":[]}""".encodeToByteArray()) else HttpResult(200, file.encodeToByteArray())
            }
        }
        val choice = assertNotNull(outlookArchiveFor(OutlookType.Categorical, epochOf(2025, 5, 20, 22, 0, 0), epochOf(2025, 5, 20, 18, 0, 0)))
        val data = OutlookRepository(http).load(choice)
        assertEquals(2, data.shapes.size)
        assertTrue(asked.first().contains("cycle=16") && asked.size == 2)
    }
}

class AlertArchiveTest {
    private val valid = epochOf(2025, 5, 20, 22, 0, 0)

    private fun warning(phSig: String, issue: String, expire: String, product: String = "202505202140-KOUN-WFUS54-TORFWD") =
        """{"type":"Feature","properties":{"ph_sig":"$phSig","utc_issue":"$issue","utc_expire":"$expire","locations":"Cleveland [OK]","product_id":"$product"},
            "geometry":{"type":"MultiPolygon","coordinates":[[[[-98,35],[-97,35],[-97,36],[-98,36],[-98,35]]]]}}"""

    @Test fun onlyWarningsInForceAtTheTimeAreKept() {
        val text = """{"type":"FeatureCollection","features":[
            ${warning("TO.W", "2025-05-20T21:40:00Z", "2025-05-20T22:30:00Z")},
            ${warning("SV.W", "2025-05-20T20:00:00Z", "2025-05-20T21:45:00Z")},
            ${warning("TO.W", "2025-05-20T22:05:00Z", "2025-05-20T22:45:00Z")},
            ${warning("FA.Y", "2025-05-20T21:00:00Z", "2025-05-20T23:00:00Z")}]}"""
        val shapes = AlertArchive.parseWarnings(text, valid)
        assertEquals(listOf("Tornado Warning"), shapes.map { it.title })             // the second expired, the third was issued later, the advisory is not drawn
        val w = shapes.single()
        assertEquals(AlertKind.Warning, w.kind); assertEquals(0xFF0000, w.color)
        assertEquals("https://mesonet.agron.iastate.edu/api/1/nwstext/202505202140-KOUN-WFUS54-TORFWD", w.detailUrl)
        assertEquals(1, w.rings.size)
    }

    @Test fun watchesAreTheSpcOutlinesInForce() {
        val text = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"sel":"SEL2","utc_issued":"2025-05-20T17:10:00Z","utc_expired":"2025-05-20T22:30:00Z","type":"TOR","num":302},"geometry":{"type":"MultiPolygon","coordinates":[[[[-98,35],[-97,35],[-97,36],[-98,35]]]]}},
            {"type":"Feature","properties":{"sel":"SEL3","utc_issued":"2025-05-20T18:10:00Z","utc_expired":"2025-05-20T21:00:00Z","type":"SVR","num":303},"geometry":{"type":"MultiPolygon","coordinates":[[[[-98,35],[-97,35],[-97,36],[-98,35]]]]}}]}"""
        val shapes = AlertArchive.parseWatches(text, valid)
        assertEquals(listOf("Tornado Watch 302"), shapes.map { it.title })
        assertEquals(AlertKind.Watch, shapes.single().kind)
    }

    @Test fun urlsAskForTheHoursBeforeTheTime() {
        val url = AlertArchive.warningsUrl(valid)
        assertTrue("begints=2025-05-20T14:00:00Z" in url && "endts=2025-05-20T22:01:00Z" in url && "&ph=TO" in url && "&ph=MA" in url)
        assertEquals("https://mesonet.agron.iastate.edu/api/1/spc_watch_outline.geojson?valid=2025-05-20T22:00:00Z", AlertArchive.watchesUrl(valid))
    }

    @Test fun archivedPartsFailTogetherOrNotAtAll() = kotlinx.coroutines.test.runTest {
        val onlyWatches = object : Http {
            override suspend fun get(url: String) = if ("vtec" in url) HttpResult(500, ByteArray(0)) else HttpResult(200, """{"features":[]}""".encodeToByteArray())
        }
        assertEquals(0, AlertsRepository(onlyWatches).archived(valid, setOf(AlertKind.Warning, AlertKind.Watch)).size)      // the watches answered (with none): not an error
        assertTrue(runCatching { AlertsRepository(onlyWatches).archived(valid, setOf(AlertKind.Warning)) }.isFailure)
    }
}
