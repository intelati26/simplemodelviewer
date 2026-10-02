package io.github.intelati26.simplemodelview

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import simplemodelview.Grid
import simplemodelview.MrmsCatalogue
import simplemodelview.MrmsFile
import simplemodelview.MrmsFrame
import simplemodelview.MrmsProduct
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.mrmsStep
import simplemodelview.readout
import kotlin.math.floor

/** The MRMS page: a product, a sector, the newest scans of that product and the one being shown. */
data class MrmsState(
    val busy: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val product: MrmsProduct = MrmsCatalogue.default,
    val sector: Sector = Sector.all[0],
    val files: List<MrmsFile> = emptyList(),
    val index: Int = 0,
    val map: Bitmap? = null,
    val style: Style? = null,
    val crosshair: Pair<Float, Float>? = null,   // fractions (0..1) of the picture
    val readout: String? = null,
    val playing: Boolean = false,
    val prefetch: Float? = null,                 // 0..1 while a loop is being downloaded
    val cellKilometres: Double? = null,          // resolution of the shown picture (after any decimation)
) {
    val file: MrmsFile? get() = files.getOrNull(index)
}

class MrmsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.mrms
    private val renderer = MapRenderer(core, AppContainer.lines)

    private var dark = false
    private var frameJob: Job? = null
    private var playJob: Job? = null
    private var shown: Pair<Product, FloatArray>? = null     // what the picture shows, for the read-out
    private var view: View? = null

    // decoded windows, newest use last; the budget keeps a loop from filling the heap (4 bytes per cell)
    private val decoded = LinkedHashMap<String, MrmsFrame>(16, 0.75f, true)
    private val cellBudget = 20_000_000L

    private val _state = MutableStateFlow(
        MrmsState(
            product = MrmsCatalogue.byId(prefs.getString("mrms_product", null)) ?: MrmsCatalogue.default,
            sector = Sector.all.firstOrNull { it.name == prefs.getString("mrms_sector", null) } ?: Sector.all[0],
        )
    )
    val state: StateFlow<MrmsState> = _state

    private inline fun update(f: (MrmsState) -> MrmsState) { _state.value = f(_state.value) }

    private val dataSaver get() = prefs.getBoolean("data_saver", false)
    private val wifiOnlyLoop get() = prefs.getBoolean("wifi_only", false)

    init { load() }

    /** (Re)reads the list of newest files and shows the latest one. */
    fun load() {
        stopPlaying()
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            var failure: Exception? = null
            for (attempt in 0..3) {       // a network that is not ready yet fixes itself: retry a few times
                try {
                    val files = repo.listFiles(_state.value.product, System.currentTimeMillis() / 1000)
                    if (files.isEmpty()) throw IllegalStateException("No recent ${_state.value.product.title} files on the MRMS server.")
                    update { it.copy(files = files, index = files.size - 1) }
                    showCurrent()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    if (attempt < 3) delay(2000L shl attempt)
                }
            }
            update { it.copy(busy = false, error = failure?.message ?: "Something went wrong") }
        }
    }

    fun pickProduct(product: MrmsProduct) {
        prefs.edit().putString("mrms_product", product.id).apply()
        update { it.copy(product = product, crosshair = null, readout = null) }
        load()
    }

    fun pickSector(sector: Sector) {
        prefs.edit().putString("mrms_sector", sector.name).apply()
        stopPlaying()
        update { it.copy(sector = sector, crosshair = null, readout = null) }
        showFrame()
    }

    fun pickIndex(index: Int) {
        update { it.copy(index = index.coerceIn(0, maxOf(0, it.files.size - 1))) }
        showFrame()
    }

    fun setDark(isDark: Boolean) {
        if (dark == isDark) return
        dark = isDark
        if (shown != null) showFrame()
    }

    fun dismissNotice() = update { it.copy(notice = null) }

    private fun showFrame() {
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    private fun stepFor(product: MrmsProduct, sector: Sector) =
        mrmsStep(product.cellDegrees, sector.west, sector.east, 2000, dataSaver)

    private suspend fun showCurrent() {
        val st = _state.value
        val file = st.file ?: return
        val product = st.product
        val sector = st.sector
        val step = stepFor(product, sector)
        update { it.copy(busy = true, error = null) }
        val key = "${file.key}|${sector.name}|$step"
        val frame = decoded[key] ?: run {
            val margin = 0.05
            val f = repo.loadFrame(product, file, sector.west - margin, sector.south - margin, sector.east + margin, sector.north + margin, step)
            decoded[key] = f
            trimCache()
            f
        }
        val window = frame.window
        val grid = window.grid
        val scale = pixelScale(grid)
        val v = View(sector.name, 0, 0, grid.columns, grid.rows, scale, 1, grid)
        val synthetic = Product(product.id, product.title, product.group, product.style, hasMembers = false, timeDimension = null, grid = grid)
        val bitmap = withContext(Dispatchers.Default) { renderer.render(synthetic, product.style, window.values, emptyList(), v, dark) }
        view = v
        shown = synthetic to window.values
        update { it.copy(map = bitmap, style = product.style, busy = false, cellKilometres = window.sourceCellDegrees * step * 111.0) }
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    /** Whole pixels per cell: as large as keeps the picture under about 6 million pixels (and at most 4). */
    private fun pixelScale(grid: Grid): Int {
        var s = 4
        while (s > 1 && grid.columns.toLong() * s * grid.rows * s > 6_000_000L) s -= 1
        return s
    }

    private fun trimCache() {
        var cells = decoded.values.sumOf { it.window.values.size.toLong() }
        val it = decoded.entries.iterator()
        while (cells > cellBudget && decoded.size > 1 && it.hasNext()) {
            val e = it.next()
            cells -= e.value.window.values.size
            it.remove()
        }
    }

    /** A tap on the picture, given as fractions of its width and height. */
    fun onTap(fx: Float, fy: Float) {
        val (product, values) = shown ?: return
        val v = view ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { update { it.copy(crosshair = null, readout = null) }; return }
        val column = floor(fx * v.cols).toInt().coerceIn(0, v.cols - 1)
        val row = v.rows - 1 - floor(fy * v.rows).toInt().coerceIn(0, v.rows - 1)
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, values, emptyList(), column, row)) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null) }

    // ---- loop ----
    fun togglePlay() { if (_state.value.playing) stopPlaying() else startPlaying() }

    private fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        update { it.copy(playing = false, prefetch = null) }
    }

    private fun startPlaying() {
        val st = _state.value
        if (st.files.size < 2) return
        if (wifiOnlyLoop && isMetered()) {
            update { it.copy(notice = "Loops are set to download on Wi-Fi only. Connect to Wi-Fi or change this in Settings.") }
            return
        }
        playJob = viewModelScope.launch {
            update { it.copy(playing = true, prefetch = 0f) }
            try {
                // download the whole loop first (politely: a few at a time); the HTTP cache then serves each pass
                val gate = Semaphore(3)
                var done = 0
                coroutineScope {
                    st.files.map { f ->
                        async { gate.withPermit { repo.prefetch(f) }; done += 1; update { it.copy(prefetch = done / st.files.size.toFloat()) } }
                    }.awaitAll()
                }
                update { it.copy(prefetch = null) }
                while (true) {
                    val next = (_state.value.index + 1) % _state.value.files.size
                    update { it.copy(index = next) }
                    showCurrent()
                    delay(if (next == _state.value.files.size - 1) 1200 else 150)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(playing = false, prefetch = null, busy = false, error = e.message) }
            }
        }
    }

    private fun isMetered(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }
}
