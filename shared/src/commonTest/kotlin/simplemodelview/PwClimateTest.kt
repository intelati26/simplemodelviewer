package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PwClimateTest {
    @Test fun theDayOfTheYearIsCountedFromTheMonthAndDay() {
        assertEquals(0, PwClimate.dayIndex(2026, 1, 1)); assertEquals(59, PwClimate.dayIndex(2026, 3, 1)); assertEquals(59, PwClimate.dayIndex(2028, 3, 1))
        assertEquals(58, PwClimate.dayIndex(2028, 2, 29)); assertEquals(364, PwClimate.dayIndex(2026, 12, 31))
        assertEquals(PwClimate.dayIndex(2026, 11, 27), PwClimate.dayIndexOf(epochOf(2026, 11, 27, 9, 0, 0)))
    }

    @Test fun normanIsDryInJanuaryAndMoistInJuly() {
        val jan = assertNotNull(PwClimate.at(35.2, -97.4, PwClimate.dayIndex(2026, 1, 15)))
        val jul = assertNotNull(PwClimate.at(35.2, -97.4, PwClimate.dayIndex(2026, 7, 15)))
        assertTrue(jan.meanIn in 0.25..0.6 && jul.meanIn in 1.2..1.8, "jan ${jan.meanIn} jul ${jul.meanIn}")
        assertTrue(jul.sigmaIn in 0.1..0.5 && jan.sigmaIn in 0.05..0.4, "sigma jan ${jan.sigmaIn} jul ${jul.sigmaIn}")
        assertTrue(PwClimate.at(26.0, -80.0, PwClimate.dayIndex(2026, 7, 15))!!.meanIn > jul.meanIn)     // south Florida is moister still
    }

    @Test fun theValueBlendsBetweenTheStoredDaysAndWrapsAtTheYearsEnd() {
        val a = PwClimate.at(35.0, -97.5, 100)!!.meanIn; val b = PwClimate.at(35.0, -97.5, 102)!!.meanIn; val c = PwClimate.at(35.0, -97.5, 104)!!.meanIn
        assertEquals((a + c) / 2.0, b, 1e-9)                                                  // day 102 is half way between the stored days 100 and 104
        assertNotNull(PwClimate.at(35.0, -97.5, 364))
    }

    @Test fun placesOutsideTheDataHaveNoClimatology() {
        assertNull(PwClimate.at(60.0, -97.0, 10)); assertNull(PwClimate.at(35.0, 10.0, 10)); assertNull(PwClimate.at(10.0, -97.0, 10))
    }

    @Test fun aSoundingMoreThanTwoSigmaMoistThanNormalGivesTheFlashFloodStep() {
        val n = PwClimate.at(35.2, -97.4, PwClimate.dayIndex(2026, 7, 15))!!
        val base = SoundingHazard.Params(hasInflowLayer = true, inflowBase = 0.0, mlCin = -10.0, muCin = -10.0, upshear = 5.0)
        assertEquals("FLASH FLOOD", SoundingHazard.classify(base.copy(pwIn = n.meanIn + 2.0 * n.sigmaIn + 0.05), n.meanIn, n.sigmaIn).type)
        assertEquals("NONE", SoundingHazard.classify(base.copy(pwIn = n.meanIn + 1.0 * n.sigmaIn), n.meanIn, n.sigmaIn).type)
    }
}
