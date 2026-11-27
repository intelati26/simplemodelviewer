package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import simplemodelview.Bufkit
import simplemodelview.BufkitFile
import simplemodelview.BufkitModel
import simplemodelview.BufkitStation
import simplemodelview.Sounding
import simplemodelview.formatUtc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val TEMP_RED = Color(0xFFD32F2F)
private val DEW_GREEN = Color(0xFF2E7D32)
private val WET_CYAN = Color(0xFF0097A7)

/** What a model's file for the station gave: the profiles, or why there are none. */
private class Loaded(val model: BufkitModel, val file: BufkitFile?, val error: String?)

/**
 * A model sounding at a station (an ASOS airport's BUFKIT file): the skew-T and the hodograph for the hour on the slider, with the file's own indices below.
 * The RRFS and the GFS are one file each, so the slider moves through every forecast hour without another download.
 */
@Composable
internal fun SoundingScreen(station: BufkitStation, startModel: BufkitModel, onDismiss: () -> Unit) {
    var model by remember { mutableStateOf(startModel) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(station.id, model) {
        loaded = null
        val r = try { AppContainer.http.get(model.url(station.id)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val file = if (r != null && r.status == 200) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Bufkit.parse(r.body.decodeToString()) } else null
        loaded = Loaded(model, file, if (file != null) null else if (r == null || r.status == 0) "Could not reach the server." else "No ${model.label} sounding for ${station.id} right now.")
        // keep the hour nearest the one that was shown (the two models start and step differently): the first hour of a new file
        index = 0
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${station.id}  ·  ${station.name}", fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("%.0f km from the crosshair".format(station.km), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in BufkitModel.entries) FilterChip(selected = m == model, onClick = { model = m }, label = { Text(m.label) })
                }
                val l = loaded
                val file = l?.file
                when {
                    l == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    file == null -> Text(l.error ?: "No sounding.", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                    else -> {
                        val at = index.coerceIn(0, file.soundings.size - 1)
                        val s = file.soundings[at]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = at > 0, onClick = { index = at - 1 }) { Text("‹") }
                            Column(Modifier.weight(1f)) {
                                Text(formatUtc(s.valid) + "   (+${s.forecastHour} h)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                file.run?.let { Text("${model.label} run ${formatUtc(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            TextButton(enabled = at < file.soundings.size - 1, onClick = { index = at + 1 }) { Text("›") }
                        }
                        if (file.soundings.size > 1) Slider(value = at.toFloat(), onValueChange = { index = it.roundToInt() }, valueRange = 0f..(file.soundings.size - 1).toFloat())
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            SkewT(s, file.elevationM, Modifier.fillMaxWidth().aspectRatio(0.95f))
                            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("— temperature", color = TEMP_RED, style = MaterialTheme.typography.labelSmall)
                                Text("— dewpoint", color = DEW_GREEN, style = MaterialTheme.typography.labelSmall)
                                Text("— wet bulb", color = WET_CYAN, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("Hodograph (knots, winds to 12 km above the ground)", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                            Hodograph(s, file.elevationM, Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for ((label, color) in HODOGRAPH_LAYERS.map { it.label to it.color }) Text("— $label", color = color, style = MaterialTheme.typography.labelSmall)
                            }
                            IndexList(s)
                            Text("Model profile at the airport's grid point: ${model.label}, Iowa State's BUFKIT feed.", Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexList(s: Sounding) {
    val shown = listOf("CAPE" to "CAPE", "CINS" to "CIN", "LCLP" to "LCL (mb)", "LFCT" to "LFC (mb)", "EQLV" to "EL (mb)", "PWAT" to "PW (mm)", "KINX" to "K index", "SHOW" to "Showalter", "TOTL" to "Total totals", "BRCH" to "BRN")
        .mapNotNull { (key, label) -> s.indices[key]?.takeIf { !it.isNaN() }?.let { label to it } }
    if (shown.isEmpty()) return
    Text("From the file", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
    Text(shown.joinToString("   ") { (label, v) -> "$label ${if (abs(v) >= 100) "%.0f".format(v) else "%.1f".format(v)}" }, style = MaterialTheme.typography.bodySmall)
}

// ---- the skew-T ----

private const val P_TOP = 100.0
private const val P_BOTTOM = 1050.0
private const val T_LEFT = -40.0
private const val T_RIGHT = 50.0

/** Temperature and dewpoint (and wet bulb) against pressure, the isotherms skewed to the right; the wind barbs of the levels at the right edge. */
@Composable
private fun SkewT(s: Sounding, elevationM: Double, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val left = 34.dp.toPx(); val right = 46.dp.toPx(); val top = 6.dp.toPx(); val bottom = size.height - 6.dp.toPx()
        val pw = size.width - left - right; val ph = bottom - top
        fun y(p: Double) = (top + ph * (ln(p / P_TOP) / ln(P_BOTTOM / P_TOP))).toFloat()
        fun x(t: Double, p: Double) = (left + pw * (t - T_LEFT) / (T_RIGHT - T_LEFT) + (bottom - y(p))).toFloat()
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); color = text.copy(alpha = 0.7f).hashCodeColor() }
        clipRect(left, top, left + pw, bottom) {
            // isotherms, every 10 C (the 0 C line heavier)
            var t = -120
            while (t <= 50) {
                drawLine(grid.copy(alpha = if (t == 0) 0.55f else 0.2f), Offset(x(t.toDouble(), P_BOTTOM), y(P_BOTTOM)), Offset(x(t.toDouble(), P_TOP), y(P_TOP)), strokeWidth = if (t == 0) 1.5.dp.toPx() else 0.8.dp.toPx())
                t += 10
            }
            // dry adiabats, every 10 C of potential temperature
            for (theta in -30..200 step 10) {
                val path = Path()
                var first = true
                var p = P_BOTTOM
                while (p >= P_TOP) {
                    val temp = (theta + 273.15) * (p / 1000.0).pow(0.2857) - 273.15
                    val px = x(temp, p); val py = y(p)
                    if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
                    p -= 25.0
                }
                drawPath(path, Color(0xFF8D6E63).copy(alpha = 0.18f), style = Stroke(0.8.dp.toPx()))
            }
        }
        // pressure lines
        for (p in 100..1000 step 100) {
            val major = p == 1000 || p == 850 || p == 700 || p == 500 || p == 300 || p == 200 || p == 100
            drawLine(grid.copy(alpha = if (major) 0.4f else 0.15f), Offset(left, y(p.toDouble())), Offset(left + pw, y(p.toDouble())), strokeWidth = 0.8.dp.toPx())
            if (major) drawContext.canvas.nativeCanvas.drawText(p.toString(), 2.dp.toPx(), y(p.toDouble()) + 3.5.dp.toPx(), label)
        }
        drawRect(grid.copy(alpha = 0.5f), Offset(left, top), androidx.compose.ui.geometry.Size(pw, ph), style = Stroke(1.dp.toPx()))
        val levels = s.levels.filter { it.pressure in P_TOP..P_BOTTOM }
        clipRect(left, top, left + pw, bottom) {
            fun line(color: Color, width: Float, value: (simplemodelview.SoundingLevel) -> Double) {
                val path = Path(); var started = false
                for (l in levels) {
                    val v = value(l)
                    if (v.isNaN()) { started = false; continue }
                    val px = x(v, l.pressure); val py = y(l.pressure)
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width))
            }
            line(WET_CYAN, 1.2.dp.toPx()) { it.wetBulbC }
            line(DEW_GREEN, 2.5.dp.toPx()) { it.dewC }
            line(TEMP_RED, 2.5.dp.toPx()) { it.tempC }
        }
        // the winds, a barb at the right edge about every 25 dp
        var lastY = Float.MAX_VALUE
        val bx = left + pw + 24.dp.toPx()
        for (l in levels) {
            val py = y(l.pressure)
            if (l.speedKt.isNaN() || l.dirDeg.isNaN() || abs(lastY - py) < 26.dp.toPx() && lastY != Float.MAX_VALUE) continue
            lastY = py
            drawBarb(bx, py, l.dirDeg, l.speedKt, text)
        }
    }
}

private fun Color.hashCodeColor(): Int = android.graphics.Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

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

private class HodoLayer(val label: String, val color: Color, val topM: Double)

private val HODOGRAPH_LAYERS = listOf(
    HodoLayer("0–1 km", Color(0xFFD32F2F), 1000.0), HodoLayer("1–3", Color(0xFFEF6C00), 3000.0), HodoLayer("3–6", Color(0xFF2E7D32), 6000.0),
    HodoLayer("6–9", Color(0xFF1565C0), 9000.0), HodoLayer("9–12", Color(0xFF6A1B9A), 12000.0),
)

/** The wind of each level as a point (u, v in knots), joined from the ground up, a color for each of the layers. */
@Composable
private fun Hodograph(s: Sounding, elevationM: Double, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val pts = s.levels.mapNotNull { l -> l.uv()?.let { (u, v) -> Triple(l.heightM - elevationM, u, v) } }.filter { it.first in 0.0..12000.0 }.sortedBy { it.first }
        val maxSpeed = pts.maxOfOrNull { kotlin.math.hypot(it.second, it.third) } ?: 40.0
        val extent = max(40.0, (kotlin.math.ceil(maxSpeed / 20.0) * 20.0))
        val c = Offset(size.width / 2, size.height / 2)
        val scale = (minOf(size.width, size.height) / 2 - 14.dp.toPx()) / extent.toFloat()
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); color = text.copy(alpha = 0.7f).hashCodeColor() }
        var ring = 20.0
        while (ring <= extent) {
            drawCircle(grid.copy(alpha = 0.25f), (ring * scale).toFloat(), c, style = Stroke(0.8.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(ring.toInt().toString(), c.x + 2.dp.toPx(), c.y - (ring * scale).toFloat() + 10.dp.toPx(), label)
            ring += 20.0
        }
        drawLine(grid.copy(alpha = 0.4f), Offset(c.x - extent.toFloat() * scale, c.y), Offset(c.x + extent.toFloat() * scale, c.y), strokeWidth = 0.8.dp.toPx())
        drawLine(grid.copy(alpha = 0.4f), Offset(c.x, c.y - extent.toFloat() * scale), Offset(c.x, c.y + extent.toFloat() * scale), strokeWidth = 0.8.dp.toPx())
        fun at(u: Double, v: Double) = Offset(c.x + (u * scale).toFloat(), c.y - (v * scale).toFloat())
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val layer = HODOGRAPH_LAYERS.firstOrNull { b.first <= it.topM } ?: HODOGRAPH_LAYERS.last()
            drawLine(layer.color, at(a.second, a.third), at(b.second, b.third), strokeWidth = 2.5.dp.toPx())
        }
        pts.firstOrNull()?.let { drawCircle(Color.Black, 3.dp.toPx(), at(it.second, it.third)) }
    }
}
