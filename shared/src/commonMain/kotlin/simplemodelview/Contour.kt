package simplemodelview

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Contour lines of a field: one line every [interval] (in the shown units), the lines whose level is a multiple of [boldEvery] from
 * [boldBase] drawn heavier, and labels shown as `level / labelDivisor` (500 mb heights are labeled in decameters).
 */
data class ContourSpec(
    val interval: Double,
    val boldEvery: Double = 0.0,
    val boldBase: Double = 0.0,
    val labelDivisor: Double = 1.0,
    val color: Int = 0x202020,
) {
    /** The levels (shown units) between [lo] and [hi], at most [limit] of them (a sanity cap for fields with a wild range). */
    fun levels(lo: Double, hi: Double, limit: Int = 60): List<Double> {
        if (!(interval > 0) || lo.isNaN() || hi.isNaN() || hi < lo) return emptyList()
        val first = ceil(lo / interval)
        val last = floor(hi / interval)
        if (last < first || last - first + 1 > limit) return emptyList()
        return (first.toLong()..last.toLong()).map { it * interval }
    }

    fun isBold(level: Double): Boolean {
        if (boldEvery <= 0) return false
        val k = (level - boldBase) / boldEvery
        return abs(k - k.roundToInt()) < 1e-6
    }

    fun label(level: Double): String {
        val v = level / labelDivisor
        return if (abs(v - v.roundToInt()) < 1e-6) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()
    }
}

/** A contour line in pixels of the picture: [points] are x0, y0, x1, y1, ...; [labelAt] is the vertex that carries [label] (-1: none). */
class ContourLine(val level: Double, val bold: Boolean, val points: FloatArray, val label: String, val labelAt: Int, val closed: Boolean)

