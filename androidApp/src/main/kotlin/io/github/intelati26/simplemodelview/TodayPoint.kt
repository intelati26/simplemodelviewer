package io.github.intelati26.simplemodelview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import simplemodelview.NbmTextRepository
import simplemodelview.NbmTextTable
import simplemodelview.NwsPointClient
import simplemodelview.Place
import simplemodelview.PointDay
import simplemodelview.PointSummary
import simplemodelview.buildPointTable
import simplemodelview.nwsGapTable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The days of the NBM forecast at the bulletin station nearest a place, with the station and the place's time zone. */
data class PointForecast(val station: String, val stationName: String, val zone: ZoneId, val days: List<PointDay>, val cycle: String)

/**
 * Reads the NBM bulletins (the short-range and the extended) for the station nearest a place, the same way the Extended page does and from the same saved station
 * (asked of api.weather.gov once per place and kept), and gathers the next days by the place's own days.
 */
class PointForecastReader(private val bulletins: NbmTextRepository, private val nws: NwsPointClient) {
    suspend fun read(place: Place, now: Long): PointForecast? {
        val prefs = AppContainer.prefs
        val files = coroutineScope { listOf("NBS", "NBE").map { kind -> async { kind to bulletins.newest(kind, now) } }.map { it.await() }.toMap() }
        if (files.values.all { it == null }) return null
        val key = "point_station_${place.lat}_${place.lon}"
        var saved = prefs.getString(key, null)?.split("|")?.takeIf { it.size == 4 }          // id | name | grid link | time zone
        if (saved == null) {
            val old = prefs.getString(key, null)?.split("|")?.takeIf { it.size == 3 }
            val point = nws.point(place.lat, place.lon) ?: return null
            val probe = files["NBE"] ?: files["NBS"]!!
            val station = if (old != null) null else nws.station(point) { id -> bulletins.station(probe, id) != null } ?: return null
            saved = listOf(old?.get(0) ?: station!!.id, old?.get(1) ?: station!!.name.replace('|', '/'), old?.get(2) ?: point.gridUrl, point.timeZone)
            prefs.edit().putString(key, saved.joinToString("|")).apply()
        }
        val (id, name, gridUrl, zoneId) = saved
        val tables: Map<String, NbmTextTable?> = coroutineScope {
            files.map { (kind, file) -> async { kind to file?.let { f -> try { bulletins.station(f, id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } } } }.map { it.await() }.toMap()
        }
        val nbs = tables["NBS"]
        val grid = try { nws.grid(gridUrl) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val gap = if (grid != null && nbs != null) nwsGapTable(grid, now - 3 * 3600L, nbs.validTimes.first(), id) else null
        val table = buildPointTable(id, gap, nbs, tables["NBE"], null, fromEpoch = now - 3 * 3600L)
        val zone = try { ZoneId.of(zoneId) } catch (_: Exception) { ZoneId.systemDefault() }
        val days = PointSummary.days(table, { t -> ZonedDateTime.ofInstant(Instant.ofEpochSecond(t), zone).toLocalDate().toEpochDay().toInt() }, now - 3 * 3600L)
        if (days.isEmpty()) return null
        return PointForecast(id, name, zone, days, (nbs ?: tables["NBE"])!!.let { "${it.kind} ${simplemodelview.formatUtc(it.cycleEpoch).takeLast(3)}" })
    }
}

private fun dayName(zone: ZoneId, t: Long, today: Boolean): String =
    if (today) "Today" else DateTimeFormatter.ofPattern("EEE", Locale.US).format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(t), zone))

private fun mph(kt: Int) = Math.round(kt * 1.15078).toInt()

/** The general forecast at the nearest station: one line a day (words for the sky, high and low, rain chance, wind), and the way to the whole table. */
@Composable
internal fun PointCard(data: PointForecast?, note: String?, busy: Boolean, placeName: String?, onMore: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Forecast at ${placeName ?: "your place"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (data == null) { Text(note ?: if (busy) "Reading the National Blend…" else "No forecast could be read.", fontSize = 14.sp); return@Column }
        Text("Nearest station: ${data.stationName} (${data.station})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val today = ZonedDateTime.now(data.zone).toLocalDate().toEpochDay().toInt()
        for (d in data.days) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dayName(data.zone, d.firstValid, d.day == today), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(52.dp))
                Column(Modifier.weight(1f)) {
                    val hiLo = listOfNotNull(d.high?.let { "High $it°" }, d.low?.let { "low $it°" }).joinToString(" · ")
                    Text(listOfNotNull(PointSummary.skyWords(d.sky), hiLo.ifEmpty { null }).joinToString(" · "), fontSize = 14.sp)
                    val extra = listOfNotNull(
                        d.pop?.takeIf { it >= 10 }?.let { "Rain chance $it%" }, d.thunder?.takeIf { it >= 20 }?.let { "thunder $it%" },
                        d.snowTenths?.takeIf { it >= 1 }?.let { "snow ${"%.1f".format(Locale.US, it / 10.0)} in" },
                        d.gustKt?.takeIf { it >= 20 }?.let { "gusts ${mph(it)} mph" } ?: d.windKt?.takeIf { it >= 10 }?.let { "wind ${mph(it)} mph" },
                    ).joinToString(" · ")
                    if (extra.isNotEmpty()) Text(extra, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Model guidance (the National Blend of Models, ${data.cycle}), not an official forecast.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onMore, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("The full table (Extended)") }
    }
}
