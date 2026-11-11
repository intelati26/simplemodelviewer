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
import simplemodelview.WindBarbs
import simplemodelview.WindProducts
import simplemodelview.globeForward

/** Draws the globe picture: space, a shaded sphere, a graticule, the data wrapped onto it and the border lines. */
class GlobeRenderer(private val core: Core, private val lines: MapLines) {
    private var buffer = IntArray(0)

    /** A contour line in latitude / longitude (lat0, lon0, lat1, lon1, ...), so any camera can project it. */
    private class GeoLine(val bold: Boolean, val points: DoubleArray)
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

    /** The contour lines and the barbs of [product] on the sphere. */
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
                    geoLines = Contours.lines(field, grid, whole, cstyle, spec).map { line -> GeoLine(line.bold, toLatLon(grid, whole, line)) }
                    geoKey = key
                }
                drawGeoLines(canvas, cam, width, height, dark, spec.color)
            }
        }
        if (windLevel != null) drawBarbs(canvas, grid, values, windLevel, cam, width, height, dark, barbDensity, trueFrame)
    }

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

    private fun drawGeoLines(canvas: Canvas, cam: GlobeCamera, width: Int, height: Int, dark: Boolean, color: Int) {
        val ink = if (dark) 0xFFF0F0F0.toInt() else 0xFF000000.toInt() or color
        val halo = if (dark) 0xAA000000.toInt() else 0xCCFFFFFF.toInt()
        val base = maxOf(1.2f, width / 1000f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val quiet = android.graphics.Path()
        val bold = android.graphics.Path()
        for (line in geoLines) {
            val path = if (line.bold) bold else quiet
            var pen = false
            var i = 0
            while (i + 1 < line.points.size) {
                val p = globeForward(cam, width, height, line.points[i], line.points[i + 1])
                if (p == null) pen = false
                else { if (pen) path.lineTo(p[0].toFloat(), p[1].toFloat()) else path.moveTo(p[0].toFloat(), p[1].toFloat()); pen = true }
                i += 2
            }
        }
        paint.strokeWidth = base * 1.8f + 2f; paint.color = halo; canvas.drawPath(bold, paint)
        paint.strokeWidth = base * 0.8f + 2f; paint.color = (halo and 0x00FFFFFF) or 0x70000000; canvas.drawPath(quiet, paint)
        paint.strokeWidth = base * 1.8f; paint.color = ink; canvas.drawPath(bold, paint)
        paint.strokeWidth = base * 0.8f; paint.color = (ink and 0x00FFFFFF) or 0xB0000000.toInt(); canvas.drawPath(quiet, paint)
    }

    /**
     * Wind barbs locked to places: the lattice is of latitudes and longitudes (multiples of a step, wider in longitude toward the poles), so a barb stays where
     * it is as the globe turns and rides over the horizon with it. The barbs keep their size on screen at any zoom ([barbsAcross] to the picture's width); zooming
     * only changes the step of the lattice, in whole stages, so the winds show in finer detail. The wind of the cell under each point is turned to true
     * east / north (from the grid's axes unless [trueFrame]) and then onto the screen at that spot.
     */
    private fun drawBarbs(canvas: Canvas, grid: Grid, values: FloatArray, level: Int, cam: GlobeCamera, width: Int, height: Int, dark: Boolean, barbDensity: Int, trueFrame: Boolean) {
        val cells = grid.cells
        val stack = level == WindProducts.STACK && WindProducts.isStackFrame(values, cells)
        val spacing = width / (barbsAcross(barbDensity) * if (stack) 0.8f else 1f)      // screen pixels, whatever the zoom
        val length = spacing * 0.7f
        val sets = if (stack) WindProducts.stackLevels.indices.map { k -> WindProducts.stackU(k) to WindProducts.stackV(k) } else listOf(WindProducts.U to WindProducts.V)
        val inks = if (stack) {
            if (dark) listOf(0xFF5EDC8A.toInt(), 0xFF6CB4FF.toInt(), 0xFFFF7A7A.toInt()) else listOf(0xFF157A3A.toInt(), 0xFF1F5FBF.toInt(), 0xFFC62828.toInt())
        } else listOf(if (dark) 0xFFF4F4F4.toInt() else 0xFF101010.toInt())
        val halo = if (dark) 0xAA000000.toInt() else 0xCCFFFFFF.toInt()
        val lineWidth = maxOf(1.2f, width / 1300f)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val paths = sets.map { android.graphics.Path() to android.graphics.Path() }
        // the lattice step: the degrees the spacing covers at the center of the sphere, rounded up to one of a few stages
        val wanted = Math.toDegrees(spacing / cam.radius)
        val stages = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 12.0, 20.0)
        val delta = stages.firstOrNull { it >= wanted } ?: stages.last()
        // how far from the camera's point the screen reaches, in degrees
        val reach = Math.toDegrees(Math.asin(minOf(1.0, Math.hypot(width.toDouble(), height.toDouble()) / 2 / cam.radius)))
        var lat = Math.ceil(maxOf(-85.0, cam.lat - reach) / delta) * delta
        val latEnd = minOf(85.0, cam.lat + reach)
        while (lat <= latEnd) {
            val c = Math.cos(Math.toRadians(lat))
            val lonStep = delta * maxOf(1.0, Math.round(1.0 / maxOf(c, 0.05)).toDouble())     // wider apart toward the poles, still multiples of the step
            val lonReach = if (c < 0.2) 180.0 else minOf(180.0, reach / c)
            var lon = Math.ceil((cam.lon - lonReach) / lonStep) * lonStep
            while (lon <= cam.lon + lonReach) {
                val lonNow = ((lon + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
                val here = globeForward(cam, width, height, lat, lonNow)
                if (here != null && here[0] > -length && here[0] < width + length && here[1] > -length && here[1] < height + length) {
                    val rc = core.latLonToGrid(grid, lat, lonNow)
                    val col = Math.round(rc[0]).toInt()
                    val row = Math.round(rc[1]).toInt()
                    val up = globeForward(cam, width, height, lat + 0.7, lonNow)
                    if (col in 0 until grid.columns && row in 0 until grid.rows && up != null) {
                        var nx = (up[0] - here[0]).toFloat(); var ny = (up[1] - here[1]).toFloat()
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
                                val wx = eu * ex + nv * nx
                                val wy = eu * ey + nv * ny
                                val glyph = WindBarbs.glyph(here[0].toFloat(), here[1].toFloat(), wx, -wy, speed, length)
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
            line.strokeWidth = lineWidth + 2f; line.color = halo; canvas.drawPath(path, line); canvas.drawPath(flags, line)
            line.strokeWidth = lineWidth; line.color = inks[k]; canvas.drawPath(path, line)
            fill.color = inks[k]; canvas.drawPath(flags, fill)
        }
        if (stack) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = maxOf(14f, width / 70f); isFakeBoldText = true }
            var x = width / 60f
            val y = height - width / 60f
            for (k in WindProducts.stackLevels.indices) {
                val label = "${WindProducts.stackLevels[k]} mb"
                text.style = Paint.Style.STROKE; text.strokeWidth = 4f; text.color = halo; canvas.drawText(label, x, y, text)
                text.style = Paint.Style.FILL; text.color = inks[k]; canvas.drawText(label, x, y, text)
                x += text.measureText("$label   ")
            }
        }
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
