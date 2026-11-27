package simplemodelview

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The numbers an SPC sounding page prints, worked out from a model profile: parcels (surface, mixed layer, most unstable) with their CAPE, CIN, LCL, LFC, EL and
 * lifted index; the wind layers (shear, storm-relative helicity, mean wind, Bunkers storm motion); precipitable water, K index, lapse rates, the freezing and
 * wet-bulb-zero heights, and the fixed-layer significant tornado parameter. The formulas are the usual ones (Bolton 1980 for saturation and the LCL, the
 * pseudo-adiabatic ascent, virtual temperature, Bunkers et al. 2000); small differences from SHARPpy come from sampling.
 */
object SoundingMath {
    private const val RD = 287.04
    private const val CP = 1005.7
    private const val G = 9.80665
    private const val EPS = 0.622
    private const val KAPPA = RD / CP
    private const val LV = 2.5e6
    private const val KT = 0.514444                   // m/s per knot

    /** Saturation vapor pressure over water (mb) at [tC] degrees C (Bolton 1980). */
    fun es(tC: Double) = 6.112 * exp(17.67 * tC / (tC + 243.5))

    /** Mixing ratio (kg/kg) of air at [pMb] whose dewpoint is [tdC]; also the saturation mixing ratio when [tdC] is the temperature. */
    fun mixingRatio(pMb: Double, tdC: Double): Double { val e = es(tdC); return EPS * e / (pMb - e) }

    private fun virtualK(tC: Double, w: Double) = (tC + 273.15) * (1.0 + w / EPS) / (1.0 + w)

    /** The virtual temperature (C) of air at [pMb] with temperature [tC] and dewpoint [tdC]: what dry air would need to be, at that pressure, to have the same density. */
    fun virtualTemperatureC(pMb: Double, tC: Double, tdC: Double) = virtualK(tC, mixingRatio(pMb, min(tdC, tC))) - 273.15

    /** Dewpoint (C) of air at [pMb] with mixing ratio [w]. */
    fun dewpointFromMixing(pMb: Double, w: Double): Double {
        val e = w * pMb / (EPS + w)
        val l = ln(e / 6.112)
        return 243.5 * l / (17.67 - l)
    }

    /** Equivalent potential temperature (K) of a parcel (Bolton 1980). */
    fun thetaE(pMb: Double, tC: Double, tdC: Double): Double {
        val tk = tC + 273.15
        val tl = lclTemperatureK(tC, tdC)
        val w = mixingRatio(pMb, tdC) * 1000.0
        return tk * (1000.0 / pMb).pow(0.2854 * (1.0 - 0.28e-3 * w)) * exp((3.376 / tl - 0.00254) * w * (1.0 + 0.81e-3 * w))
    }

    private fun lclTemperatureK(tC: Double, tdC: Double): Double {
        val t = tC + 273.15; val td = min(tdC, tC) + 273.15
        return if (t - td < 0.05) t else 1.0 / (1.0 / (td - 56.0) + ln(t / td) / 800.0) + 56.0
    }

    /** The slope of a saturated ascent: K per mb at [pMb] and [tK]. */
    private fun moistLapse(pMb: Double, tK: Double): Double {
        val rs = mixingRatio(pMb, tK - 273.15)
        return (RD * tK + LV * rs) / (pMb * (CP + LV * LV * rs * EPS / (RD * tK * tK)))
    }

