package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnderlayTest {
    @Test
    fun relativeHumidityFromTemperatureAndDewpoint() {
        assertEquals(100f, Underlay.relativeHumidity(20f, 20f), 0.01f)                     // saturated
        assertEquals(50f, Underlay.relativeHumidity(20f, 9.3f), 1.0f)                      // about half
        assertEquals(100f, Underlay.relativeHumidity(10f, 12f), 0.01f)                     // a dewpoint above the temperature is capped
        assertTrue(Underlay.relativeHumidity(Float.NaN, 5f).isNaN())
        assertEquals(2, Underlay.relativeHumidity(floatArrayOf(10f, 20f, 30f), floatArrayOf(5f, 10f)).size)
    }

    @Test
    fun theSurfaceLinesHaveTheirOwnColorsAndIntervals() {
        assertEquals(0xB3261E, Underlay.temperatureSpec(false).color); assertEquals(10.0, Underlay.temperatureSpec(false).interval)
        assertTrue(Underlay.temperatureSpec(false).isBold(32.0)); assertTrue(Underlay.temperatureSpec(true).isBold(0.0))
        assertTrue(Underlay.humiditySpec().isBold(50.0)); assertTrue(!Underlay.humiditySpec().isBold(60.0))
        assertEquals(Underlay.humiditySpec(), Contours.specFor("rh_anl", "%"))                  // the analysis page's humidity gets its lines too
        assertEquals(0x1F5FBF, Underlay.humiditySpec().color)
    }

    @Test
    fun rtmaFileNames() {
        val t = epochOf(2026, 11, 17, 12, 7, 0)
        assertEquals("https://noaa-rtma-pds.s3.amazonaws.com/rtma2p5_ru.20261117/rtma2p5_ru.t1200z.2dvaranl_ndfd.grb2", RtmaBucket.analysisUrl(t))
        assertEquals("https://noaa-rtma-pds.s3.amazonaws.com/rtma2p5_ru.20261117/rtma2p5_ru.t1215z.2dvaranl_ndfd.grb2", RtmaBucket.analysisUrl(t + 600))
        val c = RtmaBucket.candidates(epochOf(2026, 11, 17, 0, 10, 0))
        assertEquals(epochOf(2026, 11, 17, 0, 0, 0), c.first())
        assertEquals(epochOf(2026, 11, 16, 23, 0, 0), c.last(), "back an hour, over midnight")
    }

    @Test
    fun nbmFallbackIsTheNearestHourFromRecentRuns() {
        val f = nbmFallbacks(epochOf(2026, 11, 17, 23, 44, 0))
        assertEquals("https://noaa-nbm-grib2-pds.s3.amazonaws.com/blend.20261117/23/core/blend.t23z.core.f001.co.grib2" to 1, f.first())
        assertEquals("https://noaa-nbm-grib2-pds.s3.amazonaws.com/blend.20261117/18/core/blend.t18z.core.f006.co.grib2" to 6, f.last())
    }

    private val rtmaIndex = "1:0:d=2026111723:HGT:surface:anl:\n2:100:d=2026111723:PRES:surface:anl:\n3:200:d=2026111723:TMP:2 m above ground:anl:\n" +
        "4:300:d=2026111723:DPT:2 m above ground:anl:\n5:400:d=2026111723:UGRD:10 m above ground:anl:\n"

    private class FakeHttp(val files: Map<String, String>) : Http {
        override suspend fun get(url: String): HttpResult = files[url]?.let { HttpResult(200, it.encodeToByteArray()) } ?: HttpResult(404, ByteArray(0))
        override suspend fun getRange(url: String, first: Long, lastInclusive: Long) = HttpResult(206, ByteArray((lastInclusive - first + 1).toInt()))
    }

    // a core whose decoded field is the message's length in degrees C (so a test can tell which byte range was asked for)
    private class LengthCore : Core by StubCore {
        override fun decodeGrib(message: ByteArray) = GridField(Grid(5, 2, 2), FloatArray(4) { 273.15f + message.size })
    }

    @Test
    fun picksTheNewestAnalysisAtOrBeforeTheScan() = runTest {
        val scan = epochOf(2026, 11, 17, 23, 44, 0)
        val posted = RtmaBucket.analysisUrl(epochOf(2026, 11, 17, 23, 15, 0))       // 23:30 is not posted yet
        val repo = SurfaceRepository(FakeHttp(mapOf("$posted.idx" to rtmaIndex)), LengthCore())
        val pick = assertNotNull(repo.pick(scan, scan + 60))
        assertTrue(pick.rtma)
        assertEquals(epochOf(2026, 11, 17, 23, 15, 0), pick.valid)
        assertEquals(100f, repo.temperature(pick).values[0], 0.01f, "TMP is bytes 200..299 (and comes back in C)")
        assertEquals(100f, repo.dewpoint(pick).values[0], 0.01f)
    }

    @Test
    fun fallsBackToNbm() = runTest {
        val scan = epochOf(2026, 11, 17, 23, 44, 0)
        val nbm = nbmFallbacks(scan).first().first
        val index = "35:1000:d=2026111723:DPT:2 m above ground:1 hour fcst:\n36:1500:d=2026111723:DPT:2 m above ground:1 hour fcst:ens std dev\n" +
            "133:2000:d=2026111723:TMP:2 m above ground:1 hour fcst:\n134:2600:d=2026111723:TMP:2 m above ground:1 hour fcst:ens std dev\n"
        val repo = SurfaceRepository(FakeHttp(mapOf("$nbm.idx" to index)), LengthCore())
        val pick = assertNotNull(repo.pick(scan, scan + 60))
        assertTrue(!pick.rtma && pick.lead == 1)
        assertEquals(epochOf(2026, 11, 18, 0, 0, 0), pick.valid)
        assertEquals(600f, repo.temperature(pick).values[0], 0.01f, "the plain TMP, not its spread")
        assertEquals(500f, repo.dewpoint(pick).values[0], 0.01f)
    }
}
