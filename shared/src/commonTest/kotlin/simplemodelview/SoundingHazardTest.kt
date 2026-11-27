package simplemodelview

import simplemodelview.SoundingHazard.Params
import simplemodelview.SoundingHazard.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundingHazardTest {
    // a sounding with an effective inflow layer at the ground and little inhibition, the other parameters set by each test
    private fun p(f: (Params) -> Params = { it }) =
        f(Params(hasInflowLayer = true, inflowBase = 0.0, mlCin = -10.0, muCin = -10.0, sbLcl = 500.0, mlLcl = 700.0))

    private fun type(params: Params, mean: Double = Double.NaN, sigma: Double = Double.NaN) = SoundingHazard.classify(params, mean, sigma)

    @Test fun everyTornadoStepHasItsOutcome() {
        val pds = type(p { it.copy(stpE = 4.0, stpF = 4.0, srh01 = 300.0, esrh = 300.0, srw46 = 20.0, bwd08 = 50.0, lr01 = 6.0, rhMean = 70.0) })
        assertEquals("PDS TOR", pds.type); assertEquals("L1", pds.deciding!!.id)
        val tor = type(p { it.copy(stpE = 3.2, stpF = 1.0, srh01 = 100.0) })
        assertEquals("TOR", tor.type); assertEquals("L2", tor.deciding!!.id)
        val shear = type(p { it.copy(stpE = 1.2, srw46 = 20.0) })
        assertEquals("TOR", shear.type); assertEquals("L3", shear.deciding!!.id)
        val humid = type(p { it.copy(stpE = 1.2, rhMean = 65.0, lr01 = 5.5) })
        assertEquals("TOR", humid.type); assertEquals("L4", humid.deciding!!.id)
        val marginal = type(p { it.copy(stpE = 1.2, stpF = 0.3) })
        assertEquals("MRGL TOR", marginal.type); assertEquals("L5", marginal.deciding!!.id)
        val l6 = type(p { it.copy(stpE = 0.2, stpF = 0.6, srh01 = 160.0, mlCin = -40.0) })
        assertEquals("MRGL TOR", l6.type); assertEquals("L6", l6.deciding!!.id)
    }

    @Test fun theSevereColumnCountsFromTheBottomUpOnlyWhenNoTornadoHolds() {
        assertEquals("SVR", type(p { it.copy(scp = 5.0) }).type)
        assertEquals("R2", type(p { it.copy(scp = 2.5, ship = 1.2) }).deciding!!.id)
        assertEquals("R2", type(p { it.copy(dcape = 800.0) }).deciding!!.id)
        assertEquals("R3", type(p { it.copy(sigSvr = 31000.0, mmp = 0.7) }).deciding!!.id)
        assertEquals("MRGL SVR", type(p { it.copy(ship = 0.6) }).type)
        assertEquals("MRGL SVR", type(p { it.copy(wndg = 0.6, muCin = -70.0) }).type)
        assertEquals("NONE", type(p { it.copy(wndg = 0.6, muCin = -80.0) }).type)                  // too much inhibition for the marginal step
        // a tornado step wins when both hold
        assertEquals("TOR", type(p { it.copy(scp = 5.0, stpE = 3.2, stpF = 1.0) }).type)
    }

    @Test fun noInflowLayerMeansNoTornadoButNotNoSevere() {
        val params = Params(hasInflowLayer = false, stpE = 4.0, stpF = 4.0, srh01 = 300.0, esrh = 300.0, srw46 = 20.0, bwd08 = 50.0, lr01 = 6.0, rhMean = 70.0, mlCin = -10.0, muCin = -10.0, sbLcl = 500.0, mlLcl = 700.0)
        val r = type(params)
        assertEquals("SVR", r.type)                                                                // STPF >= 1 gives the severe step
        val l1 = r.nodes.first { it.id == "L1" }
        assertTrue(l1.reached && !l1.passed)
        assertEquals("no layer", l1.lines.last().value)
    }

    @Test fun theLaterStepsAreNotReachedOnceOneHolds() {
        val r = type(p { it.copy(stpE = 3.2, stpF = 1.0, srh01 = 100.0) })
        val ids = r.nodes.map { it.id }
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5", "L6", "R1", "R2", "R3", "R4", "R5", "R6", "R7"), ids)
        assertTrue(r.nodes.first { it.id == "L1" }.reached)
        assertTrue(r.nodes.filter { it.id != "L1" && it.id != "L2" }.none { it.reached })
    }

    @Test fun flashFloodNeedsTheClimatologyAndASlowSystem() {
        val wet = p { it.copy(pwIn = 2.0, upshear = 5.0) }
        assertEquals("FLASH FLOOD", type(wet, 1.0, 0.2).type)
        assertEquals("NONE", type(wet).type)                                                        // no climatology: the test cannot say
        val test = type(wet).nodes.first { it.id == "R5" }.lines.first()
        assertEquals(State.Unknown, test.state); assertTrue("no climatology" in test.value)
        assertEquals("NONE", type(p { it.copy(pwIn = 2.0, upshear = 30.0) }, 1.0, 0.2).type)       // a fast system
        assertEquals("NONE", type(p { it.copy(pwIn = 1.3, upshear = 5.0) }, 1.0, 0.2).type)        // under mean + 2 sigma = 1.4
    }

    @Test fun blizzardAndHeat() {
        assertEquals("BLIZZARD", type(p { it.copy(sfcWindMph = 40.0, sfcTempC = -5.0, precipType = "Snow") }).type)
        assertEquals("NONE", type(p { it.copy(sfcWindMph = 40.0, sfcTempC = -5.0, precipType = null) }).type)     // the precipitation type is not worked out here
        assertEquals("NONE", type(p { it.copy(sfcWindMph = 40.0, sfcTempC = -5.0, precipType = "Rain") }).type)
        assertEquals("EXCESSIVE HEAT", type(p { it.copy(heatIndexF = 110.0) }).type)
        assertEquals("NONE", type(p { it.copy(heatIndexF = 100.0) }).type)
    }

    @Test fun nothingHoldingGivesNoneAndKeepsEveryValue() {
        val r = type(p())
        assertEquals("NONE", r.type); assertNull(r.deciding)
        assertEquals(13, r.nodes.size)
        assertTrue(r.nodes.all { it.reached && !it.passed })
        assertEquals("--", r.nodes.first().lines.first().value)                                     // STPE: the sounding has none
        assertEquals("-10", r.nodes.first().lines.first { it.text.startsWith("MLCINH") }.value)
    }

    @Test fun theHeatIndexFollowsTheNwsTable() {
        assertEquals(90.0, SharpTab.heatIndex(85.0, 60.0), 1.0)                                     // the NWS table: 85 F at 60 % is 90 F
        assertEquals(106.0, SharpTab.heatIndex(90.0, 70.0), 1.0)
        assertEquals(109.0, SharpTab.heatIndex(100.0, 40.0), 1.0)
        assertEquals(121.0, SharpTab.heatIndex(96.0, 65.0), 1.0)
        assertTrue(SharpTab.heatIndex(68.0, 40.0) < 70.0)                                           // cool air: close to the temperature
    }

    @Test fun theParametersOfASupercellEnvironmentFillTheChart() {
        val s = Sounding(0, 0, (0..64).map { k ->
            val z = k * 250.0
            val t = maxOf(30.0 - 7.0 * z / 1000.0, -70.0)
            SoundingLevel(1000.0 * kotlin.math.exp(-9.80665 * z / (287.04 * 275.0)), t, Double.NaN, minOf(23.0 - 7.8 * z / 1000.0, t), 140.0 + 130.0 * minOf(z, 6000.0) / 6000.0, 12.0 + 50.0 * minOf(z, 8000.0) / 8000.0, z)
        }, emptyMap())
        val a = assertNotNull(SoundingMath.analyze(s, 0.0, 35.0))
        val params = SoundingHazard.params(a)
        assertTrue(params.hasInflowLayer && params.inflowBase >= 0.0)
        assertTrue(listOf(params.stpE, params.stpF, params.srh01, params.esrh, params.srw46, params.bwd08, params.sbLcl, params.mlLcl, params.lr01, params.mlCin, params.rhMean,
            params.scp, params.ship, params.wndg, params.dcape, params.sigSvr, params.mmp, params.upshear, params.sfcWindMph, params.sfcTempC, params.heatIndexF, params.pwIn).none { it.isNaN() })
        val r = SoundingHazard.classify(params)
        assertTrue(r.type != "NONE", "type ${r.type}")                                              // a warm, moist, sheared, unstable profile has a hazard
        assertEquals(13, r.nodes.size)
    }
}
