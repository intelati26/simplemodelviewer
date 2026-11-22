package io.github.intelati26.simplemodelview

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                TextButton(onClick = vm::load, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Reload") }
                PageMenu("compare", onOpen)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((mode, label) in listOf(CompareMode.Side to "Side by side", CompareMode.Overlay to "Overlay", CompareMode.Difference to "Difference"))
                    FilterChip(selected = s.mode == mode, onClick = { vm.setMode(mode) }, label = { Text(label) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, t) in s.times.withIndex())
                    FilterChip(selected = i == s.timeIndex, onClick = { vm.pickTime(i) }, label = { Text("f%02d · %s".format(s.hours.getOrElse(i) { 0 }, formatClock(t))) })
            }
            val line = buildString {
                s.run?.let { append("REFS ${it.label}") }
                s.members.getOrNull(s.member)?.let { append(" · $it") }
                if (s.modelTime != null && s.obsTime != null) append(" · radar ${formatClock(s.obsTime!!)} (${(s.obsTime!! - s.modelTime!!) / 60} min from the model)")
            }
            Text(s.error ?: s.status ?: line, fontSize = 12.sp, color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { ComparePanes(s.mode, s.model, s.obs, s.diff, s.opacity, vm::setViewport) }
            if (s.mode == CompareMode.Overlay) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Model", fontSize = 12.sp); Slider(s.opacity, vm::setOpacity, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)); Text("Radar", fontSize = 12.sp)
            }
            Ranking(s, vm::pickMember)
        }
    }
}

/** The maps: one pan / zoom state drives every pane, so they always show the same place. */
@Composable
private fun ComparePanes(mode: CompareMode, model: Bitmap?, obs: Bitmap?, diff: Bitmap?, opacity: Float, onViewport: (Float, Float, Float, Float) -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var pane by remember { mutableStateOf(IntSize.Zero) }
    // the part of the picture on screen, for scoring the members over just that
    LaunchedEffect(scale, offset, pane, model) {
        val b = model ?: return@LaunchedEffect
        if (pane.width == 0 || pane.height == 0) return@LaunchedEffect
        val f = simplemodelview.visibleFractions(pane.width.toFloat(), pane.height.toFloat(), b.width.toFloat() / b.height, scale, offset.x, offset.y)
        onViewport(f[0], f[1], f[2], f[3])
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val stacked = mode == CompareMode.Side && maxHeight >= maxWidth
        val sideBySide = mode == CompareMode.Side
        // the finger's position inside the pane it is over (the panes are the same size, and the transform turns about each pane's middle)
        val gesture = Modifier.pointerInput(sideBySide, stacked, pane) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val local = when {
                    !sideBySide || pane.width == 0 -> centroid
                    stacked -> Offset(centroid.x, centroid.y % pane.height)
                    else -> Offset(centroid.x % pane.width, centroid.y)
                }
                val newScale = (scale * zoom).coerceIn(1f, 12f)
                val center = Offset(pane.width / 2f, pane.height / 2f)
                var o = offset + (local - center - offset) * (1f - newScale / scale) + pan
                val maxX = pane.width * (newScale - 1f) / 2f; val maxY = pane.height * (newScale - 1f) / 2f
                o = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                scale = newScale; offset = o
            }
        }
        // a double tap zooms in on that spot, or back out
        val doubleTap = Modifier.pointerInput(sideBySide, stacked, pane) {
            detectTapGestures(onDoubleTap = { q ->
                if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else if (pane.width > 0) {
                    val local = when {
                        !sideBySide -> q
                        stacked -> Offset(q.x, q.y % pane.height)
                        else -> Offset(q.x % pane.width, q.y)
                    }
                    val target = 3f
                    val t = (local - Offset(pane.width / 2f, pane.height / 2f)) * (1f - target)
                    val maxX = pane.width * (target - 1f) / 2f; val maxY = pane.height * (target - 1f) / 2f
                    scale = target; offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                }
            })
        }
        @Composable fun Layer(bitmap: Bitmap?, modifier: Modifier = Modifier) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
        }
        @Composable fun Pane(label: String, bitmap: Bitmap?, modifier: Modifier) {
            Box(modifier.clipToBounds().onSizeChanged { pane = it }) {
                Layer(bitmap)
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000)).padding(horizontal = 4.dp), color = Color.White)
            }
        }
        when (mode) {
            CompareMode.Side -> if (stacked) Column(Modifier.fillMaxSize().then(gesture).then(doubleTap)) {
                Pane("Radar", obs, Modifier.weight(1f).fillMaxWidth()); Pane("REFS", model, Modifier.weight(1f).fillMaxWidth())
            } else Row(Modifier.fillMaxSize().then(gesture).then(doubleTap)) {
                Pane("Radar", obs, Modifier.weight(1f).fillMaxSize()); Pane("REFS", model, Modifier.weight(1f).fillMaxSize())
            }
            CompareMode.Overlay -> Box(Modifier.fillMaxSize().then(gesture).then(doubleTap).onSizeChanged { pane = it }) {
                Layer(model, Modifier.alpha(1f)); Layer(obs, Modifier.alpha(opacity))
                Text("REFS under, radar over", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000)).padding(horizontal = 4.dp))
            }
            CompareMode.Difference -> Box(Modifier.fillMaxSize().then(gesture).then(doubleTap).onSizeChanged { pane = it }) {
                Layer(diff)
                Text("REFS minus radar (dBZ)", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xAA000000)).padding(horizontal = 4.dp))
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
        Text("Neighborhood score at 35 dBZ (45 km), f${hours.joinToString("/") { "%02d".format(it) }}; the mean ranks.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
