package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import simplemodelview.ContourLine
import simplemodelview.ContourSpec
import simplemodelview.Contours
import simplemodelview.Core
import simplemodelview.Kind
import simplemodelview.PaintballLines
import simplemodelview.Palettes
import simplemodelview.Product
import simplemodelview.Stop
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.WindProducts
import simplemodelview.fixed

/** A second field drawn over the data (under the map lines): [bitmap] has one pixel per cell of its own window and is stretched onto [dst], in pixels of the base picture. */
class Overlay(
    val bitmap: Bitmap, val dst: android.graphics.RectF, val bold: Boolean, val marker: Boolean = false, val markerScale: Float = 1f,
    /** Edge the shapes in black with a white halo outside (hail swaths, rotation tracks). */
    val halo: Boolean = false,
    /** Markers: the color of every cross (0xRRGGBB) when it stands for an age (1 / 5 / 15 / 30 min); null: each cross takes its cell's color. */
    val markerColor: Int? = null,
)

/**
 * An alert outline in pixels of the base picture: [rings] as x0, y0, x1, y1, ... A watch or warning is filled faintly; a discussion is dashed.
 * [open]: the rings are lines, not outlines (LightningCast contours): not closed, not filled, a little thinner.
 * [combined]: the rings are one polygon (the outer ring, then its holes), filled together by the even-odd rule so a hole stays empty (SPC outlooks).
 */
class AlertDraw(val rings: List<FloatArray>, val color: Int, val dashed: Boolean, val fill: Boolean, val combined: Boolean = false, val open: Boolean = false, val hatch: Int = 1, val strong: Boolean = false, val shade: Boolean = false, val heavy: Boolean = false)   // 1 = "/", 2 = "\\", 3 = both

/** How many barbs the density levels (1 to 5) put across a picture's width: 10 (sparse) to 42, about what SPC's mesoanalysis charts draw across the country; the default is 24. */
internal fun barbsAcross(barbDensity: Int): Float = when (barbDensity) { 1 -> 10f; 2 -> 16f; 3 -> 24f; 4 -> 34f; else -> 42f }

