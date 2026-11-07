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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
@Composable
internal fun GfsScreen(vm: GfsViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.exportSettings(context.contentResolver, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.importSettings(context.contentResolver, uri) }
    }
    LifecycleResumeEffect(Unit) { vm.refreshIfStale(); vm.promptForContact(); onPauseOrDispose { } }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { vm.setDark(dark) }
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    // the level and color chips look at the wind products of every level and color
    val windProducts = remember {
        GfsCatalog.levels.flatMap { l -> WindProducts.colors.mapNotNull { c -> GfsCatalog.windId(l, c.key) } }.map { Product(it, "", "", Style(), false, null, Grid.REFS) }
    }

    Box(Modifier.fillMaxSize().background(mapBackground)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = s.map
            when {
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                }
                bitmap != null -> ZoomableMap(bitmap, s.crosshair, "GFS map", onViewport = { _, _, _, _ -> }, onTap = vm::onTap)
                else -> CircularProgressIndicator()
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
                        }
                    }
                    PageMenu("gfs", onOpen)
                    var more by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { more = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⋮", fontSize = 22.sp) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
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
                        s.file?.let { nbmFrameLabel(it) + "   frame ${s.index + 1}/${s.frames.size}" } ?: "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (s.busy && s.map != null) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(16.dp), strokeWidth = 2.dp)
                    var runMenu by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(onClick = { runMenu = true }, label = { Text(s.cycle?.let { "Run " + it.shortLabel + if (s.following) "" else " · set" } ?: "Run") }, modifier = Modifier.height(32.dp))
                        DropdownMenu(expanded = runMenu, onDismissRequest = { runMenu = false }) {
                            DropdownMenuItem(text = { Text("Newest run") }, onClick = { runMenu = false; vm.pickCycle(null) })
                            for (c in s.cycles) DropdownMenuItem(text = { Text(c.label) }, onClick = { runMenu = false; vm.pickCycle(c) })
                        }
                    }
                    var sectorMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { sectorMenu = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(s.sector.name + " ▾", maxLines = 1) }
                        DropdownMenu(expanded = sectorMenu, onDismissRequest = { sectorMenu = false }) {
                            for (sector in GFS_SECTORS) DropdownMenuItem(text = { Text(sector.name) }, onClick = { sectorMenu = false; vm.pickSector(sector) })
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
                    TextButton(onClick = vm::clearTap, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
                }
            }
            s.style?.let { Legend(it, emptyList()) }
            if (s.frames.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::togglePlay, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (s.playing) "Pause" else "Play") }
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
