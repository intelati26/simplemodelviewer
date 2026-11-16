package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import simplemodelview.ContourLine
import simplemodelview.ContourSpec
import simplemodelview.Contours
import simplemodelview.Kind
import simplemodelview.PaintballLines
import simplemodelview.Core
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.Palettes
import simplemodelview.PolarCamera
import simplemodelview.Product
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.WindProducts
import simplemodelview.globeForward

/** The side of the square a globe or polar picture is saved at, and of a loop frame (before the video size scales it). */
internal const val GLOBE_EXPORT_SIDE = 3000
internal const val GLOBE_LOOP_SIDE = 2000

/** How many coordinates of contour lines a globe renderer keeps for the frames of a loop (4 bytes each). */
private const val GEO_BUDGET = 12_000_000L

/** A second field over the globe's data (the MRMS overlays): its [values] on [grid], drawn with [style]; [marker] fields are a cross per cell, [halo] ones edged and haloed. */
class GlobeOverlay(
    val grid: Grid, val values: FloatArray, val style: Style, val marker: Boolean = false, val markerScale: Float = 1f,
    val halo: Boolean = false, val markerColor: Int? = null,
)

/** Draws the globe picture: space, a shaded sphere, a graticule, the data wrapped onto it and the border lines. */
class GlobeRenderer(private val core: Core, private val lines: MapLines) {
    private var buffer = IntArray(0)
    private val alertPainter = MapRenderer(core, lines)
    private var layerBuffer = IntArray(0)

    /** A contour line in latitude / longitude (lat0, lon0, lat1, lon1, ...), so any camera can project it. */
    private class GeoLine(val level: Double, val bold: Boolean, val label: String, val points: FloatArray)       // (float is a metre or so: half the memory of a double)
    private var geoKey: Any? = null
    private var geoLines: List<GeoLine> = emptyList()

    /**
     * The contour lines of frames a loop plays, by the frame's name: tracing a field and turning every vertex into a latitude / longitude is the costly part of a
     * frame, and a loop shows the same frames again and again. Kept until about [GEO_BUDGET] coordinates (some 50 MB) are held, the least recently used going first.
     */
    private val geoCache = LinkedHashMap<String, List<GeoLine>>(32, 0.75f, true)
    private var geoCacheSize = 0L
    private val geoLock = Any()

    private fun cachedGeo(key: String): List<GeoLine>? = synchronized(geoLock) { geoCache[key] }

    private fun putGeo(key: String, lines: List<GeoLine>) = synchronized(geoLock) {
        geoCache.put(key, lines)?.let { old -> geoCacheSize -= old.sumOf { it.points.size.toLong() } }
        geoCacheSize += lines.sumOf { it.points.size.toLong() }
        val it = geoCache.entries.iterator()
        while (geoCacheSize > GEO_BUDGET && geoCache.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            geoCacheSize -= eldest.value.sumOf { l -> l.points.size.toLong() }
            it.remove()
        }
    }

    /** What decides which contour lines a frame has: the spec of its product, and for a wind product the height plane. Null when it has none. */
    private fun contourSpecFor(grid: Grid, values: FloatArray, style: Style, product: Product): Pair<ContourSpec, Int?>? {
        val windLevel = WindProducts.planeLevel(product.id)?.takeIf { WindProducts.hasPlanes(values, grid.cells) }
        val spec = if (windLevel != null) Contours.specFor("z${if (windLevel == WindProducts.STACK) 500 else windLevel}", "m") else Contours.specFor(product.id, style.units)
        return spec?.let { it to windLevel }
    }

    private fun traceGeo(grid: Grid, values: FloatArray, style: Style, spec: ContourSpec, windLevel: Int?): List<GeoLine> {
        val field = if (windLevel != null) WindProducts.plane(values, grid.cells, WindProducts.HEIGHT) else values
        val cstyle = if (windLevel != null) Style() else style
        // the lines are traced on the whole grid (pixels of a one-pixel-per-cell view), then every vertex becomes a latitude / longitude
        val whole = View("globe", 0, 0, grid.columns, grid.rows, 1, 1, grid)
        return Contours.lines(field, grid, whole, cstyle, spec).map { line -> GeoLine(line.level, line.bold, line.label, toLatLon(grid, whole, line)) }
    }

