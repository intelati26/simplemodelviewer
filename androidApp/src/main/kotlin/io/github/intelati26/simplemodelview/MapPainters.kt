package io.github.intelati26.simplemodelview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import simplemodelview.ContourLine
import simplemodelview.Core
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.View
import simplemodelview.WindBarbs
import simplemodelview.WindProducts
import simplemodelview.globeForward

/**
 * What the flat map and the globe share when they draw lines and barbs over the data: the same painting code, over a [MapProjector] that says where a place is
 * on the picture. A new view (another projection) only needs a projector.
 */
internal interface MapProjector {
    val width: Int
    val height: Int
    /** Pixels per degree of latitude near the middle of the picture. */
    val pixelsPerDegree: Double
    val latRange: ClosedFloatingPointRange<Double>
    /** The longitudes (may pass 180) to look at on the latitude [lat]. */
    fun lonRange(lat: Double): ClosedFloatingPointRange<Double>
    /** The pixel (x, y) of a place; null when it is not on the picture (the far side of a globe). */
    fun project(lat: Double, lon: Double): DoubleArray?
}

/** A flat grid window as [view] draws it, in the pixels of a picture [width] x [height]. */
internal class FlatProjector(private val core: Core, private val view: View, override val width: Int, override val height: Int) : MapProjector {
    private val scale = view.scale.toDouble() / view.step
    private val bounds: DoubleArray
    override val pixelsPerDegree: Double

    init {
        var south = 90.0; var north = -90.0; var west = 180.0; var east = -180.0
        for (i in 0..4) for (j in 0..4) {
            val ll = core.gridToLatLon(view.grid, view.col0 + view.cols * i / 4.0, view.row0 + view.rows * j / 4.0)
            south = minOf(south, ll[0]); north = maxOf(north, ll[0]); west = minOf(west, ll[1]); east = maxOf(east, ll[1])
        }
        bounds = doubleArrayOf(south, north, west, east)
        val mid = core.gridToLatLon(view.grid, view.col0 + view.cols / 2.0, view.row0 + view.rows / 2.0)
        val a = project(mid[0], mid[1])
        val b = project(mid[0] + 1.0, mid[1])
        pixelsPerDegree = if (a == null || b == null) 20.0 else maxOf(1.0, Math.hypot(b[0] - a[0], b[1] - a[1]))
    }

    override val latRange get() = bounds[0]..bounds[1]
    override fun lonRange(lat: Double) = bounds[2]..bounds[3]
    override fun project(lat: Double, lon: Double): DoubleArray? {
        val g = core.latLonToGrid(view.grid, lat, lon)
        return doubleArrayOf((g[0] + 0.5 - view.col0) * scale, (view.row0 + view.rows - 0.5 - g[1]) * scale)
    }
}

/** The globe as [cam] has it, in a picture [width] x [height]. */
internal class GlobeProjector(private val cam: GlobeCamera, override val width: Int, override val height: Int) : MapProjector {
    override val pixelsPerDegree get() = cam.radius * Math.PI / 180.0
    // how far from the camera's point the picture reaches, in degrees
    private val reach = Math.toDegrees(Math.asin(minOf(1.0, Math.hypot(width.toDouble(), height.toDouble()) / 2 / cam.radius)))
    override val latRange get() = maxOf(-85.0, cam.lat - reach)..minOf(85.0, cam.lat + reach)
    override fun lonRange(lat: Double): ClosedFloatingPointRange<Double> {
        val c = Math.cos(Math.toRadians(lat))
        val r = if (c < 0.2) 180.0 else minOf(180.0, reach / c)
        return (cam.lon - r)..(cam.lon + r)
    }
    override fun project(lat: Double, lon: Double): DoubleArray? = globeForward(cam, width, height, lat, lon)
}

