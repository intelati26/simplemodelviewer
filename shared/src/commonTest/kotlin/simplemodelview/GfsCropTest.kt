package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GfsCropTest {
    private val run = NbmCycle(2026, 11, 6, 12).epoch
    private val file = NbmFile("gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f120", run, run + 120 * 3600L, null, 500_000_000L, grib = true, field = "gfs", bucketUrl = GfsBucket.URL)

    @Test fun theFilterRequestNamesTheFieldsLevelsAndBox() {
        val url = nomadsGfsUrl(file, listOf(GfsMessage("HGT", "500 mb"), GfsMessage("UGRD", "500 mb"), GfsMessage("TMP", "2 m above ground")), doubleArrayOf(-125.0, 24.0, -66.5, 50.0))!!
        assertTrue(url.startsWith("https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl?dir=%2Fgfs.20261106%2F12%2Fatmos&file=gfs.t12z.pgrb2.0p25.f120"))
        assertTrue("&var_HGT=on" in url && "&var_UGRD=on" in url && "&var_TMP=on" in url)
        assertTrue("&lev_500_mb=on" in url && "&lev_2_m_above_ground=on" in url)
        assertTrue(url.endsWith("&subregion=&toplat=50&leftlon=235&rightlon=293.5&bottomlat=24"))
        assertNull(nomadsGfsUrl(file, listOf(GfsMessage("NOPE", "500 mb")), doubleArrayOf(0.0, 0.0, 1.0, 1.0)))
        // the whole-atmosphere level has parentheses, sent encoded
        assertTrue("lev_entire_atmosphere_%28considered_as_a_single_layer%29=on" in nomadsGfsUrl(file, listOf(GfsMessage("PWAT", "entire atmosphere (considered as a single layer)")), doubleArrayOf(-125.0, 24.0, -66.5, 50.0))!!)
        // a box on the other side of the date line is sent as east longitudes too
        assertTrue("leftlon=190" in nomadsGfsUrl(file, listOf(GfsMessage("HGT", "500 mb")), doubleArrayOf(-170.0, 8.0, -50.0, 72.0))!!)
    }

    @Test fun fieldsAreIdentifiedByParameterAndLevel() {
        val hgt = gfsCropSpec(GfsMessage("HGT", "500 mb"))!!
        assertTrue(GribMeta(3, 5, 100, 50000).matches(hgt))
        assertTrue(!GribMeta(3, 5, 100, 70000).matches(hgt))
        assertTrue(GribMeta(0, 0, 103, 2).matches(gfsCropSpec(GfsMessage("TMP", "2 m above ground"))!!))
        assertTrue(GribMeta(3, 1, 101, 0).matches(gfsCropSpec(GfsMessage("PRMSL", "mean sea level"))!!))
        assertNull(gfsCropSpec(GfsMessage("HGT", "somewhere")))
    }

    private fun message(tag: Int): ByteArray {
        val length = 24
        val b = ByteArray(length)
        "GRIB".encodeToByteArray().copyInto(b)
        b[7] = 2
        b[15] = length.toByte()
        b[16] = tag.toByte()
        return b
    }

    @Test fun aFileOfSeveralMessagesIsSplit() {
        val parts = splitGribMessages(byteArrayOf(0, 0) + message(1) + message(2) + message(3) + byteArrayOf(9, 9))     // junk around is skipped
        assertEquals(listOf(1, 2, 3), parts.map { it[16].toInt() })
        assertEquals(emptyList(), splitGribMessages(ByteArray(10)))
        assertEquals(1, splitGribMessages(message(7) + message(8).copyOf(10)).size)                                       // a cut-off message is dropped
    }

    // a core that says which message it decoded by its tag byte
    private class TagCore : Core by StubCore {
        val metas = mapOf(1 to GribMeta(3, 5, 100, 50000), 2 to GribMeta(2, 2, 100, 50000), 3 to GribMeta(2, 3, 100, 50000))
        override fun decodeGrib(message: ByteArray) = GridField(Grid(5, 2, 1), floatArrayOf(message[16].toFloat(), 0f), metas[message[16].toInt()])
        override fun reproject(values: FloatArray, from: Grid, to: Grid) = values
        override fun gridLongitudes(grid: Grid) = FloatArray(2) { -97.5f }
    }

    private class FakeHttp(val handler: (String) -> HttpResult) : Http {
        val urls = ArrayList<String>()
        override suspend fun get(url: String): HttpResult { urls += url; return handler(url) }
        override suspend fun getRange(url: String, first: Long, lastInclusive: Long): HttpResult { urls += "$url [$first-$lastInclusive]"; return handler(url) }
    }

    @Test fun aWindFrameIsOneCroppedRequestAndFallsBackToAws() = runTest {
        val region = doubleArrayOf(-125.0, 24.0, -66.5, 50.0)
        val ok = FakeHttp { HttpResult(200, message(3) + message(1) + message(2) + ByteArray(120)) }      // v, height, u, in any order
        val steps = ArrayList<String>()
        val planes = GfsRepository(ok, TagCore()).loadWind(file, 500, "speed", Grid(9, 2, 1), { steps += it }, region)
        assertEquals(1, ok.urls.size)
        assertTrue(ok.urls[0].contains("nomads.ncep.noaa.gov") && "var_VGRD=on" in ok.urls[0])
        assertEquals(8, planes.size)                                          // four planes of two cells
        assertEquals(1f, planes[2], 1e-4f)                                    // the height plane is the tag-1 message
        // not delivered: the AWS route (index, then ranges) is used
        val idx = "1:100:d=2026110612:HGT:500 mb:120 hour fcst:\n2:200:d=2026110612:UGRD:500 mb:120 hour fcst:\n3:300:d=2026110612:VGRD:500 mb:120 hour fcst:\n4:400:d=2026110612:PRMSL:mean sea level:120 hour fcst:\n"
        val aws = FakeHttp { url ->
            when {
                url.contains("nomads") -> HttpResult(503, ByteArray(0))
                url.endsWith(".idx") -> HttpResult(200, idx.encodeToByteArray())
                else -> HttpResult(206, ByteArray(300) { 1 })                 // 100..399: the three messages touch, so one range
            }
        }
        val awsSteps = ArrayList<String>()
        val awsCore = object : Core by StubCore {
            override fun decodeGrib(message: ByteArray) = GridField(Grid(5, 2, 1), floatArrayOf(1f, 2f))
            override fun reproject(values: FloatArray, from: Grid, to: Grid) = values
            override fun gridLongitudes(grid: Grid) = FloatArray(2) { -97.5f }
        }
        GfsRepository(aws, awsCore).loadWind(file, 500, "speed", Grid(9, 2, 1), { awsSteps += it }, region)
        assertTrue(awsSteps.any { it.startsWith("NOMADS:") && "Using AWS" in it })
        assertTrue(aws.urls.any { it.endsWith("[100-399]") })
        assertNotNull(aws.urls.firstOrNull { it.endsWith(".idx") })
    }

    @Test fun theRunTimeIsReadFromAnEncodedFilterUrl() {
        val url = nomadsGfsUrl(file, listOf(GfsMessage("HGT", "500 mb")), doubleArrayOf(-125.0, 24.0, -66.5, 50.0))!!
        assertEquals(run, gribSourceInitEpoch(url))
    }
}
