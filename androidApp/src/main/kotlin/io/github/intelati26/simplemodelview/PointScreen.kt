package io.github.intelati26.simplemodelview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import simplemodelview.Cycle
import simplemodelview.NbmTextElements
import simplemodelview.PointColumn
import simplemodelview.PointTable
import simplemodelview.formatUtc
import simplemodelview.pointDays
import simplemodelview.pointHourLabel
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The device's time zone: the "Local" row and the pick bar show a column's time in it beside the bulletins' UTC (the bulletins' days and hours are UTC). */
private fun localZone(): ZoneId = ZoneId.systemDefault()

/** "7p", "12a": the hour in the device's zone, short enough for a column. */
private fun localHour(valid: Long): String {
    val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(valid), localZone())
    val h = t.hour % 12
    return (if (h == 0) 12 else h).toString() + (if (t.hour < 12) "a" else "p")
}

/** "Thu 7:00 PM CST" for the pick bar. */
private fun localFull(valid: Long): String =
    DateTimeFormatter.ofPattern("EEE h:mm a zzz", Locale.US).format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(valid), localZone()))

/** The zone's short name now ("CST"): the label of the Local row. */
private fun localName(): String = DateTimeFormatter.ofPattern("zzz", Locale.US).format(ZonedDateTime.now(localZone()))

private val COLUMN_WIDTH = 46.dp
private val LABEL_WIDTH = 62.dp
private const val POINT_DISCLAIMER = "Data: NOAA / NWS National Blend of Models bulletins (and the NWS gridded forecast for the first hours). Experimental viewer, not a warning or decision-support tool."

/**
 * The fifth page: a saved place's forecast for the next 11 days as graphs and a table of the NBM bulletins' columns, with one column picked to open the
 * synoptic set up (heights and winds) for its time on the REFS page, or on the GFS page beyond REFS's range.
 */
@Composable
internal fun PointScreen(vm: PointViewModel, onOpen: (String) -> Unit, onSynoptic: (valid: Long, refs: Cycle?) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var about by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { vm.reloadFromPrefs(); vm.refreshIfStale(); onPauseOrDispose { } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            var placeMenu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                AssistChip(onClick = { placeMenu = true }, label = { Text((s.places.getOrNull(s.placeIndex)?.name ?: "No saved places") + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                DropdownMenu(expanded = placeMenu, onDismissRequest = { placeMenu = false }) {
                    for ((i, p) in s.places.withIndex()) DropdownMenuItem(text = { Text(if (i == s.placeIndex) p.name + "  ✓" else p.name) }, onClick = { placeMenu = false; vm.pickPlace(i) })
                    if (s.places.isEmpty()) DropdownMenuItem(text = { Text("Save a place on the REFS, MRMS, NBM or GFS page: tap the map, then Save") }, onClick = { placeMenu = false })
                }
            }
            PageMenu("point", onOpen)
            var more by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { more = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Refresh") }, onClick = { more = false; vm.load() })
                    DropdownMenuItem(text = { Text("About") }, onClick = { more = false; about = true })
                }
            }
        }
        val line = listOfNotNull(s.station?.let { id -> id + (s.stationName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") }, s.sources.takeIf { it.isNotEmpty() }).joinToString("   ")
        if (line.isNotEmpty() || s.busy) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.status ?: line, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.busy) CircularProgressIndicator(Modifier.padding(start = 6.dp).height(16.dp).width(16.dp), strokeWidth = 2.dp)
        }
        val table = s.table
        when {
            s.places.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Save a place on any page (tap the map, then Save) and pick it here for its forecast out to 11 days.", textAlign = TextAlign.Center)
            }
            s.error != null && table == null -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
            }
            table == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                s.error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                PointBody(table, s.selected, vm::select, Modifier.weight(1f))
                s.selected?.let { i -> table.columns.getOrNull(i)?.let { column -> SynopticBar(column, s.checking) { vm.openSynoptic(column.valid) { cycle -> onSynoptic(column.valid, cycle) } } } }
            }
        }
    }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        title = { Text("About the extended view") },
        text = {
            Text(
                "The NBM's text bulletins (NBS to 72 h every 3 hours, NBE to 192 h and NBX to 264 h every 12 hours) for the nearest station of the place, in front of them the NWS's " +
                    "gridded forecast for the first hours. Each time has a temperature spread: it grows with the lead time and shows how far to trust the later days. The first time you " +
                    "pick a place, its coordinates are sent to api.weather.gov to find the station and the grid; the answer is kept on this device.\n\nTap a time to pick it, then open " +
                    "the heights and winds for that time on the REFS page when a REFS run has that time (up to about 2 days ahead), otherwise the GFS page at the nearest frame.\n\n$POINT_DISCLAIMER",
            )
        },
    )
}

