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
            modifier = Modifier.height(32.dp).widthIn(min = 48.dp).semantics { contentDescription = "Previous time" }, contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text("\u2039", fontSize = 24.sp) }
        TextButton(onClick = onToggle, modifier = Modifier.widthIn(min = 88.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (playing) "Pause" else "Play") }
        TextButton(
            onClick = { onPick(index + 1) }, enabled = index < count - 1,
            modifier = Modifier.height(32.dp).widthIn(min = 48.dp).semantics { contentDescription = "Next time" }, contentPadding = PaddingValues(horizontal = 12.dp),
        ) { Text("\u203A", fontSize = 24.sp) }
        Text("${index + 1} / $count", Modifier.padding(start = 8.dp), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
    }
}
