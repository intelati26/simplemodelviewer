package simplemodelview

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundingMathTest {
    /** A profile built up from the ground in 250 m steps: pressure from the hydrostatic equation, temperature and dewpoint from the given functions of height. */
    private fun build(p0: Double, temp: (Double) -> Double, dew: (Double) -> Double, dir: (Double) -> Double = { 270.0 }, kt: (Double) -> Double = { 10.0 }, top: Double = 16000.0): Sounding {
        val levels = ArrayList<SoundingLevel>()
        var p = p0; var z = 0.0
        while (z <= top) {
            val t = temp(z)
            levels += SoundingLevel(p, t, Double.NaN, minOf(dew(z), t), dir(z), kt(z), z)
            val tMean = (t + temp(z + 250.0)) / 2 + 273.15
            p *= exp(-9.80665 * 250.0 / (287.04 * tMean)); z += 250.0
        }
        return Sounding(0, 0, levels, emptyMap())
    }

    private val unstable = build(1000.0, { z -> maxOf(30.0 - 6.5 * z / 1000.0, -70.0) }, { z -> 22.0 - 5.0 * z / 1000.0 })
    private val stable = build(1000.0, { z -> if (z < 1000) 10.0 + 4.0 * z / 1000.0 else maxOf(14.0 - 6.0 * (z - 1000.0) / 1000.0, -60.0) }, { z -> 5.0 - 2.0 * z / 1000.0 })

    @Test fun saturationAndMixingRatio() {
        assertEquals(23.37, SoundingMath.es(20.0), 0.1)                       // mb
        assertEquals(0.01475, SoundingMath.mixingRatio(1000.0, 20.0), 0.0003)    // 14.7 g/kg at 20 C, 1000 mb
        val w = SoundingMath.mixingRatio(900.0, 12.0)
        assertEquals(12.0, SoundingMath.dewpointFromMixing(900.0, w), 0.01)
    }

    @Test fun theLclFollowsTheDewpointSpread() {
        val a = SoundingMath.analyze(unstable, 0.0)!!
        // about 125 m for each degree of spread: 8 C -> a kilometer
        assertEquals(1000.0, a.surface.lclZ!!, 120.0)
    }

    @Test fun aWarmMoistLayerUnderColdAirHasCapeAndAnLfc() {
        val a = SoundingMath.analyze(unstable, 0.0)!!
        assertTrue(a.surface.cape in 800.0..4000.0, "cape ${a.surface.cape}")
        val lfc = assertNotNull(a.surface.lfcZ); val el = assertNotNull(a.surface.elZ)
        assertTrue(lfc >= a.surface.lclZ!! - 1.0 && el > 6000.0 && el > lfc, "lfc $lfc el $el")
        assertTrue(a.surface.cin <= 0.0)
        assertTrue(a.surface.li!! < 0.0)                                       // unstable: the parcel is warmer than the air at 500 mb
        assertTrue(a.mixed.cape < a.surface.cape && a.mostUnstable.cape >= a.surface.cape - 1.0)   // a mixed layer is cooler and drier than the surface
    }

    @Test fun aCapedStableProfileHasNone() {
        val a = SoundingMath.analyze(stable, 0.0)!!
        assertEquals(0.0, a.surface.cape, 1e-9); assertNull(a.surface.lfcZ); assertNull(a.surface.elZ)
        assertTrue(a.surface.li!! > 0.0)
    }

    @Test fun theParcelTraceCoolsWithHeight() {
        val t = SoundingMath.analyze(unstable, 0.0)!!.surface.trace
        assertTrue(t.first().first > t.last().first)
        assertTrue(t.first().second > t.last().second)
        assertEquals(30.0, t.first().second, 1e-6)
    }

    @Test fun precipitableWaterAndKIndex() {
        val a = SoundingMath.analyze(unstable, 0.0)!!
        assertTrue(a.pwMm in 25.0..70.0, "pw ${a.pwMm}")
        assertNotNull(a.kIndex)
        assertEquals(6.5, a.lapse03!!, 0.2); assertEquals(6.5, a.lapse36!!, 0.2)
        assertEquals(6.5, a.lapse700500!!, 0.4)
    }

    @Test fun theFreezingLevel() {
        val a = SoundingMath.analyze(unstable, 0.0)!!
        assertEquals(30.0 / 6.5 * 1000.0, a.freezingM!!, 150.0)                // 4.6 km
        assertEquals(-20.0, a.profile.tAt(a.profile.pAt(a.minus20M!!)), 0.3)
        assertNull(a.wetBulbZeroM)                                              // no wet bulb in this profile
    }

    @Test fun aStraightWesterlyHodographHasTheStormMotionSouthOfTheMeanWind() {
        val s = build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { 270.0 }, kt = { z -> minOf(z, 6000.0) / 6000.0 * 30.0 / 0.514444 })
        val k = SoundingMath.analyze(s, 0.0)!!.kinematics!!
        assertEquals(15.0 / 0.514444, k.mean06.kt, 1.5)                          // the 0-6 km mean is half the 30 m/s
        assertTrue(k.rightMover.v < -5.0 && k.leftMover.v > 5.0)                 // the deviation is 7.5 m/s each way, across the shear
        assertEquals(k.mean06.u, k.rightMover.u, 0.2)
        assertEquals(225.0, k.layers[2].srh, 15.0)                               // 7.5 m/s x 30 m/s over 0-6 km
        assertEquals(112.5, k.layers[1].srh, 10.0)
        assertEquals(30.0 / 0.514444, k.layers[2].shearKt, 1.0)
        assertEquals(30.0, k.shear6Ms, 0.5)
        assertEquals(270.0, k.layers[2].mean.dir, 0.5)
    }

    @Test fun theTornadoParameterNeedsEveryIngredient() {
        val warm = SoundingMath.analyze(unstable, 0.0)!!
        assertEquals(0.0, SoundingMath.stpFixed(warm.surface, warm.mixed, 150.0, 10.0), 1e-9)    // too little shear
        assertEquals(0.0, SoundingMath.stpFixed(warm.surface, warm.mixed, 0.0, 25.0), 1e-9)      // no helicity
        assertTrue(SoundingMath.stpFixed(warm.surface, warm.mixed, 200.0, 25.0) > 0.0)
        assertEquals(0.0, SoundingMath.stpFixed(SoundingMath.analyze(stable, 0.0)!!.surface, warm.mixed, 200.0, 25.0), 1e-9)   // no CAPE
    }

    @Test fun aShortProfileGivesNothing() {
        assertNull(SoundingMath.analyze(Sounding(0, 0, unstable.levels.take(3), emptyMap()), 0.0))
        assertTrue(hypot(3.0, 4.0) == 5.0)
    }
}
