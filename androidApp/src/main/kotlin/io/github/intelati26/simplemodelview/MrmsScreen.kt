package io.github.intelati26.simplemodelview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import simplemodelview.MrmsCatalogue
import simplemodelview.MrmsProduct
import simplemodelview.Sector
import simplemodelview.fixed
import simplemodelview.formatAge
import simplemodelview.formatClock
import simplemodelview.formatPlus

/** The MRMS page: composite reflectivity, rotation tracks and 24 h rainfall (and variants) over the same map machinery as the REFS page. */
@Composable
internal fun MrmsScreen(vm: MrmsViewModel, onSpc: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.LaunchedEffect(dark) { vm.setDark(dark) }
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

    Box(Modifier.fillMaxSize().background(mapBackground)) {
        // the map fills the screen; thin translucent strips float over the empty bands above and below it
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = s.map
            when {
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                bitmap != null -> ZoomableMap(bitmap, s.crosshair, "MRMS map", onViewport = { _, _, _, _ -> }, onTap = vm::onTap)
                else -> CircularProgressIndicator()
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(panel).statusBarsPadding()) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(onClick = { picker = true }, label = { Text(s.product.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSpc, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("SPC REFS") }
                    MrmsMenu(onAbout = { about = true })
                }
                s.notice?.let {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::dismissNotice) { Text("OK") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.file?.let { "Valid ${formatClock(it.validTime, System.currentTimeMillis() / 1000)}   frame ${s.index + 1}/${s.files.size}" } ?: "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (s.busy && s.map != null) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(16.dp), strokeWidth = 2.dp)
                    MrmsSectorMenu(s.sector.name, vm::pickSector)
                }
                MrmsTimesLine(s.file?.uploaded, s.file?.validTime, s.cellKilometres)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(panel).navigationBarsPadding()) {
            s.readout?.let {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::clearTap, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
                }
            }
            s.style?.let { Legend(it, emptyList()) }
            if (s.files.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::togglePlay, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (s.playing) "Pause" else "Play") }
                    Slider(
                        value = s.index.toFloat(), onValueChange = { vm.pickIndex(it.toInt()) },
                        valueRange = 0f..(s.files.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
            s.prefetch?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
    }

    if (picker) MrmsProductPicker(s.product, onPick = { picker = false; vm.pickProduct(it) }, onDismiss = { picker = false })
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        title = { Text("About the MRMS page") },
        text = {
            Text(
                "MRMS (Multi-Radar / Multi-Sensor) products from NOAA's National Severe Storms Laboratory and the National Weather Service, read from " +
                    "NOAA's open data on AWS (noaa-mrms-pds). Cells are 1 km (rotation tracks 500 m); a wide area is shown at a coarser step to fit the screen.\n\n" +
                    "Not affiliated with or endorsed by NOAA, the NWS or NSSL. Experimental viewing only: do not use it for warnings or safety decisions; " +
                    "follow official NWS products."
            )
        },
    )
}

@Composable
private fun MrmsMenu(onAbout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⋮", fontSize = 22.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("About") }, onClick = { open = false; onAbout() })
        }
    }
}

@Composable
private fun MrmsSectorMenu(current: String, onPick: (Sector) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("$current ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (sector in Sector.all) DropdownMenuItem(text = { Text(sector.name) }, onClick = { open = false; onPick(sector) })
        }
    }
}

@Composable
private fun MrmsProductPicker(current: MrmsProduct, onPick: (MrmsProduct) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("MRMS product") },
        text = {
            LazyColumn {
                for (group in MrmsCatalogue.groups) {
                    item(key = "g$group") {
                        Text(group, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    items(MrmsCatalogue.all.filter { it.group == group }, key = { it.id }) { p ->
                        Text(
                            p.title, Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp),
                            fontWeight = if (p.id == current.id) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp,
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

/** "Now 13:04Z · File 13:01Z (3 min ago)" and "Delay: file 13Z+0:46 · 1.1 km cells". */
@Composable
private fun MrmsTimesLine(uploaded: Long?, validTime: Long?, cellKm: Double?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(10_000) } }
    val style = MaterialTheme.typography.labelSmall
    val pad = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 2.dp)
    Text(
        buildString {
            append("Now ").append(formatClock(now))
            if (uploaded != null) append(" · File ").append(formatClock(uploaded, now)).append(" (").append(formatAge(now - uploaded)).append(" ago)")
        },
        pad, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    if (uploaded != null && validTime != null) {
        Text(
            buildString {
                append("Delay: file ").append(formatPlus(validTime, uploaded - validTime))
                if (cellKm != null) append(" · ").append(fixed(cellKm, 1)).append(" km cells")
            },
            pad, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
