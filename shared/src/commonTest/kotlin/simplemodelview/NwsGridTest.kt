package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NwsGridTest {
    // run-length encoded as the API sends it: a value stands for its duration
    private val grid = """{"properties":{"updateTime":"2026-11-12T06:26:51+00:00",
      "temperature":{"uom":"wmoUnit:degC","values":[
         {"validTime":"2026-11-12T12:00:00+00:00/PT3H","value":10},
         {"validTime":"2026-11-12T15:00:00+00:00/PT2H","value":15.5},
         {"validTime":"2026-11-12T17:00:00+00:00/PT1H","value":20}]},
      "dewpoint":{"uom":"wmoUnit:degC","values":[{"validTime":"2026-11-12T12:00:00+00:00/P1D","value":5}]},
      "skyCover":{"uom":"wmoUnit:percent","values":[{"validTime":"2026-11-12T12:00:00+00:00/PT6H","value":34.4}]},
      "windDirection":{"uom":"wmoUnit:degree_(angle)","values":[{"validTime":"2026-11-12T12:00:00+00:00/PT3H","value":2},{"validTime":"2026-11-12T15:00:00+00:00/PT3H","value":340}]},
      "windSpeed":{"uom":"wmoUnit:km_h-1","values":[{"validTime":"2026-11-12T12:00:00+00:00/PT6H","value":18.52}]},
      "windGust":{"uom":"wmoUnit:km_h-1","values":[{"validTime":"2026-11-12T12:00:00+00:00/PT1H","value":30}]},
      "weather":{"values":[{"validTime":"2026-11-12T12:00:00+00:00/PT6H","value":[{"weather":null}]}]}}}"""

    @Test fun durationsAreRead() {
        assertEquals(3600L, parseIsoDuration("PT1H"))
        assertEquals(108000L, parseIsoDuration("P1DT6H"))
        assertEquals(86400L, parseIsoDuration("P1D"))
        assertEquals(1800L, parseIsoDuration("PT30M"))
        assertNull(parseIsoDuration("soon"))
        assertNull(parseIsoDuration("P"))
    }

    @Test fun aValueStandsUntilTheNextOne() {
        val g = parseNwsGrid(grid)!!
        val noon = NbmCycle(2026, 11, 12, 12).epoch
        assertEquals(10.0, g.valueAt("temperature", noon))
        assertEquals(10.0, g.valueAt("temperature", noon + 3 * 3600L - 1))
        assertEquals(15.5, g.valueAt("temperature", noon + 3 * 3600L))
        assertNull(g.valueAt("temperature", noon + 6 * 3600L))      // after the last interval
        assertNull(g.valueAt("pressure", noon))
        assertEquals(noon - 5 * 3600L - 33 * 60L - 9L, g.updated)    // 06:26:51Z
        assertNull(parseNwsGrid("{}"))
        assertNull(parseNwsGrid("nope"))
    }

    @Test fun theGapBeforeTheShortRangeBulletinIsFilledInTheBulletinsUnits() {
        val g = parseNwsGrid(grid)!!
        val noon = NbmCycle(2026, 11, 12, 12).epoch
        // from 11:10 Z (the next 3-hour step is 12Z) up to the bulletin's first column at 18Z: the 12Z and 15Z columns
        val t = nwsGapTable(g, noon - 3000, noon + 6 * 3600L, "KOKC")!!
        assertEquals("API", t.kind)
        assertEquals("KOKC", t.station)
        assertEquals(listOf(noon, noon + 3 * 3600L), t.validTimes)
        assertEquals(listOf<Int?>(50, 60), t.values("TMP"))          // 10 C, 15.5 C
        assertEquals(listOf<Int?>(41, 41), t.values("DPT"))
        assertEquals(listOf<Int?>(34, 34), t.values("SKY"))
        assertEquals(listOf<Int?>(36, 34), t.values("WDR"))          // 2 degrees is "north" (36), 340 degrees is 34
        assertEquals(listOf<Int?>(10, 10), t.values("WSP"))          // 18.52 km/h = 10 kt
        assertEquals(listOf<Int?>(16, null), t.values("GST"))        // the gust interval is one hour long
        assertNull(nwsGapTable(g, noon + 6 * 3600L, noon + 6 * 3600L))
    }
}
