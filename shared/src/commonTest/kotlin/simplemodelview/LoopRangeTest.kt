package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopRangeTest {
    private val hourly = (0..23).map { 864_000L + it * 3600L }
    private val scans = (0..29).map { 864_000L + it * 120L }       // 2-minute radar scans

    @Test fun spansAreWritten() {
        assertEquals("45 min", loopSpanLabel(2700)); assertEquals("1 h", loopSpanLabel(3600)); assertEquals("1 h 20 min", loopSpanLabel(4800)); assertEquals("3 days", loopSpanLabel(3 * 86400L))
    }

    @Test fun theOptionsAreShorterThanTheSeries() {
        assertEquals(listOf(900L, 1800L), loopSpanOptions(scans))                      // 58 minutes of scans
        assertEquals(listOf(3600L, 3 * 3600L, 6 * 3600L, 12 * 3600L), loopSpanOptions(hourly).filter { it >= 3600 && it <= 12 * 3600 })
        assertEquals(emptyList(), loopSpanOptions(hourly.take(2)))
        assertEquals(3600L, loopSpanOptions(hourly).first())                            // nothing shorter than the hourly step
    }

    @Test fun aSixteenDayModelRunOffersTheLongSpans() {
        val gfs = (0..128).map { 864_000L + it * 3 * 3600L }                          // 16 days in 3-hour steps
        assertEquals(listOf(5L, 7L, 10L, 14L), loopSpanOptions(gfs).filter { it >= 5 * 86400L }.map { it / 86400L })
        assertEquals(emptyList<Long>(), loopSpanOptions((0..40).map { 864_000L + it * 3 * 3600L }).filter { it >= 5 * 86400L }.map { it / 86400L })   // a 5-day series stops short of its own length
    }

    @Test fun framesNearTheEndsAndAllBetween() {
        assertEquals(3..8, loopIndices(hourly, hourly[3], hourly[8]))
        assertEquals(3..8, loopIndices(hourly, hourly[3] + 600, hourly[8] - 600))      // the nearest frames
        assertEquals(5..6, loopIndices(hourly, hourly[5], hourly[5] + 60))             // at least two frames
        assertNull(loopIndices(hourly, hourly.last() + 10 * 3600L, hourly.last() + 12 * 3600L))   // outside the series
    }

    @Test fun aLoopEndingAtTheFrameShownOrStartingAtIt() {
        assertEquals(hourly[7] to hourly[10], loopTimesFor(hourly, 10, 3 * 3600L, endingAtShown = true))
        assertEquals(hourly[10] to hourly[13], loopTimesFor(hourly, 10, 3 * 3600L, endingAtShown = false))
        assertEquals(hourly[22] to hourly[23], loopTimesFor(hourly, 23, 3 * 3600L, endingAtShown = false))   // at the end there is only the frame before
        assertEquals(hourly[0] to hourly[1], loopTimesFor(hourly, 0, 3 * 3600L, endingAtShown = true))      // at the start, the frame after
        assertEquals(scans[20] to scans[29], loopTimesFor(scans, 29, 1080L, endingAtShown = true))
        assertNull(loopTimesFor(hourly.take(1), 0, 3600L, endingAtShown = true))
    }

    @Test fun theSummaryNamesTheTimesFramesAndSpan() {
        assertEquals("03:00Z \u2192 06:00Z  \u00B7  4 frames  \u00B7  3 h", loopRangeSummary(hourly, 3..6))
        assertEquals(true, loopRangeSummary(scans, 20..29).endsWith("10 frames  \u00B7  18 min"))
    }
}
