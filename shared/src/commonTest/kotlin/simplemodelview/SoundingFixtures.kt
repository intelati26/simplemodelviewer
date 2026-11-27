package simplemodelview

import kotlin.math.exp

/** Hand-built model soundings for the tests: pressure from the hydrostatic equation, temperature, dewpoint and wind from functions of height. */
object SoundingFixtures {
    fun build(p0: Double, temp: (Double) -> Double, dew: (Double) -> Double, dir: (Double) -> Double = { 270.0 }, kt: (Double) -> Double = { 10.0 }, top: Double = 16000.0): Sounding {
        val levels = ArrayList<SoundingLevel>()
        var p = p0; var z = 0.0
        while (z <= top) {
            val t = temp(z)
            levels += SoundingLevel(p, t, Double.NaN, minOf(dew(z), t), dir(z), kt(z), z)
            val tMean = (t + temp(z + 250.0)) / 2 + 273.15
            p *= exp(-9.80665 * 250.0 / (287.04 * tMean)); z += 250.0
        }
        return Sounding(0, 0, levels, emptyMap())
    }

    fun analysis(s: Sounding, latitude: Double = 35.0): SharpAnalysis = SharpAnalysis.compute(requireNotNull(SharpProfile.of(s, latitude)))

    /** Warm and moist under cold air aloft, the wind veering from the southeast at the ground to a strong westerly aloft. */
    val supercell = build(
        1000.0, { z -> maxOf(30.0 - 7.0 * z / 1000.0, -70.0) }, { z -> 23.0 - 7.8 * z / 1000.0 },
        dir = { z -> 140.0 + 130.0 * minOf(z, 6000.0) / 6000.0 }, kt = { z -> 12.0 + 50.0 * minOf(z, 8000.0) / 8000.0 },
    )
    val unstable = build(1000.0, { z -> maxOf(30.0 - 6.5 * z / 1000.0, -70.0) }, { z -> 22.0 - 5.0 * z / 1000.0 })
    val stable = build(1000.0, { z -> if (z < 1000) 10.0 + 4.0 * z / 1000.0 else maxOf(14.0 - 6.0 * (z - 1000.0) / 1000.0, -60.0) }, { z -> 5.0 - 2.0 * z / 1000.0 })
}
