package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NbmTest {
    private val run = epochOf(2026, 10, 30, 0, 0, 0)

    private fun stamp(epoch: Long): String {
        val c = civilFromEpoch(epoch)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "${c[0]}-${p(c[1])}-${p(c[2])}T${p(c[3])}:00"
    }

    private fun key(field: String, cycle: NbmCycle, leadHours: Int) =
        "${cycle.prefix}$field/blendv5.0_conus_${field}_${stamp(cycle.epoch)}_${stamp(cycle.epoch + leadHours * 3600L)}.tif"

    private fun listing(field: String, cycle: NbmCycle, leads: List<Int>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>noaa-nbm-pds</Name>""")
        for (h in leads) {
            append("<Contents><Key>${key(field, cycle, h)}</Key><LastModified>2026-10-30T00:41:03.000Z</LastModified>")
            append("<ETag>&quot;x&quot;</ETag><Size>857000</Size><StorageClass>STANDARD</StorageClass></Contents>")
        }
        append("</ListBucketResult>")
    }

    private val c00 = NbmCycle(2026, 10, 30, 0)
    private val hourly = (1..36).toList() + (39..144 step 3).toList() + (150..264 step 6).toList()

    @Test fun cycleNames() {
        val c = NbmCycle(2026, 10, 30, 7)
        assertEquals("2026-10-30 07Z", c.label)
        assertEquals("07Z", c.shortLabel)
        assertEquals("blendv5.0/conus/2026/10/30/0700/", c.prefix)
        assertEquals(c, NbmCycle.ofEpoch(c.epoch + 1234))
    }

    @Test fun keyTimes() {
        val k = "blendv5.0/conus/2026/10/30/0000/temp/blendv5.0_conus_temp_2026-10-30T00:00_2026-10-31T17:00.tif"
        assertEquals(run to run + 41 * 3600, nbmKeyTimes(k))
        assertEquals(run to run + 41 * 3600, nbmKeyTimes(k.replace(":", "%3A")))
        assertNull(nbmKeyTimes("blendv5.0/conus/2026/10/30/0000/temp/readme.txt"))
        assertNull(nbmKeyTimes("blendv5.0/conus/x/blendv5.0_conus_temp_2026-10-30T00:00_2026-1x-31T17:00.tif"))
        assertNull(nbmKeyTimes("short.tif"))
    }

    @Test fun parsesAListingInValidOrderAndEncodesColons() {
        val xml = listing("temp", c00, listOf(5, 1, 3))
        val files = parseNbmListing(xml)
        assertEquals(listOf(1, 3, 5), files.map { it.leadHours })
        assertEquals(857000L, files[0].size)
        assertEquals(epochOf(2026, 10, 30, 0, 41, 3), files[0].uploaded)
        assertEquals("https://noaa-nbm-pds.s3.amazonaws.com/blendv5.0/conus/2026/10/30/0000/temp/blendv5.0_conus_temp_2026-10-30T00%3A00_2026-10-30T01%3A00.tif", files[0].url)
        assertEquals(0, parseNbmListing("not xml").size)
        // a listing's own folders and other files are skipped
        assertEquals(0, parseNbmListing("<Contents><Key>blendv5.0/conus/2026/10/30/0000/temp/x.txt</Key></Contents>").size)
    }

    @Test fun runHoursFromADayListing() {
        val xml = """<ListBucketResult><CommonPrefixes><Prefix>blendv5.0/conus/2026/10/30/0000/</Prefix></CommonPrefixes>
            <CommonPrefixes><Prefix>blendv5.0/conus/2026/10/30/1700/</Prefix></CommonPrefixes><CommonPrefixes><Prefix>blendv5.0/conus/2026/10/30/0100/</Prefix></CommonPrefixes>
            <CommonPrefixes><Prefix>blendv5.0/conus/2026/10/30/notes/</Prefix></CommonPrefixes></ListBucketResult>"""
        assertEquals(listOf(0, 1, 17), parseNbmRunHours(xml))
        assertEquals(emptyList<Int>(), parseNbmRunHours("nothing"))
    }

    @Test fun cycleNotesSayWhichRunsAreExtended() {
        assertEquals("extended, to day 11", nbmCycleNote(264, true))
        assertEquals("extended, to day 11", nbmCycleNote(260, true))
        assertEquals("extended, to day 7", nbmCycleNote(168, true))
        assertEquals("short range, to 36 h", nbmCycleNote(36, true))
        assertEquals("not ready", nbmCycleNote(null, true))
        assertEquals("checking…", nbmCycleNote(null, false))
        assertTrue(nbmIsExtended(264))
        assertFalse(nbmIsExtended(167))
        assertFalse(nbmIsExtended(null))
    }

    @Test fun dwellFollowsTheFrameSpacing() {
        val frames = parseNbmListing(listing("temp", c00, hourly))
        assertEquals(1, nbmStepHours(frames, 0))
        assertEquals(1, nbmStepHours(frames, 34))
        assertEquals(3, nbmStepHours(frames, 36))     // +36 h -> +39 h
        assertEquals(6, nbmStepHours(frames, frames.size - 2))
        assertEquals(6, nbmStepHours(frames, frames.size - 1))     // the last frame takes the step before it
        assertEquals(150L, nbmDwellMillis(frames, 0, 150))
        assertEquals(900L, nbmDwellMillis(frames, frames.size - 2, 150))
        assertEquals(1, nbmStepHours(frames.take(1), 0))
        // a day of forecast plays for the same time at every spacing: 24 hourly frames, 8 three-hourly ones, 4 six-hourly ones
        val day = 24 * 150L
        assertEquals(day, (0 until 24).sumOf { nbmDwellMillis(frames, it, 150) })
        assertEquals(day, (36 until 44).sumOf { nbmDwellMillis(frames, it, 150) })
        val sixHourly = frames.indices.filter { nbmStepHours(frames, it) == 6 && frames[it].leadHours in 150..173 }
        assertEquals(4, sixHourly.size)
        assertEquals(day, sixHourly.sumOf { nbmDwellMillis(frames, it, 150) })
    }

    @Test fun loopSpansAndWindows() {
        val frames = parseNbmListing(listing("temp", c00, hourly))
        val total = ((frames.last().valid - frames.first().valid) / 3600).toInt()
        assertEquals(263, total)
        assertEquals(listOf("12 h", "24 h", "2 days", "3 days", "5 days", "7 days", "all"), nbmLoopSpans(total).map { it.label })
        assertEquals(listOf("12 h", "all"), nbmLoopSpans(20).map { it.label })
        assertEquals(listOf("all"), nbmLoopSpans(6).map { it.label })
        assertEquals(0 until frames.size, nbmLoopWindow(frames, 0))
        // 24 h after the first frame (+1 h): the frames up to +25 h
        assertEquals(0..24, nbmLoopWindow(frames, 24))
        assertEquals(47, nbmLoopWindow(frames, 72).last)          // the frame at +72 h
        assertEquals(0..1, nbmLoopWindow(parseNbmListing(listing("temp", c00, listOf(6, 30, 54))), 1))      // never fewer than two frames
        assertTrue(nbmLoopWindow(emptyList(), 24).isEmpty())
    }

    @Test fun picksTheProductsByPickerId() {
        assertEquals("temp", NbmCatalog.default.id)
        assertEquals("nbm_temp", NbmCatalog.default.pickerId)
        assertEquals("qpf06", NbmCatalog.byPickerId("nbm_qpf06")?.id)
        assertNull(NbmCatalog.byId("nope"))
        assertEquals(NbmCatalog.all.size, NbmCatalog.all.map { it.id }.toSet().size)
        assertTrue(NbmCatalog.all.all { it.group in NbmCatalog.groups })
        assertTrue(NbmCatalog.groups.all { g -> NbmCatalog.all.any { it.group == g } })
    }

    private class FakeHttp(val handler: (String) -> HttpResult) : Http {
        val urls = ArrayList<String>()
        override suspend fun get(url: String): HttpResult { urls += url; return handler(url) }
    }

    @Test fun listsCyclesNewestFirstWithinTheWindow() = runTest {
        val now = epochOf(2026, 10, 30, 17, 43, 0)
        val http = FakeHttp { url ->
            val day = url.substringAfter("conus/").substringBefore("/&").substringBefore("&")
            val hours = when {
                "2026/10/30/" in url -> listOf(0, 1, 16, 17, 18)       // 18Z is in the future of "now": left out
                "2026/10/29/" in url -> listOf(0, 12, 23)
                "2026/10/28/" in url -> listOf(23)
                else -> emptyList()
            }
            val prefixes = hours.joinToString("") { "<CommonPrefixes><Prefix>blendv5.0/conus/$day${it.toString().padStart(2, '0')}00/</Prefix></CommonPrefixes>" }
            HttpResult(200, "<ListBucketResult>$prefixes</ListBucketResult>".encodeToByteArray())
        }
        val cycles = NbmRepository(http, StubCore).listCycles(now, hours = 36)
        // 36 h before 17:43 on the 30th is 05:43 on the 29th: the 29th's 00Z is too old, the 30th's 18Z is not there yet
        assertEquals(
            listOf(NbmCycle(2026, 10, 30, 17), NbmCycle(2026, 10, 30, 16), NbmCycle(2026, 10, 30, 1), NbmCycle(2026, 10, 30, 0), NbmCycle(2026, 10, 29, 23), NbmCycle(2026, 10, 29, 12)),
            cycles,
        )
        assertTrue(http.urls.all { "delimiter=/" in it && it.startsWith("https://noaa-nbm-pds.s3.amazonaws.com/?list-type=2") })
    }

    @Test fun listsFramesOfAField() = runTest {
        val http = FakeHttp { HttpResult(200, listing("pop12", c00, listOf(12, 18, 24)).encodeToByteArray()) }
        val frames = NbmRepository(http, StubCore).listFrames("pop12", c00)
        assertEquals(listOf(12, 18, 24), frames.map { it.leadHours })
        assertTrue(http.urls.single().contains("prefix=blendv5.0/conus/2026/10/30/0000/pop12/"))
        val failing = FakeHttp { HttpResult(500, ByteArray(0)) }
        assertFailsWith<RefsException> { NbmRepository(failing, StubCore).listFrames("pop12", c00) }
    }

    @Test fun lastLeadAsksForTheFramesBeyondSevenDays() = runTest {
        val http = FakeHttp { url ->
            val after = url.substringAfter("start-after=").replace("%3A", ":")
            // frames after the given key, as the bucket lists them
            val leads = hourly.filter { stamp(c00.epoch + it * 3600L) > after.substringAfterLast("_").removeSuffix(".tif") }
            HttpResult(200, listing("temp", c00, leads).encodeToByteArray())
        }
        assertEquals(264, NbmRepository(http, StubCore).lastLead("temp", c00))
        assertEquals(1, http.urls.size)                          // an extended run is known after one small request
        assertTrue(http.urls[0].contains("start-after=") && http.urls[0].contains("_2026-11-05T23%3A00.tif"))      // lists from the frames after +167 h

        val short = (1..36).toList()
        val shortHttp = FakeHttp { url ->
            val after = url.substringAfter("start-after=").replace("%3A", ":").substringAfterLast("_").removeSuffix(".tif")
            HttpResult(200, listing("temp", c00, short.filter { stamp(c00.epoch + it * 3600L) > after }).encodeToByteArray())
        }
        assertEquals(36, NbmRepository(shortHttp, StubCore).lastLead("temp", c00))
        assertEquals(2, shortHttp.urls.size)                     // a short run needs the second request

        val none = FakeHttp { HttpResult(200, "<ListBucketResult></ListBucketResult>".encodeToByteArray()) }
        assertNull(NbmRepository(none, StubCore).lastLead("temp", c00))
        assertNull(NbmRepository(FakeHttp { HttpResult(404, ByteArray(0)) }, StubCore).lastLead("temp", c00))
    }

    @Test fun loadsAndDecodesAFrame() = runTest {
        val grid = Grid(2, 3, 2)
        val core = object : Core by StubCore { override fun decodeGeotiff(file: ByteArray) = GridField(grid, FloatArray(6) { it.toFloat() }) }
        val file = parseNbmListing(listing("temp", c00, listOf(1))).single()
        val http = FakeHttp { HttpResult(200, byteArrayOf(1, 2, 3)) }
        val field = NbmRepository(http, core).loadFrame(file)
        assertEquals(grid, field.grid)
        assertEquals(file.url, http.urls.single())
        assertFailsWith<RefsException> { NbmRepository(FakeHttp { HttpResult(404, ByteArray(0)) }, core).loadFrame(file) }
        assertFailsWith<RefsException> { NbmRepository(FakeHttp { HttpResult(200, ByteArray(0)) }, core).loadFrame(file) }
        // a decoder that rejects the bytes is reported with the product's own words
        val bad = object : Core by StubCore { override fun decodeGeotiff(file: ByteArray): GridField = throw IllegalStateException("damaged") }
        val e = assertFailsWith<RefsException> { NbmRepository(http, bad).loadFrame(file) }
        assertNotNull(e.message)
        assertTrue("damaged" in e.message!!)
    }
}
