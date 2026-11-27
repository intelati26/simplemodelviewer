package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import simplemodelview.Bufkit
import simplemodelview.BufkitFile
import simplemodelview.BufkitModel
import simplemodelview.BufkitStation
import simplemodelview.Sounding
import simplemodelview.SoundingHazard
import simplemodelview.SharpAnalysis
import simplemodelview.SharpProfile
import simplemodelview.formatUtc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What a model's file for the station gave: the profiles, or why there are none. */
private class Loaded(val model: BufkitModel, val file: BufkitFile?, val error: String?)

/**
 * A model sounding at a station (an ASOS airport's BUFKIT file): the skew-T and the hodograph for the hour on the slider, with the file's own indices below.
 * The RRFS and the GFS are one file each, so the slider moves through every forecast hour without another download.
 */
@Composable
internal fun SoundingScreen(station: BufkitStation, startModel: BufkitModel, onDismiss: () -> Unit) {
    var model by remember { mutableStateOf(startModel) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var parcelAt by remember { mutableIntStateOf(2) }            // the parcel the skew-T draws: 0 surface, 1 mixed layer, 2 most unstable (SPC's default), 3 forecast surface
    LaunchedEffect(station.id, model) {
        loaded = null
        val r = try { AppContainer.http.get(model.url(station.id)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val file = if (r != null && r.status == 200) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Bufkit.parse(r.body.decodeToString()) } else null
        loaded = Loaded(model, file, if (file != null) null else if (r == null || r.status == 0) "Could not reach the server." else "No ${model.label} sounding for ${station.id} right now.")
        // keep the hour nearest the one that was shown (the two models start and step differently): the first hour of a new file
        index = 0
    }
    // a wide screen (a tablet, or the phone on its side) gets the header and the time controls in fewer rows, to leave the height to the plots
    val wideScreen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                val modelChips: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in BufkitModel.entries) FilterChip(selected = m == model, onClick = { model = m }, label = { Text(m.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = if (wideScreen) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wideScreen) 12.dp else 0.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${station.id}  ·  ${station.name}", fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("%.0f km from the crosshair".format(station.km), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (wideScreen) modelChips()
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (!wideScreen) modelChips()
                val l = loaded
                val file = l?.file
                when {
                    l == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    file == null -> Text(l.error ?: "No sounding.", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                    else -> {
                        val at = index.coerceIn(0, file.soundings.size - 1)
                        val s = file.soundings[at]
                        val analysis = remember(s, file) { SharpProfile.of(s, file.lat)?.let { SharpAnalysis.compute(it) } }
                        val slider: @Composable (Modifier) -> Unit = { m ->
                            if (file.soundings.size > 1) CompactSlider(value = at.toFloat(), onValueChange = { index = it.roundToInt() }, valueRange = 0f..(file.soundings.size - 1).toFloat(), modifier = m)   // the value is rounded to an hour of the file, so the thumb sits on a forecast hour
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = at > 0, onClick = { index = at - 1 }) { Text("\u2039") }
                            Column(if (wideScreen) Modifier else Modifier.weight(1f)) {
                                Text("${station.id}  ${stamp(s.valid)}  (${model.label} +${s.forecastHour} h)", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                file.run?.let { Text("run ${formatUtc(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            TextButton(enabled = at < file.soundings.size - 1, onClick = { index = at + 1 }) { Text("\u203A") }
                            if (wideScreen) slider(Modifier.weight(1f))
                        }
                        if (!wideScreen) slider(Modifier)
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                            // SPC's page is wide: the skew-T at the left, the hodograph beside it, the tables under them. A narrow screen stacks the same pieces.
                            val wide = maxWidth >= 700.dp
                            val shownParcel = analysis?.let { listOf(it.sb, it.ml, it.mu, it.fcst).getOrNull(parcelAt.coerceIn(0, 3)) }?.takeIf { it.valid }
                            val skew: @Composable () -> Unit = {
                                if (analysis != null) {
                                    SkewT(analysis, shownParcel, Modifier.fillMaxWidth().aspectRatio(0.95f))
                                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("\u2014 temperature", color = TEMP_RED, style = MaterialTheme.typography.labelSmall)
                                        Text("\u2014 dewpoint", color = DEW_GREEN, style = MaterialTheme.typography.labelSmall)
                                        Text("\u2014 wet bulb", color = WET_CYAN, style = MaterialTheme.typography.labelSmall)
                                        Text("\u2014 virtual T", color = VIRTUAL_BLUE, style = MaterialTheme.typography.labelSmall)
                                        Text("- - parcel", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            val parcelTable: @Composable () -> Unit = {
                                if (analysis != null) { ParcelGrid(analysis, parcelAt) { parcelAt = it }; Text("Tap SB, ML, MU or FCST to draw that parcel on the skew-T.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            val hodo: @Composable () -> Unit = {
                                if (analysis != null) {
                                    Hodograph(analysis, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = if (wide) 0.dp else 10.dp, bottom = 4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        for (l in HODOGRAPH_LAYERS) Text("\u2014 ${l.label}", color = l.color, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        WindInset(analysis, Modifier.width(110.dp).height(76.dp))
                                        Text("1 km and 6 km AGL wind barbs. RM and LM: the right and left movers (Bunkers); MW: 0-6 km mean wind; UP and DN: the Corfidi vectors.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            val windTable: @Composable () -> Unit = { if (analysis != null) KinematicsGrid(analysis) }
                            val panels: @Composable () -> Unit = {
                                if (analysis != null) {
                                    val ratio = if (wide) 1.1f else 0.9f
                                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f)) { ThetaEPanel(analysis, Modifier.fillMaxWidth().aspectRatio(ratio)) }
                                        Column(Modifier.weight(1f)) { StormRelativePanel(analysis, Modifier.fillMaxWidth().aspectRatio(ratio)) }
                                        if (wide) Column(Modifier.weight(1f)) { AdvectionPanel(analysis, Modifier.fillMaxWidth().aspectRatio(ratio)) }
                                    }
                                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!wide) Column(Modifier.weight(1f)) { AdvectionPanel(analysis, Modifier.fillMaxWidth().aspectRatio(ratio)) }
                                        Column(Modifier.weight(1f)) { CompositeNumbers(analysis) }
                                        if (wide) Column(Modifier.weight(1f)) { ShipBoxPlot(analysis, Modifier.fillMaxWidth().aspectRatio(ratio)) }
                                    }
                                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(if (wide) 1.5f else 1.25f)) { StpBoxPlot(analysis, Modifier.fillMaxWidth().aspectRatio(if (wide) 1.6f else 0.95f)) }
                                        Column(Modifier.weight(if (wide) 1f else 1.6f).padding(top = 18.dp)) { TornadoProbBox(analysis) }
                                        if (!wide) Column(Modifier.weight(0.8f)) { ShipBoxPlot(analysis, Modifier.fillMaxWidth().aspectRatio(0.55f)) }
                                    }
                                }
                            }
                            val rest: @Composable () -> Unit = {
                                if (analysis != null) { SarsPanel(analysis); IndexGrids(analysis) }
                                IndexList(s)
                                Text("Model profile at the airport's grid point: ${model.label}, Iowa State's BUFKIT feed. Calculations after SHARPpy, by way of wxqt-grib.", Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (analysis != null) HazardBanner(analysis) else Text("This profile is too short to analyze.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                                if (wide) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Column(Modifier.weight(1f)) { skew() }
                                        Column(Modifier.weight(1f)) { hodo() }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Column(Modifier.weight(1f)) { parcelTable() }
                                        Column(Modifier.weight(1f)) { windTable() }
                                    }
                                } else {
                                    skew(); parcelTable(); hodo(); windTable()
                                }
                                panels()
                                rest()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexList(s: Sounding) {
    val shown = listOf("CAPE" to "CAPE", "CINS" to "CIN", "LCLP" to "LCL (mb)", "LFCT" to "LFC (mb)", "EQLV" to "EL (mb)", "PWAT" to "PW (mm)", "KINX" to "K index", "SHOW" to "Showalter", "TOTL" to "Total totals", "BRCH" to "BRN")
        .mapNotNull { (key, label) -> s.indices[key]?.takeIf { !it.isNaN() }?.let { label to it } }
    if (shown.isEmpty()) return
    Text("From the file", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
    Text(shown.joinToString("   ") { (label, v) -> "$label ${if (abs(v) >= 100) "%.0f".format(v) else "%.1f".format(v)}" }, style = MaterialTheme.typography.bodySmall)
}


private fun stamp(valid: Long): String =
    java.text.SimpleDateFormat("yyMMdd/HHmm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(valid * 1000))
