package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import simplemodelview.MemberRank
import simplemodelview.formatClock

/** Model reflectivity against the radar at the same valid time: side by side, overlaid, or as a difference, with the members ranked by how well their first hours match. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompareScreen(vm: CompareViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { vm.setDark(dark) }
    val background = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(Modifier.fillMaxSize().background(background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Radar vs REFS", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (s.busy) BusySpinner(Modifier.padding(end = 2.dp))
                val context = androidx.compose.ui.platform.LocalContext.current
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var sharing by remember { mutableStateOf(false) }
                var wholeMap by remember { mutableStateOf(false) }
                var withScores by remember { mutableStateOf(true) }
                var making by remember { mutableStateOf(false) }
                TextButton(onClick = { sharing = true }, enabled = !making && (s.model != null || s.tiles.any { it.bitmap != null }), modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (making) "Making…" else "Share") }
                if (sharing) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { sharing = false },
                    title = { Text("Share the comparison") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Area", style = MaterialTheme.typography.labelLarge)
                            for ((whole, name) in listOf(false to "This screen area (every pane the same)", true to "Whole map")) Row(Modifier.fillMaxWidth().clickable { wholeMap = whole }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (wholeMap == whole) "●" else "○", Modifier.padding(end = 12.dp), fontSize = 18.sp)
                                Text(name, fontWeight = if (wholeMap == whole) FontWeight.Bold else FontWeight.Normal)
                            }
                            Row(Modifier.fillMaxWidth().clickable { withScores = !withScores }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Scores under the picture", fontWeight = FontWeight.Bold)
                                    Text("The members shown with their neighborhood scores.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                androidx.compose.material3.Switch(withScores, { withScores = it })
                            }
                            Text("A picture on white: the layout as shown (${when (s.mode) { CompareMode.Side -> "side by side"; CompareMode.Overlay -> "overlay"; CompareMode.Difference -> "difference"; CompareMode.Grid -> "the radar and the best members" }}), at the time shown, with one color key.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            sharing = false; making = true
                            scope.launch {
                                try { vm.buildShareFile(wholeMap, withScores)?.let { shareFile(context, it, "image/png", "Share comparison") } }
                                catch (e: Exception) { android.widget.Toast.makeText(context, "Could not make the picture: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
                                making = false
                            }
                        }) { Text("Picture") }
                    },
                    dismissButton = { TextButton(onClick = { sharing = false }) { Text("Cancel") } },
                )
                TextButton(onClick = vm::load, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Reload") }
                PageMenu("compare", onOpen)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((mode, label) in listOf(CompareMode.Side to "Side by side", CompareMode.Overlay to "Overlay", CompareMode.Difference to "Difference", CompareMode.Grid to "Members"))
                    FilterChip(selected = s.mode == mode, onClick = { vm.setMode(mode) }, label = { Text(label) })
            }
            if (s.mode == CompareMode.Grid) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Best", fontSize = 12.sp)
                val all = s.members.size.coerceAtLeast(1)
                for (n in listOf(1, 2, 3, 5, 8).filter { it < all } + all)
                    FilterChip(selected = s.gridCount.coerceAtMost(all) == n, onClick = { vm.setGridCount(n) }, label = { Text(if (n == all) "All $n" else "$n") })
                FilterChip(selected = s.gridDiff, onClick = { vm.setGridDiff(!s.gridDiff) }, label = { Text("Minus radar") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, t) in s.times.withIndex())
                    FilterChip(selected = i == s.timeIndex, onClick = { vm.pickTime(i) }, label = { Text("f%02d · %s".format(s.hours.getOrElse(i) { 0 }, formatClock(t))) })
            }
            val line = buildString {
                s.run?.let { append("REFS ${it.label}") }
                if (s.mode == CompareMode.Grid) append(" · the radar and ${s.tiles.size - (if (s.gridDiff) 0 else 1)} best members")
                else s.members.getOrNull(s.member)?.let { append(" · $it") }
                if (s.modelTime != null && s.obsTime != null) append(" · radar ${formatClock(s.obsTime!!)} (${(s.obsTime!! - s.modelTime!!) / 60} min from the model)")
            }
            Text(s.error ?: s.status ?: line, fontSize = 12.sp, color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { ComparePanes(s, vm::setViewport) }
            if (s.mode == CompareMode.Overlay) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Model", fontSize = 12.sp); Slider(s.opacity, vm::setOpacity, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)); Text("Radar", fontSize = 12.sp)
            }
            Ranking(s, vm::pickMember)
        }
    }
}

/**
 * The maps: one pan / zoom state drives every pane, so they always show the same place. The side-by-side and member layouts are a grid of equal panes (the finger's
 * position inside whichever pane it is over steers the zoom); the overlay and the difference are one pane.
 */