    /** A profile with its levels surface first, pressure falling, on which the layers below are computed. Heights are meters above the ground. */
    class Profile(val p: DoubleArray, val t: DoubleArray, val td: DoubleArray, val wet: DoubleArray, val z: DoubleArray, val dir: DoubleArray, val spd: DoubleArray, val elevation: Double) {
        val size get() = p.size
        private fun lerp(a: DoubleArray, pMb: Double): Double {
            if (pMb >= p[0]) return a[0]
            if (pMb <= p[size - 1]) return a[size - 1]
            var i = 0
            while (i < size - 2 && p[i + 1] > pMb) i++
            val f = ln(pMb / p[i]) / ln(p[i + 1] / p[i])
            return a[i] + f * (a[i + 1] - a[i])
        }
        fun tAt(pMb: Double) = lerp(t, pMb)
        fun tdAt(pMb: Double) = lerp(td, pMb)
        fun zAt(pMb: Double) = lerp(z, pMb)
        /** The pressure (mb) at [zAgl] meters above the ground, by interpolating the heights. */
        fun pAt(zAgl: Double): Double {
            if (zAgl <= z[0]) return p[0]
            if (zAgl >= z[size - 1]) return p[size - 1]
            var i = 0
            while (i < size - 2 && z[i + 1] < zAgl) i++
            val f = (zAgl - z[i]) / (z[i + 1] - z[i])
            return p[i] * (p[i + 1] / p[i]).pow(f)
        }
        fun uvAtZ(zAgl: Double): Pair<Double, Double>? {
            var lo = -1
            for (i in 0 until size) if (z[i] <= zAgl && !dir[i].isNaN() && !spd[i].isNaN()) lo = i
            var hi = -1
            for (i in size - 1 downTo 0) if (z[i] >= zAgl && !dir[i].isNaN() && !spd[i].isNaN()) hi = i
            if (lo < 0 && hi < 0) return null
            fun comp(i: Int) = (-spd[i] * KT * kotlin.math.sin(dir[i] * PI / 180.0)) to (-spd[i] * KT * kotlin.math.cos(dir[i] * PI / 180.0))
            if (lo < 0) return comp(hi); if (hi < 0) return comp(lo)
            if (lo == hi) return comp(lo)
            val f = (zAgl - z[lo]) / (z[hi] - z[lo])
            val a = comp(lo); val b = comp(hi)
            return (a.first + f * (b.first - a.first)) to (a.second + f * (b.second - a.second))
        }
    }

    /** The usable levels of [s] (temperature, dewpoint and height present, pressure falling), or null when fewer than five. */
    fun profile(s: Sounding, elevationM: Double): Profile? {
        val ls = s.levels.filter { !it.pressure.isNaN() && !it.tempC.isNaN() && !it.dewC.isNaN() && !it.heightM.isNaN() }
        val kept = ArrayList<SoundingLevel>()
        for (l in ls) if (kept.isEmpty() || (l.pressure < kept.last().pressure && l.heightM > kept.last().heightM)) kept += l
        if (kept.size < 5) return null
        return Profile(
            DoubleArray(kept.size) { kept[it].pressure }, DoubleArray(kept.size) { kept[it].tempC }, DoubleArray(kept.size) { min(kept[it].dewC, kept[it].tempC) },
            DoubleArray(kept.size) { kept[it].wetBulbC }, DoubleArray(kept.size) { kept[it].heightM - elevationM }, DoubleArray(kept.size) { kept[it].dirDeg }, DoubleArray(kept.size) { kept[it].speedKt }, elevationM,
        )
    }

    /** What lifting a parcel gave: [trace] is its temperature (C) at each pressure; heights in meters above the ground (null: none); CAPE and CIN in J/kg, [li] in K. */
    class Parcel(val name: String, val startP: Double, val trace: List<Pair<Double, Double>>, val lclZ: Double?, val lfcZ: Double?, val elZ: Double?, val cape: Double, val cin: Double, val li: Double?)