object Contours {
    /** Which fields get contour lines, from the array name and the shown units; null: none. Intervals follow the usual charts. */
    fun specFor(id: String, units: String): ContourSpec? = when {
        id.startsWith("z500") -> ContourSpec(60.0, boldEvery = 300.0, labelDivisor = 10.0)       // 6 dam lines, the 540 / 570 / 600 bold
        id.startsWith("z700") -> ContourSpec(30.0, boldEvery = 150.0, labelDivisor = 10.0)
        id.startsWith("z850") -> ContourSpec(30.0, boldEvery = 150.0, labelDivisor = 10.0)
        id.startsWith("pmsl") -> ContourSpec(4.0, boldEvery = 20.0)                              // hPa
        id.startsWith("t850") || id.startsWith("t700") || id.startsWith("t500") ->
            if (units == "F") ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0) else ContourSpec(5.0, boldEvery = 1000.0)
        id.startsWith("t2m") || id.startsWith("td2m") ->
            if (units == "F") ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0) else ContourSpec(5.0, boldEvery = 1000.0)
        id.startsWith("nbm_") && units == "F" && id.removePrefix("nbm_").let { it.startsWith("temp") || it.startsWith("appttemp") || it.startsWith("dewpoint") || it == "maxt" || it == "mint" } ->
            ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0)                                       // the National Blend of Models page's temperatures
        id.startsWith("Model_0degC_Height") -> if (units == "km") ContourSpec(0.5, boldEvery = 2.0) else ContourSpec(2.0, boldEvery = 10.0)   // the MRMS model fields
        id.startsWith("Model_SurfaceTemp") || id.startsWith("Model_WetBulbTemp") ->
            if (units == "F") ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0) else ContourSpec(5.0, boldEvery = 1000.0)
        else -> null
    }

    /**
     * The contour lines of [values] (a [grid] field, row 0 = south, stored units) inside [view], in picture pixels. [style] converts the
     * stored values to the shown ones the [spec] is written in. NaN cells break the lines.
     */
    fun lines(values: FloatArray, grid: Grid, view: View, style: Style, spec: ContourSpec, smoothing: Int = SMOOTHING_PASSES): List<ContourLine> {
        val cols = grid.columns
        val rows = grid.rows
        if (values.size < cols * rows) return emptyList()
        val c0 = max(0, view.col0)
        val r0 = max(0, view.row0)
        val c1 = min(cols - 1, view.col0 + view.cols)     // one cell past the window so a line reaches its edge
        val r1 = min(rows - 1, view.row0 + view.rows)
        if (c1 <= c0 || r1 <= r0) return emptyList()
        val f = if (style.factor == 0.0) 1.0 else style.factor
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (r in r0..r1) for (c in c0..c1) {
            val v = values[r * cols + c]
            if (v.isNaN()) continue
            val s = v * f + style.offset
            if (s < lo) lo = s
            if (s > hi) hi = s
        }
        val levels = spec.levels(lo, hi)
        // the lines follow a lightly smoothed field, so the cell-to-cell noise of a model does not turn them into scribble
        val field = smoothed(values, cols, c0, r0, c1, r1, smoothing)
        val scale = view.scale.toFloat() / view.step
        val out = ArrayList<ContourLine>()
        for (level in levels) {
            val stored = ((level - style.offset) / f).toFloat()
            val chains = trace(field, cols, c0, r0, c1, r1, stored)
            var best = -1
            var bestLen = -1
            chains.forEachIndexed { i, ch -> if (ch.size > bestLen) { best = i; bestLen = ch.size } }
            chains.forEachIndexed { i, raw ->
                if (raw.size < 8 || length(raw) < MIN_LINE_CELLS) return@forEachIndexed   // fewer than four points, or a few cells long: a speck
                val closed = raw[0] == raw[raw.size - 2] && raw[1] == raw[raw.size - 1]
                val ch = if (smoothing > 0) chaikin(raw, closed, 2) else raw                // round the corners the grid leaves
                val px = FloatArray(ch.size)
                var k = 0
                while (k < ch.size) {
                    px[k] = (ch[k] - view.col0 + 0.5f) * scale
                    px[k + 1] = (view.row0 + view.rows - 0.5f - ch[k + 1]) * scale
                    k += 2
                }
                val mid = if (i == best && ch.size >= 24) (ch.size / 4) * 2 else -1   // a label on the longest line only, and only if it has room
                out.add(ContourLine(level, spec.isBold(level), px, spec.label(level), mid, closed))
            }
        }
        return out
    }

    /** How many times the field is blurred (3 x 3, 1-2-1 weights) before it is traced. */
    const val SMOOTHING_PASSES = 2

    /** Lines shorter than this many cells are dropped: on a noisy field they are specks and short squiggles. */
    const val MIN_LINE_CELLS = 8.0

    private fun length(p: FloatArray): Double {
        var sum = 0.0
        var i = 2
        while (i + 1 < p.size) { sum += hypot((p[i] - p[i - 2]).toDouble(), (p[i + 1] - p[i - 1]).toDouble()); i += 2 }
        return sum
    }

    /**
     * [values] blurred [passes] times inside the window (c0..c1, r0..r1), a cell's neighbors weighing half of it, NaN cells staying NaN and not
     * counting as neighbors. A straight ramp is left as it is. Cells outside the window are copied.
     */
    internal fun smoothed(values: FloatArray, cols: Int, c0: Int, r0: Int, c1: Int, r1: Int, passes: Int): FloatArray {
        if (passes <= 0) return values
        val w = c1 - c0 + 1
        val h = r1 - r0 + 1
        var cur = values
        val tmp = FloatArray(w * h)
        repeat(passes) {
            val src = cur
            for (r in 0 until h) for (c in 0 until w) {
                val v = src[(r0 + r) * cols + c0 + c]
                if (v.isNaN()) { tmp[r * w + c] = v; continue }
                var sum = 2 * v; var wt = 2f
                if (c > 0) src[(r0 + r) * cols + c0 + c - 1].let { if (!it.isNaN()) { sum += it; wt += 1f } }
                if (c < w - 1) src[(r0 + r) * cols + c0 + c + 1].let { if (!it.isNaN()) { sum += it; wt += 1f } }
                tmp[r * w + c] = sum / wt
            }
            val dst = values.copyOf()
            for (r in 0 until h) for (c in 0 until w) {
                val v = tmp[r * w + c]
                if (v.isNaN()) { dst[(r0 + r) * cols + c0 + c] = v; continue }
                var sum = 2 * v; var wt = 2f
                if (r > 0) tmp[(r - 1) * w + c].let { if (!it.isNaN()) { sum += it; wt += 1f } }
                if (r < h - 1) tmp[(r + 1) * w + c].let { if (!it.isNaN()) { sum += it; wt += 1f } }
                dst[(r0 + r) * cols + c0 + c] = sum / wt
            }
            cur = dst
        }
        return cur
    }

    /** Corner cutting: each pass replaces every corner of the line by two points at a quarter and three quarters of its segments. An open line keeps its ends. */
    internal fun chaikin(p: FloatArray, closed: Boolean, passes: Int): FloatArray {
        var cur = p
        repeat(passes) {
            val n = cur.size / 2
            if (n < 3) return cur
            val out = ArrayList<Float>(cur.size * 2)
            if (!closed) { out.add(cur[0]); out.add(cur[1]) }
            for (i in 0 until n - 1) {
                val x0 = cur[2 * i]; val y0 = cur[2 * i + 1]; val x1 = cur[2 * i + 2]; val y1 = cur[2 * i + 3]
                out.add(0.75f * x0 + 0.25f * x1); out.add(0.75f * y0 + 0.25f * y1)
                out.add(0.25f * x0 + 0.75f * x1); out.add(0.25f * y0 + 0.75f * y1)
            }
            if (closed) { out.add(out[0]); out.add(out[1]) } else { out.add(cur[2 * n - 2]); out.add(cur[2 * n - 1]) }
            cur = out.toFloatArray()
        }
        return cur
    }

    /**
     * Marching squares of one level over the cells (c0..c1, r0..r1) of [values]: the segments are chained into polylines, as x0, y0, x1, y1, ...
     * in cell coordinates (the value of cell (c, r) sits at (c, r)). A saddle is joined by the mean of its four corners.
     */
    internal fun trace(values: FloatArray, cols: Int, c0: Int, r0: Int, c1: Int, r1: Int, level: Float): List<FloatArray> {
        // an edge is named by its lower-left cell and direction: horizontal (0) from (c, r) to (c + 1, r), vertical (1) to (c, r + 1)
        fun key(c: Int, r: Int, vertical: Int): Long = ((r.toLong() * cols + c) shl 1) or vertical.toLong()
        val px = HashMap<Long, FloatArray>()
        val next = HashMap<Long, LongArray>()      // each edge point joins at most two segments

        fun point(a: Int, b: Int, c: Int, r: Int, vertical: Int, va: Float, vb: Float): Long {
            val k = key(c, r, vertical)
            if (k !in px) {
                val t = (level - va) / (vb - va)
                px[k] = if (vertical == 0) floatArrayOf(c + t, r.toFloat()) else floatArrayOf(c.toFloat(), r + t)
            }
            return k
        }

        fun link(p: Long, q: Long) {
            for ((x, y) in listOf(p to q, q to p)) {
                val cur = next[x]
                next[x] = if (cur == null) longArrayOf(y) else longArrayOf(cur[0], y)
            }
        }

        for (r in r0 until r1) for (c in c0 until c1) {
            val a = values[r * cols + c]               // lower left
            val b = values[r * cols + c + 1]           // lower right
            val d = values[(r + 1) * cols + c]         // upper left
            val e = values[(r + 1) * cols + c + 1]     // upper right
            if (a.isNaN() || b.isNaN() || d.isNaN() || e.isNaN()) continue
            val ia = a >= level; val ib = b >= level; val id = d >= level; val ie = e >= level
            if (ia == ib && ib == id && id == ie) continue
            val bottom = if (ia != ib) point(0, 0, c, r, 0, a, b) else -1L
            val top = if (id != ie) point(0, 0, c, r + 1, 0, d, e) else -1L
            val left = if (ia != id) point(0, 0, c, r, 1, a, d) else -1L
            val right = if (ib != ie) point(0, 0, c + 1, r, 1, b, e) else -1L
            val hits = listOf(bottom, right, top, left).filter { it >= 0 }
            if (hits.size == 2) link(hits[0], hits[1])
            else if (hits.size == 4) {                  // a saddle: the center decides which corners are joined
                val centerHigh = (a + b + d + e) / 4 >= level
                if (centerHigh == ia) { link(bottom, left); link(top, right) } else { link(bottom, right); link(top, left) }
            }
        }

        val used = HashSet<Long>()
        val chains = ArrayList<FloatArray>()
        fun walk(start: Long) {
            val pts = ArrayList<Float>()
            var prev = -1L
            var cur = start
            while (true) {
                used.add(cur)
                val p = px.getValue(cur)
                pts.add(p[0]); pts.add(p[1])
                val nb = next[cur] ?: break
                val step = nb.firstOrNull { it != prev && (it !in used || it == start && prev != -1L && cur != start && pts.size > 4) } ?: break
                if (step == start) { val s = px.getValue(start); pts.add(s[0]); pts.add(s[1]); break }
                prev = cur; cur = step
            }
            chains.add(pts.toFloatArray())
        }
        for ((k, nb) in next) if (nb.size == 1 && k !in used) walk(k)      // open lines first, from an end
        for (k in next.keys) if (k !in used) walk(k)                        // what remains are closed loops
        return chains
    }
}
