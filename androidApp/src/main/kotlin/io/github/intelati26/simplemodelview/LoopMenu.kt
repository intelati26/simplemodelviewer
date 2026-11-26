package io.github.intelati26.simplemodelview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import simplemodelview.loopLengthOptions
import simplemodelview.loopRangeSummary
import simplemodelview.loopSpanLabel
import simplemodelview.loopSpanOptions
import simplemodelview.loopTimesFor

/** "Loop: 12" / "Loop: all": how many frames Play (and a loop video) covers; 0 = all [total] frames. */
@Composable
internal fun LoopLengthMenu(length: Int, total: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(if (length <= 0 || length >= total) "Loop: all" else "Loop: $length")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (n in loopLengthOptions(total)) DropdownMenuItem(text = { Text(if (n == 0) "All $total frames" else "$n frames") }, onClick = { open = false; onPick(n) })
            HorizontalDivider()
            LoopStartItem { open = false }
        }
    }
}

/** A checkbox row of every loop menu: the loop starts at the frame on screen (and covers the chosen number of steps after it) instead of the page's usual start. */
@Composable
internal fun LoopStartItem(onDone: () -> Unit) {
    val on by AppContainer.loopFromShown.collectAsStateWithLifecycle()
    DropdownMenuItem(text = { Text((if (on) "\u2713 " else "") + "Start at the frame shown") }, onClick = { AppContainer.setLoopFromShown(!on); onDone() })
}

/** Whether the loop's range bar is open (kept for every page; folded away by default). */
internal object LoopBar {
    var open by mutableStateOf(AppContainer.prefs.getBoolean("loop_bar_open", false))
    fun toggle() { open = !open; AppContainer.prefs.edit().putBoolean("loop_bar_open", open).apply() }
}

/** The small Loop button on the line with the arrows: opens and folds the range bar above it. */
@Composable
internal fun LoopToggle() {
    TextButton(onClick = LoopBar::toggle, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("Loop " + if (LoopBar.open) "\u25BE" else "\u25B4", fontSize = 12.sp)
    }
}

/**
 * The loop on the time bar, the same on every page: a two-handle bar over the frames (drag either end), what it covers in words (the times, the number of frames and
 * how long they span: what Play and a loop video will use), and spans in time that are turned into frames for the page ("3 h up to the frame shown"). [windowFor]
 * gives the frames the loop covers now, from the page's own rules when nothing was chosen here; [onRange] gets the range as two valid times, [onAll] goes back to the whole series.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoopRangeBar(times: List<Long>, windowFor: () -> IntRange, shown: Int, explicit: Boolean, onRange: (Pair<Long, Long>) -> Unit, onAll: () -> Unit) {
    if (times.size < 3) return
    val fromShown by AppContainer.loopFromShown.collectAsStateWithLifecycle()    // (read, so a change of "start at the frame shown" redraws the summary)
    val window = windowFor().also { if (fromShown) Unit }
    if (window.isEmpty()) return
    val n = times.size
    var menu by remember { mutableStateOf(false) }
    if (!LoopBar.open) return                    // folded away unless opened from the Loop button on the line with the arrows
    val open = true
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Loop  " + loopRangeSummary(times, window), Modifier.weight(1f), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (open) Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Span \u25BE", fontSize = 12.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val spans = loopSpanOptions(times)
                    DropdownMenuItem(text = { Text("Up to the frame shown", style = MaterialTheme.typography.labelSmall) }, onClick = {}, enabled = false)
                    for (sp in spans) DropdownMenuItem(text = { Text(loopSpanLabel(sp)) }, onClick = { menu = false; loopTimesFor(times, shown, sp, endingAtShown = true)?.let(onRange) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("From the frame shown", style = MaterialTheme.typography.labelSmall) }, onClick = {}, enabled = false)
                    for (sp in spans) DropdownMenuItem(text = { Text(loopSpanLabel(sp)) }, onClick = { menu = false; loopTimesFor(times, shown, sp, endingAtShown = false)?.let(onRange) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("All $n frames") }, onClick = { menu = false; onAll() })
                }
            }
        }
        val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val ends = androidx.compose.material3.SliderDefaults.colors()      // the same colors as the time slider
        if (open) RangeSlider(
            modifier = Modifier.compactSlider(),
            startThumb = { androidx.compose.material3.SliderDefaults.Thumb(source, colors = ends, thumbSize = androidx.compose.ui.unit.DpSize(5.dp, 20.dp)) },
            endThumb = { androidx.compose.material3.SliderDefaults.Thumb(source, colors = ends, thumbSize = androidx.compose.ui.unit.DpSize(5.dp, 20.dp)) },
            track = { st -> androidx.compose.material3.SliderDefaults.Track(st, Modifier.height(6.dp), colors = ends, drawStopIndicator = null, thumbTrackGapSize = 2.dp, trackInsideCornerSize = 2.dp) },
            value = window.first.toFloat()..window.last.toFloat(),
            onValueChange = { r ->
                val a = Math.round(r.start).coerceIn(0, n - 2)
                val b = Math.round(r.endInclusive).coerceIn(a + 1, n - 1)
                if (a != window.first || b != window.last) onRange(times[a] to times[b])
            },
            valueRange = 0f..(n - 1).toFloat(),
        )
    }
}
