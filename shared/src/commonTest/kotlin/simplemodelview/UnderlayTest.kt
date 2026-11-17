package simplemodelview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnderlayTest {
    @Test
    fun saturatedAirHasItsTemperatureAsDewpoint() {
        assertEquals(20.0, Underlay.dewpoint(20.0, 20.0, 1000.0), 0.01)
        assertEquals(5.0, Underlay.dewpoint(5.0, 5.3, 900.0), 0.01)       // a wet-bulb above the temperature (rounding) is saturated
    }

    @Test
    fun matchesPsychrometricTables() {
        // 30 C with a 20 C wet-bulb at 1000 hPa: tables give a dewpoint of about 14.6 C
        assertEquals(14.6, Underlay.dewpoint(30.0, 20.0, 1000.0), 0.5)
        // 10 C with a 5 C wet-bulb: about -1.5 C
        assertEquals(-1.5, Underlay.dewpoint(10.0, 5.0, 1000.0), 0.5)
    }

    @Test
    fun lowerPressureMeansMoreMoistureForTheSameWetBulb() {
        val sea = Underlay.dewpoint(30.0, 15.0, 1013.0)
        val denver = Underlay.dewpoint(30.0, 15.0, Underlay.standardPressure(1609.0))
        assertTrue(denver - sea > 1.5, "at altitude the same wet-bulb depression means a higher dewpoint ($sea vs $denver)")
        assertTrue(abs(Underlay.dewpoint(30.0, 25.0, 1000.0) - Underlay.dewpoint(30.0, 25.0, 990.0)) < 0.2, "10 hPa barely matters")
    }

    @Test
    fun standardAtmosphere() {
        assertEquals(1013.25, Underlay.standardPressure(0.0), 0.01)
        assertEquals(835.0, Underlay.standardPressure(1609.0), 3.0)
        val p = Underlay.pressureFromHeights(floatArrayOf(Float.NaN, 0f))
        assertEquals(1013.25f, p[0], 0.01f)
    }

    @Test
    fun fieldKeepsMissingCells() {
        val td = Underlay.dewpointField(floatArrayOf(20f, Float.NaN, 25f), floatArrayOf(15f, 10f, Float.NaN), null)
        assertTrue(!td[0].isNaN() && td[0] < 15f)
        assertTrue(td[1].isNaN() && td[2].isNaN())
    }

    @Test
    fun rtmaFileNames() {
        val t = epochOf(2026, 11, 17, 12, 7, 0)
        assertEquals("https://noaa-rtma-pds.s3.amazonaws.com/rtma2p5_ru.20261117/rtma2p5_ru.t1200z.2dvaranl_ndfd.grb2", RtmaBucket.analysisUrl(t))
        assertEquals("https://noaa-rtma-pds.s3.amazonaws.com/rtma2p5_ru.20261117/rtma2p5_ru.t1215z.2dvaranl_ndfd.grb2", RtmaBucket.analysisUrl(t + 600))
    }

    @Test
    fun terrainRoundTrip() {
        val heights = floatArrayOf(0f, 1609.4f, Float.NaN, -12f, 4300f, 3121f)
        val back = decodeTerrain(encodeTerrain(heights, 3, 2), 3, 2)!!
        assertEquals(1609f, back[1]); assertTrue(back[2].isNaN()); assertEquals(-12f, back[3]); assertEquals(4300f, back[4])
        assertEquals(null, decodeTerrain(encodeTerrain(heights, 3, 2), 2, 3), "another grid's file is not read")
    }

    @Test
    fun smoothingAveragesNeighbors() {
        val v = smooth3(floatArrayOf(0f, 0f, 0f, 0f, 900f, 0f, 0f, 0f, 0f), 3, 3)
        assertEquals(100f, v[4], 0.01f)
        assertEquals(225f, v[0], 0.01f)
    }

    @Test
    fun surfaceFileIsTheNearestWithinThreeHoursBefore() {
        fun f(h: Int) = MrmsFile("k$h", epochOf(2026, 11, 17, h, 0, 0), null, 0L)
        val files = listOf(f(17), f(18), f(19), f(20), f(21), f(22))
        // live: 23:44 has only the 22:00 file (they arrive ~85 minutes late)
        assertEquals("k22", Underlay.fileFor(files, epochOf(2026, 11, 17, 23, 44, 0))?.key)
        assertEquals("k20", Underlay.fileFor(files, epochOf(2026, 11, 17, 19, 40, 0))?.key)
        assertEquals(null, Underlay.fileFor(files, epochOf(2026, 11, 18, 2, 0, 0)))
    }
}
