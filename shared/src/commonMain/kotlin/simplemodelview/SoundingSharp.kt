package simplemodelview

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The parts of an SPC sounding page that follow SHARPpy's way of working (sharptab params.py / winds.py), ported by way of wxqt-grib's checked versions: winds are
 * interpolated in log pressure and averaged on 1 mb steps, the storm motion is the effective-layer Bunkers motion, helicity is summed over the levels, and the layers
 * (low and mid humidity, the Corfidi vectors, the downdraft CAPE) are the ones SPC prints. SHARPpy: Copyright (c) 2011 Patrick T. Marsh and John Hart, 2012 MetPy
 * Developers, 2020 Kelton Halbert, Greg Blumberg and Tim Supinie; BSD-3-Clause (see NOTICE).
 */
object SharpTab {
    private const val KT = SoundingMath.KT

    /** The winds of a profile by pressure: u and v in m/s at the levels that have a wind, interpolated in log pressure; null outside the levels. */
    class WindColumn(pr: SoundingMath.Profile) {
        private val ps = ArrayList<Double>(); private val us = ArrayList<Double>(); private val vs = ArrayList<Double>()
        init {
            for (i in 0 until pr.size) {
                if (pr.dir[i].isNaN() || pr.spd[i].isNaN()) continue
                val r = pr.dir[i] * PI / 180.0
                ps += pr.p[i]; us += -pr.spd[i] * KT * kotlin.math.sin(r); vs += -pr.spd[i] * KT * kotlin.math.cos(r)
            }
        }
        val levels get() = ps.size
        fun at(pMb: Double): Pair<Double, Double>? {
            if (ps.size < 2 || pMb > ps[0] + 1e-6 || pMb < ps[ps.size - 1] - 1e-6) return null
            var i = 0
            while (i < ps.size - 2 && ps[i + 1] > pMb) i++
            val f = (ln(pMb) - ln(ps[i])) / (ln(ps[i + 1]) - ln(ps[i]))
            return (us[i] + f * (us[i + 1] - us[i])) to (vs[i] + f * (vs[i + 1] - vs[i]))
        }
        /** The levels' own pressures and winds between two pressures, the ends excluded. */
        fun levelsBetween(pBottom: Double, pTop: Double): List<Triple<Double, Double, Double>> =
            ps.indices.filter { ps[it] < pBottom && ps[it] > pTop }.map { Triple(ps[it], us[it], vs[it]) }
    }

    private fun pAtAgl(pr: SoundingMath.Profile, z: Double) = if (z <= 0.0) pr.p[0] else pr.pAt(z)

    /** The wind at a height above the ground (m/s). */
    fun windAtAgl(pr: SoundingMath.Profile, w: WindColumn, z: Double) = w.at(pAtAgl(pr, z))

    /** The mean wind between two heights above the ground on 1 mb steps, pressure weighted or plain (m/s). */
    fun meanWind(pr: SoundingMath.Profile, w: WindColumn, fromAgl: Double, toAgl: Double, weighted: Boolean = true): Pair<Double, Double>? =
        meanBetweenPressures(w, pAtAgl(pr, fromAgl), pAtAgl(pr, toAgl), weighted)

    fun meanBetweenPressures(w: WindColumn, pBottom: Double, pTop: Double, weighted: Boolean): Pair<Double, Double>? {
        var su = 0.0; var sv = 0.0; var sw = 0.0
        var p = pBottom
        while (p >= pTop - 1e-9) {
            val uv = w.at(p)
            if (uv != null) { val k = if (weighted) p else 1.0; su += uv.first * k; sv += uv.second * k; sw += k }
            p -= 1.0
        }
        return if (sw <= 0.0) null else (su / sw) to (sv / sw)
    }

    fun bulkShear(pr: SoundingMath.Profile, w: WindColumn, fromAgl: Double, toAgl: Double): Pair<Double, Double>? {
        val a = windAtAgl(pr, w, fromAgl) ?: return null; val b = windAtAgl(pr, w, toAgl) ?: return null
        return (b.first - a.first) to (b.second - a.second)
    }

