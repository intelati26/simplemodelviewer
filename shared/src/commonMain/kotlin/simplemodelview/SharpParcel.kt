package simplemodelview

import kotlin.math.floor
import kotlin.math.min

/** One lifted parcel and what it does (wxqt's SoundingParcel::Parcel). Heights are meters above the ground, pressures mb; -9999 = not found. */
class SharpParcel {
    var lplPres = SHARP_MISSING; var lplTemp = SHARP_MISSING; var lplDwpt = SHARP_MISSING
    var lclPres = SHARP_MISSING; var lclHght = SHARP_MISSING
    var lfcPres = SHARP_MISSING; var lfcHght = SHARP_MISSING
    var elPres = SHARP_MISSING; var elHght = SHARP_MISSING
    var mplPres = SHARP_MISSING; var mplHght = SHARP_MISSING
    var cape = 0.0; var cin = 0.0; var cape3km = 0.0
    var liftedIndex500 = SHARP_MISSING; var liftedIndex300 = SHARP_MISSING
    val tracePres = ArrayList<Double>(); val traceTemp = ArrayList<Double>()
    var valid = false
}

enum class SharpParcelKind { SurfaceBased, MixedLayer, MostUnstable }

/** A port of SHARPpy's params.parcelx and its parcel starts, by way of wxqt's SoundingParcel (see [SharpProfile]). */
object SharpParcels {
    private fun ok(v: Double) = !gone(v)
    private fun ctok(c: Double) = c + SharpThermo.ZERO_C_K

    /** The start of the mixed-layer parcel: the mean potential temperature and the mixing ratio of the mean dewpoint at the mean pressure of the lowest [depthMb], put at the surface pressure. */
    fun mixedLayerStart(prof: SharpProfile, depthMb: Double): Triple<Double, Double, Double> {
        val pBot = prof.sfcPres(); val pTop = pBot - depthMb
        val tBot = prof.interpTemp(pBot); val tTop = prof.interpTemp(pTop); val dBot = prof.interpDwpt(pBot); val dTop = prof.interpDwpt(pTop)
        if (gone(tBot) || gone(tTop) || gone(dBot) || gone(dTop)) return Triple(pBot, SHARP_MISSING, SHARP_MISSING)
        var sumTheta = 0.0; var sumTd = 0.0; var sumP = 0.0; var nTheta = 0; var nTd = 0
        for (i in 0 until prof.size) {
            if (prof.pres[i] >= pBot || prof.pres[i] <= pTop) continue
            if (!gone(prof.tmpc[i])) { sumTheta += SharpThermo.theta(prof.pres[i], prof.tmpc[i], 1000.0); nTheta++ }
            if (!gone(prof.dwpc[i])) { sumTd += prof.dwpc[i]; sumP += prof.pres[i]; nTd++ }
        }
        val meanTheta = (0.5 * (SharpThermo.theta(pBot, tBot, 1000.0) + SharpThermo.theta(pTop, tTop, 1000.0)) + sumTheta) / (nTheta + 1)
        val meanTd = (0.5 * (dBot + dTop) + sumTd) / (nTd + 1)
        val meanP = (0.5 * (pBot + pTop) + sumP) / (nTd + 1)
        return Triple(pBot, SharpThermo.theta(1000.0, meanTheta, pBot), SharpThermo.tempAtMixingRatio(SharpThermo.mixingRatio(meanP, meanTd), pBot))
    }

