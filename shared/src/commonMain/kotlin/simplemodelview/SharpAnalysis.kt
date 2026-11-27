package simplemodelview

import kotlin.math.pow

/** The best-guess precipitation type with what led to it (wxqt's SoundingPrecip::Result). */
class SharpPrecip(val type: String = "", val phase: Int = -1, val sourcePressure: Double = SHARP_MISSING, val sourceTemp: Double = SHARP_MISSING, val positiveArea: Double = 0.0, val negativeArea: Double = 0.0, val surfaceTempC: Double = SHARP_MISSING)

/** Everything a SHARPpy / SPC sounding page prints for a profile (wxqt's SoundingAnalysis), computed once. */
class SharpAnalysis(val profile: SharpProfile) {
    lateinit var sb: SharpParcel; lateinit var ml: SharpParcel; lateinit var mu: SharpParcel
    var fcst = SharpParcel()
    var maxTempC = SHARP_MISSING
    var effective = SharpEffectiveLayer()
    var rightMover = SharpWind(); var leftMover = SharpWind(); var meanWind06 = SharpWind()
    var shear01 = SharpWind(); var shear03 = SharpWind(); var shear06 = SharpWind()
    var corfidi = SharpCorfidi()
    var criticalAngle = SHARP_MISSING
    var kin1 = SharpLayerKinematics(); var kin3 = SharpLayerKinematics(); var kinEff = SharpLayerKinematics(); var kin6 = SharpLayerKinematics(); var kin8 = SharpLayerKinematics()
    var kinCloud = SharpLayerKinematics(); var kinEbwd = SharpLayerKinematics(); var kin46 = SharpLayerKinematics()
    var brnShear = SHARP_MISSING; var downTempC = SHARP_MISSING
    var effectiveShearKt = SHARP_MISSING; var srh01 = SHARP_MISSING; var srh03 = SHARP_MISSING; var effectiveSrh = SHARP_MISSING
    var precipitableWaterIn = SHARP_MISSING; var meanMixingLow100 = SHARP_MISSING; var meanMixing03 = SHARP_MISSING
    var lapse03 = SHARP_MISSING; var lapse36 = SHARP_MISSING; var lapse700500 = SHARP_MISSING; var lapse850500 = SHARP_MISSING
    var dcape = SHARP_MISSING; var convectiveTemp = SHARP_MISSING; var freezingLevelAgl = SHARP_MISSING; var wetBulbZeroAgl = SHARP_MISSING; var surfaceRh = SHARP_MISSING
    var sarsSupercell = SharpSars.Result(); var sarsHail = SharpSars.Result()
    var kIndex = SHARP_MISSING; var totalTotals = SHARP_MISSING; var lowRh = SHARP_MISSING; var midRh = SHARP_MISSING
    var esp = SHARP_MISSING; var wndg = SHARP_MISSING; var sigSevere = SHARP_MISSING; var mmp = SHARP_MISSING
    var stpFixed = SHARP_MISSING; var stpEffective = SHARP_MISSING; var supercell = SHARP_MISSING; var hail = SHARP_MISSING
    var thetaEIndex = SHARP_MISSING
    var advection: List<SharpIndices.Advection> = emptyList()
    var precip = SharpPrecip()

