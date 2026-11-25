package simplemodelview

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Model against radar: how well a model reflectivity field matches the radar's, on one grid. Fields are dBZ in the same cells; NaN is "not usable"
 * (outside the radar's coverage, or a missing chunk) and leaves a cell out. A radar cell with no echo carries a low number (below every threshold), not NaN.
 */

/** Scores of one field against another: hits / misses / false alarms at each threshold, and the neighborhood score at the headline threshold. */
class MatchScore(
    val thresholds: DoubleArray,
    /** Critical success index per threshold, hits / (hits + misses + false alarms); NaN when neither field has a cell at or over it. */
    val csi: DoubleArray,
    /** Frequency bias per threshold, (hits + false alarms) / (hits + misses): over 1 the model has too much, under 1 too little; NaN when the radar has none. */
    val bias: DoubleArray,
    /** Fractions skill score at the headline threshold: 1 is a perfect match at the neighborhood scale, 0 none; NaN when neither field has an event. */
    val fss: Double,
    /** Mean absolute dBZ difference over the cells where either field is at least [CompareScore.ECHO_DBZ]; NaN when there are none. */
    val meanAbsError: Double,
    val cells: Int,
)

object CompareScore {
    val THRESHOLDS = doubleArrayOf(20.0, 35.0, 45.0)
    const val HEADLINE_DBZ = 35.0
    /** 10 cells of 3 km: 30 km. */
    const val HEADLINE_RADIUS = 10
    const val ECHO_DBZ = 10.0

    /** Model minus radar where either is an echo (at least [minDbz]) and both are usable; NaN elsewhere. */
    fun difference(model: FloatArray, obs: FloatArray, minDbz: Double = ECHO_DBZ): FloatArray = FloatArray(model.size) {
        val m = model[it]; val o = obs[it]
        if (m.isNaN() || o.isNaN() || (m < minDbz && o < minDbz)) Float.NaN else m - o
    }

    /**
     * Scores [model] against [obs] over a [columns] x [rows] grid. [region] (first column, first row, last column, last row, inclusive) limits it to a part
     * of the grid (what is on screen); null is all of it.
     */
    fun score(
        model: FloatArray, obs: FloatArray, columns: Int, rows: Int, region: IntArray? = null,
        thresholds: DoubleArray = THRESHOLDS, fssThreshold: Double = HEADLINE_DBZ, fssRadius: Int = HEADLINE_RADIUS,
    ): MatchScore {
        val c0 = region?.get(0)?.coerceIn(0, columns - 1) ?: 0
        val r0 = region?.get(1)?.coerceIn(0, rows - 1) ?: 0
        val c1 = region?.get(2)?.coerceIn(c0, columns - 1) ?: (columns - 1)
        val r1 = region?.get(3)?.coerceIn(r0, rows - 1) ?: (rows - 1)
        val w = c1 - c0 + 1; val h = r1 - r0 + 1
        val n = thresholds.size
        val hits = IntArray(n); val misses = IntArray(n); val falseAlarms = IntArray(n)
        var cells = 0; var errSum = 0.0; var errCount = 0
        // integral images over the region: usable cells, model events, radar events (at the headline threshold)
        val stride = w + 1
        val usable = IntArray(stride * (h + 1)); val mEv = IntArray(stride * (h + 1)); val oEv = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowUsable = 0; var rowM = 0; var rowO = 0
            for (x in 0 until w) {
                val i = (r0 + y) * columns + (c0 + x)
                val m = model[i]; val o = obs[i]
                if (!m.isNaN() && !o.isNaN()) {
                    cells++
                    rowUsable++
                    if (m >= fssThreshold) rowM++
                    if (o >= fssThreshold) rowO++
                    for (k in 0 until n) {
                        val mm = m >= thresholds[k]; val oo = o >= thresholds[k]
                        if (mm && oo) hits[k]++ else if (oo) misses[k]++ else if (mm) falseAlarms[k]++
                    }
                    if (m >= ECHO_DBZ || o >= ECHO_DBZ) { errSum += abs(m - o); errCount++ }
                }
                val at = (y + 1) * stride + (x + 1)
                usable[at] = usable[y * stride + (x + 1)] + rowUsable
                mEv[at] = mEv[y * stride + (x + 1)] + rowM
                oEv[at] = oEv[y * stride + (x + 1)] + rowO
            }
        }
        var num = 0.0; var den = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val i = (r0 + y) * columns + (c0 + x)
            if (model[i].isNaN() || obs[i].isNaN()) continue
            val xa = max(0, x - fssRadius); val xb = min(w, x + fssRadius + 1)
            val ya = max(0, y - fssRadius); val yb = min(h, y + fssRadius + 1)
            fun box(a: IntArray) = a[yb * stride + xb] - a[ya * stride + xb] - a[yb * stride + xa] + a[ya * stride + xa]
            val u = box(usable)
            if (u == 0) continue
            val pm = box(mEv).toDouble() / u; val po = box(oEv).toDouble() / u
            num += (pm - po) * (pm - po); den += pm * pm + po * po
        }
        return MatchScore(
            thresholds.copyOf(),
            DoubleArray(n) { val d = hits[it] + misses[it] + falseAlarms[it]; if (d == 0) Double.NaN else hits[it].toDouble() / d },
            DoubleArray(n) { val d = hits[it] + misses[it]; if (d == 0) Double.NaN else (hits[it] + falseAlarms[it]).toDouble() / d },
            if (den == 0.0) Double.NaN else 1.0 - num / den,
            if (errCount == 0) Double.NaN else errSum / errCount,
            cells,
        )
    }
}

/** One member's scores at each of the early valid times scored (null where its frame was not there). */
class MemberRank(val name: String, val index: Int, val scores: List<MatchScore?>) {
    /** The headline: the mean neighborhood score over the times that have one; NaN when none do (a quiet map). */
    val headline: Double get() = scores.mapNotNull { it?.fss }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }
}

/** Members best first (NaN headlines last, in member order). */
fun rankMembers(ranks: List<MemberRank>): List<MemberRank> =
    ranks.sortedWith(compareBy<MemberRank> { it.headline.isNaN() }.thenByDescending { if (it.headline.isNaN()) 0.0 else it.headline }.thenBy { it.index })

/** Index of the time nearest [target]; -1 when there are no times. */
fun nearestTimeIndex(times: List<Long>, target: Long): Int =
    if (times.isEmpty()) -1 else times.indices.minByOrNull { abs(times[it] - target) }!!

/** The first [count] time steps of a run that radar already covers: at or after the run's start, no later than the newest radar frame (a few minutes of slack). */
fun earlyTimeIndices(runStart: Long, times: List<Long>, latestRadar: Long, count: Int = 4): List<Int> =
    times.indices.filter { times[it] >= runStart && times[it] <= latestRadar + 300 }.take(count)

/** When a run starts (epoch seconds). */
fun cycleStart(c: Cycle): Long = epochOf(c.year, c.month, c.day, c.hour, 0, 0)

/** Diverging dBZ-difference bands (model minus radar): reds where the model is too high, blues too low, nothing near zero. */
val differenceStyle: Style = run {
    val edges = listOf(-60, -40, -30, -20, -10, -5, 5, 10, 20, 30, 40)
    val colors = listOf(0x08306b, 0x2171b5, 0x4292c6, 0x6baed6, 0x9ecae1, 0xf0f0f0, 0xfcae91, 0xfb6a4a, 0xef3b2c, 0xcb181d, 0x99000d)
    Style(Kind.Banded, edges.zip(colors) { v, c -> Stop(v.toDouble(), c) }, top = 100.0, units = "dBZ", digits = 0)
}
