package io.github.intelati26.simplemodelview

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import simplemodelview.formatUtc
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import simplemodelview.GfsCatalog
import simplemodelview.Grid
import simplemodelview.Product
import simplemodelview.Style
import simplemodelview.WindProducts
import simplemodelview.nbmFrameLabel
import simplemodelview.nbmLoopSpans

private const val GFS_DISCLAIMER = "Data: NOAA/NWS Global Forecast System. Experimental viewer, not a warning or decision-support tool."

/** The GFS page: the Global Forecast System to 16 days, the upper air first. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GfsScreen(vm: GfsViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val outlookState by AppContainer.outlook.state.collectAsStateWithLifecycle()
    var outlookDialog by remember { mutableStateOf(false) }
    var pickSince by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    var savingPlace by remember { mutableStateOf(false) }
    var pickDates by remember { mutableStateOf(false) }
    var loopProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var restartAsk by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.exportSettings(context.contentResolver, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { if (vm.importSettings(context.contentResolver, uri)) restartAsk = true }
    }
    LifecycleResumeEffect(Unit) { vm.refreshIfStale(); vm.promptForContact(); AppContainer.outlook.refreshIfStale(); onPauseOrDispose { } }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { vm.setDark(dark) }
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    // the level and color chips look at the wind products of every level and color
    val windProducts = remember {
        (GfsCatalog.levels + WindProducts.STACK).flatMap { l -> WindProducts.colors.mapNotNull { c -> GfsCatalog.windId(l, c.key) } }.map { Product(it, "", "", Style(), false, null, Grid.REFS) }
    }

    Box(Modifier.fillMaxSize().background(mapBackground)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = s.map
            when {
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                s.globe -> GlobeSurface(bitmap, s.cam, s.tapLatLon, "GFS globe", onSize = vm::setGlobeSize, onGesture = vm::globeGesture, onTap = vm::onGlobeTap, polar = s.polar, onPolarGesture = vm::polarGesture)
                bitmap != null -> ZoomableMap(bitmap, s.crosshair, "GFS map", detail = s.detail, onViewport = vm::setViewport, onTap = vm::onTap, focus = s.focus, onFocusApplied = vm::focusApplied)
                else -> BusySpinner(big = true)
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(panel).statusBarsPadding()) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    var productMenu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        AssistChip(onClick = { productMenu = true }, label = { Text(s.title.ifEmpty { "Product" }, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        DropdownMenu(expanded = productMenu, onDismissRequest = { productMenu = false }) {
                            DropdownMenuItem(text = { Text("Heights and winds (250 to 850 mb)") }, onClick = { productMenu = false; vm.pickHeightsAndWinds() })
                            for (p in GfsCatalog.surface) DropdownMenuItem(text = { Text(p.title) }, onClick = { productMenu = false; vm.pickSurface(p.id) })
                            DropdownMenuItem(text = { Text("Precipitation since a date and time…") }, onClick = { productMenu = false; pickSince = true })
                        }
                    }
                    PageMenu("gfs", onOpen)
                    var more by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { more = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⋮", fontSize = 22.sp) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            if (s.frames.size > 1) DropdownMenuItem(text = { Text(if (s.history) "Save the history loop (video)" else "Save the loop (video)") }, onClick = {
                                more = false
                                scope.launch {
                                    try {
                                        loopProgress = 0 to s.frames.size
                                        val file = vm.buildLoopFile { done, total -> loopProgress = done to total }
                                        loopProgress = null
                                        if (file != null) shareFile(context, file, "video/mp4", "Share loop")
                                    } catch (e: Exception) { loopProgress = null; vm.reportNotice("Could not make the video: ${e.message}") }
                                }
                            })
                            DropdownMenuItem(text = { Text("Places") }, onClick = { more = false; places = true })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { more = false; settings = true })
                            DropdownMenuItem(text = { Text("About") }, onClick = { more = false; about = true })
                        }
                    }
                }
                if (s.product == "hw") WindChooser(windProducts, s.level, s.color, vm::pickLevel, vm::pickColor)
                s.notice?.let {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::dismissNotice) { Text("OK") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.file?.let { nbmFrameLabel(it) + "   frame ${s.index + 1}/${s.frames.size}" + (s.source?.let { src -> "   \u00B7 " + src } ?: "") } ?: "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (s.busy && s.map != null) BusySpinner(Modifier.padding(end = 2.dp))
                    var runMenu by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(onClick = { runMenu = true }, label = { Text(if (s.history) (s.historyRange?.let { r -> formatUtc(r.first).take(10) + "…" } ?: "History ${s.historyDays} d") else s.cycle?.let { "Run " + it.shortLabel + if (s.following) "" else " · set" } ?: "Run") }, modifier = Modifier.height(32.dp))
                        DropdownMenu(expanded = runMenu, onDismissRequest = { runMenu = false }) {
                            DropdownMenuItem(text = { Text("Newest run") }, onClick = { runMenu = false; vm.pickCycle(null) })
                            for (c in s.cycles) DropdownMenuItem(text = { Text(c.label) }, onClick = { runMenu = false; vm.pickCycle(c) })
                            androidx.compose.material3.HorizontalDivider()
                            for (d in listOf(2, 5, 10, 30, 90, 365)) DropdownMenuItem(text = { Text("History: the past " + (if (d == 365) "year" else "$d days") + if (d > 80) " (daily)" else "") }, onClick = { runMenu = false; vm.setHistory(d) })
                            DropdownMenuItem(text = { Text("History: pick dates (back to 2021-03-23)") }, onClick = { runMenu = false; pickDates = true })
                        }
                    }
                    var sectorMenu by remember { mutableStateOf(false) }
                    var sectorPage by remember { mutableStateOf(0) }
                    Box {
                        TextButton(onClick = { sectorMenu = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text((if (s.polar != null) (if (s.polar?.north == true) "N polar" else "S polar") else if (s.globe) "Globe" else s.sector.name) + " ▾", maxLines = 1) }
                        DropdownMenu(expanded = sectorMenu, onDismissRequest = { sectorMenu = false; sectorPage = 0 }) {
                            val wide = GFS_SECTORS.take(4)
                            when (sectorPage) {
                                1 -> {
                                    DropdownMenuItem(text = { Text("‹ Views") }, onClick = { sectorPage = 0 })
                                    DropdownMenuItem(text = { Text("Globe over the north pole") }, onClick = { sectorMenu = false; sectorPage = 0; vm.setGlobePole(true) })
                                    DropdownMenuItem(text = { Text("Globe over the south pole") }, onClick = { sectorMenu = false; sectorPage = 0; vm.setGlobePole(false) })
                                    DropdownMenuItem(text = { Text("North polar map") }, onClick = { sectorMenu = false; sectorPage = 0; vm.setPolar(true) })
                                    DropdownMenuItem(text = { Text("South polar map") }, onClick = { sectorMenu = false; sectorPage = 0; vm.setPolar(false) })
                                }
                                2 -> {
                                    DropdownMenuItem(text = { Text("‹ Views") }, onClick = { sectorPage = 0 })
                                    for (sector in GFS_SECTORS.drop(4)) DropdownMenuItem(text = { Text(sector.name) }, onClick = { sectorMenu = false; sectorPage = 0; vm.pickSector(sector) })
                                }
                                else -> {
                                    if (s.globe && s.polar == null) DropdownMenuItem(text = { Text("Map of this view") }, onClick = { sectorMenu = false; vm.showMapHere() })
                                    DropdownMenuItem(text = { Text("Globe") }, onClick = { sectorMenu = false; vm.setGlobe(true) })
                                    DropdownMenuItem(text = { Text("Poles ›") }, onClick = { sectorPage = 1 })
                                    for (sector in wide) DropdownMenuItem(text = { Text(sector.name) }, onClick = { sectorMenu = false; vm.pickSector(sector) })
                                    DropdownMenuItem(text = { Text("Regions ›") }, onClick = { sectorPage = 2 })
                                }
                            }
}
                    }
                    TextButton(
                        onClick = { scope.launch { vm.buildShareFile()?.let { shareFile(context, it, "image/png", "Share map") } } },
                        enabled = s.map != null, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text("Share") }
                }
                s.loadStatus?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
            TimingsLine(s.settings.showTimings)
            s.style?.let { Legend(it, emptyList()) }
            if (s.frames.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    var spanMenu by remember { mutableStateOf(false) }
                    Box {
                        val fromShown by AppContainer.loopFromShown.collectAsStateWithLifecycle()
                        val spans = nbmLoopSpans(((s.frames.last().valid - s.frames.first().valid) / 3600).toInt())
                        TextButton(onClick = { spanMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text((if (s.loopSpanHours < 0) "${-s.loopSpanHours} steps" else spans.firstOrNull { it.hours == s.loopSpanHours }?.label ?: "all") + " \u25BE")
                        }
                        DropdownMenu(expanded = spanMenu, onDismissRequest = { spanMenu = false }) {
                            for (n in simplemodelview.NBM_LOOP_STEPS) DropdownMenuItem(text = { Text(if (fromShown) "$n steps from the frame shown" else "First $n steps") }, onClick = { spanMenu = false; vm.setLoopSpan(-n) })
                            for (span in spans) DropdownMenuItem(text = { Text(if (fromShown) span.label + " from the frame shown" else span.label) }, onClick = { spanMenu = false; vm.setLoopSpan(span.hours) })
                            androidx.compose.material3.HorizontalDivider()
                            LoopStartItem { spanMenu = false }
                        }
                    }
                    TimeMenu(s.frames.map { it.valid }, s.index, { i -> nbmFrameLabel(s.frames[i]) }, vm::pickIndex)
                    Slider(
                        value = s.index.toFloat(), onValueChange = { vm.pickIndex(it.toInt()) },
                        valueRange = 0f..(s.frames.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
                TimeStepRow(s.index, s.frames.size, s.playing, vm::togglePlay, vm::pickIndex)
            }
            s.prefetch?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
    }

    if (savingPlace) s.tapLatLon?.let { (lat, lon) ->
        var name by remember { mutableStateOf(simplemodelview.placeLabel(lat, lon)) }
        AlertDialog(
            onDismissRequest = { savingPlace = false },
            title = { Text("Save place") },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.savePlace(name.trim(), lat, lon); savingPlace = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { savingPlace = false }) { Text("Cancel") } },
        )
    }
    val live = rememberLiveControl { vm.goToPlace(it, follow = true) }
    if (places) PlacesDialog(s.places, onDismiss = { places = false }, onGo = { places = false; vm.goToPlace(it) }, onDelete = vm::deletePlace, live = live, onEdit = vm::editPlace, read = vm::readAt, marked = s.tapLatLon)

    if (pickSince) {
        val now = System.currentTimeMillis()
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (now - 86_400_000L) / 86_400_000L * 86_400_000L,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in (simplemodelview.GfsHistory.FIRST_RUN * 1000L - 86_400_000L)..now
            },
        )
        var hour by remember { mutableStateOf(0) }
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickSince = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = { val d = state.selectedDateMillis ?: return@TextButton; pickSince = false; vm.pickPrecipSince(d / 1000L + hour * 3600L) },
                ) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { pickSince = false }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DatePicker(state, modifier = Modifier.weight(1f, fill = false), title = { Text("Precipitation from … to the time shown", Modifier.padding(start = 24.dp, top = 16.dp)) })
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Run time (UTC)", Modifier.padding(end = 8.dp))
                for (h in listOf(0, 6, 12, 18)) androidx.compose.material3.FilterChip(selected = hour == h, onClick = { hour = h }, label = { Text("%02dZ".format(h)) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
    }

    if (pickDates) {
        val now = System.currentTimeMillis()
        val first = simplemodelview.GfsHistory.FIRST_RUN * 1000L
        val state = androidx.compose.material3.rememberDateRangePickerState(
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in (first - 86_400_000L)..now
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val a = state.selectedStartDateMillis ?: return@TextButton
                        val b = state.selectedEndDateMillis ?: a
                        pickDates = false
                        vm.setHistoryRange(a / 1000L, minOf(now / 1000L, b / 1000L + 18 * 3600L))      // the end day's runs are included
                    },
                ) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DateRangePicker(state, modifier = Modifier.weight(1f, fill = false), title = { Text("Analyses from … to …", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }

    loopProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {}, confirmButton = {}, title = { Text("Making the loop video") },
            text = { Column { Text("Frame $done of $total"); LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) } },
        )
    }

    if (s.contactPrompt) ContactDialog(
        onSave = { vm.setSettings(s.settings.copy(contactEmail = it)); vm.dismissContactPrompt() }, onNotNow = vm::dismissContactPrompt,
        provider = "NOAA", server = "NOAA's servers",
    )
    if (outlookDialog) OutlookDialog(outlookState.type, onPick = { outlookDialog = false; AppContainer.outlook.setType(it) }, onDismiss = { outlookDialog = false })
    if (restartAsk) RestartDialog(onLater = { restartAsk = false })
    if (settings) SettingsDialog(
        s.settings, vm::setSettings, onExport = { exportLauncher.launch("simplemodelview-settings.json") },
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, disclaimer = GFS_DISCLAIMER, showContours = true, showBarbs = true, showCrop = true,
    ) { settings = false }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        title = { Text("About the GFS page") },
        text = {
            Text(
                "The Global Forecast System (0.25 degrees, a run every six hours to 16 days) from NOAA's open data on AWS (noaa-gfs-bdp-pds). Only the fields shown are downloaded " +
                    "(about 0.5 to 0.8 MB each), and kept on the device.\n\nHeights and winds: height contours and wind barbs at the level you choose over the color you choose.\n\n" +
                    "Not affiliated with or endorsed by NOAA or the NWS. Experimental viewing only: do not use it for safety decisions; follow official NWS products."
            )
        },
    )
}