    /** Bunkers' storm motion (internal dynamics, SHARPpy's non-parcel method): the plain 0 to 6 km mean wind moved 7.5 m/s across the 0 to 6 km shear; [side] 1 is the right mover. */
    fun bunkers(pr: SoundingMath.Profile, w: WindColumn, side: Double): Pair<Double, Double>? {
        val m = meanWind(pr, w, 0.0, 6000.0, weighted = false) ?: return null
        val sh = bulkShear(pr, w, 0.0, 6000.0) ?: return null
        val mag = hypot(sh.first, sh.second)
        if (mag < 1e-6) return null
        return (m.first + side * 7.5 / mag * sh.second) to (m.second - side * 7.5 / mag * sh.first)
    }

    /** Helicity (m2/s2) between two heights for a storm motion: the sum over the levels inside the layer and its interpolated ends. */
    fun helicity(pr: SoundingMath.Profile, w: WindColumn, fromAgl: Double, toAgl: Double, storm: Pair<Double, Double>): Double? {
        val pb = pAtAgl(pr, fromAgl); val pt = pAtAgl(pr, toAgl)
        val first = w.at(pb) ?: return null; val last = w.at(pt) ?: return null
        val seq = ArrayList<Pair<Double, Double>>()
        seq += first
        for (l in w.levelsBetween(pb, pt)) seq += (l.second to l.third)
        seq += last
        var sum = 0.0
        for (i in 0 until seq.size - 1) {
            val u1 = seq[i].first - storm.first; val v1 = seq[i].second - storm.second
            val u2 = seq[i + 1].first - storm.first; val v2 = seq[i + 1].second - storm.second
            sum += u2 * v1 - u1 * v2
        }
        return sum
    }

    /**
     * SHARPpy's effective inflow layer: the first level from the ground whose parcel has at least [minCape] with CIN above [minCin] is its base, the layer ends at the level
     * before the first that does not; none when the most unstable parcel itself fails, or when every level qualifies up to the top of the profile (as SHARPpy leaves it).
     */
    fun effectiveInflowLayer(pr: SoundingMath.Profile, mu: SoundingMath.Parcel, minCape: Double = 100.0, minCin: Double = -250.0): SoundingMath.EffectiveLayer? {
        if (mu.cape == 0.0 || mu.cape < minCape || !(mu.cin > minCin)) return null
        val last = pr.size - 1
        var bottom = -1
        for (i in 0 until last) {
            val pc = SoundingMath.lift(pr, "", pr.p[i], pr.t[i], pr.td[i])
            if (pc.cape >= minCape && pc.cin > minCin) { bottom = i; break }
        }
        if (bottom < 0) return null
        var pTop = Double.NaN; var prev = bottom
        for (i in bottom + 1 until last) {
            if (pr.td[i] == 0.0 || pr.t[i] == 0.0) continue            // (SHARPpy skips a level whose temperature or dewpoint is exactly zero)
            val pc = SoundingMath.lift(pr, "", pr.p[i], pr.t[i], pr.td[i])
            if (pc.cape < minCape || pc.cin <= minCin) { pTop = min(pr.p[prev], pr.p[bottom]); break }
            prev = i
        }
        if (pTop.isNaN()) return null
        return SoundingMath.EffectiveLayer(pr.zAt(pr.p[bottom]), pr.zAt(pTop), pr.p[bottom], pTop)
    }

    /** The storm motion of the effective layer (SHARPpy): the Bunkers motion of the pressure-weighted mean wind from the layer base to 65 % of the way to the most unstable EL; the plain one without a layer. */
    fun stormMotion(pr: SoundingMath.Profile, w: WindColumn, mu: SoundingMath.Parcel, layer: SoundingMath.EffectiveLayer?, right: Boolean): Pair<Double, Double>? {
        val side = if (right) 1.0 else -1.0
        val el = mu.elZ
        if (layer == null || !(mu.cape > 100.0) || el == null) return bunkers(pr, w, side)
        val depth = el - layer.bottomZ
        val pTop = pAtAgl(pr, layer.bottomZ + depth * 0.65)
        val m = meanBetweenPressures(w, layer.bottomP, pTop, weighted = true) ?: return null
        val b = w.at(layer.bottomP) ?: return null; val t = w.at(pTop) ?: return null
        val shu = t.first - b.first; val shv = t.second - b.second
        val mag = hypot(shu, shv)
        if (mag < 1e-6) return null
        return (m.first + side * 7.5 / mag * shv) to (m.second - side * 7.5 / mag * shu)
    }

