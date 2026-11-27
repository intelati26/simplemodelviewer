package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import simplemodelview.Bufkit
import simplemodelview.BufkitFile
import simplemodelview.BufkitModel
import simplemodelview.BufkitStation
import simplemodelview.Sounding
import simplemodelview.SoundingMath
import simplemodelview.metersToFeet
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
private val WET_CYAN = Color(0xFF29B6F6)
private val VIRTUAL_BLUE = Color(0xFF3949AB)
private val ISOTHERM_ORANGE = Color(0xFFFFA54F)       // SPC's isotherms
private val ADIABAT_PEACH = Color(0xFFFFE4C4)        // and its dry adiabats
private val MIXING_GREEN = Color(0xFF43A047)
private val HEIGHT_RED = Color(0xFFC62828)

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
    var parcelAt by remember { mutableIntStateOf(2) }            // the parcel the skew-T draws: 0 surface, 1 mixed layer, 2 most unstable (SPC's default)
    LaunchedEffect(station.id, model) {
        loaded = null
        val r = try { AppContainer.http.get(model.url(station.id)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val file = if (r != null && r.status == 200) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Bufkit.parse(r.body.decodeToString()) } else null
        loaded = Loaded(model, file, if (file != null) null else if (r == null || r.status == 0) "Could not reach the server." else "No ${model.label} sounding for ${station.id} right now.")
        // keep the hour nearest the one that was shown (the two models start and step differently): the first hour of a new file
        index = 0
    }
    // a wide screen (a tablet, or the phone on its side) gets the header and the time controls in fewer rows, to leave the height to the plots
    val wideScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                val modelChips: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in BufkitModel.entries) FilterChip(selected = m == model, onClick = { model = m }, label = { Text(m.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = if (wideScreen) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wideScreen) 12.dp else 0.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${station.id}  ·  ${station.name}", fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("%.0f km from the crosshair".format(station.km), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (wideScreen) modelChips()
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (!wideScreen) modelChips()
                val l = loaded
                val file = l?.file
                when {
                    l == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    file == null -> Text(l.error ?: "No sounding.", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                    else -> {
                        val at = index.coerceIn(0, file.soundings.size - 1)
                        val s = file.soundings[at]
                        val analysis = remember(s, file) { SoundingMath.analyze(s, file.elevationM) }
                        val slider: @Composable (Modifier) -> Unit = { m ->
                            if (file.soundings.size > 1) Slider(value = at.toFloat(), onValueChange = { index = it.roundToInt() }, valueRange = 0f..(file.soundings.size - 1).toFloat(), modifier = m)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = at > 0, onClick = { index = at - 1 }) { Text("\u2039") }
                            Column(if (wideScreen) Modifier else Modifier.weight(1f)) {
                                Text("${station.id}  ${stamp(s.valid)}  (${model.label} +${s.forecastHour} h)", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                file.run?.let { Text("run ${formatUtc(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            TextButton(enabled = at < file.soundings.size - 1, onClick = { index = at + 1 }) { Text("\u203A") }
                            if (wideScreen) slider(Modifier.weight(1f))
                        }
                        if (!wideScreen) slider(Modifier)
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                            // SPC's page is wide: the skew-T at the left, the hodograph beside it, the tables under them. A narrow screen stacks the same pieces.
                            val wide = maxWidth >= 700.dp
                            val parcels = analysis?.let { listOf(it.surface, it.mixed, it.mostUnstable) }
                            val shownParcel = parcels?.getOrNull(parcelAt.coerceIn(0, 2))
                            val skew: @Composable () -> Unit = {
                                SkewT(s, file.elevationM, analysis, shownParcel, Modifier.fillMaxWidth().aspectRatio(0.95f))
                                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("\u2014 temperature", color = TEMP_RED, style = MaterialTheme.typography.labelSmall)
                                    Text("\u2014 dewpoint", color = DEW_GREEN, style = MaterialTheme.typography.labelSmall)
                                    Text("\u2014 wet bulb", color = WET_CYAN, style = MaterialTheme.typography.labelSmall)
                                    Text("\u2014 virtual T", color = VIRTUAL_BLUE, style = MaterialTheme.typography.labelSmall)
                                    Text("- - parcel", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            val parcelTable: @Composable () -> Unit = {
                                if (analysis != null) { ParcelTable(analysis, parcelAt) { parcelAt = it }; Text("Tap a parcel to draw it on the skew-T.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            val hodo: @Composable () -> Unit = {
                                Hodograph(s, file.elevationM, analysis?.kinematics, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = if (wide) 0.dp else 10.dp, bottom = 4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    for (l in HODOGRAPH_LAYERS) Text("\u2014 ${l.label}", color = l.color, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            val windTable: @Composable () -> Unit = { analysis?.kinematics?.let { WindTable(it) } }
                            val indexTable: @Composable () -> Unit = {
                                analysis?.let { IndexTable(it) }
                                IndexList(s)
                                Text("Model profile at the airport's grid point: ${model.label}, Iowa State's BUFKIT feed.", Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (wide) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Column(Modifier.weight(1f)) { skew() }
                                        Column(Modifier.weight(1f)) { hodo() }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Column(Modifier.weight(1f)) { parcelTable() }
                                        Column(Modifier.weight(1f)) { windTable() }
                                    }
                                } else {
                                    skew(); parcelTable(); hodo(); windTable()
                                }
                                indexTable()
                            }
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

/** How far an isotherm leans: pixels to the right per pixel up. Measured from SPC's sounding image (the isotherms there cross 100 px of height with 69.5 px of lean). */
private const val SKEW = 0.695

private fun stamp(valid: Long): String =
    java.text.SimpleDateFormat("yyMMdd/HHmm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(valid * 1000))

/** The color of a wind at [zAgl] meters above the ground: red to 3 km, green to 6, purple to 9, cyan to 12 (SPC's layers). */
private fun layerColor(zAgl: Double) = when { zAgl < 3000 -> Color(0xFFD32F2F); zAgl < 6000 -> Color(0xFF2E7D32); zAgl < 9000 -> Color(0xFF7E57C2); zAgl < 12000 -> Color(0xFF00ACC1); else -> Color(0xFF9E9E9E) }

/**
 * SPC's sounding page, in its order: the skew-T (orange isotherms, the -20 C one dashed blue, green mixing-ratio lines, heights in km at the left, the freezing,
 * -20 C and wet-bulb-zero levels marked), then the barbs and the wind speed bars colored by layer. The [parcel] drawn is the one picked in the table.
 */
@Composable
private fun SkewT(s: Sounding, elevationM: Double, analysis: SoundingMath.Analysis?, parcel: SoundingMath.Parcel?, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val left = 34.dp.toPx(); val right = 90.dp.toPx(); val top = 6.dp.toPx(); val bottom = size.height - 6.dp.toPx()
        val pw = size.width - left - right; val ph = bottom - top
        fun y(p: Double) = (top + ph * (ln(p / P_TOP) / ln(P_BOTTOM / P_TOP))).toFloat()
        fun x(t: Double, p: Double) = (left + pw * (t - T_LEFT) / (T_RIGHT - T_LEFT) + SKEW * (bottom - y(p))).toFloat()
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); color = text.copy(alpha = 0.7f).hashCodeColor() }
        fun tag(color: Color) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); this.color = color.hashCodeColor(); isFakeBoldText = true }
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        clipRect(left, top, left + pw, bottom) {
            // dry adiabats every 10 C of potential temperature, light, under the isotherms
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
                    val px = x(SoundingMath.dewpointFromMixing(p, w / 1000.0), p); val py = y(p)
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
        val levels = s.levels.filter { it.pressure in P_TOP..P_BOTTOM }
        clipRect(left, top, left + pw, bottom) {
            fun line(color: Color, width: Float, pathEffect: PathEffect? = null, value: (simplemodelview.SoundingLevel) -> Double) {
                val path = Path(); var started = false
                for (l in levels) {
                    val v = value(l)
                    if (v.isNaN()) { started = false; continue }
                    val px = x(v, l.pressure); val py = y(l.pressure)
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width, pathEffect = pathEffect))
            }
            line(WET_CYAN, 1.2.dp.toPx()) { it.wetBulbC }
            line(VIRTUAL_BLUE, 1.2.dp.toPx()) { if (it.tempC.isNaN() || it.dewC.isNaN()) Double.NaN else SoundingMath.virtualTemperatureC(it.pressure, it.tempC, it.dewC) }
            line(DEW_GREEN, 2.5.dp.toPx()) { it.dewC }
            line(TEMP_RED, 2.5.dp.toPx()) { it.tempC }
            parcel?.let { pc ->
                val path = Path(); var first = true
                for ((p, t) in pc.trace) { if (p < P_TOP) continue; val px = x(t, p); val py = y(p); if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py) }
                drawPath(path, text.copy(alpha = 0.85f), style = Stroke(1.3.dp.toPx(), pathEffect = dash))
            }
        }
        // heights above the ground, in km (red, at the left), and the levels worth a mark
        if (analysis != null) {
            val red = tag(HEIGHT_RED)
            for (km in listOf(1, 3, 6, 9, 12, 15)) {
                val p = analysis.profile.pAt(km * 1000.0)
                if (p < P_TOP || p > P_BOTTOM || p >= analysis.profile.p[0] - 0.5) continue
                drawContext.canvas.nativeCanvas.drawText("$km km", left + 3.dp.toPx(), y(p) - 2.dp.toPx(), red)
            }
            val marks = listOfNotNull(
                analysis.freezingM?.let { Triple("FRZ", it, ISOTHERM_ORANGE) }, analysis.minus20M?.let { Triple("-20C", it, Color(0xFF1565C0)) },
                analysis.wetBulbZeroM?.let { Triple("WBZ", it, MIXING_GREEN) },
            )
            for ((name, z, color) in marks) {
                val p = analysis.profile.pAt(z)
                if (p < P_TOP || p > P_BOTTOM) continue
                drawLine(color, Offset(left + pw - 60.dp.toPx(), y(p)), Offset(left + pw, y(p)), strokeWidth = 1.5.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("$name ${metersToFeet(z).roundToInt()}'", left + pw - 58.dp.toPx(), y(p) - 2.dp.toPx(), tag(color))
            }
        }
        // the winds: a barb, and a bar of the speed in the color of its layer, at about every 26 dp
        var lastY = Float.MAX_VALUE
        val bx = left + pw + 22.dp.toPx(); val barX = left + pw + 46.dp.toPx(); val barMax = 40.dp.toPx()
        for (l in levels) {
            val py = y(l.pressure)
            if (l.speedKt.isNaN() || l.dirDeg.isNaN() || abs(lastY - py) < 26.dp.toPx() && lastY != Float.MAX_VALUE) continue
            lastY = py
            drawBarb(bx, py, l.dirDeg, l.speedKt, text)
            drawRect(layerColor(l.heightM - elevationM), Offset(barX, py - 2.5.dp.toPx()), androidx.compose.ui.geometry.Size((l.speedKt / 100.0).coerceAtMost(1.0).toFloat() * barMax, 5.dp.toPx()))
        }
        drawContext.canvas.nativeCanvas.drawText("kt", barX, top + 8.dp.toPx(), label)
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
    HodoLayer("0\u20133 km", Color(0xFFD32F2F), 3000.0), HodoLayer("3\u20136", Color(0xFF2E7D32), 6000.0), HodoLayer("6\u20139", Color(0xFF7E57C2), 9000.0), HodoLayer("9\u201312", Color(0xFF00ACC1), 12000.0),
)

/** The wind of each level as a point (u, v in knots), joined from the ground up, a color for each layer, the storm motions (RM, LM) and the mean wind marked. */
@Composable
private fun Hodograph(s: Sounding, elevationM: Double, kin: SoundingMath.Kinematics?, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val pts = s.levels.mapNotNull { l -> l.uv()?.let { (u, v) -> Triple(l.heightM - elevationM, u, v) } }.filter { it.first in 0.0..12000.0 }.sortedBy { it.first }
        val maxSpeed = pts.maxOfOrNull { kotlin.math.hypot(it.second, it.third) } ?: 40.0
        val extent = max(40.0, (kotlin.math.ceil(maxSpeed / 10.0) * 10.0))
        val c = Offset(size.width / 2, size.height / 2)
        val scale = (minOf(size.width, size.height) / 2 - 14.dp.toPx()) / extent.toFloat()
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); color = text.copy(alpha = 0.7f).hashCodeColor() }
        val bold = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); color = text.hashCodeColor(); isFakeBoldText = true }
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
            val a = pts[i - 1]; val b = pts[i]
            val layer = HODOGRAPH_LAYERS.firstOrNull { b.first <= it.topM } ?: HODOGRAPH_LAYERS.last()
            drawLine(layer.color, at(a.second, a.third), at(b.second, b.third), strokeWidth = 2.5.dp.toPx())
        }
        pts.firstOrNull()?.let { drawCircle(Color.Black, 3.dp.toPx(), at(it.second, it.third)) }
        // a black dot at 3, 6, 9 and 12 km, numbered
        for (km in listOf(3, 6, 9, 12)) {
            val z = km * 1000.0
            val j = pts.indexOfFirst { it.first >= z }
            if (j <= 0) continue
            val a = pts[j - 1]; val b = pts[j]; val f = (z - a.first) / (b.first - a.first)
            val o = at(a.second + f * (b.second - a.second), a.third + f * (b.third - a.third))
            drawCircle(Color.Black, 2.5.dp.toPx(), o)
            drawContext.canvas.nativeCanvas.drawText("$km", o.x + 4.dp.toPx(), o.y - 3.dp.toPx(), bold)
        }
        // the storm motions and the mean wind (m/s to knots)
        if (kin != null) {
            fun mark(w: SoundingMath.Wind, name: String) {
                val o = at(w.u / 0.514444, w.v / 0.514444)
                drawCircle(text, 5.dp.toPx(), o, style = Stroke(1.3.dp.toPx()))
                drawLine(text, Offset(o.x - 5.dp.toPx(), o.y), Offset(o.x + 5.dp.toPx(), o.y), strokeWidth = 1.dp.toPx())
                drawLine(text, Offset(o.x, o.y - 5.dp.toPx()), Offset(o.x, o.y + 5.dp.toPx()), strokeWidth = 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(name, o.x + 7.dp.toPx(), o.y + 14.dp.toPx(), bold)
            }
            mark(kin.rightMover, "RM"); mark(kin.leftMover, "LM")
            val mw = at(kin.mean06.u / 0.514444, kin.mean06.v / 0.514444)
            drawCircle(Color(0xFF1565C0), 3.5.dp.toPx(), mw, style = Stroke(1.3.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText("MW", mw.x + 6.dp.toPx(), mw.y - 5.dp.toPx(), android.graphics.Paint(bold).apply { color = 0xFF1565C0.toInt() })
        }
    }
}

// ---- the tables ----

private val TABLE = FontFamily.Monospace

private fun pad(text: String, width: Int) = text.padStart(width)
private fun metersOrM(z: Double?) = if (z == null) "M" else "${z.roundToInt()}m"
private fun windText(w: SoundingMath.Wind) = "%03.0f/%.0f".format(w.dir, w.kt)

/** SPC's parcel table: a row for the surface, the mixed layer and the most unstable parcel, with their CAPE, CINH, LCL, LI, LFC and EL; a tap picks the one the skew-T draws. */
@Composable
private fun ParcelTable(a: SoundingMath.Analysis, selected: Int, onSelect: (Int) -> Unit) {
    val rows = listOf(a.surface, a.mixed, a.mostUnstable)
    Column(Modifier.padding(top = 10.dp)) {
        Text("PARCEL".padEnd(13) + pad("CAPE", 5) + pad("CINH", 5) + pad("LCL", 7) + pad("LI", 4) + pad("LFC", 7) + pad("EL", 7), fontFamily = TABLE, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        for ((i, p) in rows.withIndex()) {
            val name = if (i == 2) "MU (${p.startP.roundToInt()} mb)" else p.name
            Text(
                name.padEnd(13) + pad("%.0f".format(p.cape), 5) + pad("%.0f".format(p.cin), 5) + pad(metersOrM(p.lclZ), 7) + pad(p.li?.let { "%.0f".format(it) } ?: "M", 4) + pad(metersOrM(p.lfcZ), 7) + pad(metersOrM(p.elZ), 7),
                Modifier.fillMaxWidth().clickable { onSelect(i) }.background(if (i == selected) Color(0x33FF9800) else Color.Transparent).padding(vertical = 3.dp),
                fontFamily = TABLE, fontSize = 11.sp, color = if (i == selected) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** SPC's shear table: helicity, bulk shear, mean wind and storm-relative wind for the layers to 1, 3, 6 and 8 km, and the Bunkers storm motions. */
@Composable
private fun WindTable(k: SoundingMath.Kinematics) {
    Column(Modifier.padding(top = 10.dp)) {
        Text("LAYER".padEnd(12) + pad("SRH", 5) + pad("SHEAR", 6) + pad("MNWIND", 9) + pad("SRW", 9), fontFamily = TABLE, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("SRH m2/s2, shear kt, winds dir/kt", fontFamily = TABLE, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (l in k.layers) Text(l.label.padEnd(12) + pad("%.0f".format(l.srh), 5) + pad("%.0f".format(l.shearKt), 6) + pad(windText(l.mean), 9) + pad(windText(l.stormRelative), 9), fontFamily = TABLE, fontSize = 11.sp)
        Text("Bunkers right = ${windText(k.rightMover)} kt   left = ${windText(k.leftMover)} kt", Modifier.padding(top = 4.dp), fontFamily = TABLE, fontSize = 11.sp)
        Text("0-6 km mean wind = ${windText(k.mean06)} kt", fontFamily = TABLE, fontSize = 11.sp)
    }
}

/** The single numbers of an SPC page: precipitable water, K index, lapse rates, the fixed-layer significant tornado parameter. */
@Composable
private fun IndexTable(a: SoundingMath.Analysis) {
    fun lapse(v: Double?) = v?.let { "%.1f".format(it) } ?: "M"
    Column(Modifier.padding(top = 10.dp)) {
        Text("PW = ${"%.2f".format(a.pwMm / 25.4)} in    K = ${a.kIndex?.let { "%.0f".format(it) } ?: "M"}    STP (fixed) = ${"%.1f".format(a.stp)}", fontFamily = TABLE, fontSize = 11.sp)
        Text("Lapse rates (C/km): sfc-3km agl ${lapse(a.lapse03)}   3-6km ${lapse(a.lapse36)}", Modifier.padding(top = 2.dp), fontFamily = TABLE, fontSize = 11.sp)
        Text("                   850-500mb ${lapse(a.lapse850500)}   700-500mb ${lapse(a.lapse700500)}", fontFamily = TABLE, fontSize = 11.sp)
    }
}
