package io.github.intelati26.simplemodelview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import simplemodelview.AlertKind
import simplemodelview.OverlayLayer
import simplemodelview.OutlookType
import simplemodelview.Stop
import simplemodelview.Style
import simplemodelview.formatClock
import simplemodelview.lightningFrameFor
import simplemodelview.outlookChoiceFor
import simplemodelview.overlayName
import simplemodelview.overlayThresholdChoices

/** One line for the strip under the map: what is drawn over it ("Overlays: Rotation, Warnings, Day 2 tornado, LightningCast"), or "Overlays: none". */
internal fun overlaySummary(
    layers: List<OverlayLayer>, alertKinds: Set<AlertKind>, outlook: OutlookState, valid: Long?, lightning: LightningState,
): String {
    val now = System.currentTimeMillis() / 1000
    val parts = buildList {
        if (layers.isNotEmpty()) add(overlayName(layers))
        if (alertKinds.isNotEmpty()) add(alertKinds.sortedBy { it.ordinal }.joinToString(", ") { it.label })
        outlook.type?.let { add(outlookChoiceFor(it, valid ?: now, now)?.label ?: it.label) }
        if (lightning.enabled) add("LightningCast")
    }
    return if (parts.isEmpty()) "Overlays: none" else "Overlays: " + parts.joinToString(", ")
}

