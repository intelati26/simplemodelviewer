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
    val labelSuffix: String = "",
    val color: Int = 0x202020,
    /** How many times the field is smoothed before it is traced: a rough field (humidity) needs more than a smooth one (heights). */
    val smoothing: Int = Contours.SMOOTHING_PASSES,
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
        return (if (abs(v - v.roundToInt()) < 1e-6) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()) + labelSuffix
    }
}

/** A contour line in pixels of the picture: [points] are x0, y0, x1, y1, ...; [labelAt] is the vertex that carries [label] (-1: none). */
class ContourLine(val level: Double, val bold: Boolean, val points: FloatArray, val label: String, val labelAt: Int, val closed: Boolean)

object Contours {
    /** Which fields get contour lines, from the array name and the shown units; null: none. Intervals follow the usual charts. */
    fun specFor(id: String, units: String): ContourSpec? = when {
        id.startsWith("z500") -> ContourSpec(60.0, boldEvery = 300.0, labelDivisor = 10.0)       // 6 dam lines, the 540 / 570 / 600 bold
        id.startsWith("z250") || id.startsWith("z300") -> ContourSpec(120.0, boldEvery = 600.0, labelDivisor = 10.0)
        id.startsWith("z200") || id.startsWith("z100") -> ContourSpec(120.0, boldEvery = 600.0, labelDivisor = 10.0)
        id.startsWith("z50") || id.startsWith("z30") || id.startsWith("z10") -> ContourSpec(200.0, boldEvery = 1000.0, labelDivisor = 10.0)   // the stratosphere: the heights are 20 to 30 km
        id.startsWith("z700") -> ContourSpec(30.0, boldEvery = 150.0, labelDivisor = 10.0)
        id.startsWith("z850") -> ContourSpec(30.0, boldEvery = 150.0, labelDivisor = 10.0)
        id.startsWith("pmsl") -> ContourSpec(4.0, boldEvery = 20.0)                              // hPa
        id.startsWith("t850") || id.startsWith("t700") || id.startsWith("t500") ->
            if (units == "F") ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0) else ContourSpec(5.0, boldEvery = 1000.0)
        id.startsWith("t2m") || id.startsWith("td2m") ->
            if (units == "F") ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0) else ContourSpec(5.0, boldEvery = 1000.0)
        id.startsWith("nbm_") && units == "F" && id.removePrefix("nbm_").let { it.startsWith("temp") || it.startsWith("appttemp") || it.startsWith("dewpoint") || it == "maxt" || it == "mint" } ->
            ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0)                                       // the National Blend of Models page's temperatures
        id == "rh_anl" || id.startsWith("rh2m") -> Underlay.humiditySpec()                           // the humidity analysis, in percent
        id.startsWith("Model_0degC_Height") -> if (units == "km") ContourSpec(0.5, boldEvery = 2.0) else ContourSpec(2.0, boldEvery = 10.0)   // the MRMS model fields
        id.startsWith("Model_SurfaceTemp") || id.startsWith("Model_WetBulbTemp") ->
            if (units == "F") ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0) else ContourSpec(5.0, boldEvery = 1000.0)
        else -> null
    }

    /**
     * The contour lines of [values] (a [grid] field, row 0 = south, stored units) inside [view], in picture pixels. [style] converts the
     * stored values to the shown ones the [spec] is written in. NaN cells break the lines.
     */
    fun lines(values: FloatArray, grid: Grid, view: View, style: Style, spec: ContourSpec, smoothing: Int = spec.smoothing, levelsOverride: List<Double>? = null): List<ContourLine> {
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
        val levels = levelsOverride ?: spec.levels(lo, hi)   // [levelsOverride]: exactly these levels, whatever the range
        // the lines follow a lightly smoothed field, so the cell-to-cell noise of a model does not turn them into scribble
        val field = smoothed(values, cols, c0, r0, c1, r1, smoothing)
        val scale = view.scale.toFloat() / view.step
        val stats = BlockStats.of(field, cols, c0, r0, c1, r1)       // the range of each block of cells: a level only looks at the blocks it crosses
        val out = ArrayList<ContourLine>()
        for (level in levels) {
            val stored = ((level - style.offset) / f).toFloat()
            val chains = trace(field, cols, c0, r0, c1, r1, stored, stats)
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
     * counting as neighbors. A straight ramp is left as it is. Cells outside the window are copied. One copy of the field is made, whatever the passes:
     * each pass blurs along the rows into a window-sized buffer and then down the columns back into the copy, which reads only the buffer.
     */
    internal fun smoothed(values: FloatArray, cols: Int, c0: Int, r0: Int, c1: Int, r1: Int, passes: Int): FloatArray {
        if (passes <= 0) return values
        val w = c1 - c0 + 1
        val h = r1 - r0 + 1
        val dst = values.copyOf()
        val tmp = FloatArray(w * h)
        repeat(passes) { pass ->
            val src = if (pass == 0) values else dst
            for (r in 0 until h) {
                val row = (r0 + r) * cols + c0
                for (c in 0 until w) {
                    val v = src[row + c]
                    if (v.isNaN()) { tmp[r * w + c] = v; continue }
                    var sum = 2 * v; var wt = 2f
                    if (c > 0) { val a = src[row + c - 1]; if (!a.isNaN()) { sum += a; wt += 1f } }
                    if (c < w - 1) { val a = src[row + c + 1]; if (!a.isNaN()) { sum += a; wt += 1f } }
                    tmp[r * w + c] = sum / wt
                }
            }
            for (r in 0 until h) {
                val row = (r0 + r) * cols + c0
                for (c in 0 until w) {
                    val v = tmp[r * w + c]
                    if (v.isNaN()) { dst[row + c] = v; continue }
                    var sum = 2 * v; var wt = 2f
                    if (r > 0) { val a = tmp[(r - 1) * w + c]; if (!a.isNaN()) { sum += a; wt += 1f } }
                    if (r < h - 1) { val a = tmp[(r + 1) * w + c]; if (!a.isNaN()) { sum += a; wt += 1f } }
                    dst[row + c] = sum / wt
                }
            }
        }
        return dst
    }

    /** Corner cutting: each pass replaces every corner of the line by two points at a quarter and three quarters of its segments. An open line keeps its ends. */
    internal fun chaikin(p: FloatArray, closed: Boolean, passes: Int): FloatArray {
        var cur = p
        repeat(passes) {
            val n = cur.size / 2
            if (n < 3) return cur
            val out = FloatArray(if (closed) 4 * (n - 1) + 2 else 4 * (n - 1) + 4)
            var k = 0
            if (!closed) { out[k++] = cur[0]; out[k++] = cur[1] }
            for (i in 0 until n - 1) {
                val x0 = cur[2 * i]; val y0 = cur[2 * i + 1]; val x1 = cur[2 * i + 2]; val y1 = cur[2 * i + 3]
                out[k++] = 0.75f * x0 + 0.25f * x1; out[k++] = 0.75f * y0 + 0.25f * y1
                out[k++] = 0.25f * x0 + 0.75f * x1; out[k++] = 0.25f * y0 + 0.75f * y1
            }
            if (closed) { out[k++] = out[0]; out[k++] = out[1] } else { out[k++] = cur[2 * n - 2]; out[k++] = cur[2 * n - 1] }
            cur = out
        }
        return cur
    }

    /** The lowest and highest value in each block of [size] x [size] cells of a window, corners included (a cell's square reaches one row and column past it). */
    internal class BlockStats(val c0: Int, val r0: Int, val c1: Int, val r1: Int, val size: Int, val bw: Int, val bh: Int, val min: FloatArray, val max: FloatArray) {
        companion object {
            fun of(values: FloatArray, cols: Int, c0: Int, r0: Int, c1: Int, r1: Int, size: Int = 16): BlockStats {
                val bw = (c1 - c0 + size - 1) / size
                val bh = (r1 - r0 + size - 1) / size
                val mn = FloatArray(bw * bh)
                val mx = FloatArray(bw * bh)
                for (by in 0 until bh) for (bx in 0 until bw) {
                    val cs = c0 + bx * size; val ce = min(cs + size, c1)
                    val rs = r0 + by * size; val re = min(rs + size, r1)
                    var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY      // a block of nothing but NaN keeps these: no level crosses it
                    for (r in rs..re) {
                        val row = r * cols
                        for (c in cs..ce) {
                            val v = values[row + c]
                            if (v < lo) lo = v
                            if (v > hi) hi = v
                        }
                    }
                    mn[by * bw + bx] = lo; mx[by * bw + bx] = hi
                }
                return BlockStats(c0, r0, c1, r1, size, bw, bh, mn, mx)
            }
        }
    }

    /** The points where a level crosses the edges of the cells, found by the edge's key, with the (at most two) neighbors of each: no boxed numbers, no hash nodes. */
    private class EdgePoints {
        var xs = FloatArray(1024); var ys = FloatArray(1024)
        var n0 = IntArray(1024); var n1 = IntArray(1024)
        var count = 0
        private var keys = LongArray(2048) { -1L }
        private var ids = IntArray(2048)
        private var mask = 2047

        private fun slot(key: Long, mask: Int) = ((key * -7046029254386353131L) ushr 32).toInt() and mask

        fun find(key: Long): Int {
            var s = slot(key, mask)
            while (true) {
                val k = keys[s]
                if (k == key) return ids[s]
                if (k == -1L) return -1
                s = (s + 1) and mask
            }
        }

        fun add(key: Long, x: Float, y: Float): Int {
            if (count == xs.size) { xs = xs.copyOf(count * 2); ys = ys.copyOf(count * 2); n0 = n0.copyOf(count * 2); n1 = n1.copyOf(count * 2) }
            if ((count + 1) * 2 > keys.size) grow()
            val id = count++
            xs[id] = x; ys[id] = y; n0[id] = -1; n1[id] = -1
            var s = slot(key, mask)
            while (keys[s] != -1L) s = (s + 1) and mask
            keys[s] = key; ids[s] = id
            return id
        }

        private fun grow() {
            val oldKeys = keys; val oldIds = ids
            val size = oldKeys.size * 2
            keys = LongArray(size) { -1L }; ids = IntArray(size); mask = size - 1
            for (i in oldKeys.indices) if (oldKeys[i] != -1L) {
                var s = slot(oldKeys[i], mask)
                while (keys[s] != -1L) s = (s + 1) and mask
                keys[s] = oldKeys[i]; ids[s] = oldIds[i]
            }
        }

        /** Each point joins at most two segments (a third would replace the second). */
        fun link(p: Int, q: Int) {
            if (n0[p] < 0) n0[p] = q else n1[p] = q
            if (n0[q] < 0) n0[q] = p else n1[q] = p
        }
    }

    /**
     * Marching squares of one level over the cells (c0..c1, r0..r1) of [values]: the segments are chained into polylines, as x0, y0, x1, y1, ...
     * in cell coordinates (the value of cell (c, r) sits at (c, r)). A saddle is joined by the mean of its four corners. With [stats] only the blocks
     * whose range holds the level are looked at.
     */
    internal fun trace(values: FloatArray, cols: Int, c0: Int, r0: Int, c1: Int, r1: Int, level: Float, stats: BlockStats? = null): List<FloatArray> {
        val st = stats ?: BlockStats.of(values, cols, c0, r0, c1, r1)
        val pts = EdgePoints()
        // an edge is named by its lower-left cell and direction: horizontal (0) from (c, r) to (c + 1, r), vertical (1) to (c, r + 1)
        fun horizontal(c: Int, r: Int, va: Float, vb: Float): Int {
            val key = (r.toLong() * cols + c) shl 1
            val found = pts.find(key)
            return if (found >= 0) found else pts.add(key, c + (level - va) / (vb - va), r.toFloat())
        }
        fun vertical(c: Int, r: Int, va: Float, vb: Float): Int {
            val key = ((r.toLong() * cols + c) shl 1) or 1L
            val found = pts.find(key)
            return if (found >= 0) found else pts.add(key, c.toFloat(), r + (level - va) / (vb - va))
        }

        for (by in 0 until st.bh) for (bx in 0 until st.bw) {
            val b = by * st.bw + bx
            if (level <= st.min[b] || level > st.max[b]) continue      // every corner of the block is on one side of the level
            val cs = c0 + bx * st.size; val ce = min(cs + st.size, c1)
            val rs = r0 + by * st.size; val re = min(rs + st.size, r1)
            for (r in rs until re) for (c in cs until ce) {
                val a = values[r * cols + c]               // lower left
                val bb = values[r * cols + c + 1]          // lower right
                val d = values[(r + 1) * cols + c]         // upper left
                val e = values[(r + 1) * cols + c + 1]     // upper right
                if (a.isNaN() || bb.isNaN() || d.isNaN() || e.isNaN()) continue
                val ia = a >= level; val ib = bb >= level; val id = d >= level; val ie = e >= level
                if (ia == ib && ib == id && id == ie) continue
                val bottom = if (ia != ib) horizontal(c, r, a, bb) else -1
                val top = if (id != ie) horizontal(c, r + 1, d, e) else -1
                val left = if (ia != id) vertical(c, r, a, d) else -1
                val right = if (ib != ie) vertical(c + 1, r, bb, e) else -1
                var hits = 0
                if (bottom >= 0) hits += 1
                if (right >= 0) hits += 1
                if (top >= 0) hits += 1
                if (left >= 0) hits += 1
                if (hits == 2) {
                    // the two that are there, in the order bottom, right, top, left
                    var first = -1
                    var second = -1
                    if (bottom >= 0) first = bottom
                    if (right >= 0) { if (first < 0) first = right else second = right }
                    if (top >= 0) { if (first < 0) first = top else second = top }
                    if (left >= 0) { if (first < 0) first = left else second = left }
                    pts.link(first, second)
                } else if (hits == 4) {                  // a saddle: the center decides which corners are joined
                    val centerHigh = (a + bb + d + e) / 4 >= level
                    if (centerHigh == ia) { pts.link(bottom, left); pts.link(top, right) } else { pts.link(bottom, right); pts.link(top, left) }
                }
            }
        }

        val n = pts.count
        val used = BooleanArray(n)
        val chains = ArrayList<FloatArray>()
        var buf = FloatArray(256)
        fun walk(start: Int) {
            var len = 0
            var prev = -1
            var cur = start
            while (true) {
                used[cur] = true
                if (len + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
                buf[len++] = pts.xs[cur]; buf[len++] = pts.ys[cur]
                val a = pts.n0[cur]; val b2 = pts.n1[cur]
                if (a < 0) break
                // the first neighbor that is not where we came from and not used yet (the start again closes a loop of three or more points)
                val canClose = prev != -1 && cur != start && len > 4
                var step = -1
                if (a != prev && (!used[a] || (a == start && canClose))) step = a
                else if (b2 >= 0 && b2 != prev && (!used[b2] || (b2 == start && canClose))) step = b2
                if (step < 0) break
                if (step == start) {
                    if (len + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
                    buf[len++] = pts.xs[start]; buf[len++] = pts.ys[start]
                    break
                }
                prev = cur; cur = step
            }
            chains.add(buf.copyOf(len))
        }
        for (k in 0 until n) if (pts.n0[k] >= 0 && pts.n1[k] < 0 && !used[k]) walk(k)      // open lines first, from an end
        for (k in 0 until n) if (pts.n0[k] >= 0 && !used[k]) walk(k)                        // what remains are closed loops
        return chains
    }
}
