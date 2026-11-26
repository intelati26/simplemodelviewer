package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A card of the Today page in two sizes: a symbol and a one-line summary (and, where it helps, a small picture), and the details under it when the card is tapped.
 * [key] keeps the card's open state while the screen is rotated or left and come back to; it starts folded unless [startOpen].
 */
@Composable
internal fun ExpandCard(
    key: String, symbol: String, title: String, startOpen: Boolean = false, icon: (@Composable () -> Unit)? = null,
    summary: @Composable ColumnScope.() -> Unit, details: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(36.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) {
                if (icon != null) icon() else Text(symbol, fontSize = 20.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                summary()
            }
            Text(if (open) "▴" else "▾", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { details() }
    }
}

/** The hours of a timing card as a row of small blocks (a block is outlined where it is past the line). */
@Composable
internal fun MiniStrip(cells: List<Pair<Color, Boolean>>, height: Int = 12) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((color, over) in cells) Box(
            Modifier.weight(1f).height(height.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surface).background(color)
                .then(if (over) Modifier.border(BorderStroke(1.dp, Color(0xFFB3261E)), RoundedCornerShape(2.dp)) else Modifier),
        )
    }
}

/** The state borders of the contiguous states as one path in the thumbnail's units (east-west stretched by cos 38 N, north down), built once from the map's own line file. */
private object ThumbBorders {
    const val WEST = -125.0; const val EAST = -66.5; const val SOUTH = 24.5; const val NORTH = 49.5
    val K = Math.cos(Math.toRadians(38.0))
    @Volatile private var path: androidx.compose.ui.graphics.Path? = null
    fun path(context: android.content.Context): androidx.compose.ui.graphics.Path = path ?: synchronized(this) {
        path ?: androidx.compose.ui.graphics.Path().also { p ->
            try {
                val bytes = context.assets.open("statev2.f32").use { it.readBytes() }
                val f = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val a = FloatArray(f.remaining() / 4 * 4).also { f.get(it) }
                var i = 0
                while (i < a.size) {      // lat, west-positive lon, lat, west-positive lon
                    val la = a[i].toDouble(); val lo = -a[i + 1].toDouble(); val lb = a[i + 2].toDouble(); val lp = -a[i + 3].toDouble()
                    if (la in SOUTH..NORTH && lb in SOUTH..NORTH && lo in WEST..EAST && lp in WEST..EAST) {
                        p.moveTo(((lo - WEST) * K).toFloat(), (NORTH - la).toFloat()); p.lineTo(((lp - WEST) * K).toFloat(), (NORTH - lb).toFloat())
                    }
                    i += 4
                }
            } catch (_: Exception) { }
            path = p
        }
    }
}

/**
 * A small picture of an outlook: its areas in the solid colors of the categories (the more severe ones on top) over a plain background, with a dot at the place.
 * Drawn from the shapes already downloaded for the card above it; no map lines, so it is a sketch of where the risk is, not a map to read.
 */
@Composable
internal fun OutlookThumb(shapes: List<simplemodelview.OutlookShape>, lat: Double?, lon: Double?, modifier: Modifier = Modifier) {
    val bg = Color(0xFFEDEAF0)      // a little darker than white, so SPC's pale general-thunder green can be seen
    val edge = MaterialTheme.colorScheme.outline
    val context = androidx.compose.ui.platform.LocalContext.current
    val borders = androidx.compose.runtime.remember { ThumbBorders.path(context) }
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        drawRect(bg)
        val k = ThumbBorders.K
        val west = ThumbBorders.WEST * k; val east = ThumbBorders.EAST * k
        val south = ThumbBorders.SOUTH; val north = ThumbBorders.NORTH
        val sx = size.width / (east - west).toFloat(); val sy = size.height / (north - south).toFloat()
        val scale = minOf(sx, sy)
        val ox = (size.width - (east - west).toFloat() * scale) / 2f; val oy = (size.height - (north - south).toFloat() * scale) / 2f
        fun x(lon: Double) = ox + ((lon * k - west).toFloat()) * scale
        fun y(lat: Double) = oy + ((north - lat).toFloat()) * scale
        for (shape in shapes.filter { !it.hatched }.sortedBy { it.rank }) {
            // general thunderstorms (rank 1) come pale from SPC: a stronger green for a picture this small
            val color = if (shape.rank == 1) Color(0xFF86C986) else Color(0xFF000000.toInt() or shape.color)
            for (polygon in shape.polygons) {
                val path = androidx.compose.ui.graphics.Path().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd }
                for (ring in polygon) {
                    var i = 0
                    while (i + 1 < ring.size) {
                        val px = x(ring[i + 1]); val py = y(ring[i])
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        i += 2
                    }
                    path.close()
                }
                drawPath(path, color)
            }
        }
        withTransform({ translate(ox, oy); scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero) }) {
            drawPath(borders, edge.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.7f / scale))
        }
        drawRect(edge.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        if (lat != null && lon != null) {
            val c = androidx.compose.ui.geometry.Offset(x(lon), y(lat))
            val r = minOf(3.2.dp.toPx(), size.width / 14f)
            drawCircle(Color.White, radius = r, center = c)
            drawCircle(Color.Black, radius = r * 0.62f, center = c)
        }
    }
}
