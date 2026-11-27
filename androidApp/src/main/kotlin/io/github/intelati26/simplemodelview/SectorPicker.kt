package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
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
                            detectTapGestures { p ->
                                val scale = size.width / (east - west).toFloat()
                                val hit = regions.minByOrNull { s ->
                                    val (lo, la) = centerOf(s)
                                    val dx = ((lo * k - west).toFloat()) * scale - p.x; val dy = ((north - la).toFloat()) * scale - p.y
                                    dx * dx + dy * dy
                                }
                                if (hit != null) {
                                    val (lo, la) = centerOf(hit)
                                    val dx = ((lo * k - west).toFloat()) * scale - p.x; val dy = ((north - la).toFloat()) * scale - p.y
                                    if (kotlin.math.sqrt(dx * dx + dy * dy) <= 40.dp.toPx()) onPick(hit)
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