@Composable
private fun PointBody(table: PointTable, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val apiColor = MaterialTheme.colorScheme.tertiary
    val days = remember(table) { pointDays(table) }
    val dayStarts = remember(days) { days.map { it.first }.toSet() }
    fun cell(i: Int, content: @Composable () -> Unit, height: Dp, background: Color = Color.Transparent): @Composable () -> Unit = {
        Box(Modifier.width(COLUMN_WIDTH).height(height).background(if (i == selected) highlight else background).clickable { onSelect(i) }, contentAlignment = Alignment.Center) { content() }
    }
    @Composable fun valueRow(height: Dp = 22.dp, text: (PointColumn) -> String?, color: (PointColumn) -> Color = { Color.Unspecified }) {
        Row { table.columns.forEachIndexed { i, c -> cell(i, { Text(text(c).orEmpty(), fontSize = 12.sp, color = color(c), maxLines = 1) }, height)() } }
    }
    fun inches(hundredths: Int?) = hundredths?.takeIf { it > 0 }?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }
    fun tenths(v: Int?) = v?.takeIf { it > 0 }?.let { (it / 10.0).toString() }

    // (label, height) of each row, top to bottom: both columns of the table are built from the same list so the rows line up
    class RowDef(val label: String, val height: Dp, val content: @Composable () -> Unit)
    val rows = ArrayList<RowDef>()
    rows += RowDef("", 22.dp) {
        Row { table.columns.indices.forEach { i -> Box(Modifier.width(COLUMN_WIDTH).height(22.dp)) { days.firstOrNull { it.first == i }?.let { Text(it.second, Modifier.wrapContentWidth(unbounded = true, align = Alignment.Start).padding(start = 3.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) } } } }
    }
    rows += RowDef("UTC", 22.dp) { valueRow(text = { pointHourLabel(it) }, color = { if (it.source == "API") apiColor else Color.Unspecified }) }
    rows += RowDef(localName(), 22.dp) { valueRow(text = { localHour(it.valid) }, color = { Color.Gray }) }
    rows += RowDef("°F", 140.dp) { TemperatureChart(table, COLUMN_WIDTH, 140.dp, selected, onSelect) }
    rows += RowDef("Temp", 22.dp) { valueRow(text = { it.value(NbmTextElements.TEMP)?.toString() }, color = { Color(0xFFD84315) }) }
    rows += RowDef("± spread", 22.dp) { valueRow(text = { it.value(NbmTextElements.TEMP_SPREAD)?.let { v -> "±$v" } }) }
    rows += RowDef("Dewpt", 22.dp) { valueRow(text = { it.value(NbmTextElements.DEW)?.toString() }, color = { Color(0xFF2E7D32) }) }
    if (table.hasAny(NbmTextElements.MAX_MIN)) rows += RowDef("Hi / Lo", 22.dp) { valueRow(text = { c -> c.value(NbmTextElements.MAX_MIN)?.let { v -> (if (pointHourLabel(c) == "00Z") "H " else if (pointHourLabel(c) == "12Z") "L " else "") + v } }) }
    rows += RowDef("Sky %", 22.dp) {
        Row { table.columns.forEachIndexed { i, c -> val v = c.value(NbmTextElements.SKY); cell(i, { Text(v?.toString().orEmpty(), fontSize = 12.sp) }, 22.dp, Color.Gray.copy(alpha = (v ?: 0) / 100f * 0.45f))() } }
    }
    rows += RowDef("Wind kt", 120.dp) { WindChart(table, COLUMN_WIDTH, 120.dp, selected, onSelect) }
    rows += RowDef("Speed", 22.dp) { valueRow(text = { it.value(NbmTextElements.WIND)?.toString() }) }
    rows += RowDef("Gust", 22.dp) { valueRow(text = { it.value(NbmTextElements.GUST)?.toString() }) }
    rows += RowDef("Precip %", 90.dp) { PrecipChart(table, COLUMN_WIDTH, 90.dp, selected, onSelect) }
    rows += RowDef("Chance", 22.dp) { valueRow(text = { c -> (c.value(NbmTextElements.POP12) ?: c.value("P06"))?.toString() }) }
    rows += RowDef("Rain in", 22.dp) { valueRow(text = { c -> inches(c.value(NbmTextElements.QPF12) ?: c.value("Q06")) }, color = { Color(0xFF2E7D32) }) }
    if (table.hasAny(NbmTextElements.SNOW12)) rows += RowDef("Snow in", 22.dp) { valueRow(text = { c -> tenths(c.value(NbmTextElements.SNOW12)) }, color = { Color(0xFF1E88E5) }) }
    if (table.hasAny(NbmTextElements.ICE12)) rows += RowDef("Ice in", 22.dp) { valueRow(text = { c -> inches(c.value(NbmTextElements.ICE12)) }, color = { Color(0xFF6A1B9A) }) }
    if (table.hasAny(NbmTextElements.THUNDER12) || table.hasAny("T06")) rows += RowDef("T-storm %", 22.dp) { valueRow(text = { c -> (c.value(NbmTextElements.THUNDER12) ?: c.value("T06"))?.takeIf { it > 0 }?.toString() }) }
    if (table.hasAny(NbmTextElements.SNOW_LEVEL)) rows += RowDef("Snow kft", 22.dp) { valueRow(text = { c -> c.value(NbmTextElements.SNOW_LEVEL)?.let { (it / 10.0).toString() } }) }       // (hundreds of feet in the bulletin)

    Row(modifier.fillMaxWidth().verticalScroll(vertical)) {
        Column(Modifier.width(LABEL_WIDTH).background(MaterialTheme.colorScheme.background)) {
            for (r in rows) {
                Box(Modifier.height(r.height).fillMaxWidth().padding(start = 6.dp), contentAlignment = Alignment.CenterStart) { Text(r.label, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)) }
                HorizontalDivider(Modifier.fillMaxWidth(), color = Color.Transparent)       // (the same height as the divider under the row of values)
            }
        }
        Column(Modifier.horizontalScroll(horizontal)) {
            for (r in rows) { r.content(); HorizontalDivider(Modifier.width(COLUMN_WIDTH * table.columns.size), color = faint) }
        }
    }
}

/** The column picked: its time, and the button that opens the heights and winds for it: on a REFS run that has the time, else on GFS (the page asks the runs when it is pressed). */
@Composable
private fun SynopticBar(column: PointColumn, checking: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(formatUtc(column.valid), fontWeight = FontWeight.Bold)
            Text(localFull(column.valid), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpen, enabled = !checking) { Text(if (checking) "Looking for a REFS run…" else "Heights & winds (REFS, else GFS)") }
    }
}