    /** Lifts the parcel ([p0], [t0], [td0]) through [pr]: dry to its LCL, then along the pseudo-adiabat; buoyancy against the virtual temperature of the environment. */
    fun lift(pr: Profile, name: String, p0: Double, t0: Double, td0: Double, virtual: Boolean = true): Parcel {
        val tdc = min(td0, t0)
        val w0 = mixingRatio(p0, tdc)
        val tl = lclTemperatureK(t0, tdc)
        val pl = p0 * (tl / (t0 + 273.15)).pow(1.0 / KAPPA)
        val ps = ArrayList<Double>()
        var p = p0
        while (p > pl + 0.01) { ps += p; p -= 5.0 }
        ps += max(pl, 100.0)
        p = ps.last() - 5.0
        while (p >= 100.0) { ps += p; p -= 5.0 }
        val trace = ArrayList<Pair<Double, Double>>(ps.size)
        val buoy = DoubleArray(ps.size)
        var tk = t0 + 273.15
        for ((i, pi) in ps.withIndex()) {
            val wP: Double
            if (pi >= pl) { tk = (t0 + 273.15) * (pi / p0).pow(KAPPA); wP = w0 }
            else {
                if (i > 0) {
                    val dp = pi - ps[i - 1]                                              // negative
                    val k1 = moistLapse(ps[i - 1], tk)
                    val k2 = moistLapse(ps[i - 1] + dp / 2, tk + k1 * dp / 2)
                    tk += k2 * dp
                }
                wP = mixingRatio(pi, tk - 273.15)
            }
            trace += pi to (tk - 273.15)
            val tEnv = pr.tAt(pi); val tdEnv = min(pr.tdAt(pi), tEnv)
            buoy[i] = if (virtual) virtualK(tk - 273.15, wP) - virtualK(tEnv, mixingRatio(pi, tdEnv)) else (tk - 273.15) - tEnv
        }
        // the energy between levels, J/kg, and the crossings
        val lclIndex = ps.indexOfFirst { it <= pl + 0.01 }.let { if (it < 0) ps.size - 1 else it }
        var lfc = -1
        for (i in lclIndex until ps.size) if (buoy[i] > 0) { lfc = i; break }
        if (lfc < 0) return Parcel(name, p0, trace, pr.zAt(pl).coerceAtLeast(0.0), null, null, 0.0, 0.0, liAt500(pr, trace, buoy, ps))
        var el = lfc
        for (i in lfc until ps.size) if (buoy[i] > 0) el = i
        fun dE(i: Int) = RD * (buoy[i] + buoy[i - 1]) / 2.0 * ln(ps[i - 1] / ps[i])
        var cape = 0.0; var cin = 0.0
        for (i in 1 until ps.size) {
            val e = dE(i)
            if (i <= lfc) { if (e < 0) cin += e } else if (i <= el) cape += e
        }
        return Parcel(name, p0, trace, pr.zAt(pl).coerceAtLeast(0.0), pr.zAt(ps[lfc]), pr.zAt(ps[el]), max(cape, 0.0), cin, liAt500(pr, trace, buoy, ps))
    }

    private fun liAt500(pr: Profile, trace: List<Pair<Double, Double>>, buoy: DoubleArray, ps: List<Double>): Double? {
        if (ps.last() > 500.0) return null
        val i = ps.indexOfFirst { it <= 500.0 }
        if (i <= 0) return null
        val f = (ln(500.0 / ps[i - 1])) / ln(ps[i] / ps[i - 1])
        return -(buoy[i - 1] + f * (buoy[i] - buoy[i - 1]))                           // environment minus parcel
    }

    fun surfaceParcel(pr: Profile) = lift(pr, "SURFACE", pr.p[0], pr.t[0], pr.td[0])

    /** The mean of the lowest [depth] mb: potential temperature and mixing ratio, lifted from the surface pressure. */
    fun mixedLayerParcel(pr: Profile, depth: Double = 100.0): Parcel {
        var th = 0.0; var w = 0.0; var n = 0
        var p = pr.p[0]
        while (p >= pr.p[0] - depth && p > pr.p[pr.size - 1]) {
            th += (pr.tAt(p) + 273.15) * (1000.0 / p).pow(KAPPA); w += mixingRatio(p, pr.tdAt(p)); n++
            p -= 10.0
        }
        th /= n; w /= n
        val t = th * (pr.p[0] / 1000.0).pow(KAPPA) - 273.15
        return lift(pr, "MIXED LAYER", pr.p[0], t, min(dewpointFromMixing(pr.p[0], w), t))
    }

    /** The parcel of highest equivalent potential temperature in the lowest [depth] mb. */
    fun mostUnstableParcel(pr: Profile, depth: Double = 300.0): Parcel {
        var best = 0; var bestTe = -1.0
        for (i in 0 until pr.size) {
            if (pr.p[i] < pr.p[0] - depth) break
            val te = thetaE(pr.p[i], pr.t[i], pr.td[i])
            if (te > bestTe) { bestTe = te; best = i }
        }
        return lift(pr, "MU", pr.p[best], pr.t[best], pr.td[best])
    }

