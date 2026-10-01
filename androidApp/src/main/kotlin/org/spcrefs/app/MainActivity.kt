package org.spcrefs.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import spcrefs.Place
import spcrefs.PointSeries
import spcrefs.fixed
import spcrefs.formatUtc
import spcrefs.placeLabel
import kotlinx.coroutines.launch
import spcrefs.Cycle
import spcrefs.Groups
import spcrefs.Kind
import spcrefs.Palettes
import spcrefs.Product
import spcrefs.Sector
import spcrefs.Style

class MainActivity : ComponentActivity() {
    private val viewModel: RefsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    LifecycleResumeEffect(Unit) { vm.checkForNewCycle(); onPauseOrDispose { } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("SPC REFS") },
            actions = {
                SectorMenu(s.sector, vm::pickSector)
                TextButton(onClick = {
                    scope.launch {
                        val file = vm.buildShareFile() ?: return@launch
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share map"))
                    }
                }, enabled = s.map != null) { Text("Share") }
                MoreMenu(onPlaces = { places = true }, onSettings = { settings = true }, onAbout = { about = true })
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // product / cycle / member
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { picker = true }, label = { Text(s.product?.title ?: "Product", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f))
                CycleMenu(s.cycle, s.cycles, vm::pickCycle)
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
            Text(
                listOfNotNull(s.cycle?.let { "Run ${it.label}" }, s.validLabel.takeIf { it.isNotEmpty() }?.let { "Valid $it" },
                    s.forecastHour?.let { "f${it.toString().padStart(2, '0')}" }).joinToString("   "),
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(s.error!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
                    }
                    s.map != null -> MapView(s, vm)
                    else -> CircularProgressIndicator()
                }
                s.readout?.let {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)).padding(horizontal = 8.dp)) {
                        Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        s.tapLatLon?.let { (lat, lon) ->
                            Row {
                                TextButton(onClick = { vm.showPointForecast(placeLabel(lat, lon), lat, lon) }) { Text("Point forecast") }
                                TextButton(onClick = { savingPlace = true }) { Text("Save place") }
                                TextButton(onClick = vm::clearTap) { Text("Clear") }
                            }
                        }
                    }
                }
                if (s.busy && s.map != null) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp), strokeWidth = 2.dp)
            }

            s.style?.let { Legend(it, s.members) }

            if (s.times.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::togglePlay) { Text(if (s.playing) "Pause" else "Play") }
                    Slider(
                        value = s.timeIndex.toFloat(), onValueChange = { vm.pickTime(it.toInt()) },
                        valueRange = 0f..(s.times.size - 1).toFloat(), modifier = Modifier.weight(1f),
                    )
                }
            }
            s.prefetch?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
            Text(
                "Data: NOAA/NWS Storm Prediction Center. Experimental guidance, not a warning or decision-support tool.",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
            )
        }
    }

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
        onForecast = { places = false; vm.showPointForecast(it.name, it.lat, it.lon) },
        onDelete = vm::deletePlace,
    )
    if (settings) SettingsDialog(s.settings, vm::setSettings) { settings = false }
    s.strip?.let { PointStripSheet(it, s.product?.title ?: "", s.cycle?.label ?: "", vm::closePointForecast) }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
        title = { Text("About") },
        text = {
            Text(
                "An unofficial viewer for the Storm Prediction Center's experimental REFS ensemble products.\n\n" +
                    "Data: NOAA/NWS Storm Prediction Center. Not affiliated with or endorsed by NOAA, the NWS or SPC. " +
                    "Experimental guidance only: do not use it for warnings or safety decisions; follow official NWS products.\n\n" +
                    "Licence: GPLv3."
            )
        },
    )
}

