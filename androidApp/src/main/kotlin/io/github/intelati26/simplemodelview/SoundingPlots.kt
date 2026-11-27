package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import simplemodelview.SHARP_MISSING
import simplemodelview.SharpAnalysis
import simplemodelview.SharpIndices
import simplemodelview.SharpParcel
import simplemodelview.SharpSars
import simplemodelview.SharpTornadoProb
import simplemodelview.SharpWind
import simplemodelview.SoundingHazard
import simplemodelview.gone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal val TEMP_RED = Color(0xFFD32F2F)
internal val DEW_GREEN = Color(0xFF2E7D32)
internal val WET_CYAN = Color(0xFF29B6F6)
internal val VIRTUAL_BLUE = Color(0xFF3949AB)
internal val ISOTHERM_ORANGE = Color(0xFFFFA54F)       // SPC's isotherms
internal val ADIABAT_PEACH = Color(0xFFFFE4C4)        // and its dry adiabats
internal val MIXING_GREEN = Color(0xFF43A047)
internal val HEIGHT_RED = Color(0xFFC62828)

private const val P_TOP = 100.0
private const val P_BOTTOM = 1050.0
private const val T_LEFT = -40.0
private const val T_RIGHT = 50.0

/** How far an isotherm leans: pixels to the right per pixel up. Measured from SPC's sounding image (the isotherms there cross 100 px of height with 69.5 px of lean). */
private const val SKEW = 0.695

/** The color of a wind at [zAgl] meters above the ground: red to 3 km, green to 6, purple to 9, cyan to 12 (SPC's layers). */
internal fun layerColor(zAgl: Double) = when { zAgl < 3000 -> Color(0xFFD32F2F); zAgl < 6000 -> Color(0xFF2E7D32); zAgl < 9000 -> Color(0xFF7E57C2); zAgl < 12000 -> Color(0xFF00ACC1); else -> Color(0xFF9E9E9E) }

internal fun Color.hashCodeColor(): Int = android.graphics.Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

private fun paint(color: Color, size: Float, bold: Boolean = false, align: android.graphics.Paint.Align = android.graphics.Paint.Align.LEFT) =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color.hashCodeColor(); isFakeBoldText = bold; textAlign = align }

/**
 * SPC's sounding page, in its order: the skew-T (orange isotherms, the 0 and -20 C ones dashed blue, green mixing-ratio lines, heights in km at the left, the freezing, -20 C and
 * wet-bulb-zero levels marked), then the barbs and the wind speed bars colored by layer. The [parcel] drawn is the one picked in the table.
 */
