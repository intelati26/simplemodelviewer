package io.github.intelati26.simplemodelview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import simplemodelview.Core
import simplemodelview.Metar
import simplemodelview.View
import simplemodelview.WindBarbs
import simplemodelview.metarTemp
import simplemodelview.thinStations

/**
 * A surface station as the map draws it, in picture pixels: a dot in its flight category's color, the temperature (red) upper left, the dewpoint (green)
 * lower left, the present weather to the right and a wind barb from the dot. [u] / [v] are the wind on the picture (east = +x, north = up), in knots.
 */
class StationPlot(
    val metar: Metar, val x: Float, val y: Float, val temp: String?, val dew: String?, val weather: String?,
    val u: Float, val v: Float, val speed: Float, val category: String?,
)

/** The size of a plot's text for a picture [pictureWidth] wide: as the contour labels, so a plot reads the same on screen at any picture size. */
internal fun stationTextSize(pictureWidth: Int) = maxOf(11f, pictureWidth / 95f)

/**
 * The plots of [metars] on the picture of [view] (its grid's cells, [view]'s scale), thinned so no two are nearer than about five text heights. ASOS
 * stations of the US (K...) are kept first, then the rest, each set by its id so the choice does not move between frames.
 */
internal fun stationPlots(core: Core, metars: List<Metar>, view: View, celsius: Boolean): List<StationPlot> {
    val s = view.scale.toFloat() / view.step
    val w = view.pixelWidth
    val h = view.pixelHeight
    val ordered = metars.sortedWith(compareBy({ !it.id.startsWith("K") }, { it.id }))
    val xy = FloatArray(ordered.size * 2)
    val inside = BooleanArray(ordered.size)
    for ((i, m) in ordered.withIndex()) {
        val g = core.latLonToGrid(view.grid, m.lat, m.lon)
        val x = ((g[0] - view.col0 + 0.5) * s).toFloat()
        val y = ((view.row0 + view.rows - 0.5 - g[1]) * s).toFloat()
        xy[2 * i] = x; xy[2 * i + 1] = y
        inside[i] = x in 0f..w.toFloat() && y in 0f..h.toFloat()
    }
    val keepXy = ArrayList<Int>()
    for (i in ordered.indices) if (inside[i]) keepXy += i
    val packed = FloatArray(keepXy.size * 2) { k -> xy[2 * keepXy[k / 2] + k % 2] }
    val spacing = stationTextSize(w) * 4.2f
    return thinStations(packed, spacing).map { k ->
        val i = keepXy[k]
        val m = ordered[i]
        val x = xy[2 * i]; val y = xy[2 * i + 1]
        // which way north points on the picture here (a Lambert map turns a little from place to place)
        val g2 = core.latLonToGrid(view.grid, m.lat + 0.2, m.lon)
        var nx = ((g2[0] - view.col0 + 0.5) * s).toFloat() - x
        var ny = ((view.row0 + view.rows - 0.5 - g2[1]) * s).toFloat() - y
        val len = kotlin.math.hypot(nx, ny).takeIf { it > 1e-6f } ?: 1f
        nx /= len; ny /= len
        val ex = -ny; val ey = nx
        val speed = (m.windKt ?: 0).toFloat()
        var u = 0f; var v = 0f
        val dir = m.windDir
        if (dir != null && speed > 0f) {
            val r = Math.toRadians(dir.toDouble())
            val eu = (-speed * Math.sin(r)).toFloat(); val nv = (-speed * Math.cos(r)).toFloat()
            u = eu * ex + nv * nx; v = -(eu * ey + nv * ny)
        }
        StationPlot(m, x, y, metarTemp(m.tempC, celsius), metarTemp(m.dewC, celsius), m.weather?.takeIf { it.isNotBlank() }?.split(' ')?.first(), u, v, if (dir == null) 0f else speed, m.category)
    }
}

private fun categoryColor(category: String?): Int = when (category) {
    "VFR" -> 0xFF2E9E3E.toInt()
    "MVFR" -> 0xFF2F6FD6.toInt()
    "IFR" -> 0xFFD63131.toInt()
    "LIFR" -> 0xFFC23BC2.toInt()
    else -> 0xFF8A8A8A.toInt()
}

/** The plots over everything else, each text and line on a halo so it reads on any color underneath. */
internal fun drawStationPlots(canvas: Canvas, plots: List<StationPlot>, dark: Boolean, pictureWidth: Int) {
    val size = stationTextSize(pictureWidth)
    val halo = if (dark) 0xCC000000.toInt() else 0xE6FFFFFF.toInt()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; isFakeBoldText = true }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val ink = if (dark) 0xFFF2F2F2.toInt() else 0xFF111111.toInt()
    val tempInk = if (dark) 0xFFFF7A6E.toInt() else 0xFFC62828.toInt()
    val dewInk = if (dark) 0xFF6EE08A.toInt() else 0xFF1B7A2E.toInt()
    val barbLength = size * 2.4f
    val lineWidth = maxOf(1.2f, size / 11f)
    val barbs = Path(); val flags = Path()
    for (p in plots) if (p.speed >= 2.5f) {
        val glyph = WindBarbs.glyph(p.x, p.y, p.u, p.v, p.speed, barbLength)
        var i = 0
        while (i + 3 < glyph.lines.size) { barbs.moveTo(glyph.lines[i], glyph.lines[i + 1]); barbs.lineTo(glyph.lines[i + 2], glyph.lines[i + 3]); i += 4 }
        i = 0
        while (i + 5 < glyph.pennants.size) { flags.moveTo(glyph.pennants[i], glyph.pennants[i + 1]); flags.lineTo(glyph.pennants[i + 2], glyph.pennants[i + 3]); flags.lineTo(glyph.pennants[i + 4], glyph.pennants[i + 5]); flags.close(); i += 6 }
    }
    line.strokeWidth = lineWidth + 2.5f; line.color = halo; canvas.drawPath(barbs, line); canvas.drawPath(flags, line)
    line.strokeWidth = lineWidth; line.color = ink; canvas.drawPath(barbs, line)
    fill.color = ink; canvas.drawPath(flags, fill)
    val r = size * 0.32f
    fun label(s: String, x: Float, y: Float, color: Int, align: Paint.Align) {
        text.textAlign = align
        text.style = Paint.Style.STROKE; text.strokeWidth = size / 5f; text.color = halo; canvas.drawText(s, x, y, text)
        text.style = Paint.Style.FILL; text.color = color; canvas.drawText(s, x, y, text)
    }
    for (p in plots) {
        fill.color = halo; canvas.drawCircle(p.x, p.y, r + 2f, fill)
        fill.color = categoryColor(p.category); canvas.drawCircle(p.x, p.y, r, fill)
        p.temp?.let { label(it, p.x - r - 2f, p.y - r * 0.4f, tempInk, Paint.Align.RIGHT) }
        p.dew?.let { label(it, p.x - r - 2f, p.y + size * 0.95f, dewInk, Paint.Align.RIGHT) }
        p.weather?.let { label(it, p.x + r + 3f, p.y + size * 0.35f, ink, Paint.Align.LEFT) }
    }
}
