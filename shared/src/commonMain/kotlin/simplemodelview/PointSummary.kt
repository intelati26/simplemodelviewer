package simplemodelview

/** One local day of the NBM forecast at a station, in the bulletins' units (F, knots, percent, hundredths and tenths of an inch). Null where the bulletins have nothing for it. */
class PointDay(
    val day: Int, val firstValid: Long, val high: Int?, val low: Int?, val pop: Int?, val windKt: Int?, val gustKt: Int?,
    val sky: Int?, val rainHundredths: Int?, val snowTenths: Int?, val thunder: Int?,
)

object PointSummary {
    /** The sky in words, from the mean cloud cover (%). */
    fun skyWords(sky: Int?): String? = when {
        sky == null -> null
        sky < 25 -> "Clear"
        sky < 50 -> "Partly cloudy"
        sky < 80 -> "Mostly cloudy"
        else -> "Cloudy"
    }

    /**
     * The table's columns gathered by local day ([dayOf] turns a valid time into the place's day number; columns before [from] are left out) for the first [maxDays] days.
     * A day's high and low come from its temperature columns and the bulletins' own 18-hour maximum (reported at 00Z: the afternoon before) and minimum (12Z: the morning).
     */
    fun days(table: PointTable, dayOf: (Long) -> Int, from: Long, maxDays: Int = 5): List<PointDay> {
        class Acc(val day: Int, val first: Long) {
            val temps = ArrayList<Int>(); val highs = ArrayList<Int>(); val lows = ArrayList<Int>()
            val pops = ArrayList<Int>(); val winds = ArrayList<Int>(); val gusts = ArrayList<Int>(); val skies = ArrayList<Int>()
            var rain: Int? = null; var snow: Int? = null; val thunders = ArrayList<Int>()
        }
        val byDay = LinkedHashMap<Int, Acc>()
        fun acc(day: Int, t: Long) = byDay.getOrPut(day) { Acc(day, t) }
        for (c in table.columns) {
            if (c.valid < from) continue
            val a = acc(dayOf(c.valid), c.valid)
            c.value(NbmTextElements.TEMP)?.let { a.temps += it }
            c.value(NbmTextElements.SKY)?.let { a.skies += it }
            c.value(NbmTextElements.WIND)?.let { a.winds += it }
            c.value(NbmTextElements.GUST)?.let { a.gusts += it }
            (c.value(NbmTextElements.POP12) ?: c.value("P06"))?.let { a.pops += it }
            (c.value(NbmTextElements.THUNDER12) ?: c.value("T06"))?.let { a.thunders += it }
            (c.value(NbmTextElements.QPF12) ?: c.value("Q06"))?.let { a.rain = (a.rain ?: 0) + it }
            c.value(NbmTextElements.SNOW12)?.let { a.snow = (a.snow ?: 0) + it }
            c.value(NbmTextElements.MAX_MIN)?.let { v ->
                when (pointHourLabel(c)) {
                    "00Z" -> acc(dayOf(c.valid - 3 * 3600L), c.valid).highs += v      // the maximum of the afternoon that just ended
                    "12Z" -> a.lows += v
                }
            }
        }
        return byDay.values.sortedBy { it.day }.take(maxDays).map { a ->
            PointDay(
                a.day, a.first, (a.temps + a.highs).maxOrNull(), (a.temps + a.lows).minOrNull(), a.pops.maxOrNull(), a.winds.maxOrNull(), a.gusts.maxOrNull(),
                a.skies.takeIf { it.isNotEmpty() }?.average()?.toInt(), a.rain, a.snow, a.thunders.maxOrNull(),
            )
        }
    }
}