@Composable
internal fun SkewT(a: SharpAnalysis, parcel: SharpParcel?, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    val pr = a.profile
    Canvas(modifier) {
        val left = 34.dp.toPx(); val right = 90.dp.toPx(); val top = 6.dp.toPx(); val bottom = size.height - 18.dp.toPx()      // room under the plot for the temperature scale
        val pw = size.width - left - right; val ph = bottom - top
        fun y(p: Double) = (top + ph * (ln(p / P_TOP) / ln(P_BOTTOM / P_TOP))).toFloat()
        fun x(t: Double, p: Double) = (left + pw * (t - T_LEFT) / (T_RIGHT - T_LEFT) + SKEW * (bottom - y(p))).toFloat()
        val label = paint(text.copy(alpha = 0.7f), 10.dp.toPx())
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        clipRect(left, top, left + pw, bottom) {
            // dry adiabats every 10 C of potential temperature, light, under the isotherms
            for (theta in -30..200 step 10) {
                val path = Path(); var first = true
                var p = P_BOTTOM
                while (p >= P_TOP) {
                    val temp = (theta + 273.15) * (p / 1000.0).pow(0.2857) - 273.15
                    val px = x(temp, p); val py = y(p)
                    if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
                    p -= 25.0
                }
                drawPath(path, ADIABAT_PEACH, style = Stroke(1.2.dp.toPx()))
            }
            // isotherms every 10 C: orange, the 0 and -20 C ones dashed blue
            for (t in -120..50 step 10) {
                val blue = t == 0 || t == -20
                drawLine(if (blue) Color(0xFF1E40FF) else ISOTHERM_ORANGE, Offset(x(t.toDouble(), P_BOTTOM), y(P_BOTTOM)), Offset(x(t.toDouble(), P_TOP), y(P_TOP)), strokeWidth = 1.dp.toPx(), pathEffect = if (blue) dash else null)
            }
            // mixing ratio lines (g/kg), in the lowest part
            for (w in listOf(2, 3, 5, 8, 12, 16, 20)) {
                val path = Path(); var first = true
                var p = P_BOTTOM
                while (p >= 600.0) {
                    val px = x(simplemodelview.SharpThermo.tempAtMixingRatio(w.toDouble(), p), p); val py = y(p)
                    if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
                    p -= 25.0
                }
                drawPath(path, MIXING_GREEN.copy(alpha = 0.6f), style = Stroke(0.9.dp.toPx(), pathEffect = dash))
            }
        }
        for (p in 100..1000 step 100) {
            val major = p == 1000 || p == 850 || p == 700 || p == 500 || p == 300 || p == 200 || p == 100
            drawLine(grid.copy(alpha = if (major) 0.55f else 0.15f), Offset(left, y(p.toDouble())), Offset(left + pw, y(p.toDouble())), strokeWidth = if (major) 1.dp.toPx() else 0.8.dp.toPx())
            if (major) drawContext.canvas.nativeCanvas.drawText(p.toString(), 2.dp.toPx(), y(p.toDouble()) + 3.5.dp.toPx(), label)
        }
        drawRect(grid.copy(alpha = 0.5f), Offset(left, top), androidx.compose.ui.geometry.Size(pw, ph), style = Stroke(1.dp.toPx()))
        // the temperature scale (C) along the bottom edge, where the isotherms start
        val scale = paint(text.copy(alpha = 0.7f), 10.dp.toPx(), align = android.graphics.Paint.Align.CENTER)
        for (t in -30..50 step 10) {
            val tx = x(t.toDouble(), P_BOTTOM)
            if (tx < left + 4.dp.toPx() || tx > left + pw - 4.dp.toPx()) continue
            drawLine(grid.copy(alpha = 0.6f), Offset(tx, bottom), Offset(tx, bottom + 3.dp.toPx()), strokeWidth = 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(t.toString(), tx, bottom + 14.dp.toPx(), scale)
        }
        val idx = (0 until pr.size).filter { pr.pres[it] in P_TOP..P_BOTTOM }
        clipRect(left, top, left + pw, bottom) {
            fun line(color: Color, width: Float, value: (Int) -> Double) {
                val path = Path(); var started = false
                for (i in idx) {
                    val v = value(i)
                    if (gone(v)) { started = false; continue }
                    val px = x(v, pr.pres[i]); val py = y(pr.pres[i])
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width))
            }
            line(WET_CYAN, 1.2.dp.toPx()) { pr.wetbulb[it] }
            line(VIRTUAL_BLUE, 1.2.dp.toPx()) { pr.vtmp[it] }
            line(DEW_GREEN, 2.5.dp.toPx()) { pr.dwpc[it] }
            line(TEMP_RED, 2.5.dp.toPx()) { pr.tmpc[it] }
            parcel?.let { pc ->
                val path = Path(); var first = true
                for (k in pc.tracePres.indices) { val p = pc.tracePres[k]; if (p < P_TOP) continue; val px = x(pc.traceTemp[k], p); val py = y(p); if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py) }
                drawPath(path, text.copy(alpha = 0.85f), style = Stroke(1.3.dp.toPx(), pathEffect = dash))
            }
        }
        // the surface temperature and dewpoint in F at the base of the curves, as SPC prints them (dewpoint green to the left, temperature red to the right)
        val s0 = pr.sfc
        if (!gone(pr.tmpc[s0]) && !gone(pr.dwpc[s0])) {
            val ty = y(pr.pres[s0]) + 12.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText((pr.tmpc[s0] * 1.8 + 32).roundToInt().toString(), x(pr.tmpc[s0], pr.pres[s0]) + 2.dp.toPx(), ty, paint(TEMP_RED, 10.dp.toPx(), true))
            drawContext.canvas.nativeCanvas.drawText((pr.dwpc[s0] * 1.8 + 32).roundToInt().toString(), x(pr.dwpc[s0], pr.pres[s0]) - 2.dp.toPx(), ty, paint(DEW_GREEN, 10.dp.toPx(), true, android.graphics.Paint.Align.RIGHT))
        }
        // heights above the ground, in km (red, at the left), and the levels worth a mark
        val red = paint(HEIGHT_RED, 10.dp.toPx(), true)
        val top0 = pr.hght[pr.size - 1] - pr.sfcHght()
        for (km in listOf(1, 3, 6, 9, 12, 15)) {
            val p = SharpIndices.presAtAgl(pr, km * 1000.0)
            if (gone(p) || p < P_TOP || p > P_BOTTOM || p >= pr.pres[0] - 0.5 || km * 1000.0 > top0) continue
            drawContext.canvas.nativeCanvas.drawText("$km km", left + 3.dp.toPx(), y(p) - 2.dp.toPx(), red)
        }
        val marks = listOf(
            Triple("FRZ", SharpIndices.temperatureLevel(pr, 0.0).takeIf { !gone(a.freezingLevelAgl) }, ISOTHERM_ORANGE),
            Triple("-20C", SharpIndices.temperatureLevel(pr, -20.0), Color(0xFF1565C0)),
            Triple("WBZ", SharpIndices.temperatureLevel(pr, 0.0, wetBulb = true).takeIf { !gone(a.wetBulbZeroAgl) }, MIXING_GREEN),
        )
        for ((name, p, color) in marks) {
            if (p == null || gone(p) || p < P_TOP || p > P_BOTTOM) continue
            val zFt = (pr.interpHght(p) - pr.sfcHght()) * 3.28084
            drawLine(color, Offset(left + pw - 60.dp.toPx(), y(p)), Offset(left + pw, y(p)), strokeWidth = 1.5.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("$name ${zFt.roundToInt()}'", left + pw - 58.dp.toPx(), y(p) - 2.dp.toPx(), paint(color, 10.dp.toPx(), true))
        }
        // the winds: a barb, and a bar of the speed in the color of its layer, at about every 26 dp
        var lastY = Float.MAX_VALUE
        val bx = left + pw + 22.dp.toPx(); val barX = left + pw + 46.dp.toPx(); val barMax = 40.dp.toPx()
        for (i in idx) {
            val py = y(pr.pres[i])
            if (gone(pr.wspd[i]) || gone(pr.wdir[i]) || abs(lastY - py) < 26.dp.toPx() && lastY != Float.MAX_VALUE) continue
            lastY = py
            drawBarb(bx, py, pr.wdir[i], pr.wspd[i], text)
            drawRect(layerColor(pr.hght[i] - pr.sfcHght()), Offset(barX, py - 2.5.dp.toPx()), androidx.compose.ui.geometry.Size((pr.wspd[i] / 100.0).coerceAtMost(1.0).toFloat() * barMax, 5.dp.toPx()))
        }
        drawContext.canvas.nativeCanvas.drawText("kt", barX, top + 8.dp.toPx(), label)
    }
}

