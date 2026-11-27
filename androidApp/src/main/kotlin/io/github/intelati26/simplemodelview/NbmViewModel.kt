package io.github.intelati26.simplemodelview

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import simplemodelview.BackupPlace
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.GridField
import simplemodelview.NbmCatalog
import simplemodelview.NbmCycle
import simplemodelview.NbmFile
import simplemodelview.NbmProduct
import simplemodelview.Place
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.SettingsBackup
import simplemodelview.Style
import simplemodelview.VideoSize
import simplemodelview.View
import simplemodelview.decodePlaces
import simplemodelview.globeForward
import simplemodelview.globeInverse
import simplemodelview.formatUtc
import simplemodelview.isValidEmail
import simplemodelview.nbmDwellMillis
import simplemodelview.nbmLoopWindow
import simplemodelview.nbmStepHours
import simplemodelview.nextPlayable
import simplemodelview.placeLabel
import simplemodelview.readout
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/** The National Blend of Models page: a field, a run, a sector, the run's forecast frames and the one being shown. */
data class NbmState(
    val detail: DetailLayer? = null,           // a finer picture of the zoomed-in part, drawn after the zoom settles
    val busy: Boolean = true,
    val loadStatus: String? = null,                   // what a slow load (a GRIB frame) is doing: reading the index, downloading, decoding
    val error: String? = null,
    val notice: String? = null,
    val product: NbmProduct = NbmCatalog.default,
    val other: NbmProduct? = null,                    // the product shown before this one (the compare shows it under this one)
    val compare: Boolean = false,
    val activePane: Int = 0,                          // the compare pane the product controls change: 0 above, 1 below (a tap on a pane selects it)
    val paneB: List<Bitmap>? = null,                  // the other product's own field and, over it, the shared lines
    val titleB: String? = null,
    val styleB: Style? = null,
    val sector: Sector = Sector.all[0],
    val cycles: List<NbmCycle> = emptyList(),
    val cycle: NbmCycle? = null,
    val following: Boolean = true,                    // the newest complete run is shown (no run was picked by hand)
    val leads: Map<Long, Int?> = emptyMap(),          // per run (epoch seconds): the lead in hours of its last frame; null = looked, nothing there
    val frames: List<NbmFile> = emptyList(),
    val index: Int = 0,
    val map: Bitmap? = null,
    val style: Style? = null,
    val crosshair: Pair<Float, Float>? = null,        // fractions (0..1) of the picture
    val tapLatLon: Pair<Double, Double>? = null,
    val readout: String? = null,
    val playing: Boolean = false,
    val prefetch: Float? = null,                      // 0..1 while a loop is being downloaded
    val favorites: Set<String> = emptySet(),
    val places: List<Place> = emptyList(),
    val settings: Settings = Settings(),
    val contactPrompt: Boolean = false,
    val loopSpanHours: Int = 0,                       // how much of the run Play and a loop video cover, from its start; 0 = all
    val loopTimes: Pair<Long, Long>? = null,     // the loop as a range of valid times chosen on the time bar (overrides the length / span below)
    val globe: Boolean = false,
    val cam: GlobeCamera = GlobeCamera(38.0, -97.0, 380.0),
    val focus: simplemodelview.MapFocus? = null,      // the part of the next map picture to zoom to (set on leaving the globe), cleared once applied
) {
    val file: NbmFile? get() = frames.getOrNull(index)
}

class NbmViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val handoff = GlobeHandoff(core)
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.nbm
    private val renderer = MapRenderer(core, AppContainer.lines)
    private val globeRenderer = GlobeRenderer(core, AppContainer.lines)
    private var globeW = 0
    private var globeH = 0
    private val outlook = AppContainer.outlook

    private var dark = false
    private var frameJob: Job? = null
    private var playJob: Job? = null
    private var shown: Pair<Product, FloatArray>? = null      // what the picture shows, for the read-out
    private var view: View? = null
    private var viewport = floatArrayOf(0f, 0f, 1f, 1f)       // visible part of the picture: left, top, right, bottom fractions
    private var lastLoad = 0L
    private var pickedCycle: NbmCycle? = null                 // a run chosen by hand, kept across fields

    // decoded fields, newest use last; one is 3.7 million cells (15 MB), so only a few are kept
    private val decoded = LinkedHashMap<String, GridField>(8, 0.75f, true)
    private val cellBudget = 12_000_000L

    private val _state = MutableStateFlow(
        NbmState(
            product = NbmCatalog.byId(prefs.getString("nbm_product", null)) ?: NbmCatalog.default,
            sector = Sector.all.firstOrNull { it.name == prefs.getString("nbm_sector", null) } ?: Sector.all[0],
            favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(),
            places = decodePlaces(prefs.getString("places", "").orEmpty()),
            settings = readSettings(),
            loopSpanHours = prefs.getInt("nbm_loop_hours", 0),
        )
    )
    val state: StateFlow<NbmState> = _state

    // the saved places are one list for every page: this page's copy follows it
    init { viewModelScope.launch { AppContainer.places.places.collect { p -> update { it.copy(places = p) } } } }

    private inline fun update(f: (NbmState) -> NbmState) { _state.value = f(_state.value) }

    private fun readSettings() = Settings(
        prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(), prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false),
    ).withDisplayPrefs(prefs)

    private val dataSaver get() = prefs.getBoolean("data_saver", false)
    private val wifiOnlyLoop get() = prefs.getBoolean("wifi_only", false)

    init {
        load(); promptForContact()
        // a newly read (or changed) SPC outlook is drawn at once
        viewModelScope.launch { overlayChanges(lightning = false).collect { if (shown != null && !_state.value.playing) { if (_state.value.globe) requestGlobe(coarse = false) else showFrame() } } }
    }

    /** The other pages may have changed favorites, places or settings while this page was away. */
    fun reloadFromPrefs() = update {
        it.copy(favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(), places = decodePlaces(prefs.getString("places", "").orEmpty()), settings = readSettings())
    }

    /**
     * (Re)reads the runs and the frames of the chosen field. A run picked by hand stays; otherwise the newest run that has the field and has
     * arrived in full (a run still being uploaded has fewer frames than the one before it, and is skipped until it catches up). The frame nearest the
     * one shown before is kept, else the one for the current hour.
     */
    fun load() {
        stopPlaying()
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            var failure: Exception? = null
            for (attempt in 0..3) {       // a network that is not ready yet fixes itself: retry a few times
                try {
                    val now = System.currentTimeMillis() / 1000
                    val cycles = repo.listCycles(now)
                    if (cycles.isEmpty()) throw IllegalStateException("No recent National Blend of Models runs on the server.")
                    val product = _state.value.product
                    val keepValid = _state.value.file?.valid
                    var note: String? = null
                    val hand = pickedCycle?.takeIf { it in cycles }
                    var chosen: Pair<NbmCycle, List<NbmFile>>? = null
                    if (hand != null) {
                        val frames = repo.listFrames(product.id, hand)
                        if (frames.isNotEmpty()) chosen = hand to frames else note = "The ${hand.label} run has no ${product.title.lowercase()}; showing the newest run."
                    }
                    if (chosen == null) {
                        var best: Pair<NbmCycle, List<NbmFile>>? = null
                        var older: Pair<NbmCycle, List<NbmFile>>? = null
                        // the newest two runs are asked about together (the second only matters when the first is still arriving)
                        val firstTwo = cycles.take(2).map { c -> async { c to repo.listFrames(product.id, c) } }.awaitAll()
                        for ((c, frames) in firstTwo) {
                            if (frames.isEmpty()) continue
                            if (best == null) best = c to frames else { older = c to frames; break }
                        }
                        if (best == null) for (c in cycles.drop(2).take(4)) {
                            val frames = repo.listFrames(product.id, c)
                            if (frames.isEmpty()) continue
                            if (best == null) best = c to frames else { older = c to frames; break }
                        }
                        if (best == null) throw IllegalStateException("No ${product.title.lowercase()} in the last runs on the server.")
                        chosen = if (older == null || best.second.size >= older.second.size * 0.9) best else older
                        if (chosen !== best) note = "The ${best.first.shortLabel} run is still arriving; showing ${chosen.first.shortLabel}."
                    }
                    val picked = chosen ?: throw IllegalStateException("No recent National Blend of Models data.")
                    if (picked.first.grib) note = "NOAA's rendered files are behind, so this run comes from its GRIB files. Only temperature, dewpoint, wind, cloud cover, humidity, visibility, ceiling, precipitable water and CAPE are there; the other products show the last rendered run."
                    // NOAA's rendered (GeoTIFF) files can stop while the model keeps running: say how old the newest run is
                    val ageHours = (now - picked.first.epoch) / 3600
                    if (hand == null && !picked.first.grib && ageHours >= 6) note = "The newest run NOAA has published in this format is ${picked.first.label}, ${if (ageHours >= 48) "${ageHours / 24} days" else "$ageHours hours"} old. The rendered files may be behind; the model itself may be newer."
                    lastLoad = System.currentTimeMillis()
                    val frames = picked.second
                    val index = keepValid?.let { nearest(frames, it) } ?: frames.indexOfFirst { it.valid >= now - 1800 }.let { if (it < 0) frames.size - 1 else it }
                    update { it.copy(cycles = cycles, cycle = picked.first, following = hand == null, frames = frames, index = index, notice = note ?: it.notice) }
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

    private fun nearest(frames: List<NbmFile>, valid: Long): Int =
        frames.indices.minByOrNull { kotlin.math.abs(frames[it].valid - valid) } ?: 0

    /** Coming back to the page after a while: follow the newest run if no run was picked by hand. */
    fun refreshIfStale() {
        val st = _state.value
        if (System.currentTimeMillis() - lastLoad < 10 * 60 * 1000 || st.playing || st.busy || !st.following) return
        load()
    }

    /** The product [delta] places along in the catalog (the arrows beside the product chip). */
    fun stepProduct(delta: Int) {
        val all = NbmCatalog.families
        val at = all.indexOfFirst { it.id == activeProduct().family }
        all.getOrNull(at + delta)?.let { pickProduct(it.first) }
    }

    /** The product the controls are working on: the one above, or in a compare the one of the selected pane. */
    fun activeProduct(st: NbmState = _state.value): NbmProduct = if (st.compare && st.activePane == 1) st.other ?: st.product else st.product

    fun selectPane(i: Int) { if (_state.value.activePane != i) update { it.copy(activePane = i) } }

    /** In a compare: the two panes change places (the one above becomes the one below). */
    fun swapPanes() {
        val st = _state.value
        val o = st.other ?: return
        prefs.edit().putString("nbm_product", o.id).apply()
        update { it.copy(product = o, other = st.product, paneB = null, titleB = null, styleB = null) }
        load()
    }

    fun pickProduct(picked: NbmProduct) {
        val cur = _state.value
        if (cur.compare && cur.activePane == 1 && cur.other != null) {
            // the pane below: only that picture is replaced (the run, time and zoom stay)
            update { it.copy(other = picked, paneB = null, titleB = null, styleB = null) }
            viewModelScope.launch { showCurrent() }
            return
        }
        prefs.edit().putString("nbm_product", picked.id).apply()
        update { st -> st.copy(product = picked, other = if (st.compare) st.other else st.product.takeIf { it.id != picked.id } ?: st.other) }
        load()
    }

    /** Shows [cycle] (null: follow the newest run again). */
    fun pickCycle(cycle: NbmCycle?) {
        pickedCycle = cycle
        load()
    }

    /** Looks up how far each listed run reaches (one small request each, a few at a time), for the run picker's notes. */
    fun loadLeads() {
        viewModelScope.launch {
            val todo = _state.value.cycles.filter { it.epoch !in _state.value.leads }
            val gate = Semaphore(4)
            coroutineScope {
                for (c in todo) launch {
                    gate.withPermit {
                        val lead = try { repo.lastLead("temp", c) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        update { it.copy(leads = it.leads + (c.epoch to lead)) }
                    }
                }
            }
        }
    }

    fun pickSector(sector: Sector) {
        prefs.edit().putString("nbm_sector", sector.name).apply()
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

    fun toggleFavorite(product: Product) {
        val favorites = _state.value.favorites.let { if (product.id in it) it - product.id else it + product.id }
        prefs.edit().putStringSet("favorites", favorites).apply()
        update { it.copy(favorites = favorites) }
    }

    private fun showFrame() {
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    /** The decoded field of [file]: cached for a few frames, decoded again (a few tens of milliseconds) otherwise. */
    private suspend fun decode(file: NbmFile, cache: Boolean = true): GridField {
        if (cache) decoded[file.cacheKey]?.let { return it }
        val field = withContext(Dispatchers.Default) { Timings.timed("data") { repo.loadFrame(file) { text -> if (file.grib) update { it.copy(loadStatus = text) } } } }
        if (cache) { decoded[file.cacheKey] = field; trimCache() }
        if (file.grib) update { it.copy(loadStatus = null) }
        return field
    }

    private fun trimCache() {
        var cells = decoded.values.sumOf { it.values.size.toLong() }
        val it = decoded.entries.iterator()
        while (cells > cellBudget && decoded.size > 1 && it.hasNext()) {
            val e = it.next()
            cells -= e.value.values.size
            it.remove()
        }
    }

    /** The sector's window of the blend's grid, drawn at as many whole pixels per cell as keep the picture under about 6 million pixels (at most 3). */
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
        if (on) viewModelScope.launch { showCurrent() }
    }

    /** The compare's second picture: the other product in the same run at the frame nearest this time, as its own field only, with the lines and alerts over it once. */
    private suspend fun computePaneB(product: Product, field: GridField, v: View, file: NbmFile) {
        val st = _state.value
        val b = st.other
        val cycle = st.cycle
        if (!st.compare || st.playing || b == null || cycle == null) { if (st.paneB != null) update { it.copy(paneB = null, titleB = null, styleB = null) }; return }
        try {
            val frames = repo.listFrames(b.id, cycle)
            val fb = frames.minByOrNull { kotlin.math.abs(it.valid - file.valid) }?.takeIf { kotlin.math.abs(it.valid - file.valid) <= 5400 }
            if (fb == null) { update { it.copy(paneB = null, notice = "${b.title} has no frame near this time in this run.") }; return }
            val fieldB = decode(fb)
            val settings = st.settings
            val syn = synthetic(b, fieldB.grid)
            val (over, fld) = withContext(Dispatchers.Default) {
                val bv = if (fieldB.grid == field.grid) v else viewFor(st.sector, fieldB.grid)
                renderer.render(product, product.style, field.values, emptyList(), v, dark, alerts = outlookDraws(core, outlook, v, file.valid, file.run), counties = settings.counties, part = RenderPart.Over) to
                    renderer.render(syn, syn.style, fieldB.values, emptyList(), bv, dark, contours = settings.contours, barbDensity = settings.barbDensity, part = RenderPart.Field)
            }
            update { it.copy(paneB = listOf(fld, over), titleB = b.title, styleB = syn.style) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(paneB = null, notice = e.message) } }
    }

    private fun synthetic(product: NbmProduct, grid: Grid) =
        Product(product.pickerId, product.title, product.group, product.style, hasMembers = false, timeDimension = null, grid = grid)

    private suspend fun showCurrent() {
        val st = _state.value
        val file = st.file ?: return
        update { it.copy(busy = true, error = null) }
        val field = decode(file)
        val grid = field.grid
        if (st.globe) {
            shown = synthetic(st.product, grid) to field.values
            if (globeW > 0) renderGlobeNow(1) else update { it.copy(busy = false) }
            return
        }
        outlook.want(file.valid, file.run)
        val v = viewFor(st.sector, grid)
        val product = synthetic(st.product, grid)
        val bitmap = withContext(Dispatchers.Default) {
            renderer.render(product, product.style, field.values, emptyList(), v, dark, alerts = outlookDraws(core, outlook, v, file.valid, file.run), contours = _state.value.settings.contours, counties = _state.value.settings.counties, barbDensity = _state.value.settings.barbDensity)
        }
        view = v
        shown = product to field.values
        update { it.copy(map = bitmap, style = product.style, busy = false, detail = null, focus = handoff.takeFocus(v)) }
        computePaneB(product, field, v, file)
        scheduleDetail()
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    // ---- taps and read-outs ----
    /** A tap on the picture, given as fractions of its width and height. */
    fun onTap(fx: Float, fy: Float) {
        val (product, values) = shown ?: return
        val v = view ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { clearTap(); return }
        val column = v.col0 + floor(fx * v.cols).toInt().coerceIn(0, v.cols - 1)
        val row = v.row0 + v.rows - 1 - floor(fy * v.rows).toInt().coerceIn(0, v.rows - 1)
        val (lat, lon) = core.gridToLatLon(product.grid, column.toDouble(), row.toDouble())
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, values, emptyList(), column, row) + (outlook.readoutAt(lat, lon, _state.value.file?.valid, _state.value.file?.run)?.let { t -> "   \u00B7 $t" } ?: ""), tapLatLon = lat to lon) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null) }

    // ---- the globe ----
    fun onGlobeTap(px: Float, py: Float) {
        val (product, values) = shown ?: return
        val ll = globeInverse(_state.value.cam, globeW, globeH, px.toDouble(), py.toDouble())
        if (ll == null) { clearTap(); return }
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, ll[0], ll[1])
        val column = Math.round(c).toInt()
        val row = Math.round(r).toInt()
        val text = if (column !in 0 until grid.columns || row !in 0 until grid.rows)
            "${placeLabel(ll[0], ll[1])}   outside the National Blend of Models area" else readout(core, product, values, emptyList(), column, row)
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

    /** One render at a time; while one runs, further requests collapse into a single follow-up with the newest camera. */
    private fun requestGlobe(coarse: Boolean) {
        globeDirty = true
        globeCoarse = coarse
        if (globeBusy) return
        globeBusy = true
        viewModelScope.launch {
            try {
                while (globeDirty) {
                    globeDirty = false
                    renderGlobeNow(if (globeCoarse) 4 else 1)
                }
            } finally { globeBusy = false }
        }
    }

    private suspend fun renderGlobeNow(stride: Int) {
        val (product, values) = shown ?: return
        if (globeW == 0 || values.size < product.grid.cells) return
        val st = _state.value
        val valid = st.file?.valid
        val run = st.file?.run
        outlook.want(valid, run)
        val bitmap = withContext(Dispatchers.Default) {
            val alerts = outlookDrawsOn(outlook, valid, run) { lat, lon -> globeForward(st.cam, globeW, globeH, lat, lon) }
            globeRenderer.render(product.grid, values, product.style, emptyList(), st.cam, globeW, globeH, stride, dark, product, st.settings.contours, st.settings.barbDensity, alerts = alerts, counties = st.settings.counties)
        }
        update { it.copy(map = bitmap, busy = false) }
    }

    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewport = floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
        scheduleDetail()
    }

    private var detailJob: Job? = null

    /** After the zoom has rested a moment, only the cells on screen are drawn again, finer, over the overview. */
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
                val valid = st.file?.valid
                val run = st.file?.run
                val settings = _state.value.settings
                val bitmap = withContext(Dispatchers.Default) {
                    renderer.render(product, product.style, values, emptyList(), window.view, dark, alerts = outlookDraws(core, outlook, window.view, valid, run), contours = settings.contours, counties = settings.counties, barbDensity = settings.barbDensity)
                }
                if (view === base && shown?.second === values) update { it.copy(detail = DetailLayer(bitmap, window.left, window.top, window.right, window.bottom, 2.5)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    // ---- places (bookmarks) ----
    fun savePlace(name: String, lat: Double, lon: Double) = AppContainer.places.save(name, lat, lon)

    /** The place [old] with a new name and / or position (names are the keys; its place in the list stays). */
    /** The place [old] with a new name and / or position (names are the keys; its place in the list stays). */
    fun editPlace(old: Place, name: String, lat: Double, lon: Double) = AppContainer.places.edit(old, name, lat, lon)

    /** The shown field's value at a place, as a line of text, without moving the map or the crosshair. */
    fun readAt(lat: Double, lon: Double): String {
        val (product, values) = shown ?: return "Nothing is shown yet"
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, lat, lon)
        val column = Math.round(c).toInt(); val row = Math.round(r).toInt()
        return if (column !in 0 until grid.columns || row !in 0 until grid.rows) "${placeLabel(lat, lon)}   outside the area shown" else readout(core, product, values, emptyList(), column, row)
    }

    fun deletePlace(place: Place) = AppContainer.places.delete(place)

    /** Puts the crosshair on a saved place (switching to CONUS when the current sector does not contain it). */
    /** [follow]: a live fix; it only moves the crosshair, and never reloads another sector to reach it. */
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

    // ---- settings ----
    fun setSettings(settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).putBoolean("contours", settings.contours).putBoolean("minimal_ui", settings.minimalUi).putDisplay(settings).apply()
        val redraw = settings.dataSaver != _state.value.settings.dataSaver || settings.contours != _state.value.settings.contours || settings.displayDiffers(_state.value.settings)
        update { it.copy(settings = settings) }
        if (redraw && shown != null) showFrame()
    }

    private fun backup(): SettingsBackup {
        val st = _state.value
        return SettingsTransfer.withPrefs(SettingsBackup(
            wifiOnlyLoop = st.settings.wifiOnlyLoop, dataSaver = st.settings.dataSaver, cacheMegabytes = st.settings.cacheMegabytes,
            contactEmail = st.settings.contactEmail, favorites = st.favorites.sorted(),
            places = st.places.map { BackupPlace(it.name, it.lat, it.lon) },
            product = prefs.getString("product", null), sector = prefs.getString("sector", null),
        ), prefs)
    }

    suspend fun exportSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = SettingsBackup.encode(backup())
        val ok = withContext(Dispatchers.IO) {
            try { resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (e: Exception) { false }
        }
        update { it.copy(notice = if (ok) "Settings exported: ${backup().favorites.size} favorites, ${backup().places.size} places." else "Could not write the settings file.") }
    }

    /** True when display or source choices were imported: the app restarts to take them up. */
    suspend fun importSettings(resolver: android.content.ContentResolver, uri: android.net.Uri): Boolean {
        RainHistory.importIfArchive(resolver, uri)?.let { note -> update { it.copy(notice = note) }; return false }      // (a rain history file from the export: no restart)
        val text = withContext(Dispatchers.IO) {
            try { resolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().decodeToString() } } catch (e: Exception) { null }
        }
        val b = text?.let { SettingsBackup.decode(it) }
        if (b == null) { update { it.copy(notice = "That file is not a simplemodelview settings file.") }; return false }
        val current = _state.value.settings
        setSettings(current.copy(wifiOnlyLoop = b.wifiOnlyLoop, dataSaver = b.dataSaver, cacheMegabytes = b.cacheMegabytes, contactEmail = b.contactEmail.ifEmpty { current.contactEmail }))
        prefs.edit().putStringSet("favorites", b.favorites.toSet()).apply()
        AppContainer.places.replaceAll(b.places.map { Place(it.name, it.lat, it.lon) })
        update { it.copy(favorites = b.favorites.toSet(), places = b.places.map { p -> Place(p.name, p.lat, p.lon) }) }
        if (isValidEmail(_state.value.settings.contactEmail)) dismissContactPrompt()
        val extras = SettingsTransfer.apply(prefs, b)
        update { it.copy(notice = "Settings imported: ${b.favorites.size} favorites, ${b.places.size} places" + (if (extras > 0) ", $extras display and source choices" else "") + ".") }
        return extras > 0
    }

    private var lastContactPrompt = 0L

    /** Optional but wanted: ask again at every launch and after 30 minutes away, until an address is saved. "Not now" just closes it. */
    fun promptForContact() {
        val now = System.currentTimeMillis()
        if (isValidEmail(_state.value.settings.contactEmail) || now - lastContactPrompt < 30 * 60 * 1000 && lastContactPrompt != 0L) return
        lastContactPrompt = now
        update { it.copy(contactPrompt = true) }
    }

    fun dismissContactPrompt() = update { it.copy(contactPrompt = false) }

    // ---- loop ----
    /** Chooses how much of the run Play (and a loop video) covers, from its start, in hours; 0 = all. Restarts a running loop. */
    fun setLoopSpan(hours: Int) {
        prefs.edit().putInt("nbm_loop_hours", hours).apply()
        val wasPlaying = _state.value.playing
        if (wasPlaying) stopPlaying()
        update { it.copy(loopSpanHours = hours, loopTimes = null) }
        if (wasPlaying) startPlaying()
    }

    /** The loop as a range of valid times chosen on the time bar (null: the page's usual loop). It follows the times, so a new scan or run does not shift it. */
    fun setLoopTimes(range: Pair<Long, Long>?) {
        val wasPlaying = _state.value.playing
        if (wasPlaying) stopPlaying()
        update { it.copy(loopTimes = range) }
        if (wasPlaying) startPlaying()
    }

    /** The frames Play and a loop video cover: the range chosen on the time bar, else the span from the start (or from the frame shown). */
    fun loopWindowFor(st: NbmState): IntRange =
        st.loopTimes?.let { (a, b) -> simplemodelview.loopIndices(st.frames.map { it.valid }, a, b) }
            ?: nbmLoopWindow(st.frames, st.loopSpanHours, if (AppContainer.loopFromShown.value) st.index else 0)

    fun togglePlay() { if (_state.value.playing) stopPlaying() else startPlaying() }

    private fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        update { it.copy(playing = false, prefetch = null) }
    }

    private fun startPlaying() {
        val st = _state.value
        if (st.frames.size < 2) return
        if (wifiOnlyLoop && isMetered()) {
            update { it.copy(notice = "Loops are set to download on Wi-Fi only. Connect to Wi-Fi or change this in Settings.") }
            return
        }
        playJob = viewModelScope.launch {
            update { it.copy(playing = true, prefetch = 0f) }
            val frames = st.frames
            val window = loopWindowFor(st)
            outlook.wantAllAs(window.map { frames[it].valid to frames[it].run })      // the outlook of every day the loop reaches, ready when the loop gets there
            val count = window.count()
            val ready = BooleanArray(frames.size)
            var finished = false
            try {
                // download in order, a few at a time (politely), while the slideshow already runs on the frames that have arrived
                launch {
                    val gate = Semaphore(3)
                    var done = 0
                    coroutineScope {
                        for (i in window) launch {
                            gate.withPermit {
                                try { repo.prefetch(frames[i]); ready[i] = true } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                            }
                            done += 1
                            update { it.copy(prefetch = if (done == count) null else done / count.toFloat()) }
                        }
                    }
                    finished = true
                }
                if (_state.value.index in window) ready[_state.value.index] = true          // the frame on screen is loaded
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
                    // each frame is held for as long as the forecast hours it stands for (1 x for hourly frames, 3 x for 3-hourly, 6 x for 6-hourly),
                    // so a day of forecast takes the same time to play at every spacing; going back to the first frame rests a little longer
                    val wrap = if (next < at) (if (finished) 1200L else 500L) else 0L
                    delay(maxOf(nbmDwellMillis(frames, next, LOOP_BASE_MILLIS), wrap))
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

    // ---- share / export ----
    var videoSize: VideoSize
        get() = VideoSize.byName(prefs.getString("video_size", null))
        set(value) { prefs.edit().putString("video_size", value.name).apply() }

    /** Whole pixels per cell for an export: about 1800 px wide, at most 6 per cell, under about 8 million pixels. */
    private fun exportScale(cols: Int, rows: Int): Int {
        var s = ceil(1800.0 / cols).toInt().coerceIn(1, 6)
        while (s > 1 && cols.toLong() * s * rows * s > 8_000_000L) s -= 1
        return s
    }

    /** The window of the grid an export of [area] covers: the visible part of the picture, the sector on screen, or a meso sector; at the export scale. */
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

    private fun header(file: NbmFile): String =
        "Valid ${formatUtc(file.valid)} (+${file.leadHours} h)   Run ${NbmCycle.ofEpoch(file.run).label}   Data: NOAA/NWS National Blend of Models (experimental viewer)"

    private fun fileName(product: NbmProduct, file: NbmFile, extension: String): String {
        val c = simplemodelview.civilFromEpoch(file.valid)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "nbm_${product.id}_${c[0]}${p(c[1])}${p(c[2])}${p(c[3])}.$extension"
    }

    private fun sharedDir() = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    private suspend fun areaImage(area: ShareArea, file: NbmFile, side: Int = GLOBE_EXPORT_SIDE): Triple<Bitmap, Product, Style> {
        val st = _state.value
        val field = decode(file, cache = false)
        if (area == ShareArea.Globe && globeW > 0) {
            val globeProduct = synthetic(st.product, field.grid)
            outlook.ensure(file.valid, file.run)
            val cam = st.cam.copy(radius = st.cam.radius * side / minOf(globeW, globeH))       // the globe as turned now, on a square at export size
            val bitmap = withContext(Dispatchers.Default) {
                globeRenderer.render(
                    field.grid, field.values, globeProduct.style, emptyList(), cam, side, side, 1, dark = false, product = globeProduct, contours = st.settings.contours, barbDensity = st.settings.forExport().barbDensity,
                    alerts = outlookDrawsOn(outlook, file.valid, file.run) { la, lo -> globeForward(cam, side, side, la, lo) }, counties = st.settings.forExport().counties,
                )
            }
            return Triple(bitmap, globeProduct, globeProduct.style)
        }
        outlook.ensure(file.valid, file.run)
        val box = areaView(area, field.grid)
        val product = synthetic(st.product, field.grid)
        val map = withContext(Dispatchers.Default) {
            renderer.render(product, product.style, field.values, emptyList(), box, dark = false, alerts = outlookDraws(core, outlook, box, file.valid, file.run), contours = st.settings.contours, counties = st.settings.forExport().counties, barbDensity = st.settings.forExport().barbDensity)
        }
        return Triple(map, product, product.style)
    }

    /** The compare's second picture for an export: the other product, in the same run at the frame nearest this time, drawn in full on [area]. Null: none near. */
    private suspend fun areaImageB(area: ShareArea, file: NbmFile): Triple<Bitmap, Product, Style>? {
        val st = _state.value
        val b = st.other ?: return null
        val cycle = st.cycle ?: return null
        val fb = repo.listFrames(b.id, cycle).minByOrNull { kotlin.math.abs(it.valid - file.valid) }?.takeIf { kotlin.math.abs(it.valid - file.valid) <= 5400 } ?: return null
        val fieldB = decode(fb, cache = false)
        val syn = synthetic(b, fieldB.grid)
        val box = areaView(area, fieldB.grid)
        val map = withContext(Dispatchers.Default) {
            renderer.render(syn, syn.style, fieldB.values, emptyList(), box, dark = false, alerts = outlookDraws(core, outlook, box, file.valid, file.run), contours = st.settings.contours, counties = st.settings.forExport().counties, barbDensity = st.settings.forExport().barbDensity)
        }
        return Triple(map, syn, syn.style)
    }

    /** Writes the shareable picture (header, map, color key) and returns the file, or null when there is nothing to share. In a compare both panes are on it. */
    suspend fun buildShareFile(area: ShareArea = ShareArea.Whole, size: VideoSize? = null): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val file = st.file ?: return@withContext null
        val (map, product, style) = areaImage(area, file)
        val b = if (st.compare && st.other != null && area != ShareArea.Globe) areaImageB(area, file) else null
        val image = if (b != null) renderer.shareCompareImage(map, b.first, st.product.title, st.other!!.title, product, style, b.second, b.third, header(file), size?.width ?: 0)
            else renderer.shareImage(map, product, style, header(file), emptyList())
        val out = File(sharedDir(), (if (b != null) "compare_" else "") + fileName(st.product, file, "png"))
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out
    }

    /** Renders the frames of the loop into an MP4 and returns it. Each frame is held for the forecast hours it stands for. [onProgress] gets (done, total). */
    suspend fun buildLoopFile(area: ShareArea, size: VideoSize, onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val window = loopWindowFor(st)
        val n = window.count()
        if (n < 2) return@withContext null
        val out = File(sharedDir(), fileName(st.product, st.frames[window.first], "mp4"))
        var exporter: LoopExporter? = null
        try {
            for ((k, i) in window.withIndex()) {
                val file = st.frames[i]
                val (map, product, style) = areaImage(area, file, GLOBE_LOOP_SIDE)
                var picture = renderer.shareImage(map, product, style, header(file), emptyList())
                val (sw, sh) = size.scaled(picture.width, picture.height)
                if (sw != picture.width) picture = Bitmap.createScaledBitmap(picture, sw, sh, true)
                if (exporter == null) exporter = LoopExporter(out, picture.width, picture.height, size.bitrate(picture.width, picture.height, (1000 / LOOP_BASE_MILLIS).toInt()), frameMillis = LOOP_BASE_MILLIS.toInt())
                exporter.add(picture, last = k == n - 1, hold = nbmStepHours(st.frames, i))
                onProgress(k + 1, n)
            }
            exporter?.finish()
            out
        } catch (e: Exception) {
            exporter?.abort()
            throw e
        }
    }

    /** Estimated size of a loop video of the current field and area, for the share dialog. */
    fun estimateLoopMegabytes(area: ShareArea, size: VideoSize): Double {
        val grid = shown?.first?.grid ?: return 0.0
        val st = _state.value
        val frames = loopWindowFor(st).count()
        if (area == ShareArea.Globe) { val w = GLOBE_LOOP_SIDE; return VideoSize.estimateMegabytes(size, w, w + w / 6, frames) }
        val box = areaView(area, grid)
        return VideoSize.estimateMegabytes(size, box.pixelWidth, box.pixelHeight + box.pixelWidth / 6, frames)   // + header and legend
    }

    private companion object {
        /** The time a loop shows one forecast hour of frame spacing. */
        const val LOOP_BASE_MILLIS = 150L
    }
}
