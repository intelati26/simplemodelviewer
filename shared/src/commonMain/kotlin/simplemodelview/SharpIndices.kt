package simplemodelview

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A wind in knots (u east, v north); -9999 marks a missing one. */
class SharpWind(val u: Double = SHARP_MISSING, val v: Double = SHARP_MISSING) {
    val valid get() = u > -9998.0 && v > -9998.0
    fun speed() = if (valid) hypot(u, v) else SHARP_MISSING
    /** Meteorological: degrees the wind blows from. */
    fun direction(): Double {
        if (!valid) return SHARP_MISSING
        var d = atan2(-u, -v) * 180.0 / PI
        if (d < 0) d += 360.0
        return d
    }
}

class SharpEffectiveLayer(val valid: Boolean = false, val pBot: Double = SHARP_MISSING, val pTop: Double = SHARP_MISSING, val botAgl: Double = SHARP_MISSING, val topAgl: Double = SHARP_MISSING)

class SharpCorfidi(val upshear: SharpWind = SharpWind(), val downshear: SharpWind = SharpWind()) { val valid get() = upshear.valid && downshear.valid }

class SharpLayerKinematics(val shear: SharpWind = SharpWind(), val mean: SharpWind = SharpWind(), val stormRelative: SharpWind = SharpWind())

/** SHARPpy's sharptab indices (params.py, winds.py), by way of wxqt's SoundingIndices. */
object SharpIndices {
    private const val KT_MS = 0.514444
    private fun ctok(c: Double) = c + 273.15

    fun presAtAgl(p: SharpProfile, hAgl: Double) = p.interpPresAtHght(p.toMsl(hAgl))

    private fun mean(p: SharpProfile, fromAgl: Double, toAgl: Double, weighted: Boolean): SharpWind {
        val pb = presAtAgl(p, fromAgl); val pt = presAtAgl(p, toAgl)
        if (gone(pb) || gone(pt)) return SharpWind()
        var su = 0.0; var sv = 0.0; var sw = 0.0
        var pr = pb
        while (pr >= pt - 1e-9) {
            val uv = p.interpComponents(pr)
            if (uv != null) { val w = if (weighted) pr else 1.0; su += uv.first * w; sv += uv.second * w; sw += w }
            pr -= 1.0
        }
        return if (sw <= 0) SharpWind() else SharpWind(su / sw, sv / sw)
    }

    fun windAtAgl(p: SharpProfile, hAgl: Double): SharpWind {
        val pr = if (hAgl <= 0) p.sfcPres() else presAtAgl(p, hAgl)
        if (gone(pr)) return SharpWind()
        val uv = p.interpComponents(pr) ?: return SharpWind()
        return SharpWind(uv.first, uv.second)
    }

    fun bulkShear(p: SharpProfile, fromAgl: Double, toAgl: Double): SharpWind {
        val a = windAtAgl(p, fromAgl); val b = windAtAgl(p, toAgl)
        return if (!a.valid || !b.valid) SharpWind() else SharpWind(b.u - a.u, b.v - a.v)
    }

    fun meanWind(p: SharpProfile, fromAgl: Double, toAgl: Double) = mean(p, fromAgl, toAgl, true)

    /** Bunkers' internal-dynamics motion (SHARPpy's non_parcel_bunkers_motion): the plain 0 to 6 km mean wind moved 7.5 m/s across the 0 to 6 km shear. */
    private fun bunkers(p: SharpProfile, side: Double): SharpWind {
        val d = 7.5 / KT_MS
        val m6 = mean(p, 0.0, 6000.0, false); val shear = bulkShear(p, 0.0, 6000.0)
        if (!m6.valid || !shear.valid) return SharpWind()
        val mag = hypot(shear.u, shear.v)
        if (mag < 1e-6) return SharpWind()
        return SharpWind(m6.u + side * d / mag * shear.v, m6.v - side * d / mag * shear.u)
    }

    fun bunkersRight(p: SharpProfile) = bunkers(p, 1.0)
    fun bunkersLeft(p: SharpProfile) = bunkers(p, -1.0)