    /** The effective bulk wind difference (m/s): from the base of the inflow layer to half way up to the most unstable EL. */
    fun effectiveBulkShear(pr: SoundingMath.Profile, w: WindColumn, mu: SoundingMath.Parcel, layer: SoundingMath.EffectiveLayer?): Double? {
        val el = mu.elZ ?: return null
        if (layer == null) return null
        val pTop = pAtAgl(pr, layer.bottomZ + (el - layer.bottomZ) / 2.0)
        val b = w.at(layer.bottomP) ?: return null; val t = w.at(pTop) ?: return null
        return hypot(t.first - b.first, t.second - b.second)
    }

    /** The Corfidi vectors as SPC prints them: the plain mean wind from 850 to 300 mb (surface to 300 when the surface is above 850) less the plain mean of the lowest 1.5 km. */
    fun corfidi(pr: SoundingMath.Profile, w: WindColumn): SoundingMath.Corfidi? {
        val sfc = pr.p[0]
        val tropo = meanBetweenPressures(w, if (sfc < 850.0) sfc else 850.0, 300.0, weighted = false) ?: return null
        val low = meanBetweenPressures(w, sfc, pr.pAt(1500.0), weighted = false) ?: return null
        val up = (tropo.first - low.first) to (tropo.second - low.second)
        return SoundingMath.Corfidi(SoundingMath.wind(up.first, up.second), SoundingMath.wind(tropo.first + up.first, tropo.second + up.second))
    }

    /** SPC's low and mid-level relative humidity: the pressure-weighted mean over the levels between two pressures (the observed levels only), in percent. */
    fun meanRelativeHumidity(pr: SoundingMath.Profile, pBottom: Double, pTop: Double): Double? {
        var sum = 0.0; var weights = 0.0
        for (i in 0 until pr.size) {
            if (pr.p[i] > pBottom || pr.p[i] < pTop) continue
            val rh = 100.0 * SoundingMath.mixingRatio(pr.p[i], pr.td[i]) / SoundingMath.mixingRatio(pr.p[i], pr.t[i])
            sum += rh * pr.p[i]; weights += pr.p[i]
        }
        return if (weights > 0.0) min(100.0, sum / weights) else null
    }

    /** The wind-damage parameter (SPC WNDG): mixed-layer CAPE, the 0 to 3 km lapse rate, the 1 to 3.5 km mean wind and the mixed-layer CIN. */
    fun wndg(pr: SoundingMath.Profile, w: WindColumn, mlCape: Double, mlCin: Double, lapse03: Double?): Double? {
        val m = meanWind(pr, w, 1000.0, 3500.0) ?: return null
        if (lapse03 == null) return null
        val lr = if (lapse03 < 7.0) 0.0 else lapse03
        val cin = if (mlCin < -50.0) -50.0 else mlCin
        return (mlCape / 2000.0) * (lr / 9.0) * (hypot(m.first, m.second) / 15.0) * ((50.0 + cin) / 40.0)
    }

    /** The mesoscale convective system maintenance probability (SPC MMP, the logistic fit of Coniglio et al.): 0 under 100 J/kg of most unstable CAPE; null when a part is missing. */
    fun mmp(pr: SoundingMath.Profile, w: WindColumn, muCape: Double): Double? {
        if (muCape < 100.0) return 0.0
        val bottoms = (0 until pr.size).filter { pr.z[it] <= 1000.0 }
        val tops = (0 until pr.size).filter { pr.z[it] >= 6000.0 && pr.z[it] < 10000.0 }
        if (bottoms.isEmpty() || tops.isEmpty()) return null
        var maxShear = -1.0
        for (b in bottoms) for (t in tops) {
            val vb = w.at(pr.p[b]) ?: continue; val vt = w.at(pr.p[t]) ?: continue
            maxShear = max(maxShear, hypot(vt.first - vb.first, vt.second - vb.second))
        }
        if (maxShear < 0.0) return null
        val lr38 = SoundingMath.lapseRateZ(pr, 3000.0, 8000.0) ?: return null
        val mean = meanWind(pr, w, 3000.0, 12000.0) ?: return null
        val meanMs = hypot(mean.first, mean.second)
        val a0 = 13.0; val a1 = -4.59e-2; val a2 = -1.16; val a3 = -6.17e-4; val a4 = -0.17
        return 1.0 / (1.0 + exp(a0 + a1 * maxShear + a2 * lr38 + a3 * muCape + a4 * meanMs))
    }

