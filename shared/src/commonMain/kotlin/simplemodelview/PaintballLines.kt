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
    val colors = listOf(0x3B9AE1, 0x2CA02C, 0xE6B800, 0xF07D00, 0xC0141C)

    /** How many times the share is blurred before it is traced: about a 40-km neighborhood on the 3-km grid. */
    const val SMOOTHING = 12

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

    /** The share of the [members] members set in each cell of a [mask], in percent (NaN stays NaN). */
    fun percent(mask: FloatArray, members: Int, into: FloatArray = FloatArray(mask.size)): FloatArray {
        val n = members.coerceIn(1, 20)
        for (i in mask.indices) {
            val m = mask[i]
            into[i] = if (m.isNaN()) Float.NaN else Integer.bitCount(m.toInt()) * 100f / n
        }
        return into
    }
}
