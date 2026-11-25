package simplemodelview

import kotlin.math.abs
import kotlin.math.roundToInt

/** The chance of thunder at a place in the hour ending at [valid] (epoch seconds), in percent. */
class HourChance(val valid: Long, val percent: Double)

/** Where a run of hourly chances stands against a personal line: the first hour at or over it and the highest hour. */
class ThunderSummary(val line: Int, val firstOver: HourChance?, val peak: HourChance?, val lastHour: Long?)

/**
 * The thunder-chance card's arithmetic: hours against the user's line ("Past your 25% line from about 4 pm"), and how a number compares with the same period in the
 * previous model run ("up from 30%"). The numbers come from SPC's REFSCT thunder probability (percent, 0-100).
 */
object ThunderTiming {
    const val DEFAULT_LINE = 25
    val LINES = listOf(10, 15, 25, 40, 60)

    /** The hours from [now] on (the first hour ends after [now]), at most [count] of them. */
    fun upcoming(hours: List<HourChance>, now: Long, count: Int = 12): List<HourChance> =
        hours.sortedBy { it.valid }.filter { it.valid > now }.take(count)

    fun summarize(hours: List<HourChance>, line: Int): ThunderSummary {
        val over = hours.firstOrNull { it.percent >= line }
        return ThunderSummary(line, over, hours.maxByOrNull { it.percent }, hours.lastOrNull()?.valid)
    }

    /** "up from 30%" / "down from 55%" when the two differ by at least [minChange] points; null when close, or when there is no earlier number. */
    fun rise(current: Double?, previous: Double?, minChange: Int = 5): String? {
        if (current == null || previous == null || current.isNaN() || previous.isNaN()) return null
        val change = current - previous
        if (abs(change) < minChange) return null
        return (if (change > 0) "up from " else "down from ") + "${previous.roundToInt()}%"
    }

    /** The headline: where the chance first reaches the line, or that it stays under it. [clock] writes an hour in the user's own time ("4 pm"). */
    fun headline(s: ThunderSummary, clock: (Long) -> String): String = when {
        s.firstOver != null -> "Past your ${s.line}% line from about ${clock(s.firstOver.valid)}"
        s.peak != null && s.lastHour != null -> "Under your ${s.line}% line through ${clock(s.lastHour)} (highest ${s.peak.percent.roundToInt()}% around ${clock(s.peak.valid)})"
        else -> "No thunder chance could be read"
    }
}
