package io.github.intelati26.simplemodelview

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import simplemodelview.Grid
import simplemodelview.NbmCatalog
import simplemodelview.NbmCycle
import simplemodelview.NbmFile
import simplemodelview.Product
import simplemodelview.formatAge
import simplemodelview.formatClock
import simplemodelview.formatUtc
import simplemodelview.nbmCycleNote
import simplemodelview.nbmFrameLabel
import simplemodelview.nbmIsExtended
import simplemodelview.nbmLoopSpans
import simplemodelview.placeLabel

private const val NBM_DISCLAIMER = "Data: NOAA/NWS National Blend of Models. Experimental viewer, not a warning or decision-support tool."

/** The National Blend of Models page: forecasts to 11 days from NOAA's open data, over the same map machinery as the other pages. */
@Composable
internal fun NbmScreen(vm: NbmViewModel, onSpc: () -> Unit, onMrms: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var cyclePicker by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var savingPlace by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val outlookState by AppContainer.outlook.state.collectAsStateWithLifecycle()
    val lightningState by AppContainer.lightning.state.collectAsStateWithLifecycle()
    var outlookDialog by remember { mutableStateOf(false) }
    var loopProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.exportSettings(context.contentResolver, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.importSettings(context.contentResolver, uri) }
    }
    LifecycleResumeEffect(Unit) { vm.refreshIfStale(); vm.promptForContact(); AppContainer.outlook.refreshIfStale(); AppContainer.lightning.refreshIfStale(); onPauseOrDispose { } }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { vm.setDark(dark) }
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    // the catalog as the picker's product type (it groups, searches and stars them like the other pages do)
    val products = remember { NbmCatalog.all.map { Product(it.pickerId, it.title, it.group, it.style, false, null, Grid.REFS) } }

    Box(Modifier.fillMaxSize().background(mapBackground)) {
        // the map fills the screen; thin translucent strips float over the empty bands above and below it
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = s.map
            when {
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                bitmap != null -> ZoomableMap(bitmap, s.crosshair, "National Blend of Models map", onViewport = vm::setViewport, onTap = vm::onTap)
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
                    TextButton(onClick = onSpc, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("REFS") }
                    TextButton(onClick = onMrms, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("MRMS") }
                    MrmsMenu(onPlaces = { places = true }, onSettings = { settings = true }, onAbout = { about = true })
                }
                s.notice?.let {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::dismissNotice) { Text("OK") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.file?.let { nbmFrameLabel(it) + "   frame ${s.index + 1}/${s.frames.size}" } ?: "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (s.busy && s.map != null) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(16.dp), strokeWidth = 2.dp)
                    AssistChip(
                        onClick = { cyclePicker = true },
                        label = { Text(s.cycle?.let { "Run " + it.shortLabel + (if (s.following) "" else " · set") } ?: "Run") },
                        modifier = Modifier.height(32.dp),
                    )
                    SectorMenu(s.sector.name, onGlobe = null, onPick = vm::pickSector)
                    TextButton(onClick = { sharing = true }, enabled = s.map != null, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Share") }
                }
                NbmTimesLine(s.cycle, s.file)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(panel).navigationBarsPadding()) {
            s.readout?.let {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (s.tapLatLon != null) TextButton(onClick = { savingPlace = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Save") }
                    TextButton(onClick = vm::clearTap, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
                }
            }
            OutlookRow(outlookState, s.file?.valid, onClick = { outlookDialog = true })
            LightningCastRow(lightningState, onToggle = { AppContainer.lightning.setEnabled(!lightningState.enabled) })
            s.style?.let { Legend(it, emptyList()) }
            if (s.frames.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::togglePlay, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (s.playing) "Pause" else "Play") }
                    NbmLoopMenu(s.loopSpanHours, s.frames, vm::setLoopSpan)
                    Slider(
                        value = s.index.toFloat(), onValueChange = { vm.pickIndex(it.toInt()) },
                        valueRange = 0f..(s.frames.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
            s.prefetch?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
    }

    if (s.contactPrompt) ContactDialog(
        onSave = { vm.setSettings(s.settings.copy(contactEmail = it)); vm.dismissContactPrompt() }, onNotNow = vm::dismissContactPrompt,
        provider = "NOAA", server = "NOAA's servers",
    )
    if (picker) ProductPicker(
        products, products.firstOrNull { it.id == s.product.pickerId }, s.favorites, onToggle = vm::toggleFavorite,
        onPick = { picked -> picker = false; NbmCatalog.byPickerId(picked.id)?.let(vm::pickProduct) }, onDismiss = { picker = false },
        groups = NbmCatalog.groups,
    )
    if (outlookDialog) OutlookDialog(outlookState.type, onPick = { outlookDialog = false; AppContainer.outlook.setType(it) }, onDismiss = { outlookDialog = false })
    if (cyclePicker) {
        LaunchedEffect(Unit) { vm.loadLeads() }
        CycleDialog(s.cycles, s.cycle, s.following, s.leads, onPick = { cyclePicker = false; vm.pickCycle(it) }, onDismiss = { cyclePicker = false })
    }
    if (savingPlace) s.tapLatLon?.let { (lat, lon) ->
        var name by remember { mutableStateOf(placeLabel(lat, lon)) }
        AlertDialog(
            onDismissRequest = { savingPlace = false },
            title = { Text("Save place") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.savePlace(name.trim(), lat, lon); savingPlace = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { savingPlace = false }) { Text("Cancel") } },
        )
    }
    if (places) PlacesDialog(s.places, onDismiss = { places = false }, onGo = { places = false; vm.goToPlace(it) }, onDelete = vm::deletePlace)
    if (settings) SettingsDialog(
        s.settings, vm::setSettings, onExport = { exportLauncher.launch("simplemodelview-settings.json") },
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, disclaimer = NBM_DISCLAIMER, showContours = true,
    ) { settings = false }
    if (sharing) ShareDialog(
        canLoop = s.frames.size > 1, current = s.sector.name, globe = false, onDismiss = { sharing = false },
        initialSize = vm.videoSize, estimate = { area, size -> vm.estimateLoopMegabytes(area, size) },
        onShare = { area, loop, size ->
            vm.videoSize = size
            sharing = false
            scope.launch {
                try {
                    if (!loop) {
                        val file = vm.buildShareFile(area) ?: return@launch
                        shareFile(context, file, "image/png", "Share map")
                    } else {
                        loopProgress = 0 to s.frames.size
                        val file = vm.buildLoopFile(area, size) { done, total -> loopProgress = done to total }
                        loopProgress = null
                        if (file != null) shareFile(context, file, "video/mp4", "Share loop")
                    }
                } catch (e: Exception) {
                    loopProgress = null
                    vm.reportNotice("Could not make the ${if (loop) "video" else "picture"}: ${e.message}")
                }
            }
        },
        disclaimer = NBM_DISCLAIMER,
    )
    loopProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Making the loop video") },
            text = {
                Column {
                    Text("Frame $done of $total")
                    LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
        )
    }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        title = { Text("About the Blend page") },
        text = {
            Text(
                "The National Blend of Models (NBM) from NOAA's National Weather Service: a calibrated blend of many models, with chances and percentiles, " +
                    "out to 11 days. Read from NOAA's open data on AWS (noaa-nbm-pds), as GeoTIFF rasters on a 2.5 km grid, for the contiguous US.\n\n" +
                    "A new run starts every hour. Runs that reach a week or more are marked extended in the run list. Frames come every hour for the first day and a " +
                    "half, then every 3 hours, then every 6; Play shows each for as long as the hours it stands for, so a day of forecast takes the same time at any spacing.\n\n" +
                    "Not affiliated with or endorsed by NOAA or the NWS. Experimental viewing only: do not use it for warnings or safety decisions; " +
                    "follow official NWS products."
            )
        },
    )
}

/** The runs to choose from, newest first, each with how far it reaches; the extended ones (a week or more) are marked. "Newest run" follows the newest complete one. */
@Composable
private fun CycleDialog(
    cycles: List<NbmCycle>, current: NbmCycle?, following: Boolean, leads: Map<Long, Int?>, onPick: (NbmCycle?) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Model run") },
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp)) {
                        Text("Newest complete run", fontWeight = if (following) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
                        Text("Follows new runs as they arrive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                items(cycles, key = { it.epoch }) { cycle ->
                    val known = cycle.epoch in leads
                    val lead = leads[cycle.epoch]
                    val selected = !following && cycle == current
                    Column(Modifier.fillMaxWidth().clickable { onPick(cycle) }.padding(vertical = 8.dp)) {
                        Text(formatUtc(cycle.epoch), fontWeight = if (selected || (following && cycle == current)) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
                        Text(
                            (if (nbmIsExtended(lead)) "★ " else "") + nbmCycleNote(lead, known),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (nbmIsExtended(lead)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (nbmIsExtended(lead)) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
    )
}

/** "Loop: all" / "Loop: 3 days": how much of the run, from its start, Play (and a loop video) covers. */
@Composable
private fun NbmLoopMenu(spanHours: Int, frames: List<NbmFile>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val total = if (frames.size < 2) 0 else ((frames.last().valid - frames.first().valid) / 3600).toInt()
    val spans = nbmLoopSpans(total)
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("Loop: " + (spans.firstOrNull { it.hours == spanHours && spanHours > 0 }?.label ?: "all"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (span in spans) DropdownMenuItem(text = { Text(if (span.hours == 0) "All of the run" else "First ${span.label}") }, onClick = { open = false; onPick(span.hours) })
        }
    }
}

/** "Now 17:43Z" and "Run 2026-10-30 00Z · file uploaded 00:41Z (17 h 02 min ago)". */
@Composable
private fun NbmTimesLine(cycle: NbmCycle?, file: NbmFile?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(10_000) } }
    val style = MaterialTheme.typography.labelSmall
    Text(
        buildString {
            append("Now ").append(formatClock(now))
            if (cycle != null) append(" · Run ").append(cycle.label)
            val uploaded = file?.uploaded
            if (uploaded != null) append(" · file uploaded ").append(formatClock(uploaded, now)).append(" (").append(formatAge(now - uploaded)).append(" ago)")
        },
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 2.dp), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
