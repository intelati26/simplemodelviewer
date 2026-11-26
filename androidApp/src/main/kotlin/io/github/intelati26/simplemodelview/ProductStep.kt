package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small arrow beside the product chip that steps to the previous (‹) or next (›) product in the picker's order. */
@Composable
internal fun ProductStepButton(next: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.height(32.dp).widthIn(min = 32.dp).semantics { contentDescription = if (next) "Next product" else "Previous product" },
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) { Text(if (next) "›" else "‹", fontSize = 24.sp) }
}

/** A line under the timeline: back (‹), Play / Pause and forward (›) one time step, with the place in the list ("12 / 48"). Used by the model pages and MRMS. */
@Composable
internal fun TimeStepRow(index: Int, count: Int, playing: Boolean, onToggle: () -> Unit, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onPick(index - 1) }, enabled = index > 0,
            modifier = Modifier.height(32.dp).widthIn(min = 40.dp).semantics { contentDescription = "Previous time" }, contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text("\u2039", fontSize = 24.sp) }
        TextButton(
            onClick = onToggle, modifier = Modifier.height(32.dp).widthIn(min = 56.dp).semantics { contentDescription = if (playing) "Pause" else "Play" }, contentPadding = PaddingValues(horizontal = 10.dp),
        ) { Text(if (playing) "\u275A\u275A" else "\u25B6", fontSize = 18.sp) }
        TextButton(
            onClick = { onPick(index + 1) }, enabled = index < count - 1,
            modifier = Modifier.height(32.dp).widthIn(min = 40.dp).semantics { contentDescription = "Next time" }, contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text("\u203A", fontSize = 24.sp) }
        Text("${index + 1} / $count", Modifier.padding(start = 8.dp), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    }
}

/** A slider that takes [height] of the screen instead of the 48 dp a Material slider reserves (its track and handle are drawn in the middle of that and kept). */
internal fun Modifier.compactSlider(height: androidx.compose.ui.unit.Dp = 26.dp): Modifier = this.then(Modifier.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val h = height.roundToPx().coerceAtMost(p.height)
    layout(p.width, h) { p.place(0, -(p.height - h) / 2) }
})

/** The time and level sliders: a slim handle and track in a shorter row than the Material default. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun CompactSlider(
    value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
    steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
) {
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.material3.Slider(
        value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.compactSlider(),
        interactionSource = source,
        thumb = { androidx.compose.material3.SliderDefaults.Thumb(source, thumbSize = androidx.compose.ui.unit.DpSize(5.dp, 20.dp)) },
        track = { st -> androidx.compose.material3.SliderDefaults.Track(st, Modifier.height(6.dp), drawStopIndicator = null, thumbTrackGapSize = 2.dp, trackInsideCornerSize = 2.dp) },
    )
}
