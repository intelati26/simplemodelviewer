package io.github.intelati26.simplemodelview

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import simplemodelview.visibleFractions
import simplemodelview.globeForward
import simplemodelview.placeLabel
import kotlinx.coroutines.launch
import simplemodelview.Cycle
import simplemodelview.Groups
import simplemodelview.Kind
import simplemodelview.Palettes
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.Style

class MainActivity : ComponentActivity() {
    private val viewModel: RefsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            LaunchedEffect(dark) { viewModel.setDark(dark) }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { RefsScreen(viewModel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefsScreen(vm: RefsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var savingPlace by remember { mutableStateOf(false) }
    var loopProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sharing by remember { mutableStateOf(false) }
    // settings export / import go through the system file pickers: the app needs no storage permission
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.exportSettings(context.contentResolver, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.importSettings(context.contentResolver, uri) }
    }
    LifecycleResumeEffect(Unit) { vm.checkForNewCycle(); vm.promptForContact(); onPauseOrDispose { } }

    val dark = isSystemInDarkTheme()
    val mapBackground = if (dark) Color(0xFF202428) else Color(0xFFEDEFF2)     // the map picture's own background: no dead white around it
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

    // The map fills the whole screen (under the system bars); the controls are two thin translucent strips floating over the empty
    // bands above and below it. Tablets / landscape: controls in a column on the left, the map filling the rest.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(mapBackground)) {
        val wide = maxWidth >= 720.dp && maxWidth > maxHeight
        val head: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
          // compact header: chips and buttons are 32 dp tall instead of being padded out to the 48 dp touch-target minimum
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { picker = true }, label = { Text(s.product?.title ?: "Product", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f))
                CycleMenu(s.cycle, s.cycles, vm::pickCycle)
                MoreMenu(onPlaces = { places = true }, onSettings = { settings = true }, onAbout = { about = true })
            }
            if (s.product?.hasMembers == true && s.members.isNotEmpty()) MemberMenu(s.members, s.member, vm::pickMember)

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
                if (s.busy && s.map != null) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(16.dp), strokeWidth = 2.dp)
                SectorMenu(if (s.globe) "Globe" else s.sector.name, onGlobe = { vm.setGlobe(true) }, onPick = { vm.setGlobe(false); vm.pickSector(it) })
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
                    s.map != null || s.globe -> if (s.globe) GlobeView(s, vm) else MapView(s, vm)
                    else -> CircularProgressIndicator()
                }
            }
        }
        val foot: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            s.readout?.let {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (s.tapLatLon != null) TextButton(onClick = { savingPlace = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Save") }
                    TextButton(onClick = vm::clearTap, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
                }
            }
            s.style?.let { Legend(it, s.members) }
            if (s.times.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::togglePlay, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (s.playing) "Pause" else "Play") }
                    Slider(
                        value = s.timeIndex.toFloat(), onValueChange = { vm.pickTime(it.toInt()) },
                        valueRange = 0f..(s.times.size - 1).toFloat(), modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
            s.prefetch?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(380.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) { head(); foot() }
                mapArea(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            mapArea(Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(panel).statusBarsPadding()) { head() }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(panel).navigationBarsPadding()) { foot() }
        }
    }

    if (s.contactPrompt) ContactDialog(onSave = { vm.setSettings(s.settings.copy(contactEmail = it)); vm.dismissContactPrompt() }, onNotNow = vm::dismissContactPrompt)
    if (picker) ProductPicker(s.products, s.product, s.favourites, onToggle = vm::toggleFavourite, onPick = { picker = false; vm.pickProduct(it) }, onDismiss = { picker = false })
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
    if (places) PlacesDialog(
        s.places, onDismiss = { places = false },
        onGo = { places = false; vm.goToPlace(it) },
        onDelete = vm::deletePlace,
    )
    if (settings) SettingsDialog(s.settings, vm::setSettings, onExport = { exportLauncher.launch("simplemodelview-settings.json") }, onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { settings = false }
    if (sharing) ShareDialog(
        canLoop = s.times.size > 1, current = s.sector.name, globe = s.globe, onDismiss = { sharing = false },
        initialSize = vm.videoSize,
        estimate = { area, size -> vm.estimateLoopMegabytes(area, size) },
        onShare = { area, loop, size ->
            vm.videoSize = size
            sharing = false
            scope.launch {
                if (!loop) {
                    val file = vm.buildShareFile(area) ?: return@launch
                    shareFile(context, file, "image/png", "Share map")
                } else {
                    loopProgress = 0 to s.times.size
                    try {
                        val file = vm.buildLoopFile(area, size) { done, total -> loopProgress = done to total }
                        loopProgress = null
                        if (file != null) shareFile(context, file, "video/mp4", "Share loop")
                    } catch (e: Exception) {
                        loopProgress = null
                        vm.reportNotice("Could not make the video: ${e.message}")
                    }
                }
            }
        },
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
        title = { Text("About simplemodelview") },
        text = {
            Text(
                "simplemodelview is an unofficial viewer for the Storm Prediction Center's experimental REFS ensemble products.\n\n" +
                    "Data: NOAA/NWS Storm Prediction Center. Not affiliated with or endorsed by NOAA, the NWS or SPC. " +
                    "Experimental guidance only: do not use it for warnings or safety decisions; follow official NWS products.\n\n" +
                    "Licence: GPLv3."
            )
        },
    )
}

