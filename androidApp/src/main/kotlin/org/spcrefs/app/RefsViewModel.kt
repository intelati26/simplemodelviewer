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
import spcrefs.AlertRule
import spcrefs.decodeRules
import spcrefs.encodeRules
import spcrefs.Place
import spcrefs.PointSeries
import spcrefs.Product
import spcrefs.decodePlaces
import spcrefs.encodePlaces
import spcrefs.placeLabel
import spcrefs.pointSeries
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

data class Settings(val wifiOnlyLoop: Boolean = false, val dataSaver: Boolean = false, val cacheMegabytes: Int = 150)

/** The point-forecast sheet: one place, every forecast time. */
data class StripState(val label: String, val series: PointSeries? = null, val progress: Float = 0f, val error: String? = null)

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
    val settings: Settings = Settings(),
    val places: List<Place> = emptyList(),
    val tapLatLon: Pair<Double, Double>? = null,
    val strip: StripState? = null,
    val newerCycle: Cycle? = null,
    val rules: List<AlertRule> = emptyList(),
    val alertStatus: String = "",
    val notice: String? = null,                  // a short message over a working map (the error field replaces the map)
) {
    val validLabel: String
        get() = times.getOrNull(timeIndex)?.let { formatUtc(it) } ?: ""
    val forecastHour: Int?
        get() = cycle?.let { c -> times.getOrNull(timeIndex)?.let { ((it - c.epochSeconds) / 3600).toInt() } }
}

class RefsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.repo
    private val renderer = MapRenderer(core, MapLines(app, core))

    private var store: CycleStore? = null
    private var values: FloatArray? = null
    private var view: View = View.CONUS
    private var dark = false
    private var frameJob: Job? = null
    private var playJob: Job? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        UiState(
            favourites = prefs.getStringSet("favourites", emptySet()).orEmpty(),
            settings = Settings(prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150)),
            places = decodePlaces(prefs.getString("places", "").orEmpty()),
            rules = decodeRules(prefs.getString("rules", "").orEmpty()),
            alertStatus = prefs.getString("alert_status", "").orEmpty(),
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
            val v = if (sector.name == "CONUS") View.CONUS.decimated(if (_state.value.settings.dataSaver) 2 else 1) else sector.view(core)
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
        val (lat, lon) = core.gridToLatLon(column.toDouble(), row.toDouble())
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, data, it.members, column, row), tapLatLon = lat to lon) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null) }

    // ---- settings ----
    fun setSettings(settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).apply()
        val redraw = settings.dataSaver != _state.value.settings.dataSaver
        update { it.copy(settings = settings) }
        AlertScheduler.sync(getApplication(), _state.value.rules.isNotEmpty(), settings.wifiOnlyLoop)
        if (redraw && values != null) viewModelScope.launch { rerender() }
    }

    // ---- places and the point forecast ----
    fun savePlace(name: String, lat: Double, lon: Double) {
        val places = (_state.value.places.filter { it.name != name } + Place(name.ifBlank { placeLabel(lat, lon) }, lat, lon))
        prefs.edit().putString("places", encodePlaces(places)).apply()
        update { it.copy(places = places) }
    }

    fun deletePlace(place: Place) {
        val places = _state.value.places - place
        prefs.edit().putString("places", encodePlaces(places)).apply()
        update { it.copy(places = places) }
    }

    /** Puts the crosshair on a saved place (switching to CONUS when the current sector does not contain it). */
    fun goToPlace(place: Place) {
        val (c, r) = core.latLonToGrid(place.lat, place.lon)
        var inView = c >= view.col0 && c <= view.col0 + view.cols - 1 && r >= view.row0 && r <= view.row0 + view.rows - 1
        if (!inView && _state.value.sector.name != "CONUS") {
            update { it.copy(sector = Sector.all[0]) }
            viewModelScope.launch { rerender(); tapCell(c, r) }
        } else tapCell(c, r)
    }

    private fun tapCell(c: Double, r: Double) {
        val fx = ((c - view.col0 + 0.5) / view.cols).toFloat()
        val fy = ((view.row0 + view.rows - r - 0.5) / view.rows).toFloat()
        onTap(fx, fy)
    }

    private var stripJob: Job? = null

    fun showPointForecast(label: String, lat: Double, lon: Double) {
        val s = store ?: return
        val st = _state.value
        val product = st.product ?: return
        stripJob?.cancel()
        update { it.copy(strip = StripState(label)) }
        stripJob = viewModelScope.launch {
            try {
                val series = repo.pointSeries(s, product, st.member, lat, lon, core) { p -> update { cur -> cur.copy(strip = cur.strip?.copy(progress = p)) } }
                update { it.copy(strip = StripState(label, series, 1f)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(strip = StripState(label, error = e.message ?: "Could not load the point forecast")) }
            }
        }
    }

    fun closePointForecast() { stripJob?.cancel(); update { it.copy(strip = null) } }

    // ---- threshold alerts ----
    /** A default alert level for the current product: a mid-table value in shown units. */
    fun suggestedThreshold(): Double {
        val style = _state.value.product?.style ?: return 50.0
        return when {
            style.kind == spcrefs.Kind.Paintball -> 3.0
            style.stops.isEmpty() -> 50.0
            else -> style.stops[minOf(2, style.stops.size - 1)].value
        }
    }

    fun addRule(place: Place, threshold: Double) {
        val product = _state.value.product ?: return
        val rule = AlertRule(place.name, place.lat, place.lon, product.id, product.title, threshold)
        val rules = _state.value.rules.filter { it.key != rule.key } + rule
        saveRules(rules)
    }

    fun deleteRule(rule: AlertRule) = saveRules(_state.value.rules - rule)

    private fun saveRules(rules: List<AlertRule>) {
        prefs.edit().putString("rules", encodeRules(rules)).apply()
        update { it.copy(rules = rules) }
        AlertScheduler.sync(getApplication(), rules.isNotEmpty(), _state.value.settings.wifiOnlyLoop)
    }

    fun checkAlertsNow() {
        prefs.edit().putString("alert_status", "Checking...").apply()
        update { it.copy(alertStatus = "Checking...") }
        AlertScheduler.checkNow(getApplication())
        viewModelScope.launch {   // the worker writes its result to the preferences; show it when it lands
            repeat(60) {
                delay(2000)
                val status = prefs.getString("alert_status", "").orEmpty()
                if (status.isNotEmpty() && status != "Checking...") { update { it.copy(alertStatus = status) }; return@launch }
            }
        }
    }

    // ---- a newer run on the server? ----
    private var lastCheck = 0L

    fun checkForNewCycle() {
        val now = System.currentTimeMillis()
        val current = _state.value.cycle ?: return
        if (now - lastCheck < 10 * 60 * 1000) return
        lastCheck = now
        viewModelScope.launch {
            try {
                val newest = repo.findCycles(now / 1000, wanted = 1).firstOrNull() ?: return@launch
                if (newest.epochSeconds > current.epochSeconds) update { it.copy(newerCycle = newest) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* a failed check is not an error to show */ }
        }
    }

    fun loadNewerCycle() {
        val newer = _state.value.newerCycle ?: return
        update { it.copy(newerCycle = null, cycles = (listOf(newer) + it.cycles).distinct()) }
        pickCycle(newer)
    }

    fun reportNotice(text: String) = update { it.copy(notice = text) }

    fun dismissNotice() = update { it.copy(notice = null) }

    fun dismissNewerCycle() = update { it.copy(newerCycle = null) }

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
        if (st.settings.wifiOnlyLoop && isMetered()) {
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

    private fun isMetered(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    /**
     * Renders every forecast time of the current product / sector into an MP4 and returns the file. [onProgress] gets (done, total).
     * Frames come from the HTTP cache when the loop has been played.
     */
    suspend fun buildLoopFile(onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.Default) {
        val s = store ?: return@withContext null
        val st = _state.value
        val product = st.product ?: return@withContext null
        val cycle = st.cycle ?: return@withContext null
        val n = st.times.size
        if (n < 2) return@withContext null
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "spc_refs_loop_${product.id.filter { it.isLetterOrDigit() || it == '_' }.take(40)}_${cycle.label.filter { it.isDigit() }}.mp4")
        var exporter: LoopExporter? = null
        try {
            for (t in 0 until n) {
                val data = repo.readFrame(s, product, t, st.member)
                val style = withAutoStops(product.style, data)
                val v = if (st.sector.name == "CONUS") View.CONUS else st.sector.view(core)
                val map = renderer.render(product, style, data, st.members, v, dark = false)
                val hour = (st.times[t] - cycle.epochSeconds) / 3600
                val header = "Run ${cycle.label}   Valid ${formatUtc(st.times[t])}  (f${hour.toString().padStart(2, '0')})   Data: NOAA/NWS Storm Prediction Center (experimental)"
                var picture = renderer.shareImage(map, product, style, header, st.members)
                if (picture.width > 1280) picture = Bitmap.createScaledBitmap(picture, 1280, picture.height * 1280 / picture.width, true)
                if (exporter == null) exporter = LoopExporter(file, picture.width, picture.height)
                exporter.add(picture, last = t == n - 1)
                onProgress(t + 1, n)
            }
            exporter?.finish()
            file
        } catch (e: Exception) {
            exporter?.abort()
            throw e
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
