package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import simplemodelview.Place
import simplemodelview.fixed
import simplemodelview.VideoSize
import simplemodelview.formatAge
import simplemodelview.epochSeconds
import simplemodelview.formatClock
import simplemodelview.formatPlus
import simplemodelview.formatUtc
import simplemodelview.isValidEmail
import simplemodelview.placeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import simplemodelview.Cycle
import simplemodelview.Groups
import simplemodelview.Kind
import simplemodelview.Palettes
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.Style

class MainActivity : ComponentActivity() {
    private val viewModel: RefsViewModel by viewModels()
    private val mrmsViewModel: MrmsViewModel by viewModels()
    private val nbmViewModel: NbmViewModel by viewModels()
    private val gfsViewModel: GfsViewModel by viewModels()
    private val pointViewModel: PointViewModel by viewModels()
    private val analysisViewModel: AnalysisViewModel by viewModels()
    private val compareViewModel: CompareViewModel by viewModels()
    private val outlookViewModel: OutlookBrowserViewModel by viewModels()
    private val todayViewModel: TodayViewModel by viewModels()

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onStart() { super.onStart(); AppContainer.renders.appVisible = true }
    override fun onStop() { AppContainer.renders.appVisible = false; super.onStop() }

    /** The tap on a "ready to share" notification: offer that render. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getLongExtra(RenderService.EXTRA_RENDER, -1L).takeIf { it >= 0 }?.let { AppContainer.renders.offer.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getLongExtra(RenderService.EXTRA_RENDER, -1L)?.takeIf { it >= 0 }?.let { AppContainer.renders.offer.value = it }
        // which page opens: an intent extra (`--es page mrms`, used by the emulator scripts), else Today
        val start = intent?.getStringExtra("page") ?: "today"
        setContent {
            val dark = isSystemInDarkTheme()
            var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(listOf("today", "spc", "mrms", "nbm", "gfs", "point", "analysis", "compare", "outlook").firstOrNull { it == start && (AppMode.offers(it) || intent?.hasExtra("page") == true) } ?: "today") }
            fun open(p: String) { AppContainer.prefs.edit().putString("page", p).apply(); page = p }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                // a render that finished (or whose notification was tapped): the system share sheet for it
                val offered by AppContainer.renders.offer.collectAsStateWithLifecycle()
                val renderContext = LocalContext.current
                LaunchedEffect(offered) {
                    val id = offered ?: return@LaunchedEffect
                    AppContainer.renders.offer.value = null
                    AppContainer.renders.get(id)?.let { j -> j.file?.takeIf { it.exists() }?.let { shareFile(renderContext, it, j.mime, "Share") } }
                }
                // notifications for the progress of a render: asked for once, when the first one is queued
                val renderJobs by AppContainer.renders.jobs.collectAsStateWithLifecycle()
                LaunchedEffect(renderJobs.isNotEmpty()) {
                    if (renderJobs.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 33 && !AppContainer.prefs.getBoolean("asked_notifications", false) &&
                        androidx.core.content.ContextCompat.checkSelfPermission(renderContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        AppContainer.prefs.edit().putBoolean("asked_notifications", true).apply()
                        notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                // in landscape the navigation bar and the camera cutout sit at the sides: keep the pages clear of them (top and bottom are padded by each page)
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), color = MaterialTheme.colorScheme.background) {
                    // each page's view model is created only when the page is first opened
                    // the page menu opens any page; the page being opened reads the favorites, places and settings the others may have changed
                    val go: (String) -> Unit = { p ->
                        open(p)
                        when (p) { "spc" -> viewModel.reloadFromPrefs(); "mrms" -> mrmsViewModel.reloadFromPrefs(); "nbm" -> nbmViewModel.reloadFromPrefs(); "analysis" -> analysisViewModel.reloadFromPrefs() }
                    }
                    // the extended point view opens the synoptic set up of a time on the REFS page, or on the GFS page beyond its range
                    val toSynoptic: (Long, Cycle?) -> Unit = { valid, run ->
                        if (run != null) { viewModel.showValid(valid, run); go("spc") } else { gfsViewModel.showValid(valid); go("gfs") }
                    }
                    if (page == "today") TodayScreen(todayViewModel, go)
                    else if (page == "point") PointScreen(pointViewModel, go, toSynoptic)
                    else if (page == "gfs") GfsScreen(gfsViewModel, go)
                    else if (page == "analysis") AnalysisScreen(analysisViewModel, go)
                    else if (page == "compare") CompareScreen(compareViewModel, go)
                    else if (page == "outlook") OutlookScreen(outlookViewModel, go)
                    else if (page == "nbm") NbmScreen(nbmViewModel, go)
                    else if (page == "mrms") MrmsScreen(mrmsViewModel, go)
                    else {
                        LaunchedEffect(dark) { viewModel.setDark(dark) }
                        RefsScreen(viewModel, go)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefsScreen(vm: RefsViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    var soundingStations by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var savingPlace by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val outlookState by AppContainer.outlook.state.collectAsStateWithLifecycle()
    var outlookDialog by remember { mutableStateOf(false) }
    // settings export / import go through the system file pickers: the app needs no storage permission
    var restartAsk by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.exportSettings(context.contentResolver, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { if (vm.importSettings(context.contentResolver, uri)) restartAsk = true }
    }
    LifecycleResumeEffect(Unit) { vm.checkForNewCycle(); vm.promptForContact(); AppContainer.outlook.refreshIfStale(); onPauseOrDispose { } }

    val dark = isSystemInDarkTheme()
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)     // the map picture's own background: no dead white around it
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

    // The map fills the whole screen (under the system bars); the controls are two thin translucent strips floating over the empty
    // bands above and below it. Tablets / landscape: controls in a column on the left, the map filling the rest.
    var topPx by remember { mutableStateOf(0) }       // the heights of the strips floating over the map: the compare's panes fit between them
    var bottomPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(mapBackground)) {
        val wide = maxWidth >= 720.dp && maxWidth > maxHeight
        val head: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
          // compact header: chips and buttons are 32 dp tall instead of being padded out to the 48 dp touch-target minimum
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val activeProduct = vm.activeProduct(s)
                val at = s.products.indexOfFirst { it.id == activeProduct?.id }
                ProductStepButton(next = false, enabled = at > 0) { vm.stepProduct(-1) }
                AssistChip(onClick = { picker = true }, label = { Text(activeProduct?.title ?: "Product", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f))
                ProductStepButton(next = true, enabled = at in 0 until s.products.size - 1) { vm.stepProduct(1) }
                CycleMenu(s.cycle, s.cycles, vm::pickCycle)
                PageMenu("spc", onOpen)
                MoreMenu(onPlaces = { places = true }, onSettings = { settings = true }, onAbout = { about = true }, compare = s.compare, canCompare = s.other != null, onGlobe = s.globe, onCompare = vm::setCompare, onSwap = vm::swapPanes, canSound = s.tapLatLon != null, onSounding = { soundingStations = true })
            }
            if (vm.activeProduct(s)?.hasMembers == true && s.members.isNotEmpty()) MemberMenu(s.members, s.member, vm::pickMember)
            if (vm.activeProduct(s)?.let { simplemodelview.WindProducts.isWind(it.id) } == true) WindChooser(s.products, s.windLevel, s.windColor, vm::pickWindLevel, vm::pickWindColor)

            s.newerCycle?.let {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("New run ${it.label} is available", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::loadNewerCycle) { Text("Load") }
                    TextButton(onClick = vm::dismissNewerCycle) { Text("Later") }
                }
            }
            s.notice?.let {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::dismissNotice) { Text("OK") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(s.cycle?.let { "Run ${it.label}" }, s.validLabel.takeIf { it.isNotEmpty() }?.let { "Valid $it" },
                        s.forecastHour?.let { "f${it.toString().padStart(2, '0')}" }).joinToString("   "),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (s.busy && s.map != null) BusySpinner(Modifier.padding(end = 2.dp))
                SectorMenu(if (s.globe) "Globe" else s.sector.name, onGlobe = { vm.setGlobe(true) }, onPick = { vm.setGlobe(false); vm.pickSector(it) }, onMapHere = if (s.globe) vm::showMapHere else null)
                TextButton(onClick = { sharing = true }, enabled = s.map != null, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Share") }
            }
            TimesLine(s.fileTime, s.runComplete, s.cycle, s.times.getOrNull(s.timeIndex))
          }
        }
        val mapArea: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier, contentAlignment = Alignment.Center) {
                when {
                    s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(s.error!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                    }
                    s.map != null || s.globe -> if (s.globe) GlobeView(s, vm) else MapView(s, vm, with(density) { topPx.toDp() }, with(density) { bottomPx.toDp() })
                    else -> BusySpinner(big = true)
                }
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
            OutlookRow(outlookState, s.times.getOrNull(s.timeIndex), onClick = { outlookDialog = true })
            TimingsLine(s.settings.showTimings)
            s.style?.let { Legend(it, s.members, withMembers = s.product?.id?.let(simplemodelview.PaintballLines::isPaintballLines) == true) }
            if (s.compare && s.paneB != null) s.styleB?.let { Legend(it, s.members) }
            if (s.times.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!AppMode.simple) LoopLengthMenu(s.loopLength, s.times.size, vm::setLoopLength)
                    TextButton(onClick = vm::goToNow, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Now") }
                    TimeMenu(s.times, s.timeIndex, { i -> s.cycle?.let { c -> "${formatUtc(s.times[i])}  (f${((s.times[i] - c.epochSeconds) / 3600).toString().padStart(2, '0')})" } ?: formatUtc(s.times[i]) }, vm::pickTime)
                    CompactSlider(
                        value = s.timeIndex.toFloat(), onValueChange = { vm.pickTime(it.toInt()) },
                        valueRange = 0f..(s.times.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
                if (!AppMode.simple) LoopRangeBar(s.times, { vm.loopWindowFor(s) }, s.timeIndex, s.loopTimes != null, vm::setLoopTimes) { vm.setLoopLength(0) }
                TimeStepRow(s.timeIndex, s.times.size, s.playing, vm::togglePlay, vm::pickTime, loopToggle = !AppMode.simple)
            }
            s.prefetch?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
        val foot: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = { readoutBlock(); controlsBlock() }
        if (s.settings.minimalUi) {
            mapArea(Modifier.fillMaxSize())
            MinimalChrome(
                listOfNotNull(s.cycle?.let { "Run ${it.label}" }, s.validLabel.takeIf { it.isNotEmpty() }?.let { "Valid $it" }, s.forecastHour?.let { "f${it.toString().padStart(2, '0')}" }).joinToString("  "),
                s.busy && s.map != null, panel, readoutBlock, { head(); controlsBlock() },
            )
        } else if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(380.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) { head(); foot() }
                mapArea(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            mapArea(Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(panel).onSizeChanged { topPx = it.height }.statusBarsPadding()) { head() }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(panel).onSizeChanged { bottomPx = it.height }.navigationBarsPadding()) { foot() }
        }
    }

    if (s.contactPrompt) ContactDialog(onSave = { vm.setSettings(s.settings.copy(contactEmail = it)); vm.dismissContactPrompt() }, onNotNow = vm::dismissContactPrompt)
    if (picker) ProductPicker(simplemodelview.Catalog.forPicker(s.products), vm.activeProduct(s)?.takeIf { !simplemodelview.WindProducts.isWind(it.id) } ?: s.products.firstOrNull { it.id == simplemodelview.WindProducts.CHOOSER_ID }, s.favorites, onToggle = vm::toggleFavorite, onPick = { picker = false; vm.pickProduct(it) }, onDismiss = { picker = false }, simpleIds = simplemodelview.SimpleProducts.refs)
    if (outlookDialog) OutlookDialog(outlookState.type, onPick = { outlookDialog = false; AppContainer.outlook.setType(it) }, onDismiss = { outlookDialog = false })
    if (savingPlace) s.tapLatLon?.let { (lat, lon) ->
        var name by remember { mutableStateOf(placeLabel(lat, lon)) }
        AlertDialog(
            onDismissRequest = { savingPlace = false },
            title = { Text("Save place") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { AppContainer.places.save(name.trim(), lat, lon); savingPlace = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { savingPlace = false }) { Text("Cancel") } },
        )
    }
    val live = rememberLiveControl { vm.goToPlace(it, follow = true) }
    if (soundingStations) s.tapLatLon?.let { (la, lo) -> SoundingStationsDialog(la, lo) { soundingStations = false } }
    if (places) PlacesDialog(
        s.places, onDismiss = { places = false },
        onGo = { places = false; vm.goToPlace(it) },
        onDelete = AppContainer.places::delete, live = live, onEdit = AppContainer.places::edit, read = vm::readAt, marked = s.tapLatLon,
    )
    if (restartAsk) RestartDialog(onLater = { restartAsk = false })
    if (settings) SettingsDialog(s.settings, vm::setSettings, onExport = { exportLauncher.launch("simplemodelview-settings.json") }, onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, showContours = true, showBarbs = true) { settings = false }
    if (sharing) ShareDialog(
        canLoop = s.times.size > 1, current = s.sector.name, globe = s.globe, comparing = s.compare && s.other != null, onDismiss = { sharing = false },
        initialSize = AppContainer.videoSize,
        estimate = { area, size -> vm.estimateLoopMegabytes(area, size) },
        onShare = { area, loop, size ->
            AppContainer.videoSize = size
            sharing = false
            scope.launch {
                if (!loop) {
                    val file = vm.buildShareFile(area, size) ?: return@launch
                    shareFile(context, file, "image/png", "Share map")
                } else {
                    // made in the background (the Renders list and a notification show how far it is): the share sheet opens when it is done
                    AppContainer.renders.enqueueLoop("REFS loop · ${s.product?.title.orEmpty()}", vm::reportNotice) { progress -> vm.buildLoopFile(area, size, progress) }
                }
            }
        },
    )
        if (about) AlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        title = { Text("About simplemodelview") },
        text = {
            Text(
                "simplemodelview is an unofficial viewer for the Storm Prediction Center's experimental REFS ensemble products.\n\n" +
                    "Data: NOAA/NWS Storm Prediction Center. Not affiliated with or endorsed by NOAA, the NWS or SPC. " +
                    "Experimental guidance only: do not use it for warnings or safety decisions; follow official NWS products.\n\n" +
                    "License: GPLv3."
            )
        },
    )
}

/** The REFS globe (see [GlobeSurface]). */
@Composable
private fun GlobeView(s: UiState, vm: RefsViewModel) {
    GlobeSurface(s.map, s.cam, s.tapLatLon, "SPC REFS globe", onSize = vm::setGlobeSize, onGesture = vm::globeGesture, onTap = vm::onGlobeTap)
}

/** The REFS map picture (see [ZoomableMap]). */
@Composable
private fun MapView(s: UiState, vm: RefsViewModel, insetTop: Dp = 0.dp, insetBottom: Dp = 0.dp) {
    val bitmap = s.map ?: return
    val second = s.paneB
    if (s.compare && second != null && s.other != null && !s.playing) {
        ComparePanes(Modifier.padding(top = insetTop, bottom = insetBottom), bitmap, second, s.product?.title.orEmpty(), s.titleB.orEmpty(), if (isSystemInDarkTheme()) Color(0xFF202428) else Color(0xFFEDEFF2), s.crosshair, s.detail, vm::setViewport, vm::onTap, vm::onTap, s.focus, vm::focusApplied, s.activePane, vm::selectPane)
        return
    }
    ZoomableMap(bitmap, s.crosshair, "SPC REFS map", detail = s.detail, onViewport = vm::setViewport, onTap = vm::onTap, focus = s.focus, onFocusApplied = vm::focusApplied)
}

@Composable
internal fun Legend(style: Style, members: List<String>, withMembers: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        if (style.kind == Kind.Paintball || withMembers) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 10) Box(
                    Modifier.weight(1f).height(10.dp).background(Color(0xFF000000.toInt() or (Palettes.memberColors[members.getOrNull(i)] ?: 0x808080))),
                )
            }
            Text(if (withMembers) "Cells: a member over the threshold. Lines: the share of members, in %" else "Each color: a member over the threshold", style = MaterialTheme.typography.labelSmall)
            if (style.kind == Kind.Paintball) return
        }
        if (style.stops.isEmpty()) return
        if (style.kind == Kind.Categorical) {      // a swatch and name per category
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                style.stops.forEachIndexed { i, stop ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(Color(0xFF000000.toInt() or stop.rgb)))
                        Text(" " + style.labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            return
        }
        // the color bar with a number under the start of its colors, as many as fit (the shared picture's legend does the same), and the units over its right end
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stops = style.stops
            val labels = stops.map { MapRenderer.label(it, style) }
            val top = style.top?.let { MapRenderer.label(simplemodelview.Stop(it, 0), style) }
            val widest = (labels + listOfNotNull(top)).maxOf { it.length }
            val labelWidth = (widest * 6.6f + 8f).dp                          // a small label: about 6.6 dp a character, and a gap
            val every = kotlin.math.ceil(stops.size * labelWidth.value / maxWidth.value).toInt().coerceAtLeast(1)
            val last = (stops.size - 1) / every * every
            val showTop = top != null && last <= stops.size - 2 * every
            Column(Modifier.fillMaxWidth()) {
                if (style.units.isNotEmpty()) Text(style.units, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Row(Modifier.fillMaxWidth()) {
                    for (stop in stops) Box(Modifier.weight(1f).height(7.dp).background(Color(0xFF000000.toInt() or stop.rgb)))
                }
                Box(Modifier.fillMaxWidth().height(14.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (i in stops.indices) Box(Modifier.weight(1f)) {
                            if (i % every == 0) Text(labels[i], Modifier.wrapContentWidth(Alignment.Start, unbounded = true), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                    if (showTop) Text(top!!, Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun SectorMenu(current: String, onGlobe: (() -> Unit)?, onPick: (Sector) -> Unit, onMapHere: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(current) }
    if (open) SectorPicker(
        current, Sector.all,
        onGlobe = onGlobe?.let { g -> { open = false; g() } }, onMapHere = onMapHere?.let { m -> { open = false; m() } },
        onPick = { open = false; onPick(it) }, onDismiss = { open = false },
    )
}

@Composable
private fun RowScope.CycleMenu(current: Cycle?, cycles: List<Cycle>, onPick: (Cycle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(current?.let { "${it.hour.toString().padStart(2, '0')}Z" } ?: "Run") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (cycle in cycles) DropdownMenuItem(text = { Text(cycle.label) }, onClick = { open = false; onPick(cycle) })
        }
    }
}

/** The Heights & Winds product's two choices: the level (500 / 700 / 850 mb) and what is in color under its height contours and barbs. */
@Composable
internal fun WindChooser(products: List<Product>, level: Int, colorKey: String, onLevel: (Int) -> Unit, onColor: (String) -> Unit) {
    val ids = products.map { it.id }.toSet()
    val levels = (simplemodelview.WindProducts.levels + simplemodelview.WindProducts.STACK).filter { l -> simplemodelview.WindProducts.id(l) in ids }
    fun levelName(l: Int) = if (l == simplemodelview.WindProducts.STACK) "500 mb height, 850/700/500 winds" else "$l mb"
    val colors = simplemodelview.WindProducts.colors.filter { c -> simplemodelview.WindProducts.id(level, c) in ids }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var levelOpen by remember { mutableStateOf(false) }
        Box {
            AssistChip(onClick = { levelOpen = true }, label = { Text(levelName(level) + " \u25BE") })
            DropdownMenu(expanded = levelOpen, onDismissRequest = { levelOpen = false }) {
                for (l in levels) DropdownMenuItem(text = { Text(levelName(l)) }, onClick = { levelOpen = false; onLevel(l) })
            }
        }
        var colorOpen by remember { mutableStateOf(false) }
        Box {
            AssistChip(onClick = { colorOpen = true }, label = { Text("Color: " + (colors.firstOrNull { it.key == colorKey }?.title ?: "Wind speed") + " \u25BE") })
            DropdownMenu(expanded = colorOpen, onDismissRequest = { colorOpen = false }) {
                for (c in colors) DropdownMenuItem(text = { Text(if (c.key == "tlev") "Temperature at ${if (level == simplemodelview.WindProducts.STACK) 500 else level} mb" else c.title) }, onClick = { colorOpen = false; onColor(c.key) })
            }
        }
    }
}

@Composable
private fun MemberMenu(members: List<String>, current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        AssistChip(onClick = { open = true }, label = { Text("Member: " + Palettes.memberDisplayName(members.getOrElse(current) { "?" })) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            members.forEachIndexed { i, name -> DropdownMenuItem(text = { Text(Palettes.memberDisplayName(name)) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

internal const val FAVORITES = "Favorites"

/** The product list in collapsible buckets like SPC's own menu (Guidance, Synoptic, Severe, ...), with Favorites first. All start collapsed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductPicker(
    products: List<Product>, current: Product?, favorites: Set<String>,
    onToggle: (Product) -> Unit, onPick: (Product) -> Unit, onDismiss: () -> Unit, groups: List<String> = Groups.ordered,
    topItem: (@Composable () -> Unit)? = null,
    /** Simple mode: only these ids (and the starred ones and the one on screen) are listed until "All products" is tapped; a search looks through everything. */
    simpleIds: Set<String>? = null,
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(setOf<String>()) }        // buckets the user has expanded
    val words = query.split(' ').filter { it.isNotBlank() }
    val searching = words.isNotEmpty()
    val short = simpleIds != null && AppMode.simple && !showAll && !searching
    val shown = products.filter { p -> words.all { p.title.contains(it, ignoreCase = true) } && (!short || p.id in simpleIds!! || p.id in favorites || p.id == current?.id) }
    // while searching, every bucket with a match is open; otherwise only the ones the user opened
    val buckets: List<Pair<String, List<Product>>> =
        listOf(FAVORITES to shown.filter { it.id in favorites }) + groups.map { g -> g to shown.filter { it.group == g } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        topItem?.invoke()
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("Search products") })
        LazyColumn(Modifier.fillMaxWidth()) {
            for ((name, list) in buckets) {
                if (list.isEmpty() && (name != FAVORITES || searching)) continue
                val expanded = if (searching || short) true else name in open
                item(key = "h-$name") {
                    BucketHeader(name, list.size, expanded) { if (!searching) open = if (expanded) open - name else open + name }
                }
                if (!expanded) continue
                if (list.isEmpty()) {
                    item(key = "e-$name") { Text("Tap the star on a product to keep it here.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                } else {
                    items(list, key = { "$name/${it.id}" }) { ProductRow(it, it.id == current?.id, it.id in favorites, onToggle, onPick) }
                }
            }
            if (simpleIds != null && AppMode.simple && !searching) item(key = "all-toggle") {
                TextButton(onClick = { showAll = !showAll }, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { Text(if (showAll) "Fewer products" else "All products (${products.size})") }
            }
        }
    }
}

@Composable
private fun BucketHeader(name: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "\u25BE" else "\u25B8", Modifier.padding(end = 10.dp), fontSize = 16.sp)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ProductRow(product: Product, selected: Boolean, favorite: Boolean, onToggle: (Product) -> Unit, onPick: (Product) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPick(product) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(product.title, Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
        TextButton(onClick = { onToggle(product) }) { Text(if (favorite) "★" else "☆", fontSize = 20.sp) }
    }
    HorizontalDivider()
}

@Composable
private fun MoreMenu(onPlaces: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit, compare: Boolean, canCompare: Boolean, onGlobe: Boolean, onCompare: (Boolean) -> Unit, onSwap: () -> Unit, canSound: Boolean, onSounding: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("\u22EE", fontSize = 22.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!AppMode.simple) DropdownMenuItem(text = { Text(if (compare) "One picture" else if (onGlobe) "Compare (on the nearest region)" else "Compare with the previous product") }, enabled = compare || canCompare, onClick = { open = false; onCompare(!compare) })
            if (compare && !AppMode.simple) DropdownMenuItem(text = { Text("Swap the two panes") }, onClick = { open = false; onSwap() })
            if (!AppMode.simple) DropdownMenuItem(text = { Text(if (canSound) "Sounding stations near the crosshair" else "Sounding stations (tap the map first)") }, enabled = canSound, onClick = { open = false; onSounding() })
            DropdownMenuItem(text = { Text("Places") }, onClick = { open = false; onPlaces() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text("About") }, onClick = { open = false; onAbout() })
        }
    }
}

@Composable
internal fun PlacesDialog(
    places: List<Place>, onDismiss: () -> Unit, onGo: (Place) -> Unit, onDelete: (Place) -> Unit, live: LiveControl? = null,
    /** Edit a place (old, new name, latitude, longitude), read the shown field at a position, and the position the crosshair is on (for "use the crosshair"). */
    onEdit: ((Place, String, Double, Double) -> Unit)? = null, read: ((Double, Double) -> String)? = null, marked: Pair<Double, Double>? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var reads by remember { mutableStateOf(mapOf<String, String>()) }
    var editing by remember { mutableStateOf<Place?>(null) }
    var finding by remember { mutableStateOf(false) }
    fun readMine() {
        finding = true
        reads = reads + (LiveLocation.NAME to "Finding where you are…")
        LiveLocation.once(context) { fix ->
            finding = false
            reads = reads + (LiveLocation.NAME to (if (fix == null) "No location fix (is location on, and allowed for this app?)" else read?.invoke(fix.lat, fix.lon) ?: placeLabel(fix.lat, fix.lon)))
        }
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) readMine() else reads = reads + (LiveLocation.NAME to "Location is not allowed for this app.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Places") },
        text = {
          Column {
            PlaceSearchBox { hit -> val saved = Place(hit.name, hit.lat, hit.lon); AppContainer.places.save(saved.name, saved.lat, saved.lon); onGo(saved) }
            live?.let {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("My location (live)", fontWeight = FontWeight.Bold)
                        Text("Keeps the crosshair on where you are.", style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(checked = it.on, onCheckedChange = it.set)
                }
                HorizontalDivider()
            }
            if (read != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Where I am now", fontWeight = FontWeight.Bold)
                    TextButton(enabled = !finding, onClick = {
                        if (LiveLocation.hasPermission(context)) readMine()
                        else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Read here") }
                    reads[LiveLocation.NAME]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider()
            }
            if (places.isEmpty()) Text("No saved places yet. Tap the map, then choose Save place; Show jumps back to it.")
            else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                for (p in places) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(placeLabel(p.lat, p.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { onGo(p) }) { Text("Show") }
                            if (read != null) TextButton(onClick = { reads = reads + (p.name to read(p.lat, p.lon)) }) { Text("Read") }
                            if (onEdit != null) TextButton(onClick = { editing = p }) { Text("Edit") }
                            TextButton(onClick = { onDelete(p) }) { Text("Delete") }
                        }
                        reads[p.name]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider()
                }
            }
          }
        },
    )
    editing?.let { place ->
        EditPlaceDialog(place, marked, onUseMine = { done -> LiveLocation.once(context) { fix -> done(fix) } }, onDismiss = { editing = null }) { name, lat, lon ->
            onEdit?.invoke(place, name, lat, lon)
            reads = reads - place.name
            editing = null
        }
    }
}

/** Rename a place and refine where it is: type the coordinates, or take them from the crosshair or from where you are. */
@Composable
private fun EditPlaceDialog(place: Place, marked: Pair<Double, Double>?, onUseMine: (((Place?) -> Unit)) -> Unit, onDismiss: () -> Unit, onSave: (String, Double, Double) -> Unit) {
    var name by remember { mutableStateOf(place.name) }
    var lat by remember { mutableStateOf(fixed(place.lat, 4)) }
    var lon by remember { mutableStateOf(fixed(place.lon, 4)) }
    var note by remember { mutableStateOf<String?>(null) }
    val la = lat.trim().toDoubleOrNull(); val lo = lon.trim().toDoubleOrNull()
    val valid = la != null && lo != null && simplemodelview.validLatLon(la, lo)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val keys = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                    androidx.compose.material3.OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true, keyboardOptions = keys, modifier = Modifier.weight(1f))
                    androidx.compose.material3.OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, singleLine = true, keyboardOptions = keys, modifier = Modifier.weight(1f))
                }
                Text("Degrees; north and east are positive (west is negative: -97.44).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    if (marked != null) TextButton(onClick = { lat = fixed(marked.first, 4); lon = fixed(marked.second, 4); note = null }) { Text("Use the crosshair") }
                    TextButton(onClick = { note = "Finding where you are…"; onUseMine { fix -> if (fix != null) { lat = fixed(fix.lat, 4); lon = fixed(fix.lon, 4); note = null } else note = "No location fix (is location on, and allowed for this app?)" } }) { Text("Use my location") }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!valid) Text("Latitude -90 to 90, longitude -180 to 180.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(name, la!!, lo!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SettingsDialog(
    settings: Settings, onChange: (Settings) -> Unit, onExport: () -> Unit, onImport: () -> Unit,
    units: simplemodelview.Units? = null, onUnits: (simplemodelview.Units) -> Unit = {}, disclaimer: String = DISCLAIMER, showContours: Boolean = false, showBarbs: Boolean = false, showCrop: Boolean = false, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow("Download loops on Wi-Fi only", "Play will not start a loop on a metered connection (a loop is about 15 MB).", settings.wifiOnlyLoop) {
                    onChange(settings.copy(wifiOnlyLoop = it))
                }
                SwitchRow("Data saver", "Draw the whole-US map at half resolution: about 4x less memory. Downloads are the same size.", settings.dataSaver) {
                    onChange(settings.copy(dataSaver = it))
                }
                SwitchRow("Minimal layout", "The map fills the screen; the product, run, loop and overlay controls open from a menu button.", settings.minimalUi) {
                    onChange(settings.copy(minimalUi = it))
                }
                if (showContours) SwitchRow("Contour lines", "Draw lines for heights, sea-level pressure and temperatures over the colors (REFS), and for the freezing level and model temperatures (MRMS).", settings.contours) {
                    onChange(settings.copy(contours = it))
                }
                if (showCrop) SwitchRow("Crop regions with NOMADS", "Ask NOAA's NOMADS for just the sector's box of each GFS field (tens of kilobytes instead of about 0.8 MB). If NOMADS is off or does not answer, AWS gives the field down to the sector's south edge (about half), and only then the whole field.", settings.gfsCrop) {
                    onChange(settings.copy(gfsCrop = it))
                }
                if (showBarbs) Column {
                    Text("Wind barb spacing")
                    androidx.compose.material3.Slider(
                        value = settings.barbDensity.toFloat(),
                        onValueChange = { v -> val d = Math.round(v).coerceIn(1, 5); if (d != settings.barbDensity) onChange(settings.copy(barbDensity = d)) },
                        valueRange = 1f..5f, steps = 3,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sparse", style = MaterialTheme.typography.labelSmall); Text("Dense", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column {
                    Text("County lines")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((mode, name) in listOf(0 to "Auto", 1 to "Always", 2 to "Never")) FilterChip(settings.counties == mode, { onChange(settings.copy(counties = mode)) }, label = { Text(name) })
                    }
                    Text("Auto draws them when the map is zoomed to a sector.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SwitchRow("Show timings", "A small line under the map with how long the last picture took, stage by stage: data, raster, layers under and over it, lines, contours, barbs.", settings.showTimings) {
                    onChange(settings.copy(showTimings = it))
                }
                var logTimings by remember { mutableStateOf(AppContainer.prefs.getBoolean("log_timings", false)) }
                SwitchRow("Log timings", "Also write each stage time to Logcat (tag Timings), to measure a drag or a loop from a computer: adb logcat -s Timings.", logTimings) {
                    logTimings = it; Timings.logging = it; AppContainer.prefs.edit().putBoolean("log_timings", it).apply()
                }
                SwitchRow("Use these in exports too", "Wind barb spacing and county lines in shared pictures and loops. Off: exports keep the standard look, so the screen can show more than reads well in a picture.", settings.optionsInExport) {
                    onChange(settings.copy(optionsInExport = it))
                }
                var storage by remember { mutableStateOf(false) }
                Column {
                    Text("Storage and cache")
                    Text("What the app keeps on the device (downloaded files, model data, rainfall days), how much space it takes, and how to clear it.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { storage = true }) { Text("Open\u2026") }
                }
                if (storage) StorageDialog(settings, onChange) { storage = false }
                if (units != null) Column {      // (the MRMS page only: SPC's fields have their own fixed units)
                    Text("Units (MRMS page)")
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!units.metricLength, { onUnits(units.copy(metricLength = false)) }, label = { Text("Rain: in") })
                        FilterChip(units.metricLength, { onUnits(units.copy(metricLength = true)) }, label = { Text("mm") })
                        FilterChip(!units.celsius, { onUnits(units.copy(celsius = false)) }, label = { Text("Temp: \u00B0F") })
                        FilterChip(units.celsius, { onUnits(units.copy(celsius = true)) }, label = { Text("\u00B0C") })
                        FilterChip(!units.kilometers, { onUnits(units.copy(kilometers = false)) }, label = { Text("Height: kft") })
                        FilterChip(units.kilometers, { onUnits(units.copy(kilometers = true)) }, label = { Text("km") })
                    }
                }
                var email by remember { mutableStateOf(settings.contactEmail) }
                // follow a change from outside the field (an imported settings file) without disturbing what is being typed
                LaunchedEffect(settings.contactEmail) { if (isValidEmail(settings.contactEmail) && settings.contactEmail != email.trim()) email = settings.contactEmail }
                OutlinedTextField(
                    value = email, onValueChange = { email = it; if (isValidEmail(it)) onChange(settings.copy(contactEmail = it.trim())) }, singleLine = true,
                    label = { Text("Your email (optional)") }, isError = email.isNotEmpty() && !isValidEmail(email),
                    supportingText = { Text(if (isValidEmail(email)) "Sent to the data provider in this app's User-Agent." else if (email.isEmpty()) "Optional. Sent to the data provider in this app's User-Agent." else "Enter a valid address; the last valid one stays in use.") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text("Back up or move your settings")
                    Text("Favorites, saved places, these options, the display choices (units, barbs, contours, loops, each page's product, area and level) and the sources (LightningCast and ProbSevere links, the GFS crop switch), as a JSON file you choose where to save. Importing restarts the app.", style = MaterialTheme.typography.bodySmall)
                    val rain = remember { RainHistory.summary() }
                    var withRain by remember { mutableStateOf(false) }
                    var rainNote by remember { mutableStateOf<String?>(null) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    val context = androidx.compose.ui.platform.LocalContext.current
                    // the rain history is its own file (it can be large): saved first, then the settings file's own save dialog follows
                    val rainLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        if (uri == null) rainNote = "Rain history not saved."
                        else scope.launch {
                            val n = withContext(Dispatchers.IO) { try { context.contentResolver.openOutputStream(uri, "wt")?.use { RainHistory.write(AppContainer.rainDays, it) } } catch (e: Exception) { null } }
                            rainNote = if (n == null) "Could not write the rain history file." else "Rain history saved: $n days."
                        }
                        onExport()
                    }
                    if (rain != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = withRain, onCheckedChange = { withRain = it })
                        Text("Include the rain history (${rain.first} days, ${if (rain.second >= 1_048_576) "%.1f MB".format(rain.second / 1048576.0) else "${rain.second / 1024} KB"}): another phone reads it and skips the download", style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        TextButton(onClick = { if (withRain && rain != null) rainLauncher.launch("simplemodelview-rain-history.zip") else onExport() }) { Text("Export") }
                        TextButton(onClick = onImport) { Text("Import") }
                    }
                    rainNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Disclaimer(text = disclaimer)
            }
        },
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

internal fun shareFile(context: android.content.Context, file: java.io.File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}

/** Choose what to export (this screen area, the whole map, or a meso sector) and in what form. */
@Composable
internal fun ShareDialog(
    canLoop: Boolean, current: String, globe: Boolean, initialSize: VideoSize, estimate: (ShareArea, VideoSize) -> Double,
    onDismiss: () -> Unit, onShare: (ShareArea, Boolean, VideoSize) -> Unit, disclaimer: String = DISCLAIMER,
    /** False for a rainfall total: it is only the sector on screen (or the part of it that shows), not another meso area. */
    mesoAreas: Boolean = true,
    /** A compare picture is large (two maps): its size is chosen with the video size. */
    comparing: Boolean = false,
) {
    var area by remember { mutableStateOf<ShareArea>(if (globe) ShareArea.Globe else ShareArea.Screen) }
    var size by remember { mutableStateOf(initialSize) }
    var renders by remember { mutableStateOf(false) }
    val jobs by AppContainer.renders.jobs.collectAsStateWithLifecycle()
    if (renders) RendersDialog { renders = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share") },
        text = {
            Column {
                Text("Area", style = MaterialTheme.typography.labelLarge)
                LazyColumn(if (mesoAreas) Modifier.height(300.dp) else Modifier) {
                    if (globe) {
                        item { AreaRow("The globe, as turned now", area == ShareArea.Globe) { area = ShareArea.Globe } }
                    } else {
                        item { AreaRow("This screen area", area == ShareArea.Screen) { area = ShareArea.Screen } }
                        item { AreaRow("Whole map ($current)", area == ShareArea.Whole) { area = ShareArea.Whole } }
                    }
                    if (mesoAreas) {
                        item { Text("Meso areas", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge) }
                        items(Sector.all) { sector ->
                            AreaRow(sector.name, (area as? ShareArea.Meso)?.sector == sector) { area = ShareArea.Meso(sector) }
                        }
                    }
                }
                if (canLoop || comparing) {
                    Text(if (canLoop) "Loop video size" + (if (comparing) " (and compare picture)" else "") else "Compare picture size", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in VideoSize.entries) {
                            val mb = estimate(area, option)
                            FilterChip(size == option, { size = option }, label = { Text(if (canLoop) "${option.label} ~${if (mb < 10) fixed(mb, 1) else mb.toInt().toString()} MB" else "${option.label} ${option.width} px") })
                        }
                    }
                }
                Disclaimer(Modifier.padding(top = 8.dp), disclaimer)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onShare(area, false, size) }) { Text("Picture") }
                if (canLoop) TextButton(onClick = { onShare(area, true, size) }) { Text("Loop video") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (jobs.isNotEmpty()) TextButton(onClick = { renders = true }) { Text("Renders (${jobs.size})") }
            }
        },
    )
}

@Composable
private fun AreaRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "●" else "○", Modifier.padding(end = 12.dp), fontSize = 18.sp)
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

internal const val DISCLAIMER = "Data: NOAA/NWS Storm Prediction Center. Experimental guidance, not a warning or decision-support tool."

@Composable
private fun Disclaimer(modifier: Modifier = Modifier, text: String = DISCLAIMER) =
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Asks for a contact address. Optional, but it comes back at every launch until one is saved; "Not now" closes it. */
@Composable
internal fun ContactDialog(onSave: (String) -> Unit, onNotNow: () -> Unit, provider: String = "the Storm Prediction Center", server: String = "SPC's server") {
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("Your email address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This app reads $provider's public data directly. To be a polite client it would like to identify " +
                        "you to $server: your address would be sent in the User-Agent header of every request, so they can contact you if the app " +
                        "ever causes them problems. It is not shared with anyone else and is kept only on this device. Optional; you can also " +
                        "set it later in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true, label = { Text("Email") },
                    isError = email.isNotEmpty() && !isValidEmail(email),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(email.trim()) }, enabled = isValidEmail(email)) { Text("Save") } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}

/**
 * The UTC clock, always visible, next to when the shown frame's file was uploaded and when the run finished, for comparison; and
 * the delays after the frame's valid time and the run's cycle time, for any run.
 */
@Composable
private fun TimesLine(fileTime: Long?, runComplete: Long?, cycle: Cycle?, validTime: Long?) {
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(10_000) } }
    val style = MaterialTheme.typography.labelSmall
    val pad = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 2.dp)
    Text(
        buildString {
            append("Now ").append(formatClock(now))
            if (fileTime != null) append(" \u00B7 File ").append(formatClock(fileTime, now)).append(" (").append(formatAge(now - fileTime)).append(" ago)")
            if (runComplete != null) append(" \u00B7 Done ").append(formatClock(runComplete, now))
        },
        pad, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    if (fileTime != null || runComplete != null) {
        // how long after the frame's valid time its file appeared, and after the run's cycle time the run was complete
        Text(
            buildString {
                append("Delay")
                if (fileTime != null && validTime != null) append(": file ").append(formatPlus(validTime, fileTime - validTime))
                if (cycle != null && runComplete != null) append(" \u00B7 run done ").append(formatPlus(cycle.epochSeconds, runComplete - cycle.epochSeconds))
            },
            pad, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}


/** After an import: the display and source choices are read when the app starts, so offer to start it again. */
@Composable
internal fun RestartDialog(onLater: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Restart to apply") },
        text = { Text("The display choices and sources in that file are read when the app starts. Restart now?") },
        confirmButton = { TextButton(onClick = { SettingsTransfer.restart(context) }) { Text("Restart") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

/** A name, a landmark or a zip code to look up (Wikipedia and OpenStreetMap, on Search only); a match is saved as a place and shown. */
@Composable
private fun PlaceSearchBox(onPick: (simplemodelview.PlaceHit) -> Unit) {
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<simplemodelview.PlaceSearchResult?>(null) }
    val scope = rememberCoroutineScope()
    fun go() {
        if (busy || query.trim().length < 2) return
        busy = true
        scope.launch { result = AppContainer.placeSearch.search(query); busy = false }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find a place or landmark") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }),
            )
            TextButton(enabled = !busy && query.trim().length >= 2, onClick = { go() }) { Text("Search") }
        }
        if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        result?.let { r ->
            if (r.hits.isEmpty()) Text(
                if (r.failed.size == 2) "Could not reach the search services (is the network on?)." else "Nothing found for that.",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
            )
            for (h in r.hits) Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 6.dp)) {
                Text(h.name, fontWeight = FontWeight.Bold)
                Text(listOf(h.detail, h.source).filter { it.isNotBlank() }.joinToString("  \u00B7  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.hits.isNotEmpty()) Text("Tap a match to save it as a place and show it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.failed.size == 1) Text("${r.failed[0]} could not be reached.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Text("Wikipedia, \u00A9 OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}
