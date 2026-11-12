package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import simplemodelview.NbmTextElements
import simplemodelview.PointTable
import simplemodelview.pointDays
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The graphs of the extended view. Each draws one point per column of the table, the columns of equal width (the spacing of the times changes where the
 * sources change: 3-hourly, then 12-hourly) so the graphs and the table underneath line up. Tapping a graph picks the column under the finger.
 */
private class Chart(val table: PointTable, val colPx: Float, val selected: Int?, val ink: Color, val faint: Color, val highlight: Color)

private fun DrawScope.frame(c: Chart) {
    c.selected?.let { drawRect(c.highlight, Offset(it * c.colPx, 0f), Size(c.colPx, size.height)) }
    for ((i, _) in pointDays(c.table)) drawLine(c.faint, Offset(i * c.colPx, 0f), Offset(i * c.colPx, size.height), strokeWidth = 1f)
}

private fun x(c: Chart, i: Int) = (i + 0.5f) * c.colPx

@Composable
private fun ChartBox(table: PointTable, colW: Dp, height: Dp, selected: Int?, onPick: (Int) -> Unit, draw: DrawScope.(Chart) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = ink.copy(alpha = 0.18f)
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    Canvas(
        Modifier.width(colW * table.columns.size).height(height).pointerInput(table, colW) {
            detectTapGestures { p -> onPick(min(table.columns.size - 1, max(0, (p.x / colW.toPx()).toInt()))) }
        },
    ) {
        val c = Chart(table, colW.toPx(), selected, ink, faint, highlight)
        frame(c)
        draw(c)
    }
}

/** Temperature (the line), dewpoint (the dashed line) and the temperature's spread (a band of one standard deviation each side: it widens with the lead time). */
@Composable
internal fun TemperatureChart(table: PointTable, colW: Dp, height: Dp, selected: Int?, onPick: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val hot = Color(0xFFD84315)
    val cool = Color(0xFF2E7D32)
    ChartBox(table, colW, height, selected, onPick) { c ->
        val temps = table.columns.map { it.value(NbmTextElements.TEMP) }
        val dews = table.columns.map { it.value(NbmTextElements.DEW) }
        val spread = table.columns.map { it.value(NbmTextElements.TEMP_SPREAD) ?: 0 }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        temps.forEachIndexed { i, t -> if (t != null) { lo = min(lo, t - spread[i].toFloat()); hi = max(hi, t + spread[i].toFloat()) } }
        dews.forEachIndexed { i, d -> if (d != null) { lo = min(lo, d.toFloat()); hi = max(hi, d.toFloat()) } }
        if (lo > hi) return@ChartBox
        lo = floor(lo / 10f) * 10f - 0f
        hi = ceil(hi / 10f) * 10f
        if (hi - lo < 20f) hi = lo + 20f
        val top = 8f
        val bottom = size.height - 8f
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        var level = lo
        while (level <= hi) {
            drawLine(c.faint, Offset(0f, y(level)), Offset(size.width, y(level)), strokeWidth = 1f)
            drawText(measurer, level.toInt().toString(), Offset(3f, y(level) - 14f), TextStyle(fontSize = 9.sp, color = c.ink.copy(alpha = 0.6f)))
            level += 10f
        }
        if (32f in lo..hi) drawLine(Color(0xFF1E88E5), Offset(0f, y(32f)), Offset(size.width, y(32f)), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        // the spread: a band where the columns have one (the gridded forecast's do not), in runs
        var run = ArrayList<Int>()
        fun band() {
            if (run.size >= 2) {
                val p = Path()
                run.forEachIndexed { k, i -> val v = y(temps[i]!! + spread[i].toFloat()); if (k == 0) p.moveTo(x(c, i), v) else p.lineTo(x(c, i), v) }
                run.asReversed().forEach { i -> p.lineTo(x(c, i), y(temps[i]!! - spread[i].toFloat())) }
                p.close()
                drawPath(p, hot.copy(alpha = 0.18f))
            }
            run = ArrayList()
        }
        table.columns.forEachIndexed { i, col -> if (temps[i] != null && col.value(NbmTextElements.TEMP_SPREAD) != null) run.add(i) else band() }
        band()
        fun line(values: List<Int?>, color: Color, dashed: Boolean) {
            val p = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                if (!started) { p.moveTo(x(c, i), y(v.toFloat())); started = true } else p.lineTo(x(c, i), y(v.toFloat()))
            }
            drawPath(p, color, style = Stroke(width = 3f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null))
        }
        line(dews, cool, true)
        line(temps, hot, false)
    }
}

