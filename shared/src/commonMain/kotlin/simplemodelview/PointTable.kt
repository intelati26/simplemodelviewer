package simplemodelview

/** One column of the extended view: a valid time, which source it came from ("API", "NBS", "NBE", "NBX") and its values by the bulletins' codes. */
class PointColumn(val valid: Long, val source: String, val values: Map<String, Int?>) {
    fun value(code: String): Int? = values[code]
}

/** The columns of the extended view, earliest first. */
class PointTable(val station: String, val columns: List<PointColumn>) {
    /** The column nearest [valid] (epoch seconds), or null for an empty table. */
    fun nearest(valid: Long): PointColumn? = columns.minByOrNull { kotlin.math.abs(it.valid - valid) }

    /** Which of [codes] some column has a value other than zero for (a row of snow or ice amounts is left out when it is all zero). */
    fun hasAny(code: String): Boolean = columns.any { (it.value(code) ?: 0) != 0 }
}

/**
 * The extended view's columns from the four sources, one after the other by valid time: the gridded forecast ([api]) for the hours before the short-range
 * bulletin, the short-range bulletin ([nbs], 3-hourly to 72 h), the extended one ([nbe], 12-hourly to 192 h) and the super-extended ([nbx], to 264 h). A later
 * source gives only the columns after the last one of the sources before it, so no time appears twice and the changes of spacing are where the sources change.
 * Columns before [fromEpoch] are left out (a table that opens on the hours already past is no use).
 */
fun buildPointTable(station: String, api: NbmTextTable?, nbs: NbmTextTable?, nbe: NbmTextTable?, nbx: NbmTextTable?, fromEpoch: Long = Long.MIN_VALUE): PointTable {
    val columns = ArrayList<PointColumn>()
    var last = Long.MIN_VALUE
    for (table in listOfNotNull(api, nbs, nbe, nbx)) {
        val times = table.validTimes
        for (i in times.indices) {
            if (times[i] <= last || times[i] < fromEpoch) continue
            val values = table.rows.mapValues { (_, v) -> v.getOrNull(i) }
            // the super-extended bulletin of some cycles lists hours past the end of the blend (to 372 h) with nothing but a 24-hour chance in them: no column for those
            if (listOf(NbmTextElements.TEMP, NbmTextElements.SKY, NbmTextElements.WIND, NbmTextElements.POP12).all { values[it] == null }) continue
            columns += PointColumn(times[i], table.kind, values)
        }
        columns.lastOrNull()?.let { last = maxOf(last, it.valid) }
    }
    return PointTable(station, columns)
}

/** "Fri 13" style day headings: the UTC day of each column (the bulletins' days are UTC), as the index of its first column and the label. */
fun pointDays(table: PointTable): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    var day = ""
    table.columns.forEachIndexed { i, c ->
        val label = formatUtc(c.valid).substringBeforeLast(' ')       // "Thu 15 Oct"
        if (label != day) { out += i to label; day = label }
    }
    return out
}

/** The hour label of a column: "18Z". */
fun pointHourLabel(column: PointColumn): String = formatUtc(column.valid).substringAfterLast(' ')
