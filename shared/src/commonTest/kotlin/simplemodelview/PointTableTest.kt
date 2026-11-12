package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointTableTest {
    private val noon = NbmCycle(2026, 11, 12, 12).epoch

    private fun table(kind: String, first: Long, stepHours: Int, count: Int, temp: Int) =
        NbmTextTable("KOKC", kind, first, (0 until count).map { it * stepHours }, mapOf("TMP" to (0 until count).map { temp + it }, "S12" to List(count) { 0 }))

    @Test fun theSourcesFollowOneAnotherByValidTime() {
        val api = table("API", noon, 3, 2, 50)                          // 12Z, 15Z
        val nbs = table("NBS", noon + 6 * 3600L, 3, 23, 60)             // 18Z of the 12th to 72 h after the run (12Z of the 15th)
        val nbe = table("NBE", noon + 24 * 3600L, 12, 15, 40)           // 12Z of the 13th: overlaps the short range up to the 15th 12Z
        val nbx = table("NBX", noon + 204 * 3600L, 12, 6, 70)
        val t = buildPointTable("KOKC", api, nbs, nbe, nbx)
        val sources = t.columns.map { it.source }
        assertEquals(listOf("API", "API") + List(23) { "NBS" }, sources.take(25))
        // the extended bulletin gives only what is after the last short-range column (72 h after the run)
        val firstNbe = t.columns.first { it.source == "NBE" }
        assertEquals(noon + 84 * 3600L, firstNbe.valid)
        assertEquals(noon + 192 * 3600L, t.columns.last { it.source == "NBE" }.valid)
        assertEquals(noon + 204 * 3600L, t.columns.first { it.source == "NBX" }.valid)
        assertEquals(noon + 264 * 3600L, t.columns.last().valid)
        assertEquals(t.columns.map { it.valid }, t.columns.map { it.valid }.sorted())
        assertEquals(t.columns.size, t.columns.map { it.valid }.toSet().size)
    }

    @Test fun columnsBeforeTheStartAreLeftOut() {
        val api = table("API", noon, 3, 2, 50)
        val nbs = table("NBS", noon + 6 * 3600L, 3, 4, 60)
        val t = buildPointTable("KOKC", api, nbs, null, null, fromEpoch = noon + 4 * 3600L)
        assertEquals(listOf(noon + 6 * 3600L, noon + 9 * 3600L, noon + 12 * 3600L, noon + 15 * 3600L), t.columns.map { it.valid }.let { listOf(it[0], it[1], it[2], it[3]) })
        assertEquals(4, t.columns.size)
    }

    @Test fun aMissingSourceIsSkippedAndTheValuesCarryTheirCodes() {
        val nbx = table("NBX", noon + 204 * 3600L, 12, 6, 70)
        val t = buildPointTable("KOKC", null, null, null, nbx)
        assertEquals(6, t.columns.size)
        assertEquals(70, t.columns.first().value("TMP"))
        assertEquals(null, t.columns.first().value("DPT"))
        assertFalse(t.hasAny("S12"))
        assertTrue(t.hasAny("TMP"))
        assertEquals(noon + 252 * 3600L, t.nearest(noon + 250 * 3600L)!!.valid)
    }

    @Test fun columnsWithNothingInThemAreLeftOut() {
        // a super-extended bulletin that lists hours past the end of the blend: the temperature, sky, wind and chance rows are blank there
        val nbx = NbmTextTable("KOKC", "NBX", noon, listOf(204, 216, 372), mapOf("TMP" to listOf(70, 71, null), "P24" to listOf(null, null, 12)))
        val t = buildPointTable("KOKC", null, null, null, nbx)
        assertEquals(listOf(noon + 204 * 3600L, noon + 216 * 3600L), t.columns.map { it.valid })
    }

    @Test fun daysAndHoursAreLabelledInUtc() {
        val t = buildPointTable("KOKC", null, table("NBS", noon + 6 * 3600L, 3, 6, 60), null, null)
        // 18Z, 21Z of the 12th, then 00Z, 03Z, 06Z, 09Z of the 13th
        assertEquals(listOf(0 to "Thu 12 Nov", 2 to "Fri 13 Nov"), pointDays(t))
        assertEquals("18Z", pointHourLabel(t.columns[0]))
        assertEquals("00Z", pointHourLabel(t.columns[2]))
    }
}
