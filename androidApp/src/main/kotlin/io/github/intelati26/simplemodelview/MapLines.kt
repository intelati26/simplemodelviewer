package io.github.intelati26.simplemodelview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import simplemodelview.Core
import simplemodelview.Grid
import simplemodelview.View
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * State, county, Canada and Mexico lines, bundled as little-endian float32 segments (lat, west-positive lon, lat, west-positive
 * lon). Each vertex is projected to grid coordinates once; drawing a view is then just a scale and offset.
 */
class MapLines(context: Context, private val core: Core) {
    private companion object { const val CHUNK = 64 }
    // raw: lat, west-positive lon, lat, west-positive lon per segment; trig: sin(lat), cos(lat), sin(lon), cos(lon) for both vertices
    // (the globe needs no trig per frame). Grid cells (col, row, col, row) are projected per grid on first use.
    private class Layer(val raw: FloatArray, val trig: FloatArray) {
        // a globe frame skips whole runs of segments that cannot show: [CHUNK] consecutive segments (a stretch of a line) have a center (unit vector) and an angular radius
        val chunks = (trig.size / 8 + CHUNK - 1) / CHUNK
        val center = FloatArray(chunks * 3)
        val radius = FloatArray(chunks)
        init {
            val segments = trig.size / 8
            for (k in 0 until chunks) {
                val from = k * CHUNK; val to = minOf(segments, from + CHUNK)
                var x = 0.0; var y = 0.0; var z = 0.0
                for (sgm in from until to) for (v in 0..1) {
                    val t = sgm * 8 + v * 4
                    x += trig[t + 1] * trig[t + 3]; y += trig[t + 1] * trig[t + 2]; z += trig[t]
                }
                val len = Math.sqrt(x * x + y * y + z * z).let { if (it < 1e-9) 1.0 else it }
                x /= len; y /= len; z /= len
                var widest = 0.0
                for (sgm in from until to) for (v in 0..1) {
                    val t = sgm * 8 + v * 4
                    val dot = trig[t + 1] * trig[t + 3] * x + trig[t + 1] * trig[t + 2] * y + trig[t] * z
                    widest = maxOf(widest, Math.acos(dot.coerceIn(-1.0, 1.0)))
                }
                center[k * 3] = x.toFloat(); center[k * 3 + 1] = y.toFloat(); center[k * 3 + 2] = z.toFloat(); radius[k] = widest.toFloat()
            }
        }

