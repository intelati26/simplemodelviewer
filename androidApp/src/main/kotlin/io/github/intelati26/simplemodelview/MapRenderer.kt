package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import simplemodelview.Core
import simplemodelview.Kind
import simplemodelview.Palettes
import simplemodelview.Product
import simplemodelview.Stop
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.fixed

/** Draws one finished map picture: background, data (native rasteriser), lines. */
class MapRenderer(private val core: Core, private val lines: MapLines) {
    fun render(product: Product, style: Style, values: FloatArray, memberNames: List<String>, view: View, dark: Boolean): Bitmap {
        val w = view.pixelWidth
        val h = view.pixelHeight
        val argb = IntArray(w * h)
        val memberRgb = IntArray(10) { Palettes.memberColours[memberNames.getOrNull(it)] ?: 0x808080 }
        core.render(values, view, style, memberRgb, argb)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF202428.toInt() else 0xFFEDEFF2.toInt())
        canvas.drawBitmap(Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888), 0f, 0f, null)
        lines.draw(canvas, view, dark)
        return bitmap
    }

    /** The picture to share: a header (product / run / valid time), the map and a colour key. */
    fun shareImage(map: Bitmap, product: Product, style: Style, header: String, memberNames: List<String>): Bitmap {
        val pad = maxOf(12, map.width / 90)
        val titleSize = maxOf(18f, map.width / 48f)
        val legendHeight = (titleSize * 2.6f).toInt()
        val headerHeight = (titleSize * 3.2f).toInt()
        val out = Bitmap.createBitmap(map.width, headerHeight + map.height + legendHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val text = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = titleSize; isFakeBoldText = true }
        canvas.drawText(product.title, pad.toFloat(), titleSize * 1.3f, text)
        text.isFakeBoldText = false
        text.textSize = titleSize * 0.8f
        canvas.drawText(header, pad.toFloat(), titleSize * 2.4f, text)
        canvas.drawBitmap(map, 0f, headerHeight.toFloat(), null)
        drawLegend(canvas, style, product, memberNames, pad.toFloat(), (headerHeight + map.height).toFloat(), map.width - 2f * pad, legendHeight.toFloat(), titleSize * 0.7f)
        return out
    }

    private fun drawLegend(canvas: Canvas, style: Style, product: Product, members: List<String>, x: Float, y: Float, w: Float, h: Float, textSize: Float) {
        val paint = Paint().apply { isAntiAlias = true; this.textSize = textSize; color = Color.BLACK }
        if (style.kind == Kind.Paintball) {
            val cell = w / 10f
            for (i in 0 until 10) {
                paint.color = 0xFF000000.toInt() or (Palettes.memberColours[members.getOrNull(i)] ?: 0x808080)
                canvas.drawRect(x + i * cell, y + h * 0.15f, x + (i + 1) * cell, y + h * 0.45f, paint)
                paint.color = Color.BLACK
                canvas.drawText(Palettes.memberDisplayName(members.getOrNull(i) ?: ""), x + i * cell, y + h * 0.85f, paint)
            }
            return
        }
        val stops = style.stops
        if (stops.isEmpty()) return
        if (style.kind == Kind.Categorical) {      // swatches with the category names, two lines if needed
            val per = (stops.size + 1) / 2
            val cellW = w / per
            for ((i, stop) in stops.withIndex()) {
                val row = i / per
                val col = i % per
                val top = y + h * (0.1f + 0.45f * row)
                paint.color = 0xFF000000.toInt() or stop.rgb
                canvas.drawRect(x + col * cellW, top, x + col * cellW + textSize, top + textSize, paint)
                paint.color = Color.BLACK
                canvas.drawText(style.labels.getOrElse(i) { "" }, x + col * cellW + textSize * 1.3f, top + textSize * 0.9f, paint)
            }
            return
        }
        val cell = w / stops.size
        for ((i, stop) in stops.withIndex()) {
            paint.color = 0xFF000000.toInt() or stop.rgb
            canvas.drawRect(x + i * cell, y + h * 0.15f, x + (i + 1) * cell, y + h * 0.45f, paint)
        }
        paint.color = Color.BLACK
        val every = maxOf(1, (stops.size * textSize * 3.2f / w).toInt() + 1)
        for ((i, stop) in stops.withIndex()) {
            if (i % every == 0) canvas.drawText(label(stop, style), x + i * cell, y + h * 0.85f, paint)
        }
        canvas.drawText(style.units, x + w - textSize * (style.units.length + 1), y + h * 0.15f - 2f, paint)
    }

    companion object {
        fun label(stop: Stop, style: Style): String {
            val v = stop.value
            return if (v == v.toLong().toDouble()) v.toLong().toString() else fixed(v, if (kotlin.math.abs(v) < 1) 2 else 1)
        }
    }
}
