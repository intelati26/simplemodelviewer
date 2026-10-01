package org.spcrefs.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import spcrefs.Catalogue
import spcrefs.Cycle
import spcrefs.CycleStore
import spcrefs.Product
import spcrefs.RefsException
import spcrefs.RefsRepository
import spcrefs.Sector
import spcrefs.Style
import spcrefs.View
import spcrefs.formatUtc
import spcrefs.epochSeconds
import spcrefs.readout
import spcrefs.withAutoStops
import java.io.File
import kotlin.math.floor

data class UiState(
    val busy: Boolean = true,
    val error: String? = null,
    val cycles: List<Cycle> = emptyList(),
    val cycle: Cycle? = null,
    val products: List<Product> = emptyList(),
    val product: Product? = null,
    val members: List<String> = emptyList(),
    val member: Int = 0,
    val times: List<Long> = emptyList(),
    val timeIndex: Int = 0,
    val sector: Sector = Sector.all[0],
    val map: Bitmap? = null,
    val style: Style? = null,
    val crosshair: Pair<Float, Float>? = null,   // fractions (0..1) of the picture
    val readout: String? = null,
    val playing: Boolean = false,
    val prefetch: Float? = null,                 // 0..1 while a loop is being downloaded
    val favourites: Set<String> = emptySet(),
) {
    val validLabel: String
        get() = times.getOrNull(timeIndex)?.let { formatUtc(it) } ?: ""
    val forecastHour: Int?
        get() = cycle?.let { c -> times.getOrNull(timeIndex)?.let { ((it - c.epochSeconds) / 3600).toInt() } }
}

class RefsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = NativeCore()
    private val repo = RefsRepository(OkHttpHttp(app), core)
    private val renderer = MapRenderer(core, MapLines(app, core))
    private val prefs = app.getSharedPreferences("spcrefs", Context.MODE_PRIVATE)

    private var store: CycleStore? = null
    private var values: FloatArray? = null
    private var view: View = View.CONUS
    private var dark = false
    private var frameJob: Job? = null
    private var playJob: Job? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        UiState(
            favourites = prefs.getStringSet("favourites", emptySet()).orEmpty(),
            sector = Sector.all.firstOrNull { it.name == prefs.getString("sector", null) } ?: Sector.all[0],
        )
    )
    val state: kotlinx.coroutines.flow.StateFlow<UiState> = _state

    private inline fun update(f: (UiState) -> UiState) { _state.value = f(_state.value) }

    init { load() }

    fun load() {
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try {
                val cycles = repo.findCycles(System.currentTimeMillis() / 1000)
                if (cycles.isEmpty()) throw RefsException("Could not reach SPC's REFS data. Check the connection and try again.")
                update { it.copy(cycles = cycles) }
                openCycle(cycles.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message ?: "Something went wrong") }
            }
        }
    }

    fun pickCycle(cycle: Cycle) {
        stopPlaying()
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try { openCycle(cycle) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    private suspend fun openCycle(cycle: Cycle) {
        val opened = repo.open(cycle)
        store = opened
        val last = prefs.getString("product", null)
        val product = opened.products.firstOrNull { it.id == last }
            ?: opened.products.firstOrNull { it.id == "uh25_04h_max_overlap_max" } ?: opened.products.first()
        val members = repo.members(opened)
        update { it.copy(cycle = cycle, products = opened.products, members = members, member = it.member.coerceIn(0, maxOf(0, members.size - 1))) }
        selectProductInternal(product)
    }

    fun pickProduct(product: Product) {
        stopPlaying()
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try { selectProductInternal(product) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    private suspend fun selectProductInternal(product: Product) {
        val s = store ?: return
        prefs.edit().putString("product", product.id).apply()
        val times = repo.validTimes(s, product)
        update { it.copy(product = product, times = times, timeIndex = it.timeIndex.coerceIn(0, maxOf(0, times.size - 1))) }
        loadFrame()
    }

    fun pickMember(index: Int) { update { it.copy(member = index) }; showFrame() }

    fun pickTime(index: Int) {
        update { it.copy(timeIndex = index.coerceIn(0, maxOf(0, it.times.size - 1))) }
        showFrame()
    }

    fun pickSector(sector: Sector) {
        prefs.edit().putString("sector", sector.name).apply()
        update { it.copy(sector = sector, crosshair = null, readout = null) }
        viewModelScope.launch { rerender() }
    }

    fun setDark(isDark: Boolean) {
        if (dark == isDark) return
        dark = isDark
        if (values != null) viewModelScope.launch { rerender() }
    }

    fun toggleFavourite(product: Product) {
        val favourites = _state.value.favourites.let { if (product.id in it) it - product.id else it + product.id }
        prefs.edit().putStringSet("favourites", favourites).apply()
        update { it.copy(favourites = favourites) }
    }

    /** Frame for the current selection; a newer request replaces one still loading. */
    private fun showFrame() {
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            try { loadFrame() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    private suspend fun loadFrame() {
        val s = store ?: return
        val st = _state.value
        val product = st.product ?: return
        update { it.copy(busy = true, error = null) }
        values = repo.readFrame(s, product, st.timeIndex, st.member)
        rerender()
    }

    private suspend fun rerender() {
        val product = _state.value.product ?: return
        val data = values ?: return
        val sector = _state.value.sector
        val members = _state.value.members
        val (bitmap, style, v) = withContext(Dispatchers.Default) {
            val v = if (sector.name == "CONUS") View.CONUS else sector.view(core)
            val style = withAutoStops(product.style, data)
            Triple(renderer.render(product, style, data, members, v, dark), style, v)
        }
        view = v
        update { it.copy(map = bitmap, style = style, busy = false) }
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    /** A tap on the picture, given as fractions of its width and height. */
    fun onTap(fx: Float, fy: Float) {
        val product = _state.value.product ?: return
        val data = values ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { update { it.copy(crosshair = null, readout = null) }; return }
        val column = view.col0 + floor(fx * view.cols).toInt().coerceIn(0, view.cols - 1)
        val row = view.row0 + view.rows - 1 - floor(fy * view.rows).toInt().coerceIn(0, view.rows - 1)
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, data, it.members, column, row)) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null) }

    fun togglePlay() { if (_state.value.playing) stopPlaying() else startPlaying() }

    private fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        update { it.copy(playing = false, prefetch = null) }
    }

    private fun startPlaying() {
        val s = store ?: return
        val st = _state.value
        val product = st.product ?: return
        if (st.times.size < 2) return
        playJob = viewModelScope.launch {
            update { it.copy(playing = true, prefetch = 0f) }
            try {
                // download the whole loop first (politely: a few at a time); the HTTP cache then serves each pass
                val gate = Semaphore(3)
                var done = 0
                coroutineScope {
                    st.times.indices.map { t ->
                        async { gate.withPermit { repo.prefetchFrame(s, product, t, st.member) }; done += 1; update { it.copy(prefetch = done / st.times.size.toFloat()) } }
                    }.awaitAll()
                }
                update { it.copy(prefetch = null) }
                while (true) {
                    val next = (_state.value.timeIndex + 1) % _state.value.times.size
                    update { it.copy(timeIndex = next) }
                    loadFrame()
                    delay(if (next == _state.value.times.size - 1) 1200 else 250)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(playing = false, prefetch = null, busy = false, error = e.message) }
            }
        }
    }

    /** Writes the shareable picture (header, map, colour key) and returns the file, or null when there is nothing to share. */
    suspend fun buildShareFile(): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val product = st.product ?: return@withContext null
        val data = values ?: return@withContext null
        val cycle = st.cycle ?: return@withContext null
        val style = withAutoStops(product.style, data)
        val map = renderer.render(product, style, data, st.members, view, dark = false)
        val header = "Run ${cycle.label}   Valid ${st.validLabel}" + (st.forecastHour?.let { "  (f${it.toString().padStart(2, '0')})" } ?: "") +
            "   Data: NOAA/NWS Storm Prediction Center (experimental)"
        val image = renderer.shareImage(map, product, style, header, st.members)
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "spc_refs_${product.id.filter { it.isLetterOrDigit() || it == '_' }.take(40)}_${cycle.label.filter { it.isDigit() }}_f${st.timeIndex}.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }
}
