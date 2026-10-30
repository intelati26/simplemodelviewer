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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import simplemodelview.Grid
import simplemodelview.Product
import simplemodelview.placeLabel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import simplemodelview.AlertKind
import simplemodelview.AlertShape
import simplemodelview.MrmsCatalog
import simplemodelview.Stop
import simplemodelview.overlayThresholdChoices
import simplemodelview.MrmsProduct
import simplemodelview.Sector
import simplemodelview.fixed
import simplemodelview.formatAge
import simplemodelview.formatClock
import simplemodelview.formatPlus

private const val MRMS_DISCLAIMER = "Data: NOAA/NSSL MRMS. Experimental viewer, not a warning or decision-support tool."

/** The MRMS page: composite reflectivity, rotation tracks and 24 h rainfall (and variants) over the same map machinery as the REFS page. */
@Composable
internal fun MrmsScreen(vm: MrmsViewModel, onSpc: () -> Unit, onNbm: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var overlayDialog by remember { mutableStateOf(false) }
    var alertsDialog by remember { mutableStateOf(false) }
    var overlayPicker by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var savingPlace by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var loopProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.exportSettings(context.contentResolver, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.importSettings(context.contentResolver, uri) }
    }
    LifecycleResumeEffect(Unit) { vm.refreshIfStale(); vm.promptForContact(); vm.setLive(true); onPauseOrDispose { vm.setLive(false) } }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { vm.setDark(dark) }
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    // the catalog as the picker's product type (it groups, searches and stars them like the SPC page does)
    val products = remember { MrmsCatalog.all.map { Product(it.id, it.title, it.group, it.style, false, null, Grid.REFS) } }

    Box(Modifier.fillMaxSize().background(mapBackground)) {
        // the map fills the screen; thin translucent strips float over the empty bands above and below it
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = s.map
            when {
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                s.globe -> GlobeSurface(bitmap, s.cam, s.tapLatLon, "MRMS globe", onSize = vm::setGlobeSize, onGesture = vm::globeGesture, onTap = vm::onGlobeTap)
                bitmap != null -> ZoomableMap(bitmap, s.crosshair, "MRMS map", onViewport = vm::setViewport, onTap = vm::onTap)
                else -> CircularProgressIndicator()
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(panel).statusBarsPadding()) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(onClick = { picker = true }, label = { Text(s.product.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSpc, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("REFS") }
                    TextButton(onClick = onNbm, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("NBM") }
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
                        s.file?.let { "Valid at ${formatClock(it.validTime, System.currentTimeMillis() / 1000)}   frame ${s.index + 1}/${s.files.size}" + if (s.live && s.index == s.files.size - 1) "   \u25CF live" else "" } ?: "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (s.busy && s.map != null) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(16.dp), strokeWidth = 2.dp)
                    SectorMenu(if (s.globe) "Globe" else s.sector.name, onGlobe = { vm.setGlobe(true) }, onPick = vm::pickSector)
                    TextButton(onClick = { sharing = true }, enabled = s.map != null, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Share") }
                }
                MrmsTimesLine(s.file?.uploaded, s.file?.validTime, s.cellKilometers)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(panel).navigationBarsPadding()) {
            for (shape in s.alertHits.take(3)) AlertCard(shape, onRead = { vm.showAlertText(shape) })
            s.readout?.let {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (s.tapLatLon != null) TextButton(onClick = { savingPlace = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Save") }
                    TextButton(onClick = vm::clearTap, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
                }
            }
            if (s.product.levels.isNotEmpty()) HeightRow(s.product, vm::pickLevel)
            OverlayRow(s.overlays, s.overlayStyles, onPick = { overlayDialog = true }, onThreshold = vm::setOverlayThreshold, onClear = { vm.pickOverlay(null) })
            AlertsRow(s.alertKinds, s.alertsAsOf, s.alertNote, newestOnly = s.index != s.files.lastIndex, onClick = { alertsDialog = true })
            s.overlayStyles.lastOrNull()?.let { st -> if (s.overlays.isNotEmpty()) {
                Legend(st.copy(stops = st.stops.filter { it.value >= st.hideBelow }), emptyList())       // (the legend is of the top layer, at full strength)
                s.overlayInfo?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } }
            s.style?.let { Legend(it, emptyList()) }
            if (s.files.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::togglePlay, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (s.playing) "Pause" else "Play") }
                    LoopLengthMenu(s.loopLength, s.files.size, vm::setLoopLength)
                    Slider(
                        value = s.index.toFloat(), onValueChange = { vm.pickIndex(it.toInt()) },
                        valueRange = 0f..(s.files.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(end = 8.dp),
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
        products, products.firstOrNull { it.id == s.product.id }, s.favorites, onToggle = vm::toggleFavorite,
        onPick = { picked -> picker = false; MrmsCatalog.byId(picked.id)?.let(vm::pickProduct) }, onDismiss = { picker = false },
        groups = MrmsCatalog.groups,
    )
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
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, units = s.units, onUnits = vm::setUnits, disclaimer = MRMS_DISCLAIMER, showContours = true,
    ) { settings = false }
    if (sharing) ShareDialog(
        canLoop = s.files.size > 1, current = s.sector.name, globe = s.globe, onDismiss = { sharing = false },
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
                        loopProgress = 0 to s.files.size
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
        disclaimer = MRMS_DISCLAIMER,
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
    if (alertsDialog) AlertsDialog(s.alertKinds, onChange = vm::setAlertKinds, onDismiss = { alertsDialog = false })
    s.alertDetail?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = vm::dismissAlertText,
            confirmButton = { TextButton(onClick = vm::dismissAlertText) { Text("Close") } },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text ?: "Loading\u2026", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("Unofficial copy: the official product is on weather.gov.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
                }
            },
        )
    }
    if (overlayDialog) OverlayDialog(
        current = s.overlayName, onNone = { overlayDialog = false; vm.pickOverlay(null) },
        onNest = { overlayDialog = false; vm.pickOverlayPreset(simplemodelview.OverlayPresets.LIGHTNING_NEST) },
        onProduct = { overlayDialog = false; overlayPicker = true }, onDismiss = { overlayDialog = false },
    )
    if (overlayPicker) ProductPicker(
        products, products.firstOrNull { it.id == s.overlays.singleOrNull()?.product?.id }, s.favorites, onToggle = vm::toggleFavorite,
        onPick = { picked -> overlayPicker = false; MrmsCatalog.byId(picked.id)?.let(vm::pickOverlay) }, onDismiss = { overlayPicker = false },
        groups = MrmsCatalog.groups,
    )
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
internal fun MrmsMenu(onPlaces: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⋮", fontSize = 22.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Places") }, onClick = { open = false; onPlaces() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text("About") }, onClick = { open = false; onAbout() })
        }
    }
}

