package io.github.intelati26.simplemodelview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import simplemodelview.Cycle
import simplemodelview.CycleStore
import simplemodelview.HourChance
import simplemodelview.Place
import simplemodelview.Product
import simplemodelview.RefsRepository
import simplemodelview.ThunderTiming
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One chance and what the previous run said for the same period. */
data class Chance(val percent: Double?, val earlier: Double?)

data class ThunderData(
    val run: String,
    val hours: List<HourChance>,
    val earlierHours: Map<Long, Double>,       // the previous run's chance for the same hours
    val nextHour: Chance,
    val next4: Chance,
    val rest: Chance,
    val severe: List<SevereChance> = emptyList(), val severeRun: String? = null,   // the next 4 hours by type (GREFS)
)

/** One severe type's chance at the place for the 4-hour period, and what the previous run said. */
data class SevereChance(val name: String, val percent: Double, val earlier: Double?)

/**
 * Reads SPC's REFSCT thunder probability (percent, on a 40.6 km grid) at a place from the newest run that has its first hour, and the same hours from the run before it, so the card can say
 * whether the chance is rising. Each hour is one small chunk; the three stores are the 1-hour, the 4-hour and the remainder-of-the-convective-day periods.
 */
class ThunderReader(private val core: Core, private val repo: RefsRepository) {
    private val one = "refsct-1hr/thunder_prob"
    private val four = "refsct-4hr/thunder_prob"
    private val full = "refsct-full/thunder_prob"

    private class Open(val store: CycleStore, val p1: Product, val p4: Product, val pf: Product, val t1: List<Long>, val t4: List<Long>, val tf: List<Long>, val sev: List<Pair<String, Product>>, val ts: List<Long>)