    /** Helicity (m2/s2) between two heights for a storm motion. */
    fun helicity(p: SharpProfile, fromAgl: Double, toAgl: Double, storm: SharpWind): Double {
        if (!storm.valid) return SHARP_MISSING
        val pb = if (fromAgl <= 0) p.sfcPres() else presAtAgl(p, fromAgl); val pt = presAtAgl(p, toAgl)
        if (gone(pb) || gone(pt)) return SHARP_MISSING
        val us = ArrayList<Double>(); val vs = ArrayList<Double>()
        val first = p.interpComponents(pb) ?: return SHARP_MISSING
        us += first.first; vs += first.second
        for (i in 0 until p.size) if (p.pres[i] < pb && p.pres[i] > pt && !gone(p.u[i]) && !gone(p.v[i])) { us += p.u[i]; vs += p.v[i] }
        val last = p.interpComponents(pt) ?: return SHARP_MISSING
        us += last.first; vs += last.second
        var sum = 0.0
        for (i in 0 until us.size - 1) {
            val u1 = us[i] - storm.u; val v1 = vs[i] - storm.v; val u2 = us[i + 1] - storm.u; val v2 = vs[i + 1] - storm.v
            sum += u2 * v1 - u1 * v2
        }
        return sum * KT_MS * KT_MS
    }

    private fun mixingRatioAt(p: SharpProfile, pMb: Double): Double { val td = p.interpDwpt(pMb); return if (gone(td)) SHARP_MISSING else SharpThermo.mixingRatio(pMb, td) }

    /** Precipitable water (inches) from the surface to [topMb]. */
    fun precipitableWater(p: SharpProfile, topMb: Double = 400.0): Double {
        val pb = p.sfcPres()
        if (gone(pb) || topMb >= pb) return SHARP_MISSING
        val ps = arrayListOf(pb); val ws = arrayListOf(mixingRatioAt(p, pb))
        for (i in 0 until p.size) if (p.pres[i] < pb && p.pres[i] > topMb && !gone(p.dwpc[i])) { ps += p.pres[i]; ws += SharpThermo.mixingRatio(p.pres[i], p.dwpc[i]) }
        ps += topMb; ws += mixingRatioAt(p, topMb)
        var pw = 0.0
        for (i in 0 until ps.size - 1) { if (gone(ws[i]) || gone(ws[i + 1])) return SHARP_MISSING; pw += 0.5 * (ws[i] + ws[i + 1]) * (ps[i] - ps[i + 1]) }
        return pw * 0.00040173
    }

    fun meanMixingRatio(p: SharpProfile, fromAgl: Double, toAgl: Double): Double {
        val pb = if (fromAgl <= 0) p.sfcPres() else presAtAgl(p, fromAgl); val pt = presAtAgl(p, toAgl)
        return meanMixingRatioMb(p, pb, pt)
    }

    fun meanMixingRatioMb(p: SharpProfile, pb: Double, pt: Double): Double {
        if (gone(pb) || gone(pt)) return SHARP_MISSING
        val db = p.interpDwpt(pb); val dt = p.interpDwpt(pt)
        if (gone(db) || gone(dt)) return SHARP_MISSING
        var sumTd = 0.5 * (db + dt); var sumP = 0.5 * (pb + pt); var n = 0
        for (i in 0 until p.size) if (p.pres[i] < pb && p.pres[i] > pt && !gone(p.dwpc[i])) { sumTd += p.dwpc[i]; sumP += p.pres[i]; n++ }
        return SharpThermo.mixingRatio(sumP / (n + 1), sumTd / (n + 1))
    }

    fun lapseRateMb(p: SharpProfile, fromMb: Double, toMb: Double): Double {
        val a = p.interpTemp(fromMb); val b = p.interpTemp(toMb); val ha = p.interpHght(fromMb); val hb = p.interpHght(toMb)
        if (gone(a) || gone(b) || gone(ha) || gone(hb) || hb == ha) return SHARP_MISSING
        return (a - b) / (hb - ha) * 1000.0
    }

    fun lapseRateAgl(p: SharpProfile, fromAgl: Double, toAgl: Double): Double {
        val pa = if (fromAgl <= 0) p.sfcPres() else presAtAgl(p, fromAgl); val pb = presAtAgl(p, toAgl)
        if (gone(pa) || gone(pb)) return SHARP_MISSING
        val a = p.interpTemp(pa); val b = p.interpTemp(pb)
        if (gone(a) || gone(b)) return SHARP_MISSING
        return (a - b) / (toAgl - fromAgl) * 1000.0
    }

