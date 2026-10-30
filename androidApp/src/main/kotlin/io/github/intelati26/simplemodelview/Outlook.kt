package io.github.intelati26.simplemodelview

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import simplemodelview.Core
import simplemodelview.OutlookChoice
import simplemodelview.OutlookData
import simplemodelview.OutlookType
import simplemodelview.outlookChoiceFor
import simplemodelview.OutlookRepository
import simplemodelview.View
import simplemodelview.outlookAt
import simplemodelview.outlookReadout

/** An outlook file that was read: its areas and when it was read (epoch seconds). */
class LoadedOutlook(val data: OutlookData, val asOf: Long)

/**
 * The SPC outlooks drawn over the maps: which kind of map the user chose ([type], null = off), and every outlook file read so far (one per day and kind, by
 * [OutlookChoice.id]). A page does not pick a day: each frame is drawn with the outlook that covers its own valid time, so the polygons change as a loop plays.
 */
data class OutlookState(
    val type: OutlookType? = null,
    val loaded: Map<String, LoadedOutlook> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),     // why a file could not be read (by id)
    val reading: Set<String> = emptySet(),
    val version: Int = 0,                            // counts the changes that alter a picture
)

/**
 * Holds the chosen kind of SPC outlook for the whole process, so the REFS, MRMS and NBM pages draw the same one (the choice is saved). Files are read when a
 * frame needs them ([want] / [wantAll]) and again when older than 15 minutes as a page comes back to the screen; SPC issues a few times a day.
 */
class OutlookModel(private val repo: OutlookRepository, private val prefs: SharedPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(OutlookState(type = OutlookType.entries.firstOrNull { it.name == prefs.getString("outlook_type", null) }))
    val state: StateFlow<OutlookState> = _state
    private val lock = Any()

    private fun now() = System.currentTimeMillis() / 1000

    private inline fun change(f: (OutlookState) -> OutlookState) = synchronized(lock) { _state.value = f(_state.value) }

    /** Draws [type] (null: no outlook). */
    fun setType(type: OutlookType?) {
        prefs.edit().putString("outlook_type", type?.name).apply()
        change { OutlookState(type = type, version = it.version + 1) }
    }

    /** The outlook for a frame valid at [valid] (null: now), or null when none is chosen or none covers the time. */
    fun choiceFor(valid: Long?): OutlookChoice? {
        val type = _state.value.type ?: return null
        val at = now()
        return outlookChoiceFor(type, valid ?: at, at)
    }

    /** Makes sure the outlook for a frame valid at [valid] is read (or being read); the page redraws when it arrives. */
    fun want(valid: Long?) {
        val choice = choiceFor(valid) ?: return
        load(choice)
    }

    /** The same for every time of a loop, so the polygons are ready when the loop reaches them. */
    fun wantAll(valids: Collection<Long>) {
        for (choice in valids.mapNotNull { choiceFor(it) }.toSet()) load(choice)
    }

    private fun load(choice: OutlookChoice, force: Boolean = false) {
        val id = choice.id
        val st = _state.value
        if (id in st.reading) return
        if (!force && st.loaded[id]?.let { now() - it.asOf < 15 * 60 } == true) return
        change { it.copy(reading = it.reading + id, notes = it.notes - id) }
        scope.launch { fetch(choice) }
    }

    private suspend fun fetch(choice: OutlookChoice) {
        val id = choice.id
        try {
            val data = repo.load(choice)
            change { cur ->
                if (cur.type == null) cur.copy(reading = cur.reading - id)
                else cur.copy(loaded = cur.loaded + (id to LoadedOutlook(data, now())), reading = cur.reading - id, version = cur.version + 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            change { cur -> cur.copy(reading = cur.reading - id, notes = cur.notes + (id to (e.message ?: "The outlook could not be read"))) }
        }
    }

    /** Reads the outlook for a frame valid at [valid] now and waits for it (an export needs every frame's outlook in hand); a failure is a note, not an exception. */
    suspend fun ensure(valid: Long?) {
        val choice = choiceFor(valid) ?: return
        if (_state.value.loaded[choice.id]?.let { now() - it.asOf < 15 * 60 } == true) return
        change { it.copy(reading = it.reading + choice.id, notes = it.notes - choice.id) }
        fetch(choice)
    }

    /** A page came back to the screen: read again the files that are older than 15 minutes. */
    fun refreshIfStale() {
        val st = _state.value
        if (st.type == null) return
        for ((id, loaded) in st.loaded) if (now() - loaded.asOf >= 15 * 60) OutlookChoice.byId(id)?.let { load(it, force = true) }
    }

    /** What the outlook that covers [valid] says at a point, for the tap read-out; null when none is shown. */
    fun readoutAt(lat: Double, lon: Double, valid: Long?): String? {
        val choice = choiceFor(valid) ?: return null
        val loaded = _state.value.loaded[choice.id] ?: return null
        return outlookReadout(choice, outlookAt(loaded.data.shapes, lat, lon))
    }
}

/**
 * The areas of the outlook that covers a frame valid at [valid] as outlines in the pixels of the picture of [view] (a window of its grid at `scale / step`
 * pixels per cell); lowest level first, so the higher ones lie on top. Empty until that outlook has been read.
 */
internal fun outlookDraws(core: Core, model: OutlookModel, view: View, valid: Long?): List<AlertDraw> {
    val choice = model.choiceFor(valid) ?: return emptyList()
    val data = model.state.value.loaded[choice.id]?.data ?: return emptyList()
    val pixels = view.scale.toFloat() / view.step
    return data.shapes.flatMap { shape ->
        shape.polygons.map { polygon ->
            val rings = polygon.map { ring ->
                val out = FloatArray(ring.size)
                var i = 0
                while (i + 1 < ring.size) {
                    val g = core.latLonToGrid(view.grid, ring[i], ring[i + 1])
                    out[i] = ((g[0] + 0.5 - view.col0) * pixels).toFloat()
                    out[i + 1] = ((view.row0 + view.rows - 0.5 - g[1]) * pixels).toFloat()
                    i += 2
                }
                out
            }
            AlertDraw(rings, shape.color, dashed = shape.hatched, fill = !shape.hatched, combined = true)
        }
    }
}

/** The LightningCast overlay (the chance of lightning in the next hour, GOES-East): on or off, the last hour of 5-minute scans, and when they were read. */
data class LightningState(
    val enabled: Boolean = false,
    val frames: List<simplemodelview.LightningFrame> = emptyList(),
    val asOf: Long? = null,
    val note: String? = null,
    val busy: Boolean = false,
)

/**
 * Holds the LightningCast overlay for the whole process, so every page can draw it (the on / off choice is saved). CIMSS updates the placefile every few
 * minutes, so a page coming back to the screen (and the MRMS page, while it follows the radar) reads it again when it is over two minutes old.
 */
class LightningCastModel(private val repo: simplemodelview.LightningCastRepository, private val prefs: SharedPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(LightningState(enabled = prefs.getBoolean("lightningcast", false)))
    val state: StateFlow<LightningState> = _state
    private var job: Job? = null

    init { if (_state.value.enabled) refresh() }

    private fun now() = System.currentTimeMillis() / 1000

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("lightningcast", on).apply()
        job?.cancel()
        _state.value = LightningState(enabled = on, busy = on)
        if (on) refresh()
    }

    fun refresh() {
        if (!_state.value.enabled) return
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(busy = true, note = null)
            try {
                val frames = repo.load()
                if (_state.value.enabled) _state.value = LightningState(true, frames, now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.enabled) _state.value = _state.value.copy(busy = false, note = e.message ?: "LightningCast could not be read")
            }
        }
    }

    fun refreshIfStale() {
        val st = _state.value
        if (st.enabled && !st.busy && (st.asOf == null || now() - st.asOf > 120)) refresh()
    }
}

