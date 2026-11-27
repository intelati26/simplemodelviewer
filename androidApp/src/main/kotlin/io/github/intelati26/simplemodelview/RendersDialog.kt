package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The renders: what is being made, what waits, and what is finished (to share again or throw away). */
@Composable
internal fun RendersDialog(onDismiss: () -> Unit) {
    val manager = AppContainer.renders
    val jobs by manager.jobs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picked = remember { mutableStateListOf<Long>() }
    // finished videos can be put together: tick two or more
    val ready = jobs.filter { it.state == RenderState.Done && it.mime == "video/mp4" }
    picked.removeAll { id -> ready.none { it.id == id } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renders") },
        text = {
            if (jobs.isEmpty()) Text("Nothing is being made. Loop videos are made in the background and wait here to be shared.")
            else Column {
              if (ready.size > 1) Text("Tick two or more finished videos to Stack (two, one above the other) or Join (one after another).", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
              LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(jobs.asReversed(), key = { it.id }) { job ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(job.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (job.state) {
                                RenderState.Queued -> "Waiting"
                                RenderState.Running -> if (job.total > 0) "Frame ${job.done} of ${job.total}" else "Starting"
                                RenderState.Done -> "Ready" + (job.file?.length()?.let { " · ${fixedMb(it)}" } ?: "")
                                RenderState.Failed -> "Could not be made: ${job.error}"
                                RenderState.Cancelled -> "Stopped"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (job.state == RenderState.Running && job.total > 0) LinearProgressIndicator(progress = { job.done / job.total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (job.state == RenderState.Done && job.mime == "video/mp4" && ready.size > 1) Checkbox(job.id in picked, { on -> if (on) picked += job.id else picked -= job.id })
                            if (job.active) TextButton(onClick = { manager.cancel(job.id) }) { Text("Stop") }
                            if (job.state == RenderState.Done) job.file?.let { f -> TextButton(onClick = { shareFile(context, f, job.mime, "Share") }) { Text("Share") } }
                            if (!job.active) TextButton(onClick = { manager.remove(job.id) }) { Text(if (job.state == RenderState.Done) "Delete" else "Remove") }
                        }
                    }
                }
              }
            }
        },
        confirmButton = {
            Row {
                if (picked.size == 2) TextButton(onClick = { manager.combine(picked.toList(), stack = true); picked.clear() }) { Text("Stack") }
                if (picked.size >= 2) TextButton(onClick = { manager.combine(picked.toList(), stack = false); picked.clear() }) { Text("Join") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

private fun fixedMb(bytes: Long): String = simplemodelview.fixed(bytes / 1_000_000.0, 1) + " MB"
