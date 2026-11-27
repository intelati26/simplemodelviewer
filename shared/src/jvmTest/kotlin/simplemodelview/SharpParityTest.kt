package simplemodelview
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
/**
 * The Kotlin sounding engine against wxqt-grib's C++ one (src/sounding), which was checked against SPC: the same profiles through both, every number compared. The expected numbers were made by
 * scripts/sharp-parity/harness.cpp from wxqt-grib's sources (see the README there to make them again).
 */
class SharpParityTest {
    private fun w(m: MutableMap<String, Double>, n: String, x: SharpWind) { m["$n.u"] = x.u; m["$n.v"] = x.v }
    private fun lk(m: MutableMap<String, Double>, n: String, k: SharpLayerKinematics) { w(m, "$n.shear", k.shear); w(m, "$n.mean", k.mean); w(m, "$n.sr", k.stormRelative) }
    private fun parcel(m: MutableMap<String, Double>, n: String, p: SharpParcel) {
        m["$n.cape"] = p.cape; m["$n.cin"] = p.cin; m["$n.lcl"] = p.lclHght; m["$n.lfc"] = p.lfcHght; m["$n.el"] = p.elHght; m["$n.mpl"] = p.mplHght; m["$n.cape3"] = p.cape3km; m["$n.li5"] = p.liftedIndex500; m["$n.li3"] = p.liftedIndex300; m["$n.lclp"] = p.lclPres
    }
    private fun resource(name: String): List<String> = requireNotNull(javaClass.getResourceAsStream("/sharp/$name")).bufferedReader().readLines()

    @Test fun everyNumberMatchesWxqtOnRealProfiles() {
        val cpp = HashMap<String, HashMap<String, String>>(); var cur: HashMap<String, String>? = null
        for (l in resource("wxqt_expected.txt")) {
            if (l.startsWith("PROFILE ")) { cur = HashMap(); cpp[l.substring(8)] = cur } else if (l == "ENDP") cur = null else { val i = l.indexOf(' '); cur?.put(l.substring(0, i), l.substring(i + 1)) }
        }
        val lines = resource("profiles.txt")
        var i = 0; var n = 0
        var compared = 0; var nonzeroCape = 0; var sarsHits = 0; val bad = HashMap<String, Int>(); val worst = HashMap<String, String>()
        while (i < lines.size) {
            val h = lines[i].split(' '); val name = h[1]; val lat = h[2].toDouble(); i++
            val pres = ArrayList<Double>(); val hg = ArrayList<Double>(); val t = ArrayList<Double>(); val td = ArrayList<Double>(); val wd = ArrayList<Double>(); val ws = ArrayList<Double>()
            while (lines[i] != "END") { val f = lines[i].split(' ').map { it.toDouble() }; pres += f[0]; hg += f[1]; t += f[2]; td += f[3]; wd += f[4]; ws += f[5]; i++ }
            i++
            val p = SharpProfile(pres.toDoubleArray(), hg.toDoubleArray(), t.toDoubleArray(), td.toDoubleArray(), wd.toDoubleArray(), ws.toDoubleArray(), lat)
            val a = SharpAnalysis.compute(p)
            val m = LinkedHashMap<String, Double>()
            parcel(m, "sb", a.sb); parcel(m, "ml", a.ml); parcel(m, "mu", a.mu); parcel(m, "fcst", a.fcst); m["maxT"] = a.maxTempC
            m["eff.valid"] = if (a.effective.valid) 1.0 else 0.0; m["eff.bot"] = a.effective.botAgl; m["eff.top"] = a.effective.topAgl; m["eff.pbot"] = a.effective.pBot; m["eff.ptop"] = a.effective.pTop
            w(m, "rm", a.rightMover); w(m, "lm", a.leftMover); w(m, "mw06", a.meanWind06)
            m["srh01"] = a.srh01; m["srh03"] = a.srh03; m["esrh"] = a.effectiveSrh; m["eshear"] = a.effectiveShearKt
            w(m, "corf.up", a.corfidi.upshear); w(m, "corf.dn", a.corfidi.downshear); m["crit"] = a.criticalAngle
            lk(m, "k1", a.kin1); lk(m, "k3", a.kin3); lk(m, "keff", a.kinEff); lk(m, "k6", a.kin6); lk(m, "k8", a.kin8); lk(m, "kcloud", a.kinCloud); lk(m, "kebwd", a.kinEbwd); lk(m, "k46", a.kin46)
            m["brn"] = a.brnShear; m["downT"] = a.downTempC; m["dcape"] = a.dcape; m["pw"] = a.precipitableWaterIn; m["mmix100"] = a.meanMixingLow100; m["mmix03"] = a.meanMixing03
            m["lapse03"] = a.lapse03; m["lapse36"] = a.lapse36; m["lapse75"] = a.lapse700500; m["lapse85"] = a.lapse850500
            m["convT"] = a.convectiveTemp; m["frz"] = a.freezingLevelAgl; m["wbz"] = a.wetBulbZeroAgl; m["sfcrh"] = a.surfaceRh
            m["k"] = a.kIndex; m["tt"] = a.totalTotals; m["lowrh"] = a.lowRh; m["midrh"] = a.midRh; m["esp"] = a.esp; m["wndg"] = a.wndg; m["sigsvr"] = a.sigSevere; m["mmp"] = a.mmp
            m["stpf"] = a.stpFixed; m["stpe"] = a.stpEffective; m["scp"] = a.supercell; m["ship"] = a.hail
            m["sars.sc.loose"] = a.sarsSupercell.looseMatches.toDouble(); m["sars.sc.sig"] = a.sarsSupercell.significant.toDouble(); m["sars.sc.prob"] = a.sarsSupercell.probability; m["sars.sc.q"] = a.sarsSupercell.quality.size.toDouble()
            m["sars.h.loose"] = a.sarsHail.looseMatches.toDouble(); m["sars.h.sig"] = a.sarsHail.significant.toDouble(); m["sars.h.prob"] = a.sarsHail.probability; m["sars.h.q"] = a.sarsHail.quality.size.toDouble()
            m["tei"] = a.thetaEIndex
            val ref = cpp[name]!!
            for ((k, v) in m) {
                val r = ref[k]?.toDouble() ?: continue; compared++
                val tol = maxOf(1e-3, abs(r) * 1e-4)
                if (abs(r - v) > tol && !(r.isNaN() && v.isNaN())) { bad[k] = (bad[k] ?: 0) + 1; if (!worst.containsKey(k)) worst[k] = "$name cpp=$r kt=$v" }
            }
            if (ref["precip"] != a.precip.type) { bad["precip"] = (bad["precip"] ?: 0) + 1; worst.putIfAbsent("precip", "$name cpp=${ref["precip"]} kt=${a.precip.type}") }
            if (a.mu.cape > 100) nonzeroCape++; if (a.sarsSupercell.looseMatches > 0 || a.sarsHail.looseMatches > 0) sarsHits++
            n++
        }
        assertTrue(compared > 5000 && nonzeroCape >= 20 && sarsHits >= 5, "compared $compared, with cape $nonzeroCape, with analogues $sarsHits")
        assertTrue(bad.isEmpty(), bad.entries.sortedByDescending { it.value }.take(10).joinToString("; ") { "${it.key}: ${it.value} mismatches, first ${worst[it.key]}" })
    }
}
