package simplemodelview

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * The sounding engine of the app: a Kotlin port of wxqt-grib's `src/sounding` (SoundingProfile, SoundingThermo, SoundingParcel, SoundingIndices, SoundingAnalysis, SoundingPrecip,
 * SoundingSars, SoundingTornadoProb), which reimplements SHARPpy (sharptab params.py, winds.py, thermo.py, watch_type.py; BSD-3-Clause, see NOTICE and docs/sharppy-notice.md) and was
 * checked against SPC's own numbers on 270 soundings. The port keeps wxqt's conventions, so the two can be compared line by line: -9999 marks a missing number, heights are
 * meters (above sea level in a profile, above the ground in the results), winds are knots, and a profile's "surface" is its first level with a temperature and a dewpoint.
 */
const val SHARP_MISSING = -9999.0

fun gone(v: Double) = v <= -9998.0 || v.isNaN()

/** The thermodynamic functions of SHARPpy's thermo.py (Wobus polynomials), as wxqt's SoundingThermo. */
object SharpThermo {
    const val ZERO_C_K = 273.15
    const val GRAVITY = 9.80665
    const val ROCP = 0.28571426

    fun vaporPressure(tC: Double): Double {
        var pol = tC * (1.1112018e-17 + tC * (-3.0994571e-20))
        pol = tC * (2.1874425e-13 + tC * (-1.789232e-15 + pol))
        pol = tC * (4.3884180e-09 + tC * (-2.988388e-11 + pol))
        pol = tC * (7.8736169e-05 + tC * (-6.111796e-07 + pol))
        pol = 0.99999683 + tC * (-9.082695e-03 + pol)
        return 6.1078 / pol.pow(8)
    }

    /** Mixing ratio (g/kg) of air at [pMb] with dewpoint (or temperature, for saturation) [tC]. */
    fun mixingRatio(pMb: Double, tC: Double): Double {
        val x = 0.02 * (tC - 12.5 + 7500.0 / pMb)
        val wfw = 1.0 + 0.0000045 * pMb + 0.0014 * x * x
        val fwesw = wfw * vaporPressure(tC)
        return 621.97 * (fwesw / (pMb - fwesw))
    }

    fun virtualTemp(pMb: Double, tC: Double, tdC: Double): Double {
        val tk = tC + ZERO_C_K
        val w = 0.001 * mixingRatio(pMb, tdC)
        return tk * (1.0 + w / 0.622) / (1.0 + w) - ZERO_C_K
    }

    fun theta(pMb: Double, tC: Double, p2Mb: Double) = (tC + ZERO_C_K) * (p2Mb / pMb).pow(ROCP) - ZERO_C_K

    fun lclTemp(tC: Double, tdC: Double): Double {
        val s = tC - tdC
        val dlt = s * (1.2185 + 0.001278 * tC + s * (-0.00219 + 1.173e-5 * s - 0.0000052 * tC))
        return tC - dlt
    }

    /** The LCL pressure and temperature of a parcel: (pressure, temperature). */
    fun dryLift(pMb: Double, tC: Double, tdC: Double): Pair<Double, Double> {
        val t = lclTemp(tC, tdC)
        return 1000.0 * ((t + ZERO_C_K) / (theta(pMb, tC, 1000.0) + ZERO_C_K)).pow(1.0 / ROCP) to t
    }

    fun wobus(tC: Double): Double {
        val t = tC - 20.0
        if (t <= 0.0) {
            var npol = 1.0 + t * (-8.841660499999999e-3 + t * (1.4714143e-4 + t * (-9.671989000000001e-7 + t * (-3.2607217e-8 + t * (-3.8598073e-10)))))
            npol = 15.13 / npol.pow(4)
            return npol
        }
        var ppol = t * (4.9618922e-07 + t * (-6.1059365e-09 + t * (3.9401551e-11 + t * (-1.2588129e-13 + t * (1.6688280e-16)))))
        ppol = 1.0 + t * (3.6182989e-03 + t * (-1.3603273e-05 + ppol))
        return (29.93 / ppol.pow(4)) + (0.96 * t) - 14.8
    }

