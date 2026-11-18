package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RainSinceTest {
    private fun t(d: Int, h: Int, m: Int) = epochOf(2026, 11, d, h, m, 0)

    @Test
    fun wholeHoursAreMultiSensorAndTheEdgesRadar() {
        // from 11-16 17:20 (-> 17:15) to 11-18 12:04 (newest 15 min total 12:00), multi-sensor to 11:00
        val p = RainSince.plan(t(16, 17, 20), t(18, 11, 0), t(18, 12, 4))
        assertEquals(t(16, 17, 15), p.start); assertEquals(t(18, 12, 0), p.end)
        assertEquals(t(16, 18, 0), p.multiFrom); assertEquals(t(18, 11, 0), p.multiTo)
        // the pieces tile the span exactly: no gap, no overlap
        var at = p.start
        for (x in p.pieces) { assertEquals(at, x.start, "contiguous"); at = x.end }
        assertEquals(p.end, at)
        val multi = p.pieces.filter { it.multiSensor }
        assertEquals(41 * 60, multi.sumOf { it.minutes })                  // 18Z to 11Z two days later
        assertEquals(listOf(1, 1, 3, 12, 24), multi.map { it.minutes / 60 }, "few files (MRMS has no 2 h total)")
        assertEquals(listOf(15, 15, 15), p.pieces.takeWhile { it.minutes == 15 }.map { it.minutes })   // 17:15 -> 18:00
        assertEquals(60, p.pieces.last().minutes, "11 -> 12 radar-only hour")
        assertEquals(false, p.pieces.last().multiSensor)
    }

    @Test
    fun noMultiSensorYetMeansRadarOnly() {
        val p = RainSince.plan(t(18, 9, 0), null, t(18, 12, 0))
        assertTrue(p.pieces.none { it.multiSensor })
        assertEquals(listOf(180), p.pieces.map { it.minutes })
    }

    @Test
    fun shortSpans() {
        assertEquals(listOf(15, 15), RainSince.plan(t(18, 11, 30), t(18, 11, 0), t(18, 12, 4)).pieces.map { it.minutes })
        assertTrue(RainSince.plan(t(18, 12, 10), null, t(18, 12, 4)).pieces.isEmpty())
    }

    @Test
    fun keysAndFallbacks() {
        assertEquals("CONUS/RadarOnly_QPE_15M_00.00/20261118/MRMS_RadarOnly_QPE_15M_00.00_20261118-120000.grib2.gz", RainSince.key("RadarOnly_QPE_15M_00.00", t(18, 12, 0)))
        assertEquals(listOf("MultiSensor_QPE_24H_Pass2_00.00", "MultiSensor_QPE_24H_Pass1_00.00", "RadarOnly_QPE_24H_00.00"), RainSince.Piece(t(18, 0, 0), 24 * 60, true).directories)
    }

    @Test
    fun addingKeepsMissingAsNothing() {
        val a = floatArrayOf(1f, Float.NaN, Float.NaN)
        RainSince.addInto(a, floatArrayOf(2f, 3f, Float.NaN))
        assertEquals(3f, a[0]); assertEquals(3f, a[1]); assertTrue(a[2].isNaN())
    }
}
