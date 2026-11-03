package simplemodelview

/**
 * Paintball contours: one line per ensemble member at a threshold, each member in its own color. The frame of such a product is the
 * member bit mask of the model's own paintball fields (bit k set where member k reaches the threshold), so the read-out and the legend
 * are the ones those use; the lines are traced from each member's bit.
 */
object PaintballLines {
    /** [source]: the member array it is made from; [threshold]: in that array's stored units; [label]: how the threshold reads in a title. */
    class Spec(val id: String, val source: String, val threshold: Float, val title: String)

    val specs = listOf(
        Spec("pbl_cref", "cref", 40f, "Composite Reflectivity Paintball Contours (> 40 dBZ)"),
        Spec("pbl_uh25", "uh25_04h_max_overlap", 75f, "2-5 km Updraft Helicity Paintball Contours (> 75 m2/s2)"),
    )

    fun spec(id: String): Spec? = specs.firstOrNull { it.id == id }

    fun isPaintballLines(id: String) = spec(id) != null

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

    /** Member [k]'s 0 / 1 field out of a [mask] (NaN stays NaN), written into [into]. */
    fun memberField(mask: FloatArray, k: Int, into: FloatArray): FloatArray {
        for (i in mask.indices) {
            val m = mask[i]
            into[i] = if (m.isNaN()) Float.NaN else if (m.toInt() and (1 shl k) != 0) 1f else 0f
        }
        return into
    }
}
