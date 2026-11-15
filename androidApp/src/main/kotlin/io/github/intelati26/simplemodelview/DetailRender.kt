package io.github.intelati26.simplemodelview

import simplemodelview.View
import kotlin.math.ceil
import kotlin.math.floor

/** A finer drawing of the grid cells on screen: the [view] to draw, and where it sits in the base picture (fractions of its width and height, y down). */
internal class DetailWindow(val view: View, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The part of the base picture [base] that the visible fractions ([l], [t], [r], [b]) show, as a view of its own drawn at more pixels per cell, so a zoomed-in picture
 * can be drawn again, finer, after the zoom has settled (and only the part on screen, which is what keeps it quick). Null when little is zoomed or the base already has
 * the detail.
 */
internal fun detailWindow(base: View, l: Float, t: Float, r: Float, b: Float, maxPixels: Int = 2400): DetailWindow? {
    if ((r - l) * (b - t) > 0.6f) return null
    val cMin = base.col0 + floor(l * base.cols).toInt().coerceIn(0, base.cols - 1)
    val cMax = base.col0 + (ceil(r * base.cols).toInt() - 1).coerceIn(0, base.cols - 1)
    val rMax = base.row0 + base.rows - 1 - floor(t * base.rows).toInt().coerceIn(0, base.rows - 1)
    val rMin = base.row0 + base.rows - 1 - (ceil(b * base.rows).toInt() - 1).coerceIn(0, base.rows - 1)
    if (cMax <= cMin || rMax <= rMin) return null
    val cols = cMax - cMin + 1
    val rows = rMax - rMin + 1
    val scale = (maxPixels / cols).coerceIn(1, 8)
    if (scale <= base.scale.toFloat() / base.step * 1.2f) return null
    val view = View("Detail", cMin, rMin, cols, rows, scale, 1, base.grid)
    return DetailWindow(
        view,
        (cMin - base.col0).toFloat() / base.cols, 1f - (rMax + 1 - base.row0).toFloat() / base.rows,
        (cMax + 1 - base.col0).toFloat() / base.cols, 1f - (rMin - base.row0).toFloat() / base.rows,
    )
}