@Composable
private fun ComparePanes(s: CompareState, onViewport: (Float, Float, Float, Float) -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var pane by remember { mutableStateOf(IntSize.Zero) }
    val mode = s.mode
    val tiled = mode == CompareMode.Side || mode == CompareMode.Grid
    val panes: List<PaneImage> = when (mode) {
        CompareMode.Side -> listOf(PaneImage("Radar", s.obs), PaneImage(s.members.getOrNull(s.member) ?: "REFS", s.model))
        CompareMode.Grid -> s.tiles
        else -> emptyList()
    }
    // the part of the picture on screen (every picture is the same grid, so its shape is fixed), for scoring the members over just that
    LaunchedEffect(scale, offset, pane, s.model, s.tiles.size) {
        if (pane.width == 0 || pane.height == 0) return@LaunchedEffect
        val f = simplemodelview.visibleFractions(pane.width.toFloat(), pane.height.toFloat(), simplemodelview.GRID_COLUMNS.toFloat() / simplemodelview.GRID_ROWS, scale, offset.x, offset.y)
        onViewport(f[0], f[1], f[2], f[3])
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val n = panes.size.coerceAtLeast(1)
        // the number of columns that gives the pictures the most room (they are wide: a tall screen wants few columns and many rows)
        val aspect = simplemodelview.GRID_COLUMNS.toFloat() / simplemodelview.GRID_ROWS
        val cols = if (!tiled) 1 else (1..n).maxByOrNull { c ->
            val r = (n + c - 1) / c
            minOf(maxWidth.value / c, maxHeight.value / r * aspect) + (if (c == 1) 0f else 0.01f)      // (a tie goes to the wider grid)
        } ?: 1
        val rowsCount = (n + cols - 1) / cols
        // the finger's position inside the pane it is over (the panes are the same size, and the transform turns about each pane's middle)
        fun local(p: Offset) = if (!tiled || pane.width == 0 || pane.height == 0) p else Offset(p.x % pane.width, p.y % pane.height)
        val gesture = Modifier.pointerInput(tiled, cols, pane) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val newScale = (scale * zoom).coerceIn(1f, 12f)
                val center = Offset(pane.width / 2f, pane.height / 2f)
                var o = offset + (local(centroid) - center - offset) * (1f - newScale / scale) + pan
                val maxX = pane.width * (newScale - 1f) / 2f; val maxY = pane.height * (newScale - 1f) / 2f
                o = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                scale = newScale; offset = o
            }
        }
        // a double tap zooms in on that spot, or back out
        val doubleTap = Modifier.pointerInput(tiled, cols, pane) {
            detectTapGestures(onDoubleTap = { q ->
                if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else if (pane.width > 0) {
                    val target = 3f
                    val t = (local(q) - Offset(pane.width / 2f, pane.height / 2f)) * (1f - target)
                    val maxX = pane.width * (target - 1f) / 2f; val maxY = pane.height * (target - 1f) / 2f
                    scale = target; offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                }
            })
        }
        @Composable fun Layer(bitmap: Bitmap?, modifier: Modifier = Modifier) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
        }
        @Composable fun Tag(text: String, modifier: Modifier) =
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(4.dp).background(Color(0xAA000000)).padding(horizontal = 4.dp))
        @Composable fun Pane(item: PaneImage, modifier: Modifier) {
            Box(modifier.clipToBounds().onSizeChanged { pane = it }) {
                Layer(item.bitmap)
                if (item.bitmap == null) BusySpinner(Modifier.align(Alignment.Center))
                Tag(item.label, Modifier.align(Alignment.TopStart))
            }
        }
        when {
            tiled -> Column(Modifier.fillMaxSize().then(gesture).then(doubleTap)) {
                for (r in 0 until rowsCount) Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (cIndex in 0 until cols) {
                        val item = panes.getOrNull(r * cols + cIndex)
                        if (item != null) Pane(item, Modifier.weight(1f).fillMaxSize()) else Box(Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
            mode == CompareMode.Overlay -> Box(Modifier.fillMaxSize().then(gesture).then(doubleTap).onSizeChanged { pane = it }) {
                Layer(s.model); Layer(s.obs, Modifier.alpha(s.opacity))
                Tag("REFS under, radar over", Modifier.align(Alignment.TopStart))
            }
            else -> Box(Modifier.fillMaxSize().then(gesture).then(doubleTap).onSizeChanged { pane = it }) {
                Layer(s.diff)
                Tag("REFS minus radar (dBZ)", Modifier.align(Alignment.TopStart))
            }
        }
    }
}

/** The members, best match first. */
@Composable
private fun Ranking(s: CompareState, onPick: (Int) -> Unit) {
    val hours = s.scoredHours
    Column(Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 12.dp)) {
        val area = if (s.inView) " (on screen)" else " (whole map)"
        val progress = s.scored?.let { (done, of) -> if (done < of) "  scoring $done of $of hours…" else "" } ?: if (s.members.isEmpty()) "" else "  scoring…"
        Text("Initial conditions$area, best match first$progress", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("Neighborhood score at 35 dBZ (30 km), f${hours.joinToString("/") { "%02d".format(it) }}; the mean ranks.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.ranks.isEmpty()) return@Column
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(s.ranks) { i, r -> RankRow(i + 1, r, r.index == s.member) { onPick(r.index) } }
        }
    }
}

@Composable
private fun RankRow(place: Int, r: MemberRank, selected: Boolean, onClick: () -> Unit) {
    fun f(v: Double?) = if (v == null || v.isNaN()) "–" else "%.2f".format(v)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("$place", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp).then(Modifier))
        Text(r.name, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.scores.joinToString("  ") { f(it?.fss) }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
        Text(f(r.headline), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