    /** The temperature at [pMb] on the moist adiabat with wet-bulb potential temperature [thetaM]. */
    fun satLift(pMb: Double, thetaM: Double): Double {
        if (abs(pMb - 1000.0) - 0.001 <= 0.0) return thetaM
        var eor = 999.0; var rate = 1.0
        var t1 = 0.0; var t2 = 0.0; var e1 = 0.0; var e2 = 0.0
        val pwrp = (pMb / 1000.0).pow(ROCP)
        var guard = 0
        while (abs(eor) - 0.1 > 0.0 && guard < 200) {
            guard += 1
            if (eor == 999.0) {
                t1 = (thetaM + ZERO_C_K) * pwrp - ZERO_C_K
                e1 = wobus(t1) - wobus(thetaM)
                rate = 1.0
            } else {
                rate = (t2 - t1) / (e2 - e1)
                t1 = t2; e1 = e2
            }
            t2 = t1 - e1 * rate
            e2 = (t2 + ZERO_C_K) / pwrp - ZERO_C_K
            e2 += wobus(t2) - wobus(e2) - thetaM
            eor = e2 * rate
        }
        return t2 - eor
    }

    fun wetLift(pMb: Double, tC: Double, p2Mb: Double): Double {
        val thta = theta(pMb, tC, 1000.0)
        val thetam = thta - wobus(thta) + wobus(tC)
        return satLift(p2Mb, thetam)
    }

    fun wetBulb(pMb: Double, tC: Double, tdC: Double): Double {
        val (mp, mt) = dryLift(pMb, tC, tdC)
        return wetLift(mp, mt, pMb)
    }

    /** Equivalent potential temperature (C): lift to the LCL, follow the moist adiabat to 100 mb, then dry-descend to 1000 mb. */
    fun thetaE(pMb: Double, tC: Double, tdC: Double): Double {
        val (lp, lt) = dryLift(pMb, tC, tdC)
        return theta(100.0, wetLift(lp, lt, 100.0), 1000.0)
    }

    fun tempAtMixingRatio(w: Double, pMb: Double): Double {
        val x = log10(w * pMb / (622.0 + w))
        val a = 10.0.pow(0.0915 * x) - 1.2035
        return (10.0.pow(0.0498646455 * x + 2.4082965) - 7.07475 + 38.9114 * a * a) - ZERO_C_K
    }

    fun relativeHumidityPct(pMb: Double, tC: Double, tdC: Double) = 100.0 * mixingRatio(pMb, tdC) / mixingRatio(pMb, tC)
}

/**
 * A sounding as SHARPpy holds it: levels from the ground up (pressure falling), with the winds' components in knots, the virtual temperature, theta-e and wet bulb worked out, and
 * log-pressure interpolation of each series. Missing numbers are -9999.
 */
class SharpProfile(val pres: DoubleArray, val hght: DoubleArray, val tmpc: DoubleArray, val dwpc: DoubleArray, val wdir: DoubleArray, val wspd: DoubleArray, val latitude: Double = 35.0) {
    class Series { val logp = ArrayList<Double>(); val value = ArrayList<Double>() }

    val size get() = pres.size
    val u = DoubleArray(size) { SHARP_MISSING }
    val v = DoubleArray(size) { SHARP_MISSING }
    val vtmp = DoubleArray(size) { SHARP_MISSING }
    val thetae = DoubleArray(size) { SHARP_MISSING }
    val wetbulb = DoubleArray(size) { SHARP_MISSING }
    var sfc = 0
        private set
    private val tempS = Series(); private val dwptS = Series(); private val vtmpS = Series(); private val thetaeS = Series(); private val hghtS = Series(); private val uS = Series(); private val vS = Series()
    private val hghtAsc = ArrayList<Double>(); private val logpAtHght = ArrayList<Double>()

    init {
        var foundSfc = false
        for (i in 0 until size) {
            if (gone(pres[i]) || pres[i] <= 0.0) continue
            val lp = log10(pres[i])
            if (!gone(wdir[i]) && !gone(wspd[i])) {
                u[i] = -wspd[i] * sin(wdir[i] * PI / 180.0); v[i] = -wspd[i] * cos(wdir[i] * PI / 180.0)
                uS.logp += lp; uS.value += u[i]; vS.logp += lp; vS.value += v[i]
            }
            if (!gone(tmpc[i])) { tempS.logp += lp; tempS.value += tmpc[i] }
            if (!gone(dwpc[i])) { dwptS.logp += lp; dwptS.value += dwpc[i] }
            if (!gone(tmpc[i]) && !gone(dwpc[i])) {
                vtmp[i] = SharpThermo.virtualTemp(pres[i], tmpc[i], dwpc[i])
                vtmpS.logp += lp; vtmpS.value += vtmp[i]
                thetae[i] = SharpThermo.thetaE(pres[i], tmpc[i], dwpc[i])
                wetbulb[i] = SharpThermo.wetBulb(pres[i], tmpc[i], dwpc[i])
                thetaeS.logp += lp; thetaeS.value += thetae[i]
                if (!foundSfc && !gone(hght[i])) { sfc = i; foundSfc = true }
            }
            if (!gone(hght[i])) { hghtS.logp += lp; hghtS.value += hght[i] }
        }
        for (i in hghtS.value.indices) if (hghtAsc.isEmpty() || hghtS.value[i] > hghtAsc.last()) { hghtAsc += hghtS.value[i]; logpAtHght += hghtS.logp[i] }
    }

