package spcrefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertsTest {
    private val rule = AlertRule("Norman, OK", 35.22, -97.44, "uh25_04h_max_overlap_max", "4-h Max UH", 75.0)
    private val t0 = Cycle(2026, 10, 1, 12).epochSeconds

    @Test fun roundTrip() {
        val back = decodeRules(encodeRules(listOf(rule, rule.copy(placeName = "x|y", threshold = 0.3))))
        assertEquals(2, back.size)
        assertEquals(rule, back[0])
        assertEquals("x/y", back[1].placeName)
        assertEquals(emptyList(), decodeRules("a|b"))
    }

    @Test fun hitWhenThresholdReached() {
        val series = PointSeries((1..4).map { t0 + it * 3600L }, listOf(10.0, 80.0, 120.0, Double.NaN), "m2/s2", 0)
        val hit = assertNotNull(rule.evaluate(series))
        assertEquals(1, hit.firstIndex)
        assertEquals(120.0, hit.peak)
        assertEquals(t0 + 3 * 3600L, hit.peakTime)
        val text = rule.message(hit, series, Cycle(2026, 10, 1, 12))
        assertTrue("Experimental guidance" in text && "Norman, OK" in text && "120 m2/s2" in text)
    }

    @Test fun noHitBelowThreshold() {
        assertNull(rule.evaluate(PointSeries(listOf(t0), listOf(74.9), "m2/s2", 0)))
        assertNull(rule.evaluate(PointSeries(listOf(t0), listOf(Double.NaN), "m2/s2", 0)))
    }
}
