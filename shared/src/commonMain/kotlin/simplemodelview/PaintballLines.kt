package simplemodelview

/**
 * Member probability contours: lines of the share of ensemble members at or above a threshold (composite reflectivity > 40 dBZ), smoothed over
 * a neighborhood. The frame of such a product is the member bit mask of the model's own paintball fields (bit k set where member k reaches the
 * threshold), so the read-out is the one those use; the lines are traced from the share of set bits.
 */
object PaintballLines {
    /** [source]: the member array it is made from; [threshold]: in that array's stored units. */
    class Spec(val id: String, val source: String, val threshold: Float, val title: String)

    val specs = listOf(
        Spec("pbl_cref", "cref", 40f, "Composite Reflectivity > 40 dBZ, Member Probability Contours"),
        Spec("pbl_uh25", "uh25_04h_max_overlap", 75f, "2-5 km Updraft Helicity > 75 m2/s2, Member Probability Contours"),
    )

    fun spec(id: String): Spec? = specs.firstOrNull { it.id == id }

    fun isPaintballLines(id: String) = spec(id) != null

    /** The percentages that get a line, and the color of each (also the bands of the color key). */
    val levels = listOf(10.0, 30.0, 50.0, 70.0, 90.0)
    val colors = listOf(0x0B4F9E, 0x0F6B1F, 0x8A6A00, 0xB04A00, 0x8E0B14)   // dark enough to read over the pastel member colors

    /** Each member counts where it reaches the threshold within this many cells (the 3-km grid: about 40 km), as in the model's neighborhood probabilities. */
    const val NEIGHBORHOOD = 13

    /** The share is then blurred a little: three box blurs of this half-width, so the lines are smooth curves. */
    const val BLUR_RADIUS = 5

    fun levelColor(level: Double): Int = colors[levels.indexOfFirst { it >= level - 1e-6 }.coerceAtLeast(0).coerceAtMost(colors.size - 1)]

    /** Folds the members' fields into the bit mask: bit k set where member k is at or above [threshold]; NaN where no member has data. */
    fun mask(fields: List<FloatArray>, threshold: Float): FloatArray {
        val cells = fields.firstOrNull()?.size ?: return FloatArray(0)
        val out = FloatArray(cells)
        val any = BooleanArray(cells)
        for ((k, f) in fields.withIndex()) {
            if (f.size < cells) continue
            for (i in 0 until cells) {
                val v = f[i]
                if (v.isNaN()) continue
                any[i] = true
                if (v >= threshold) out[i] = (out[i].toInt() or (1 shl k)).toFloat()
            }
        }
        for (i in 0 until cells) if (!any[i]) out[i] = Float.NaN
        return out
    }

    /**
     * The neighborhood share: for each of the [members] members, the cells within [radius] cells (a square) of one where its bit is set count as
     * set; the share of members that count is the result, in percent (NaN where [mask] is NaN).
     */
    fun neighborhoodPercent(mask: FloatArray, cols: Int, rows: Int, members: Int, radius: Int): FloatArray {
        val n = members.coerceIn(1, 20)
        val total = IntArray(mask.size)
        val flag = IntArray(mask.size)
        val tmp = IntArray(mask.size)
        for (k in 0 until n) {
            for (i in mask.indices) { val m = mask[i]; flag[i] = if (!m.isNaN() && m.toInt() and (1 shl k) != 0) 1 else 0 }
            for (r in 0 until rows) {                                    // along the rows: how many set cells within the radius
                val base = r * cols
                var sum = 0
                for (c in 0..minOf(radius, cols - 1)) sum += flag[base + c]
                for (c in 0 until cols) {
                    tmp[base + c] = sum
                    if (c + radius + 1 <= cols - 1) sum += flag[base + c + radius + 1]
                    if (c - radius >= 0) sum -= flag[base + c - radius]
                }
            }
            for (c in 0 until cols) {                                    // then down the columns
                var sum = 0
                for (r in 0..minOf(radius, rows - 1)) sum += tmp[r * cols + c]
                for (r in 0 until rows) {
                    if (sum > 0) total[r * cols + c] += 1
                    if (r + radius + 1 <= rows - 1) sum += tmp[(r + radius + 1) * cols + c]
                    if (r - radius >= 0) sum -= tmp[(r - radius) * cols + c]
                }
            }
        }
        return FloatArray(mask.size) { if (mask[it].isNaN()) Float.NaN else total[it] * 100f / n }
    }

    /** The share of the [members] members set in each cell of a [mask], in percent (NaN stays NaN). */
    fun percent(mask: FloatArray, members: Int, into: FloatArray = FloatArray(mask.size)): FloatArray {
        val n = members.coerceIn(1, 20)
        for (i in mask.indices) {
            val m = mask[i]
            into[i] = if (m.isNaN()) Float.NaN else Integer.bitCount(m.toInt()) * 100f / n
        }
        return into
    }

    /**
     * [values] blurred by [passes] separable box blurs of half-width [radius] (about a Gaussian of sigma radius * 1.0 cells for 3 passes), edges averaging
     * what is there. NaN cells count as 0 while blurring and are NaN again afterwards.
     */
    fun blur(values: FloatArray, cols: Int, rows: Int, radius: Int, passes: Int = 3): FloatArray {
        if (radius <= 0 || values.size < cols * rows) return values
        var cur = FloatArray(values.size) { if (values[it].isNaN()) 0f else values[it] }
        var tmp = FloatArray(values.size)
        repeat(passes) {
            for (r in 0 until rows) {                                    // along the rows
                val base = r * cols
                var sum = 0.0
                for (c in 0..minOf(radius, cols - 1)) sum += cur[base + c]
                for (c in 0 until cols) {
                    val lo = maxOf(0, c - radius)
                    val hi = minOf(cols - 1, c + radius)
                    tmp[base + c] = (sum / (hi - lo + 1)).toFloat()
                    if (c + radius + 1 <= cols - 1) sum += cur[base + c + radius + 1]
                    if (c - radius >= 0) sum -= cur[base + c - radius]
                }
            }
            for (c in 0 until cols) {                                    // then down the columns
                var sum = 0.0
                for (r in 0..minOf(radius, rows - 1)) sum += tmp[r * cols + c]
                for (r in 0 until rows) {
                    val lo = maxOf(0, r - radius)
                    val hi = minOf(rows - 1, r + radius)
                    cur[r * cols + c] = (sum / (hi - lo + 1)).toFloat()
                    if (r + radius + 1 <= rows - 1) sum += tmp[(r + radius + 1) * cols + c]
                    if (r - radius >= 0) sum -= tmp[(r - radius) * cols + c]
                }
            }
        }
        for (i in values.indices) if (values[i].isNaN()) cur[i] = Float.NaN
        return cur
    }
}
