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

    @Test fun virtualTemperatureIsWarmerForMoistAir() {
        val moist = SoundingMath.virtualTemperatureC(1000.0, 20.0, 20.0) - 20.0           // saturated at 20 C: about 2.6 C warmer
        assertTrue(moist in 2.2..3.0, "moist $moist")
        assertEquals(0.0, SoundingMath.virtualTemperatureC(1000.0, 20.0, -40.0) - 20.0, 0.1)   // nearly dry air: the same
        // the correction raises the buoyancy of a moist parcel: more CAPE than without it
        val pr = SoundingMath.profile(unstable, 0.0)!!
        assertTrue(SoundingMath.lift(pr, "x", pr.p[0], pr.t[0], pr.td[0], true).cape > SoundingMath.lift(pr, "x", pr.p[0], pr.t[0], pr.td[0], false).cape)
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
        assertTrue(k.mean06.kt in 20.0..26.0, "mean ${k.mean06.kt}")             // pressure weighted, so under half the 30 m/s (the low, slow levels weigh more)
        assertTrue(k.rightMover.v < -5.0 && k.leftMover.v > 5.0)                 // the deviation is 7.5 m/s each way, across the shear
        assertTrue(k.rightMover.u in 12.0..15.0, "rm u ${k.rightMover.u}")       // the storm motion starts from the plain mean (on 1 mb steps, so the low levels count more)
        assertEquals(225.0, k.layers[2].srh, 15.0)                               // 7.5 m/s x 30 m/s over 0-6 km
        assertEquals(112.5, k.layers[1].srh, 10.0)
        assertEquals(30.0 / 0.514444, k.layers[2].shearKt, 1.0)
        assertEquals(30.0, k.shear6Ms, 0.5)
        assertEquals(270.0, k.layers[2].mean.dir, 0.5)
    }

    @Test fun theTornadoParameterNeedsEveryIngredient() {
        val warm = SoundingMath.analyze(unstable, 0.0)!!
        assertEquals(0.0, SoundingMath.stpFixed(warm.surface, 150.0, 10.0), 1e-9)    // too little shear
        assertEquals(0.0, SoundingMath.stpFixed(warm.surface, 0.0, 25.0), 1e-9)      // no helicity
        assertTrue(SoundingMath.stpFixed(warm.surface, 200.0, 25.0) > 0.0)
        assertEquals(0.0, SoundingMath.stpFixed(SoundingMath.analyze(stable, 0.0)!!.surface, 200.0, 25.0), 1e-9)   // no CAPE
    }

    @Test fun levelsWithoutADewpointHighUpAreKept() {
        val cut = Sounding(0, 0, unstable.levels.map { if (it.pressure < 300.0) it.copy(dewC = Double.NaN) else it }, emptyMap())
        val full = SoundingMath.profile(unstable, 0.0)!!; val kept = SoundingMath.profile(cut, 0.0)!!
        assertEquals(full.size, kept.size)                                                   // the cold, dry top is still there
        assertEquals(SoundingMath.analyze(unstable, 0.0)!!.surface.cape, SoundingMath.analyze(cut, 0.0)!!.surface.cape, 150.0)
        assertTrue(kept.z.last() > 12000.0)
    }

    @Test fun aShortProfileGivesNothing() {
        assertNull(SoundingMath.analyze(Sounding(0, 0, unstable.levels.take(3), emptyMap()), 0.0))
        assertTrue(hypot(3.0, 4.0) == 5.0)
    }

    // warm, moist and unstable under cold air aloft, the wind veering from the southeast at the ground to a strong westerly aloft
    private val supercell = build(
        1000.0, { z -> maxOf(30.0 - 7.0 * z / 1000.0, -70.0) }, { z -> 23.0 - 7.8 * z / 1000.0 },
        dir = { z -> 140.0 + 130.0 * minOf(z, 6000.0) / 6000.0 }, kt = { z -> 12.0 + 50.0 * minOf(z, 8000.0) / 8000.0 },
    )

    @Test fun theEffectiveLayerStartsWhereParcelsHaveCapeAndLittleInhibition() {
        val a = SoundingMath.analyze(supercell, 0.0, 35.0)!!
        val eil = assertNotNull(a.effective)
        assertEquals(0.0, eil.bottomZ, 1.0)                                       // surface based
        assertTrue(eil.topZ > 1000.0, "top ${eil.topZ}")
        assertNull(SoundingMath.analyze(stable, 0.0, 35.0)!!.effective)           // a capped profile has none
    }

    @Test fun aSupercellEnvironmentHasTheIngredientsTheCompositesWant() {
        val a = SoundingMath.analyze(supercell, 0.0, 35.0)!!
        assertTrue(a.effectiveSrh > 100.0, "esrh ${a.effectiveSrh}")
        assertTrue(a.effectiveShearMs!! > 20.0, "ebwd ${a.effectiveShearMs}")
        assertTrue(a.supercell > 1.0, "scp ${a.supercell}")
        assertTrue(a.stpEffective > 0.5, "stp ${a.stpEffective}")
        assertTrue(a.sigSevere!! > 10_000.0, "sigsevere ${a.sigSevere}")          // m3/s3
        assertTrue(a.ship!! in 0.0..6.0, "ship ${a.ship}")
        val none = SoundingMath.analyze(stable, 0.0, 35.0)!!
        assertEquals(0.0, none.stpEffective, 1e-9); assertEquals(0.0, none.supercell, 1e-9)
    }

    @Test fun theCompositesFollowTheirDefinitions() {
        val a = SoundingMath.analyze(supercell, 0.0)!!
        val eil = a.effective
        assertEquals(0.0, SoundingMath.stpEffective(a.mixed, 200.0, 10.0, eil), 1e-9)          // too little shear
        assertEquals(0.0, SoundingMath.stpEffective(a.mixed, 200.0, 25.0, null), 1e-9)         // no inflow layer
        assertEquals(0.0, SoundingMath.stpEffective(a.mixed, -50.0, 25.0, eil), 1e-9)          // negative helicity is no support
        val base = SoundingMath.supercellComposite(a.mostUnstable, 100.0, 20.0)
        assertEquals(2.0 * base, SoundingMath.supercellComposite(a.mostUnstable, 200.0, 20.0), 1e-6 * maxOf(1.0, base))   // linear in the helicity
        assertEquals(0.0, SoundingMath.supercellComposite(a.mostUnstable, 100.0, 5.0), 1e-9)   // shear under 10 m/s
    }

    @Test fun veeringWindsMeanWarmAdvectionAndBackingColdAdvection() {
        val veer = build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { z -> 180.0 + 90.0 * minOf(z, 6000.0) / 6000.0 }, kt = { 30.0 })
        val back = build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { z -> 270.0 - 90.0 * minOf(z, 6000.0) / 6000.0 }, kt = { 30.0 })
        val warm = SoundingMath.analyze(veer, 0.0, 40.0)!!.advection
        val cold = SoundingMath.analyze(back, 0.0, 40.0)!!.advection
        // the wind turns through the lowest 6 km only: the layers there advect, the ones above (a constant wind) do not
        assertTrue(warm.filter { it.pTop >= 500.0 }.let { l -> l.isNotEmpty() && l.all { it.cPerHour > 0.0 } }, "warm ${warm.map { it.pBottom to it.cPerHour }}")
        assertTrue(cold.filter { it.pTop >= 500.0 }.let { l -> l.isNotEmpty() && l.all { it.cPerHour < 0.0 } })
        assertTrue(warm.filter { it.pBottom <= 400.0 }.all { kotlin.math.abs(it.cPerHour) < 0.05 })
        assertTrue(warm.maxOf { it.cPerHour } in 0.2..10.0)                       // a few C per hour at most
        val straight = SoundingMath.analyze(build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, kt = { 30.0 }), 0.0, 40.0)!!.advection
        assertTrue(straight.all { kotlin.math.abs(it.cPerHour) < 1e-6 })          // a wind that does not turn advects nothing
    }

    @Test fun theMcsVectorsFollowFromTheCloudLayerWindAndTheJet() {
        // a southerly jet near 1 km under westerly winds aloft: the upwind-propagating vector points toward the south
        val s = build(1000.0, { z -> maxOf(30.0 - 6.5 * z / 1000.0, -70.0) }, { z -> 20.0 - 5.0 * z / 1000.0 },
            dir = { z -> if (z < 2000.0) 180.0 else 270.0 }, kt = { z -> if (z in 800.0..1300.0) 45.0 else if (z < 2000.0) 15.0 else 35.0 })
        val c = assertNotNull(SoundingMath.analyze(s, 0.0, 35.0)!!.corfidi)
        assertTrue(c.upwind.v < 0.0, "upwind v ${c.upwind.v}")
        assertTrue(c.downwind.kt > c.upwind.kt)
    }

    @Test fun humidityAndTheProfilesForThePanels() {
        val a = SoundingMath.analyze(supercell, 0.0, 35.0)!!
        assertTrue(a.midRh!! in 0.0..100.0 && a.lowRh!! in 0.0..100.0)
        assertTrue(a.lowRh!! > a.midRh!!)                                          // moister near the ground in this profile
        assertTrue(a.thetaE.isNotEmpty() && a.thetaE.first().first > a.thetaE.last().first)
        assertTrue(a.thetaE.first().second > 330.0)                                // a 30 C / 23 C surface is very high in theta-e
        assertEquals(49, a.stormRelative.size)
        assertTrue(a.stormRelative.all { it.second >= 0.0 })
    }

    @Test fun theAdvectionFollowsSharppysFormulaByHand() {
        // the wind 180 deg (south) at the ground and 270 deg (west) at 1.5 km, 20 kt at both; the temperature falls 6.5 C/km from 25 C
        val s = build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { z -> 180.0 + 90.0 * minOf(z / 1500.0, 1.0) }, kt = { 20.0 })
        val first = SoundingMath.analyze(s, 0.0, 35.0)!!.advection.first()
        assertEquals(1000.0, first.pBottom, 1e-6); assertEquals(900.0, first.pTop, 1e-6)       // layers of 100 mb from the surface
        // 100 mb is about 900 m here: the wind has turned 90 * 900 / 1500 = 54 degrees; a mean wind a little under 20 kt = 10 m/s
        val f = 2.0 * (2.0 * Math.PI / 86164.0) * kotlin.math.sin(35.0 * Math.PI / 180.0)
        val z = SoundingMath.analyze(s, 0.0, 35.0)!!.profile.zAt(900.0)
        val expected = (f / 9.81) * (Math.PI / 180.0) * 9.0 * 9.0 * (2.0 * (298.15 + 298.15 - 6.5 * z / 1000.0)) * (54.0 / z) * 3600.0   // speed 9 m/s +- the mean
        assertTrue(first.cPerHour > 0.0 && first.cPerHour in expected * 0.6..expected * 1.6, "got ${first.cPerHour}, hand ${expected}")
        // SHARPpy's value is much smaller than SPC's: the same wind turning the other way is the same size, cold
        val back = build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { z -> 180.0 - 90.0 * minOf(z / 1500.0, 1.0) }, kt = { 20.0 })
        assertEquals(-first.cPerHour, SoundingMath.analyze(back, 0.0, 35.0)!!.advection.first().cPerHour, kotlin.math.abs(first.cPerHour) * 0.05)
        // the latitude sets the Coriolis parameter: 70 N gives about 1.64 times 35 N
        val high = SoundingMath.analyze(s, 0.0, 70.0)!!.advection.first().cPerHour
        assertEquals(kotlin.math.sin(70.0 * Math.PI / 180.0) / kotlin.math.sin(35.0 * Math.PI / 180.0), high / first.cPerHour, 0.01)
    }
}
