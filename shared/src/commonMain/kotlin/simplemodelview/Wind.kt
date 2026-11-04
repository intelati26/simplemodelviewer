package simplemodelview

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Upper-air wind products of the REFS page: the ensemble-mean wind speed in color, wind barbs and the height of the level as contour lines.
 * The store has the three as separate arrays (`u500_mean`, `v500_mean`, `z500_mean`, in knots and meters); a product here is a virtual one
 * whose frame is the four planes of [pack] one after the other, so the speed plane alone is what the rasteriser, the read-out and a
 * `values.size >= cells` check see.
 */
object WindProducts {
    val levels = listOf(500, 700, 850)
    const val SPEED = 0
    const val HEIGHT = 1
    const val U = 2
    const val V = 3
    const val PLANES = 4

    fun id(level: Int) = "wind${level}_mean"
    fun level(id: String): Int? = levels.firstOrNull { id == id(it) }
    /** What can be in color under a level's height contours and barbs: the wind speed itself or another field ([source] = the store array, `%d` the level). */
    class Color(val key: String, val title: String, val source: String?)
    val colors = listOf(
        Color("speed", "Wind speed", null),
        Color("t2m", "2-m temperature", "t2m_mean"),
        Color("tlev", "Temperature at the level", "t%d_mean"),
        Color("td2m", "2-m dewpoint", "td2m_mean"),
        Color("rh2m", "2-m relative humidity", "rh2m_mean"),
    )

    /** The id of the virtual product of [level] drawn over [color]: `wind500_mean` for the speed, `wind500_t2m_mean` and so on for the others. */
    fun id(level: Int, color: Color) = if (color.source == null) id(level) else "wind${level}_${color.key}_mean"

    /** The level and color of a virtual product id, or null. */
    fun parse(id: String): Pair<Int, Color>? {
        for (l in levels) for (c in colors) if (id == id(l, c)) return l to c
        return null
    }

    /** The store array behind the color of [level]'s product (null for the wind speed, which is computed). */
    fun colorSource(level: Int, color: Color): String? = color.source?.let { if ("%d" in it) it.format(level) else it }

    /** The picker's single entry for all of these: the level and the color are chosen with chips beside the product. */
    const val CHOOSER_ID = "heightwind"

    /** The level of a product made of the four planes (the color field or speed, height, u, v), whichever its color. */
    fun planeLevel(id: String): Int? = parse(id)?.first
    fun isWind(id: String) = planeLevel(id) != null
    /** True when the first plane is not the wind speed. */
    fun hasColorField(id: String) = parse(id)?.second?.source != null

    /** The store arrays behind a level: height, u, v. */
    fun sources(level: Int) = Triple("z${level}_mean", "u${level}_mean", "v${level}_mean")

    /** Speed (knots) of u / v components; NaN where either is missing. */
    fun speed(u: Float, v: Float): Float = if (u.isNaN() || v.isNaN()) Float.NaN else hypot(u, v)

    /** The frame of a wind product: speed (or the [color] field of a temperature product), height, u and v, each [cells] long. */
    fun pack(cells: Int, height: FloatArray, u: FloatArray, v: FloatArray, color: FloatArray? = null): FloatArray {
        val out = FloatArray(cells * PLANES)
        if (color != null) color.copyInto(out, 0, 0, cells) else for (i in 0 until cells) out[i] = speed(u[i], v[i])
        height.copyInto(out, cells, 0, cells)
        u.copyInto(out, cells * U, 0, cells)
        v.copyInto(out, cells * V, 0, cells)
        return out
    }

    fun plane(values: FloatArray, cells: Int, plane: Int): FloatArray = values.copyOfRange(cells * plane, cells * (plane + 1))

    fun hasPlanes(values: FloatArray, cells: Int) = values.size >= cells * PLANES

