package simplemodelview

import kotlin.math.abs

/**
 * SARS, the Sounding Analogue System (SHARPpy's databases/sars.py, by way of wxqt's SoundingSars): a sounding is matched against historical ones on a few parameters; loose matches give the
 * probability, tighter "quality" matches are listed. The databases are the supercell one (Rich Thompson) and the hail one (Ryan Jewell), both NOAA SPC.
 */
object SharpSars {
    class SupercellRecord(val id: String, val category: Int, val mlcape: Double, val mllcl: Double, val srh1: Double, val shear6: Double, val temp500: Double, val lapse75: Double, val shear3: Double, val shear9: Double, val srh3: Double)
    class HailRecord(val id: String, val size: Double, val mucape: Double, val mumr: Double, val temp500: Double, val lapse75: Double, val shear3: Double, val shear6: Double, val shear9: Double, val srh3: Double)
    class Match(val id: String, val label: String = "", val size: Double = 0.0, val category: Int = 0)
    class Result(val quality: List<Match> = emptyList(), val looseMatches: Int = 0, val significant: Int = 0, val probability: Double = 0.0, val valid: Boolean = false)

    val supercellDatabase: List<SupercellRecord> by lazy {
        SharpSarsData.SUPERCELL.flatMap { it.lines() }.filter { it.isNotBlank() }.map { l ->
            val f = l.split(','); SupercellRecord(f[0], f[1].toInt(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toDouble(), f[8].toDouble(), f[9].toDouble(), f[10].toDouble())
        }
    }
    val hailDatabase: List<HailRecord> by lazy {
        SharpSarsData.HAIL.flatMap { it.lines() }.filter { it.isNotBlank() }.map { l ->
            val f = l.split(','); HailRecord(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toDouble(), f[8].toDouble(), f[9].toDouble())
        }
    }

    private fun within(value: Double, match: Double, range: Double) = value >= match - range && value <= match + range

    fun supercell(mlcape: Double, mllcl: Double, temp500: Double, lapse: Double, shear6: Double, srh1: Double, shear3: Double, shear9: Double, srh3: Double): Result {
        val rangeMlcape = if (mlcape == 0.0) 0.0 else 1300.0
        val rangeMlcapeT1 = mlcape * 0.25
        val rangeMllcl = 500.0; val rangeMllclT1 = 200.0; val rangeShr = 14.0; val rangeShrT1 = 10.0
        val rangeSrh = if (abs(srh1) < 50.0) 100.0 else srh1       // as SHARPpy writes it (not the absolute value)
        val rangeSrhT1 = if (abs(srh1) < 100.0) 50.0 else abs(srh1) * 0.30
        val rangeSrh3T1 = if (abs(srh3) < 100.0) 50.0 else abs(srh3) * 0.50
        val rangeTemp = 7.0; val rangeTempT1 = 5.0; val rangeLr = 1.0; val rangeLrT1 = 0.8; val rangeShr3T1 = 15.0; val rangeShr9T1 = 25.0
        var loose = 0; var tornadoes = 0
        val quality = ArrayList<Match>()
        for (r in supercellDatabase) {
            if (within(mlcape, r.mlcape, rangeMlcape) && within(mllcl, r.mllcl, rangeMllcl) && within(shear6, r.shear6, rangeShr) && within(srh1, r.srh1, rangeSrh) &&
                within(temp500, r.temp500, rangeTemp) && within(lapse, r.lapse75, rangeLr)) { loose++; if (r.category > 0) tornadoes++ }
            if (within(mlcape, r.mlcape, rangeMlcapeT1) && within(mllcl, r.mllcl, rangeMllclT1) && within(shear6, r.shear6, rangeShrT1) && within(srh1, r.srh1, rangeSrhT1) &&
                within(temp500, r.temp500, rangeTempT1) && within(lapse, r.lapse75, rangeLrT1) && within(shear3, r.shear3, rangeShr3T1) && within(shear9, r.shear9, rangeShr9T1) && within(srh3, r.srh3, rangeSrh3T1))
                quality += Match(r.id, if (r.category == 2) "SIGTOR" else if (r.category == 1) "WEAKTOR" else "NONTOR", category = r.category)
        }
        return Result(quality, loose, tornadoes, if (loose > 0 && mlcape > 0.0) tornadoes.toDouble() / loose else 0.0, true)
    }

    fun hail(mumr: Double, mucape: Double, temp500: Double, lapse: Double, shear6: Double, shear9: Double, shear3: Double, srh3: Double): Result {
        val rangeMumr = 2.0; val rangeMucape = mucape * 0.30
        val rangeMucapeT1 = if (mucape < 500.0) mucape * 0.50 else if (mucape < 2000.0) mucape * 0.25 else mucape * 0.20
        val rangeLr = 2.0; val rangeLrT1 = 0.4; val rangeTemp = 9.0; val rangeTempT1 = 1.5; val rangeShr6 = 12.0; val rangeShr6T1 = 6.0
        val rangeShr9 = 22.0; val rangeShr9T1 = 15.0; val rangeShr3 = 10.0; val rangeShr3T1 = 8.0
        val rangeSrhT1 = if (srh3 < 50.0) 25.0 else srh3 * 0.5
        val maxQuality = 15                            // SPC's graphic does not list more
        var loose = 0; var significant = 0
        val quality = ArrayList<Match>()
        for (r in hailDatabase) {
            if (within(mumr, r.mumr, rangeMumr) && within(mucape, r.mucape, rangeMucape) && within(lapse, r.lapse75, rangeLr) && within(temp500, r.temp500, rangeTemp) &&
                within(shear6, r.shear6, rangeShr6) && within(shear9, r.shear9, rangeShr9) && within(shear3, r.shear3, rangeShr3)) { loose++; if (r.size >= 2.0) significant++ }
            if (quality.size < maxQuality && within(mumr, r.mumr, rangeMumr) && within(mucape, r.mucape, rangeMucapeT1) && within(lapse, r.lapse75, rangeLrT1) && within(temp500, r.temp500, rangeTempT1) &&
                within(shear6, r.shear6, rangeShr6T1) && within(shear9, r.shear9, rangeShr9T1) && within(shear3, r.shear3, rangeShr3T1) && within(srh3, r.srh3, rangeSrhT1)) quality += Match(r.id, size = r.size)
        }
        return Result(quality, loose, significant, if (loose > 0 && mucape > 0.0) significant.toDouble() / loose else 0.0, true)
    }
}

/** SHARPpy's text box in the STP panel: the chance of an EF2+ tornado given each parameter alone (SPC prints the same list), by way of wxqt's SoundingTornadoProb. [colour] is 0 to 5. */
object SharpTornadoProb {
    class Result(val probability: Double = -1.0, val colour: Int = 0)
    private class Row(val below: Double, val probability: Double, val colour: Int)