    fun surfaceRelativeHumidity(p: SharpProfile): Double { val s = p.sfc; return SharpThermo.relativeHumidityPct(p.pres[s], p.tmpc[s], p.dwpc[s]) }

    // ---- SHARPpy's effective layer, storm motion and bulk shear

    fun effectiveInflowLayer(p: SharpProfile, mu: SharpParcel, minCape: Double = 100.0, maxCin: Double = -250.0): SharpEffectiveLayer {
        if (mu.cape == 0.0 || mu.cape < minCape || !(mu.cin > maxCin)) return SharpEffectiveLayer()
        var last = 0
        for (i in 0 until p.size) if (!gone(p.tmpc[i])) last = i
        fun lifted(i: Int) = SharpParcels.liftFrom(p, p.pres[i], p.tmpc[i], p.dwpc[i])
        var bottom = p.size
        for (i in p.sfc until last) {
            if (gone(p.tmpc[i]) || gone(p.dwpc[i])) continue
            val pcl = lifted(i)
            if (pcl.valid && pcl.cape >= minCape && pcl.cin > maxCin) { bottom = i; break }
        }
        if (bottom >= p.size) return SharpEffectiveLayer()
        var pTop = SHARP_MISSING
        var prev = bottom
        for (i in bottom + 1 until last) {
            // SHARPpy skips levels whose dewpoint or temperature is missing (or exactly zero)
            if (gone(p.dwpc[i]) || gone(p.tmpc[i]) || p.dwpc[i] == 0.0 || p.tmpc[i] == 0.0) continue
            val pcl = lifted(i)
            if (!pcl.valid || pcl.cape < minCape || pcl.cin <= maxCin) { pTop = min(p.pres[prev], p.pres[bottom]); break }
            prev = i
        }
        if (gone(pTop)) return SharpEffectiveLayer()
        return SharpEffectiveLayer(true, p.pres[bottom], pTop, p.toAgl(p.interpHght(p.pres[bottom])), p.toAgl(p.interpHght(pTop)))
    }

    fun stormMotion(p: SharpProfile, mu: SharpParcel, layer: SharpEffectiveLayer, leftMover: Boolean = false): SharpWind {
        if (!layer.valid || !(mu.cape > 100.0) || gone(mu.elHght)) return bunkers(p, if (leftMover) -1.0 else 1.0)
        val d = 7.5 / KT_MS
        val depth = mu.elHght - layer.botAgl
        val pTop = presAtAgl(p, layer.botAgl + depth * 0.65)
        if (gone(pTop)) return SharpWind()
        var su = 0.0; var sv = 0.0; var sw = 0.0
        var pr = layer.pBot
        while (pr >= pTop - 1e-9) { val uv = p.interpComponents(pr); if (uv != null) { su += uv.first * pr; sv += uv.second * pr; sw += pr }; pr -= 1.0 }
        val b = p.interpComponents(layer.pBot); val t = p.interpComponents(pTop)
        if (sw <= 0 || b == null || t == null) return SharpWind()
        val shu = t.first - b.first; val shv = t.second - b.second
        val mag = hypot(shu, shv)
        if (mag < 1e-6) return SharpWind()
        val side = if (leftMover) -1.0 else 1.0
        return SharpWind(su / sw + side * d / mag * shv, sv / sw - side * d / mag * shu)
    }

    fun effectiveBulkShear(p: SharpProfile, mu: SharpParcel, layer: SharpEffectiveLayer): Double {
        if (!layer.valid || gone(mu.elHght)) return SHARP_MISSING
        val depth = (mu.elHght - layer.botAgl) / 2.0
        val pTop = presAtAgl(p, layer.botAgl + depth)
        val b = p.interpComponents(layer.pBot); val t = if (gone(pTop)) null else p.interpComponents(pTop)
        if (gone(pTop) || b == null || t == null) return SHARP_MISSING
        return hypot(t.first - b.first, t.second - b.second)
    }