/** "Overlay: none", or its name with (for a single product) its minimum value, and a clear button. Overlays: other fields (lightning, rotation, ...) drawn over the first. */
@Composable
private fun OverlayRow(layers: List<simplemodelview.OverlayLayer>, styles: List<simplemodelview.Style>, onPick: () -> Unit, onThreshold: (Int) -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f, fill = false)) {
            Text(if (layers.isEmpty()) "Overlay: none" else "Overlay: ${simplemodelview.overlayName(layers)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val style = styles.singleOrNull()
        if (layers.size == 1 && style != null) {
            val choices = overlayThresholdChoices(style)
            val index = layers[0].thresholdIndex
            if (choices.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("\u2265 ${MapRenderer.label(Stop(choices[index.coerceIn(0, choices.size - 1)], 0), style)} ${style.units} \u25BE") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        choices.forEachIndexed { i, v -> DropdownMenuItem(text = { Text("\u2265 ${MapRenderer.label(Stop(v, 0), style)} ${style.units}") }, onClick = { open = false; onThreshold(i) }) }
                    }
                }
            }
        }
        if (layers.isNotEmpty()) TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("\u2715") }
    }
}

/** What to draw over the map: nothing, the lightning nest (strikes by recency), or one product of your choice. */
@Composable
private fun OverlayDialog(current: String, onNone: () -> Unit, onNest: () -> Unit, onProduct: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Overlay") },
        text = {
            Column {
                @Composable fun choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
                    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
                        Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
                        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                choice("No overlay", null, current.isEmpty(), onNone)
                choice("Lightning nest", "Strikes by recency: the last minute solid, the last 5 minutes at 50 %, the last 30 minutes as a 20 % shadow", current == "Lightning nest", onNest)
                choice("Choose a product\u2026", "Rotation tracks (with a minimum value), strikes, hail size, any other field", current.isNotEmpty() && current != "Lightning nest", onProduct)
            }
        },
    )
}