    private fun lookup(value: Double, rows: List<Row>, last: Double, lastColour: Int): Result {
        if (value <= -9998.0) return Result()
        for (r in rows) if (value < r.below) return Result(r.probability, r.colour)
        return Result(last, lastColour)
    }

    fun fromMlCape(cape: Double): Result {
        if (cape <= -9998.0) return Result()
        if (cape == 0.0) return Result(0.0, 0)
        return lookup(cape, listOf(Row(250.0, 0.12, 1), Row(500.0, 0.14, 2), Row(1000.0, 0.16, 2), Row(1500.0, 0.15, 2), Row(2000.0, 0.13, 2), Row(2500.0, 0.14, 2), Row(3000.0, 0.18, 3), Row(4000.0, 0.20, 3)), 0.16, 3)
    }

    fun fromMlLcl(lcl: Double) = lookup(lcl, listOf(Row(750.0, 0.19, 3), Row(1000.0, 0.19, 3), Row(1250.0, 0.15, 2), Row(1500.0, 0.10, 1), Row(1750.0, 0.06, 0), Row(2000.0, 0.06, 0), Row(2500.0, 0.02, 0)), 0.0, 0)

    fun fromEffectiveSrh(srh: Double) = lookup(srh, listOf(Row(50.0, 0.06, 0), Row(100.0, 0.06, 0), Row(200.0, 0.08, 1), Row(300.0, 0.14, 2), Row(400.0, 0.20, 3), Row(500.0, 0.27, 3), Row(600.0, 0.38, 4), Row(700.0, 0.37, 4)), 0.42, 4)

    fun fromEffectiveShear(shear: Double): Result {
        if (shear <= -9998.0) return Result()
        if (shear == 0.0) return Result(0.0, 0)
        return lookup(shear, listOf(Row(20.0, 0.03, 0), Row(30.0, 0.05, 0), Row(40.0, 0.06, 0), Row(50.0, 0.12, 1), Row(60.0, 0.19, 3), Row(70.0, 0.27, 3), Row(80.0, 0.36, 4)), 0.26, 3)
    }

    fun fromStpEffective(stp: Double) = lookup(stp, listOf(Row(0.1, 0.06, 0), Row(0.5, 0.08, 1), Row(1.0, 0.12, 1), Row(2.0, 0.17, 2), Row(4.0, 0.25, 3), Row(6.0, 0.32, 4), Row(8.0, 0.34, 4), Row(10.0, 0.55, 5)), 0.58, 5)

    fun fromStpFixed(stp: Double) = lookup(stp, listOf(Row(0.1, 0.05, 0), Row(0.5, 0.06, 0), Row(1.0, 0.11, 1), Row(2.0, 0.17, 2), Row(3.0, 0.25, 3), Row(5.0, 0.25, 3), Row(7.0, 0.39, 4), Row(9.0, 0.55, 5)), 0.59, 5)
}
