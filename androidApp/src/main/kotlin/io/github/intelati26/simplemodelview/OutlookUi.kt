package io.github.intelati26.simplemodelview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import simplemodelview.OutlookChoice
import simplemodelview.formatClock

/**
 * "Outlook: off", or the chosen SPC outlook with the times it is valid for, the areas it has (a color key) and a note when it could not be read.
 * The same outlook is drawn on every page; tapping the map adds what it says at that point to the read-out.
 */
@Composable
internal fun OutlookRow(state: OutlookState, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            val choice = state.choice
            val data = state.data
            val now = System.currentTimeMillis() / 1000
            Text(
                if (choice == null) "SPC outlook: off" else
                    "SPC outlook: ${choice.label}" + (data?.valid?.let { v -> " · valid ${formatClock(v, now)}" + (data?.expires?.let { e -> " to ${formatClock(e, now)}" } ?: "") } ?: "") +
                        if (state.busy) " · reading…" else "",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val shapes = state.data?.shapes.orEmpty().filter { !it.hatched }.distinctBy { it.title }
        if (shapes.isNotEmpty()) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (shape in shapes) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Color(0xFF000000.toInt() or shape.color)))
                    Text(" " + shape.title, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        state.note?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** The outlooks to choose from: none, or a day (1-3) and what it shows, or one of days 4-8. */
@Composable
internal fun OutlookDialog(current: OutlookChoice?, onPick: (OutlookChoice?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("SPC outlook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun row(title: String, selected: Boolean, onClick: () -> Unit) {
                    Text(title, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
                    HorizontalDivider()
                }
                row("No outlook", current == null) { onPick(null) }
                for (day in 1..8) {
                    val choices = OutlookChoice.all.filter { it.day == day }
                    if (day <= 3) {
                        Text("Day $day", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        for (c in choices) row(c.type.label, c == current) { onPick(c) }
                    } else {
                        if (day == 4) Text("Days 4 to 8", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        for (c in choices) row("Day $day: chance of severe weather", c == current) { onPick(c) }
                    }
                }
                Text(
                    "Areas the Storm Prediction Center outlines for severe weather on that day (categorical risk, or the chance within 25 miles of a point). " +
                        "Drawn over the map on every page, in exports too. Unofficial copy: use spc.noaa.gov for the real outlooks.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * "LightningCast: off", or on with the scan shown and a color key of its contours: the chance of lightning in the next hour from GOES-East (NOAA and CIMSS),
 * lines at 10, 30, 50, 70 and 90 percent. Tapping switches it on or off; it is drawn on every page.
 */
@Composable
internal fun LightningCastRow(state: LightningState, onToggle: () -> Unit) {
    val frame = simplemodelview.lightningFrameFor(state.frames, null)
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(
                if (!state.enabled) "LightningCast: off" else
                    "LightningCast: lightning chance, next 60 min" + (frame?.let { " · scan ${formatClock(it.start)}" } ?: "") + if (state.busy) " · reading…" else "",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val levels = frame?.contours.orEmpty().distinctBy { it.percent }.sortedBy { it.percent }
        if (state.enabled && levels.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in levels) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 14.dp, height = 3.dp).background(Color(0xFF000000.toInt() or c.color)))
                    Text(" ${c.percent}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (state.enabled) state.note?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}