    /** The lowest level going up where the profile (or the wet bulb) reaches [tempC]: a level that equals it, or a log-p crossing. Pressure, or -9999. */
    fun temperatureLevel(p: SharpProfile, tempC: Double, wetBulb: Boolean = false): Double {
        val series = if (wetBulb) p.wetbulb else p.tmpc
        var prevT = SHARP_MISSING; var prevLogP = SHARP_MISSING
        for (i in 0 until p.size) {
            if (gone(series[i]) || gone(p.pres[i]) || p.pres[i] <= 0) continue
            val t = series[i]; val lp = log10(p.pres[i])
            if (t == tempC) return p.pres[i]
            if (!gone(prevT) && (prevT - tempC) * (t - tempC) < 0) {
                val f = (tempC - prevT) / (t - prevT)
                return 10.0.pow(prevLogP + f * (lp - prevLogP))
            }
            prevT = t; prevLogP = lp
        }
        return SHARP_MISSING
    }

    /** The downdraft CAPE (J/kg) and, through [downTemp], the downdraft parcel's temperature when it reaches the ground (SPC's DownT). */
    fun dcape(p: SharpProfile, downTemp: DoubleArray? = null): Double {
        val sfcP = p.sfcPres()
        // the source: the centre of the driest (lowest mean theta-e) 100 mb layer in the lowest 400 mb; layers starting in the lowest 100 mb are skipped (SPC's own output does, SHARPpy does not)
        var minMean = 1000.0; var minP = SHARP_MISSING
        for (i in 0 until p.size) {
            if (gone(p.thetae[i]) || p.pres[i] < sfcP - 400.0 || p.pres[i] > sfcP - 100.0) continue
            val pb = p.pres[i]; val pt = pb - 100.0
            val t1 = p.interpThetae(pb); val t2 = p.interpThetae(pt)
            if (gone(t1) || gone(t2)) continue
            var sum = 0.0; var n = 0
            for (j in 0 until p.size) if (p.pres[j] < pb && p.pres[j] > pt && !gone(p.thetae[j])) { sum += p.thetae[j]; n++ }
            val mean = (0.5 * (t1 + t2) + sum) / (n + 1)
            if (mean < minMean) { minMean = mean; minP = pb - 50.0 }
        }
        if (gone(minP)) return SHARP_MISSING
        val idx = (0 until p.size).filter { !gone(p.thetae[it]) && !gone(p.pres[it]) }
        var top = 0; var found = false
        for (k in idx.indices) if (p.pres[idx[k]] >= minP) { top = k; found = true }
        if (!found) return SHARP_MISSING
        var pe1 = minP; var te1 = p.interpTemp(pe1); var h1 = p.interpHght(pe1)
        var tp1 = SharpThermo.wetBulb(minP, te1, p.interpDwpt(minP))
        var tote = 0.0
        for (k in top downTo 0) {
            val i = idx[k]
            val pe2 = p.pres[i]; val te2 = p.tmpc[i]; val h2 = p.hght[i]
            val tp2 = SharpThermo.wetLift(pe1, tp1, pe2)
            if (!gone(te1) && !gone(te2)) {
                val d1 = (tp1 - te1) / ctok(te1); val d2 = (tp2 - te2) / ctok(te2)
                tote += 9.8 * (d1 + d2) / 2.0 * (h2 - h1)
            }
            pe1 = pe2; te1 = te2; h1 = h2; tp1 = tp2
        }
        if (downTemp != null) downTemp[0] = tp1
        return tote
    }

    fun convectiveTemperature(p: SharpProfile): Double {
        val pres = p.sfcPres()
        var tmpc = p.tmpc[p.sfc]
        val mixing = meanMixingRatioMb(p, pres, pres - 100.0)
        if (gone(mixing) || gone(tmpc)) return SHARP_MISSING
        val dwpc = SharpThermo.tempAtMixingRatio(mixing, pres)
        val minCin = -1.0
        fun lift(t: Double): Pair<Boolean, Double> { val pcl = SharpParcels.liftFrom(p, pres, t, dwpc); return (pcl.valid && pcl.cape != 0.0) to pcl.cin }
        var (valid0, cin) = lift(tmpc + 25.0)
        if (!valid0 || cin < minCin) return SHARP_MISSING
        if (dwpc - tmpc > 0.0) tmpc += dwpc - tmpc + 4.0
        var r = lift(tmpc); var valid = r.first; cin = r.second
        var guard = 0
        while (guard < 200 && (!valid || cin < minCin)) {
            tmpc += if (valid && cin < -100.0) 2.0 else 0.5
            r = lift(tmpc); valid = r.first; cin = r.second
            guard++
        }
        return tmpc
    }

