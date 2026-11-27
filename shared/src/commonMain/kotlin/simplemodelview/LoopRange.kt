package simplemodelview

import kotlin.math.abs

/*
 * A loop chosen in time, so one control (and the same presets) works on every page whatever its step: 2-minute radar scans, hourly model frames, 3-hourly
 * outlooks. A page keeps the loop as two valid times; each time the frames change (a new scan, a new run) the times are turned back into frames.
 */

/** "45 min", "1 h", "1 h 20 min", "3 h", "2 days". */
fun loopSpanLabel(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> { val h = minutes / 60; val m = minutes % 60; if (m == 0L) "$h h" else "$h h $m min" }
        else -> { val d = minutes / (24 * 60); val h = minutes % (24 * 60) / 60; if (h == 0L) "$d days" else "$d days $h h" }
    }
}

/** The spans offered for a series of [times], in seconds: a few round ones shorter than the series. */
fun loopSpanOptions(times: List<Long>): List<Long> {
    if (times.size < 3) return emptyList()
    val total = times.last() - times.first()
    val step = total / (times.size - 1)           // a span shorter than one step would hold a single frame
    return listOf(900L, 1800L, 3600L, 3 * 3600L, 6 * 3600L, 12 * 3600L, 24 * 3600L, 48 * 3600L, 72 * 3600L, 120 * 3600L, 168 * 3600L, 240 * 3600L, 336 * 3600L).filter { it < total && it >= step }
}

/**
 * The frames of [times] that a loop from [from] to [to] (epoch seconds) covers: the frame nearest each end and all between (at least two frames). Null when the
 * loop is outside the series, or the series has fewer than two frames.
 */
fun loopIndices(times: List<Long>, from: Long, to: Long): IntRange? {
    if (times.size < 2 || to < from) return null
    val tolerance = maxOf(900L, (times.last() - times.first()) / times.size)
    if (to < times.first() - tolerance || from > times.last() + tolerance) return null
    fun nearest(t: Long) = times.indices.minByOrNull { abs(times[it] - t) } ?: 0
    var a = nearest(from); var b = nearest(to)
    if (b - a < 1) { if (b < times.size - 1) b = a + 1 else a = b - 1 }
    return a..b
}

/** The loop of [seconds] that ends at frame [shown] (the frames up to it) or starts at it, as two frames' times. Null for a series with fewer than two frames. */
fun loopTimesFor(times: List<Long>, shown: Int, seconds: Long, endingAtShown: Boolean): Pair<Long, Long>? {
    if (times.size < 2) return null
    val at = shown.coerceIn(0, times.size - 1)
    return if (endingAtShown) {
        val end = if (at == 0) 1 else at
        val start = times.indices.firstOrNull { times[it] >= times[end] - seconds } ?: 0
        times[minOf(start, end - 1)] to times[end]
    } else {
        val start = if (at == times.size - 1) at - 1 else at
        val end = times.indices.lastOrNull { times[it] <= times[start] + seconds } ?: (start + 1)
        times[start] to times[maxOf(end, start + 1)]
    }
}

/** "13:00Z → 18:00Z · 6 frames · 5 h" for the frames [range] of [times]. */
fun loopRangeSummary(times: List<Long>, range: IntRange): String {
    if (times.isEmpty() || range.isEmpty()) return ""
    val a = range.first.coerceIn(0, times.size - 1); val b = range.last.coerceIn(a, times.size - 1)
    val now = times.last()
    return "${formatClock(times[a], now)} → ${formatClock(times[b], now)}  ·  ${b - a + 1} frames  ·  ${loopSpanLabel(times[b] - times[a])}"
}
