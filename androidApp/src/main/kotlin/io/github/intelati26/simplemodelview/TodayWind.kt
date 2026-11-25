package io.github.intelati26.simplemodelview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import simplemodelview.Core
import simplemodelview.HourChance
import simplemodelview.Place
import simplemodelview.RefsRepository
import simplemodelview.ThunderTiming
import simplemodelview.WindTiming
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WindData(val run: String, val hours: List<HourChance>)

/** Reads SPC REFS's ensemble-mean gust (mph) at a place for the next 12 hours from the newest run that has its first hour (each hour is one 1 MB chunk). */
class WindReader(private val core: Core, private val repo: RefsRepository) {
    suspend fun read(place: Place, now: Long): WindData? {
        val cycles = try { repo.findCycles(now, 4) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        for (c in cycles.take(3)) {
            val store = try { repo.open(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val p = store.products.firstOrNull { it.id == "gust_mean" } ?: continue
            val times = try { repo.validTimes(store, p) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val first = times.indexOfFirst { it > now }
            if (first < 0) continue
            val g = core.latLonToGrid(p.grid, place.lat, place.lon)
            val col = Math.round(g[0]).toInt(); val row = Math.round(g[1]).toInt()
            if (col !in 0 until p.grid.columns || row !in 0 until p.grid.rows) return null
            val gate = Semaphore(4)
            val hours = coroutineScope {
                (first until minOf(times.size, first + 12)).map { s ->
                    async {
                        gate.withPermit {
                            val f = try { repo.readFrameTimed(store, p, s, 0) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            if (f?.fileTime == null) null else f.values[row * p.grid.columns + col].toDouble().takeIf { !it.isNaN() }?.let { HourChance(times[s], it) }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (hours.isEmpty()) continue            // the run exists but its chunks are not written yet: the next older one
            return WindData(c.label, hours)
        }
        return null
    }
}

private fun clockLabel(epoch: Long): String = SimpleDateFormat("h a", Locale.getDefault()).format(Date(epoch * 1000)).lowercase(Locale.getDefault())

private fun gustShade(mph: Double): Color = when {
    mph < 20 -> Color(0x00000000)
    mph < 30 -> Color(0xFFC0DD97)
    mph < 40 -> Color(0xFFFAC775)
    mph < 50 -> Color(0xFFEF9F27)
    mph < 60 -> Color(0xFFE24B4A)
    else -> Color(0xFF993556)
}

/**
 * The wind at the place: the gust timing against the user's line, and the damaging-wind side from the outlook (today's SPC wind chance) and the model (GREFS 4-hour
 * chance with how it changed from the last run).
 */
@Composable
internal fun WindCard(wind: WindData?, note: String?, busy: Boolean, placeName: String?, line: Int, onLine: (Int) -> Unit, outlook: PlaceOutlook?, day: Int, thunder: ThunderData?) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wind at ${placeName ?: "your place"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Box {
                Text("Gust line: $line mph ▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (l in WindTiming.LINES) DropdownMenuItem(text = { Text("$l mph") }, onClick = { menu = false; onLine(l) })
                }
            }
        }
        if (wind == null) Text(note ?: if (busy) "Reading the model…" else "No gust forecast could be read.", fontSize = 14.sp)
        else {
            val summary = WindTiming.summarize(wind.hours, line)
            Text(WindTiming.headline(summary, ::clockLabel), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (summary.firstOver != null) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurface)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (h in wind.hours) Box(
                    Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface).background(gustShade(h.percent))
                        .then(if (h.percent >= line) Modifier.border(BorderStroke(1.5.dp, Color(0xFFB3261E)), RoundedCornerShape(3.dp)) else Modifier),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clockLabel(wind.hours.first().valid), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("peak ${summary.peak?.percent?.let { Math.round(it) } ?: 0} mph · ${summary.peak?.let { clockLabel(it.valid) } ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(clockLabel(wind.hours.last().valid), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // the damaging-wind side
        val dayWord = if (day == 1) "today" else if (day == 2) "tomorrow" else "day $day"
        outlook?.wind?.let { Text("SPC damaging-wind chance $dayWord: $it%", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        thunder?.severe?.firstOrNull { it.name == "Damaging wind" }?.let { c ->
            val shown = if (c.percent < 1.0) "<1%" else "${Math.round(c.percent)}%"
            Text("Model damaging-wind chance, 4 hours: $shown", fontSize = 13.sp)
            ThunderTiming.rise(c.percent, c.earlier)?.let { Text("$it on the last run", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text("Model guidance (SPC REFS ensemble-mean gust${wind?.let { ", ${it.run} run" } ?: ""}): averaging smooths out the peaks, so a storm can gust higher than shown.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