    private suspend fun open(cycle: Cycle): Open? {
        val store = try { repo.open(cycle) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        val p1 = store.products.firstOrNull { it.id == one } ?: return null
        val p4 = store.products.firstOrNull { it.id == four } ?: return null
        val pf = store.products.firstOrNull { it.id == full } ?: return null
        val sev = listOf("Tornado" to "grefs-4hr/tor_prob", "Damaging wind" to "grefs-4hr/wind_prob", "Large hail" to "grefs-4hr/hail_prob").mapNotNull { (n, id) -> store.products.firstOrNull { it.id == id }?.let { n to it } }
        return try { Open(store, p1, p4, pf, repo.validTimes(store, p1), repo.validTimes(store, p4), repo.validTimes(store, pf), sev, sev.firstOrNull()?.let { repo.validTimes(store, it.second) } ?: emptyList()) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private suspend fun valueAt(o: Open, p: Product, step: Int, place: Place): Double? {
        val frame = try { repo.readFrameTimed(o.store, p, step, 0) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        if (frame.fileTime == null) return null
        val g = core.latLonToGrid(p.grid, place.lat, place.lon)
        val col = Math.round(g[0]).toInt(); val row = Math.round(g[1]).toInt()
        if (col !in 0 until p.grid.columns || row !in 0 until p.grid.rows) return null
        return frame.values[row * p.grid.columns + col].toDouble().takeIf { !it.isNaN() }
    }

    suspend fun read(place: Place, now: Long): ThunderData? {
        val cycles = try { repo.findCycles(now, 4) } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        // the two newest runs are opened together (the newest may not have its chunks written yet: then the one before it is the current run and the third is its earlier one)
        val opened = coroutineScope { cycles.take(3).map { c -> async { open(c) } }.awaitAll() }
        var current: Open? = null; var currentAt = -1
        for ((i, o) in opened.withIndex()) {
            if (o == null) continue
            val first = o.t1.indexOfFirst { it > now }
            if (first < 0) continue
            if (valueAt(o, o.p1, first, place) == null) continue          // the run exists but its chunks are not all written yet: the next older one
            current = o; currentAt = i; break
        }
        val cur = current ?: return null
        val previous = opened.getOrNull(currentAt + 1)
        val gate = Semaphore(4)
        val first = cur.t1.indexOfFirst { it > now }
        val steps = (first until minOf(cur.t1.size, first + 12)).toList()
        return coroutineScope {
            val hours = steps.map { s -> async { gate.withPermit { valueAt(cur, cur.p1, s, place)?.let { HourChance(cur.t1[s], it) } } } }.awaitAll().filterNotNull()
            if (hours.isEmpty()) return@coroutineScope null
            val earlier = previous?.let { pv -> hours.map { h -> async { gate.withPermit { pv.t1.indexOf(h.valid).takeIf { it >= 0 }?.let { i -> valueAt(pv, pv.p1, i, place)?.let { h.valid to it } } } } }.awaitAll().filterNotNull().toMap() } ?: emptyMap()
            val at4 = cur.t4.indices.minByOrNull { Math.abs(cur.t4[it] - (now + 4 * 3600L)) }
            val atRest = cur.tf.indexOfFirst { it > now }
            val v4 = at4?.let { async { valueAt(cur, cur.p4, it, place) } }
            val vr = atRest.takeIf { it >= 0 }?.let { async { valueAt(cur, cur.pf, it, place) } }
            val e4 = if (previous != null && at4 != null) previous.t4.indexOf(cur.t4[at4]).takeIf { it >= 0 }?.let { async { valueAt(previous, previous.p4, it, place) } } else null
            val er = if (previous != null && atRest >= 0) previous.tf.indexOf(cur.tf[atRest]).takeIf { it >= 0 }?.let { async { valueAt(previous, previous.pf, it, place) } } else null
            // GREFS is written later than REFSCT: when this run has none yet, the newest run that has it is used (and named on the card)
            val sevAt = (currentAt until opened.size).firstOrNull { opened[it]?.sev?.isNotEmpty() == true && opened[it]!!.ts.isNotEmpty() }
            val sc = sevAt?.let { opened[it] }
            val sp = sevAt?.let { opened.getOrNull(it + 1) }
            val sNow = sc?.ts?.indices?.minByOrNull { Math.abs(sc.ts[it] - (now + 4 * 3600L)) }
            val sevJobs = if (sc == null || sNow == null) emptyList() else sc.sev.map { (n, pr) ->
                val ePrev = sp?.sev?.firstOrNull { it.first == n }?.second
                val eIdx = sp?.ts?.indexOf(sc.ts[sNow])?.takeIf { it >= 0 }
                Triple(n, async { valueAt(sc, pr, sNow, place) }, if (sp != null && ePrev != null && eIdx != null) async { valueAt(sp, ePrev, eIdx, place) } else null)
            }
            val severe = sevJobs.mapNotNull { (n, v, e) -> v.await()?.let { SevereChance(n, it, e?.await()) } }
            ThunderData(
                severe = severe, severeRun = sc?.store?.cycle?.label,
                run = cur.store.cycle.label, hours = hours, earlierHours = earlier,
                nextHour = Chance(hours.first().percent, earlier[hours.first().valid]),
                next4 = Chance(v4?.await(), e4?.await()),
                rest = Chance(vr?.await(), er?.await()),
            )
        }
    }
}

private fun clockLabel(epoch: Long): String = SimpleDateFormat("h a", Locale.getDefault()).format(Date(epoch * 1000)).lowercase(Locale.getDefault())

private fun shade(p: Double): Color = when {
    p < 5 -> Color(0x00000000)
    p < 15 -> Color(0xFFC0DD97)
    p < 30 -> Color(0xFFFAC775)
    p < 50 -> Color(0xFFEF9F27)
    p < 70 -> Color(0xFFE24B4A)
    else -> Color(0xFF993556)
}

/** The thunder chance at the place: the headline against the user's line, the three periods, and the next 12 hours. */
@Composable
internal fun ThunderCard(data: ThunderData?, note: String?, busy: Boolean, placeName: String?, line: Int, onLine: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Thunder chance at ${placeName ?: "your place"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Box {
                Text("Your line: $line% ▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (l in ThunderTiming.LINES) DropdownMenuItem(text = { Text("$l%") }, onClick = { menu = false; onLine(l) })
                }
            }
        }
        if (data == null) { Text(note ?: if (busy) "Reading the model…" else "No thunder chance could be read.", fontSize = 14.sp); return@Column }
        val summary = ThunderTiming.summarize(data.hours, line)
        val over = summary.firstOver != null
        Text(ThunderTiming.headline(summary, ::clockLabel), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (over) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurface)

        // the hours
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (h in data.hours) {
                val isOver = h.percent >= line
                Box(
                    Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface).background(shade(h.percent))
                        .then(if (isOver) Modifier.border(BorderStroke(1.5.dp, Color(0xFFB3261E)), RoundedCornerShape(3.dp)) else Modifier),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockLabel(data.hours.first().valid), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("peak ${summary.peak?.percent?.let { Math.round(it) } ?: 0}% · ${summary.peak?.let { clockLabel(it.valid) } ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(clockLabel(data.hours.last().valid), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // the three periods, each against the line
        for ((label, c) in listOf("Next hour" to data.nextHour, "Next 4 hours" to data.next4, "Rest of the day" to data.rest)) {
            val p = c.percent ?: continue
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text(label, fontSize = 12.sp, modifier = Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxWidth(fraction = (p / 100.0).coerceIn(0.0, 1.0).toFloat()).fillMaxHeight().background(if (p >= line) Color(0xFFE24B4A) else shade(maxOf(p, 5.0)).copy(alpha = 1f)))
                    // your line: a mark at its share of the bar
                    Box(Modifier.fillMaxWidth(fraction = line / 100f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) { Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface)) }
                }
                Text("${Math.round(p)}%", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(36.dp))
            }
            ThunderTiming.rise(p, c.earlier)?.let { Text("$it on the last run", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 104.dp)) }
        }
        if (data.severe.isNotEmpty()) {
            val top = data.severe.maxByOrNull { it.percent }
            Text("Severe, 4 hours (by type)" + (data.severeRun?.takeIf { it != data.run }?.let { " · $it run" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            for (c in data.severe) {
                val isTop = c === top && c.percent >= 1.0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.name, fontSize = 12.sp, fontWeight = if (isTop) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface)) {
                        // the bar is scaled to 30% so small chances are visible; the mark is your line when it is on that scale
                        Box(Modifier.fillMaxWidth(fraction = (c.percent / 30.0).coerceIn(0.0, 1.0).toFloat()).fillMaxHeight().background(if (c.percent >= line) Color(0xFFE24B4A) else Color(0xFFEF9F27)))
                    }
                    Text(if (c.percent < 1.0) "<1%" else "${Math.round(c.percent)}%", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(36.dp))
                }
                ThunderTiming.rise(c.percent, c.earlier)?.let { Text("$it on the last run", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 104.dp)) }
            }
            if (top != null && top.percent >= 1.0) Text("Highest at your place: ${top.name.lowercase()}", fontSize = 12.sp)
        }
        Text("Model guidance (SPC REFS thunder probability, ${data.run} run), not a forecast.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