    fun stpFixed(sbCape: Double, sbLclM: Double, srh01: Double, bwd6Ms: Double): Double {
        var b = bwd6Ms
        val lclTerm = if (sbLclM < 1000.0) 1.0 else if (sbLclM > 2000.0) 0.0 else (2000.0 - sbLclM) / 1000.0
        if (b > 30.0) b = 30.0 else if (b < 12.5) b = 0.0
        return sbCape / 1500.0 * lclTerm * (srh01 / 150.0) * (b / 20.0)
    }

    fun stpEffective(mlCape: Double, effSrh: Double, effBwdMs: Double, mlLclM: Double, mlCin: Double): Double {
        val bwdTerm = if (effBwdMs < 12.5) 0.0 else if (effBwdMs > 30.0) 1.5 else effBwdMs / 20.0
        val lclTerm = if (mlLclM < 1000.0) 1.0 else if (mlLclM > 2000.0) 0.0 else (2000.0 - mlLclM) / 1000.0
        val cinTerm = if (mlCin > -50.0) 1.0 else if (mlCin < -200.0) 0.0 else (mlCin + 200.0) / 150.0
        return max(mlCape / 1500.0 * (effSrh / 150.0) * bwdTerm * lclTerm * cinTerm, 0.0)
    }

    fun supercellComposite(muCape: Double, effSrh: Double, effBwdMs: Double): Double {
        var b = effBwdMs
        if (b > 20.0) b = 20.0 else if (b < 10.0) b = 0.0
        return muCape / 1000.0 * (effSrh / 50.0) * (b / 20.0)
    }

    fun significantHail(p: SharpProfile, mu: SharpParcel): Double {
        if (gone(mu.lplPres) || gone(mu.lplDwpt)) return SHARP_MISSING
        var mumr = SharpThermo.mixingRatio(mu.lplPres, mu.lplDwpt)
        val frzP = temperatureLevel(p, 0.0)
        val frzH = if (gone(frzP)) SHARP_MISSING else p.interpHght(frzP)
        var h5 = p.interpTemp(500.0)
        val lr75 = lapseRateMb(p, 700.0, 500.0)
        val shear = bulkShear(p, 0.0, 6000.0)
        if (gone(frzH) || gone(h5) || gone(lr75) || !shear.valid) return SHARP_MISSING
        var shr06 = shear.speed() * KT_MS
        shr06 = min(27.0, max(7.0, shr06)); mumr = min(13.6, max(11.0, mumr)); h5 = min(-5.5, h5)
        var ship = -1.0 * (mu.cape * mumr * lr75 * h5 * shr06) / 42000000.0
        if (mu.cape < 1300.0) ship *= mu.cape / 1300.0
        if (lr75 < 5.8) ship *= lr75 / 5.8
        if (frzH < 2400.0) ship *= frzH / 2400.0
        return ship
    }

    fun thetaEIndex(p: SharpProfile): Double {
        if (p.size == 0 || gone(p.sfcPres())) return SHARP_MISSING
        val topPres = p.sfcPres() - 400.0
        var low = 1e9; var high = -1e9
        for (i in 0 until p.size) if (p.pres[i] >= topPres && !gone(p.pres[i]) && !gone(p.thetae[i])) { low = min(low, p.thetae[i]); high = max(high, p.thetae[i]) }
        return if (high < low) SHARP_MISSING else high - low
    }

    fun kIndex(p: SharpProfile): Double {
        val t8 = p.interpTemp(850.0); val t7 = p.interpTemp(700.0); val t5 = p.interpTemp(500.0); val td7 = p.interpDwpt(700.0); val td8 = p.interpDwpt(850.0)
        if (gone(t8) || gone(t7) || gone(t5) || gone(td7) || gone(td8)) return SHARP_MISSING
        return t8 - t5 + td8 - (t7 - td7)
    }