    fun sfcPres() = if (size == 0) SHARP_MISSING else pres[sfc]
    fun sfcHght() = if (size == 0) SHARP_MISSING else hght[sfc]
    fun toAgl(hMsl: Double) = hMsl - sfcHght()
    fun toMsl(hAgl: Double) = hAgl + sfcHght()

    private fun interpLogP(s: Series, pMb: Double): Double {
        val n = s.logp.size
        if (n < 2 || pMb <= 0.0) return SHARP_MISSING
        val lp = log10(pMb)
        if (lp > s.logp[0] + 1e-12 || lp < s.logp[n - 1] - 1e-12) return SHARP_MISSING
        for (i in 0 until n - 1) {
            val a = s.logp[i]; val b = s.logp[i + 1]
            if (lp <= a + 1e-12 && lp >= b - 1e-12) {
                if (abs(a - b) < 1e-12) return s.value[i]
                return s.value[i] + (lp - a) / (b - a) * (s.value[i + 1] - s.value[i])
            }
        }
        return SHARP_MISSING
    }

    fun interpHght(pMb: Double) = interpLogP(hghtS, pMb)
    fun interpTemp(pMb: Double) = interpLogP(tempS, pMb)
    fun interpDwpt(pMb: Double) = interpLogP(dwptS, pMb)
    fun interpVtmp(pMb: Double) = interpLogP(vtmpS, pMb)
    fun interpThetae(pMb: Double) = interpLogP(thetaeS, pMb)

    /** The wind components (u, v in knots) at a pressure, or null outside the winds. */
    fun interpComponents(pMb: Double): Pair<Double, Double>? {
        val a = interpLogP(uS, pMb); val b = interpLogP(vS, pMb)
        return if (gone(a) || gone(b)) null else a to b
    }

    /** The pressure at a height above sea level, or -9999. */
    fun interpPresAtHght(hMsl: Double): Double {
        val n = hghtAsc.size
        if (n < 2 || hMsl < hghtAsc[0] || hMsl > hghtAsc[n - 1]) return SHARP_MISSING
        for (i in 0 until n - 1) {
            if (hMsl >= hghtAsc[i] && hMsl <= hghtAsc[i + 1]) {
                val f = (hMsl - hghtAsc[i]) / (hghtAsc[i + 1] - hghtAsc[i])
                return 10.0.pow(logpAtHght[i] + f * (logpAtHght[i + 1] - logpAtHght[i]))
            }
        }
        return SHARP_MISSING
    }

    companion object {
        /** A profile from a model [sounding]: each level's pressure, height, temperature, dewpoint and wind (the levels with no dewpoint high up are kept as very dry, as in SoundingMath). */
        fun of(s: Sounding, latitude: Double = Double.NaN): SharpProfile? {
            val ls = s.levels.filter { !it.pressure.isNaN() && !it.tempC.isNaN() && !it.heightM.isNaN() }.map { if (it.dewC.isNaN()) it.copy(dewC = it.tempC - 40.0) else it }
            val kept = ArrayList<SoundingLevel>()
            for (l in ls) if (kept.isEmpty() || (l.pressure < kept.last().pressure && l.heightM > kept.last().heightM)) kept += l
            if (kept.size < 5) return null
            return SharpProfile(
                DoubleArray(kept.size) { kept[it].pressure }, DoubleArray(kept.size) { kept[it].heightM }, DoubleArray(kept.size) { kept[it].tempC }, DoubleArray(kept.size) { minOf(kept[it].dewC, kept[it].tempC) },
                DoubleArray(kept.size) { if (kept[it].dirDeg.isNaN()) SHARP_MISSING else kept[it].dirDeg }, DoubleArray(kept.size) { if (kept[it].speedKt.isNaN()) SHARP_MISSING else kept[it].speedKt },
                if (latitude.isNaN()) 35.0 else latitude,
            )
        }
    }
}
