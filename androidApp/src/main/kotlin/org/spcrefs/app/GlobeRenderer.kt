package org.spcrefs.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import spcrefs.Core
import spcrefs.GlobeCamera
import spcrefs.Palettes
import spcrefs.Product
import spcrefs.Style
import spcrefs.globeForward

/** Draws the globe picture: space, a shaded sphere, a graticule, the data wrapped onto it and the border lines. */
class GlobeRenderer(private val core: Core, private val lines: MapLines) {
    private var buffer = IntArray(0)

    @Synchronized
    fun render(values: FloatArray, style: Style, members: List<String>, cam: GlobeCamera, width: Int, height: Int, stride: Int, dark: Boolean): Bitmap {
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
        val memberRgb = IntArray(10) { Palettes.memberColours[members.getOrNull(it)] ?: 0x808080 }
        val domain = if (dark) 0x66FFFFFF else 0x99FFFFFF.toInt()
        core.renderGlobe(values, width, height, cam.radius, cam.lat, cam.lon, stride, domain, style, memberRgb, buffer)
        canvas.drawBitmap(buffer, 0, width, 0, 0, width, height, true, null)

        lines.drawGlobe(canvas, cam, width, height, dark, detailed = stride == 1)

        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 1.5f; color = if (dark) 0x66FFFFFF else 0x66000000
        })
        return bitmap
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