    /** Makes the contour lines of a frame ahead of its turn (a loop prepares the next frame while this one is shown): [frameKey] is the name [render] is given. Not locked: it runs beside a picture. */
    fun warmContours(grid: Grid, values: FloatArray, style: Style, product: Product, frameKey: String) {
        val key = "$frameKey|${product.id}"
        if (cachedGeo(key) != null) return
        val (spec, windLevel) = contourSpecFor(grid, values, style, product) ?: return
        putGeo(key, traceGeo(grid, values, style, spec, windLevel))
    }

    /**
     * Draws the globe. [product] (with [contours] and [barbDensity]) adds what the flat map draws over its data: the field's contour lines, and for the wind
     * products (height and winds) the height lines and barbs, which lie the way the sphere does.
     */
    @Synchronized
    fun render(
        grid: Grid, values: FloatArray, style: Style, members: List<String>, cam: GlobeCamera, width: Int, height: Int, stride: Int, dark: Boolean,
        product: Product? = null, contours: Boolean = false, barbDensity: Int = DEFAULT_BARB_DENSITY, windInTrueFrame: Boolean = false, polar: PolarCamera? = null, alerts: List<AlertDraw> = emptyList(),
        counties: Int = 0, overlays: List<GlobeOverlay> = emptyList(), baseHalo: Boolean = false, frameKey: String? = null,
    ): Bitmap {
        val pictureStart = System.nanoTime()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF070B14.toInt() else 0xFFE8EDF3.toInt())
        val cx = width / 2f
        val cy = height / 2f
        val r = (polar?.radius ?: cam.radius).toFloat()