/** The globe: drag to turn it, pinch to zoom, tap for a value. The picture is drawn by the core at the screen's pixel size. */
@Composable
private fun GlobeView(s: UiState, vm: RefsViewModel) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it; vm.setGlobeSize(it.width, it.height) }
            .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> vm.globeGesture(pan.x, pan.y, zoom) } }
            .pointerInput(Unit) { detectTapGestures { p -> vm.onGlobeTap(p.x, p.y) } },
    ) {
        s.map?.let { Image(it.asImageBitmap(), contentDescription = "SPC REFS globe", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        s.tapLatLon?.let { (lat, lon) ->
            val p = globeForward(s.cam, size.width, size.height, lat, lon)
            if (p != null) Canvas(Modifier.fillMaxSize()) {
                val x = p[0].toFloat(); val y = p[1].toFloat(); val r = 10.dp.toPx(); val w = 1.5.dp.toPx()
                drawLine(Color.Black, Offset(x - r, y), Offset(x + r, y), w * 2.4f); drawLine(Color.Black, Offset(x, y - r), Offset(x, y + r), w * 2.4f)
                drawLine(Color.White, Offset(x - r, y), Offset(x + r, y), w); drawLine(Color.White, Offset(x, y - r), Offset(x, y + r), w)
            }
        }
    }
}

/** The map picture with pinch-zoom, pan and tap-for-value. Tap positions are converted back through the zoom and pan. */
@Composable
private fun MapView(s: UiState, vm: RefsViewModel) {
    val bitmap = s.map ?: return
    var scale by remember(bitmap.width, bitmap.height) { mutableStateOf(1f) }
    var offset by remember(bitmap.width, bitmap.height) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // tell the view model which part of the picture is on screen, for "export this screen area"
    LaunchedEffect(scale, offset, size, bitmap) {
        if (size.width == 0) return@LaunchedEffect
        val f = visibleFractions(size.width.toFloat(), size.height.toFloat(), bitmap.width.toFloat() / bitmap.height, scale, offset.x, offset.y)
        vm.setViewport(f[0], f[1], f[2], f[3])
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 12f)
                    // keep the point under the fingers fixed, then pan
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    offset = (offset + (centroid - centre - offset) * (1f - newScale / scale) + pan)
                    scale = newScale
                    val maxX = size.width * (scale - 1f) / 2f
                    val maxY = size.height * (scale - 1f) / 2f
                    offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val aspect = bitmap.width.toFloat() / bitmap.height
        Box(
            Modifier.aspectRatio(aspect)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onTap = { p -> vm.onTap(p.x / this.size.width, p.y / this.size.height) },
                        // zoom in on that spot (the wide US map leaves room above and below it), or back out
                        onDoubleTap = { q ->
                            if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else {
                                val target = 2.5f
                                val t = (q - Offset(this.size.width / 2f, this.size.height / 2f)) * (1f - target)   // keeps that point under the finger
                                val maxX = size.width * (target - 1f) / 2f
                                val maxY = size.height * (target - 1f) / 2f
                                scale = target
                                offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                            }
                        },
                    )
                },
        ) {
            Image(image, contentDescription = "SPC REFS map", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            s.crosshair?.let { (fx, fy) ->
                Canvas(Modifier.fillMaxSize()) {
                    val x = fx * this.size.width
                    val y = fy * this.size.height
                    val r = 10.dp.toPx() / scale
                    val stroke = 1.5.dp.toPx() / scale
                    drawLine(Color.Black, Offset(x - r, y), Offset(x + r, y), stroke * 2.4f)
                    drawLine(Color.Black, Offset(x, y - r), Offset(x, y + r), stroke * 2.4f)
                    drawLine(Color.White, Offset(x - r, y), Offset(x + r, y), stroke)
                    drawLine(Color.White, Offset(x, y - r), Offset(x, y + r), stroke)
                }
            }
        }
    }
}