    fun totalTotals(p: SharpProfile): Double {
        val t8 = p.interpTemp(850.0); val t5 = p.interpTemp(500.0); val td8 = p.interpDwpt(850.0)
        if (gone(t8) || gone(t5) || gone(td8)) return SHARP_MISSING
        return (t8 - t5) + (td8 - t5)
    }

    /** The pressure-weighted mean relative humidity over the observed levels inside the layer (no interpolated ends: those drift a point from SPC's). */
    fun meanRelativeHumidity(p: SharpProfile, bottomMb: Double, topMb: Double): Double {
        var sum = 0.0; var weights = 0.0
        for (i in 0 until p.size) {
            if (p.pres[i] <= bottomMb && p.pres[i] >= topMb && !gone(p.tmpc[i]) && !gone(p.dwpc[i])) {
                val pr = p.pres[i]
                val t = p.interpTemp(pr); val td = p.interpDwpt(pr)
                if (gone(t) || gone(td)) continue
                sum += SharpThermo.relativeHumidityPct(pr, t, td) * pr; weights += pr
            }
        }
        return if (weights > 0.0) sum / weights else SHARP_MISSING
    }

    fun esp(mlCape3km: Double, mlCape: Double, lapse03: Double): Double {
        if (gone(mlCape3km) || gone(lapse03)) return SHARP_MISSING
        if (lapse03 < 7.0 || mlCape < 250.0) return 0.0
        return (mlCape3km / 50.0) * ((lapse03 - 7.0) / 1.0)
    }

    fun wndg(p: SharpProfile, mlCape: Double, mlCin: Double, lapse03: Double): Double {
        val m = meanWind(p, 1000.0, 3500.0)
        if (!m.valid || gone(lapse03) || gone(mlCape)) return SHARP_MISSING
        val wind = m.speed() * KT_MS
        val lr = if (lapse03 < 7.0) 0.0 else lapse03
        val cin = if (mlCin < -50.0) -50.0 else mlCin
        return (mlCape / 2000.0) * (lr / 9.0) * (wind / 15.0) * ((50.0 + cin) / 40.0)
    }

    fun sigSevere(mlCape: Double, shear06Kt: Double): Double = if (gone(mlCape) || gone(shear06Kt)) SHARP_MISSING else mlCape * shear06Kt * KT_MS

    fun mmp(p: SharpProfile, muCape: Double): Double {
        if (gone(muCape)) return SHARP_MISSING
        if (muCape < 100.0) return 0.0
        val bottoms = ArrayList<Double>(); val tops = ArrayList<Double>()
        for (i in 0 until p.size) {
            if (gone(p.hght[i]) || gone(p.pres[i])) continue
            val agl = p.toAgl(p.hght[i])
            if (agl <= 1000.0) bottoms += p.pres[i]
            if (agl >= 6000.0 && agl < 10000.0) tops += p.pres[i]
        }
        if (bottoms.isEmpty() || tops.isEmpty()) return SHARP_MISSING
        var maxShear = -1.0
        for (b in bottoms) for (t in tops) {
            val vb = p.interpComponents(b) ?: continue; val vt = p.interpComponents(t) ?: continue
            maxShear = max(maxShear, hypot(vt.first - vb.first, vt.second - vb.second))
        }
        if (maxShear < 0.0) return SHARP_MISSING
        val maxBulkShear = maxShear * KT_MS
        val lr38 = lapseRateAgl(p, 3000.0, 8000.0)
        val m = meanWind(p, 3000.0, 12000.0)
        if (gone(lr38) || !m.valid) return SHARP_MISSING
        val meanWindMs = m.speed() * KT_MS
        return 1.0 / (1.0 + exp(13.0 + -4.59e-2 * maxBulkShear + -1.16 * lr38 + -6.17e-4 * muCape + -0.17 * meanWindMs))
    }

    private fun meanBetweenPressures(p: SharpProfile, bottomMb: Double, topMb: Double): SharpWind {
        var su = 0.0; var sv = 0.0; var count = 0.0
        var pr = bottomMb
        while (pr >= topMb - 1e-9) { val uv = p.interpComponents(pr); if (uv != null) { su += uv.first; sv += uv.second; count += 1.0 }; pr -= 1.0 }
        return if (count == 0.0) SharpWind() else SharpWind(su / count, sv / count)
    }