        // what does not depend on the frame (the sphere and its graticule below the data, the map lines over it) is drawn once per view and kept: a loop at one zoom
        // only draws its data. Not while the view is being dragged (every picture is another view), and not for a big export picture.
        val keep = stride == 1 && width.toLong() * height <= 4_000_000L
        val layerKey = LayerKey(cam, polar, width, height, dark, counties)
        if (keep) {
            if (underKey != layerKey || under == null) {
                under?.recycle()
                under = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawUnder(Canvas(it), cam, polar, width, height, dark, cx, cy, r) }
                underKey = layerKey
            }
            canvas.drawBitmap(under!!, 0f, 0f, null)
        } else drawUnder(canvas, cam, polar, width, height, dark, cx, cy, r)

        // data
        if (buffer.size < width * height) buffer = IntArray(width * height)
        val memberRgb = IntArray(10) { Palettes.memberColors[members.getOrNull(it)] ?: 0x808080 }
        // a member-probability contour product: the members' paintball under lines of the share of members, as the flat map has it
        val paintLines = product != null && PaintballLines.isPaintballLines(product.id)
        val rasterStyle = if (paintLines) Style(Kind.Paintball, units = "members") else style
        val domain = if (dark) 0x66FFFFFF else 0x99FFFFFF.toInt()
        val baseDomain = if (baseHalo) 0 else domain          // an edged swath is only the shapes, not the whole domain
        Timings.timed("raster") {
            if (polar != null) core.renderPolar(grid, values, width, height, polar.radius, polar.lon, polar.edgeLat, polar.north, stride, baseDomain, rasterStyle, memberRgb, buffer)
            else core.renderGlobe(grid, values, width, height, cam.radius, cam.lat, cam.lon, stride, baseDomain, rasterStyle, memberRgb, buffer)
        }
        if (baseHalo) alertPainter.haloPixels(buffer, width, height)
        if (paintLines) Timings.timed("contours") { drawPaintball(canvas, grid, values, members.size, cam, polar, width, height, dark) }
        canvas.drawBitmap(buffer, 0, width, 0, 0, width, height, true, if (paintLines) Paint().apply { alpha = 0x90 } else null)
        for (overlay in overlays) Timings.timed("overlay") { drawOverlay(canvas, overlay, cam, polar, width, height, stride) }

        Timings.timed("lines") {
            if (keep) {
                if (overKey != layerKey || over == null) {
                    over?.recycle()
                    over = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawLines(Canvas(it), cam, polar, width, height, dark, stride, counties) }
                    overKey = layerKey
                }
                canvas.drawBitmap(over!!, 0f, 0f, null)
            } else drawLines(canvas, cam, polar, width, height, dark, stride, counties)
        }
        if (product != null) decorate(canvas, grid, values, style, product, cam, polar, width, height, dark, contours, barbDensity, windInTrueFrame, frameKey)
        if (alerts.isNotEmpty()) Timings.timed("alerts") { alertPainter.drawAlerts(canvas, alerts, width) }       // watches, warnings and outlooks over everything
        Timings.record("picture", (System.nanoTime() - pictureStart) / 1_000_000)

        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, minOf(width, height) / 800f); color = if (dark) 0x66FFFFFF else 0x66000000
        })
        return bitmap
    }

    /** The contour lines and the barbs of [product] on the sphere, drawn by the code the flat map uses. */
    private fun decorate(
        canvas: Canvas, grid: Grid, values: FloatArray, style: Style, product: Product, cam: GlobeCamera, polar: PolarCamera?, width: Int, height: Int, dark: Boolean,
        contours: Boolean, barbDensity: Int, trueFrame: Boolean, frameKey: String? = null,
    ) {
        val cells = grid.cells
        val windLevel = WindProducts.planeLevel(product.id)?.takeIf { WindProducts.hasPlanes(values, cells) }
        if (contours) {
            val found = contourSpecFor(grid, values, style, product)
            if (found != null) {
                val (spec, wind) = found
                val lines: List<GeoLine> = if (frameKey != null) {
                    val key = "$frameKey|${product.id}"
                    cachedGeo(key) ?: Timings.timed("contours") { traceGeo(grid, values, style, spec, wind) }.also { putGeo(key, it) }
                } else {
                    val key = Triple(values, product.id, grid)
                    if (geoKey != key) { Timings.timed("contours") { geoLines = traceGeo(grid, values, style, spec, wind) }; geoKey = key }
                    geoLines
                }
                drawContourLines(canvas, projectedContours(lines) { la, lo -> if (polar != null) polar.project(width, height, la, lo) else globeForward(cam, width, height, la, lo) }, spec.color, dark, width)
            }
        }
        if (windLevel != null) {
            // the globe is crowded sooner than a flat map (the barbs sit over the data and the lines, and thin out toward the limb): one density level down
            val across = barbsAcross((barbDensity - 1).coerceAtLeast(1)) * if (level(windLevel, values, cells)) STACK_DENSITY else 1f
            Timings.timed("barbs") { drawWindBarbs(canvas, core, if (polar != null) PolarProjector(polar, width, height) else GlobeProjector(cam, width, height), grid, values, windLevel, dark, width / across, maxOf(1.2f, width / 1300f), 2f, trueFrame) }
        }
    }

    private fun level(windLevel: Int, values: FloatArray, cells: Int) = windLevel == WindProducts.STACK && WindProducts.isStackFrame(values, cells)

    private fun toLatLon(grid: Grid, whole: View, line: ContourLine): FloatArray {
        val p = line.points
        val out = FloatArray(p.size)
        var i = 0
        while (i + 1 < p.size) {
            val col = p[i] - 0.5 + whole.col0
            val row = whole.row0 + whole.rows - 0.5 - p[i + 1]
            val ll = core.gridToLatLon(grid, col, row)
            out[i] = ll[0].toFloat(); out[i + 1] = ll[1].toFloat()
            i += 2
        }
        return out
    }

    /** The contour lines as the camera sees them, in picture pixels: broken where a line goes over the horizon, a label on the longest piece of each level. */
    private fun projectedContours(lines: List<GeoLine>, project: (Double, Double) -> DoubleArray?): List<ContourLine> {
        val out = ArrayList<ContourLine>()
        for (line in lines) {
            val pieces = ArrayList<FloatArray>()
            var current = ArrayList<Float>()
            var i = 0
            while (i + 1 < line.points.size) {
                val p = project(line.points[i].toDouble(), line.points[i + 1].toDouble())
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
            style = Paint.Style.STROKE; strokeWidth = maxOf(1f, minOf(width, height) / 1100f); color = if (dark) 0x33FFFFFF else 0x33000000
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

    private fun drawPolarGraticule(canvas: Canvas, cam: PolarCamera, width: Int, height: Int, dark: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = maxOf(1f, minOf(width, height) / 1100f); color = if (dark) 0x33FFFFFF else 0x33000000
        }
        fun polyline(points: Sequence<Pair<Double, Double>>) {
            var prev: DoubleArray? = null
            for ((lat, lon) in points) {
                val p = cam.project(width, height, lat, lon)
                if (p != null && prev != null) canvas.drawLine(prev[0].toFloat(), prev[1].toFloat(), p[0].toFloat(), p[1].toFloat(), paint)
                prev = p
            }
        }
        val sign = if (cam.north) 1 else -1
        for (lat in 30..80 step 10) polyline((-180..180 step 3).asSequence().map { (sign * lat).toDouble() to it.toDouble() })
        for (lon in -180 until 180 step 30) polyline(sequenceOf((sign * 89.9) to lon.toDouble(), (sign * cam.edgeLat) to lon.toDouble()))
    }

    private data class LayerKey(val cam: GlobeCamera, val polar: PolarCamera?, val width: Int, val height: Int, val dark: Boolean, val counties: Int)
    private var underKey: LayerKey? = null
    private var under: Bitmap? = null            // the sphere and the graticule
    private var overKey: LayerKey? = null
    private var over: Bitmap? = null             // the map lines (clear where there are none)

    /** The sphere, lit from the upper left, and its graticule. */
    private fun drawUnder(canvas: Canvas, cam: GlobeCamera, polar: PolarCamera?, width: Int, height: Int, dark: Boolean, cx: Float, cy: Float, r: Float) {
        val ocean = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - 0.35f * r, cy - 0.35f * r, r * 1.5f,
                if (dark) intArrayOf(0xFF2B5278.toInt(), 0xFF10263D.toInt(), 0xFF08121F.toInt()) else intArrayOf(0xFFD6E8F7.toInt(), 0xFFA9C8E4.toInt(), 0xFF7FA6CB.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, ocean)
        if (polar != null) drawPolarGraticule(canvas, polar, width, height, dark) else drawGraticule(canvas, cam, width, height, dark)
    }

    private fun drawLines(canvas: Canvas, cam: GlobeCamera, polar: PolarCamera?, width: Int, height: Int, dark: Boolean, stride: Int, counties: Int) {
        if (polar != null) lines.drawPolar(canvas, polar, width, height, dark, detailed = stride == 1, countyMode = counties)
        else lines.drawGlobe(canvas, cam, width, height, dark, detailed = stride == 1, countyMode = counties)
    }

    /** One overlay field over the data: rastered like the base field into its own buffer (clear where it has nothing), then edged or drawn as crosses where it asks. */
    private fun drawOverlay(canvas: Canvas, ov: GlobeOverlay, cam: GlobeCamera, polar: PolarCamera?, width: Int, height: Int, stride: Int) {
        if (layerBuffer.size < width * height) layerBuffer = IntArray(width * height)
        val rgb = IntArray(10)
        if (polar != null) core.renderPolar(ov.grid, ov.values, width, height, polar.radius, polar.lon, polar.edgeLat, polar.north, stride, 0, ov.style, rgb, layerBuffer)
        else core.renderGlobe(ov.grid, ov.values, width, height, cam.radius, cam.lat, cam.lon, stride, 0, ov.style, rgb, layerBuffer)
        if (ov.marker) { drawMarkers(canvas, ov, cam, polar, width, height); return }
        if (ov.halo && stride == 1) alertPainter.haloPixels(layerBuffer, width, height)
        canvas.drawBitmap(layerBuffer, 0, width, 0, 0, width, height, true, null)
    }

    /** A cross for each cell that shows (strikes), in the overlay's color there (or the marker color), on a dark rim: the flat map's look. */
    private fun drawMarkers(canvas: Canvas, ov: GlobeOverlay, cam: GlobeCamera, polar: PolarCamera?, width: Int, height: Int) {
        val g = ov.grid
        val st = ov.style
        val f = if (st.factor == 0.0) 1.0 else st.factor
        val unit = maxOf(1f, width / 900f) * ov.markerScale
        val arm = 3.4f * unit
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f * unit; strokeCap = Paint.Cap.BUTT }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.7f * unit; strokeCap = Paint.Cap.BUTT; color = Color.BLACK }
        var drawn = 0
        for (r in 0 until g.rows) {
            for (c in 0 until g.columns) {
                val raw = ov.values[r * g.columns + c]
                if (raw.isNaN()) continue
                val shown = raw * f + st.offset
                if (shown < st.hideBelow) continue
                val ll = core.gridToLatLon(g, c.toDouble(), r.toDouble())
                val p = (if (polar != null) polar.project(width, height, ll[0], ll[1]) else globeForward(cam, width, height, ll[0], ll[1])) ?: continue
                val x = p[0].toFloat(); val y = p[1].toFloat()
                if (x < -arm || y < -arm || x > width + arm || y > height + arm) continue
                val sampled = if (x >= 0 && y >= 0 && x < width && y < height) layerBuffer[y.toInt() * width + x.toInt()] else 0
                val alpha = if (sampled ushr 24 == 0) 255 else sampled ushr 24
                ink.color = ov.markerColor?.let { (alpha shl 24) or it } ?: if (sampled ushr 24 == 0) Color.WHITE else sampled
                rim.alpha = maxOf(alpha * 2 / 3, 90)
                canvas.drawLine(x - arm, y, x + arm, y, rim); canvas.drawLine(x, y - arm, x, y + arm, rim)
                canvas.drawLine(x - arm, y, x + arm, y, ink); canvas.drawLine(x, y - arm, x, y + arm, ink)
                drawn += 1
                if (drawn >= 20_000) return                     // a storm-wide burst: enough to read
            }
        }
    }

    private var paintKey: Any? = null
    private var paintGeo: List<GeoLine> = emptyList()

    /** Lines of the share of members at or above the threshold: traced once per frame on the whole grid (the neighborhood and blur are the costly part), then projected. */
    private fun drawPaintball(canvas: Canvas, grid: Grid, mask: FloatArray, memberCount: Int, cam: GlobeCamera, polar: PolarCamera?, width: Int, height: Int, dark: Boolean) {
        if (mask.size < grid.cells) return
        val key = Triple(mask, grid, memberCount)
        if (paintKey != key) {
            val share = PaintballLines.blur(PaintballLines.neighborhoodPercent(mask, grid.columns, grid.rows, memberCount.takeIf { it > 0 } ?: 10, PaintballLines.NEIGHBORHOOD), grid.columns, grid.rows, PaintballLines.BLUR_RADIUS)
            val whole = View("globe", 0, 0, grid.columns, grid.rows, 1, 1, grid)
            val spec = ContourSpec(10.0, boldEvery = 50.0, labelSuffix = "%")
            paintGeo = Contours.lines(share, grid, whole, Style(), spec, smoothing = 1, levelsOverride = PaintballLines.levels).map { GeoLine(it.level, it.bold, it.label, toLatLon(grid, whole, it)) }
            paintKey = key
        }
        drawPaintballContourLines(canvas, projectedContours(paintGeo) { la, lo -> if (polar != null) polar.project(width, height, la, lo) else globeForward(cam, width, height, la, lo) }, dark, width)
    }
}