/** Draws one finished map picture: background, data (native rasteriser), an optional overlay, lines. */
class MapRenderer(private val core: Core, private val lines: MapLines) {
    fun render(product: Product, style: Style, values: FloatArray, memberNames: List<String>, view: View, dark: Boolean, overlays: List<Overlay> = emptyList(), alerts: List<AlertDraw> = emptyList(), contours: Boolean = false, halo: Boolean = false, barbDensity: Int = DEFAULT_BARB_DENSITY, counties: Int = 0, barbZoom: Float = 1f): Bitmap {
        val w = view.pixelWidth
        val h = view.pixelHeight
        val argb = IntArray(w * h)
        val memberRgb = IntArray(10) { Palettes.memberColors[memberNames.getOrNull(it)] ?: 0x808080 }
        val paintLines = PaintballLines.isPaintballLines(product.id)
        // the probability contours lie over the model's own paintball (each member's cells over the threshold in its color): the mask is the same
        core.render(values, view, if (paintLines) Style(Kind.Paintball, units = "members") else style, memberRgb, argb)
        if (halo) outline(argb, w, h, edgeFor(w))
        val picture = argb
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF202428.toInt() else 0xFFEDEFF2.toInt())
        if (paintLines) drawPaintballLines(canvas, product, values, memberNames, view, dark, w)   // the member cells go over the contours
        canvas.drawBitmap(Bitmap.createBitmap(picture, w, h, Bitmap.Config.ARGB_8888), 0f, 0f, if (paintLines) Paint().apply { alpha = 0x90 } else null)
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
        lines.draw(canvas, view, dark, counties)
        if (contours) Contours.specFor(product.id, style.units)?.let { spec -> drawContourLines(canvas, Contours.lines(values, product.grid, view, style, spec), spec.color, dark, w) }
        WindProducts.planeLevel(product.id)?.let { level ->
            if (WindProducts.hasPlanes(values, product.grid.cells)) drawWind(canvas, product, values, level, view, dark, w, contours, barbDensity, barbZoom)
        }
        if (alerts.isNotEmpty()) drawAlerts(canvas, alerts, w)
        return bitmap
    }

    /** Lines of the share of members at or above the threshold (10 / 30 / 50 / 70 / 90 %), blurred over a neighborhood; one color per level, the 50 % line heavier. */
    private fun drawPaintballLines(canvas: Canvas, product: Product, mask: FloatArray, memberNames: List<String>, view: View, dark: Boolean, pictureWidth: Int) {
        val cells = product.grid.cells
        if (mask.size < cells) return
        val grid = product.grid
        val share = PaintballLines.blur(PaintballLines.neighborhoodPercent(mask, grid.columns, grid.rows, memberNames.size.takeIf { it > 0 } ?: 10, PaintballLines.NEIGHBORHOOD), grid.columns, grid.rows, PaintballLines.BLUR_RADIUS)
        val spec = ContourSpec(10.0, boldEvery = 50.0, labelSuffix = "%")
        val lines = Contours.lines(share, product.grid, view, Style(), spec, smoothing = 1, levelsOverride = PaintballLines.levels)
        val width = maxOf(2.2f, pictureWidth / 600f)
        val halo = if (dark) 0xDD000000.toInt() else 0xEEFFFFFF.toInt()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = maxOf(11f, pictureWidth / 90f); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        for (c in lines.sortedBy { it.level }) {
            val path = android.graphics.Path()
            path.moveTo(c.points[0], c.points[1])
            var i = 2
            while (i + 1 < c.points.size) { path.lineTo(c.points[i], c.points[i + 1]); i += 2 }
            val w = if (c.bold) width * 1.8f else width
            line.strokeWidth = w + 4f; line.color = halo; canvas.drawPath(path, line)
            line.strokeWidth = w; line.color = 0xFF000000.toInt() or PaintballLines.levelColor(c.level); canvas.drawPath(path, line)
        }
        for (c in lines) if (c.labelAt >= 0) {
            val x = c.points[c.labelAt]; val y = c.points[c.labelAt + 1]
            if (x < 0 || y < 0 || x > pictureWidth) continue
            text.style = Paint.Style.STROKE; text.strokeWidth = 4f; text.color = halo; canvas.drawText(c.label, x, y + text.textSize / 3, text)
            text.style = Paint.Style.FILL; text.color = 0xFF000000.toInt() or PaintballLines.levelColor(c.level); canvas.drawText(c.label, x, y + text.textSize / 3, text)
        }
    }

    /** A wind product's height contours (from its height plane) and barbs (from its u / v planes), over the speed colors. */
    private fun drawWind(canvas: Canvas, product: Product, values: FloatArray, level: Int, view: View, dark: Boolean, pictureWidth: Int, contours: Boolean, barbDensity: Int, barbZoom: Float = 1f) {
        val cells = product.grid.cells
        val stack = level == WindProducts.STACK && WindProducts.isStackFrame(values, cells)
        if (contours) Contours.specFor("z${if (level == WindProducts.STACK) 500 else level}", "m")?.let { spec ->
            val lines = Contours.lines(WindProducts.plane(values, cells, WindProducts.HEIGHT), product.grid, view, Style(), spec)
            drawContourLines(canvas, lines, spec.color, dark, pictureWidth)
        }
        // barbs across the picture's width, whatever area it shows (a sector gets as many as the whole country): 12 (sparse) to 56 (dense); 42 is about what SPC's
        // mesoanalysis charts draw across the country (one barb per 24 pixels of a 1000 pixel chart). [barbZoom]: the fraction of the picture on screen (pinch-zoom):
        // the barbs are laid out for what is shown, so they keep their size on screen as the picture is magnified. Drawn by the code the globe uses too.
        val across = barbsAcross(barbDensity) * if (stack) STACK_DENSITY else 1f
        val spacing = pictureWidth * barbZoom / across
        val lineWidth = (maxOf(1.2f, pictureWidth / 1300f) * barbZoom).coerceAtLeast(0.6f)      // SPC's mesoanalysis barbs are drawn with thin lines
        drawWindBarbs(canvas, core, FlatProjector(core, view, view.pixelWidth, view.pixelHeight), product.grid, values, level, dark, spacing, lineWidth, 2f * barbZoom, trueFrame = false, keyScale = barbZoom)
    }

    /** Diagonal lines inside [path] instead of a solid fill, so what is underneath stays readable. */
    private fun hatchInside(canvas: Canvas, path: android.graphics.Path, paint: Paint, gap: Float, kind: Int = 1) {
        val box = android.graphics.RectF().also { path.computeBounds(it, true) }
        canvas.save()
        canvas.clipPath(path)
        var x = box.left - box.height()
        if (kind and 1 != 0) while (x < box.right) { canvas.drawLine(x, box.bottom, x + box.height(), box.top, paint); x += gap }
        if (kind and 2 != 0) {
            x = box.left
            while (x < box.right + box.height()) { canvas.drawLine(x, box.bottom, x - box.height(), box.top, paint); x += gap }
        }
        canvas.restore()
    }

    /** Alert outlines over everything else: a dark casing under a colored line so they read on any background. */
    private fun drawAlerts(canvas: Canvas, alerts: List<AlertDraw>, pictureWidth: Int) {
        val width = maxOf(2f, pictureWidth / 600f)
        val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width + 2f; color = 0x4D000000.toInt(); strokeJoin = Paint.Join.ROUND }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width; strokeJoin = Paint.Join.ROUND }
        val baseHatch = maxOf(1f, pictureWidth / 1400f)
        val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = baseHatch }
        val gap = maxOf(12f, pictureWidth / 80f)
        val shadeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (a in alerts) {
            line.strokeWidth = (if (a.open) width * 0.75f else width) * (if (a.heavy) 1.6f else 1f)
            casing.strokeWidth = line.strokeWidth + 2f
            line.color = (if (a.heavy) 0xCC000000.toInt() else 0x66000000) or a.color
            line.pathEffect = if (a.dashed) android.graphics.DashPathEffect(floatArrayOf(width * 4f, width * 3f), 0f) else null
            casing.pathEffect = line.pathEffect
            shadeFill.color = 0x30000000 or a.color
            hatch.color = (if (a.strong) 0x99000000.toInt() else 0x66000000) or a.color
            hatch.strokeWidth = baseHatch * (if (a.strong) 1.8f else 1f)
            if (a.combined && a.fill) {
                val whole = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
                for (ring in a.rings) {
                    if (ring.size < 6) continue
                    whole.moveTo(ring[0], ring[1])
                    var i = 2
                    while (i + 1 < ring.size) { whole.lineTo(ring[i], ring[i + 1]); i += 2 }
                    whole.close()
                }
                if (a.strong) hatchInside(canvas, whole, hatch, gap, a.hatch)   // only the CIG / significant areas are hatched
            }
            // a watch is a patchwork of counties: shade and hatch each, but outline only the outer edge of the union, not the county lines inside it
            var outer: android.graphics.Path? = null
            if (a.shade && a.rings.size > 1) {
                val union = android.graphics.Path()
                var ok = true
                for (ring in a.rings) {
                    if (ring.size < 6) continue
                    val p = android.graphics.Path()
                    p.moveTo(ring[0], ring[1])
                    var i = 2
                    while (i + 1 < ring.size) { p.lineTo(ring[i], ring[i + 1]); i += 2 }
                    p.close()
                    if (union.isEmpty) union.set(p) else if (!union.op(p, android.graphics.Path.Op.UNION)) { ok = false; break }
                }
                if (ok) outer = union
            }
            for (ring in a.rings) {
                if (ring.size < 6) continue
                val path = android.graphics.Path()
                path.moveTo(ring[0], ring[1])
                var i = 2
                while (i + 1 < ring.size) { path.lineTo(ring[i], ring[i + 1]); i += 2 }
                if (!a.open) path.close()
                if (a.fill && !a.combined) { if (a.shade) canvas.drawPath(path, shadeFill); if (a.strong) hatchInside(canvas, path, hatch, gap, a.hatch) }
                if (outer == null) { canvas.drawPath(path, casing); canvas.drawPath(path, line) }
            }
            outer?.let { canvas.drawPath(it, casing); canvas.drawPath(it, line) }
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
            ink.color = overlay.markerColor?.let { (alpha shl 24) or it } ?: c
            rim.alpha = maxOf(alpha * 2 / 3, 90)         // (even a 20 % shadow gets a visible rim)
            canvas.drawLine(x - arm, y, x + arm, y, rim); canvas.drawLine(x, y - arm, x, y + arm, rim)
            canvas.drawLine(x - arm, y, x + arm, y, ink); canvas.drawLine(x, y - arm, x, y + arm, ink)
        }
    }

    /** How many pixels a thin shape (a rotation track one cell wide) is thickened by on a wide picture, so it still shows at the scale of the whole country. */
    private fun growFor(pictureWidth: Int) = if (pictureWidth >= 800) 1 + maxOf(0, pictureWidth - 2000) / 2500 else 0

    /** Thickens the shapes (pixels with any opacity) by [r] pixels: a clear pixel takes the most opaque neighbor within [r] along the row, then along the column. */
    private fun grow(px: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val row = IntArray(w)
        for (j in 0 until h) {
            System.arraycopy(px, j * w, row, 0, w)
            for (i in 0 until w) {
                if ((row[i] ushr 24) != 0) continue
                var best = 0
                for (k in maxOf(0, i - r)..minOf(w - 1, i + r)) if ((row[k] ushr 24) > (best ushr 24)) best = row[k]
                if (best != 0) px[j * w + i] = best
            }
        }
        val col = IntArray(h)
        for (i in 0 until w) {
            for (j in 0 until h) col[j] = px[j * w + i]
            for (j in 0 until h) {
                if ((col[j] ushr 24) != 0) continue
                var best = 0
                for (k in maxOf(0, j - r)..minOf(h - 1, j + r)) if ((col[k] ushr 24) > (best ushr 24)) best = col[k]
                if (best != 0) px[j * w + i] = best
            }
        }
    }

    /** Edge width in picture pixels: 2 on a small picture, 3 on a large one. */
    private fun edgeFor(pictureWidth: Int) = 1 + maxOf(1, pictureWidth / 1200)

    /** The overlay drawn at picture resolution, its shapes edged in black and haloed in white (see [outline]). */
    private fun drawHaloed(canvas: Canvas, overlay: Overlay, w: Int, h: Int) {
        val plain = Paint().apply { isFilterBitmap = false }
        try {
            val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(layer).drawBitmap(overlay.bitmap, null, overlay.dst, plain)
            val pixels = IntArray(w * h)
            layer.getPixels(pixels, 0, w, 0, 0, w, h)
            grow(pixels, w, h, growFor(w))
            outline(pixels, w, h, edgeFor(w))
            layer.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(layer, 0f, 0f, null)
            layer.recycle()
        } catch (_: OutOfMemoryError) {
            // a very wide picture with the heap nearly full: the overlay without its edge and halo
            canvas.drawBitmap(overlay.bitmap, null, overlay.dst, plain)
        }
    }

    /**
     * wxqt's swath style: the shapes (pixels with any opacity) edged in black, `edge` pixels deep along the border, and a white halo (92 % opaque) on the clear
     * pixels within `edge` pixels of a shape, so a hail swath or a rotation track reads whatever color is under it.
     */
    private fun outline(src: IntArray, w: Int, h: Int, edge: Int) {
        // [src] is changed in place, so which pixels are shapes is read from a mask taken first (7 MB on a 28 MB picture, not another copy)
        val solid = BooleanArray(w * h) { (src[it] ushr 24) != 0 }
        // the clear pixels near a shape: a square dilation of the shapes, one pass along the rows and one along the columns
        val along = BooleanArray(w * h)
        for (j in 0 until h) {
            var i = 0
            while (i < w) {
                if (solid[j * w + i]) for (k in maxOf(0, i - edge)..minOf(w - 1, i + edge)) along[j * w + k] = true
                i += 1
            }
        }
        for (j in 0 until h) for (i in 0 until w) {
            val k = j * w + i
            if (!solid[k]) {
                var near = false
                var jj = maxOf(0, j - edge)
                while (jj <= minOf(h - 1, j + edge) && !near) { near = along[jj * w + i]; jj += 1 }
                if (near) src[k] = HALO
                continue
            }
            var border = false
            var d = 1
            while (d <= edge && !border) {
                border = i - d < 0 || i + d >= w || j - d < 0 || j + d >= h ||
                    !solid[k - d] || !solid[k + d] || !solid[k - d * w] || !solid[k + d * w]
                d += 1
            }
            if (border) src[k] = 0xFF000000.toInt()
        }
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
