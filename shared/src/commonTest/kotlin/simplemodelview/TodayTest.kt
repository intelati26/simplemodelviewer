package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayTest {
    private val mcd = """
040 
ACUS11 KWNS 252131
SWOMCD
SPC MCD 252130 
ILZ000-MOZ000-260300-

Mesoscale Discussion 2465
NWS Storm Prediction Center Norman OK
0330 PM CST Wed Nov 25 2026

Areas affected...Parts of southeastern Missouri and southwestern
Illinois

Concerning...Heavy snow 

Valid 252130Z - 260300Z

SUMMARY...Areas of heavy snow with rates near 1 inch per hour are
possible later this afternoon and evening.

DISCUSSION...Preceding a midlevel low tracking southeastward toward
the Ozark Plateau, snow is increasing.

Moderate to heavy snow is expected to persist.

..Weinman.. 11/25/2026

...Please see www.spc.noaa.gov for graphic product...

ATTN...WFO...PAH...LSX...SGF...

LAT...LON   37199112 37829120 38599111 38939089 39119055 39089012
            38848974 37748967 37128984 36849017 36909090 37199112

040 
ACUS11 KWNS 222056
SWOMCD
SPC MCD 222056

Mesoscale Discussion 2464
Areas affected...Portions of Central and Southern Arkansas
Concerning...Severe potential...Watch unlikely 
Valid 222056Z - 222300Z

SUMMARY...Isolated severe storms possible.

DISCUSSION...A line of storms.

..Smith.. 11/22/2026

LAT...LON   34999300 35009100 33999100 34009300 34999300
"""
    // 2026-11-25 22:00 UTC
    private val now = epochOf(2026, 11, 25, 22, 0, 0)

    @Test fun productsAreSplitAndRead() {
        val all = SpcMesoscale.parseAll(mcd, now)
        assertEquals(listOf(2465, 2464), all.map { it.number })
        val a = all[0]
        assertEquals("Parts of southeastern Missouri and southwestern Illinois", a.areas)
        assertEquals("Heavy snow", a.concerning)
        assertEquals(epochOf(2026, 11, 25, 21, 30, 0), a.validFrom); assertEquals(epochOf(2026, 11, 26, 3, 0, 0), a.validTo)
        assertTrue(a.summary.startsWith("Areas of heavy snow")); assertTrue(a.discussion.contains("Moderate to heavy snow is expected to persist."))
        assertEquals("..Weinman.. 11/25/2026", a.forecaster)
    }

    @Test fun onlyTheUnexpiredOnesAreActiveAndThePolygonIsRead() {
        val all = SpcMesoscale.parseAll(mcd, now)
        assertTrue(all[0].activeAt(now)); assertFalse(all[1].activeAt(now))
        assertEquals(24, all[0].polygon.size)
        assertEquals(37.19, all[0].polygon[0], 1e-9); assertEquals(-91.12, all[0].polygon[1], 1e-9)
        assertTrue(all[0].covers(38.0, -90.5)); assertFalse(all[0].covers(35.2, -97.4))
        assertFalse(all[1].isWatchRelated)
    }

    @Test fun aValidTimeAcrossMidnightEndsOnTheNextDay() {
        val d = SpcMesoscale.parse(mcd.substringBefore("040 \nACUS11 KWNS 222056").replace("040 \nACUS11", "ACUS11"), now)
        assertNotNull(d); assertTrue(d.validTo > d.validFrom)
    }

    @Test fun categoriesBecomeNumbersAndSentences() {
        assertEquals(2, TodayText.level("Slight Risk")); assertEquals(0, TodayText.level("General Thunderstorms")); assertEquals(0, TodayText.level(null)); assertEquals(5, TodayText.level("High Risk"))
        assertEquals("Slight risk (2 of 5) of severe storms", TodayText.sentence(2)); assertEquals("No severe risk here", TodayText.sentence(0))
        assertEquals("Marginal risk (1 of 5) of severe storms today", TodayText.sentence(1, " today"))
    }

    @Test fun theTopThreatIsTheHighestChance() {
        assertEquals(2, TodayText.percent("2% Tornado Risk")); assertNull(TodayText.percent("Slight Risk"))
        assertEquals("Hail" to 15, TodayText.topThreat(2, 10, 15)); assertEquals("Tornado" to 5, TodayText.topThreat(5, 5, 5))
        assertNull(TodayText.topThreat(null, 0, null))
        assertEquals("Damaging wind is the top threat here (15%)", TodayText.topThreatLine("Wind" to 15)); assertNull(TodayText.topThreatLine(null))
        assertEquals("today", TodayText.dayName(1, 3)); assertEquals("Friday", TodayText.dayName(3, 5))
    }
}