    /** A wind vector in knots from a (u, v) in m/s: where it blows from (degrees) and its speed. */
    class Wind(val dir: Double, val kt: Double, val u: Double, val v: Double)

    private fun wind(u: Double, v: Double): Wind {
        val sp = hypot(u, v)
        var d = if (sp < 1e-9) 0.0 else (270.0 - atan2(v, u) * 180.0 / PI)
        d = ((d % 360.0) + 360.0) % 360.0
        return Wind(d, sp / KT, u, v)
    }

    /** One layer of the wind: shear (kt), mean wind, storm-relative mean wind, and helicity (m2/s2) for the storm motion used. */
    class WindLayer(val label: String, val topM: Double, val srh: Double, val shearKt: Double, val mean: Wind, val stormRelative: Wind)

    class Kinematics(val rightMover: Wind, val leftMover: Wind, val mean06: Wind, val layers: List<WindLayer>, val shear6Ms: Double, val srh1: Double)

    /** The shear, helicity and storm motion of the winds to 8 km, the storm motion that of Bunkers' internal-dynamics method (7.5 m/s off the 0 to 6 km mean wind). */
    fun kinematics(pr: Profile): Kinematics? {
        val zs = (0..80).map { it * 100.0 }
        val uv = zs.map { pr.uvAtZ(it) ?: return null }
        fun meanOf(z0: Double, z1: Double): Pair<Double, Double> {
            val idx = zs.indices.filter { zs[it] in z0..z1 }
            return idx.map { uv[it].first }.average() to idx.map { uv[it].second }.average()
        }
        val m = meanOf(0.0, 6000.0)
        val lo = meanOf(0.0, 500.0); val hi = meanOf(5500.0, 6000.0)
        val sx = hi.first - lo.first; val sy = hi.second - lo.second
        val s = hypot(sx, sy).let { if (it < 1e-6) 1e-6 else it }
        val d = 7.5
        val rm = (m.first + d * sy / s) to (m.second - d * sx / s)
        val lm = (m.first - d * sy / s) to (m.second + d * sx / s)
        fun srh(top: Double, c: Pair<Double, Double>): Double {
            var sum = 0.0
            for (i in 0 until zs.size - 1) {
                if (zs[i + 1] > top) break
                sum += (uv[i + 1].first - c.first) * (uv[i].second - c.second) - (uv[i].first - c.first) * (uv[i + 1].second - c.second)
            }
            return sum
        }
        val layers = listOf(1000.0 to "SFC - 1 km", 3000.0 to "SFC - 3 km", 6000.0 to "SFC - 6 km", 8000.0 to "SFC - 8 km").map { (top, label) ->
            val mean = meanOf(0.0, top); val i1 = zs.indexOfFirst { it >= top }
            val shear = hypot(uv[i1].first - uv[0].first, uv[i1].second - uv[0].second)
            WindLayer(label, top, srh(top, rm), shear / KT, wind(mean.first, mean.second), wind(mean.first - rm.first, mean.second - rm.second))
        }
        val i6 = zs.indexOfFirst { it >= 6000.0 }
        return Kinematics(wind(rm.first, rm.second), wind(lm.first, lm.second), wind(m.first, m.second), layers, hypot(uv[i6].first - uv[0].first, uv[i6].second - uv[0].second), srh(1000.0, rm))
    }

    /** Precipitable water in millimeters (kg/m2). */
    fun precipitableWater(pr: Profile): Double {
        var sum = 0.0
        for (i in 1 until pr.size) {
            val w = (mixingRatio(pr.p[i], pr.td[i]) + mixingRatio(pr.p[i - 1], pr.td[i - 1])) / 2.0
            sum += w * (pr.p[i - 1] - pr.p[i]) * 100.0 / G
        }
        return sum
    }