/** Contour lines over the data and the map lines: a halo under each line so it reads on any color, heavier for the bold levels, one label per level. */
internal fun drawContourLines(canvas: Canvas, contours: List<ContourLine>, color: Int, dark: Boolean, pictureWidth: Int) {
    val width = maxOf(1.5f, pictureWidth / 900f)
    val ink = if (dark) 0xFFF0F0F0.toInt() else 0xFF000000.toInt() or color
    val halo = if (dark) 0xAA000000.toInt() else 0xCCFFFFFF.toInt()
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = maxOf(11f, pictureWidth / 90f); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    val labels = ArrayList<ContourLine>()
    for (c in contours) {
        if (c.points.size < 4) continue
        val path = Path()
        path.moveTo(c.points[0], c.points[1])
        var i = 2
        while (i + 1 < c.points.size) { path.lineTo(c.points[i], c.points[i + 1]); i += 2 }
        val w = if (c.bold) width * 1.8f else width * 0.8f       // the ordinary levels are the quiet ones
        line.strokeWidth = w + 2f; line.color = if (c.bold) halo else (halo and 0x00FFFFFF) or 0x70000000; canvas.drawPath(path, line)
        line.strokeWidth = w; line.color = if (c.bold) ink else (ink and 0x00FFFFFF) or 0xB0000000.toInt(); canvas.drawPath(path, line)
        if (c.labelAt >= 0) labels.add(c)
    }
    for (c in labels) {
        val x = c.points[c.labelAt]; val y = c.points[c.labelAt + 1]
        if (x < 0 || y < 0 || x > pictureWidth) continue
        text.style = Paint.Style.STROKE; text.strokeWidth = 4f; text.color = halo; canvas.drawText(c.label, x, y + text.textSize / 3, text)
        text.style = Paint.Style.FILL; text.color = ink; canvas.drawText(c.label, x, y + text.textSize / 3, text)
    }
}

/**
 * Wind barbs, the same on the flat map and the globe: a lattice of latitudes and longitudes (multiples of a step, wider in longitude toward the poles), so a
 * barb stays on its place whatever the view does, and a barb's size is the same on screen at any zoom ([spacing] pixels between barbs, [lineWidth] thick). The
 * step of the lattice is the one that comes closest to that spacing from above, in whole stages. The wind of the cell under each point is turned to true east /
 * north (from the grid's axes unless [trueFrame]) and then onto the picture at that spot. The stack (three levels) draws its barbs in three colors from the same
 * points, the 500 mb one on top, with a key in the corner.
 */
