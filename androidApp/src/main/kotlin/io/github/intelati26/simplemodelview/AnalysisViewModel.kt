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
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.Place
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.Style
import simplemodelview.VideoSize
import simplemodelview.View
import simplemodelview.decodePlaces
import simplemodelview.formatUtc
import simplemodelview.globeInverse
import simplemodelview.playWhileLoading
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
    val other: AnalysisProduct? = null,             // the product shown before this one (the compare shows it under this one)
    val compare: Boolean = false,
    val lines: Set<String> = emptySet(),            // the surface lines drawn over any product: "t", "td", "rh"
    val activePane: Int = 0,                        // the compare pane the product controls change: 0 above, 1 below (a tap on a pane selects it)
    val paneB: List<Bitmap>? = null,                // that product's own field and, over it, the shared lines
    val titleB: String? = null,
    val styleB: Style? = null,
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
    private val globe = GlobeDriver(viewModelScope, { renderGlobeNow(it) }) { turn -> update { it.copy(cam = turn(it.cam)) } }
    private val globeW get() = globe.w
    private val globeH get() = globe.h

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
            lines = prefs.getString("analysis_lines", "").orEmpty().split(',').filter { it in LINE_KINDS.map { k -> k.key } }.toSet(),
            settings = readSettings(),
        )
    )
    val state: StateFlow<AnalysisState> = _state

    // the saved places are one list for every page: this page's copy follows it
    init { viewModelScope.launch { AppContainer.places.places.collect { p -> update { it.copy(places = p) } } } }

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
        all.getOrNull(all.indexOf(activeProduct()) + delta)?.let(::pickProduct)
    }

    /** The product the controls are working on: the one above, or in a compare the one of the selected pane. */
    fun activeProduct(st: AnalysisState = _state.value): AnalysisProduct = if (st.compare && st.activePane == 1) st.other ?: st.product else st.product

    fun selectPane(i: Int) { if (_state.value.activePane != i) update { it.copy(activePane = i) } }

    /** In a compare: the two panes change places (the one above becomes the one below). */
    fun swapPanes() {
        val st = _state.value
        val o = st.other ?: return
        prefs.edit().putString("analysis_product", o.id).apply()
        update { it.copy(product = o, other = st.product, paneB = null, titleB = null, styleB = null) }
        showFrame()
    }

    fun pickProduct(picked: AnalysisProduct) {
        val cur = _state.value
        if (cur.compare && cur.activePane == 1 && cur.other != null) {
            update { it.copy(other = picked, paneB = null, titleB = null, styleB = null) }       // only the pane below changes
            showFrame()
            return
        }
        prefs.edit().putString("analysis_product", picked.id).apply()
        update { st -> st.copy(product = picked, other = if (st.compare) st.other else st.product.takeIf { it != picked } ?: st.other) }
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

    /** Shows the previous product under this one (or back to one picture). */
    fun setCompare(on: Boolean) {
        if (_state.value.compare == on) return
        update { it.copy(compare = on, paneB = null, titleB = null, styleB = null, activePane = 0) }
        if (on && _state.value.globe) { showMapHere(); return }      // the globe has no compare: on to the flat map of the region it is looking at
        if (on) showFrame()
    }

    /** Draws or stops drawing one surface line kind ("t", "td", "rh") over whatever product is shown. */
    fun setLine(key: String, on: Boolean) {
        val next = _state.value.lines.let { if (on) it + key else it - key }
        prefs.edit().putString("analysis_lines", next.joinToString(",")).apply()
        update { it.copy(lines = next) }
        showFrame()
    }

    /** The line layers that are on, from the same frame's other products (the lines of the shown product itself are its own contours), when they share its grid. */
    private suspend fun lineLayersFor(frame: AnalysisFrame, shownId: String, grid: Grid, keys: Set<String> = _state.value.lines): List<FieldLines> {
        return LINE_KINDS.filter { it.key in keys && it.productId != shownId }.mapNotNull { k ->
            val p = AnalysisCatalog.all.firstOrNull { it.id == k.productId } ?: return@mapNotNull null
            val f = try { field(frame, p) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return@mapNotNull null
            if (f.first != grid) null else FieldLines(f.second, grid, p.style, k.spec, "an|${frame.valid}|${k.productId}|${grid.id}")
        }
    }

    private var shownLines: List<FieldLines> = emptyList()

    /** The compare's second picture: the other product of the same frame, as its own field only, with the lines over it once. */
    private suspend fun computePaneB(product: Product, values: FloatArray, v: View, frame: AnalysisFrame) {
        val st = _state.value
        val b = st.other
        if (!st.compare || st.playing || b == null) { if (st.paneB != null) update { it.copy(paneB = null, titleB = null, styleB = null) }; return }
        try {
            val fb = field(frame, b)
            if (fb == null) { update { it.copy(paneB = null, notice = "No ${b.title} analysis for this time.") }; return }
            val (gridB, valuesB, _) = fb
            val syn = synthetic(b, gridB)
            val settings = st.settings
            val (over, fld) = withContext(Dispatchers.Default) {
                val bv = if (gridB == product.grid) v else viewFor(st.sector, gridB)
                renderer.render(product, product.style, values, emptyList(), v, dark, counties = settings.counties, fieldLines = shownLines, part = RenderPart.Over) to
                    renderer.render(syn, syn.style, valuesB, emptyList(), bv, dark, contours = settings.contours, barbDensity = settings.barbDensity, part = RenderPart.Field)
            }
            update { it.copy(paneB = listOf(fld, over), titleB = b.title, styleB = syn.style) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(paneB = null, notice = e.message) } }
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
        val lineLayers = lineLayersFor(frame, st.product.id, grid)
        shownLines = lineLayers
        val bitmap = withContext(Dispatchers.Default) {
            renderer.render(product, product.style, values, emptyList(), v, dark, contours = _state.value.settings.contours, counties = _state.value.settings.counties, barbDensity = _state.value.settings.barbDensity, fieldLines = lineLayers)
        }
        view = v
        update { it.copy(map = bitmap, busy = false, detail = null, focus = handoff.takeFocus(v)) }
        computePaneB(product, values, v, frame)
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
        val cam = handoff.switching(on, view, viewport, globeW, globeH)
        update { it.copy(globe = on, cam = cam ?: it.cam, crosshair = null, readout = null, tapLatLon = null) }
        showFrame()
    }

    /** Back to the flat map from the globe: the sector that holds what the globe shows, zoomed to it. */
    fun showMapHere() { handoff.leaving(_state.value.cam, globeW, globeH)?.let { pickSector(it) } }

    fun focusApplied() = update { it.copy(focus = null) }

    fun setGlobeSize(w: Int, h: Int) = globe.setSize(w, h, onFirst = { w, h -> update { it.copy(cam = handoff.enter?.let { b -> handoff.fit(b, w, h) } ?: it.cam.copy(radius = 0.49 * minOf(w, h))) } }, after = { handoff.enter = null }, ready = { _state.value.globe && shown != null })

    fun globeGesture(dx: Float, dy: Float, zoom: Float) = globe.gesture(dx, dy, zoom)

    private fun requestGlobe(coarse: Boolean) = globe.request(coarse)

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
        viewport = clampedViewport(left, top, right, bottom)
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
                    renderer.render(product, product.style, values, emptyList(), window.view, dark, contours = settings.contours, counties = settings.counties, barbDensity = settings.barbDensity, fieldLines = shownLines)
                }
                if (view === base && shown?.second === values) update { it.copy(detail = DetailLayer(bitmap, window.left, window.top, window.right, window.bottom, 2.5)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    // ---- places ----


    /** The shown field's value at a place, as a line of text, without moving the map or the crosshair. */
    fun readAt(lat: Double, lon: Double): String {
        val (product, values) = shown ?: return "Nothing is shown yet"
        return readAtPlace(core, product, values, emptyList(), lat, lon)
    }


    fun goToPlace(place: Place, follow: Boolean = false) = viewModelScope.goToPlaceOnPage(
        core, place, follow, _state.value.globe, _state.value.sector, Sector.all[0],
        turnGlobeTo = { update { it.copy(cam = it.cam.copy(lat = place.lat, lon = place.lon), tapLatLon = place.lat to place.lon) }; requestGlobe(coarse = false) },
        openSector = { update { it.copy(sector = Sector.all[0]) }; try { showCurrent() } catch (e: Exception) { if (e is CancellationException) throw e; update { it.copy(busy = false, error = e.message) }; throw e } },
        view = { view }, tap = ::onTap,
    )

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
        if (wifiOnlyLoop && AppContainer.isMetered()) { update { it.copy(notice = "Loops are set to download on Wi-Fi only. Connect to Wi-Fi or change this in Settings.") }; return }
        playJob = viewModelScope.launch {
            update { it.copy(playing = true, prefetch = 0f) }
            val frames = st.frames
            val window = if (AppContainer.loopFromShown.value) st.index until frames.size else frames.indices
            try {
                playWhileLoading(window, frames.size, { _state.value.index },
                    fetch = { i ->
                        val ok = repo.prefetch(frames[i], st.product.needs, now())
                        if (!ok) update { it.copy(missing = it.missing + frames[i].valid) }
                        ok
                    },
                    onProgress = { p -> update { it.copy(prefetch = p) } },
                    show = { next -> update { it.copy(index = next) }; showCurrent() },
                    dwell = { LOOP_MILLIS })
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(playing = false, prefetch = null, busy = false, error = e.message) }
            }
        }
    }

    // ---- share / export ----
    private fun exportCtx() = ExportCtx(_state.value, view, viewport.copyOf(), globeW, globeH)

    private fun areaView(area: ShareArea, grid: Grid, x: ExportCtx<AnalysisState> = exportCtx()): View =
        exportWindow(core, area, grid, x.view ?: viewFor(x.st.sector, grid), x.viewport)

    private fun header(frame: AnalysisFrame, source: String): String =
        "Valid ${formatUtc(frame.valid)}   $source   Data: NOAA/NWS ${if (source == "URMA") "URMA" else "RTMA"} surface analysis (experimental viewer)"

    private fun fileName(product: AnalysisProduct, frame: AnalysisFrame, extension: String): String {
        val c = simplemodelview.civilFromEpoch(frame.valid)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "analysis_${product.id.removeSuffix("_anl")}_${c[0]}${p(c[1])}${p(c[2])}${p(c[3])}${p(c[4])}.$extension"
    }

    /** The picture of [frame] for an export of [area], or null when the frame has no file. */
    private val exporter = Exporter(renderer) { exportDir(getApplication()) }

    /** What an export of [frames] is, taken from [x]: the extractor of each pane (the analysis of the frame; in a compare the other product), the lines laid over it, the area's window. The globe is drawn here. */
    private fun exportJob(area: ShareArea, frames: List<AnalysisFrame>, x: ExportCtx<AnalysisState>): ExportJob {
        val st = x.st
        val settings = st.settings.forExport()
        suspend fun field(product: AnalysisProduct, frame: AnalysisFrame, lines: Boolean): PaneField? {
            val (grid, values, _) = field(frame, product, cache = false) ?: return null
            val extras = if (lines) lineLayersFor(frame, product.id, grid, st.lines) else emptyList()
            return PaneField(grid, values, synthetic(product, grid), extras = { RenderExtras(fieldLines = extras) })
        }
        val other = st.other?.takeIf { st.compare && area != ShareArea.Globe }
        val panes = buildList {
            add(ExportPane(st.product.title) { i -> field(st.product, frames[i], lines = true) })
            if (other != null) add(ExportPane(other.title) { i -> field(other, frames[i], lines = false) })
        }
        val globe: (suspend (Int, Int) -> Pair<Bitmap, PaneField>)? = if (area == ShareArea.Globe && x.globeW > 0) { i, side ->
            val pf = field(st.product, frames[i], lines = false) ?: throw IllegalStateException("no ${st.product.title} for this time")
            val cam = st.cam.copy(radius = st.cam.radius * side / minOf(x.globeW, x.globeH))
            val bitmap = withContext(Dispatchers.Default) {
                globeRenderer.render(pf.grid, pf.values, pf.style, emptyList(), cam, side, side, 1, dark = false, product = pf.product, contours = st.settings.contours, barbDensity = settings.barbDensity, counties = settings.counties)
            }
            bitmap to pf
        } else null
        return ExportJob(
            frames.map { f -> ExportFrame({ header(f, sources[f.valid] ?: "") }) }, panes, { grid -> areaView(area, grid, x) }, Drawing(st.settings.contours, settings.counties, settings.barbDensity),
            { ext -> fileName(st.product, frames[0], ext) }, LOOP_MILLIS.toInt(), globe,
        )
    }

    suspend fun buildShareFile(area: ShareArea = ShareArea.Whole, size: VideoSize? = null): File? {
        val x = exportCtx()
        val frame = x.st.frame ?: return null
        return exporter.picture(exportJob(area, listOf(frame), x), size)
    }

    /** The frames of the loop as an MP4 (a frame with no file is left out). [onProgress] gets (done, total). */
    suspend fun buildLoopFile(area: ShareArea, size: VideoSize, onProgress: (Int, Int) -> Unit): File? {
        val x = exportCtx()
        val st = x.st
        val window = if (AppContainer.loopFromShown.value) st.index until st.frames.size else st.frames.indices
        if (window.count() < 2) return null
        return exporter.loop(exportJob(area, window.map { st.frames[it] }, x), size, onProgress)
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

private class LineKind(val key: String, val productId: String, val spec: simplemodelview.ContourSpec)

private val LINE_KINDS = listOf(
    LineKind("t", "t2m_anl", simplemodelview.Underlay.temperatureSpec(false)),
    LineKind("td", "td2m_anl", simplemodelview.Underlay.dewpointSpec(false)),
    LineKind("rh", "rh_anl", simplemodelview.Underlay.humiditySpec()),
)