/** A wind barb at ([x], [y]): the staff points toward where the wind comes from; a pennant is 50 kt, a long barb 10, a short one 5. */
private fun DrawScope.drawBarb(x: Float, y: Float, fromDeg: Double, speedKt: Double, color: Color) {
    val len = 20.dp.toPx(); val tick = 8.dp.toPx(); val step = 3.5.dp.toPx(); val w = 1.2.dp.toPx()
    val r = fromDeg * PI / 180.0
    val sx = sin(r).toFloat(); val sy = (-cos(r)).toFloat()                 // the staff's direction on the screen (north is up)
    val tipX = x + sx * len; val tipY = y + sy * len
    drawLine(color, Offset(x, y), Offset(tipX, tipY), strokeWidth = w)
    if (speedKt < 2.5) { drawCircle(color, 3.dp.toPx(), Offset(x, y), style = Stroke(w)); return }
    // barbs lean 60 degrees clockwise from the staff
    val a = 60.0 * PI / 180.0
    val bxv = (sx * cos(a) - sy * sin(a)).toFloat(); val byv = (sx * sin(a) + sy * cos(a)).toFloat()
    var rest = (speedKt / 5.0).roundToInt() * 5
    var pos = 0f
    while (rest >= 50) {
        val p0 = Offset(tipX - sx * pos, tipY - sy * pos); val p1 = Offset(tipX - sx * (pos + step * 1.4f), tipY - sy * (pos + step * 1.4f))
        val path = Path().apply { moveTo(p0.x, p0.y); lineTo(p0.x + bxv * tick, p0.y + byv * tick); lineTo(p1.x, p1.y); close() }
        drawPath(path, color)
        pos += step * 1.8f; rest -= 50
    }
    while (rest >= 10) { val p = Offset(tipX - sx * pos, tipY - sy * pos); drawLine(color, p, Offset(p.x + bxv * tick, p.y + byv * tick), strokeWidth = w); pos += step; rest -= 10 }
    if (rest >= 5) {
        if (pos == 0f) pos += step                                         // a lone 5 kt barb sits a step in from the tip
        val p = Offset(tipX - sx * pos, tipY - sy * pos); drawLine(color, p, Offset(p.x + bxv * tick * 0.5f, p.y + byv * tick * 0.5f), strokeWidth = w)
    }
}