@Composable
private fun Legend(style: Style, members: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        if (style.kind == Kind.Paintball) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 10) Box(
                    Modifier.weight(1f).height(10.dp).background(Color(0xFF000000.toInt() or (Palettes.memberColours[members.getOrNull(i)] ?: 0x808080))),
                )
            }
            Text("Each colour: a member over the threshold", style = MaterialTheme.typography.labelSmall)
            return
        }
        if (style.stops.isEmpty()) return
        Row(Modifier.fillMaxWidth()) {
            for (stop in style.stops) Box(Modifier.weight(1f).height(10.dp).background(Color(0xFF000000.toInt() or stop.rgb)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(MapRenderer.label(style.stops.first(), style), style = MaterialTheme.typography.labelSmall)
            Text(style.units, style = MaterialTheme.typography.labelSmall)
            Text(MapRenderer.label(style.stops.last(), style), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectorMenu(current: String, onGlobe: () -> Unit, onPick: (Sector) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Globe") }, onClick = { open = false; onGlobe() })
            for (sector in Sector.all) DropdownMenuItem(text = { Text(sector.name) }, onClick = { open = false; onPick(sector) })
        }
    }
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

private const val FAVOURITES = "Favourites"

/** The product list in collapsible buckets like SPC's own menu (Guidance, Synoptic, Severe, ...), with Favourites first. All start collapsed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPicker(
    products: List<Product>, current: Product?, favourites: Set<String>,
    onToggle: (Product) -> Unit, onPick: (Product) -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<String>()) }        // buckets the user has expanded
    val words = query.split(' ').filter { it.isNotBlank() }
    val searching = words.isNotEmpty()
    val shown = products.filter { p -> words.all { p.title.contains(it, ignoreCase = true) } }
    // while searching, every bucket with a match is open; otherwise only the ones the user opened
    val buckets: List<Pair<String, List<Product>>> =
        listOf(FAVOURITES to shown.filter { it.id in favourites }) + Groups.ordered.map { g -> g to shown.filter { it.group == g } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("Search products") })
        LazyColumn(Modifier.fillMaxWidth()) {
            for ((name, list) in buckets) {
                if (list.isEmpty() && (name != FAVOURITES || searching)) continue
                val expanded = if (searching) true else name in open
                item(key = "h-$name") {
                    BucketHeader(name, list.size, expanded) { if (!searching) open = if (expanded) open - name else open + name }
                }
                if (!expanded) continue
                if (list.isEmpty()) {
                    item(key = "e-$name") { Text("Tap the star on a product to keep it here.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                } else {
                    items(list, key = { "$name/${it.id}" }) { ProductRow(it, it.id == current?.id, it.id in favourites, onToggle, onPick) }
                }
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
private fun ProductRow(product: Product, selected: Boolean, favourite: Boolean, onToggle: (Product) -> Unit, onPick: (Product) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPick(product) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(product.title, Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
        TextButton(onClick = { onToggle(product) }) { Text(if (favourite) "★" else "☆", fontSize = 20.sp) }
    }
    HorizontalDivider()
}

@Composable
private fun MoreMenu(onPlaces: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("\u22EE", fontSize = 22.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Places") }, onClick = { open = false; onPlaces() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text("About") }, onClick = { open = false; onAbout() })
        }
    }
}

@Composable
private fun PlacesDialog(places: List<Place>, onDismiss: () -> Unit, onGo: (Place) -> Unit, onDelete: (Place) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Places") },
        text = {
            if (places.isEmpty()) Text("No saved places yet. Tap the map, then choose Save place; Show jumps back to it.")
            else LazyColumn {
                items(places, key = { it.name }) { p ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Row {
                            TextButton(onClick = { onGo(p) }) { Text("Show") }
                            TextButton(onClick = { onDelete(p) }) { Text("Delete") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
    )
}

@Composable
private fun SettingsDialog(settings: Settings, onChange: (Settings) -> Unit, onExport: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
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
                Column {
                    Text("Cache size (applies on the next launch)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mb in listOf(50, 150, 300)) FilterChip(settings.cacheMegabytes == mb, { onChange(settings.copy(cacheMegabytes = mb)) }, label = { Text("$mb MB") })
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
                    Text("Favourites, saved places, last product and area, and these options, as a JSON file you choose where to save.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onExport) { Text("Export") }
                        TextButton(onClick = onImport) { Text("Import") }
                    }
                }
                Disclaimer()
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

private fun shareFile(context: android.content.Context, file: java.io.File, mime: String, title: String) {
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
private fun ShareDialog(
    canLoop: Boolean, current: String, globe: Boolean, initialSize: VideoSize, estimate: (ShareArea, VideoSize) -> Double,
    onDismiss: () -> Unit, onShare: (ShareArea, Boolean, VideoSize) -> Unit,
) {
    var area by remember { mutableStateOf<ShareArea>(if (globe) ShareArea.Globe else ShareArea.Screen) }
    var size by remember { mutableStateOf(initialSize) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share") },
        text = {
            Column {
                Text("Area", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.height(300.dp)) {
                    if (globe) {
                        item { AreaRow("The globe, as turned now", area == ShareArea.Globe) { area = ShareArea.Globe } }
                    } else {
                        item { AreaRow("This screen area", area == ShareArea.Screen) { area = ShareArea.Screen } }
                        item { AreaRow("Whole map ($current)", area == ShareArea.Whole) { area = ShareArea.Whole } }
                    }
                    item { Text("Meso areas", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge) }
                    items(Sector.all) { sector ->
                        AreaRow(sector.name, (area as? ShareArea.Meso)?.sector == sector) { area = ShareArea.Meso(sector) }
                    }
                }
                if (canLoop) {
                    Text("Loop video size", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in VideoSize.entries) {
                            val mb = estimate(area, option)
                            FilterChip(size == option, { size = option }, label = { Text("${option.label} ~${if (mb < 10) fixed(mb, 1) else mb.toInt().toString()} MB") })
                        }
                    }
                }
                Disclaimer(Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onShare(area, false, size) }) { Text("Picture") }
                if (canLoop) TextButton(onClick = { onShare(area, true, size) }) { Text("Loop video") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AreaRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "●" else "○", Modifier.padding(end = 12.dp), fontSize = 18.sp)
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

private const val DISCLAIMER = "Data: NOAA/NWS Storm Prediction Center. Experimental guidance, not a warning or decision-support tool."

@Composable
private fun Disclaimer(modifier: Modifier = Modifier) =
    Text(DISCLAIMER, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Asks for a contact address. Optional, but it comes back at every launch until one is saved; "Not now" closes it. */
@Composable
private fun ContactDialog(onSave: (String) -> Unit, onNotNow: () -> Unit) {
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("Your email address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This app reads the Storm Prediction Center's public data directly. To be a polite client it would like to identify " +
                        "you to SPC's server: your address would be sent in the User-Agent header of every request, so SPC can contact you if the app " +
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
