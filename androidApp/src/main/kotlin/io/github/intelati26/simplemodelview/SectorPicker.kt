package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import simplemodelview.Sector

/** The short mark on a region's dot: compass letters for the corners (NW, NE, SW, SE, MW), else the initials ("Great Lakes" is GL, "Intermountain West" IW). */
internal fun sectorMark(name: String): String = when (name) {
    "Northwest" -> "NW"; "Northeast" -> "NE"; "Southwest" -> "SW"; "Southeast" -> "SE"; "Midwest" -> "MW"
    else -> name.split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.uppercase().take(3)
}

/**
 * The area chooser: a small map of the country with a dot on each region, a tap on a dot picks it and its box is outlined; under it the same choices
 * as chips with their names (and the globe, and the map of what the globe shows). Replaces a long list of names.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SectorPicker(
    current: String, sectors: List<Sector>, onGlobe: (() -> Unit)?, onMapHere: (() -> Unit)?, onPick: (Sector) -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val borders = remember { ThumbBorders.path(context) }
    val regions = sectors.filter { it.name != "CONUS" }
    val edge = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val land = MaterialTheme.colorScheme.surfaceVariant
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val tertiary = MaterialTheme.colorScheme.tertiary
    var preview by remember { mutableStateOf<Sector?>(null) }       // the region under the finger: its box is outlined until the finger lifts (lifting picks it)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Area") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // the map: x is longitude scaled by the cosine of a middle latitude (as the borders are), y is latitude
                val k = ThumbBorders.K
                val west = ThumbBorders.WEST * k; val east = ThumbBorders.EAST * k
                val south = ThumbBorders.SOUTH; val north = ThumbBorders.NORTH
                val aspect = ((east - west) / (north - south)).toFloat()
                fun centerOf(s: Sector) = (s.west + s.east) / 2 to (s.south + s.north) / 2
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "Map of the regions: tap a dot to choose it" }
                        .pointerInput(regions) {
                            val reach = 40.dp.toPx()
                            fun nearest(p: Offset): Sector? {
                                val scale = size.width / (east - west).toFloat()
                                val hit = regions.minByOrNull { r ->
                                    val (lo, la) = centerOf(r)
                                    val dx = ((lo * k - west).toFloat()) * scale - p.x; val dy = ((north - la).toFloat()) * scale - p.y
                                    dx * dx + dy * dy
                                } ?: return null
                                val (lo, la) = centerOf(hit)
                                val dx = ((lo * k - west).toFloat()) * scale - p.x; val dy = ((north - la).toFloat()) * scale - p.y
                                return hit.takeIf { kotlin.math.sqrt(dx * dx + dy * dy) <= reach }
                            }
                            // touch and slide over the dots to see each box; lift on one to choose it (a plain tap does the same)
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    preview = nearest(down.position)
                                    var last = preview
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val ch = e.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        last = nearest(ch.position)
                                        preview = last
                                        ch.consume()
                                    }
                                    val pick = preview
                                    preview = null
                                    if (pick != null) onPick(pick)
                                }
                            }
                        },
                ) {
                    drawRect(land.copy(alpha = 0.55f))
                    val scale = size.width / (east - west).toFloat()
                    fun x(lon: Double) = ((lon * k - west).toFloat()) * scale
                    fun y(lat: Double) = ((north - lat).toFloat()) * scale
                    withTransform({ scale(scale, scale, Offset.Zero) }) {
                        drawPath(borders, edge.copy(alpha = 0.6f), style = Stroke(0.8f / scale))
                    }
                    // the chosen region's box, under the dots
                    regions.firstOrNull { it.name == current }?.let { s ->
                        drawRect(accent.copy(alpha = 0.18f), Offset(x(s.west), y(s.north)), androidx.compose.ui.geometry.Size(x(s.east) - x(s.west), y(s.south) - y(s.north)))
                        drawRect(accent, Offset(x(s.west), y(s.north)), androidx.compose.ui.geometry.Size(x(s.east) - x(s.west), y(s.south) - y(s.north)), style = Stroke(2.dp.toPx()))
                    }
                    preview?.let { s ->
                        val tl = Offset(x(s.west), y(s.north)); val sz = androidx.compose.ui.geometry.Size(x(s.east) - x(s.west), y(s.south) - y(s.north))
                        drawRect(tertiary.copy(alpha = 0.22f), tl, sz)
                        drawRect(tertiary, tl, sz, style = Stroke(2.5.dp.toPx()))
                        val tag = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.dp.toPx(); isFakeBoldText = true; color = tertiary.toArgb() }
                        drawContext.canvas.nativeCanvas.drawText(s.name, 8.dp.toPx(), size.height - 8.dp.toPx(), tag)
                    }
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 9.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; color = onAccent.toArgb()
                    }
                    for (s in regions) {
                        val (lo, la) = centerOf(s)
                        val c = Offset(x(lo), y(la))
                        val chosen = s.name == current
                        drawCircle(Color.White, 12.dp.toPx(), c)
                        drawCircle(if (chosen) accent else accent.copy(alpha = 0.78f), 10.dp.toPx(), c)
                        drawContext.canvas.nativeCanvas.drawText(sectorMark(s.name), c.x, c.y + 3.dp.toPx(), paint)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (onMapHere != null) FilterChip(false, { onMapHere() }, { Text("Map of this view") })
                    if (onGlobe != null) FilterChip(current == "Globe", { onGlobe() }, { Text("Globe") })
                    if (sectors.any { it.name == "CONUS" }) FilterChip(current == "CONUS", { onPick(sectors.first { it.name == "CONUS" }) }, { Text("CONUS") })
                    for (s in regions) FilterChip(current == s.name, { onPick(s) }, { Text(s.name) })
                }
            }
        },
    )
}
