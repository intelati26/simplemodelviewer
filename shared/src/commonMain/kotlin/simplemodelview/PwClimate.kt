package simplemodelview

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.floor

/**
 * The climatological precipitable water of a place and day, for the flash-flood step of the hazard chart (it asks whether the sounding's PW is over the mean plus two standard deviations).
 * The numbers are NCEP/NCAR Reanalysis 1's 1991-2020 daily means and the spread of the daily values about them, on its 2.5 degree grid over the contiguous U.S. and its surroundings
 * (see PwClimateData), blended in space and, between the stored days (every fourth), in time: wxqt-grib's GfsClimate, without its downloads.
 */
object PwClimate {
    private const val DAYS = 92
    private const val COLS = 29
    private const val ROWS = 15
    private const val WEST = 230.0       // degrees east of the first column
    private const val NORTH = 55.0       // degrees north of the first row
    private const val STEP = 2.5

    class Normal(val meanIn: Double, val sigmaIn: Double)

    @OptIn(ExperimentalEncodingApi::class)
    private fun decode(chunks: List<String>): ShortArray {
        val bytes = Base64.decode(chunks.joinToString(""))
        return ShortArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 0xFF) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
    }

    private val mean: ShortArray by lazy { decode(PwClimateData.MEAN) }
    private val sigma: ShortArray by lazy { decode(PwClimateData.SIGMA) }

    /** The day of the year, 0 to 364, counted from the month and day (29 February counts as 28 February; 1 March is day 59 in every year). */
    fun dayIndex(year: Int, month: Int, day: Int): Int {
        val before = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val d = if (leap && month == 2 && day == 29) 28 else day
        return (before[month - 1] + d - 1).coerceIn(0, 364)
    }

    /** The day index of a time (epoch seconds, UTC). */
    fun dayIndexOf(epochSeconds: Long): Int { val c = civilFromEpoch(epochSeconds); return dayIndex(c[0], c[1], c[2]) }

    private fun sample(data: ShortArray, k: Int, lat: Double, lonEast: Double): Double {
        val x = (lonEast - WEST) / STEP; val y = (NORTH - lat) / STEP
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        fun at(c: Int, r: Int) = data[(k * ROWS + r.coerceIn(0, ROWS - 1)) * COLS + c.coerceIn(0, COLS - 1)] / 10.0
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
    }

    /** The normal precipitable water (inches) and its spread at [lat], [lon] (west negative) on [dayIndex]; null outside the area the data covers (the contiguous U.S. and its edges). */
    fun at(lat: Double, lon: Double, dayIndex: Int): Normal? {
        val east = if (lon < 0) lon + 360.0 else lon
        if (lat > NORTH || lat < NORTH - (ROWS - 1) * STEP || east < WEST || east > WEST + (COLS - 1) * STEP) return null
        val k = (dayIndex / 4).coerceIn(0, DAYS - 1); val k2 = (k + 1) % DAYS
        val w = (dayIndex - k * 4) / 4.0
        fun blend(data: ShortArray) = sample(data, k, lat, east) * (1 - w) + sample(data, k2, lat, east) * w
        return Normal(blend(mean) / 25.4, blend(sigma) / 25.4)
    }
}