    /** The significant hail parameter (SPC SHIP, SHARPpy's limits). The freezing level is compared above sea level, as SHARPpy does. */
    fun ship(pr: SoundingMath.Profile, w: WindColumn, mu: SoundingMath.Parcel): Double? {
        val startTd = pr.tdAt(mu.startP)
        var mumr = SoundingMath.mixingRatio(mu.startP, startTd) * 1000.0
        val frzP = SoundingMath.levelOf(pr, 0.0)?.let { pr.pAt(it) }
        val frzH = if (frzP == null) null else pr.zAt(frzP) + pr.elevation
        if (pr.p[pr.size - 1] > 500.0 || frzH == null) return null
        var h5 = pr.tAt(500.0)
        val lr75 = SoundingMath.lapseRateP(pr, 700.0, 500.0) ?: return null
        val sh = bulkShear(pr, w, 0.0, 6000.0) ?: return null
        val shr06 = min(27.0, max(7.0, hypot(sh.first, sh.second)))
        mumr = min(13.6, max(11.0, mumr)); h5 = min(-5.5, h5)
        var v = -1.0 * (mu.cape * mumr * lr75 * h5 * shr06) / 42_000_000.0
        if (mu.cape < 1300.0) v *= mu.cape / 1300.0
        if (lr75 < 5.8) v *= lr75 / 5.8
        if (frzH < 2400.0) v *= frzH / 2400.0
        return v
    }

    // ---- the downdraft CAPE (SPC DCAPE and DownT)

    /** A parcel's temperature (C) after a pseudo-adiabatic move from [p1] to [p2] (up or down), in 5 mb steps. */
    fun wetLift(p1: Double, tC: Double, p2: Double): Double {
        var tk = tC + 273.15; var p = p1
        val dir = if (p2 > p1) 1.0 else -1.0
        while ((p2 - p) * dir > 1e-9) {
            val dp = dir * min(5.0, kotlin.math.abs(p2 - p))
            val k1 = SoundingMath.moistLapse(p, tk)
            val k2 = SoundingMath.moistLapse(p + dp / 2, tk + k1 * dp / 2)
            tk += k2 * dp; p += dp
        }
        return tk - 273.15
    }

    /** The wet-bulb temperature (C): lift dry to the LCL, then bring the saturated parcel back down to the level. */
    fun wetBulb(pMb: Double, tC: Double, tdC: Double): Double {
        val tl = SoundingMath.lclTemperatureK(tC, tdC)
        val pl = pMb * (tl / (tC + 273.15)).pow(1.0 / (287.04 / 1005.7))
        return wetLift(pl, tl - 273.15, pMb)
    }

    /**
     * The downdraft CAPE (J/kg) and the downdraft parcel's surface temperature (C): the parcel starts at the middle of the driest (lowest mean theta-e) 100 mb layer that
     * begins 100 to 400 mb above the surface, at its wet-bulb temperature, and sinks along a pseudo-adiabat to the ground; null when the profile is too short.
     */
    fun dcape(pr: SoundingMath.Profile): Pair<Double, Double>? {
        val sfcP = pr.p[0]
        val te = DoubleArray(pr.size) { SoundingMath.thetaE(pr.p[it], pr.t[it], pr.td[it]) }
        fun teAt(pMb: Double): Double = SoundingMath.thetaE(pMb, pr.tAt(pMb), pr.tdAt(pMb))
        var minMean = 1000.0; var minP = Double.NaN
        for (i in 0 until pr.size) {
            if (pr.p[i] < sfcP - 400.0 || pr.p[i] > sfcP - 100.0) continue
            val pb = pr.p[i]; val pt = pb - 100.0
            var sum = 0.0; var n = 0
            for (j in 0 until pr.size) if (pr.p[j] < pb && pr.p[j] > pt) { sum += te[j]; n++ }
            val mean = (0.5 * (teAt(pb) + teAt(pt)) + sum) / (n + 1)
            if (mean < minMean) { minMean = mean; minP = pb - 50.0 }
        }
        if (minP.isNaN()) return null
        var top = -1
        for (k in 0 until pr.size) if (pr.p[k] >= minP) top = k
        if (top < 0) return null
        var pe1 = minP; var te1 = pr.tAt(pe1); var h1 = pr.zAt(pe1)
        var tp1 = wetBulb(minP, te1, pr.tdAt(minP))
        var tote = 0.0
        for (k in top downTo 0) {
            val pe2 = pr.p[k]; val te2 = pr.t[k]; val h2 = pr.z[k]
            val tp2 = wetLift(pe1, tp1, pe2)
            val d1 = (tp1 - te1) / (te1 + 273.15); val d2 = (tp2 - te2) / (te2 + 273.15)
            tote += 9.8 * (d1 + d2) / 2.0 * (h2 - h1)
            pe1 = pe2; te1 = te2; h1 = h2; tp1 = tp2
        }
        return tote to tp1
    }