internal fun drawWindBarbs(
    canvas: Canvas, core: Core, proj: MapProjector, grid: Grid, values: FloatArray, level: Int, dark: Boolean,
    spacing: Float, lineWidth: Float, haloExtra: Float, trueFrame: Boolean, keyScale: Float = 1f,
) {
    val cells = grid.cells
    val stack = level == WindProducts.STACK && WindProducts.isStackFrame(values, cells)
    val length = spacing * 0.7f
    val sets = if (stack) WindProducts.stackLevels.indices.map { k -> WindProducts.stackU(k) to WindProducts.stackV(k) } else listOf(WindProducts.U to WindProducts.V)
    val inks = if (stack) {
        if (dark) listOf(0xFF5EDC8A.toInt(), 0xFF6CB4FF.toInt(), 0xFFFF7A7A.toInt()) else listOf(0xFF157A3A.toInt(), 0xFF1F5FBF.toInt(), 0xFFC62828.toInt())
    } else listOf(if (dark) 0xFFF4F4F4.toInt() else 0xFF101010.toInt())
    val halo = if (dark) 0xAA000000.toInt() else 0xCCFFFFFF.toInt()
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val paths = sets.map { Path() to Path() }
    val stages = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 12.0, 20.0)
    val wanted = spacing / proj.pixelsPerDegree
    val delta = stages.firstOrNull { it >= wanted } ?: stages.last()
    var lat = Math.ceil(proj.latRange.start / delta) * delta
    while (lat <= proj.latRange.endInclusive) {
        val c = Math.cos(Math.toRadians(lat))
        val lonStep = delta * maxOf(1.0, Math.round(1.0 / maxOf(c, 0.05)).toDouble())
        val range = proj.lonRange(lat)
        var lon = Math.ceil(range.start / lonStep) * lonStep
        while (lon <= range.endInclusive) {
            val lonNow = ((lon + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
            val here = proj.project(lat, lonNow)
            if (here != null && here[0] > -length && here[0] < proj.width + length && here[1] > -length && here[1] < proj.height + length) {
                val rc = core.latLonToGrid(grid, lat, lonNow)
                val col = Math.round(rc[0]).toInt()
                val row = Math.round(rc[1]).toInt()
                val up = proj.project(lat + 0.7, lonNow)
                if (col in 0 until grid.columns && row in 0 until grid.rows && up != null) {
                    var nx = (up[0] - here[0]).toFloat(); var ny = (up[1] - here[1]).toFloat()       // which way north points on the picture here
                    val len = Math.hypot(nx.toDouble(), ny.toDouble()).toFloat()
                    if (len > 1e-6f) {
                        nx /= len; ny /= len
                        val ex = -ny; val ey = nx           // east: north turned a quarter clockwise
                        for ((k, uv) in sets.withIndex()) {
                            val gu = values[cells * uv.first + row * grid.columns + col]
                            val gv = values[cells * uv.second + row * grid.columns + col]
                            val speed = WindProducts.speed(gu, gv)
                            if (speed.isNaN() || speed < 2.5f) continue
                            var eu = gu; var nv = gv
                            if (!trueFrame) {
                                val bearing = Math.toRadians(WindProducts.compassDegrees(WindProducts.fromDegrees(gu, gv), lonNow))
                                eu = (-speed * Math.sin(bearing)).toFloat(); nv = (-speed * Math.cos(bearing)).toFloat()
                            }
                            val glyph = WindBarbs.glyph(here[0].toFloat(), here[1].toFloat(), eu * ex + nv * nx, -(eu * ey + nv * ny), speed, length)
                            val (path, flags) = paths[k]
                            var i = 0
                            while (i + 3 < glyph.lines.size) { path.moveTo(glyph.lines[i], glyph.lines[i + 1]); path.lineTo(glyph.lines[i + 2], glyph.lines[i + 3]); i += 4 }
                            i = 0
                            while (i + 5 < glyph.pennants.size) {
                                flags.moveTo(glyph.pennants[i], glyph.pennants[i + 1]); flags.lineTo(glyph.pennants[i + 2], glyph.pennants[i + 3]); flags.lineTo(glyph.pennants[i + 4], glyph.pennants[i + 5]); flags.close()
                                i += 6
                            }
                        }
                    }
                }
            }
            lon += lonStep
        }
        lat += delta
    }
    for ((k, pair) in paths.withIndex()) {
        val (path, flags) = pair
        line.strokeWidth = lineWidth + haloExtra; line.color = halo; canvas.drawPath(path, line); canvas.drawPath(flags, line)
        line.strokeWidth = lineWidth; line.color = inks[k]; canvas.drawPath(path, line)
        fill.color = inks[k]; canvas.drawPath(flags, fill)
    }
    if (stack) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (maxOf(14f, proj.width / 70f) * keyScale).coerceAtLeast(8f); isFakeBoldText = true }
        var x = proj.width / 60f * keyScale
        val y = proj.height - proj.width / 60f * keyScale
        for (k in WindProducts.stackLevels.indices) {
            val label = "${WindProducts.stackLevels[k]} mb"
            text.style = Paint.Style.STROKE; text.strokeWidth = 4f; text.color = halo; canvas.drawText(label, x, y, text)
            text.style = Paint.Style.FILL; text.color = inks[k]; canvas.drawText(label, x, y, text)
            x += text.measureText("$label   ")
        }
    }
}