        // projected lines per grid; MRMS windows make many grids, so only the few most recent are kept
        val cells = object : LinkedHashMap<Grid, FloatArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Grid, FloatArray>) = size > 6
        }
    }

    private val layers: Map<String, Layer> = listOf("statev2", "county", "ca", "mx").associateWith { name ->
        val bytes = context.assets.open("$name.f32").use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = floats.remaining() / 4 * 4
        val raw = FloatArray(n).also { floats.get(it, 0, n) }
        val trig = FloatArray(n * 2)
        var i = 0
        while (i < n) {
            for (k in 0..1) {
                val lat = Math.toRadians(raw[i + 2 * k].toDouble())
                val lon = Math.toRadians(-raw[i + 2 * k + 1].toDouble())
                val t = i * 2 + k * 4
                trig[t] = Math.sin(lat).toFloat(); trig[t + 1] = Math.cos(lat).toFloat()
                trig[t + 2] = Math.sin(lon).toFloat(); trig[t + 3] = Math.cos(lon).toFloat()
            }
            i += 4
        }
        Layer(raw, trig)
    }

    private fun cellsFor(layer: Layer, grid: Grid): FloatArray = synchronized(layer) {
        layer.cells.getOrPut(grid) {
            val raw = layer.raw
            val cells = FloatArray(raw.size)
            var i = 0
            while (i < raw.size) {
                val a = core.latLonToGrid(grid, raw[i].toDouble(), -raw[i + 1].toDouble())
                val b = core.latLonToGrid(grid, raw[i + 2].toDouble(), -raw[i + 3].toDouble())
                cells[i] = a[0].toFloat(); cells[i + 1] = a[1].toFloat(); cells[i + 2] = b[0].toFloat(); cells[i + 3] = b[1].toFloat()
                i += 4
            }
            cells
        }
    }

    /** Draws the lines of [view] on a canvas whose size is view.pixelWidth x view.pixelHeight. */
    /** [countyMode]: 0 counties when zoomed to a sector, 1 always, 2 never. */
    fun draw(canvas: Canvas, view: View, dark: Boolean, countyMode: Int = 0) {
        val s = view.scale.toFloat() / view.step      // (a view that draws every n-th cell, the data saver, is n times smaller)
        val widthUnit = if (view.scale > 1) 1.0f else 0.8f
        val window = view.grid.isWindow   // an MRMS window: 1-3 px per cell
        val lineScale = if (view.grid == Grid.REFS) maxOf(1f, s / 2f) else if (window) maxOf(1f, s / 2f) else 1.2f   // (the coarse GREFS grid is drawn at 8-16 px per cell: keep lines thin)
        // counties only when zoomed to a sector: at whole-map scale they are noise
        val auto = if (view.grid == Grid.REFS) view.scale > 1 else if (window) view.cols * view.step <= 2000 else view.scale >= 16
        val counties = when (countyMode) { 1 -> true; 2 -> false; else -> auto }
        val plan = listOf(
            Triple("mx", if (dark) 0xFF8A8A8A.toInt() else 0xFF5A5A5A.toInt(), 1.0f * widthUnit),
            Triple("ca", if (dark) 0xFF8A8A8A.toInt() else 0xFF5A5A5A.toInt(), 1.0f * widthUnit),
        ) + (if (counties) listOf(Triple("county", if (dark) 0x50AAAAAA else 0x50606060, 0.5f * widthUnit)) else emptyList()) +
            listOf(Triple("statev2", if (dark) 0xFFD0D0D0.toInt() else 0xFF323232.toInt(), 1.3f * widthUnit))
        val paint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
        val w = view.pixelWidth.toFloat()
        val h = view.pixelHeight.toFloat()
        for ((name, color, width) in plan) {
            val cells = cellsFor(layers.getValue(name), view.grid)
            val pts = FloatArray(cells.size)
            var count = 0
            var i = 0
            while (i < cells.size) {
                val x1 = (cells[i] - view.col0 + 0.5f) * s - 0.5f
                val y1 = (view.row0 + view.rows - cells[i + 1] - 0.5f) * s - 0.5f
                val x2 = (cells[i + 2] - view.col0 + 0.5f) * s - 0.5f
                val y2 = (view.row0 + view.rows - cells[i + 3] - 0.5f) * s - 0.5f
                if (!((x1 < 0 && x2 < 0) || (x1 > w && x2 > w) || (y1 < 0 && y2 < 0) || (y1 > h && y2 > h))) {
                    pts[count] = x1; pts[count + 1] = y1; pts[count + 2] = x2; pts[count + 3] = y2
                    count += 4
                }
                i += 4
            }
            paint.color = color
            paint.strokeWidth = width * lineScale
            canvas.drawLines(pts, 0, count, paint)
        }
    }

    /** Draws the lines on an orthographic globe (see simplemodelview.GlobeCamera). Only segments with both ends on the near side are drawn. */
    /** [countyMode]: 0 counties when zoomed in, 1 always, 2 never (as on the flat map). */
    fun drawGlobe(canvas: Canvas, cam: simplemodelview.GlobeCamera, width: Int, height: Int, dark: Boolean, detailed: Boolean, countyMode: Int = 0) {
        val phi0 = Math.toRadians(cam.lat)
        val sin0 = Math.sin(phi0).toFloat(); val cos0 = Math.cos(phi0).toFloat()
        val lam0 = Math.toRadians(cam.lon)
        val sinL0 = Math.sin(lam0).toFloat(); val cosL0 = Math.cos(lam0).toFloat()
        val r = cam.radius.toFloat()
        val cx = width / 2f; val cy = height / 2f
        val zoomedIn = r > width * 1.4f
        val gray = if (dark) 0xFF9AA4B0.toInt() else 0xFF4A5560.toInt()
        val plan = listOf(Triple("mx", gray, 0.8f), Triple("ca", gray, 0.8f)) +
            (if (detailed && when (countyMode) { 1 -> true; 2 -> false; else -> zoomedIn }) listOf(Triple("county", if (dark) 0x50AAAAAA else 0x50606060, 0.45f)) else emptyList()) +
            listOf(Triple("statev2", if (dark) 0xFFE0E4E8.toInt() else 0xFF2A3036.toInt(), 1.1f))
        val paint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = detailed }
        // the part of the sphere that can show: a cap around the camera's point, out to the horizon or, zoomed in, to the screen's far corner
        val cap = Math.min(Math.PI / 2, Math.asin(Math.min(1.0, Math.hypot(cx.toDouble(), cy.toDouble()) / r))) + 0.03
        val camX = (cos0 * cosL0).toDouble(); val camY = (cos0 * sinL0).toDouble(); val camZ = sin0.toDouble()
        for ((name, color, width1) in plan) {
            val layer = layers.getValue(name)
            val trig = layer.trig
            val alive = BooleanArray(layer.chunks)
            var segments = 0
            for (k in 0 until layer.chunks) {
                val dot = camX * layer.center[k * 3] + camY * layer.center[k * 3 + 1] + camZ * layer.center[k * 3 + 2]
                if (Math.acos(dot.coerceIn(-1.0, 1.0)) - layer.radius[k] <= cap) { alive[k] = true; segments += minOf(CHUNK, trig.size / 8 - k * CHUNK) }
            }
            val pts = FloatArray(segments * 4)
            var count = 0
            // while the globe is being dragged, runs of segments shorter than a couple of pixels (most of a map's lines at this scale) are joined into one: the same line, far fewer to draw
            val minLength = if (detailed) 0f else 2.2f * maxOf(1f, minOf(width, height) / 1100f)
            var pending = false
            var sx = 0f; var sy = 0f; var ex = 0f; var ey = 0f
            fun flush() { if (pending) { pts[count] = sx; pts[count + 1] = sy; pts[count + 2] = ex; pts[count + 3] = ey; count += 4; pending = false } }
            for (k in 0 until layer.chunks) {
              if (!alive[k]) continue
              var i = k * CHUNK * 8
              val end = minOf(trig.size, i + CHUNK * 8)
              while (i < end) {
                // vertex a, vertex b: sinLat, cosLat, sinLon, cosLon
                val sa = trig[i]; val ca = trig[i + 1]; val sla = trig[i + 2]; val cla = trig[i + 3]
                val sb = trig[i + 4]; val cb = trig[i + 5]; val slb = trig[i + 6]; val clb = trig[i + 7]
                i += 8
                val cosDa = cla * cosL0 + sla * sinL0            // cos(lon - lon0)
                val cosCa = sin0 * sa + cos0 * ca * cosDa
                if (cosCa <= 0f) continue
                val cosDb = clb * cosL0 + slb * sinL0
                val cosCb = sin0 * sb + cos0 * cb * cosDb
                if (cosCb <= 0f) continue
                val sinDa = sla * cosL0 - cla * sinL0
                val sinDb = slb * cosL0 - clb * sinL0
                val x1 = cx + r * ca * sinDa; val y1 = cy - r * (cos0 * sa - sin0 * ca * cosDa)
                val x2 = cx + r * cb * sinDb; val y2 = cy - r * (cos0 * sb - sin0 * cb * cosDb)
                if ((x1 < 0 && x2 < 0) || (x1 > width && x2 > width) || (y1 < 0 && y2 < 0) || (y1 > height && y2 > height)) continue
                if (minLength > 0f) {
                    if (pending && Math.abs(x1 - ex) < 0.01f && Math.abs(y1 - ey) < 0.01f) {
                        ex = x2; ey = y2                                     // the line goes on: extend the pending piece
                    } else { flush(); sx = x1; sy = y1; ex = x2; ey = y2; pending = true }
                    if (Math.abs(ex - sx) + Math.abs(ey - sy) >= minLength) flush()
                } else {
                    pts[count] = x1; pts[count + 1] = y1; pts[count + 2] = x2; pts[count + 3] = y2
                    count += 4
                }
              }
            }
            flush()
            paint.color = color
            paint.strokeWidth = width1 * (if (zoomedIn) 1.5f else 1f) * maxOf(1f, minOf(width, height) / 1100f)
            canvas.drawLines(pts, 0, count, paint)
        }
    }

    /** Draws the lines on a polar stereographic map (see simplemodelview.PolarCamera). Only segments with both ends inside the circle are drawn. */
    fun drawPolar(canvas: Canvas, cam: simplemodelview.PolarCamera, width: Int, height: Int, dark: Boolean, detailed: Boolean, countyMode: Int = 0) {
        val lam0 = Math.toRadians(cam.lon)
        val sinL0 = Math.sin(lam0).toFloat(); val cosL0 = Math.cos(lam0).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val r = cam.radius.toFloat()
        val tanEdge = Math.tan(Math.toRadians(90.0 - cam.edgeLat) / 2).toFloat()
        val sign = if (cam.north) 1f else -1f
        val zoomedIn = r > Math.min(width, height) * 1.2f
        val gray = if (dark) 0xFF9AA4B0.toInt() else 0xFF4A5560.toInt()
        val plan = listOf(Triple("mx", gray, 0.8f), Triple("ca", gray, 0.8f)) +
            (if (detailed && when (countyMode) { 1 -> true; 2 -> false; else -> zoomedIn }) listOf(Triple("county", if (dark) 0x50AAAAAA else 0x50606060, 0.45f)) else emptyList()) +
            listOf(Triple("statev2", if (dark) 0xFFE0E4E8.toInt() else 0xFF2A3036.toInt(), 1.1f))
        val paint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = detailed }
        for ((name, color, width1) in plan) {
            val trig = layers.getValue(name).trig
            val pts = FloatArray(trig.size / 2)
            var count = 0
            var i = 0
            while (i < trig.size) {
                val sa = trig[i]; val ca = trig[i + 1]; val sla = trig[i + 2]; val cla = trig[i + 3]
                val sb = trig[i + 4]; val cb = trig[i + 5]; val slb = trig[i + 6]; val clb = trig[i + 7]
                i += 8
                // tan(colatitude / 2) = cos(lat) / (1 +- sin(lat)); the far hemisphere has no map
                val da = 1f + sign * sa; val db = 1f + sign * sb
                if (da < 1e-4f || db < 1e-4f) continue
                val ta = ca / da; val tb = cb / db
                if (ta > tanEdge || tb > tanEdge) continue
                val ra = r * ta / tanEdge; val rb = r * tb / tanEdge
                val sinDa = sla * cosL0 - cla * sinL0; val cosDa = cla * cosL0 + sla * sinL0
                val sinDb = slb * cosL0 - clb * sinL0; val cosDb = clb * cosL0 + slb * sinL0
                val x1 = cx + ra * sinDa; val y1 = cy + sign * ra * cosDa
                val x2 = cx + rb * sinDb; val y2 = cy + sign * rb * cosDb
                if ((x1 < 0 && x2 < 0) || (x1 > width && x2 > width) || (y1 < 0 && y2 < 0) || (y1 > height && y2 > height)) continue
                pts[count] = x1; pts[count + 1] = y1; pts[count + 2] = x2; pts[count + 3] = y2
                count += 4
            }
            paint.color = color
            paint.strokeWidth = width1 * (if (zoomedIn) 1.5f else 1f) * maxOf(1f, minOf(width, height) / 1100f)
            canvas.drawLines(pts, 0, count, paint)
        }
    }
}
