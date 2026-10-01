package org.spcrefs.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import spcrefs.Core
import spcrefs.View
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * State, county, Canada and Mexico lines, bundled as little-endian float32 segments (lat, west-positive lon, lat, west-positive
 * lon). Each vertex is projected to grid coordinates once; drawing a view is then just a scale and offset.
 */
class MapLines(context: Context, core: Core) {
    // cells: col, row, col, row per segment; trig: sin(lat), cos(lat), sin(lon), cos(lon) for both vertices (the globe needs no trig per frame)
    private class Layer(val cells: FloatArray, val trig: FloatArray)

    private val layers: Map<String, Layer> = listOf("statev2", "county", "ca", "mx").associateWith { name ->
        val bytes = context.assets.open("$name.f32").use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = floats.remaining() / 4 * 4
        val cells = FloatArray(n)
        val trig = FloatArray(n * 2)
        var i = 0
        while (i < n) {
            for (k in 0..1) {
                val lat = Math.toRadians(floats.get(i + 2 * k).toDouble())
                val lon = Math.toRadians(-floats.get(i + 2 * k + 1).toDouble())
                val t = i * 2 + k * 4
                trig[t] = Math.sin(lat).toFloat(); trig[t + 1] = Math.cos(lat).toFloat()
                trig[t + 2] = Math.sin(lon).toFloat(); trig[t + 3] = Math.cos(lon).toFloat()
            }
            val a = core.latLonToGrid(floats.get(i).toDouble(), -floats.get(i + 1).toDouble())
            val b = core.latLonToGrid(floats.get(i + 2).toDouble(), -floats.get(i + 3).toDouble())
            cells[i] = a[0].toFloat(); cells[i + 1] = a[1].toFloat(); cells[i + 2] = b[0].toFloat(); cells[i + 3] = b[1].toFloat()
            i += 4
        }
        Layer(cells, trig)
    }

    /** Draws the lines of [view] on a canvas whose size is view.pixelWidth x view.pixelHeight. */
    fun draw(canvas: Canvas, view: View, dark: Boolean) {
        val s = view.scale.toFloat()
        val widthUnit = if (view.scale > 1) 1.0f else 0.8f
        // counties only when zoomed to a sector: at CONUS scale they are noise
        val plan = listOf(
            Triple("mx", if (dark) 0xFF8A8A8A.toInt() else 0xFF5A5A5A.toInt(), 1.0f * widthUnit),
            Triple("ca", if (dark) 0xFF8A8A8A.toInt() else 0xFF5A5A5A.toInt(), 1.0f * widthUnit),
        ) + (if (view.scale > 1) listOf(Triple("county", if (dark) 0x66AAAAAA else 0x66606060, 0.6f * widthUnit)) else emptyList()) +
            listOf(Triple("statev2", if (dark) 0xFFD0D0D0.toInt() else 0xFF323232.toInt(), 1.3f * widthUnit))
        val paint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
        val w = view.pixelWidth.toFloat()
        val h = view.pixelHeight.toFloat()
        for ((name, colour, width) in plan) {
            val cells = layers.getValue(name).cells
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
            paint.color = colour
            paint.strokeWidth = width * maxOf(1f, s / 2f)
            canvas.drawLines(pts, 0, count, paint)
        }
    }

    /** Draws the lines on an orthographic globe (see spcrefs.GlobeCamera). Only segments with both ends on the near side are drawn. */
    fun drawGlobe(canvas: Canvas, cam: spcrefs.GlobeCamera, width: Int, height: Int, dark: Boolean, detailed: Boolean) {
        val phi0 = Math.toRadians(cam.lat)
        val sin0 = Math.sin(phi0).toFloat(); val cos0 = Math.cos(phi0).toFloat()
        val lam0 = Math.toRadians(cam.lon)
        val sinL0 = Math.sin(lam0).toFloat(); val cosL0 = Math.cos(lam0).toFloat()
        val r = cam.radius.toFloat()
        val cx = width / 2f; val cy = height / 2f
        val zoomedIn = r > width * 1.4f
        val grey = if (dark) 0xFF9AA4B0.toInt() else 0xFF4A5560.toInt()
        val plan = listOf(Triple("mx", grey, 0.8f), Triple("ca", grey, 0.8f)) +
            (if (zoomedIn && detailed) listOf(Triple("county", if (dark) 0x55AAAAAA else 0x55606060, 0.5f)) else emptyList()) +
            listOf(Triple("statev2", if (dark) 0xFFE0E4E8.toInt() else 0xFF2A3036.toInt(), 1.1f))
        val paint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = detailed }
        for ((name, colour, width1) in plan) {
            val trig = layers.getValue(name).trig
            val pts = FloatArray(trig.size / 2)
            var count = 0
            var i = 0
            while (i < trig.size) {
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
                pts[count] = x1; pts[count + 1] = y1; pts[count + 2] = x2; pts[count + 3] = y2
                count += 4
            }
            paint.color = colour
            paint.strokeWidth = width1 * (if (zoomedIn) 1.5f else 1f)
            canvas.drawLines(pts, 0, count, paint)
        }
    }
}
