package simplemodelview

/**
 * The "possible hazard type" of SHARPpy and SPC's sounding page (sharptab watch_type.py: possible_watch), as wxqt-grib walks it: the parameters of the sounding go
 * through a chain of tests, the left column for tornadoes (PDS TOR, TOR, MRGL TOR) from the top, then the right column from the bottom for severe storms (SVR,
 * MRGL SVR), flash flooding, blizzard and excessive heat; NONE when nothing holds. Every test is kept with the value it was given, so a screen can show the path taken
 * and why each step held or not. SHARPpy: BSD-3-Clause (see NOTICE).
 */
object SoundingHazard {
    enum class State { Pass, Fail, Unknown }

    /** One test: its text ("STPE >= 3"), the value the sounding gave ("1.2", "--" when it has none) and whether it held. */
    class Condition(val text: String, val value: String, val state: State)

    /** One step of the chart: its tests (all must hold), what it gives when it does, and whether the walk got to it and it held. */
    class Node(val id: String, val outcome: String, val lines: List<Condition>, val reached: Boolean, val passed: Boolean)

    class Result(val type: String, val nodes: List<Node>, val pwKnown: Boolean) {
        /** The node that gave the type, or null for NONE. */
        val deciding: Node? get() = nodes.firstOrNull { it.passed }
    }

    /** The parameters the chart reads, NaN when the sounding has none. Units as the chart's text: kt, m, J/kg, C/km, percent. */
    data class Params(
        val stpE: Double = Double.NaN, val stpF: Double = Double.NaN, val srh01: Double = Double.NaN, val esrh: Double = Double.NaN, val srw46: Double = Double.NaN,
        val bwd08: Double = Double.NaN, val sbLcl: Double = Double.NaN, val mlLcl: Double = Double.NaN, val lr01: Double = Double.NaN, val mlCin: Double = Double.NaN,
        val muCin: Double = Double.NaN, val hasInflowLayer: Boolean = false, val inflowBase: Double = Double.NaN, val rhMean: Double = Double.NaN, val scp: Double = Double.NaN,
        val ship: Double = Double.NaN, val wndg: Double = Double.NaN, val dcape: Double = Double.NaN, val sigSvr: Double = Double.NaN, val mmp: Double = Double.NaN,
        val upshear: Double = Double.NaN, val sfcWindMph: Double = Double.NaN, val sfcTempC: Double = Double.NaN, val heatIndexF: Double = Double.NaN, val pwIn: Double = Double.NaN,
        /** The best-guess precipitation type ("Snow", "Rain" ...), or null when it is not worked out (the blizzard test then cannot hold). */
        val precipType: String? = null,
    )

    /** wxqt's -9999 for a missing number is NaN here. */
    private fun nn(v: Double) = if (gone(v)) Double.NaN else v

    /** The chart's parameters of an [analysis], read as wxqt's SoundingHazard::classify reads them. */
    fun params(a: SharpAnalysis, precipType: String? = a.precip.type): Params {
        val p = a.profile
        val low = a.lowRh; val mid = a.midRh
        val sfcWind = p.wspd[p.sfc]
        val sfcT = p.tmpc[p.sfc]
        return Params(
            stpE = nn(a.stpEffective), stpF = nn(a.stpFixed), srh01 = nn(a.srh01), esrh = nn(a.effectiveSrh), srw46 = nn(a.kin46.stormRelative.speed()), bwd08 = nn(a.kin8.shear.speed()),
            sbLcl = if (a.sb.valid) nn(a.sb.lclHght) else Double.NaN, mlLcl = if (a.ml.valid) nn(a.ml.lclHght) else Double.NaN, lr01 = nn(SharpIndices.lapseRateAgl(p, 0.0, 1000.0)),
            mlCin = if (a.ml.valid) a.ml.cin else Double.NaN, muCin = if (a.mu.valid) a.mu.cin else Double.NaN,
            hasInflowLayer = a.effective.valid, inflowBase = if (a.effective.valid) a.effective.botAgl else Double.NaN,
            rhMean = if (!gone(low) && !gone(mid)) (low + mid) / 2.0 else Double.NaN, scp = nn(a.supercell), ship = nn(a.hail), wndg = nn(a.wndg), dcape = nn(a.dcape), sigSvr = nn(a.sigSevere), mmp = nn(a.mmp),
            upshear = if (a.corfidi.valid) a.corfidi.upshear.speed() else Double.NaN, sfcWindMph = if (gone(sfcWind)) Double.NaN else sfcWind * 1.15078, sfcTempC = nn(sfcT),
            heatIndexF = if (gone(sfcT) || gone(a.surfaceRh)) Double.NaN else SharpIndices.heatIndex(sfcT * 1.8 + 32.0, a.surfaceRh), pwIn = nn(a.precipitableWaterIn), precipType = precipType,
        )
    }

