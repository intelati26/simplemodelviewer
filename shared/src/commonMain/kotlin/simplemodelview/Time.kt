package simplemodelview

/** Days since 1970-01-01 of a civil date (proleptic Gregorian). */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097 + doe - 719468
}

val Cycle.epochSeconds: Long get() = daysFromCivil(year, month, day) * 86400L + hour * 3600L

/** "Thu 01 Oct 21Z" for an epoch second count (UTC). */
fun formatUtc(epochSeconds: Long): String {
    val days = floorDiv(epochSeconds, 86400L)
    val secs = floorMod(epochSeconds, 86400L)
    val weekday = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")[floorMod(days, 7L).toInt()]
    // civil from days (Howard Hinnant)
    val z = days + 719468
    val era = floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val monthName = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[m - 1]
    val hour = (secs / 3600).toInt()
    return "$weekday ${d.toString().padStart(2, '0')} $monthName ${hour.toString().padStart(2, '0')}Z"
}

private fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }
private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
