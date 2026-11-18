package io.github.intelati26.simplemodelview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** One request of the app's HTTP client, as the downloads list shows it. */
data class DownloadEntry(
    val id: Long, val url: String, val range: String?, val started: Long,
    val total: Long = -1,                 // bytes expected (Content-Length), -1 while unknown
    val received: Long = 0,
    val state: State = State.Connecting,
    val status: Int = 0,                  // the HTTP status once answered
    val finished: Long = 0,               // when it ended (ms), 0 while running
) {
    enum class State { Connecting, Downloading, Done, Cached, Failed }
    val running: Boolean get() = state == State.Connecting || state == State.Downloading
}

/**
 * What the app is downloading: every request of [OkHttpHttp] is listed here while it runs, and the last few after (with its size, time and status), for the
 * list behind a busy spinner. Requests answered from the HTTP cache are listed as such. Updates are at most a few per second per request.
 */
object DownloadMonitor {
    private val ids = AtomicLong()
    private val lock = Any()
    private val entries = LinkedHashMap<Long, DownloadEntry>()
    private val _state = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val state: StateFlow<List<DownloadEntry>> = _state

    private const val KEEP_FINISHED = 40

    private fun publish() { _state.value = entries.values.toList() }

    fun start(url: String, range: String?): Long = synchronized(lock) {
        val id = ids.incrementAndGet()
        entries[id] = DownloadEntry(id, url, range, System.currentTimeMillis())
        publish()
        id
    }

    fun update(id: Long, f: (DownloadEntry) -> DownloadEntry) = synchronized(lock) {
        entries[id]?.let { entries[id] = f(it) }
        if (entries[id]?.running == false) {
            val finished = entries.values.filter { !it.running }
            if (finished.size > KEEP_FINISHED) finished.sortedBy { it.finished }.take(finished.size - KEEP_FINISHED).forEach { entries.remove(it.id) }
        }
        publish()
    }

    fun clearFinished() = synchronized(lock) { entries.values.removeAll { !it.running }; publish() }
}

/** The data source of a url in a word or two ("MRMS", "RTMA", "NBM", ...). */
internal fun sourceOf(url: String): String = when {
    "noaa-mrms-pds" in url -> "MRMS"
    "noaa-rtma-pds" in url -> "RTMA"
    "noaa-nbm" in url -> "NBM"
    "noaa-gfs" in url || "nomads.ncep" in url -> "GFS"
    "spc.noaa.gov/exper/refs" in url -> "SPC REFS"
    "spc.noaa.gov" in url -> "SPC"
    "mesonet.agron.iastate.edu" in url -> "IEM archive"
    "weather.gov" in url || "mapservices.weather.noaa.gov" in url -> "NWS"
    "ssec.wisc.edu" in url || "cimss" in url -> "CIMSS"
    else -> url.substringAfter("://").substringBefore('/')
}

/** The file of a url, without its folders and query: what the list names. */
internal fun fileOf(url: String): String {
    val path = url.substringAfter("://").substringAfter('/').substringBefore('?')
    val name = path.substringAfterLast('/')
    return if (name.isEmpty()) path.trimEnd('/').substringAfterLast('/').ifEmpty { url } + "/" else name
}

internal fun formatBytes(n: Long): String = when {
    n < 0 -> "?"
    n < 1000 -> "$n B"
    n < 1_000_000 -> "${n / 1000} kB"
    else -> String.format("%.1f MB", n / 1e6)
}

/**
 * A busy spinner that opens the downloads list when tapped. [size] / [stroke] as the spinner it replaces; with [big] the tap target is the spinner
 * itself (a page loading with nothing to show yet).
 */
@Composable
fun BusySpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, stroke: Dp = 2.dp, big: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    // a finger-sized target around a small spinner
    Box(modifier.size(if (big) 64.dp else size + 16.dp).clickable { open = true }.semantics { contentDescription = "Downloads" }, contentAlignment = Alignment.Center) {
        if (big) CircularProgressIndicator() else CircularProgressIndicator(Modifier.size(size), strokeWidth = stroke)
    }
    if (open) DownloadsDialog { open = false }
}

/** The list: what is downloading now (with its progress and speed), then what finished lately, newest first. */
@Composable
fun DownloadsDialog(onDismiss: () -> Unit) {
    val all by DownloadMonitor.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    val running = all.filter { it.running }.sortedBy { it.started }
    val done = all.filter { !it.running }.sortedByDescending { it.finished }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (done.isNotEmpty()) TextButton(onClick = DownloadMonitor::clearFinished) { Text("Clear list") } },
        title = { Text(if (running.isEmpty()) "Downloads" else "Downloading ${running.size}") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                if (running.isEmpty()) item { Text("Nothing is downloading.", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium) }
                items(running, key = { it.id }) { DownloadRow(it, now) }
                if (done.isNotEmpty()) item {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Recent", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                items(done, key = { it.id }) { DownloadRow(it, now) }
            }
        },
    )
}

@Composable
private fun DownloadRow(e: DownloadEntry, now: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sourceOf(e.url), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(fileOf(e.url) + if (e.range != null) "  (part)" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val seconds = ((if (e.finished > 0) e.finished else now) - e.started) / 1000.0
        val detail = when (e.state) {
            DownloadEntry.State.Connecting -> "waiting for the server… ${"%.0f".format(seconds)} s"
            DownloadEntry.State.Downloading -> {
                val rate = if (seconds > 0.3) "   ${formatBytes((e.received / seconds).toLong())}/s" else ""
                "${formatBytes(e.received)} of ${formatBytes(e.total)}$rate"
            }
            DownloadEntry.State.Done -> "${formatBytes(e.received)} in ${"%.1f".format(seconds)} s"
            DownloadEntry.State.Cached -> "${formatBytes(e.received)} from the phone's cache"
            DownloadEntry.State.Failed -> if (e.status > 0) "HTTP ${e.status}" + (if (e.status == 404) " (not there)" else "") else "no answer (network)"
        }
        Text(detail, style = MaterialTheme.typography.labelSmall, color = if (e.state == DownloadEntry.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (e.state == DownloadEntry.State.Downloading && e.total > 0) LinearProgressIndicator(progress = { (e.received.toFloat() / e.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
        else if (e.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 3.dp))
    }
}