    /** K index: T850 - T500 + Td850 - (T700 - Td700); null when the profile does not reach 500 mb. */
    fun kIndex(pr: Profile): Double? =
        if (pr.p[0] < 850.0 || pr.p[pr.size - 1] > 500.0) null else pr.tAt(850.0) - pr.tAt(500.0) + pr.tdAt(850.0) - (pr.tAt(700.0) - pr.tdAt(700.0))

    /** The temperature fall per kilometer between two heights above the ground (C/km). */
    fun lapseRateZ(pr: Profile, z0: Double, z1: Double): Double? {
        if (pr.z[pr.size - 1] < z1) return null
        val a = pr.tAt(pr.pAt(z0)); val b = pr.tAt(pr.pAt(z1))
        return (a - b) / ((z1 - z0) / 1000.0)
    }

    /** The temperature fall per kilometer between two pressures (C/km). */
    fun lapseRateP(pr: Profile, p0: Double, p1: Double): Double? {
        if (pr.p[0] < p0 || pr.p[pr.size - 1] > p1) return null
        return (pr.tAt(p0) - pr.tAt(p1)) / ((pr.zAt(p1) - pr.zAt(p0)) / 1000.0)
    }

    /** The height (m above the ground) where the temperature (or wet bulb) first falls to [valueC] above the surface, or null. */
    fun levelOf(pr: Profile, valueC: Double, wetBulb: Boolean = false): Double? {
        val a = if (wetBulb) pr.wet else pr.t
        if (a.any { it.isNaN() }) return null
        for (i in 1 until pr.size) {
            if (a[i - 1] > valueC && a[i] <= valueC) {
                val f = (a[i - 1] - valueC) / (a[i - 1] - a[i])
                return pr.z[i - 1] + f * (pr.z[i] - pr.z[i - 1])
            }
        }
        return null
    }

    /** The significant tornado parameter, fixed layer: surface CAPE and LCL, 0 to 1 km helicity, 0 to 6 km shear, mixed-layer CIN (SPC's definition). */
    fun stpFixed(sb: Parcel, ml: Parcel, srh1: Double, shear6Ms: Double): Double {
        val lcl = sb.lclZ ?: return 0.0
        val lclTerm = when { lcl < 1000.0 -> 1.0; lcl > 2000.0 -> 0.0; else -> (2000.0 - lcl) / 1000.0 }
        val shearTerm = when { shear6Ms < 12.5 -> 0.0; shear6Ms > 30.0 -> 1.5; else -> shear6Ms / 20.0 }
        val cinTerm = when { ml.cin > -50.0 -> 1.0; ml.cin < -200.0 -> 0.0; else -> (200.0 + ml.cin) / 150.0 }
        return (sb.cape / 1500.0) * lclTerm * (srh1 / 150.0) * shearTerm * cinTerm
    }

    /** Everything a sounding page prints, or null when the profile is too short. */
    class Analysis(val profile: Profile, val surface: Parcel, val mixed: Parcel, val mostUnstable: Parcel, val kinematics: Kinematics?, val pwMm: Double, val kIndex: Double?,
                   val lapse03: Double?, val lapse36: Double?, val lapse850500: Double?, val lapse700500: Double?, val freezingM: Double?, val minus20M: Double?, val wetBulbZeroM: Double?, val stp: Double)

    fun analyze(s: Sounding, elevationM: Double): Analysis? {
        val pr = profile(s, elevationM) ?: return null
        val sb = surfaceParcel(pr); val ml = mixedLayerParcel(pr); val mu = mostUnstableParcel(pr)
        val k = kinematics(pr)
        return Analysis(
            pr, sb, ml, mu, k, precipitableWater(pr), kIndex(pr), lapseRateZ(pr, 0.0, 3000.0), lapseRateZ(pr, 3000.0, 6000.0), lapseRateP(pr, 850.0, 500.0), lapseRateP(pr, 700.0, 500.0),
            levelOf(pr, 0.0), levelOf(pr, -20.0), levelOf(pr, 0.0, wetBulb = true), if (k == null) 0.0 else stpFixed(sb, ml, k.srh1, k.shear6Ms),
        )
    }
}

internal fun metersToFeet(m: Double) = m * 3.28084