    fun liftFrom(prof: SharpProfile, pres: Double, tmpc: Double, dwpc: Double): SharpParcel {
        val pcl = SharpParcel()
        if (prof.size < 3 || !ok(pres) || !ok(tmpc) || !ok(dwpc)) return pcl
        pcl.lplPres = pres; pcl.lplTemp = tmpc; pcl.lplDwpt = dwpc
        val sfcP = prof.sfcPres()
        val g = SharpThermo.GRAVITY
        var pbot = min(sfcP, pres)
        val (pe2, tp2) = SharpThermo.dryLift(pres, tmpc, dwpc)
        if (!ok(pe2) || pe2.isNaN()) return pcl
        val blupper = pe2
        val h2lcl = prof.interpHght(pe2)
        if (!ok(h2lcl)) return pcl
        pcl.lclPres = min(pe2, sfcP)
        pcl.lclHght = prof.toAgl(h2lcl)
        // the dry ascent to the LCL, for the drawn trace
        val thetaStart = SharpThermo.theta(pres, tmpc, 1000.0)
        pcl.tracePres += pres; pcl.traceTemp += tmpc
        var pp = floor(pres / 25.0) * 25.0
        while (pp > pe2) { if (pp < pres) { pcl.tracePres += pp; pcl.traceTemp += SharpThermo.theta(1000.0, thetaStart, pp) }; pp -= 25.0 }
        pcl.tracePres += pe2; pcl.traceTemp += tp2

        // the dry layer below the LCL on a 1 mb grid, compared in theta space
        val thetaParcel = SharpThermo.theta(pe2, tp2, 1000.0)
        val blmr = SharpThermo.mixingRatio(pres, dwpc)
        var totp = 0.0; var totn = 0.0; var tote = 0.0
        run {
            val tdef = ArrayList<Double>(); val hh = ArrayList<Double>()
            var p = pbot
            while (p >= blupper - 1e-9) {
                val te = prof.interpTemp(p); val td = prof.interpDwpt(p); val h = prof.interpHght(p)
                if (ok(te) && ok(td) && ok(h)) {
                    val tvEnv = SharpThermo.virtualTemp(p, SharpThermo.theta(p, te, 1000.0), td)
                    val tvPcl = SharpThermo.virtualTemp(p, thetaParcel, SharpThermo.tempAtMixingRatio(blmr, p))
                    tdef += (tvPcl - tvEnv) / ctok(tvEnv); hh += h
                }
                p -= 1.0
            }
            for (i in 0 until tdef.size - 1) {
                val lyre = g * (tdef[i] + tdef[i + 1]) / 2.0 * (hh[i + 1] - hh[i])
                if (lyre < 0.0) totn += lyre
            }
        }
        var cinhAtLfc = totn
        if (pbot > pe2) pbot = pe2

        // the moist ascent
        var lptr = prof.size
        for (i in 0 until prof.size) if (pbot >= prof.pres[i]) { lptr = i; break }
        if (lptr >= prof.size) return pcl
        var pe1 = pbot
        var h1 = prof.interpHght(pe1)
        var te1 = prof.interpVtmp(pe1)
        var tp1 = SharpThermo.wetLift(pe2, tp2, pe1)
        var lyre = 0.0; var lyrlast = 0.0
        var lfcP = SHARP_MISSING; var elP = SHARP_MISSING; var mplP = SHARP_MISSING
        var liDone5 = false; var liDone3 = false; var b3Set = false
        val lcl3 = pcl.lclHght < 3000.0
        pcl.cape3km = 0.0
        fun buoyant(p: Double, pStart: Double, tStart: Double, envV: Double): Boolean {
            val tw = SharpThermo.wetLift(pStart, tStart, p)
            return envV < SharpThermo.virtualTemp(p, tw, tw)
        }
        for (i in lptr until prof.size) {
            if (!ok(prof.tmpc[i]) || !ok(prof.vtmp[i])) continue
            val pe2l = prof.pres[i]; val h2 = prof.hght[i]; val te2 = prof.vtmp[i]
            val tp2l = SharpThermo.wetLift(pe1, tp1, pe2l)
            pcl.tracePres += pe2l; pcl.traceTemp += tp2l
            val tdef1 = (SharpThermo.virtualTemp(pe1, tp1, tp1) - te1) / ctok(te1)
            val tdef2 = (SharpThermo.virtualTemp(pe2l, tp2l, tp2l) - te2) / ctok(te2)
            lyrlast = lyre
            lyre = g * (tdef1 + tdef2) / 2.0 * (h2 - h1)
            if (lyre > 0.0) totp += lyre else if (pe2l > 500.0) totn += lyre
            tote += lyre
            val pelast = pe1
            pe1 = pe2l; te1 = te2; tp1 = tp2l

            // the energy within the lowest 3 km (only when the LCL is below 3 km)
            if (lcl3) {
                if (prof.toAgl(h1) <= 3000.0 && prof.toAgl(h2) >= 3000.0 && !b3Set) {
                    val pe3 = pelast
                    val h3 = prof.interpHght(pe3)
                    val te3 = prof.interpVtmp(pe3)
                    val tp3 = SharpThermo.wetLift(pe1, tp1, pe3)
                    var b3 = if (lyre > 0.0) totp - lyre else totp
                    val h4 = prof.toMsl(3000.0)
                    val pe4 = prof.interpPresAtHght(h4)
                    if (ok(pe4)) {
                        val te4 = prof.interpVtmp(pe4)
                        val tp4 = SharpThermo.wetLift(pe3, tp3, pe4)
                        val d3 = (SharpThermo.virtualTemp(pe3, tp3, tp3) - te3) / ctok(te3)
                        val d4 = (SharpThermo.virtualTemp(pe4, tp4, tp4) - te4) / ctok(te4)
                        val lyrf = g * (d3 + d4) / 2.0 * (h4 - h3)
                        if (lyrf > 0.0) b3 += lyrf
                    }
                    pcl.cape3km = b3; b3Set = true
                }
            } else pcl.cape3km = 0.0
            h1 = h2

            // the LFC: the energy turns from negative to positive
            if (lyre >= 0.0 && lyrlast <= 0.0) {
                val tp3 = tp1; val pe2x = pe1
                var pe3 = pelast
                if (buoyant(pe3, pe2x, tp3, prof.interpVtmp(pe3))) { lfcP = pe3; elP = SHARP_MISSING; mplP = SHARP_MISSING; cinhAtLfc = totn }
                else {
                    while (!buoyant(pe3, pe2x, tp3, prof.interpVtmp(pe3)) && pe3 > 0.0) pe3 -= 5.0
                    if (pe3 > 0.0) { lfcP = pe3; elP = SHARP_MISSING; mplP = SHARP_MISSING; cinhAtLfc = totn }
                }
                if (ok(lfcP) && lfcP >= pcl.lclPres) lfcP = pcl.lclPres
            }
            // the EL: the energy turns from positive to negative
            if (lyre <= 0.0 && lyrlast >= 0.0) {
                val tp3 = tp1; val pe2x = pe1
                var pe3 = pelast
                while (buoyant(pe3, pe2x, tp3, prof.interpVtmp(pe3)) && pe3 > 0.0) pe3 -= 5.0
                elP = pe3; mplP = SHARP_MISSING
            }
            // the MPL: past the EL, where the negative energy has used up the positive (SHARPpy's loop, h3 not advanced)
            if (tote < 0.0 && !ok(mplP) && ok(elP)) {
                var pe3 = pelast
                val h3 = prof.interpHght(pe3)
                var te3 = prof.interpVtmp(pe3)
                var tp3 = SharpThermo.wetLift(pe1, tp1, pe3)
                var totx = tote - lyre
                var pe2m = pelast
                while (totx > 0.0 && pe2m > 1.0) {
                    pe2m -= 1.0
                    val te2m = prof.interpVtmp(pe2m)
                    val tp2m = SharpThermo.wetLift(pe3, tp3, pe2m)
                    val h2m = prof.interpHght(pe2m)
                    val tdef3 = (SharpThermo.virtualTemp(pe3, tp3, tp3) - te3) / ctok(te3)
                    val tdef2m = (SharpThermo.virtualTemp(pe2m, tp2m, tp2m) - te2m) / ctok(te2m)
                    totx += g * (tdef3 + tdef2m) / 2.0 * (h2m - h3)
                    tp3 = tp2m; te3 = te2m; pe3 = pe2m
                }
                mplP = pe2m
            }
            if (prof.pres[i] <= 500.0 && !liDone5) {
                val a = prof.interpVtmp(500.0); val b = SharpThermo.wetLift(pe1, tp1, 500.0)
                pcl.liftedIndex500 = a - SharpThermo.virtualTemp(500.0, b, b); liDone5 = true
            }
            if (prof.pres[i] <= 300.0 && !liDone3) {
                val a = prof.interpVtmp(300.0); val b = SharpThermo.wetLift(pe1, tp1, 300.0)
                pcl.liftedIndex300 = a - SharpThermo.virtualTemp(300.0, b, b); liDone3 = true
            }
        }
        pcl.cape = totp
        pcl.cin = cinhAtLfc
        // no CAPE at all: no CIN (SHARPpy zeroes CIN whenever CAPE rounds down to 0; SPC keeps it unless CAPE is exactly 0)
        if (pcl.cape == 0.0) pcl.cin = 0.0
        if (ok(lfcP)) { pcl.lfcPres = lfcP; val h = prof.interpHght(lfcP); pcl.lfcHght = if (ok(h)) prof.toAgl(h) else SHARP_MISSING }
        if (ok(elP)) { pcl.elPres = elP; val h = prof.interpHght(elP); pcl.elHght = if (ok(h)) prof.toAgl(h) else SHARP_MISSING }
        if (ok(mplP)) { pcl.mplPres = mplP; val h = prof.interpHght(mplP); pcl.mplHght = if (ok(h)) prof.toAgl(h) else SHARP_MISSING }
        pcl.valid = true
        return pcl
    }