    /** The NWS heat index (F) from the temperature (F) and relative humidity (%): the Rothfusz regression with its two adjustments. */
    fun heatIndex(tF: Double, rh: Double): Double {
        val simple = 0.5 * (tF + 61.0 + (tF - 68.0) * 1.2 + rh * 0.094)
        if ((simple + tF) / 2.0 < 80.0) return simple
        var hi = -42.379 + 2.04901523 * tF + 10.14333127 * rh - 0.22475541 * tF * rh - 0.00683783 * tF * tF - 0.05481717 * rh * rh +
            0.00122874 * tF * tF * rh + 0.00085282 * tF * rh * rh - 0.00000199 * tF * tF * rh * rh
        if (rh < 13.0 && tF in 80.0..112.0) hi -= (13.0 - rh) / 4.0 * kotlin.math.sqrt((17.0 - kotlin.math.abs(tF - 95.0)) / 17.0)
        else if (rh > 85.0 && tF in 80.0..87.0) hi += (rh - 85.0) / 10.0 * (87.0 - tF) / 5.0
        return hi
    }

    /** Where the wind blows from (degrees) for a (u, v); a calm wind is 0, as SHARPpy's arctan2(0, 0) gives. */
    private fun fromDirection(u: Double, v: Double): Double {
        if (hypot(u, v) < 1e-9) return 0.0
        val d = kotlin.math.atan2(-u, -v) * 180.0 / PI
        return if (d < 0.0) d + 360.0 else d
    }

    /**
     * SHARPpy's inferred temperature advection (params.inferred_temp_adv, the thermal-wind form of the geostrophic advection, Bluestein eq. 4.1.139) for layers of 100 mb from
     * the surface up, in C per hour (positive: warm advection, the wind veering with height). SHARPpy keeps its "average temperature" as twice the sum of the two
     * layer-end temperatures, and its values come out much smaller than the ones on SPC's graphic; both are kept as they are. [latitude] defaults to SHARPpy's 35.
     */
    fun inferredAdvection(pr: SoundingMath.Profile, w: WindColumn, latitude: Double = 35.0): List<SoundingMath.Advection> {
        val lat = if (latitude.isNaN()) 35.0 else latitude
        val lastP = (0 until pr.size).map { pr.p[it] }.filter { it >= 100.0 }.minOrNull() ?: return emptyList()
        val levels = ArrayList<Double>()
        var p = pr.p[0]
        while (p > lastP) { levels += p; p -= 100.0 }
        val omega = 2.0 * PI / 86164.0
        val f = 2.0 * omega * sin(lat * PI / 180.0)
        val multiplier = (f / 9.81) * (PI / 180.0)
        val out = ArrayList<SoundingMath.Advection>()
        for (i in 1 until levels.size) {
            val pb = levels[i - 1]; val pt = levels[i]
            val tb = pr.tAt(pb); val tt = pr.tAt(pt); val hb = pr.zAt(pb); val ht = pr.zAt(pt)
            val vb = w.at(pb); val vt = w.at(pt); val m = meanBetweenPressures(w, pb, pt, weighted = true)
            if (ht == hb || vb == null || vt == null || m == null) continue
            val bottomDir = fromDirection(vb.first, vb.second)
            var topDir = fromDirection(vt.first, vt.second) + 180.0 - bottomDir        // turn the bottom wind to 180
            if (topDir < 0.0) topDir += 360.0 else if (topDir >= 360.0) topDir -= 360.0
            val dTheta = topDir - 180.0
            val speed = hypot(m.first, m.second)
            val avgTemp = ((tt + 273.15) + (tb + 273.15)) * 2.0
            out += SoundingMath.Advection(pb, pt, multiplier * speed * speed * avgTemp * (dTheta / (ht - hb)) * 3600.0)
        }
        return out
    }