    fun corfidi(p: SharpProfile): SharpCorfidi {
        if (p.size == 0 || gone(p.sfcPres())) return SharpCorfidi()
        val tropo = if (p.sfcPres() < 850.0) meanBetweenPressures(p, p.sfcPres(), 300.0) else meanBetweenPressures(p, 850.0, 300.0)
        val p15 = p.interpPresAtHght(p.toMsl(1500.0))
        if (gone(p15)) return SharpCorfidi()
        val low = meanBetweenPressures(p, p.sfcPres(), p15)
        if (!tropo.valid || !low.valid) return SharpCorfidi()
        val up = SharpWind(tropo.u - low.u, tropo.v - low.v)
        return SharpCorfidi(up, SharpWind(tropo.u + up.u, tropo.v + up.v))
    }

    fun criticalAngle(p: SharpProfile, storm: SharpWind): Double {
        if (!storm.valid || p.size == 0 || gone(p.sfcPres())) return SHARP_MISSING
        val p500 = p.interpPresAtHght(p.toMsl(500.0))
        if (gone(p500)) return SHARP_MISSING
        val a = p.interpComponents(p500); val s = p.interpComponents(p.sfcPres())
        if (a == null || s == null) return SHARP_MISSING
        val v1u = a.first - s.first; val v1v = a.second - s.second; val v2u = storm.u - s.first; val v2v = storm.v - s.second
        val m1 = hypot(v1u, v1v); val m2 = hypot(v2u, v2v)
        if (m1 < 1e-9 || m2 < 1e-9) return SHARP_MISSING
        val c = ((v1u * v2u + v1v * v2v) / (m1 * m2)).coerceIn(-1.0, 1.0)
        return acos(c) * 180.0 / PI
    }

    fun layerKinematics(p: SharpProfile, bottomMb: Double, topMb: Double, storm: SharpWind): SharpLayerKinematics {
        if (gone(bottomMb) || gone(topMb)) return SharpLayerKinematics()
        var shear = SharpWind()
        val b = p.interpComponents(bottomMb); val t = p.interpComponents(topMb)
        if (b != null && t != null) shear = SharpWind(t.first - b.first, t.second - b.second)
        var su = 0.0; var sv = 0.0; var sw = 0.0
        var pr = bottomMb
        while (pr >= topMb - 1e-9) { val uv = p.interpComponents(pr); if (uv != null) { su += uv.first * pr; sv += uv.second * pr; sw += pr }; pr -= 1.0 }
        var mean = SharpWind(); var sr = SharpWind()
        if (sw > 0.0) {
            mean = SharpWind(su / sw, sv / sw)
            if (storm.valid) sr = SharpWind(mean.u - storm.u, mean.v - storm.v)
        }
        return SharpLayerKinematics(shear, mean, sr)
    }

    fun brnShear(p: SharpProfile): Double {
        if (p.size == 0 || gone(p.sfcPres())) return SHARP_MISSING
        val p500 = p.interpPresAtHght(p.sfcHght() + 500.0); val p6 = p.interpPresAtHght(p.toMsl(6000.0))
        if (gone(p500) || gone(p6)) return SHARP_MISSING
        val low = layerKinematics(p, p.sfcPres(), p500, SharpWind()); val high = layerKinematics(p, p.sfcPres(), p6, SharpWind())
        if (!low.mean.valid || !high.mean.valid) return SHARP_MISSING
        val ms = hypot(high.mean.u - low.mean.u, high.mean.v - low.mean.v) * KT_MS
        return ms * ms / 2.0
    }

