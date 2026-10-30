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
import simplemodelview.OutlookType
import simplemodelview.outlookChoiceFor
import simplemodelview.formatClock

/**
 * "SPC outlook: off", or the kind chosen with the day it is for the frame on screen ([valid]: Day 2 for a frame two days out), the times that outlook is valid
 * for, the areas it has (a color key) and a note when it could not be read. The polygons change as a loop plays, because each frame has its own day. The same
 * kind is drawn on every page; tapping the map adds what the outlook says at that point to the read-out.
 */
@Composable
internal fun OutlookRow(state: OutlookState, valid: Long?, onClick: () -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val type = state.type
    val choice = type?.let { outlookChoiceFor(it, valid ?: now, now) }
    val loaded = choice?.let { state.loaded[it.id] }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(
                when {
                    type == null -> "SPC outlook: off"
                    choice == null -> "SPC outlook: ${type.label} \u00B7 none covers this time"
                    else -> "SPC outlook: ${choice.label}" +
                        (loaded?.data?.valid?.let { v -> " \u00B7 valid ${formatClock(v, now)}" + (loaded?.data?.expires?.let { e -> " to ${formatClock(e, now)}" } ?: "") } ?: "") +
                        if (choice.id in state.reading) " \u00B7 reading\u2026" else ""
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val shapes = loaded?.data?.shapes.orEmpty().filter { !it.hatched }.distinctBy { it.title }
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
        val note = choice?.let { state.notes[it.id] } ?: if (loaded != null && loaded.data.shapes.isEmpty()) "No risk areas in this outlook" else null
        note?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** The kinds of outlook map to choose from; the day comes from the time of the frame shown. */
@Composable
internal fun OutlookDialog(current: OutlookType?, onPick: (OutlookType?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("SPC outlook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun row(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
                    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
                        Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
                        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                row("No outlook", null, current == null) { onPick(null) }
                row("Categorical risk", "Days 1-3; later days show the chance of severe weather", current == OutlookType.Categorical) { onPick(OutlookType.Categorical) }
                row("Tornado chance", "Days 1-2; later days show the chance of severe weather", current == OutlookType.Tornado) { onPick(OutlookType.Tornado) }
                row("Damaging wind chance", "Days 1-2; later days show the chance of severe weather", current == OutlookType.Wind) { onPick(OutlookType.Wind) }
                row("Hail chance", "Days 1-2; later days show the chance of severe weather", current == OutlookType.Hail) { onPick(OutlookType.Hail) }
                Text(
                    "The Storm Prediction Center's outlook areas for the day the frame on screen falls in (Day 1 is the convective day, 12Z to 12Z, that contains now, up to " +
                        "Day 8). In a loop the areas change as the frames move from day to day. Drawn on every page, in exports too. Unofficial copy: use spc.noaa.gov for the real outlooks.",
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
