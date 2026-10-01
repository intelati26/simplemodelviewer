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
    private class Layer(val cells: FloatArray)   // col, row, col, row per segment

    private val layers: Map<String, Layer> = listOf("statev2", "county", "ca", "mx").associateWith { name ->
        val bytes = context.assets.open("$name.f32").use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = floats.remaining() / 4 * 4
        val cells = FloatArray(n)
        var i = 0
        while (i < n) {
            val a = core.latLonToGrid(floats.get(i).toDouble(), -floats.get(i + 1).toDouble())
            val b = core.latLonToGrid(floats.get(i + 2).toDouble(), -floats.get(i + 3).toDouble())
            cells[i] = a[0].toFloat(); cells[i + 1] = a[1].toFloat(); cells[i + 2] = b[0].toFloat(); cells[i + 3] = b[1].toFloat()
            i += 4
        }
        Layer(cells)
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
}
