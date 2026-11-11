package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import simplemodelview.formatUtc

/**
 * "Time": a drop list of every valid time of the model run (grouped by day, the shown one in bold and scrolled into view) to jump straight to one,
 * instead of dragging the slider to it. [times] are epoch seconds; [label] is the line for frame [i].
 */
@Composable
fun TimeMenu(times: List<Long>, selected: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Time ▾") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, scrollState = scroll) {
        var lastDay = ""
        times.forEachIndexed { i, t ->
            val day = formatUtc(t).substringBeforeLast(' ')            // "Thu 15 Oct"
            if (day != lastDay) {
                if (lastDay.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(text = { Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }, onClick = {}, enabled = false)
                lastDay = day
            }
            DropdownMenuItem(
                text = { Text(label(i), fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal) },
                onClick = { open = false; onPick(i) },
            )
        }
    }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        // about 48 dp an entry (a day header counts as one): put the shown time a little below the top
        val days = times.take(selected + 1).map { formatUtc(it).substringBeforeLast(' ') }.distinct().size
        scroll.scrollTo(with(density) { (48.dp * maxOf(0, selected + days - 3)).roundToPx() })
    }
}
