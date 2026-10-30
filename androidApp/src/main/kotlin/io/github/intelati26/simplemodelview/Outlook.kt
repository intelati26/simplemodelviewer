package io.github.intelati26.simplemodelview

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import simplemodelview.Core
import simplemodelview.OutlookChoice
import simplemodelview.OutlookData
import simplemodelview.OutlookRepository
import simplemodelview.View
import simplemodelview.outlookAt
import simplemodelview.outlookReadout

/** The SPC outlook drawn over the maps (the same one on every page): which one, what was read, when, and a note when it could not be read or has no areas. */
data class OutlookState(
    val choice: OutlookChoice? = null,
    val data: OutlookData? = null,
    val asOf: Long? = null,            // when it was read (epoch seconds)
    val note: String? = null,
    val busy: Boolean = false,
)

/**
 * Holds the chosen SPC outlook for the whole process, so the REFS, MRMS and NBM pages draw the same one (the choice is saved). It reads the file when the
 * choice is made and again when it is older than 15 minutes as a page comes back to the screen; SPC issues a few times a day.
 */
class OutlookModel(private val repo: OutlookRepository, private val prefs: SharedPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(OutlookState(choice = OutlookChoice.byId(prefs.getString("outlook_choice", null))))
    val state: StateFlow<OutlookState> = _state
    private var job: Job? = null

    init { if (_state.value.choice != null) refresh() }

    private fun now() = System.currentTimeMillis() / 1000

    /** Draws [choice] (null: no outlook). */
    fun set(choice: OutlookChoice?) {
        prefs.edit().putString("outlook_choice", choice?.id).apply()
        job?.cancel()
        _state.value = OutlookState(choice = choice, busy = choice != null)
        if (choice != null) refresh()
    }

    fun refresh() {
        val choice = _state.value.choice ?: return
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(busy = true, note = null)
            try {
                val data = repo.load(choice)
                val at = now()
                val note = when {
                    data.shapes.isEmpty() -> "No risk areas in this outlook"
                    data.expires != null && data.expires < at -> "This outlook has expired; a new one is due"
                    else -> null
                }
                if (_state.value.choice == choice) _state.value = OutlookState(choice, data, at, note)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.choice == choice) _state.value = _state.value.copy(busy = false, note = e.message ?: "The outlook could not be read")
            }
        }
    }

    /** A page came back to the screen: read again when what is shown is older than 15 minutes. */
    fun refreshIfStale() {
        val st = _state.value
        if (st.choice != null && !st.busy && (st.asOf == null || now() - st.asOf > 15 * 60)) refresh()
    }

    /** What the outlook says at a point, for the tap read-out; null when none is shown. */
    fun readoutAt(lat: Double, lon: Double): String? {
        val st = _state.value
        val choice = st.choice ?: return null
        val data = st.data ?: return null
        return outlookReadout(choice, outlookAt(data.shapes, lat, lon))
    }
}

/** The outlook's areas as outlines in the pixels of the picture of [view] (a window of its grid at `scale / step` pixels per cell); lowest level first, so the higher ones lie on top. */
internal fun outlookDraws(core: Core, state: OutlookState, view: View): List<AlertDraw> {
    val data = state.data ?: return emptyList()
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