    /** The NWS heat index (F) from the temperature (F) and relative humidity (%): the Rothfusz regression with its two adjustments. */
    fun heatIndex(tF: Double, rh: Double): Double {
        if (gone(tF) || gone(rh)) return SHARP_MISSING
        val simple = 0.5 * (tF + 61.0 + (tF - 68.0) * 1.2 + rh * 0.094)
        if ((simple + tF) / 2.0 < 80.0) return simple
        var hi = -42.379 + 2.04901523 * tF + 10.14333127 * rh - 0.22475541 * tF * rh - 0.00683783 * tF * tF - 0.05481717 * rh * rh +
            0.00122874 * tF * tF * rh + 0.00085282 * tF * rh * rh - 0.00000199 * tF * tF * rh * rh
        if (rh < 13.0 && tF in 80.0..112.0) hi -= (13.0 - rh) / 4.0 * kotlin.math.sqrt((17.0 - kotlin.math.abs(tF - 95.0)) / 17.0)
        else if (rh > 85.0 && tF in 80.0..87.0) hi += (rh - 85.0) / 10.0 * (87.0 - tF) / 5.0
        return hi
    }

    /** One layer of the inferred temperature advection: between [pBottom] and [pTop] (mb), in C per hour (positive: warm advection, the wind veering with height). */
    class Advection(val pBottom: Double, val pTop: Double, val cPerHour: Double)

    private fun fromDirection(u: Double, v: Double): Double {
        if (hypot(u, v) < 1e-9) return 0.0            // a calm wind: 0 degrees, as SHARPpy's arctan2(0, 0) gives
        val d = atan2(-u, -v) * 180.0 / PI
        return if (d < 0.0) d + 360.0 else d
    }

    /**
     * SHARPpy's inferred temperature advection (params.inferred_temp_adv, the thermal-wind form of the geostrophic advection, Bluestein eq. 4.1.139) for layers of 100 mb from the surface
     * up, as wxqt's SoundingAdvection: SHARPpy keeps its "average temperature" as twice the sum of the two layer-end temperatures, and its values come out much smaller than the ones on
     * SPC's graphic; both are kept as they are. [latitude] is the profile's (35 when unknown, as SHARPpy's).
     */
    fun inferredAdvection(p: SharpProfile, latitude: Double = p.latitude): List<Advection> {
        if (p.size == 0 || gone(p.sfcPres())) return emptyList()
        var lastPressure = -1.0
        for (i in 0 until p.size) if (!gone(p.pres[i]) && p.pres[i] >= 100.0) lastPressure = p.pres[i]
        if (lastPressure < 0.0) return emptyList()
        val levels = ArrayList<Double>()
        var pr = p.sfcPres()
        while (pr > lastPressure) { levels += pr; pr -= 100.0 }
        val omega = 2.0 * PI / 86164.0
        val f = 2.0 * omega * kotlin.math.sin(latitude * PI / 180.0)
        val multiplier = (f / 9.81) * (PI / 180.0)
        val out = ArrayList<Advection>()
        for (i in 1 until levels.size) {
            val pb = levels[i - 1]; val pt = levels[i]
            val tb = p.interpTemp(pb); val tt = p.interpTemp(pt); val hb = p.interpHght(pb); val ht = p.interpHght(pt)
            val vb = p.interpComponents(pb); val vt = p.interpComponents(pt)
            if (gone(tb) || gone(tt) || gone(hb) || gone(ht) || ht == hb || vb == null || vt == null) continue
            val m = meanBetweenPressuresWeighted(p, pb, pt) ?: continue
            val bottomDir = fromDirection(vb.first, vb.second)
            var topDir = fromDirection(vt.first, vt.second) + 180.0 - bottomDir        // turn the bottom wind to 180
            if (topDir < 0.0) topDir += 360.0 else if (topDir >= 360.0) topDir -= 360.0
            val dTheta = topDir - 180.0
            val speed = hypot(m.first, m.second) * KT_MS
            val avgTemp = ((tt + 273.15) + (tb + 273.15)) * 2.0
            out += Advection(pb, pt, multiplier * speed * speed * avgTemp * (dTheta / (ht - hb)) * 3600.0)
        }
        return out
    }

    private fun meanBetweenPressuresWeighted(p: SharpProfile, bottomMb: Double, topMb: Double): Pair<Double, Double>? {
        var su = 0.0; var sv = 0.0; var sw = 0.0
        var pr = bottomMb
        while (pr >= topMb - 1e-9) { val uv = p.interpComponents(pr); if (uv != null) { su += uv.first * pr; sv += uv.second * pr; sw += pr }; pr -= 1.0 }
        return if (sw <= 0.0) null else (su / sw) to (sv / sw)
    }
}
