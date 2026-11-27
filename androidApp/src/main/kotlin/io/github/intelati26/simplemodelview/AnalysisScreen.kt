package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import simplemodelview.AnalysisBuckets
import simplemodelview.AnalysisCatalog
import simplemodelview.AnalysisFrame
import simplemodelview.formatUtc
import simplemodelview.placeLabel

private const val ANALYSIS_DISCLAIMER = "Data: NOAA/NWS RTMA and URMA surface analyses. Experimental viewer, not a warning or decision-support tool."

private fun frameLabel(f: AnalysisFrame) = formatUtc(f.valid).let { if (f.quarterHourly) it else it }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** The surface analysis page: RTMA / URMA fields at 2.5 km, the last six hours every 15 minutes or any span of the past every hour. */
@Composable
internal fun AnalysisScreen(vm: AnalysisViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var productMenu by remember { mutableStateOf(false) }
    var timeMenu by remember { mutableStateOf(false) }
    var pickDates by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var savingPlace by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var loopProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LifecycleResumeEffect(Unit) { vm.refreshIfStale(); onPauseOrDispose { } }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { vm.setDark(dark) }
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    val spanLabel = s.history?.let { (a, b) -> formatUtc(a).take(10) + if (b - a > 86_400) "…" + formatUtc(b).take(10) else "" } ?: "Last 6 h"

    var topPx by remember { mutableStateOf(0) }       // the heights of the strips floating over the map: the compare's panes fit between them
    var bottomPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxSize().background(mapBackground)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = s.map
            when {
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                s.globe -> GlobeSurface(bitmap, s.cam, s.tapLatLon, "Analysis globe", onSize = vm::setGlobeSize, onGesture = vm::globeGesture, onTap = vm::onGlobeTap)
                bitmap != null && s.compare && s.paneB != null && s.other != null && !s.playing ->
                    ComparePanes(Modifier.padding(top = with(density) { topPx.toDp() }, bottom = with(density) { bottomPx.toDp() }), bitmap, s.paneB!!, s.product.title, s.titleB.orEmpty(), mapBackground, s.crosshair, s.detail, vm::setViewport, vm::onTap, vm::onTap, s.focus, vm::focusApplied, s.activePane, vm::selectPane)
                bitmap != null -> ZoomableMap(bitmap, s.crosshair, "Analysis map", detail = s.detail, onViewport = vm::setViewport, onTap = vm::onTap, focus = s.focus, onFocusApplied = vm::focusApplied)
                else -> BusySpinner(big = true)
            }
        }
        val head: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val activeProduct = vm.activeProduct(s)
                    val at = AnalysisCatalog.all.indexOf(activeProduct)
                    ProductStepButton(next = false, enabled = at > 0) { vm.stepProduct(-1) }
                    Box(Modifier.weight(1f)) {
                        AssistChip(onClick = { productMenu = true }, label = { Text(activeProduct.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.fillMaxWidth())
                        DropdownMenu(expanded = productMenu, onDismissRequest = { productMenu = false }) {
                            for (p in AnalysisCatalog.all) DropdownMenuItem(
                                text = { Text(p.title, fontWeight = if (p == activeProduct) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { productMenu = false; vm.pickProduct(p) },
                            )
                        }
                    }
                    ProductStepButton(next = true, enabled = at in 0 until AnalysisCatalog.all.size - 1) { vm.stepProduct(1) }
                    PageMenu("analysis", onOpen)
                    MrmsMenu(onPlaces = { places = true }, onSettings = { settings = true }, onAbout = { about = true }, compare = s.compare, canCompare = s.other != null, onGlobe = s.globe, onCompare = vm::setCompare, onSwap = vm::swapPanes)
                }
                s.notice?.let {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::dismissNotice) { Text("OK") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.frame?.let { frameLabel(it) + (s.shownSource?.let { src -> "  $src" } ?: "") + "   ${s.index + 1}/${s.frames.size}" } ?: "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (s.busy && s.map != null) BusySpinner(Modifier.padding(end = 2.dp))
                    Box {
                        AssistChip(onClick = { timeMenu = true }, label = { Text(spanLabel) }, modifier = Modifier.height(32.dp))
                        DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                            val now = System.currentTimeMillis() / 1000
                            DropdownMenuItem(text = { Text("Last 6 hours, every 15 minutes", fontWeight = if (s.history == null) FontWeight.Bold else FontWeight.Normal) }, onClick = { timeMenu = false; vm.setHistory(null) })
                            HorizontalDivider()
                            for ((label, hours) in listOf("Last 24 hours" to 24, "Last 3 days" to 72, "Last 7 days" to 168, "Last 30 days" to 720)) DropdownMenuItem(
                                text = { Text("$label, hourly") }, onClick = { timeMenu = false; vm.setHistory(now - hours * 3600L to now) },
                            )
                            DropdownMenuItem(text = { Text("Pick dates…") }, onClick = { timeMenu = false; pickDates = true })
                        }
                    }
                    SectorMenu(if (s.globe) "Globe" else s.sector.name, onGlobe = { vm.setGlobe(true) }, onPick = vm::pickSector, onMapHere = if (s.globe) vm::showMapHere else null)
                    TextButton(onClick = { sharing = true }, enabled = s.map != null, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Share") }
                }
                if (!s.globe) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Lines", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for ((key, label) in listOf("t" to "T", "td" to "Td", "rh" to "RH")) androidx.compose.material3.FilterChip(
                        selected = key in s.lines, onClick = { vm.setLine(key, key !in s.lines) }, label = { Text(label) }, modifier = Modifier.height(30.dp),
                    )
                }
                s.loadStatus?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val readoutBlock: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            s.readout?.let {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (s.tapLatLon != null) TextButton(onClick = { savingPlace = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Save") }
                    TextButton(onClick = vm::clearTap, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
                }
            }
        }
        val controlsBlock: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            TimingsLine(s.settings.showTimings)
            s.style?.let { Legend(it, emptyList()) }
            if (s.compare && s.paneB != null) s.styleB?.let { Legend(it, emptyList()) }
            if (s.frames.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TimeMenu(s.frames.map { it.valid }, s.index, { i -> frameLabel(s.frames[i]) + if (s.frames[i].valid in s.missing) "  (no file)" else "" }, vm::pickIndex)
                    CompactSlider(
                        value = s.index.toFloat(), onValueChange = { vm.pickIndex(it.toInt()) },
                        valueRange = 0f..(s.frames.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
                TimeStepRow(s.index, s.frames.size, s.playing, vm::togglePlay, vm::pickIndex)
            }
            s.prefetch?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
        if (s.settings.minimalUi) MinimalChrome(
            s.frame?.let { frameLabel(it) + "  ${s.index + 1}/${s.frames.size}" } ?: "", s.busy && s.map != null, panel, readoutBlock, { head(); controlsBlock() },
        ) else {
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(panel).onSizeChanged { topPx = it.height }.statusBarsPadding()) { head() }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(panel).onSizeChanged { bottomPx = it.height }.navigationBarsPadding()) { readoutBlock(); controlsBlock() }
        }
    }

    if (pickDates) {
        val now = System.currentTimeMillis()
        val first = AnalysisBuckets.FIRST_RTMA * 1000L
        val state = androidx.compose.material3.rememberDateRangePickerState(
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in first..now
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
                        vm.setHistory(a / 1000L to minOf(now / 1000L, b / 1000L + 23 * 3600L))      // the whole of the last day
                    },
                ) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DateRangePicker(state, modifier = Modifier.weight(1f, fill = false), title = { Text("Hourly analyses from … to …", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
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
    val live = rememberLiveControl { vm.goToPlace(it, follow = true) }
    if (places) PlacesDialog(s.places, onDismiss = { places = false }, onGo = { places = false; vm.goToPlace(it) }, onDelete = vm::deletePlace, live = live, onEdit = vm::editPlace, read = vm::readAt, marked = s.tapLatLon)
    if (settings) SettingsDialog(
        s.settings, vm::setSettings,
        onExport = { vm.reportNotice("Settings files are exported and imported from the other pages' settings.") },
        onImport = { vm.reportNotice("Settings files are exported and imported from the other pages' settings.") },
        disclaimer = ANALYSIS_DISCLAIMER, showContours = true,
    ) { settings = false }
    if (sharing) ShareDialog(
        canLoop = s.frames.size > 1, current = s.sector.name, globe = s.globe, onDismiss = { sharing = false },
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
        disclaimer = ANALYSIS_DISCLAIMER,
    )
    loopProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {}, confirmButton = {}, title = { Text("Making the loop video") },
            text = { Column { Text("Frame $done of $total"); LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) } },
        )
    }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        title = { Text("About the analysis page") },
        text = {
            Text(
                "NOAA's surface analyses on a 2.5 km grid, from the open data on AWS (noaa-rtma-pds, noaa-urma-pds):\n\n" +
                    "• RTMA rapid update: every 15 minutes, about 15 minutes late. The last six hours use it.\n" +
                    "• URMA: every hour, the analysis made again about six hours later with the observations that came in late. Spans of the past use it, from 2014.\n" +
                    "• RTMA: every hour, from 2013; used where URMA is not there yet.\n\n" +
                    "Only the fields shown are downloaded (about 6 MB each per hour). Relative humidity and dewpoint depression are worked out from the temperature and dewpoint. " +
                    "Spans longer than ten days are thinned to at most 240 frames.\n\n" +
                    "Not affiliated with or endorsed by NOAA or the NWS. Experimental viewing only: do not use it for safety decisions; follow official NWS products."
            )
        },
    )
}