/**
 * Every overlay in one tree, so the page can keep the map clear: a strip under the map shows what is on ([overlaySummary]), and this popup holds the controls.
 * Four branches, each opening to its own choices, color key and notes: radar fields (any MRMS product, the lightning nest), alerts, the SPC outlook and
 * LightningCast.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverlayTreeDialog(
    layers: List<OverlayLayer>, styles: List<Style>, onRadarNone: () -> Unit, onRadarNest: () -> Unit, onRadarProduct: () -> Unit, onThreshold: (Int) -> Unit,
    alertKinds: Set<AlertKind>, alertsAsOf: Long?, alertNote: String?, newestOnly: Boolean, onAlertKinds: (Set<AlertKind>) -> Unit,
    outlook: OutlookState, valid: Long?, onOutlook: (OutlookType?) -> Unit,
    lightning: LightningState, onLightning: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var open by remember { mutableStateOf(emptySet<String>()) }
    fun toggle(key: String) { open = if (key in open) open - key else open + key }
    val now = System.currentTimeMillis() / 1000
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Overlays", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    // radar fields
                    TreeNode("Radar fields", if (layers.isEmpty()) "off" else overlayName(layers), "radar" in open, { toggle("radar") }) {
                        val current = overlayName(layers)
                        Choice("No overlay", null, layers.isEmpty(), onRadarNone)
                        Choice("Lightning nest", "Strikes by recency: the last minute solid, the last 5 minutes at 50 %, the last 30 minutes as a 20 % shadow", current == "Lightning nest", onRadarNest)
                        Choice("Choose a product…", "Rotation tracks, strikes, hail size, any other field", layers.isNotEmpty() && current != "Lightning nest", onRadarProduct)
                        val style = styles.singleOrNull()
                        if (layers.size == 1 && style != null) {
                            val choices = overlayThresholdChoices(style)
                            if (choices.isNotEmpty()) {
                                var menu by remember { mutableStateOf(false) }
                                val index = layers[0].thresholdIndex.coerceIn(0, choices.size - 1)
                                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Show values at or above", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Box {
                                        TextButton(onClick = { menu = true }) { Text("≥ ${thresholdLabel(choices[index], style)} ▾") }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            choices.forEachIndexed { i, v -> DropdownMenuItem(text = { Text("≥ ${thresholdLabel(v, style)}") }, onClick = { menu = false; onThreshold(i) }) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // alerts
                    TreeNode("Alerts", if (alertKinds.isEmpty()) "off" else alertKinds.sortedBy { it.ordinal }.joinToString(", ") { it.label }, "alerts" in open, { toggle("alerts") }) {
                        val detail = mapOf(
                            AlertKind.Warning to "Tornado, severe thunderstorm, flash flood and other warnings (NWS)",
                            AlertKind.Watch to "Tornado, severe thunderstorm and flash flood watches (NWS / SPC)",
                            AlertKind.Discussion to "SPC mesoscale discussions and WPC precipitation discussions, dashed",
                        )
                        for (kind in AlertKind.entries) {
                            Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(kind.label, fontWeight = FontWeight.Bold)
                                    Text(detail.getValue(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = kind in alertKinds, onCheckedChange = { on -> onAlertKinds(if (on) alertKinds + kind else alertKinds - kind) })
                            }
                        }
                        if (alertKinds.isNotEmpty()) {
                            Note((alertsAsOf?.let { "As of ${formatClock(it)}. " } ?: "") + (alertNote ?: if (newestOnly) "Alerts are shown on the newest frame only." else ""), alertNote != null || newestOnly)
                        }
                        Note("Tap an outline for its text. Unofficial copy of public NWS, SPC and WPC data, can be late or incomplete: use weather.gov for warnings.")
                    }
                    // SPC outlook
                    val choice = outlook.type?.let { outlookChoiceFor(it, valid ?: now, now) }
                    TreeNode("SPC outlook", outlook.type?.let { choice?.label ?: it.label } ?: "off", "outlook" in open, { toggle("outlook") }) {
                        Choice("No outlook", null, outlook.type == null) { onOutlook(null) }
                        Choice("Categorical risk", "Days 1-3; later days show the chance of severe weather", outlook.type == OutlookType.Categorical) { onOutlook(OutlookType.Categorical) }
                        Choice("Tornado chance", "Days 1-2; later days show the chance of severe weather", outlook.type == OutlookType.Tornado) { onOutlook(OutlookType.Tornado) }
                        Choice("Damaging wind chance", "Days 1-2; later days show the chance of severe weather", outlook.type == OutlookType.Wind) { onOutlook(OutlookType.Wind) }
                        Choice("Hail chance", "Days 1-2; later days show the chance of severe weather", outlook.type == OutlookType.Hail) { onOutlook(OutlookType.Hail) }
                        val loaded = choice?.let { outlook.loaded[it.id] }
                        loaded?.data?.valid?.let { v -> Note("${choice?.label}: valid ${formatClock(v, now)}" + (loaded?.data?.expires?.let { e -> " to ${formatClock(e, now)}" } ?: "")) }
                        val shapes = loaded?.data?.shapes.orEmpty().filter { !it.hatched }.distinctBy { it.title }
                        if (shapes.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (shape in shapes) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(Color(0xFF000000.toInt() or shape.color)))
                                Text(" " + shape.title, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val note = choice?.let { outlook.notes[it.id] } ?: if (loaded != null && loaded.data.shapes.isEmpty()) "No risk areas in this outlook" else null
                        if (choice != null && choice.id in outlook.reading) Note("Reading…")
                        note?.let { Note(it, true) }
                        Note("The area for the day the frame on screen falls in (Day 1 is the convective day, 12Z to 12Z, that contains now). In a loop the areas change from day to day. Unofficial copy: use spc.noaa.gov.")
                    }
                    // LightningCast
                    val frame = lightningFrameFor(lightning.frames, null)
                    TreeNode("LightningCast", if (lightning.enabled) "on" else "off", "lightning" in open, { toggle("lightning") }) {
                        Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lightning chance, next 60 min", fontWeight = FontWeight.Bold)
                                Text("GOES-East, NOAA and CIMSS: lines at 10, 30, 50, 70 and 90 %" + (frame?.takeIf { lightning.enabled }?.let { " · scan ${formatClock(it.start)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = lightning.enabled, onCheckedChange = onLightning)
                        }
                        val levels = frame?.contours.orEmpty().distinctBy { it.percent }.sortedBy { it.percent }
                        if (lightning.enabled && levels.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in levels) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(width = 14.dp, height = 3.dp).background(Color(0xFF000000.toInt() or c.color)))
                                Text(" ${c.percent}%", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (lightning.busy) Note("Reading…")
                        if (lightning.enabled) lightning.note?.let { Note(it, true) }
                    }
                }
            }
        }
    }
}

private fun thresholdLabel(value: Double, style: Style) = "${MapRenderer.label(Stop(value, 0), style)} ${style.units}"

/** A branch of the tree: a tappable header (arrow, name, what is on) that opens to [content], indented. */
@Composable
private fun TreeNode(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▾" else "▸", Modifier.padding(end = 10.dp), fontSize = 16.sp)
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(summary, Modifier.padding(start = 8.dp).weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 8.dp, bottom = 12.dp), content = content)
        HorizontalDivider()
    }
}

@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(36.dp), colors = androidx.compose.material3.RadioButtonDefaults.colors())
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    if (text.isBlank()) return
    Text(
        text, Modifier.padding(top = 6.dp, end = 12.dp), style = MaterialTheme.typography.labelSmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
