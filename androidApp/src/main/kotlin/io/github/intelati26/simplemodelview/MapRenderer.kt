package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import simplemodelview.ContourLine
import simplemodelview.Contours
import simplemodelview.Core
import simplemodelview.Kind
import simplemodelview.Palettes
import simplemodelview.Product
import simplemodelview.Stop
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.fixed

/** A second field drawn over the data (under the map lines): [bitmap] has one pixel per cell of its own window and is stretched onto [dst], in pixels of the base picture. */
class Overlay(
    val bitmap: Bitmap, val dst: android.graphics.RectF, val bold: Boolean, val marker: Boolean = false, val markerScale: Float = 1f,
    /** Edge the shapes in black with a white halo outside (hail swaths, rotation tracks). */
    val halo: Boolean = false,
)

/**
 * An alert outline in pixels of the base picture: [rings] as x0, y0, x1, y1, ... A watch or warning is filled faintly; a discussion is dashed.
 * [open]: the rings are lines, not outlines (LightningCast contours): not closed, not filled, a little thinner.
 * [combined]: the rings are one polygon (the outer ring, then its holes), filled together by the even-odd rule so a hole stays empty (SPC outlooks).
 */
class AlertDraw(val rings: List<FloatArray>, val color: Int, val dashed: Boolean, val fill: Boolean, val combined: Boolean = false, val open: Boolean = false)

/** Draws one finished map picture: background, data (native rasteriser), an optional overlay, lines. */
class MapRenderer(private val core: Core, private val lines: MapLines) {
    fun render(product: Product, style: Style, values: FloatArray, memberNames: List<String>, view: View, dark: Boolean, overlays: List<Overlay> = emptyList(), alerts: List<AlertDraw> = emptyList(), contours: Boolean = false, halo: Boolean = false): Bitmap {
        val w = view.pixelWidth
        val h = view.pixelHeight
        val argb = IntArray(w * h)
        val memberRgb = IntArray(10) { Palettes.memberColors[memberNames.getOrNull(it)] ?: 0x808080 }
        core.render(values, view, style, memberRgb, argb)
        val picture = if (halo) outline(argb, w, h, edgeFor(w)) else argb
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF202428.toInt() else 0xFFEDEFF2.toInt())
        canvas.drawBitmap(Bitmap.createBitmap(picture, w, h, Bitmap.Config.ARGB_8888), 0f, 0f, null)
        for (overlay in overlays) {
            if (overlay.marker) { drawMarkers(canvas, overlay, w); continue }
            if (overlay.halo) { drawHaloed(canvas, overlay, w, h); continue }
            val paint = Paint().apply { isFilterBitmap = false }       // cells stay crisp
            canvas.drawBitmap(overlay.bitmap, null, overlay.dst, paint)
            if (overlay.bold) for ((dx, dy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) {   // a pixel of weight for single cells (a strike, a thin track)
                val r = android.graphics.RectF(overlay.dst); r.offset(dx, dy)
                canvas.drawBitmap(overlay.bitmap, null, r, paint)
            }
        }
        lines.draw(canvas, view, dark)
        if (contours) Contours.specFor(product.id, style.units)?.let { spec -> drawContours(canvas, Contours.lines(values, product.grid, view, style, spec), spec.color, dark, w) }
        if (alerts.isNotEmpty()) drawAlerts(canvas, alerts, w)
        return bitmap
    }