    companion object {
        private const val KT_MS = 0.514444
        private fun have(v: Double) = v > -9998.0

        fun compute(p: SharpProfile): SharpAnalysis {
            val a = SharpAnalysis(p)
            a.sb = SharpParcels.lift(p, SharpParcelKind.SurfaceBased); a.ml = SharpParcels.lift(p, SharpParcelKind.MixedLayer); a.mu = SharpParcels.lift(p, SharpParcelKind.MostUnstable)
            a.effective = SharpIndices.effectiveInflowLayer(p, a.mu)
            a.rightMover = SharpIndices.stormMotion(p, a.mu, a.effective, false); a.leftMover = SharpIndices.stormMotion(p, a.mu, a.effective, true)
            a.meanWind06 = SharpIndices.meanWind(p, 0.0, 6000.0)
            a.shear01 = SharpIndices.bulkShear(p, 0.0, 1000.0); a.shear03 = SharpIndices.bulkShear(p, 0.0, 3000.0); a.shear06 = SharpIndices.bulkShear(p, 0.0, 6000.0)
            a.srh01 = SharpIndices.helicity(p, 0.0, 1000.0, a.rightMover); a.srh03 = SharpIndices.helicity(p, 0.0, 3000.0, a.rightMover)
            var effShearMs = 0.0
            if (a.effective.valid) {
                a.effectiveSrh = SharpIndices.helicity(p, a.effective.botAgl, a.effective.topAgl, a.rightMover)
                a.effectiveShearKt = SharpIndices.effectiveBulkShear(p, a.mu, a.effective)
                if (have(a.effectiveShearKt)) effShearMs = a.effectiveShearKt * KT_MS
            }
            a.precipitableWaterIn = SharpIndices.precipitableWater(p)
            a.meanMixingLow100 = SharpIndices.meanMixingRatioMb(p, p.sfcPres(), p.sfcPres() - 100.0)
            a.meanMixing03 = SharpIndices.meanMixingRatio(p, 0.0, 3000.0)
            a.lapse03 = SharpIndices.lapseRateAgl(p, 0.0, 3000.0)
            // SPC's printed 3-6 km lapse rate is between 3 and 6 km above sea level in wxqt's reading of it; kept as there (here the heights are above the profile's surface)
            a.lapse36 = SharpIndices.lapseRateAgl(p, 3000.0, 6000.0)
            a.lapse700500 = SharpIndices.lapseRateMb(p, 700.0, 500.0); a.lapse850500 = SharpIndices.lapseRateMb(p, 850.0, 500.0)
            val down = DoubleArray(1) { SHARP_MISSING }
            a.dcape = SharpIndices.dcape(p, down); a.downTempC = down[0]
            a.convectiveTemp = SharpIndices.convectiveTemperature(p)
            // the freezing and wet-bulb-zero heights: a surface already at or below 0 C has no melting level (0 m when exactly 0 C), even with a shallow warm layer above it
            fun zeroLevel(surfaceValue: Double, wet: Boolean): Double {
                if (!have(surfaceValue)) return SHARP_MISSING
                if (surfaceValue == 0.0) return 0.0
                if (surfaceValue < 0.0) return SHARP_MISSING
                val pr = SharpIndices.temperatureLevel(p, 0.0, wet)
                return if (have(pr)) p.toAgl(p.interpHght(pr)) else SHARP_MISSING
            }
            a.freezingLevelAgl = zeroLevel(p.tmpc[p.sfc], false); a.wetBulbZeroAgl = zeroLevel(p.wetbulb[p.sfc], true)
            a.surfaceRh = SharpIndices.surfaceRelativeHumidity(p)
            if (a.shear06.valid && have(a.srh01) && a.sb.valid) a.stpFixed = SharpIndices.stpFixed(a.sb.cape, a.sb.lclHght, a.srh01, a.shear06.speed() * KT_MS)
            if (a.effective.valid && have(a.effectiveSrh) && a.ml.valid) {
                a.stpEffective = SharpIndices.stpEffective(a.ml.cape, a.effectiveSrh, effShearMs, a.ml.lclHght, a.ml.cin)
                a.supercell = SharpIndices.supercellComposite(a.mu.cape, a.effectiveSrh, effShearMs)
            } else { a.stpEffective = 0.0; a.supercell = 0.0 }
            a.hail = SharpIndices.significantHail(p, a.mu)

            // the forecast surface parcel: the temperature 100 mb up plus 2 K brought down dry-adiabatically, with the dewpoint of the mean mixing ratio of the lowest 100 mb
            if (have(p.sfcPres())) {
                val sfcP = p.sfcPres(); val mixed = sfcP - 100.0
                val tMix = p.interpTemp(mixed); val meanW = SharpIndices.meanMixingRatioMb(p, sfcP, mixed)
                if (have(tMix) && have(meanW)) {
                    a.maxTempC = ((tMix + SharpThermo.ZERO_C_K) + 2.0) * (sfcP / mixed).pow(SharpThermo.ROCP) - SharpThermo.ZERO_C_K
                    val td = SharpThermo.tempAtMixingRatio(meanW, sfcP)
                    if (have(td)) a.fcst = SharpParcels.liftFrom(p, sfcP, a.maxTempC, td)
                }
            }
            run {
                fun at(agl: Double) = SharpIndices.presAtAgl(p, agl)
                val sfcP = p.sfcPres(); val storm = a.rightMover
                a.kin1 = SharpIndices.layerKinematics(p, sfcP, at(1000.0), storm); a.kin3 = SharpIndices.layerKinematics(p, sfcP, at(3000.0), storm)
                a.kin6 = SharpIndices.layerKinematics(p, sfcP, at(6000.0), storm); a.kin8 = SharpIndices.layerKinematics(p, sfcP, at(8000.0), storm)
                a.kin46 = SharpIndices.layerKinematics(p, at(4000.0), at(6000.0), storm)
                if (a.effective.valid) {
                    a.kinEff = SharpIndices.layerKinematics(p, a.effective.pBot, a.effective.pTop, storm)
                    if (a.mu.valid && have(a.mu.elHght)) {
                        val top = a.effective.botAgl + (a.mu.elHght - a.effective.botAgl) / 2.0
                        a.kinEbwd = SharpIndices.layerKinematics(p, a.effective.pBot, at(top), storm)
                    }
                }
                // SPC's "LCL - EL (Cloud Layer)" is the surface-based parcel's LCL to EL
                if (a.sb.valid && have(a.sb.lclPres) && have(a.sb.elPres)) a.kinCloud = SharpIndices.layerKinematics(p, a.sb.lclPres, a.sb.elPres, storm)
                a.brnShear = SharpIndices.brnShear(p)
            }
            a.corfidi = SharpIndices.corfidi(p); a.criticalAngle = SharpIndices.criticalAngle(p, a.rightMover)
            a.kIndex = SharpIndices.kIndex(p); a.totalTotals = SharpIndices.totalTotals(p)
            // SPC's LowRH / MidRH: the pressure-weighted mean relative humidity from the surface to 1.5 km AGL and from 1.5 to 4 km AGL
            val p15 = SharpIndices.presAtAgl(p, 1500.0); val p40 = SharpIndices.presAtAgl(p, 4000.0)
            if (have(p15) && have(p.sfcPres())) {
                a.lowRh = SharpIndices.meanRelativeHumidity(p, p.sfcPres(), p15)
                if (have(p40)) a.midRh = SharpIndices.meanRelativeHumidity(p, p15, p40)
            }
            if (a.ml.valid) {
                a.esp = SharpIndices.esp(a.ml.cape3km, a.ml.cape, a.lapse03)
                a.wndg = SharpIndices.wndg(p, a.ml.cape, a.ml.cin, a.lapse03)
                if (a.shear06.valid) a.sigSevere = SharpIndices.sigSevere(a.ml.cape, a.shear06.speed())
            }
            if (a.mu.valid) a.mmp = SharpIndices.mmp(p, a.mu.cape)
            // the SARS analogues need the mixed-layer and most-unstable parcels and the shears / SRH
            val shear03 = SharpIndices.bulkShear(p, 0.0, 3000.0); val shear09 = SharpIndices.bulkShear(p, 0.0, 9000.0); val temp500 = p.interpTemp(500.0)
            if (a.ml.valid && a.mu.valid && shear03.valid && shear09.valid && a.shear06.valid && have(a.srh01) && have(a.srh03) && have(temp500) && have(a.lapse700500) && have(a.mu.lplPres) && have(a.mu.lplDwpt)) {
                val mumr = SharpThermo.mixingRatio(a.mu.lplPres, a.mu.lplDwpt)
                a.sarsHail = SharpSars.hail(mumr, a.mu.cape, temp500, a.lapse700500, a.shear06.speed() * KT_MS, shear09.speed() * KT_MS, shear03.speed() * KT_MS, a.srh03)
                a.sarsSupercell = SharpSars.supercell(a.ml.cape, a.ml.lclHght, temp500, a.lapse700500, a.shear06.speed(), a.srh01, shear03.speed(), shear09.speed(), a.srh03)
            }
            a.thetaEIndex = SharpIndices.thetaEIndex(p)
            a.advection = SharpIndices.inferredAdvection(p)
            a.precip = SharpPrecipGuess.bestGuess(p)
            return a
        }
    }
}
