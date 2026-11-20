package simplemodelview

/**
 * Rainfall from a chosen time to now, added up from MRMS's own totals. Whole hours come from the multi-sensor (gauge-corrected) totals, which MRMS posts
 * on the hour about an hour late; the start's part hour and the time after the last multi-sensor hour come from the radar-only totals (15 minutes, every
 * quarter hour; hourly ones every 2 minutes). Whole UTC days are one file each (kept in the day cache); the rest is covered by as few files as possible, the longest windows first (12, 6, 3, 1 h).
 */
object RainSince {
    /** One file to add: a total over [minutes] ending at [end]. [multiSensor]: a multi-sensor total (Pass 2, else Pass 1, else the radar-only one). */
    data class Piece(val end: Long, val minutes: Int, val multiSensor: Boolean) {
        val start: Long get() = end - minutes * 60L
        /** The MRMS products to try for this piece, in order. */
        val directories: List<String> get() = when {
            minutes == 15 -> listOf("RadarOnly_QPE_15M_00.00")
            multiSensor -> listOf("MultiSensor_QPE_${hh}H_Pass2_00.00", "MultiSensor_QPE_${hh}H_Pass1_00.00", "RadarOnly_QPE_${hh}H_00.00")
            else -> listOf("RadarOnly_QPE_${hh}H_00.00")
        }
        /** A whole UTC day of multi-sensor totals (midnight to midnight): kept, once it is final, in the app's day cache. */
        val isDay: Boolean get() = multiSensor && minutes == 1440 && floorMod(end, 86400L) == 0L
        private val hh: String get() = (minutes / 60).toString().padStart(2, '0')
    }

    /** What will be added: the pieces (oldest first), and the span they cover (the start rounded to the quarter hour; the end the newest radar total). */
    class Plan(val pieces: List<Piece>, val start: Long, val end: Long, val multiFrom: Long?, val multiTo: Long?)

    private val windows = listOf(72, 48, 24, 12, 6, 3, 1)

    /** The longest windows first, ending at [to] and going back to [from] (whole hours). */
    private fun hours(from: Long, to: Long, multiSensor: Boolean): List<Piece> {
        val out = ArrayList<Piece>()
        var end = to
        while (end > from) {
            val left = ((end - from) / 3600L).toInt()
            val w = windows.first { it <= left }
            out += Piece(end, w * 60, multiSensor)
            end -= w * 3600L
        }
        return out.asReversed()
    }

    /** Whole UTC days from [from] to [to] (both midnights), then the part days at either end as hours: [first] is the hours before the first midnight. */
    private fun multiSensorPieces(from: Long, to: Long): List<Piece> {
        val firstMidnight = floorTo(from + 86399L, 86400L)
        val lastMidnight = floorTo(to, 86400L)
        if (lastMidnight - firstMidnight < 86400L) return hours(from, to, true)
        val days = generateSequence(firstMidnight + 86400L) { it + 86400L }.takeWhile { it <= lastMidnight }.map { Piece(it, 1440, true) }.toList()
        return hours(from, firstMidnight, true) + days + hours(lastMidnight, to, true)
    }

    private fun quarters(from: Long, to: Long): List<Piece> =
        generateSequence(from + 900L) { it + 900L }.takeWhile { it <= to }.map { Piece(it, 15, false) }.toList()

    private fun floorTo(t: Long, step: Long) = t - floorMod(t, step)

    /**
     * The pieces from [start] to [radarEnd] (the newest 15-minute radar total), using multi-sensor totals for the whole hours up to [multiEnd] (the newest
     * multi-sensor hour; null when there is none).
     */
    fun plan(start: Long, multiEnd: Long?, radarEnd: Long): Plan {
        val s = floorTo(start + 450L, 900L)                 // the nearest quarter hour
        val r = floorTo(radarEnd, 900L)
        if (r <= s) return Plan(emptyList(), s, s, null, null)
        val a = floorTo(s + 3599L, 3600L)                   // the first whole hour
        if (a >= r) return Plan(quarters(s, r), s, r, null, null)
        val lastHour = floorTo(r, 3600L)
        val b = multiEnd?.let { minOf(floorTo(it, 3600L), lastHour) }?.takeIf { it > a } ?: a
        val pieces = quarters(s, a) + multiSensorPieces(a, b) + hours(b, lastHour, false) + quarters(lastHour, r)
        return Plan(pieces, s, r, a.takeIf { b > a }, b.takeIf { b > a })
    }

    /** The bucket key of an MRMS file of [directory] valid at [epoch]. */
    fun key(directory: String, epoch: Long): String {
        val c = civilFromEpoch(epoch)
        fun two(n: Int) = n.toString().padStart(2, '0')
        val day = "${c[0]}${two(c[1])}${two(c[2])}"
        return "CONUS/$directory/$day/MRMS_${directory}_$day-${two(c[3])}${two(c[4])}${two(c[5])}.grib2.gz"
    }

    /** Adds [b] into [a] cell by cell: a missing cell counts as nothing when the other has a value. */
    fun addInto(a: FloatArray, b: FloatArray) {
        for (i in a.indices) {
            val y = b.getOrElse(i) { Float.NaN }
            if (y.isNaN()) continue
            a[i] = if (a[i].isNaN()) y else a[i] + y
        }
    }
}