/**
 * The contours of the scan for [at] (epoch seconds; null: the newest) as lines in the pixels of the picture of [view], lowest chance first, in the colors of the
 * placefile. On the MRMS page [at] is the radar frame's time; the other pages show the newest scan.
 */
internal fun lightningDraws(core: Core, state: LightningState, view: View, at: Long?): List<AlertDraw> {
    if (!state.enabled) return emptyList()
    val frame = simplemodelview.lightningFrameFor(state.frames, at) ?: return emptyList()
    val pixels = view.scale.toFloat() / view.step
    return frame.contours.sortedBy { it.percent }.map { contour ->
        val p = contour.points
        val out = FloatArray(p.size)
        var i = 0
        while (i + 1 < p.size) {
            val g = core.latLonToGrid(view.grid, p[i], p[i + 1])
            out[i] = ((g[0] + 0.5 - view.col0) * pixels).toFloat()
            out[i + 1] = ((view.row0 + view.rows - 0.5 - g[1]) * pixels).toFloat()
            i += 2
        }
        AlertDraw(listOf(out), contour.color, dashed = false, fill = false, open = true)
    }
}

/** Emits when something that changes the overlays' pictures changes (a new outlook or scan, or a choice), so a page redraws. The current state is not emitted. */
internal fun overlayChanges(): kotlinx.coroutines.flow.Flow<List<Any?>> =
    kotlinx.coroutines.flow.combine(AppContainer.outlook.state, AppContainer.lightning.state) { o, l -> listOf(o.type, o.version, l.enabled, l.asOf) }
        .distinctUntilChanged().drop(1)