    private fun have(v: Double) = !v.isNaN() && !v.isInfinite()
    private fun number(v: Double, digits: Int) = if (have(v)) fixed(v, digits) else "--"

    private fun test(text: String, value: Double, digits: Int, rule: (Double) -> Boolean) =
        Condition(text, number(value, digits), if (!have(value)) State.Unknown else if (rule(value)) State.Pass else State.Fail)

    /** "a or b": holds when any part holds; unknown when none holds and one part is unknown. */
    private fun anyOf(text: String, parts: List<Condition>): Condition {
        val state = when {
            parts.any { it.state == State.Pass } -> State.Pass
            parts.any { it.state == State.Unknown } -> State.Unknown
            else -> State.Fail
        }
        return Condition(text, parts.joinToString(" / ") { it.value }, state)
    }

    private fun allOf(text: String, parts: List<Condition>): Condition {
        val state = when {
            parts.any { it.state == State.Fail } -> State.Fail
            parts.any { it.state == State.Unknown } -> State.Unknown
            else -> State.Pass
        }
        return Condition(text, parts.joinToString(" / ") { it.value }, state)
    }

    private fun holds(lines: List<Condition>) = lines.all { it.state == State.Pass }

    /** Walks the chart for [p]; [pwMeanIn] and [pwSigmaIn] are the climatological precipitable water of the place and day (inches), when known, for the flash-flood test. */
    fun classify(p: Params, pwMeanIn: Double = Double.NaN, pwSigmaIn: Double = Double.NaN): Result {
        val ge = { limit: Double -> { v: Double -> v >= limit } }
        val gt = { limit: Double -> { v: Double -> v > limit } }
        val lt = { limit: Double -> { v: Double -> v < limit } }
        val le = { limit: Double -> { v: Double -> v <= limit } }
        // no effective inflow layer (not enough CAPE, too much CIN): the test cannot hold
        fun base() = if (!p.hasInflowLayer) Condition("Effective inflow base = 0 m AGL", "no layer", State.Fail)
            else test("Effective inflow base = 0 m AGL", p.inflowBase, 0) { it <= 0.5 }
        fun stp1() = anyOf("STPE >= 1 or STPF >= 1", listOf(test("STPE >= 1", p.stpE, 1, ge(1.0)), test("STPF >= 1", p.stpF, 1, ge(1.0))))
        fun cin(limit: Int) = test("MLCINH > $limit J/kg", p.mlCin, 0, gt(limit.toDouble()))
        val left = listOf(
            "L1" to ("PDS TOR" to listOf(
                test("STPE >= 3", p.stpE, 1, ge(3.0)), test("STPF >= 3", p.stpF, 1, ge(3.0)), test("SRH 0-1 km >= 200 m2/s2", p.srh01, 0, ge(200.0)), test("ESRH >= 200 m2/s2", p.esrh, 0, ge(200.0)),
                test("SRW 4-6 km >= 15 kt", p.srw46, 0, ge(15.0)), test("BWD 0-8 km > 45 kt", p.bwd08, 0, gt(45.0)), test("SBLCL < 1000 m", p.sbLcl, 0, lt(1000.0)),
                test("MLLCL < 1200 m", p.mlLcl, 0, lt(1200.0)), test("LR 0-1 km >= 5 C/km", p.lr01, 1, ge(5.0)), cin(-50), base(),
            )),
            "L2" to ("TOR" to listOf(anyOf("STPE >= 3 or STPF >= 4", listOf(test("STPE >= 3", p.stpE, 1, ge(3.0)), test("STPF >= 4", p.stpF, 1, ge(4.0)))), cin(-125), base())),
            "L3" to ("TOR" to listOf(stp1(), anyOf("SRW 4-6 km >= 15 kt or BWD 0-8 km >= 40 kt", listOf(test("SRW", p.srw46, 0, ge(15.0)), test("BWD", p.bwd08, 0, ge(40.0)))), cin(-50), base())),
            "L4" to ("TOR" to listOf(stp1(), test("(LowRH + MidRH) / 2 >= 60 %", p.rhMean, 0, ge(60.0)), test("LR 0-1 km >= 5 C/km", p.lr01, 1, ge(5.0)), cin(-50), base())),
            "L5" to ("MRGL TOR" to listOf(stp1(), cin(-150), base())),
            "L6" to ("MRGL TOR" to listOf(
                anyOf("(STPE >= 1 and ESRH >= 150) or (STPF >= 0.5 and SRH 0-1 km >= 150)", listOf(
                    allOf("STPE >= 1 and ESRH >= 150", listOf(test("STPE", p.stpE, 1, ge(1.0)), test("ESRH", p.esrh, 0, ge(150.0)))),
                    allOf("STPF >= 0.5 and SRH01 >= 150", listOf(test("STPF", p.stpF, 1, ge(0.5)), test("SRH01", p.srh01, 0, ge(150.0)))),
                )),
                cin(-50), base(),
            )),
        )
        val pwKnown = have(pwMeanIn) && have(pwSigmaIn)
        val pwTest = if (pwKnown && have(p.pwIn)) {
            val limit = pwMeanIn + 2.0 * pwSigmaIn
            Condition("PW > climatological mean + 2 sigma", "${number(p.pwIn, 2)} in (limit ${number(limit, 2)})", if (p.pwIn > limit) State.Pass else State.Fail)
        } else Condition("PW > climatological mean + 2 sigma", if (have(p.pwIn)) "${number(p.pwIn, 2)} in (no climatology here)" else "--", State.Unknown)
        val snow = Condition(
            "Bourgouin algorithm indicates snow", p.precipType ?: "not worked out",
            if (p.precipType == "Snow" || p.precipType == "Sleet and Snow") State.Pass else if (p.precipType == null || p.precipType == "Unknown" || p.precipType.isEmpty()) State.Unknown else State.Fail,
        )
        val right = listOf(
            "R1" to ("SVR" to listOf(anyOf("STPF >= 1 or SCP >= 4 or STPE >= 1", listOf(test("STPF", p.stpF, 1, ge(1.0)), test("SCP", p.scp, 1, ge(4.0)), test("STPE", p.stpE, 1, ge(1.0)))), test("MUCINH >= -50 J/kg", p.muCin, 0, ge(-50.0)))),
            "R2" to ("SVR" to listOf(
                anyOf("(SCP >= 2 and SHIP >= 1) or DCAPE >= 750 J/kg", listOf(allOf("SCP >= 2 and SHIP >= 1", listOf(test("SCP", p.scp, 1, ge(2.0)), test("SHIP", p.ship, 1, ge(1.0)))), test("DCAPE", p.dcape, 0, ge(750.0)))),
                test("MUCINH >= -50 J/kg", p.muCin, 0, ge(-50.0)),
            )),
            "R3" to ("SVR" to listOf(test("SigSvr >= 30000 m3/s3", p.sigSvr, 0, ge(30000.0)), test("MMP >= 0.6", p.mmp, 2, ge(0.6)), test("MUCINH >= -50 J/kg", p.muCin, 0, ge(-50.0)))),
            "R4" to ("MRGL SVR" to listOf(anyOf("WNDG >= 0.5 or SHIP >= 0.5 or SCP >= 0.5", listOf(test("WNDG", p.wndg, 1, ge(0.5)), test("SHIP", p.ship, 1, ge(0.5)), test("SCP", p.scp, 1, ge(0.5)))), test("MUCINH >= -75 J/kg", p.muCin, 0, ge(-75.0)))),
            "R5" to ("FLASH FLOOD" to listOf(pwTest, test("Corfidi upshear speed < 25 kt", p.upshear, 0, lt(25.0)))),
            "R6" to ("BLIZZARD" to listOf(test("Surface wind > 35 mph", p.sfcWindMph, 0, gt(35.0)), test("Surface temperature <= 0 C", p.sfcTempC, 1, le(0.0)), snow)),
            "R7" to ("EXCESSIVE HEAT" to listOf(test("Surface heat index > 105 F", p.heatIndexF, 0, gt(105.0)))),
        )
        // the walk: the tornado column from the top, then the other column from the bottom; the first node that holds gives its outcome
        var type = "NONE"
        val nodes = ArrayList<Node>()
        for ((id, spec) in left + right) {
            val (outcome, lines) = spec
            val reached = type == "NONE"
            val passed = reached && holds(lines)
            if (passed) type = outcome
            nodes += Node(id, outcome, lines, reached, passed)
        }
        return Result(type, nodes, pwKnown)
    }
}
