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

    private fun nan(v: Double?) = v ?: Double.NaN

    /** The chart's parameters of an [analysis]. */
    fun params(a: SoundingMath.Analysis, precipType: String? = a.precip.type): Params {
        val low = a.lowRh; val mid = a.midRh
        return Params(
            stpE = a.stpEffective, stpF = a.stp, srh01 = a.kinematics?.srh1 ?: Double.NaN, esrh = if (a.effective == null) Double.NaN else a.effectiveSrh, srw46 = nan(a.srw46Kt),
            bwd08 = nan(a.bwd08Kt), sbLcl = nan(a.surface.lclZ), mlLcl = nan(a.mixed.lclZ), lr01 = nan(a.lapse01), mlCin = a.mixed.cin, muCin = a.mostUnstable.cin,
            hasInflowLayer = a.effective != null, inflowBase = a.effective?.bottomZ ?: Double.NaN, rhMean = if (low != null && mid != null) (low + mid) / 2.0 else Double.NaN,
            scp = a.supercell, ship = nan(a.ship), wndg = nan(a.wndg), dcape = nan(a.dcape), sigSvr = nan(a.sigSevere), mmp = nan(a.mmp), upshear = nan(a.corfidi?.upwind?.kt),
            sfcWindMph = nan(a.sfcWindMph), sfcTempC = a.sfcTempC, heatIndexF = SharpTab.heatIndex(a.sfcTempC * 1.8 + 32.0, a.sfcRh), pwIn = a.pwMm / 25.4, precipType = precipType,
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
