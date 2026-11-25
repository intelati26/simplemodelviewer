package simplemodelview

import kotlin.math.roundToInt

/**
 * The wind card's arithmetic: the hourly gust at a place (mph, held in [HourChance.percent]) against the user's own gust line ("Gusts reach 40 mph from about 4 pm").
 * The numbers are SPC REFS's ensemble-mean gust, which smooths out the peaks of single storms, so the card calls them guidance.
 */
object WindTiming {
    const val DEFAULT_LINE = 40
    val LINES = listOf(25, 30, 40, 50, 60)

    fun summarize(hours: List<HourChance>, line: Int): ThunderSummary = ThunderTiming.summarize(hours, line)

    fun headline(s: ThunderSummary, clock: (Long) -> String): String = when {
        s.firstOver != null -> "Gusts reach your ${s.line} mph line from about ${clock(s.firstOver.valid)}"
        s.peak != null && s.lastHour != null -> "Gusts stay under your ${s.line} mph line through ${clock(s.lastHour)} (highest ${s.peak.percent.roundToInt()} mph around ${clock(s.peak.valid)})"
        else -> "No gust forecast could be read"
    }
}
