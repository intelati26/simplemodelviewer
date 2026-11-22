package io.github.intelati26.simplemodelview

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import simplemodelview.AnalysisBuckets
import simplemodelview.AnalysisCatalog
import simplemodelview.AnalysisFrame
import simplemodelview.AnalysisProduct
import simplemodelview.AnalysisSource
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.Place
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.Style
import simplemodelview.VideoSize
import simplemodelview.View
import simplemodelview.decodePlaces
import simplemodelview.encodePlaces
import simplemodelview.formatUtc
import simplemodelview.globeForward
import simplemodelview.globeInverse
import simplemodelview.nextPlayable
import simplemodelview.placeLabel
import simplemodelview.readout
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/** The surface analysis page: a field, a sector, the frames (the last six hours, or an hourly span of the past) and the one shown. */
data class AnalysisState(
    val busy: Boolean = true,
    val loadStatus: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val product: AnalysisProduct = AnalysisCatalog.default,
    val sector: Sector = Sector.all[0],
    val history: Pair<Long, Long>? = null,          // an hourly span of the past (epoch seconds); null: the last six hours, every 15 minutes
    val frames: List<AnalysisFrame> = emptyList(),
    val index: Int = 0,
    val shownSource: String? = null,                 // what the picture was read from ("URMA", "RTMA", "RTMA-RU")
    val missing: Set<Long> = emptySet(),             // frames with no file (valid times)
    val map: Bitmap? = null,
    val detail: DetailLayer? = null,
    val style: Style? = null,
    val crosshair: Pair<Float, Float>? = null,
    val tapLatLon: Pair<Double, Double>? = null,
    val readout: String? = null,
    val playing: Boolean = false,
    val prefetch: Float? = null,
    val places: List<Place> = emptyList(),
    val settings: Settings = Settings(),
    val globe: Boolean = false,
    val cam: GlobeCamera = GlobeCamera(38.0, -97.0, 380.0),
    val focus: simplemodelview.MapFocus? = null,      // the part of the next map picture to zoom to (set on leaving the globe), cleared once applied
) {
    val frame: AnalysisFrame? get() = frames.getOrNull(index)
}

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val handoff = GlobeHandoff(core)
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.analysis
    private val renderer = MapRenderer(core, AppContainer.lines)
    private val globeRenderer = GlobeRenderer(core, AppContainer.lines)
    private var globeW = 0
    private var globeH = 0

    private var dark = false
    private var frameJob: Job? = null
    private var playJob: Job? = null
    private var shown: Pair<Product, FloatArray>? = null
    private var view: View? = null
    private var viewport = floatArrayOf(0f, 0f, 1f, 1f)
    private var lastLoad = 0L

    // made fields (shown units), newest use last; one is 3.7 million cells (15 MB)
    private val made = LinkedHashMap<String, Pair<Grid, FloatArray>>(8, 0.75f, true)
    private val cellBudget = 12_000_000L

    private val _state = MutableStateFlow(
        AnalysisState(
            product = AnalysisCatalog.byId(prefs.getString("analysis_product", null)) ?: AnalysisCatalog.default,
            sector = Sector.all.firstOrNull { it.name == prefs.getString("analysis_sector", null) } ?: Sector.all[0],
            places = decodePlaces(prefs.getString("places", "").orEmpty()),
            settings = readSettings(),
        )
    )
    val state: StateFlow<AnalysisState> = _state

    private inline fun update(f: (AnalysisState) -> AnalysisState) { _state.value = f(_state.value) }

    private fun readSettings() = Settings(
        prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(), prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false),
    ).withDisplayPrefs(prefs)

    private val dataSaver get() = prefs.getBoolean("data_saver", false)
    private val wifiOnlyLoop get() = prefs.getBoolean("wifi_only", false)
    private fun now() = System.currentTimeMillis() / 1000

    init { load() }

    fun reloadFromPrefs() = update { it.copy(places = decodePlaces(prefs.getString("places", "").orEmpty()), settings = readSettings()) }

    /** (Re)makes the frames: the last six hours from the newest rapid-update analysis, or the hourly span chosen; shows the newest (or the one nearest the time shown before). */
    fun load() {
        stopPlaying()
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try {
                val now = now()
                val keep = _state.value.frame?.valid
                val history = _state.value.history
                val frames = if (history != null) AnalysisBuckets.historyFrames(history.first, history.second, now) else {
                    val newest = repo.newestQuarterHour(now) ?: (now - now % 3600 - 3600)
                    AnalysisBuckets.recentFrames(newest)
                }
                if (frames.isEmpty()) throw IllegalStateException("No analyses in that span (RTMA starts 2013-03-19).")
                val index = if (keep != null && history == null) frames.size - 1 else keep?.let { k -> frames.indices.minByOrNull { kotlin.math.abs(frames[it].valid - k) } } ?: frames.size - 1
                lastLoad = System.currentTimeMillis()
                update { it.copy(frames = frames, index = index, missing = emptySet()) }
                showCurrent()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message ?: "Something went wrong") }
            }
        }
    }

    /** Coming back after a while: the last six hours move on. */
    fun refreshIfStale() {
        val st = _state.value
        if (System.currentTimeMillis() - lastLoad < 5 * 60 * 1000 || st.playing || st.busy || st.history != null) return
        load()
    }

    /** An hourly span of the past ([start] to [end], epoch seconds); null: back to the last six hours. */
    fun setHistory(range: Pair<Long, Long>?) {
        update { it.copy(history = range, crosshair = null, readout = null, tapLatLon = null) }
        load()
    }

    fun stepProduct(delta: Int) {
        val all = AnalysisCatalog.all
        all.getOrNull(all.indexOf(_state.value.product) + delta)?.let(::pickProduct)
    }

    fun pickProduct(picked: AnalysisProduct) {
        prefs.edit().putString("analysis_product", picked.id).apply()
        update { it.copy(product = picked) }
        showFrame()
    }

    fun pickSector(sector: Sector) {
        prefs.edit().putString("analysis_sector", sector.name).apply()
        stopPlaying()
        update { it.copy(sector = sector, globe = false, crosshair = null, readout = null, tapLatLon = null) }
        showFrame()
    }

    fun pickIndex(index: Int) {
        update { it.copy(index = index.coerceIn(0, maxOf(0, it.frames.size - 1))) }
        showFrame()
    }

    fun setDark(isDark: Boolean) {
        if (dark == isDark) return
        dark = isDark
        if (shown != null) showFrame()
    }

    fun dismissNotice() = update { it.copy(notice = null) }
    fun reportNotice(text: String) = update { it.copy(notice = text) }

    private fun showFrame() {
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, error = e.message) } }
        }
    }

    /** The field of [frame] for [product] in shown units, with what it was read from; null when no file of the frame is there. */
    private suspend fun field(frame: AnalysisFrame, product: AnalysisProduct, cache: Boolean = true): Triple<Grid, FloatArray, String>? {
        val key = "${frame.valid}|${product.id}"
        if (cache) made[key]?.let { (g, v) -> return Triple(g, v, sources[frame.valid] ?: "") }
        val read = withContext(Dispatchers.IO) {
            Timings.timed("data") { repo.load(frame, product.needs, now()) { text -> update { it.copy(loadStatus = text) } } }
        }
        update { it.copy(loadStatus = null) }
        if (read == null) return null
        val values = withContext(Dispatchers.Default) { product.make(read.fields.map { it.values }) }
        val grid = read.fields.first().grid
        sources[frame.valid] = read.source.label
        if (cache) { made[key] = grid to values; trim() }
        return Triple(grid, values, read.source.label)
    }

    private val sources = HashMap<Long, String>()

    private fun trim() {
        var cells = made.values.sumOf { it.second.size.toLong() }
        val it = made.entries.iterator()
        while (cells > cellBudget && made.size > 1 && it.hasNext()) { cells -= it.next().value.second.size; it.remove() }
    }

    private fun viewFor(sector: Sector, grid: Grid): View {
        val base = sector.view(core, grid, 1)
        var scale = 3
        while (scale > 1 && base.cols.toLong() * scale * base.rows * scale > 6_000_000L) scale -= 1
        val v = base.copy(scale = scale)
        return if (dataSaver) v.decimated(2) else v
    }

    private fun synthetic(product: AnalysisProduct, grid: Grid) = Product(product.id, product.title, "Analysis", product.style, hasMembers = false, timeDimension = null, grid = grid)

    private suspend fun showCurrent() {
        val st = _state.value
        val frame = st.frame ?: return
        update { it.copy(busy = true, error = null) }
        val f = field(frame, st.product)
        if (f == null) {
            update { it.copy(busy = false, missing = it.missing + frame.valid, notice = "No analysis file for ${formatUtc(frame.valid)} (yet)." ) }
            return
        }
        val (grid, values, source) = f
        val product = synthetic(st.product, grid)
        shown = product to values
        update { it.copy(shownSource = source, style = product.style) }
        if (st.globe) {
            if (globeW > 0) renderGlobeNow(1) else update { it.copy(busy = false) }
            return
        }
        val v = viewFor(st.sector, grid)
        val bitmap = withContext(Dispatchers.Default) {
            renderer.render(product, product.style, values, emptyList(), v, dark, contours = _state.value.settings.contours, counties = _state.value.settings.counties, barbDensity = _state.value.settings.barbDensity)
        }
        view = v
        update { it.copy(map = bitmap, busy = false, detail = null, focus = handoff.takeFocus(v)) }
        scheduleDetail()
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    // ---- taps ----
    fun onTap(fx: Float, fy: Float) {
        val (product, values) = shown ?: return
        val v = view ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { clearTap(); return }
        val column = v.col0 + floor(fx * v.cols).toInt().coerceIn(0, v.cols - 1)
        val row = v.row0 + v.rows - 1 - floor(fy * v.rows).toInt().coerceIn(0, v.rows - 1)
        val (lat, lon) = core.gridToLatLon(product.grid, column.toDouble(), row.toDouble())
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, values, emptyList(), column, row), tapLatLon = lat to lon) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null) }

    // ---- the globe ----
    fun onGlobeTap(px: Float, py: Float) {
        val (product, values) = shown ?: return
        val ll = globeInverse(_state.value.cam, globeW, globeH, px.toDouble(), py.toDouble()) ?: run { clearTap(); return }
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, ll[0], ll[1])
        val column = Math.round(c).toInt()
        val row = Math.round(r).toInt()
        val text = if (column !in 0 until grid.columns || row !in 0 until grid.rows) "${placeLabel(ll[0], ll[1])}   outside the analysis area" else readout(core, product, values, emptyList(), column, row)
        update { it.copy(readout = text, tapLatLon = ll[0] to ll[1]) }
    }

    fun setGlobe(on: Boolean) {
        if (_state.value.globe == on) return
        stopPlaying()
        val enterBox = if (on) handoff.boxOf(view, viewport) else null
        if (on) handoff.cancelLeave()
        handoff.enter = if (globeW == 0) enterBox else null
        update { it.copy(globe = on, cam = if (enterBox != null && globeW > 0) handoff.fit(enterBox, globeW, globeH) else it.cam, crosshair = null, readout = null, tapLatLon = null) }
        showFrame()
    }

    /** Back to the flat map from the globe: the sector that holds what the globe shows, zoomed to it. */
    fun showMapHere() {
        val cam = _state.value.cam
        if (globeW == 0) return
        handoff.leaveFrom(cam, globeW, globeH)
        pickSector(simplemodelview.sectorForGlobe(cam, globeW, globeH))
    }

    fun focusApplied() = update { it.copy(focus = null) }

    fun setGlobeSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == globeW && h == globeH)) return
        val first = globeW == 0
        globeW = w; globeH = h
        if (first) update { it.copy(cam = handoff.enter?.let { b -> handoff.fit(b, w, h) } ?: it.cam.copy(radius = 0.49 * minOf(w, h))) }
        handoff.enter = null
        if (_state.value.globe && shown != null) requestGlobe(coarse = false)
    }

    private var settleJob: Job? = null
    private var globeBusy = false
    private var globeDirty = false
    private var globeCoarse = false

    fun globeGesture(dx: Float, dy: Float, zoom: Float) {
        val minR = 0.3 * minOf(globeW, globeH)
        update { it.copy(cam = it.cam.dragged(dx.toDouble(), dy.toDouble()).zoomed(zoom.toDouble(), minR, minR * 60)) }
        requestGlobe(coarse = true)
        settleJob?.cancel()
        settleJob = viewModelScope.launch { delay(180); requestGlobe(coarse = false) }
    }

    private fun requestGlobe(coarse: Boolean) {
        globeDirty = true
        globeCoarse = coarse
        if (globeBusy) return
        globeBusy = true
        viewModelScope.launch {
            try { while (globeDirty) { globeDirty = false; renderGlobeNow(if (globeCoarse) 4 else 1) } } finally { globeBusy = false }
        }
    }

    private suspend fun renderGlobeNow(stride: Int) {
        val (product, values) = shown ?: return
        if (globeW == 0 || values.size < product.grid.cells) return
        val st = _state.value
        val bitmap = withContext(Dispatchers.Default) {
            globeRenderer.render(product.grid, values, product.style, emptyList(), st.cam, globeW, globeH, stride, dark, product, st.settings.contours, st.settings.barbDensity, counties = st.settings.counties)
        }
        update { it.copy(map = bitmap, busy = false) }
    }

    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewport = floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
        scheduleDetail()
    }

    private var detailJob: Job? = null

    private fun scheduleDetail() {
        detailJob?.cancel()
        val st = _state.value
        val (product, values) = shown ?: return
        val base = view
        val (l, t, r, b) = viewport.toList()
        val window = if (base == null || st.globe || st.playing) null else detailWindow(base, l, t, r, b)
        if (window == null) { if (st.detail != null) update { it.copy(detail = null) }; return }
        detailJob = viewModelScope.launch {
            delay(400)
            try {
                val settings = _state.value.settings
                val bitmap = withContext(Dispatchers.Default) {
                    renderer.render(product, product.style, values, emptyList(), window.view, dark, contours = settings.contours, counties = settings.counties, barbDensity = settings.barbDensity)
                }
                if (view === base && shown?.second === values) update { it.copy(detail = DetailLayer(bitmap, window.left, window.top, window.right, window.bottom, 2.5)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    // ---- places ----
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

    fun goToPlace(place: Place, follow: Boolean = false) {
        val st = _state.value
        if (st.globe) {
            update { it.copy(cam = it.cam.copy(lat = place.lat, lon = place.lon), tapLatLon = place.lat to place.lon) }
            requestGlobe(coarse = false)
            return
        }
        val box = st.sector
        val inSector = place.lon in box.west..box.east && place.lat in box.south..box.north
        if (follow && !inSector) return
        viewModelScope.launch {
            if (!inSector && st.sector.name != Sector.all[0].name) {
                update { it.copy(sector = Sector.all[0]) }
                try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, error = e.message) }; return@launch }
            }
            val v = view ?: return@launch
            val (c, r) = core.latLonToGrid(v.grid, place.lat, place.lon)
            onTap(((c + 0.5 - v.col0) / v.cols).toFloat(), ((v.row0 + v.rows - r - 0.5) / v.rows).toFloat())
        }
    }

    fun setSettings(settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).putBoolean("contours", settings.contours).putBoolean("minimal_ui", settings.minimalUi).putDisplay(settings).apply()
        val redraw = settings.dataSaver != _state.value.settings.dataSaver || settings.contours != _state.value.settings.contours || settings.displayDiffers(_state.value.settings)
        update { it.copy(settings = settings) }
        if (redraw && shown != null) showFrame()
    }

    // ---- loop ----
    fun togglePlay() { if (_state.value.playing) stopPlaying() else startPlaying() }

    private fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        update { it.copy(playing = false, prefetch = null) }
    }

    /** Plays the frames from [AppContainer.loopFromShown]'s start; the frames are downloaded in order, a few at a time, while those that have arrived play. */
    private fun startPlaying() {
        val st = _state.value
        if (st.frames.size < 2) return
        if (wifiOnlyLoop && isMetered()) { update { it.copy(notice = "Loops are set to download on Wi-Fi only. Connect to Wi-Fi or change this in Settings.") }; return }
        playJob = viewModelScope.launch {
            update { it.copy(playing = true, prefetch = 0f) }
            val frames = st.frames
            val window = if (AppContainer.loopFromShown.value) st.index until frames.size else frames.indices
            val count = window.count()
            val ready = BooleanArray(frames.size)
            var finished = false
            try {
                launch {
                    val gate = Semaphore(3)
                    var done = 0
                    coroutineScope {
                        for (i in window) launch {
                            gate.withPermit {
                                val ok = try { repo.prefetch(frames[i], st.product.needs, now()) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                                if (ok) ready[i] = true else update { it.copy(missing = it.missing + frames[i].valid) }
                            }
                            done += 1
                            update { it.copy(prefetch = if (done == count) null else done / count.toFloat()) }
                        }
                    }
                    finished = true
                }
                if (_state.value.index in window) ready[_state.value.index] = true
                while (true) {
                    val at = _state.value.index
                    val next = nextPlayable(at, ready)
                    if (next == null || next == at) {
                        if (finished && ready.none { it }) throw IllegalStateException("The loop's frames could not be downloaded.")
                        delay(150)
                        continue
                    }
                    update { it.copy(index = next) }
                    showCurrent()
                    delay(if (next < at) (if (finished) 1200L else 500L) else LOOP_MILLIS)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(playing = false, prefetch = null, busy = false, error = e.message) }
            }
        }
    }

    private fun isMetered(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    // ---- share / export ----
    var videoSize: VideoSize
        get() = VideoSize.byName(prefs.getString("video_size", null))
        set(value) { prefs.edit().putString("video_size", value.name).apply() }

    private fun exportScale(cols: Int, rows: Int): Int {
        var s = ceil(1800.0 / cols).toInt().coerceIn(1, 6)
        while (s > 1 && cols.toLong() * s * rows * s > 8_000_000L) s -= 1
        return s
    }

    private fun areaView(area: ShareArea, grid: Grid): View {
        val base = view ?: viewFor(_state.value.sector, grid)
        val window = when (area) {
            is ShareArea.Meso -> area.sector.view(core, grid, 1)
            ShareArea.Screen -> {
                val (l, t, r, b) = viewport.toList()
                val col0 = (base.col0 + floor(l * base.cols).toInt()).coerceIn(0, grid.columns - 2)
                val row0 = (base.row0 + floor((1f - b) * base.rows).toInt()).coerceIn(0, grid.rows - 2)
                val col1 = (base.col0 + ceil(r * base.cols).toInt() - 1).coerceIn(col0 + 1, grid.columns - 1)
                val row1 = (base.row0 + ceil((1f - t) * base.rows).toInt() - 1).coerceIn(row0 + 1, grid.rows - 1)
                View("Screen area", col0, row0, col1 - col0 + 1, row1 - row0 + 1, 1, 1, grid)
            }
            else -> base.copy(scale = 1, step = 1)
        }
        return window.copy(scale = exportScale(window.cols, window.rows), step = 1)
    }

    private fun header(frame: AnalysisFrame, source: String): String =
        "Valid ${formatUtc(frame.valid)}   $source   Data: NOAA/NWS ${if (source == "URMA") "URMA" else "RTMA"} surface analysis (experimental viewer)"

    private fun fileName(product: AnalysisProduct, frame: AnalysisFrame, extension: String): String {
        val c = simplemodelview.civilFromEpoch(frame.valid)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "analysis_${product.id.removeSuffix("_anl")}_${c[0]}${p(c[1])}${p(c[2])}${p(c[3])}${p(c[4])}.$extension"
    }

    private fun sharedDir() = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    /** The picture of [frame] for an export of [area], or null when the frame has no file. */
    private suspend fun areaImage(area: ShareArea, frame: AnalysisFrame, side: Int = GLOBE_EXPORT_SIDE): Triple<Bitmap, Product, String>? {
        val st = _state.value
        val (grid, values, source) = field(frame, st.product, cache = false) ?: return null
        val product = synthetic(st.product, grid)
        if (area == ShareArea.Globe && globeW > 0) {
            val cam = st.cam.copy(radius = st.cam.radius * side / minOf(globeW, globeH))
            val bitmap = withContext(Dispatchers.Default) {
                globeRenderer.render(grid, values, product.style, emptyList(), cam, side, side, 1, dark = false, product = product, contours = st.settings.contours, barbDensity = st.settings.forExport().barbDensity, counties = st.settings.forExport().counties)
            }
            return Triple(bitmap, product, source)
        }
        val box = areaView(area, grid)
        val map = withContext(Dispatchers.Default) {
            renderer.render(product, product.style, values, emptyList(), box, dark = false, contours = st.settings.contours, counties = st.settings.forExport().counties, barbDensity = st.settings.forExport().barbDensity)
        }
        return Triple(map, product, source)
    }

    suspend fun buildShareFile(area: ShareArea = ShareArea.Whole): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val frame = st.frame ?: return@withContext null
        val (map, product, source) = areaImage(area, frame) ?: return@withContext null
        val image = renderer.shareImage(map, product, product.style, header(frame, source), emptyList())
        val out = File(sharedDir(), fileName(st.product, frame, "png"))
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out
    }

    /** The frames of the loop as an MP4 (a frame with no file is left out). [onProgress] gets (done, total). */
    suspend fun buildLoopFile(area: ShareArea, size: VideoSize, onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val window = if (AppContainer.loopFromShown.value) st.index until st.frames.size else st.frames.indices
        val n = window.count()
        if (n < 2) return@withContext null
        val out = File(sharedDir(), fileName(st.product, st.frames[window.first], "mp4"))
        var exporter: LoopExporter? = null
        try {
            for ((k, i) in window.withIndex()) {
                val frame = st.frames[i]
                val made = areaImage(area, frame, GLOBE_LOOP_SIDE)
                if (made != null) {
                    val (map, product, source) = made
                    var picture = renderer.shareImage(map, product, product.style, header(frame, source), emptyList())
                    val (sw, sh) = size.scaled(picture.width, picture.height)
                    if (sw != picture.width) picture = Bitmap.createScaledBitmap(picture, sw, sh, true)
                    if (exporter == null) exporter = LoopExporter(out, picture.width, picture.height, size.bitrate(picture.width, picture.height, (1000 / LOOP_MILLIS).toInt()), frameMillis = LOOP_MILLIS.toInt())
                    exporter.add(picture, last = k == n - 1, hold = 1)
                }
                onProgress(k + 1, n)
            }
            exporter?.finish()
            if (exporter == null) null else out
        } catch (e: Exception) {
            exporter?.abort()
            throw e
        }
    }

    fun estimateLoopMegabytes(area: ShareArea, size: VideoSize): Double {
        val grid = shown?.first?.grid ?: return 0.0
        val st = _state.value
        val frames = (if (AppContainer.loopFromShown.value) st.index until st.frames.size else st.frames.indices).count()
        if (area == ShareArea.Globe) { val w = GLOBE_LOOP_SIDE; return VideoSize.estimateMegabytes(size, w, w + w / 6, frames) }
        val box = areaView(area, grid)
        return VideoSize.estimateMegabytes(size, box.pixelWidth, box.pixelHeight + box.pixelWidth / 6, frames)
    }

    private companion object {
        const val LOOP_MILLIS = 250L
    }
}