/** The map picture with pinch-zoom, pan and tap-for-value. Tap positions are converted back through the zoom and pan. */
@Composable
private fun MapView(s: UiState, vm: RefsViewModel) {
    val bitmap = s.map ?: return
    var scale by remember(bitmap.width, bitmap.height) { mutableStateOf(1f) }
    var offset by remember(bitmap.width, bitmap.height) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

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
            Modifier.fillMaxWidth().aspectRatio(aspect)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .pointerInput(bitmap) {
                    detectTapGestures { p -> vm.onTap(p.x / this.size.width, p.y / this.size.height) }
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
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
private fun SectorMenu(current: Sector, onPick: (Sector) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(current.name) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPicker(
    products: List<Product>, current: Product?, favourites: Set<String>,
    onToggle: (Product) -> Unit, onPick: (Product) -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val words = query.split(' ').filter { it.isNotBlank() }
    val shown = products.filter { p -> words.all { p.title.contains(it, ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("Search products") })
        LazyColumn(Modifier.fillMaxWidth()) {
            val favs = shown.filter { it.id in favourites }
            if (favs.isNotEmpty()) {
                item { GroupHeader("Favourites") }
                items(favs, key = { "f" + it.id }) { ProductRow(it, it.id == current?.id, true, onToggle, onPick) }
            }
            for (group in Groups.ordered) {
                val inGroup = shown.filter { it.group == group }
                if (inGroup.isEmpty()) continue
                item { GroupHeader(group) }
                items(inGroup, key = { it.id }) { ProductRow(it, it.id == current?.id, it.id in favourites, onToggle, onPick) }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
        TextButton(onClick = { open = true }) { Text("More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Places") }, onClick = { open = false; onPlaces() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text("About") }, onClick = { open = false; onAbout() })
        }
    }
}

@Composable
private fun PlacesDialog(places: List<Place>, onDismiss: () -> Unit, onGo: (Place) -> Unit, onForecast: (Place) -> Unit, onDelete: (Place) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Places") },
        text = {
            if (places.isEmpty()) Text("No saved places yet. Tap the map, then choose Save place.")
            else LazyColumn {
                items(places, key = { it.name }) { p ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Row {
                            TextButton(onClick = { onGo(p) }) { Text("Show") }
                            TextButton(onClick = { onForecast(p) }) { Text("Forecast") }
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
private fun SettingsDialog(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** Value at one place for every forecast hour: a bar chart plus the numbers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointStripSheet(strip: StripState, product: String, run: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(strip.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$product   run $run", style = MaterialTheme.typography.bodySmall)
            when {
                strip.error != null -> Text(strip.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                strip.series == null -> {
                    Text("Loading every forecast hour...", Modifier.padding(top = 12.dp))
                    LinearProgressIndicator(progress = { strip.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                else -> StripChart(strip.series)
            }
        }
    }
}

@Composable
private fun StripChart(series: PointSeries) {
    val max = series.max
    val digits = series.digits
    val unit = if (series.units.isEmpty()) "" else " ${series.units}"
    if (max == null) { Text("No data at this point.", Modifier.padding(top = 12.dp)); return }
    val peak = series.peakIndex
    Text("Peak ${fixed(max, digits)}$unit at ${peak?.let { formatUtc(series.times[it]) } ?: ""}", Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold)
    val bar = MaterialTheme.colorScheme.primary
    val peakColour = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp)) {
        val n = series.values.size
        val top = maxOf(max, 1e-9)
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        series.values.forEachIndexed { i, v ->
            if (v.isNaN()) return@forEachIndexed
            val h = (maxOf(v, 0.0) / top * size.height).toFloat()
            drawRect(if (i == peak) peakColour else bar, Offset(i * (w + gap), size.height - h), androidx.compose.ui.geometry.Size(w, h))
        }
    }
    LazyColumn(Modifier.height(220.dp)) {
        items(series.values.indices.toList()) { i ->
            val v = series.values[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatUtc(series.times[i]), style = MaterialTheme.typography.bodySmall)
                Text(if (v.isNaN()) "-" else fixed(v, digits) + unit, style = MaterialTheme.typography.bodySmall, fontWeight = if (i == peak) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
