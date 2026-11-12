package io.github.intelati26.simplemodelview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import simplemodelview.NbmTextTable
import simplemodelview.Cycle
import simplemodelview.NwsGridData
import simplemodelview.Place
import simplemodelview.PointTable
import simplemodelview.WindProducts
import simplemodelview.buildPointTable
import simplemodelview.decodePlaces
import simplemodelview.epochSeconds
import simplemodelview.formatUtc
import simplemodelview.nwsGapTable

data class PointState(
    val places: List<Place> = emptyList(),
    val placeIndex: Int = 0,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val station: String? = null,
    val stationName: String? = null,
    val table: PointTable? = null,
    val sources: String = "",                 // which files the columns came from, for the line under the header
    val selected: Int? = null,                // the column picked (an index into the table's columns)
    val checking: Boolean = false,            // looking for a REFS run that has the time picked
)

/**
 * The extended point view: the NBM's forecast for the nearest bulletin station of a saved place, hours 0 to 264 in one table. The hours before the
 * short-range bulletin come from the NWS gridded forecast; the bulletins are read one station at a time from the bucket (see NbmTextRepository).
 * A place's station and grid link are asked of api.weather.gov once and kept, so its coordinates are sent there once.
 */
class PointViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppContainer.prefs
    private val bulletins = AppContainer.nbmText
    private val nws = AppContainer.nws
    private val refs = AppContainer.repo
    private var job: Job? = null
    private var lastLoad = 0L
    private var loadedFor: Place? = null

    private companion object {
        /** How far a REFS run reaches, in hours after its start. */
        const val REFS_HOURS = 60
    }

    private val _state = MutableStateFlow(PointState())
    val state: StateFlow<PointState> = _state
    private inline fun update(f: (PointState) -> PointState) { _state.value = f(_state.value) }

    init { reloadFromPrefs() }

    /** The places may have been changed on another page; the place used last stays picked. */
    fun reloadFromPrefs() {
        val places = decodePlaces(prefs.getString("places", "").orEmpty())
        val remembered = prefs.getString("point_place", null)
        val index = places.indexOfFirst { "${it.lat},${it.lon}" == remembered }.takeIf { it >= 0 } ?: 0
        update { it.copy(places = places, placeIndex = index.coerceIn(0, maxOf(0, places.size - 1))) }
        val place = places.getOrNull(_state.value.placeIndex)
        if (place == null) update { it.copy(table = null, station = null, error = null, status = null, busy = false) }
        else if (place != loadedFor || _state.value.table == null) load()
    }

    fun pickPlace(index: Int) {
        val place = _state.value.places.getOrNull(index) ?: return
        prefs.edit().putString("point_place", "${place.lat},${place.lon}").apply()
        update { it.copy(placeIndex = index, selected = null) }
        load()
    }

    /**
     * Opens the synoptic set up for [valid] (epoch seconds): the REFS run that has the time, the newest of those, or else (null) the GFS page at the nearest frame.
     * [open] is called with the REFS run to use, or null for GFS. The runs are asked, not guessed: a run still being written may not have reached the time.
     */
    fun openSynoptic(valid: Long, open: (Cycle?) -> Unit) {
        if (_state.value.checking) return
        update { it.copy(checking = true) }
        viewModelScope.launch {
            val cycle = try { refsRunWith(valid) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            update { it.copy(checking = false) }
            open(cycle)
        }
    }

    private suspend fun refsRunWith(valid: Long): Cycle? {
        val now = System.currentTimeMillis() / 1000
        if (valid < now - 3600 || valid > now + (REFS_HOURS + 6) * 3600L) return null           // nothing of REFS is that far ahead (or behind): no need to ask
        for (cycle in refs.findCycles(now)) {
            val start = cycle.epochSeconds
            if (valid < start || valid > start + REFS_HOURS * 3600L) continue
            val store = try { refs.open(cycle) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val product = store.products.firstOrNull { it.id == WindProducts.id(500) } ?: store.products.firstOrNull() ?: continue
            val times = try { refs.validTimes(store, product) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            if (times.any { kotlin.math.abs(it - valid) < 1800 }) return cycle
        }
        return null
    }

    fun select(column: Int?) = update { it.copy(selected = if (it.selected == column) null else column) }

    /** Reads the table again when it is more than half an hour old (the page was left and came back to). */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastLoad > 30 * 60 * 1000 && !_state.value.busy && _state.value.places.isNotEmpty()) load()
    }

    fun load() {
        val place = _state.value.places.getOrNull(_state.value.placeIndex) ?: return
        job?.cancel()
        job = viewModelScope.launch {
            update { it.copy(busy = true, error = null, status = "Finding the newest bulletins") }
            try {
                val now = System.currentTimeMillis() / 1000
                val files = coroutineScope {
                    listOf("NBS", "NBE", "NBX").map { kind -> async { kind to bulletins.newest(kind, now) } }.map { it.await() }.toMap()
                }
                if (files.values.all { it == null }) throw IllegalStateException("No NBM bulletins were found on the server.")
                val key = "point_station_${place.lat}_${place.lon}"
                var saved = prefs.getString(key, null)?.split("|")?.takeIf { it.size == 3 }       // id | name | grid link
                if (saved == null) {
                    update { it.copy(status = "Finding the bulletin station for ${place.name}") }
                    val point = nws.point(place.lat, place.lon) ?: throw IllegalStateException("The NWS does not have a forecast point for ${place.name} (is it in the US?).")
                    val probe = files["NBX"] ?: files["NBE"] ?: files["NBS"]!!
                    val station = nws.station(point) { id -> bulletins.station(probe, id) != null }
                        ?: throw IllegalStateException("No bulletin station was found near ${place.name}.")
                    saved = listOf(station.id, station.name.replace('|', '/'), point.gridUrl)
                    prefs.edit().putString(key, saved.joinToString("|")).apply()
                }
                val (id, name, gridUrl) = saved
                update { it.copy(station = id, stationName = name, status = "Reading the bulletins for $id") }
                val tables: Map<String, NbmTextTable?> = coroutineScope {
                    files.map { (kind, file) -> async { kind to file?.let { f -> try { bulletins.station(f, id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } } } }
                        .map { it.await() }.toMap()
                }
                val grid: NwsGridData? = try { nws.grid(gridUrl) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                val nbs = tables["NBS"]
                val gap = if (grid != null && nbs != null) nwsGapTable(grid, now - 3 * 3600L, nbs.validTimes.first(), id) else null
                val table = buildPointTable(id, gap, nbs, tables["NBE"], tables["NBX"], fromEpoch = now - 3 * 3600L)
                if (table.columns.isEmpty()) throw IllegalStateException("The bulletins have no forecast for $id.")
                val sources = listOfNotNull(
                    gap?.let { "NWS grid to ${formatUtc(it.validTimes.last())}" },
                    nbs?.let { "NBS ${formatUtc(it.cycleEpoch).takeLast(3)}" }, tables["NBE"]?.let { "NBE ${formatUtc(it.cycleEpoch).takeLast(3)}" }, tables["NBX"]?.let { "NBX ${formatUtc(it.cycleEpoch).takeLast(3)}" },
                ).joinToString(" · ")
                lastLoad = System.currentTimeMillis()
                loadedFor = place
                update { it.copy(busy = false, status = null, table = table, sources = sources, selected = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(busy = false, status = null, error = e.message ?: e.toString()) }
            }
        }
    }
}