// ---- the hodograph ----

internal class HodoLayer(val label: String, val color: Color, val topM: Double)

internal val HODOGRAPH_LAYERS = listOf(
    HodoLayer("0\u20133 km", Color(0xFFD32F2F), 3000.0), HodoLayer("3\u20136", Color(0xFF2E7D32), 6000.0), HodoLayer("6\u20139", Color(0xFF7E57C2), 9000.0), HodoLayer("9\u201312", Color(0xFF00ACC1), 12000.0),
)

/** The wind of each level as a point (u, v in knots), joined from the ground up, a color for each layer, the storm motions (RM, LM), the mean wind and the Corfidi vectors (UP, DN) marked. */
@Composable
internal fun Hodograph(a: SharpAnalysis, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    val pr = a.profile
    Canvas(modifier) {
        val pts = (0 until pr.size).filter { !gone(pr.u[it]) && !gone(pr.v[it]) && pr.hght[it] - pr.sfcHght() in 0.0..12000.0 }.map { Triple(pr.hght[it] - pr.sfcHght(), pr.u[it], pr.v[it]) }
        val maxSpeed = pts.maxOfOrNull { hypot(it.second, it.third) } ?: 40.0
        val extent = max(40.0, (kotlin.math.ceil(maxSpeed / 10.0) * 10.0))
        val c = Offset(size.width / 2, size.height / 2)
        val scale = (minOf(size.width, size.height) / 2 - 14.dp.toPx()) / extent.toFloat()
        val label = paint(text.copy(alpha = 0.7f), 10.dp.toPx())
        val bold = paint(text, 10.dp.toPx(), true)
        var ring = 10.0
        while (ring <= extent) {
            drawCircle(ISOTHERM_ORANGE.copy(alpha = if (ring % 20.0 == 0.0) 0.4f else 0.2f), (ring * scale).toFloat(), c, style = Stroke(0.8.dp.toPx()))
            if (ring % 20.0 == 0.0) drawContext.canvas.nativeCanvas.drawText(ring.toInt().toString(), c.x + 2.dp.toPx(), c.y - (ring * scale).toFloat() + 10.dp.toPx(), label)
            ring += 10.0
        }
        drawLine(grid.copy(alpha = 0.4f), Offset(c.x - extent.toFloat() * scale, c.y), Offset(c.x + extent.toFloat() * scale, c.y), strokeWidth = 0.8.dp.toPx())
        drawLine(grid.copy(alpha = 0.4f), Offset(c.x, c.y - extent.toFloat() * scale), Offset(c.x, c.y + extent.toFloat() * scale), strokeWidth = 0.8.dp.toPx())
        fun at(u: Double, v: Double) = Offset(c.x + (u * scale).toFloat(), c.y - (v * scale).toFloat())
        for (i in 1 until pts.size) {
            val p0 = pts[i - 1]; val p1 = pts[i]
            val layer = HODOGRAPH_LAYERS.firstOrNull { p1.first <= it.topM } ?: HODOGRAPH_LAYERS.last()
            drawLine(layer.color, at(p0.second, p0.third), at(p1.second, p1.third), strokeWidth = 2.5.dp.toPx())
        }
        pts.firstOrNull()?.let { drawCircle(Color.Black, 3.dp.toPx(), at(it.second, it.third)) }
        // a black dot at 3, 6, 9 and 12 km, numbered
        for (km in listOf(3, 6, 9, 12)) {
            val z = km * 1000.0
            val j = pts.indexOfFirst { it.first >= z }
            if (j <= 0) continue
            val p0 = pts[j - 1]; val p1 = pts[j]; val f = (z - p0.first) / (p1.first - p0.first)
            val o = at(p0.second + f * (p1.second - p0.second), p0.third + f * (p1.third - p0.third))
            drawCircle(Color.Black, 2.5.dp.toPx(), o)
            drawContext.canvas.nativeCanvas.drawText("$km", o.x + 4.dp.toPx(), o.y - 3.dp.toPx(), bold)
        }
        fun mark(w: SharpWind, name: String, color: Color, plus: Boolean) {
            if (!w.valid) return
            val o = at(w.u, w.v)
            if (plus) {
                drawCircle(color, 5.dp.toPx(), o, style = Stroke(1.3.dp.toPx()))
                drawLine(color, Offset(o.x - 5.dp.toPx(), o.y), Offset(o.x + 5.dp.toPx(), o.y), strokeWidth = 1.dp.toPx())
                drawLine(color, Offset(o.x, o.y - 5.dp.toPx()), Offset(o.x, o.y + 5.dp.toPx()), strokeWidth = 1.dp.toPx())
            } else drawCircle(color, 3.5.dp.toPx(), o, style = Stroke(1.3.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(name, o.x + 7.dp.toPx(), o.y + 14.dp.toPx(), paint(color, 10.dp.toPx(), true))
        }
        mark(a.rightMover, "RM", text, true); mark(a.leftMover, "LM", text, true); mark(a.meanWind06, "MW", Color(0xFF1565C0), false)
        if (a.corfidi.valid) { mark(a.corfidi.upshear, "UP", Color(0xFF1E90FF), false); mark(a.corfidi.downshear, "DN", Color(0xFF1E90FF), false) }
    }
}

/** SPC's inset: the 1 km and 6 km above-ground wind barbs from one base, the angle and length between them showing the shear. */
@Composable
internal fun WindInset(a: SharpAnalysis, modifier: Modifier) {
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val pr = a.profile
        val c1 = Color(0xFFFF6E6E); val c2 = Color(0xFF78BEFF)
        val base = Offset(size.width / 2, size.height / 2 + 4.dp.toPx())
        for ((z, color) in listOf(1000.0 to c1, 6000.0 to c2)) {
            val w = SharpIndices.windAtAgl(pr, z)
            if (w.valid) drawBarb(base.x, base.y, w.direction(), w.speed(), color)
        }
        drawContext.canvas.nativeCanvas.drawText("1 km", 4.dp.toPx(), size.height - 4.dp.toPx(), paint(c1, 10.dp.toPx(), true))
        drawContext.canvas.nativeCanvas.drawText("6 km", size.width - 4.dp.toPx(), size.height - 4.dp.toPx(), paint(c2, 10.dp.toPx(), true, android.graphics.Paint.Align.RIGHT))
        drawRect(text.copy(alpha = 0.3f), Offset.Zero, size, style = Stroke(1.dp.toPx()))
    }
}

// ---- the small panels ----

private fun DrawScope.panelFrame(color: Color, left: Float, top: Float, w: Float, h: Float) =
    drawRect(color.copy(alpha = 0.5f), Offset(left, top), androidx.compose.ui.geometry.Size(w, h), style = Stroke(1.dp.toPx()))

/** SPC's theta-E panel: equivalent potential temperature (K) against pressure from the surface to 400 mb; a line leaning left with height is convectively unstable. TEI is the spread in the lowest 400 mb. */
@Composable
internal fun ThetaEPanel(a: SharpAnalysis, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant; val text = MaterialTheme.colorScheme.onSurface
    val pr = a.profile
    Column {
        Text("Theta-E v. Pres" + if (!gone(a.thetaEIndex)) "   TEI ${a.thetaEIndex.roundToInt()} K" else "", style = MaterialTheme.typography.labelMedium)
        Canvas(modifier) {
            val idx = (0 until pr.size).filter { !gone(pr.pres[it]) && !gone(pr.thetae[it]) && pr.pres[it] > 400.0 }
            if (idx.size < 2) return@Canvas
            val left = 30.dp.toPx(); val top = 6.dp.toPx(); val right = 8.dp.toPx(); val bottom = size.height - 18.dp.toPx()
            val pw = size.width - left - right; val ph = bottom - top
            val lo = idx.minOf { pr.thetae[it] + 273.15 } - 10.0; val hi = idx.maxOf { pr.thetae[it] + 273.15 } + 10.0
            val pMax = 1025.0; val pMin = 400.0
            fun px(v: Double) = (left + pw * (v - lo) / (hi - lo)).toFloat()
            fun py(p: Double) = (bottom - ph * (pMax - p) / (pMax - pMin)).toFloat()
            val label = paint(text.copy(alpha = 0.7f), 10.dp.toPx()); val center = paint(text.copy(alpha = 0.7f), 10.dp.toPx(), align = android.graphics.Paint.Align.CENTER)
            for (p in listOf(1000, 900, 800, 700, 600, 500)) {
                drawLine(grid.copy(alpha = 0.25f), Offset(left, py(p.toDouble())), Offset(left + pw, py(p.toDouble())), strokeWidth = 0.8.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(p.toString(), 2.dp.toPx(), py(p.toDouble()) + 3.5.dp.toPx(), label)
            }
            var v = kotlin.math.ceil(lo / 10.0) * 10.0
            while (v <= hi) { drawLine(grid.copy(alpha = 0.25f), Offset(px(v), top), Offset(px(v), bottom), strokeWidth = 0.8.dp.toPx()); drawContext.canvas.nativeCanvas.drawText(v.roundToInt().toString(), px(v), bottom + 13.dp.toPx(), center); v += 10.0 }
            panelFrame(grid, left, top, pw, ph)
            val path = Path()
            for ((k, i) in idx.withIndex()) { val x = px(pr.thetae[i] + 273.15); val y = py(pr.pres[i]); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            clipRect(left, top, left + pw, bottom) { drawPath(path, Color(0xFFFF0000), style = Stroke(2.dp.toPx())) }
        }
    }
}

/**
 * The speed of the wind relative to the right-moving storm against height above the ground (0 to 16 km, 0 to 80 kt), as SHARPpy's srwinds panel: the trace, the 0-2, 4-6 and 9-11 km mean
 * storm-relative winds as short bars, and the 40 to 70 kt classic-supercell envelope above 8 km.
 */
@Composable
internal fun StormRelativePanel(a: SharpAnalysis, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant; val text = MaterialTheme.colorScheme.onSurface
    val pr = a.profile
    Column {
        Text("SR Winds v. Height", style = MaterialTheme.typography.labelMedium)
        Canvas(modifier) {
            val storm = a.rightMover
            if (!storm.valid) return@Canvas
            val left = 28.dp.toPx(); val top = 6.dp.toPx(); val right = 8.dp.toPx(); val bottom = size.height - 18.dp.toPx()
            val pw = size.width - left - right; val ph = bottom - top
            val hMax = 16.0; val sMax = 80.0
            fun px(kt: Double) = (left + pw * kt / sMax).toFloat()
            fun py(km: Double) = (bottom - ph * km / hMax).toFloat()
            val label = paint(text.copy(alpha = 0.7f), 10.dp.toPx()); val center = paint(text.copy(alpha = 0.7f), 10.dp.toPx(), align = android.graphics.Paint.Align.CENTER)
            for (km in listOf(2, 4, 6, 8, 10, 12, 14)) { drawLine(grid.copy(alpha = 0.25f), Offset(left, py(km.toDouble())), Offset(left + pw, py(km.toDouble())), strokeWidth = 0.8.dp.toPx()); drawContext.canvas.nativeCanvas.drawText("$km", 4.dp.toPx(), py(km.toDouble()) + 3.5.dp.toPx(), label) }
            var kt = 0.0
            while (kt <= sMax) { drawLine(grid.copy(alpha = 0.25f), Offset(px(kt), top), Offset(px(kt), bottom), strokeWidth = 0.8.dp.toPx()); drawContext.canvas.nativeCanvas.drawText(kt.roundToInt().toString(), px(kt), bottom + 13.dp.toPx(), center); kt += 20.0 }
            panelFrame(grid, left, top, pw, ph)
            val classic = Color(0xFFB1019A)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            drawLine(classic, Offset(px(40.0), py(8.0)), Offset(px(40.0), py(16.0)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawLine(classic, Offset(px(70.0), py(8.0)), Offset(px(70.0), py(16.0)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawContext.canvas.nativeCanvas.drawText("Classic Supercell", px(40.0) - 4.dp.toPx(), py(15.2), paint(classic, 9.dp.toPx()))
            clipRect(left, top, left + pw, bottom) {
                // the trace: storm-relative speed every 100 m from the surface up to 16 km (or the top of the data)
                val path = Path(); var started = false
                var h = 0.0
                while (h < hMax * 1000.0) {
                    val p = if (h <= 0.0) pr.sfcPres() else pr.interpPresAtHght(pr.sfcHght() + h)
                    val uv = if (gone(p)) null else pr.interpComponents(p)
                    if (uv == null) { started = false; if (gone(p)) break; h += 100.0; continue }
                    val x = px(hypot(uv.first - storm.u, uv.second - storm.v)); val y = py(h / 1000.0)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    h += 100.0
                }
                drawPath(path, Color(0xFFFF0000), style = Stroke(1.5.dp.toPx()))
                fun meanBar(fromKm: Double, toKm: Double, color: Color) {
                    val m = SharpIndices.meanWind(pr, fromKm * 1000.0, toKm * 1000.0)
                    if (!m.valid) return
                    val x = px(hypot(m.u - storm.u, m.v - storm.v))
                    drawLine(color, Offset(x, py(fromKm)), Offset(x, py(toKm)), strokeWidth = 3.dp.toPx())
                }
                meanBar(0.0, 2.0, Color(0xFF8B0000)); meanBar(4.0, 6.0, Color(0xFF6495ED)); meanBar(9.0, 11.0, Color(0xFF9400D3))
            }
        }
    }
}

/** The inferred temperature advection of each layer (C per hour, SHARPpy's scale of -13 to +13) as bars about a zero line: warm to the right (red), cold to the left (light blue). */
@Composable
internal fun AdvectionPanel(a: SharpAnalysis, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant; val text = MaterialTheme.colorScheme.onSurface
    Column {
        Text("Inf. Temp. Adv. (C/hr)", style = MaterialTheme.typography.labelMedium)
        Canvas(modifier) {
            val layers = a.advection
            if (layers.isEmpty()) return@Canvas
            val left = 30.dp.toPx(); val top = 6.dp.toPx(); val right = 8.dp.toPx(); val bottom = size.height - 6.dp.toPx()
            val pw = size.width - left - right; val ph = bottom - top
            val pBottom = a.profile.pres[0]
            val extent = 13.0
            fun py(p: Double) = (top + ph * ln(p / 100.0) / ln(pBottom / 100.0)).toFloat()
            val mid = left + pw / 2
            val label = paint(text.copy(alpha = 0.7f), 10.dp.toPx()); val tiny = paint(text, 9.dp.toPx())
            for (p in listOf(1000, 850, 700, 500, 300, 200)) if (p < pBottom) { drawLine(grid.copy(alpha = 0.2f), Offset(left, py(p.toDouble())), Offset(left + pw, py(p.toDouble())), strokeWidth = 0.8.dp.toPx()); drawContext.canvas.nativeCanvas.drawText(p.toString(), 2.dp.toPx(), py(p.toDouble()) + 3.5.dp.toPx(), label) }
            panelFrame(grid, left, top, pw, ph)
            drawLine(grid.copy(alpha = 0.7f), Offset(mid, top), Offset(mid, bottom), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            for (l in layers) {
                val y0 = py(l.pTop); val y1 = py(l.pBottom)
                val half = (pw / 2 * (abs(l.cPerHour) / extent)).toFloat().coerceAtMost(pw / 2)
                val warm = l.cPerHour >= 0
                drawRect(if (warm) Color(0xFFD32F2F) else Color(0xFF3399CC), Offset(if (warm) mid else mid - half, y0 + 1.dp.toPx()), androidx.compose.ui.geometry.Size(half, (y1 - y0 - 2.dp.toPx()).coerceAtLeast(1f)))
                if (y1 - y0 > 11.dp.toPx()) drawContext.canvas.nativeCanvas.drawText("%.1f".format(l.cPerHour), if (warm) mid + half + 2.dp.toPx() else mid + 2.dp.toPx(), (y0 + y1) / 2 + 3.dp.toPx(), tiny)
            }
        }
    }
}

/**
 * A box-and-whisker inset: each row of [boxes] is {low whisker end, box bottom, median, box top, high whisker end}; the y axis runs 0..[yMax] with a dashed line at every [yStep]; the sounding's
 * [value] is the line across the plot in [valueColor].
 */
@Composable
internal fun BoxPlot(title: String, yMax: Double, yStep: Double, names: List<String>, boxes: List<DoubleArray>, median: Boolean, value: Double, valueColor: Color, modifier: Modifier) {
    val text = MaterialTheme.colorScheme.onSurface; val grid = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Canvas(modifier) {
            val left = 20.dp.toPx(); val top = 4.dp.toPx(); val right = 4.dp.toPx(); val bottom = size.height - 16.dp.toPx()
            val pw = size.width - left - right; val ph = bottom - top
            fun y(v: Double) = (bottom - v.coerceIn(0.0, yMax) / yMax * ph).toFloat()
            val label = paint(text.copy(alpha = 0.7f), 9.dp.toPx(), align = android.graphics.Paint.Align.RIGHT)
            var g = 0.0
            while (g <= yMax + 1e-9) {
                drawLine(Color(0xFF0080FF).copy(alpha = 0.6f), Offset(left, y(g)), Offset(left + pw, y(g)), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                drawContext.canvas.nativeCanvas.drawText(g.roundToInt().toString(), left - 3.dp.toPx(), y(g) + 3.dp.toPx(), label)
                g += yStep
            }
            val spacing = pw / (boxes.size + 1); val width = pw / (boxes.size * 2.2f)
            val names9 = paint(text.copy(alpha = 0.8f), 8.dp.toPx(), align = android.graphics.Paint.Align.CENTER)
            for ((i, b) in boxes.withIndex()) {
                val cx = left + spacing * (i + 1)
                val green = Color(0xFF00A000)
                drawLine(green, Offset(cx, y(b[0])), Offset(cx, y(b[1])), strokeWidth = 2.dp.toPx())
                drawRect(green, Offset(cx - width / 2, y(b[3])), androidx.compose.ui.geometry.Size(width, y(b[1]) - y(b[3])), style = Stroke(2.dp.toPx()))
                if (median) drawLine(green, Offset(cx - width / 2, y(b[2])), Offset(cx + width / 2, y(b[2])), strokeWidth = 2.dp.toPx())
                drawLine(green, Offset(cx, y(b[3])), Offset(cx, y(b[4])), strokeWidth = 2.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(names[i], cx, bottom + 12.dp.toPx(), names9)
            }
            if (!value.isNaN() && !gone(value)) {
                drawLine(valueColor, Offset(left, y(value)), Offset(left + pw, y(value)), strokeWidth = 2.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("%.1f".format(value), left + 3.dp.toPx(), y(value) - 3.dp.toPx(), paint(valueColor, 10.dp.toPx(), true))
            }
            drawRect(grid.copy(alpha = 0.5f), Offset(left, top), androidx.compose.ui.geometry.Size(pw, ph), style = Stroke(1.dp.toPx()))
        }
    }
}

/** SHARPpy's effective-layer STP box plot (Thompson et al. 2012; SPC): by the strength of the tornado, EF4+ to non-tornadic supercells. */
@Composable
internal fun StpBoxPlot(a: SharpAnalysis, modifier: Modifier) {
    val boxes = listOf(doubleArrayOf(1.2, 2.6, 5.3, 8.3, 11.0), doubleArrayOf(0.2, 1.0, 2.4, 4.5, 8.4), doubleArrayOf(0.0, 0.6, 1.7, 3.7, 5.6), doubleArrayOf(0.0, 0.3, 1.2, 2.6, 4.5), doubleArrayOf(0.0, 0.1, 0.8, 2.0, 3.7), doubleArrayOf(0.0, 0.0, 0.2, 0.7, 1.7))
    val v = a.stpEffective
    val color = if (gone(v)) Color.Gray else if (v < 0.1) Color(0xFF8B5A2B) else if (v < 1.0) Color(0xFF996600) else if (v < 2.0) Color(0xFF9E9E9E) else if (v < 4.0) Color(0xFFD4A900) else if (v < 8.0) Color(0xFFFF0000) else Color(0xFFE700DF)
    BoxPlot("Effective-Layer STP (with CIN)", 11.0, 1.0, listOf("EF4+", "EF3", "EF2", "EF1", "EF0", "NONT"), boxes, true, v, color, modifier)
}

/** SHARPpy's SHIP box plot: reports under 2 inches against 2 inches or larger. */
@Composable
internal fun ShipBoxPlot(a: SharpAnalysis, modifier: Modifier) {
    val boxes = listOf(doubleArrayOf(0.2, 0.3, 0.2, 0.9, 1.2), doubleArrayOf(1.1, 1.4, 0.8, 2.8, 4.0))
    val v = a.hail
    val color = if (gone(v)) Color.Gray else if (v >= 5.0) Color(0xFFE700DF) else if (v >= 2.0) Color(0xFFFF0000) else if (v >= 1.0) Color(0xFFD4A900) else if (v >= 0.5) Color(0xFF9E9E9E) else Color(0xFF8B5A2B)
    BoxPlot("SHIP", 5.0, 1.0, listOf("<2in", ">=2in"), boxes, false, v, color, modifier)
}