    // ---- the best-guess precipitation type (SHARPpy watch_type.py: init_phase, posneg_temperature, best_guess_precip; adapted there from SHARP code of Rich Thompson, SPC)

    /** The precipitation type SPC prints and what led to it: the source layer's phase and temperature, and the warm and cold areas (J/kg) of the temperature profile below it. */
    class PrecipGuess(val type: String, val phase: Int, val sourcePressure: Double, val sourceTempC: Double, val positiveArea: Double, val negativeArea: Double)

    private fun rh(pr: SoundingMath.Profile, p: Double, t: Double, td: Double) = 100.0 * SoundingMath.mixingRatio(p, td) / SoundingMath.mixingRatio(p, t)

    private fun rhAt(pr: SoundingMath.Profile, p: Double) = rh(pr, p, pr.tAt(p), pr.tdAt(p))

    fun precipType(pr: SoundingMath.Profile): PrecipGuess {
        if (pr.size < 3) return PrecipGuess("Unknown", -1, Double.NaN, Double.NaN, 0.0, 0.0)
        // init_phase: the source is the highest 50 mb layer below 5 km AGL that is over 80 % RH at its top and its bottom; its phase follows its middle temperature
        val tops = (0 until pr.size).filter { pr.z[it] in 0.0..5000.0 && rh(pr, pr.p[it], pr.t[it], pr.td[it]) > 80.0 }.map { pr.p[it] }
        var highest = -1
        for ((j, top) in tops.withIndex()) {
            val bottom = top + 50.0
            if (bottom <= pr.p[0] && rhAt(pr, bottom) > 80.0) highest = maxOf(highest, j)
        }
        var phase = -1; var srcP = Double.NaN; var srcT = Double.NaN
        if (highest >= 0) {
            srcP = tops[highest] + 50.0 - 25.0
            srcT = pr.tAt(srcP)
            phase = if (srcT > 0.0) 0 else if (srcT > -5.0) 1 else if (srcT > -9.0) 1 else 3
        }
        // posneg_temperature: from the source level down to the surface, the energy of the warm and the cold parts once a warm layer has been found
        var tpos = 0.0; var tneg = 0.0
        if (pr.p[pr.size - 1] <= 500.0 || pr.p[pr.size - 1] <= 850.0) {
            val upper = if (phase >= 0 && srcP > 0.0) srcP else 500.0
            var upperIndex = 0
            for (i in 0 until pr.size) if (pr.p[i] > upper) upperIndex = i
            var h1 = pr.zAt(upper); var t1 = pr.tAt(upper)
            var warm = false; var cold = false; var totPos = 0.0; var totNeg = 0.0
            for (i in upperIndex downTo 0) {
                val t2 = pr.tAt(pr.p[i]); val h2 = pr.z[i]
                val d1 = (0.0 - t1) / (t1 + 273.15); val d2 = (0.0 - t2) / (t2 + 273.15)
                val energy = 9.8 * (d1 + d2) / 2.0 * (h2 - h1)
                if (t2 > 0.0 && !warm) warm = true
                if (t2 < 0.0 && warm && !cold) cold = true
                if (warm) { if (energy > 0.0) totPos += energy else totNeg += energy }
                t1 = t2; h1 = h2
            }
            if (warm && cold) { tpos = totPos; tneg = totNeg }
        }
        val sfc = pr.t[0]
        val type = when {
            phase < 0 -> "None"
            phase == 0 && tneg >= 0.0 && sfc > 0.0 -> "Rain"
            phase == 3 && tpos <= 0.0 && sfc <= 0.0 -> "Snow"
            phase == 1 && tpos <= 0.0 && sfc > 0.0 -> "Rain"
            phase == 1 && tpos <= 0.0 && sfc <= 0.0 -> if (pr.zAt(srcP) >= 3000.0) (if (srcT <= -4.0) "Sleet and Snow" else "Sleet") else "Freezing Rain/Drizzle"
            phase == 3 && tpos <= 0.0 && sfc > 0.0 -> if (sfc > 4.0) "Rain" else "Snow"
            tpos > 0.0 -> if (-tneg > 0.62 * tpos + 60.0) "Sleet" else if (sfc <= 0.0) "Freezing Rain" else "Rain"
            else -> "Unknown"
        }
        return PrecipGuess(type, phase, srcP, srcT, tpos, tneg)
    }
}
