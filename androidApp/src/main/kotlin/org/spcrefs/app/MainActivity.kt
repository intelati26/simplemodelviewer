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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                TextButton(onClick = { about = true }) { Text("About") }
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
                if (s.busy && s.map != null) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp), strokeWidth = 2.dp)
            }

            s.readout?.let {
                Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            s.style?.let { Legend(it, s.members) }

            if (s.times.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::togglePlay) { Text(if (s.playing) "Pause" else "Play") }
                    Slider(
                        value = s.timeIndex.toFloat(), onValueChange = { vm.pickTime(it.toInt()) },
                        valueRange = 0f..(s.times.size - 1).toFloat(), steps = (s.times.size - 2).coerceAtLeast(0), modifier = Modifier.weight(1f),
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
