package simplemodelview

import simplemodelview.SoundingFixtures.analysis
import simplemodelview.SoundingFixtures.build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharpEngineTest {
    @Test fun saturationAndMixingRatioFollowWobus() {
        assertEquals(23.4, SharpThermo.vaporPressure(20.0), 0.2)                                    // mb
        assertEquals(14.9, SharpThermo.mixingRatio(1000.0, 20.0), 0.3)                              // g/kg at 20 C, 1000 mb
        assertEquals(12.0, SharpThermo.tempAtMixingRatio(SharpThermo.mixingRatio(900.0, 12.0), 900.0), 0.2)
        assertTrue(SharpThermo.virtualTemp(1000.0, 20.0, 20.0) - 20.0 in 2.0..3.2)                  // saturated air at 20 C is a couple of degrees warmer in virtual temperature
    }

    @Test fun aWarmMoistLayerUnderColdAirHasCapeAnLfcAndAnEl() {
        val a = analysis(SoundingFixtures.unstable)
        assertTrue(a.sb.cape in 800.0..4500.0, "cape ${a.sb.cape}")
        assertTrue(a.sb.lfcHght >= a.sb.lclHght - 1.0 && a.sb.elHght > 6000.0, "lfc ${a.sb.lfcHght} el ${a.sb.elHght}")
        assertTrue(a.sb.cin <= 0.0 && a.sb.liftedIndex500 < 0.0 && a.sb.cape3km > 0.0)
        assertTrue(a.ml.cape < a.sb.cape && a.mu.cape >= a.sb.cape - 1.0)                           // a mixed layer is cooler and drier than the surface
        assertTrue(a.sb.tracePres.size > 10 && a.sb.tracePres.first() > a.sb.tracePres.last())
    }

    @Test fun aStableProfileHasNoCape() {
        val a = analysis(SoundingFixtures.stable)
        assertEquals(0.0, a.sb.cape, 1e-9); assertTrue(gone(a.sb.lfcHght)); assertTrue(a.sb.liftedIndex500 > 0.0)
        assertNull(a.effective.takeIf { it.valid })
    }

    @Test fun theEffectiveLayerAndTheCompositesOfASupercellEnvironment() {
        val a = analysis(SoundingFixtures.supercell)
        assertTrue(a.effective.valid && a.effective.botAgl < 1.0 && a.effective.topAgl > 500.0, "eil ${a.effective.botAgl}-${a.effective.topAgl}")
        assertTrue(a.effectiveSrh > 100.0 && a.effectiveShearKt > 40.0, "esrh ${a.effectiveSrh} ebwd ${a.effectiveShearKt}")
        assertTrue(a.supercell > 1.0 && a.stpEffective > 0.5 && a.sigSevere > 10_000.0 && a.hail in 0.0..6.0, "scp ${a.supercell} stp ${a.stpEffective} sigsvr ${a.sigSevere} ship ${a.hail}")
        assertTrue(a.rightMover.valid && a.leftMover.valid)
        assertTrue(a.srh01 > 20.0 && a.kin6.shear.speed() > 30.0, "srh01 ${a.srh01} shear6 ${a.kin6.shear.speed()}")
        val none = analysis(SoundingFixtures.stable)
        assertEquals(0.0, none.stpEffective, 1e-9); assertEquals(0.0, none.supercell, 1e-9)
    }

    @Test fun theCompositesFollowTheirDefinitions() {
        assertEquals(0.0, SharpIndices.stpEffective(1500.0, 200.0, 10.0, 500.0, -10.0), 1e-9)       // too little shear
        assertEquals(0.0, SharpIndices.stpEffective(1500.0, -50.0, 25.0, 500.0, -10.0), 1e-9)       // negative helicity
        assertEquals(1.0 * (200.0 / 150.0) * (25.0 / 20.0), SharpIndices.stpEffective(1500.0, 200.0, 25.0, 500.0, -10.0), 1e-9)
        assertEquals(0.0, SharpIndices.stpEffective(1500.0, 200.0, 25.0, 2100.0, -10.0), 1e-9)      // a high LCL
        assertEquals(2.0 * SharpIndices.supercellComposite(1000.0, 100.0, 20.0), SharpIndices.supercellComposite(1000.0, 200.0, 20.0), 1e-9)
        assertEquals(0.0, SharpIndices.supercellComposite(1000.0, 100.0, 5.0), 1e-9)
        assertEquals(0.0, SharpIndices.stpFixed(1500.0, 500.0, 150.0, 10.0), 1e-9)
    }

    @Test fun aStraightWesterlyHodographHasTheStormMotionSouthOfTheMeanWind() {
        val a = analysis(build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { 270.0 }, kt = { z -> minOf(z, 6000.0) / 6000.0 * 30.0 / 0.514444 }))
        assertTrue(a.rightMover.v < -5.0 && a.leftMover.v > 5.0)                                    // 7.5 m/s each way across the shear
        assertEquals(30.0 / 0.514444, a.kin6.shear.speed(), 1.0)
        assertEquals(270.0, a.kin6.mean.direction(), 0.5)
    }

    @Test fun theAdvectionFollowsSharppysFormulaByHand() {
        val p = SharpProfile.of(build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { z -> 180.0 + 90.0 * minOf(z / 1500.0, 1.0) }, kt = { 20.0 }), 35.0)!!
        val first = SharpIndices.inferredAdvection(p, 35.0).first()
        assertEquals(p.pres[0], first.pBottom, 1e-6); assertEquals(p.pres[0] - 100.0, first.pTop, 1e-6)
        val f = 2.0 * (2.0 * Math.PI / 86164.0) * kotlin.math.sin(35.0 * Math.PI / 180.0)
        val z = p.interpHght(first.pTop) - p.interpHght(first.pBottom)
        val expected = (f / 9.81) * (Math.PI / 180.0) * 9.0 * 9.0 * (2.0 * (298.15 + 298.15 - 6.5 * z / 1000.0)) * (54.0 / z) * 3600.0
        assertTrue(first.cPerHour > 0.0 && first.cPerHour in expected * 0.6..expected * 1.6, "got ${first.cPerHour}, hand $expected")
        val back = SharpProfile.of(build(1000.0, { z -> 25.0 - 6.5 * z / 1000.0 }, { z -> 15.0 - 6.0 * z / 1000.0 }, dir = { z -> 180.0 - 90.0 * minOf(z / 1500.0, 1.0) }, kt = { 20.0 }), 35.0)!!
        assertEquals(-first.cPerHour, SharpIndices.inferredAdvection(back, 35.0).first().cPerHour, kotlin.math.abs(first.cPerHour) * 0.05)
        assertEquals(kotlin.math.sin(70.0 * Math.PI / 180.0) / kotlin.math.sin(35.0 * Math.PI / 180.0), SharpIndices.inferredAdvection(p, 70.0).first().cPerHour / first.cPerHour, 0.01)
    }

    @Test fun precipitationTypeFollowsTheSourceLayerAndTheWarmAndColdAreas() {
        assertEquals("Snow", analysis(build(1000.0, { z -> -6.0 - 6.0 * z / 1000.0 }, { z -> -6.5 - 6.0 * z / 1000.0 })).precip.type)
        assertEquals("Rain", analysis(build(1000.0, { z -> 12.0 - 6.0 * z / 1000.0 }, { z -> 11.5 - 6.0 * z / 1000.0 })).precip.type)
        assertEquals("None", analysis(build(1000.0, { z -> -6.0 - 6.0 * z / 1000.0 }, { z -> -30.0 - 6.0 * z / 1000.0 })).precip.type)
        val nose = analysis(build(1000.0, { z -> if (z < 1000.0) -3.0 + 0.5 * z / 1000.0 else if (z < 3000.0) 2.0 else 2.0 - 7.0 * (z - 3000.0) / 1000.0 }, { z -> (if (z < 3000.0) 1.5 else -5.0) - 0.5 * z / 1000.0 })).precip
        assertTrue(nose.positiveArea > 0.0 && nose.negativeArea < 0.0 && nose.type in setOf("Freezing Rain", "Sleet", "Rain"), "type ${nose.type} ${nose.positiveArea} ${nose.negativeArea}")
    }

    @Test fun theLowLevelPiecesOfAnAnalysisAreFilled() {
        val a = analysis(SoundingFixtures.supercell)
        assertTrue(a.lowRh in 0.0..100.0 && a.midRh in 0.0..100.0 && a.lowRh > a.midRh)
        assertTrue(a.thetaEIndex > 0.0 && a.dcape > -9998.0 && a.convectiveTemp > -9998.0 && a.fcst.valid && a.maxTempC > 25.0)
        assertTrue(a.corfidi.valid && a.brnShear > 0.0 && a.criticalAngle in 0.0..180.0 && a.kin8.shear.valid && a.kinCloud.mean.valid && a.kinEff.mean.valid && a.kinEbwd.shear.valid)
        assertTrue(a.freezingLevelAgl > 3000.0 && a.precipitableWaterIn > 0.5 && a.esp > -9998.0 && a.mmp in 0.0..1.0)
        assertTrue(a.advection.isNotEmpty() && a.advection.all { it.cPerHour.isFinite() })
    }

    @Test fun aSoundingOfAFewLevelsHasNoProfile() {
        assertNull(SharpProfile.of(Sounding(0, 0, SoundingFixtures.unstable.levels.take(3), emptyMap())))
    }

    @Test fun levelsWithoutADewpointHighUpAreKept() {
        val cut = Sounding(0, 0, SoundingFixtures.unstable.levels.map { if (it.pressure < 300.0) it.copy(dewC = Double.NaN) else it }, emptyMap())
        assertEquals(assertNotNull(SharpProfile.of(SoundingFixtures.unstable)).size, assertNotNull(SharpProfile.of(cut)).size)
        assertEquals(analysis(SoundingFixtures.unstable).sb.cape, analysis(cut).sb.cape, 150.0)
    }

    @Test fun theTornadoProbabilitiesComeFromTheirTables() {
        assertEquals(0.0, SharpTornadoProb.fromMlCape(0.0).probability, 1e-9)
        assertEquals(0.15, SharpTornadoProb.fromMlCape(1200.0).probability, 1e-9)
        assertEquals(0.58, SharpTornadoProb.fromStpEffective(12.0).probability, 1e-9); assertEquals(5, SharpTornadoProb.fromStpEffective(12.0).colour)
        assertEquals(-1.0, SharpTornadoProb.fromEffectiveSrh(-9999.0).probability, 1e-9)
        assertEquals(0.19, SharpTornadoProb.fromMlLcl(900.0).probability, 1e-9)
    }

    @Test fun theSarsDatabasesAreLoaded() {
        assertEquals(938, SharpSars.supercellDatabase.size); assertEquals(1148, SharpSars.hailDatabase.size)
        assertEquals("00042320.TXK", SharpSars.supercellDatabase.first().id)
        val r = SharpSars.supercell(1700.0, 650.0, -14.0, 6.5, 62.0, 134.0, 33.0, 77.0, 166.0)      // the first record's own numbers match it
        assertTrue(r.valid && r.looseMatches > 0 && r.quality.any { it.id == "00042320.TXK" })
    }
}
