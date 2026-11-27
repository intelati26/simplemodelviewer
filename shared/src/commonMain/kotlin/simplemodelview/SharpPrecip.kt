package simplemodelview

import kotlin.math.max

/** SHARPpy's best-guess precipitation type (watch_type.py: init_phase, posneg_temperature, best_guess_precip; adapted there from SHARP code of Rich Thompson, SPC), by way of wxqt's SoundingPrecip. */
object SharpPrecipGuess {
    private class Source(var pressure: Double = SHARP_MISSING, var phase: Int = -1, var temp: Double = SHARP_MISSING)

    private fun initPhase(p: SharpProfile): Source {
        val topPressures = ArrayList<Double>()   // the pressure of each level below 5 km AGL that is over 80 % RH
        for (i in 0 until p.size) {
            val agl = p.hght[i] - p.sfcHght()
            if (agl >= 5000.0 || agl < 0.0) continue
            if (gone(p.tmpc[i]) || gone(p.dwpc[i])) continue
            if (SharpThermo.relativeHumidityPct(p.pres[i], p.tmpc[i], p.dwpc[i]) > 80.0) topPressures += p.pres[i]
        }
        var highest = -1
        for (j in topPressures.indices) {
            val bottom = topPressures[j] + 50.0
            val t = p.interpTemp(bottom); val td = p.interpDwpt(bottom)
            if (!gone(t) && !gone(td) && SharpThermo.relativeHumidityPct(bottom, t, td) > 80.0) highest = max(highest, j)
        }
        val source = Source()
        if (highest < 0) return source
        source.pressure = topPressures[highest] + 50.0 - 25.0
        source.temp = p.interpTemp(source.pressure)
        if (gone(source.temp)) return source
        source.phase = if (source.temp > 0.0) 0 else if (source.temp > -5.0) 1 else if (source.temp > -9.0) 1 else 3
        return source
    }

    /** From the source level down to the surface, the energy (J/kg) of the warm and the cold parts of the temperature profile, counted once a warm layer has been found; both 0 unless a warm layer is followed by a cold one. */
    private fun posNeg(p: SharpProfile, start: Double): Pair<Double, Double> {
        if (gone(p.interpTemp(500.0)) && gone(p.interpTemp(850.0))) return 0.0 to 0.0
        val lowest = p.sfc
        val upper = if (start > 0.0) start else 500.0
        var upperIndex = 0
        for (i in 0 until p.size) if (p.pres[i] > upper) upperIndex = i
        var height1 = p.interpHght(upper); var temp1 = p.interpTemp(upper)
        var warm = false; var cold = false; var totPos = 0.0; var totNeg = 0.0
        for (i in upperIndex downTo lowest) {
            val pressure2 = p.pres[i]; val height2 = p.hght[i]; val temp2 = p.interpTemp(pressure2)
            if (gone(temp1) || gone(temp2) || gone(height1)) { temp1 = temp2; height1 = height2; continue }
            val d1 = (0.0 - temp1) / (temp1 + SharpThermo.ZERO_C_K); val d2 = (0.0 - temp2) / (temp2 + SharpThermo.ZERO_C_K)
            val layerEnergy = 9.8 * (d1 + d2) / 2.0 * (height2 - height1)
            if (temp2 > 0.0 && !warm) warm = true
            if (temp2 < 0.0 && warm && !cold) cold = true
            if (warm) { if (layerEnergy > 0.0) totPos += layerEnergy else totNeg += layerEnergy }
            temp1 = temp2; height1 = height2
        }
        return if (warm && cold) totPos to totNeg else 0.0 to 0.0
    }

    fun bestGuess(p: SharpProfile): SharpPrecip {
        if (p.size < 3) return SharpPrecip()
        val source = initPhase(p)
        val (tpos, tneg) = posNeg(p, if (source.phase >= 0) source.pressure else -1.0)
        val sfc = p.tmpc[p.sfc]
        val type = when {
            source.phase < 0 -> "None"
            source.phase == 0 && tneg >= 0.0 && sfc > 0.0 -> "Rain"
            source.phase == 3 && tpos <= 0.0 && sfc <= 0.0 -> "Snow"
            source.phase == 1 && tpos <= 0.0 && sfc > 0.0 -> "Rain"
            source.phase == 1 && tpos <= 0.0 && sfc <= 0.0 ->
                if (p.toAgl(p.interpHght(source.pressure)) >= 3000.0) (if (source.temp <= -4.0) "Sleet and Snow" else "Sleet") else "Freezing Rain/Drizzle"
            source.phase == 3 && tpos <= 0.0 && sfc > 0.0 -> if (sfc > 4.0) "Rain" else "Snow"
            tpos > 0.0 -> if (-tneg > 0.62 * tpos + 60.0) "Sleet" else if (sfc <= 0.0) "Freezing Rain" else "Rain"
            else -> "Unknown"
        }
        return SharpPrecip(type, source.phase, source.pressure, source.temp, tpos, tneg, sfc)
    }
}
