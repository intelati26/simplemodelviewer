package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import simplemodelview.FiveMinute
import simplemodelview.Metar
import simplemodelview.formatClock

/** A tapped station: its newest report as sent, then a chart of its last day (temperature and dewpoint, wind and gusts) and its reports. */
@Composable
internal fun StationDialog(detail: StationDetail, celsius: Boolean, onDismiss: () -> Unit) {
    val m = detail.metar
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(m.id + (m.category?.let { "  ·  $it" } ?: "")) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text(m.raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Observed ${formatClock(m.time)}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val history = detail.history
                when {
                    detail.error != null -> Text(detail.error, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                    history == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    history.isEmpty() -> Text("No reports in the last day.", Modifier.padding(top = 8.dp))
                    else -> {
                        Text("Last ${((history.last().time - history.first().time) / 3600 + 1)} h  ·  " + (if (celsius) "°C" else "°F") + "  ·  wind kt", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium)
                        StationChart(history, detail.fiveMinute.orEmpty(), celsius)
                        Row(Modifier.padding(top = 2.dp)) {
                            Text("— temperature ", color = Color(0xFFC62828), style = MaterialTheme.typography.labelSmall)
                            Text("— dewpoint ", color = Color(0xFF1B7A2E), style = MaterialTheme.typography.labelSmall)
                            Text("— wind ", color = Color(0xFF1F5FBF), style = MaterialTheme.typography.labelSmall)
                            Text("· gust", color = Color(0xFF6A3D9A), style = MaterialTheme.typography.labelSmall)
                        }
                        if (detail.fiveMinute == null) Text("Reading the 5-minute wind…", style = MaterialTheme.typography.labelSmall)
                        else if (detail.fiveMinute.isNotEmpty()) Text("Thin wind line: every 5 minutes (Iowa Environmental Mesonet)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Reports", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium)
                        for (r in history.asReversed()) Text(r.raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        },
    )
}

/** Temperature and dewpoint above, wind (and gusts as dots) below, over the span of the reports; the 5-minute wind as a thin line. */
@Composable
private fun StationChart(history: List<Metar>, five: List<FiveMinute>, celsius: Boolean) {
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val t0 = history.first().time.toFloat()
    val t1 = maxOf(history.last().time.toFloat(), t0 + 3600f)
    // the cursor: touch, drag or hover on the chart puts a line at that time and the nearest report's numbers above it
    var cursor by remember { mutableStateOf<Long?>(null) }
    val unit = if (celsius) "°C" else "°F"
    fun conv(c: Double) = if (celsius) c else c * 1.8 + 32.0
    Column {
        val c = cursor
        if (c == null) { Text("Touch or drag on the chart to read the observations", style = MaterialTheme.typography.labelMedium, color = axis); Text(" ", style = MaterialTheme.typography.labelSmall) }
        else {
            val r = history.minByOrNull { Math.abs(it.time - c) }!!
            val f = five.filter { it.windKt != null }.minByOrNull { Math.abs(it.time - c) }?.takeIf { Math.abs(it.time - c) <= 450 }
            Text(
                formatClock(r.time) + "  ·  " + listOfNotNull(
                    r.tempC?.let { "%.0f%s".format(conv(it), unit) }, r.dewC?.let { "dew %.0f".format(conv(it)) },
                    r.windKt?.let { w -> "wind $w kt" + (r.gustKt?.let { " g$it" } ?: "") },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(if (f != null) "5-min " + formatClock(f.time) + "  ·  wind %.0f kt".format(f.windKt) + (f.gustKt?.let { " g%.0f".format(it) } ?: "") else " ", style = MaterialTheme.typography.labelSmall, color = axis)
        }
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).padding(top = 6.dp).pointerInput(Unit) {
            // (not consumed, so the dialog still scrolls up and down)
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull() ?: continue
                    val left = 34.dp.toPx()
                    if (ch.pressed || e.type == PointerEventType.Move || e.type == PointerEventType.Press) {
                        val frac = ((ch.position.x - left) / (size.width - left)).coerceIn(0f, 1f)
                        cursor = (t0 + frac * (t1 - t0)).toLong()
                    }
                }
            }
        },
    ) {
        val temps = history.flatMap { listOfNotNull(it.tempC?.let(::conv), it.dewC?.let(::conv)) }
        val lo = (temps.minOrNull() ?: 0.0) - 2.0
        val hi = (temps.maxOrNull() ?: 1.0) + 2.0
        val winds = history.flatMap { listOfNotNull(it.windKt?.toDouble(), it.gustKt?.toDouble()) } + five.mapNotNull { it.windKt } + five.mapNotNull { it.gustKt }
        val wmax = maxOf(10.0, winds.maxOrNull() ?: 10.0)
        val top = size.height * 0.62f
        val left = 34.dp.toPx()
        fun x(t: Long) = left + (t - t0) / (t1 - t0) * (size.width - left)
        fun yT(v: Double) = ((hi - v) / (hi - lo) * top).toFloat()
        fun yW(v: Double) = (top + 10.dp.toPx() + (1 - v / wmax) * (size.height - top - 10.dp.toPx())).toFloat()
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.dp.toPx(); color = android.graphics.Color.GRAY }
        // grid: every 6 hours, and the temperature and wind ranges
        var h = (t0.toLong() / 21600 + 1) * 21600
        while (h < t1) {
            drawLine(axis.copy(alpha = 0.25f), Offset(x(h), 0f), Offset(x(h), size.height))
            drawContext.canvas.nativeCanvas.drawText(formatClock(h).takeLast(6), x(h) + 2f, size.height - 2f, label)
            h += 21600
        }
        drawContext.canvas.nativeCanvas.drawText("%.0f".format(hi - 2), 0f, 10.dp.toPx(), label)
        drawContext.canvas.nativeCanvas.drawText("%.0f".format(lo + 2), 0f, top, label)
        drawContext.canvas.nativeCanvas.drawText("%.0f kt".format(wmax), 0f, top + 20.dp.toPx(), label)
        drawLine(axis.copy(alpha = 0.4f), Offset(left, top + 5.dp.toPx()), Offset(size.width, top + 5.dp.toPx()))
        fun DrawScope.series(points: List<Pair<Float, Float>>, color: Color, width: Float, dots: Boolean = false) {
            if (dots) { points.forEach { drawCircle(color, width, Offset(it.first, it.second)) }; return }
            if (points.size < 2) { points.forEach { drawCircle(color, width, Offset(it.first, it.second)) }; return }
            val p = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first, it.second) } }
            drawPath(p, color, style = Stroke(width))
        }
        series(five.filter { it.windKt != null }.map { x(it.time) to yW(it.windKt!!) }, Color(0x991F5FBF), 1.dp.toPx())
        series(history.mapNotNull { r -> r.tempC?.let { x(r.time) to yT(conv(it)) } }, Color(0xFFC62828), 2.dp.toPx())
        series(history.mapNotNull { r -> r.dewC?.let { x(r.time) to yT(conv(it)) } }, Color(0xFF1B7A2E), 2.dp.toPx())
        series(history.mapNotNull { r -> r.windKt?.let { x(r.time) to yW(it.toDouble()) } }, Color(0xFF1F5FBF), 2.dp.toPx())
        series(history.mapNotNull { r -> r.gustKt?.let { x(r.time) to yW(it.toDouble()) } } + five.mapNotNull { r -> r.gustKt?.let { x(r.time) to yW(it) } }, Color(0xFF6A3D9A), 2.5.dp.toPx(), dots = true)
        // the cursor line, and a ring on the nearest report's points
        cursor?.let { c ->
            val r = history.minByOrNull { Math.abs(it.time - c) }!!
            drawLine(axis.copy(alpha = 0.8f), Offset(x(c), 0f), Offset(x(c), size.height), strokeWidth = 1.dp.toPx())
            fun ring(y: Float?, color: Color) { if (y != null) { drawCircle(Color.White, 4.5.dp.toPx(), Offset(x(r.time), y)); drawCircle(color, 3.dp.toPx(), Offset(x(r.time), y)) } }
            ring(r.tempC?.let { yT(conv(it)) }, Color(0xFFC62828)); ring(r.dewC?.let { yT(conv(it)) }, Color(0xFF1B7A2E))
            ring(r.windKt?.let { yW(it.toDouble()) }, Color(0xFF1F5FBF)); ring(r.gustKt?.let { yW(it.toDouble()) }, Color(0xFF6A3D9A))
        }
    }
    }
}
