package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import simplemodelview.ContourLine
import simplemodelview.Contours
import simplemodelview.Core
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.Palettes
import simplemodelview.Product
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.WindProducts
import simplemodelview.globeForward

/** Draws the globe picture: space, a shaded sphere, a graticule, the data wrapped onto it and the border lines. */
class GlobeRenderer(private val core: Core, private val lines: MapLines) {
    private var buffer = IntArray(0)

    /** A contour line in latitude / longitude (lat0, lon0, lat1, lon1, ...), so any camera can project it. */
    private class GeoLine(val level: Double, val bold: Boolean, val label: String, val points: DoubleArray)
    private var geoKey: Any? = null
    private var geoLines: List<GeoLine> = emptyList()

    /**
     * Draws the globe. [product] (with [contours] and [barbDensity]) adds what the flat map draws over its data: the field's contour lines, and for the wind
     * products (height and winds) the height lines and barbs, which lie the way the sphere does.
     */
    @Synchronized
    fun render(
        grid: Grid, values: FloatArray, style: Style, members: List<String>, cam: GlobeCamera, width: Int, height: Int, stride: Int, dark: Boolean,
        product: Product? = null, contours: Boolean = false, barbDensity: Int = DEFAULT_BARB_DENSITY, windInTrueFrame: Boolean = false,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF070B14.toInt() else 0xFFE8EDF3.toInt())
        val cx = width / 2f
        val cy = height / 2f
        val r = cam.radius.toFloat()

        // the sphere, lit from the upper left
        val ocean = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - 0.35f * r, cy - 0.35f * r, r * 1.5f,
                if (dark) intArrayOf(0xFF2B5278.toInt(), 0xFF10263D.toInt(), 0xFF08121F.toInt()) else intArrayOf(0xFFD6E8F7.toInt(), 0xFFA9C8E4.toInt(), 0xFF7FA6CB.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, ocean)

        drawGraticule(canvas, cam, width, height, dark)

        // data
        if (buffer.size < width * height) buffer = IntArray(width * height)
        val memberRgb = IntArray(10) { Palettes.memberColors[members.getOrNull(it)] ?: 0x808080 }
        val domain = if (dark) 0x66FFFFFF else 0x99FFFFFF.toInt()
        core.renderGlobe(grid, values, width, height, cam.radius, cam.lat, cam.lon, stride, domain, style, memberRgb, buffer)
        canvas.drawBitmap(buffer, 0, width, 0, 0, width, height, true, null)

        lines.drawGlobe(canvas, cam, width, height, dark, detailed = stride == 1)
        if (product != null) decorate(canvas, grid, values, style, product, cam, width, height, dark, contours, barbDensity, windInTrueFrame)

        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 1.5f; color = if (dark) 0x66FFFFFF else 0x66000000
        })
        return bitmap
    }

    /** The contour lines and the barbs of [product] on the sphere, drawn by the code the flat map uses. */
    private fun decorate(
        canvas: Canvas, grid: Grid, values: FloatArray, style: Style, product: Product, cam: GlobeCamera, width: Int, height: Int, dark: Boolean,
        contours: Boolean, barbDensity: Int, trueFrame: Boolean,
    ) {
        val cells = grid.cells
        val windLevel = WindProducts.planeLevel(product.id)?.takeIf { WindProducts.hasPlanes(values, cells) }
        if (contours) {
            val spec = if (windLevel != null) Contours.specFor("z${if (windLevel == WindProducts.STACK) 500 else windLevel}", "m") else Contours.specFor(product.id, style.units)
            if (spec != null) {
                val key = Triple(values, product.id, grid)
                if (geoKey != key) {
                    val field = if (windLevel != null) WindProducts.plane(values, cells, WindProducts.HEIGHT) else values
                    val cstyle = if (windLevel != null) Style() else style
                    // the lines are traced on the whole grid (pixels of a one-pixel-per-cell view), then every vertex becomes a latitude / longitude
                    val whole = View("globe", 0, 0, grid.columns, grid.rows, 1, 1, grid)
                    geoLines = Contours.lines(field, grid, whole, cstyle, spec).map { line -> GeoLine(line.level, line.bold, line.label, toLatLon(grid, whole, line)) }
                    geoKey = key
                }
                drawContourLines(canvas, projectedContours(cam, width, height), spec.color, dark, width)
            }
        }
        if (windLevel != null) {
            val across = barbsAcross(barbDensity) * if (level(windLevel, values, cells)) STACK_DENSITY else 1f
            drawWindBarbs(canvas, core, GlobeProjector(cam, width, height), grid, values, windLevel, dark, width / across, maxOf(1.2f, width / 1300f), 2f, trueFrame)
        }
    }

    private fun level(windLevel: Int, values: FloatArray, cells: Int) = windLevel == WindProducts.STACK && WindProducts.isStackFrame(values, cells)

    private fun toLatLon(grid: Grid, whole: View, line: ContourLine): DoubleArray {
        val p = line.points
        val out = DoubleArray(p.size)
        var i = 0
        while (i + 1 < p.size) {
            val col = p[i] - 0.5 + whole.col0
            val row = whole.row0 + whole.rows - 0.5 - p[i + 1]
            val ll = core.gridToLatLon(grid, col, row)
            out[i] = ll[0]; out[i + 1] = ll[1]
            i += 2
        }
        return out
    }

    /** The contour lines as the camera sees them, in picture pixels: broken where a line goes over the horizon, a label on the longest piece of each level. */
    private fun projectedContours(cam: GlobeCamera, width: Int, height: Int): List<ContourLine> {
        val out = ArrayList<ContourLine>()
        for (line in geoLines) {
            val pieces = ArrayList<FloatArray>()
            var current = ArrayList<Float>()
            var i = 0
            while (i + 1 < line.points.size) {
                val p = globeForward(cam, width, height, line.points[i], line.points[i + 1])
                if (p == null) { if (current.size >= 4) pieces += current.toFloatArray(); current = ArrayList() }
                else { current.add(p[0].toFloat()); current.add(p[1].toFloat()) }
                i += 2
            }
            if (current.size >= 4) pieces += current.toFloatArray()
            val best = pieces.indices.maxByOrNull { pieces[it].size } ?: continue
            pieces.forEachIndexed { k, pts ->
                val mid = if (k == best && pts.size >= 24) (pts.size / 4) * 2 else -1
                out.add(ContourLine(line.level, line.bold, pts, line.label, mid, false))
            }
        }
        return out
    }

    private fun drawGraticule(canvas: Canvas, cam: GlobeCamera, width: Int, height: Int, dark: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = if (dark) 0x33FFFFFF else 0x33000000
        }
        fun polyline(points: Sequence<Pair<Double, Double>>) {
            var prev: DoubleArray? = null
            for ((lat, lon) in points) {
                val p = globeForward(cam, width, height, lat, lon)
                if (p != null && prev != null) canvas.drawLine(prev[0].toFloat(), prev[1].toFloat(), p[0].toFloat(), p[1].toFloat(), paint)
                prev = p
            }
        }
        for (lat in -60..60 step 20) polyline((-180..180 step 3).asSequence().map { lat.toDouble() to it.toDouble() })
        for (lon in -180 until 180 step 20) polyline((-85..85 step 3).asSequence().map { it.toDouble() to lon.toDouble() })
    }
}