    /** The direction the wind blows from, in degrees clockwise from the grid's "up" (the map's north), for components along the grid axes. */
    fun fromDegrees(u: Float, v: Float): Double {
        val d = (270.0 - Math.toDegrees(atan2(v.toDouble(), u.toDouble()))) % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /**
     * The components in the REFS files follow the grid, not the compass (checked: the wind runs along the height contours in grid axes at
     * the west, middle and east of the domain). A compass bearing is the grid one plus how far the map's north is turned at that longitude:
     * n * (longitude - 97.5 W) for the Lambert conformal projection with n = sin(38.5 N).
     */
    fun compassDegrees(gridFrom: Double, longitude: Double): Double {
        val d = (gridFrom + 0.6225 * (longitude + 97.5)) % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /** "WSW" and so on for a compass bearing. */
    fun compassName(degrees: Double): String {
        val names = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[((degrees / 22.5).roundToInt()) % 16]
    }
}

/** One wind barb in picture pixels: [lines] are x0, y0, x1, y1 ... pairs (staff and barbs), [pennants] triangles as x0, y0, x1, y1, x2, y2. */
class BarbGlyph(val lines: FloatArray, val pennants: FloatArray)

object WindBarbs {
    /**
     * Barbs for the cells of [view] on a lattice about [spacing] pixels apart, from the grid's [u] / [v] (knots, along the grid axes, so the
     * barbs lie the way the picture does). The staff points to where the wind comes from; the speed is rounded to 5 kt: a pennant is 50, a
     * long barb 10, a short barb 5 (north-hemisphere side: clockwise of the staff). Winds under 2.5 kt get no barb.
     */
    fun glyphs(u: FloatArray, v: FloatArray, grid: Grid, view: View, spacing: Float, length: Float): List<BarbGlyph> {
        val cols = grid.columns
        val rows = grid.rows
        if (u.size < cols * rows || v.size < cols * rows) return emptyList()
        val scale = view.scale.toFloat() / view.step
        val every = maxOf(1, (spacing / scale).roundToInt())
        val out = ArrayList<BarbGlyph>()
        var r = view.row0 + every / 2
        while (r < view.row0 + view.rows && r < rows) {
            var c = view.col0 + every / 2
            while (c < view.col0 + view.cols && c < cols) {
                val uu = u[r * cols + c]
                val vv = v[r * cols + c]
                val speed = WindProducts.speed(uu, vv)
                if (!speed.isNaN() && speed >= 2.5f) {
                    val x = (c - view.col0 + 0.5f) * scale
                    val y = (view.row0 + view.rows - 0.5f - r) * scale
                    out.add(glyph(x, y, uu, vv, speed, length))
                }
                c += every
            }
            r += every
        }
        return out
    }

    /** The barb of one wind at (x, y), staff [length] long. */
    fun glyph(x: Float, y: Float, u: Float, v: Float, speed: Float, length: Float): BarbGlyph {
        val tx = -u / speed          // toward where the wind comes from, on the picture (y down): east is +x, north is -y
        val ty = v / speed
        val px = -ty                 // clockwise of the staff
        val py = tx
        val ex = x + tx * length
        val ey = y + ty * length
        val lines = ArrayList<Float>()
        val pennants = ArrayList<Float>()
        lines.addAll(listOf(x, y, ex, ey))
        val knots = (speed / 5f).roundToInt() * 5
        var flags = knots / 50
        var longs = (knots % 50) / 10
        val shorts = (knots % 10) / 5
        val barb = length * 0.45f
        val gap = length * 0.14f
        var pos = 0f
        while (flags > 0) {
            val ax = ex - tx * pos; val ay = ey - ty * pos
            val bx = ex - tx * (pos + gap); val by = ey - ty * (pos + gap)
            pennants.addAll(listOf(ax, ay, bx, by, ax + px * barb, ay + py * barb))
            pos += gap * 1.2f
            flags -= 1
        }
        if (knots >= 50 && (longs > 0 || shorts > 0)) pos += gap * 0.3f
        while (longs > 0) {
            val ax = ex - tx * pos; val ay = ey - ty * pos
            lines.addAll(listOf(ax, ay, ax + px * barb + tx * barb * 0.3f, ay + py * barb + ty * barb * 0.3f))
            pos += gap
            longs -= 1
        }
        if (shorts > 0) {
            if (knots < 10) pos = gap       // a lone short barb sits one step in from the end
            val ax = ex - tx * pos; val ay = ey - ty * pos
            lines.addAll(listOf(ax, ay, ax + px * barb * 0.5f + tx * barb * 0.15f, ay + py * barb * 0.5f + ty * barb * 0.15f))
        }
        return BarbGlyph(lines.toFloatArray(), pennants.toFloatArray())
    }
}
