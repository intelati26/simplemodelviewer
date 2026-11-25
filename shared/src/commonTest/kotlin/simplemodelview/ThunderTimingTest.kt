package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThunderTimingTest {
    private val t0 = 1_000_000L
    private fun hours(vararg p: Double) = p.mapIndexed { i, v -> HourChance(t0 + (i + 1) * 3600L, v) }

    @Test fun onlyHoursAfterNowAreKept() {
        val h = hours(5.0, 10.0, 20.0, 30.0)
        assertEquals(listOf(10.0, 20.0, 30.0), ThunderTiming.upcoming(h, t0 + 3600L).map { it.percent })
        assertEquals(2, ThunderTiming.upcoming(h, t0, count = 2).size)
    }

    @Test fun theFirstHourOverTheLineAndThePeak() {
        val s = ThunderTiming.summarize(hours(5.0, 12.0, 28.0, 45.0, 30.0), 25)
        assertEquals(t0 + 3 * 3600L, s.firstOver!!.valid); assertEquals(45.0, s.peak!!.percent, 0.0)
        assertEquals("Past your 25% line from about H3", ThunderTiming.headline(s) { "H${(it - t0) / 3600}" })
    }

    @Test fun underTheLineSaysHowHighItGets() {
        val s = ThunderTiming.summarize(hours(5.0, 12.0, 20.0, 8.0), 25)
        assertNull(s.firstOver)
        assertEquals("Under your 25% line through H4 (highest 20% around H3)", ThunderTiming.headline(s) { "H${(it - t0) / 3600}" })
        assertEquals("No thunder chance could be read", ThunderTiming.headline(ThunderTiming.summarize(emptyList(), 25)) { "" })
    }

    @Test fun risingAndFalling() {
        assertEquals("up from 30%", ThunderTiming.rise(45.0, 30.0)); assertEquals("down from 55%", ThunderTiming.rise(40.0, 55.0))
        assertNull(ThunderTiming.rise(32.0, 30.0)); assertNull(ThunderTiming.rise(null, 30.0)); assertNull(ThunderTiming.rise(30.0, Double.NaN))
    }
}