    fun lift(prof: SharpProfile, kind: SharpParcelKind): SharpParcel {
        if (prof.size < 3) return SharpParcel()
        val sfcP = prof.sfcPres()
        var pres = sfcP; var temp = prof.tmpc[prof.sfc]; var dwpt = prof.dwpc[prof.sfc]
        if (kind == SharpParcelKind.MixedLayer) {
            val s = mixedLayerStart(prof, 100.0); pres = s.first; temp = s.second; dwpt = s.third
        } else if (kind == SharpParcelKind.MostUnstable) {
            // SHARPpy's most_unstable_level: on a 1 mb grid lift every parcel dry to its LCL and then moist-adiabatically to 1000 mb; the warmest result marks the level
            var best = -1e9
            var pp = sfcP
            while (pp >= sfcP - 400.0 - 1e-9) {
                val t = prof.interpTemp(pp); val d = prof.interpDwpt(pp)
                if (ok(t) && ok(d)) {
                    val (lp, lt) = SharpThermo.dryLift(pp, t, d)
                    val mt = SharpThermo.wetLift(lp, lt, 1000.0)
                    if (mt > best + 1e-10) { best = mt; pres = pp; temp = t; dwpt = d }
                }
                pp -= 1.0
            }
        }
        return liftFrom(prof, pres, temp, dwpt)
    }
}
