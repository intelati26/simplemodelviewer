package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PointSummaryTest {
    private val t0 = NbmCycle(2026, 11, 12, 0).epoch          // 00Z of the 12th
    private fun col(hour: Int, vararg v: Pair<String, Int?>) = PointColumn(t0 + hour * 3600L, "NBS", v.toMap())
    // UTC days stand in for the local ones
    private val dayOf: (Long) -> Int = { Math.floorDiv(it, 86400L).toInt() }

    @Test fun aDayTakesItsHighLowRainAndWind() {
        val table = PointTable("KOKC", listOf(
            col(6, "TMP" to 40, "SKY" to 20, "WSP" to 5, "P06" to 10),
            col(18, "TMP" to 62, "SKY" to 60, "WSP" to 12, "GST" to 20, "P06" to 40, "Q06" to 10),
            col(21, "TMP" to 55, "SKY" to 80, "WSP" to 9, "P06" to 70, "Q06" to 25),
        ))
        val d = PointSummary.days(table, dayOf, t0).single()
        assertEquals(62, d.high); assertEquals(40, d.low); assertEquals(70, d.pop); assertEquals(12, d.windKt); assertEquals(20, d.gustKt)
        assertEquals(35, d.rainHundredths); assertEquals(53, d.sky)
    }

    @Test fun theBulletinsOwnMaximumAt00ZBelongsToTheDayBefore() {
        val table = PointTable("KOKC", listOf(col(12, "TMP" to 40), col(24, "TXN" to 71, "TMP" to 60)))      // 00Z of the 13th: the 12th's afternoon
        val days = PointSummary.days(table, dayOf, t0)
        assertEquals(2, days.size)
        assertEquals(71, days[0].high)
        assertEquals(60, days[1].high)
    }

    @Test fun columnsBeforeTheStartAreLeftOutAndSkyHasWords() {
        val table = PointTable("KOKC", listOf(col(0, "TMP" to 10), col(30, "TMP" to 20)))
        assertEquals(listOf(20), PointSummary.days(table, dayOf, t0 + 12 * 3600L).map { it.high })
        assertNull(PointSummary.days(table, dayOf, t0 + 40 * 3600L).firstOrNull())
        assertEquals("Mostly cloudy", PointSummary.skyWords(70)); assertEquals("Clear", PointSummary.skyWords(10))
    }
}