/** Wind speed (the line), gust (the dashed line) and, along the bottom, an arrow for each column pointing the way the wind blows. */
@Composable
internal fun WindChart(table: PointTable, colW: Dp, height: Dp, selected: Int?, onPick: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val speedColor = Color(0xFF1565C0)
    val gustColor = Color(0xFF6A1B9A)
    ChartBox(table, colW, height, selected, onPick) { c ->
        val speeds = table.columns.map { it.value(NbmTextElements.WIND) }
        val gusts = table.columns.map { it.value(NbmTextElements.GUST) }
        val hi = max(10f, ceil((gusts + speeds).filterNotNull().maxOrNull()?.toFloat()?.div(10f) ?: 1f) * 10f)
        val arrows = 22f
        val top = 6f
        val bottom = size.height - arrows - 4f
        fun y(v: Float) = bottom - v / hi * (bottom - top)
        var level = 0f
        while (level <= hi) {
            drawLine(c.faint, Offset(0f, y(level)), Offset(size.width, y(level)), strokeWidth = 1f)
            drawText(measurer, level.toInt().toString(), Offset(3f, y(level) - 14f), TextStyle(fontSize = 9.sp, color = c.ink.copy(alpha = 0.6f)))
            level += 10f
        }
        fun line(values: List<Int?>, color: Color, dashed: Boolean) {
            val p = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                if (!started) { p.moveTo(x(c, i), y(v.toFloat())); started = true } else p.lineTo(x(c, i), y(v.toFloat()))
            }
            drawPath(p, color, style = Stroke(width = 3f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null))
        }
        line(gusts, gustColor, true)
        line(speeds, speedColor, false)
        // the direction is the one the wind comes FROM (tens of degrees, 36 = north): the arrow points the other way
        table.columns.forEachIndexed { i, col ->
            val d = col.value(NbmTextElements.WIND_DIR) ?: return@forEachIndexed
            if ((col.value(NbmTextElements.WIND) ?: 0) == 0) return@forEachIndexed
            val to = ((d * 10 + 180) % 360) * PI / 180.0
            val cx = x(c, i)
            val cy = size.height - arrows / 2f - 2f
            val dx = (sin(to) * arrows * 0.42).toFloat()
            val dy = (-cos(to) * arrows * 0.42).toFloat()
            val tail = Offset(cx - dx, cy - dy)
            val head = Offset(cx + dx, cy + dy)
            drawLine(c.ink, tail, head, strokeWidth = 2.5f)
            val left = to + 2.6
            val right = to - 2.6
            drawLine(c.ink, head, Offset(head.x + (sin(left) * 8).toFloat(), head.y - (cos(left) * 8).toFloat()), strokeWidth = 2.5f)
            drawLine(c.ink, head, Offset(head.x + (sin(right) * 8).toFloat(), head.y - (cos(right) * 8).toFloat()), strokeWidth = 2.5f)
        }
    }
}

/** The chance of precipitation as a bar for each column (12-hour chances darker, the short-range bulletin's 6-hour ones lighter), with rain, snow and ice as the table below says. */
@Composable
internal fun PrecipChart(table: PointTable, colW: Dp, height: Dp, selected: Int?, onPick: (Int) -> Unit) {
    val rain = Color(0xFF2E7D32)
    val snow = Color(0xFF1E88E5)
    val measurer = rememberTextMeasurer()
    ChartBox(table, colW, height, selected, onPick) { c ->
        val top = 6f
        val bottom = size.height - 4f
        for (level in listOf(0, 50, 100)) {
            val y = bottom - level / 100f * (bottom - top)
            drawLine(c.faint, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            drawText(measurer, "$level", Offset(3f, y - 14f), TextStyle(fontSize = 9.sp, color = c.ink.copy(alpha = 0.6f)))
        }
        table.columns.forEachIndexed { i, col ->
            val p12 = col.value(NbmTextElements.POP12)
            val p06 = col.value("P06")
            val p = p12 ?: p06 ?: return@forEachIndexed
            // snow where the precipitation is mostly snow, rain otherwise
            val color = if ((col.value(NbmTextElements.SNOW) ?: 0) > 50) snow else rain
            val h = p / 100f * (bottom - top)
            drawRect(color.copy(alpha = if (p12 != null) 0.85f else 0.45f), Offset(x(c, i) - c.colPx * 0.32f, bottom - h), Size(c.colPx * 0.64f, h))
        }
    }
}
