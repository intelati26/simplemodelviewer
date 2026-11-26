package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import simplemodelview.formatClock

private class StorageFigures(val httpBytes: Long, val grib: DiskGribStore.Stats, val rainBytes: Long, val rainDays: Int, val rainOldest: Long?)

private fun size(bytes: Long): String = when {
    bytes < 1_000 -> "0 MB"
    bytes < 1_000_000 -> "${bytes / 1_000} KB"
    else -> "%.1f MB".format(bytes / 1e6)
}

/** What the app keeps on the device, how much room each part takes, and a way to clear each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageDialog(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var figures by remember { mutableStateOf<StorageFigures?>(null) }
    LaunchedEffect(refresh) {
        figures = withContext(Dispatchers.IO) {
            val (rainBytes, rainDays) = AppContainer.rainDays.size()
            StorageFigures(AppContainer.http.cacheBytes(), AppContainer.gribStore.stats(), rainBytes, rainDays, AppContainer.rainDays.oldestEnd())
        }
    }
    val f = figures
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Storage and cache") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (f == null) { Text("Reading…", color = muted); return@Column }
                val total = f.httpBytes + f.grib.bytes + f.rainBytes
                Text("Kept on this device: ${size(total)}", style = MaterialTheme.typography.titleSmall)
                Text("Downloaded files and model data are cache: Android may clear them when the phone is short of space, and anything missing is simply downloaded again. Rainfall days are kept in the app's storage and stay until you clear them or they pass 90 days.", style = MaterialTheme.typography.bodySmall, color = muted)
                HorizontalDivider()

                Bucket(
                    "Downloaded files", size(f.httpBytes),
                    "Radar scans, model chunks and listings, kept up to 24 hours. Limited to the cache size below.",
                ) {
                    TextButton(onClick = { AppContainer.http.clearCache(); refresh += 1 }, enabled = f.httpBytes > 0) { Text("Clear") }
                }
                HorizontalDivider()

                Bucket(
                    "Model data (GFS, NBM, analyses)", if (f.grib.files == 0) "Nothing kept" else "${size(f.grib.bytes)} in ${f.grib.files} files",
                    (if (f.grib.byModel.isEmpty()) "" else f.grib.byModel.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} ${size(it.value)}" } + ". ") +
                        "Cropped GRIB messages, kept 24 hours from the model run. Limited to the cache size below.",
                ) {
                    TextButton(onClick = { AppContainer.gribStore.clear("GFS"); refresh += 1 }, enabled = f.grib.byModel.containsKey("GFS")) { Text("Clear GFS") }
                    TextButton(onClick = { AppContainer.gribStore.clear("NBM"); refresh += 1 }, enabled = f.grib.byModel.containsKey("NBM")) { Text("Clear NBM") }
                    TextButton(onClick = { AppContainer.gribStore.clear(null); refresh += 1 }, enabled = f.grib.files > 0) { Text("Clear all") }
                }
                HorizontalDivider()

                Bucket(
                    "Rainfall days", if (f.rainDays == 0) "Nothing kept" else "${size(f.rainBytes)} in ${f.rainDays} days",
                    (f.rainOldest?.let { "From the day ending ${formatClock(it, 0)}. " } ?: "") +
                        "Each finished UTC day of multi-sensor rainfall, stored compactly in the app's storage (not the cache) for the rainfall totals, kept 90 days. A total then downloads only the days not kept.",
                ) {
                    TextButton(onClick = { AppContainer.rainDays.clear(); refresh += 1 }, enabled = f.rainDays > 0) { Text("Clear") }
                }
                HorizontalDivider()

                Column {
                    Text("Cache size (applies on the next launch)")
                    Text("The limit for downloaded files and model data (each). Rainfall days have their own 90-day rule.", style = MaterialTheme.typography.bodySmall, color = muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mb in listOf(50, 150, 300, 1000)) FilterChip(settings.cacheMegabytes == mb, { onChange(settings.copy(cacheMegabytes = mb)) }, label = { Text(if (mb == 1000) "1 GB" else "$mb MB") })
                    }
                }
                Text("Not counted here: the GFS page's loop pictures (kept only while the page is open, up to a quarter of the free space) and the pictures and videos made for sharing (deleted after use).", style = MaterialTheme.typography.bodySmall, color = muted)
                TextButton(
                    onClick = { AppContainer.http.clearCache(); AppContainer.gribStore.clear(null); AppContainer.rainDays.clear(); refresh += 1 },
                    enabled = total > 0,
                ) { Text("Clear everything") }
            }
        },
    )
}

@Composable
private fun Bucket(title: String, amount: String, detail: String, actions: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.weight(1f).padding(end = 8.dp))
            Text(amount, style = MaterialTheme.typography.titleSmall)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}