    /** Contour lines over the data and the map lines: a halo under each line so it reads on any color, heavier for the bold levels, one label per level. */
    private fun drawContours(canvas: Canvas, contours: List<ContourLine>, color: Int, dark: Boolean, pictureWidth: Int) {
        val width = maxOf(1.5f, pictureWidth / 900f)
        val ink = if (dark) 0xFFF0F0F0.toInt() else 0xFF000000.toInt() or color
        val halo = if (dark) 0xAA000000.toInt() else 0xCCFFFFFF.toInt()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = maxOf(11f, pictureWidth / 90f); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val labels = ArrayList<ContourLine>()
        for (c in contours) {
            val path = android.graphics.Path()
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

    /** Alert outlines over everything else: a dark casing under a colored line so they read on any background. */
    private fun drawAlerts(canvas: Canvas, alerts: List<AlertDraw>, pictureWidth: Int) {
        val width = maxOf(2f, pictureWidth / 600f)
        val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width + 2f; color = 0x99000000.toInt(); strokeJoin = Paint.Join.ROUND }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width; strokeJoin = Paint.Join.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (a in alerts) {
            line.strokeWidth = if (a.open) width * 0.75f else width
            casing.strokeWidth = line.strokeWidth + 2f
            line.color = 0xFF000000.toInt() or a.color
            line.pathEffect = if (a.dashed) android.graphics.DashPathEffect(floatArrayOf(width * 4f, width * 3f), 0f) else null
            casing.pathEffect = line.pathEffect
            fill.color = (0x28 shl 24) or a.color
            if (a.combined && a.fill) {
                val whole = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
                for (ring in a.rings) {
                    if (ring.size < 6) continue
                    whole.moveTo(ring[0], ring[1])
                    var i = 2
                    while (i + 1 < ring.size) { whole.lineTo(ring[i], ring[i + 1]); i += 2 }
                    whole.close()
                }
                canvas.drawPath(whole, fill)
            }
            for (ring in a.rings) {
                if (ring.size < 6) continue
                val path = android.graphics.Path()
                path.moveTo(ring[0], ring[1])
                var i = 2
                while (i + 1 < ring.size) { path.lineTo(ring[i], ring[i + 1]); i += 2 }
                if (!a.open) path.close()
                if (a.fill && !a.combined) canvas.drawPath(path, fill)
                canvas.drawPath(path, casing)
                canvas.drawPath(path, line)
            }
        }
    }

    /**
     * One cross per non-empty cell (strikes; a cell is far too small to see at map scale), as wxqt draws them: a colored "+" on a dark one so it reads on any
     * color, a little larger than wxqt's. It keeps the cell's color and opacity; an older, larger window (markerScale) gets a larger cross.
     */
    private fun drawMarkers(canvas: Canvas, overlay: Overlay, pictureWidth: Int) {
        val bw = overlay.bitmap.width
        val bh = overlay.bitmap.height
        val pixels = IntArray(bw * bh)
        overlay.bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        val unit = maxOf(1f, pictureWidth / 900f) * overlay.markerScale
        val arm = 3.4f * unit
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f * unit; strokeCap = Paint.Cap.BUTT }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.7f * unit; strokeCap = Paint.Cap.BUTT; color = Color.BLACK }
        val cw = overlay.dst.width() / bw
        val ch = overlay.dst.height() / bh
        for (i in pixels.indices) {
            val c = pixels[i]
            val alpha = c ushr 24
            if (alpha == 0) continue
            val x = overlay.dst.left + (i % bw + 0.5f) * cw
            val y = overlay.dst.top + (i / bw + 0.5f) * ch
            ink.color = c
            rim.alpha = maxOf(alpha * 2 / 3, 90)         // (even a 20 % shadow gets a visible rim)
            canvas.drawLine(x - arm, y, x + arm, y, rim); canvas.drawLine(x, y - arm, x, y + arm, rim)
            canvas.drawLine(x - arm, y, x + arm, y, ink); canvas.drawLine(x, y - arm, x, y + arm, ink)
        }
    }

    /** Edge width in picture pixels: 1 on a small picture, 2 on a large one. */
    private fun edgeFor(pictureWidth: Int) = maxOf(1, pictureWidth / 1200)

    /** The overlay drawn at picture resolution, its shapes edged in black and haloed in white (see [outline]). */
    private fun drawHaloed(canvas: Canvas, overlay: Overlay, w: Int, h: Int) {
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(layer).drawBitmap(overlay.bitmap, null, overlay.dst, Paint().apply { isFilterBitmap = false })
        val pixels = IntArray(w * h)
        layer.getPixels(pixels, 0, w, 0, 0, w, h)
        layer.setPixels(outline(pixels, w, h, edgeFor(w)), 0, w, 0, 0, w, h)
        canvas.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
    }

    /**
     * wxqt's swath style: the shapes (pixels with any opacity) edged in black, `edge` pixels deep along the border, and a white halo (92 % opaque) on the clear
     * pixels within `edge` pixels of a shape, so a hail swath or a rotation track reads whatever color is under it.
     */
    private fun outline(src: IntArray, w: Int, h: Int, edge: Int): IntArray {
        val out = src.copyOf()
        // the clear pixels near a shape: a square dilation of the shapes, one pass along the rows and one along the columns
        val along = BooleanArray(w * h)
        for (j in 0 until h) {
            var i = 0
            while (i < w) {
                if ((src[j * w + i] ushr 24) != 0) for (k in maxOf(0, i - edge)..minOf(w - 1, i + edge)) along[j * w + k] = true
                i += 1
            }
        }
        for (j in 0 until h) for (i in 0 until w) {
            val k = j * w + i
            if ((src[k] ushr 24) == 0) {
                var near = false
                var jj = maxOf(0, j - edge)
                while (jj <= minOf(h - 1, j + edge) && !near) { near = along[jj * w + i]; jj += 1 }
                if (near) out[k] = HALO
                continue
            }
            var border = false
            var d = 1
            while (d <= edge && !border) {
                border = i - d < 0 || i + d >= w || j - d < 0 || j + d >= h ||
                    (src[k - d] ushr 24) == 0 || (src[k + d] ushr 24) == 0 || (src[k - d * w] ushr 24) == 0 || (src[k + d * w] ushr 24) == 0
                d += 1
            }
            if (border) out[k] = 0xFF000000.toInt()
        }
        return out
    }

    /** The picture to share: a header (product / run / valid time), the map and a color key. */
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
                paint.color = 0xFF000000.toInt() or (Palettes.memberColors[members.getOrNull(i)] ?: 0x808080)
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
        private const val HALO = 0xEBFFFFFF.toInt()

        fun label(stop: Stop, style: Style): String {
            val v = stop.value
            return if (v == v.toLong().toDouble()) v.toLong().toString() else fixed(v, if (kotlin.math.abs(v) < 1) 2 else 1)
        }
    }
}
