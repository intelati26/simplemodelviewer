package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import simplemodelview.BufkitModel
import simplemodelview.BufkitStation
import simplemodelview.placeLabel

/** What was found for one model: its nearest stations with a file, or null when they could not be looked up. */
private class StationsFound(val model: BufkitModel, val stations: List<BufkitStation>?)

/**
 * The stations near a point that have a model sounding (BUFKIT files of Iowa State, one per ASOS airport): the nearest few for the RRFS and for the GFS. The
 * profiles themselves are not read yet; this is the station lookup.
 */
@Composable
internal fun SoundingStationsDialog(lat: Double, lon: Double, onDismiss: () -> Unit) {
    var found by remember(lat, lon) { mutableStateOf<List<StationsFound>?>(null) }
    LaunchedEffect(lat, lon) {
        found = BufkitModel.entries.map { StationsFound(it, try { AppContainer.bufkit.near(lat, lon, it) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Soundings near ${placeLabel(lat, lon)}") },
        text = {
            Column {
                val result = found
                if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                else for (r in result) {
                    Text(r.model.label, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold)
                    when {
                        r.stations == null -> Text("Could not look up stations (needs a U.S. point and the network).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        r.stations.isEmpty() -> Text("No station with a ${r.model.label} file near here.", style = MaterialTheme.typography.bodySmall)
                        else -> for (s in r.stations) Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${s.id}  ·  ${if (s.km < 10) "%.1f".format(s.km) else "%.0f".format(s.km)} km", style = MaterialTheme.typography.bodyMedium)
                            Text(s.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
                Text("Model soundings: Iowa State's BUFKIT feed. Stations are the airports' ASOS ids.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