/** "Alerts: off", or the chosen layers with when they were read. They are drawn on the newest frame only. */
@Composable
private fun AlertsRow(kinds: Set<AlertKind>, asOf: Long?, note: String?, newestOnly: Boolean, onClick: () -> Unit) {
    Column {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(
                if (kinds.isEmpty()) "Alerts: off" else "Alerts: " + kinds.sortedBy { it.ordinal }.joinToString(", ") { it.label } +
                    (asOf?.let { " \u00B7 as of ${formatClock(it)}" } ?: ""),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (kinds.isNotEmpty() && (note != null || newestOnly)) {
            Text(note ?: "Alerts are shown on the newest frame only.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The outlines under the tapped point: a color key, the title and times, and a button for the full text. */
@Composable
private fun AlertCard(shape: AlertShape, onRead: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(Color(0xFF000000.toInt() or shape.color)))
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
            Text(shape.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (shape.summary.isNotEmpty()) Text(shape.summary, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onRead, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Read") }
    }
}

/** Which alert layers to draw (all off by default), with what each is and the caution that this is not the official source. */
@Composable
private fun AlertsDialog(kinds: Set<AlertKind>, onChange: (Set<AlertKind>) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Alerts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val detail = mapOf(
                    AlertKind.Warning to "Tornado, severe thunderstorm, flash flood and other warnings (NWS)",
                    AlertKind.Watch to "Tornado, severe thunderstorm and flash flood watches (NWS / SPC)",
                    AlertKind.Discussion to "SPC mesoscale discussions and WPC precipitation discussions, dashed",
                )
                for (kind in AlertKind.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(kind.label, fontWeight = FontWeight.Bold)
                            Text(detail.getValue(kind), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = kind in kinds, onCheckedChange = { on -> onChange(if (on) kinds + kind else kinds - kind) })
                    }
                }
                Text(
                    "Drawn on the newest frame only, as of the time shown; tap an outline for its text. This is an unofficial copy of public NWS, SPC and WPC data and can be late " +
                        "or incomplete: use weather.gov and official channels for warnings.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** The height of a 3D mosaic field: - / + buttons and a slider over its levels (loading starts when the slider is let go) with the height in km and kft. */
@Composable
private fun HeightRow(product: MrmsProduct, onPick: (Int) -> Unit) {
    val last = product.levels.size - 1
    var dragging by remember(product.id, product.level) { mutableStateOf<Float?>(null) }
    val shown = dragging?.toInt() ?: product.level
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPick(product.level - 1) }, enabled = product.level > 0, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("\u2212") }
        Slider(
            value = (dragging ?: product.level.toFloat()), onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { onPick(it.toInt()) } },
            valueRange = 0f..last.toFloat(), steps = maxOf(0, last - 1), modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onPick(product.level + 1) }, enabled = product.level < last, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+") }
        Text(product.copy(level = shown).levelLabel, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
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
