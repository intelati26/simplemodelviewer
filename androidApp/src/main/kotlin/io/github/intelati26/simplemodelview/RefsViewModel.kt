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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import simplemodelview.Catalog
import simplemodelview.Cycle
import simplemodelview.CycleStore
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.globeInverse
import simplemodelview.BackupPlace
import simplemodelview.Place
import simplemodelview.loopWindow
import simplemodelview.nextPlayable
import simplemodelview.VideoSize
import simplemodelview.SettingsBackup
import simplemodelview.isValidEmail
import simplemodelview.Product
import simplemodelview.decodePlaces
import simplemodelview.encodePlaces
import simplemodelview.placeLabel
import simplemodelview.RefsException
import simplemodelview.RefsRepository
import simplemodelview.Sector
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.formatUtc
import simplemodelview.epochSeconds
import simplemodelview.readout
import simplemodelview.withAutoStops
import java.io.File
import kotlin.math.floor

/** What part of the map an export covers. */
sealed class ShareArea {
    /** Whatever is on the screen right now (the zoomed / panned part of the picture). */
    object Screen : ShareArea()
    /** The whole picture currently selected (CONUS or the chosen sector). */
    object Whole : ShareArea()
    /** The globe as it is turned now (only offered in globe mode). */
    object Globe : ShareArea()
    /** A meso sector, whatever is shown now. */
    data class Meso(val sector: Sector) : ShareArea()
}

data class Settings(val wifiOnlyLoop: Boolean = false, val dataSaver: Boolean = false, val cacheMegabytes: Int = 150, val contactEmail: String = "")

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
    val favorites: Set<String> = emptySet(),
    val settings: Settings = Settings(),
    val places: List<Place> = emptyList(),
    val tapLatLon: Pair<Double, Double>? = null,
    val newerCycle: Cycle? = null,
    val globe: Boolean = false,
    val cam: GlobeCamera = GlobeCamera(38.0, -97.0, 380.0),
    val fileTime: Long? = null,                  // when the shown frame's file was uploaded (server Last-Modified, epoch s)
    val runComplete: Long? = null,               // when the run's metadata was last written: the run is complete about then
    val contactPrompt: Boolean = false,          // the "please enter your email" dialog is showing
    val notice: String? = null,                  // a short message over a working map (the error field replaces the map)
    val loopLength: Int = 0,                     // how many frames Play and a loop video cover (the first N); 0 = all
) {
    val validLabel: String
        get() = times.getOrNull(timeIndex)?.let { formatUtc(it) } ?: ""
    val forecastHour: Int?
        get() = cycle?.let { c -> times.getOrNull(timeIndex)?.let { ((it - c.epochSeconds) / 3600).toInt() } }
}

/** The member the member viewer opens on: the RRFS control member (shown as "RRFS Ctl"). */
private const val DEFAULT_MEMBER = "RRFS"

class RefsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.repo
    private val lines = AppContainer.lines
    private val renderer = MapRenderer(core, lines)
    private val globeRenderer = GlobeRenderer(core, lines)
    private var globeW = 0
    private var globeH = 0

    private var store: CycleStore? = null
    private var values: FloatArray? = null
    private var view: View = View.CONUS
    private var dark = false
    private var viewport = floatArrayOf(0f, 0f, 1f, 1f)   // visible part of the picture as fractions: left, top, right, bottom
    private var frameJob: Job? = null
    private var playJob: Job? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        UiState(
            favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(),
            settings = Settings(prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty()),
            places = decodePlaces(prefs.getString("places", "").orEmpty()),
            sector = Sector.all.firstOrNull { it.name == prefs.getString("sector", null) } ?: Sector.all[0],
            loopLength = prefs.getInt("loop_frames", 0),
        )
    )
    val state: kotlinx.coroutines.flow.StateFlow<UiState> = _state

    private inline fun update(f: (UiState) -> UiState) { _state.value = f(_state.value) }

    /** The MRMS page may have changed favorites, places or settings while this page was away. */
    fun reloadFromPrefs() = update {
        it.copy(
            favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(), places = decodePlaces(prefs.getString("places", "").orEmpty()),
            settings = Settings(prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty()),
        )
    }

    init { load(); promptForContact() }

    fun load() {
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            // a network that is not ready yet (just after boot, a train tunnel) fixes itself: try again a few times before giving up
            var failure: Exception? = null
            for (attempt in 0..3) {
                try {
                    val cycles = repo.findCycles(System.currentTimeMillis() / 1000)
                    if (cycles.isEmpty()) throw RefsException("Could not reach SPC's REFS data. Check the connection and try again.")
                    update { it.copy(cycles = cycles) }
                    openCycle(cycles.first())
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    if (attempt < 3) delay(2000L shl attempt)      // 2 s, 4 s, 8 s
                }
            }
            update { it.copy(busy = false, error = failure?.message ?: "Something went wrong") }
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
            ?: opened.products.firstOrNull { it.id == "cref" } ?: opened.products.firstOrNull { it.id == "cref_max" } ?: opened.products.first()   // (starts at F000 / F001, unlike the 4-h products)
        val members = repo.members(opened)
        // a (new) run always opens at its earliest time step: F000 / F001, not wherever the previous run's slider was
        // the member shown is remembered by name (default: the RRFS control member, "RRFS Ctl"), so it survives a new run
        val wanted = prefs.getString("member_name", DEFAULT_MEMBER)
        val memberIndex = members.indexOf(wanted).takeIf { it >= 0 } ?: members.indexOf(DEFAULT_MEMBER).takeIf { it >= 0 } ?: 0
        update { it.copy(cycle = cycle, products = opened.products, members = members, member = memberIndex, timeIndex = 0) }
        selectProductInternal(product, keepHour = false)
    }

    fun pickProduct(product: Product) {
        stopPlaying()
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try { selectProductInternal(product, keepHour = true) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    /**
     * Opens [product] at a sensible time: the same forecast hour as before when [keepHour] and the new product has it, otherwise
     * its earliest time step (F000 / F001 for most products).
     */
    private suspend fun selectProductInternal(product: Product, keepHour: Boolean) {
        val s = store ?: return
        prefs.edit().putString("product", product.id).apply()
        val times = repo.validTimes(s, product)
        val before = _state.value
        val hour = if (keepHour) before.forecastHour else null
        val index = if (hour == null) 0 else times.indexOfFirst { (it - s.cycle.epochSeconds) / 3600 == hour.toLong() }.coerceAtLeast(0)
        update { it.copy(product = product, times = times, timeIndex = index) }
        loadFrame()
    }

    fun pickMember(index: Int) {
        _state.value.members.getOrNull(index)?.let { prefs.edit().putString("member_name", it).apply() }
        update { it.copy(member = index) }
        showFrame()
    }

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

    fun toggleFavorite(product: Product) {
        val favorites = _state.value.favorites.let { if (product.id in it) it - product.id else it + product.id }
        prefs.edit().putStringSet("favorites", favorites).apply()
        update { it.copy(favorites = favorites) }
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
        val frame = repo.readFrameTimed(s, product, st.timeIndex, st.member)
        values = frame.values
        update { it.copy(fileTime = frame.fileTime, runComplete = s.metadataTime) }
        rerender()
    }

    private suspend fun rerender() {
        if (_state.value.globe) { renderGlobeNow(1); return }
        val product = _state.value.product ?: return
        val data = values ?: return
        if (data.size < product.grid.cells) return   // the frame of the previous product is still loaded (another grid)
        val sector = _state.value.sector
        val members = _state.value.members
        val (bitmap, style, v) = withContext(Dispatchers.Default) {
            val g = product.grid
            val v = if (sector.name == "CONUS") View.whole(g).decimated(if (_state.value.settings.dataSaver && g == Grid.REFS) 2 else 1) else sector.view(core, g)
            val style = withAutoStops(product.style, data)
            Triple(renderer.render(product, style, data, members, v, dark), style, v)
        }
        view = v
        update { it.copy(map = bitmap, style = style, busy = false) }
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    // ---- the globe ----
    /** Turns the globe view on or off (a sector choice turns it off). */
    fun setGlobe(on: Boolean) {
        if (_state.value.globe == on) return
        update { it.copy(globe = on, crosshair = null, readout = null, tapLatLon = null) }
        if (values != null) viewModelScope.launch { rerender() }
    }

    /** The globe's drawing area in pixels; the first call also fits the globe to the screen. */
    fun setGlobeSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == globeW && h == globeH)) return
        val first = globeW == 0
        globeW = w; globeH = h
        if (first) update { it.copy(cam = it.cam.copy(radius = 0.49 * minOf(w, h))) }   // the whole globe fits the screen
        if (_state.value.globe && values != null) requestGlobe(coarse = false)
    }

    /** Drag ([dx], [dy] pixels) and pinch ([zoom] factor) on the globe: a coarse picture at once, the full one when the fingers rest. */
    fun globeGesture(dx: Float, dy: Float, zoom: Float) {
        val minR = 0.3 * minOf(globeW, globeH)
        update { it.copy(cam = it.cam.dragged(dx.toDouble(), dy.toDouble()).zoomed(zoom.toDouble(), minR, minR * 60)) }
        requestGlobe(coarse = true)
        settleJob?.cancel()
        settleJob = viewModelScope.launch { delay(180); requestGlobe(coarse = false) }
    }

    fun onGlobeTap(px: Float, py: Float) {
        val product = _state.value.product ?: return
        val data = values ?: return
        val ll = globeInverse(_state.value.cam, globeW, globeH, px.toDouble(), py.toDouble())
        if (ll == null) { clearTap(); return }
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, ll[0], ll[1])
        val column = Math.round(c).toInt()
        val row = Math.round(r).toInt()
        if (column !in 0 until grid.columns || row !in 0 until grid.rows) {
            update { it.copy(tapLatLon = ll[0] to ll[1], readout = "%.2f%s %.2f%s   outside the REFS area".format(
                kotlin.math.abs(ll[0]), if (ll[0] >= 0) "N" else "S", kotlin.math.abs(ll[1]), if (ll[1] < 0) "W" else "E")) }
            return
        }
        update { it.copy(readout = readout(core, product, data, it.members, column, row), tapLatLon = ll[0] to ll[1]) }
    }

    private var settleJob: Job? = null
    private var globeBusy = false
    private var globeDirty = false
    private var globeCoarse = false

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
        val product = _state.value.product ?: return
        val data = values ?: return
        if (globeW == 0 || data.size < product.grid.cells) return
        val st = _state.value
        val (bitmap, style) = withContext(Dispatchers.Default) {
            val style = withAutoStops(product.style, data)
            globeRenderer.render(product.grid, data, style, st.members, st.cam, globeW, globeH, stride, dark) to style
        }
        update { it.copy(map = bitmap, style = style, busy = false) }
    }

    /** A tap on the picture, given as fractions of its width and height. */
    fun onTap(fx: Float, fy: Float) {
        val product = _state.value.product ?: return
        val data = values ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { update { it.copy(crosshair = null, readout = null) }; return }
        val column = view.col0 + floor(fx * view.cols).toInt().coerceIn(0, view.cols - 1)
        val row = view.row0 + view.rows - 1 - floor(fy * view.rows).toInt().coerceIn(0, view.rows - 1)
        val (lat, lon) = core.gridToLatLon(product.grid, column.toDouble(), row.toDouble())
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, data, it.members, column, row), tapLatLon = lat to lon) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null) }

    // ---- settings ----
    fun setSettings(settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).apply()
        val redraw = settings.dataSaver != _state.value.settings.dataSaver
        update { it.copy(settings = settings) }
        if (redraw && values != null) viewModelScope.launch { rerender() }
    }

    // ---- settings export / import ----
    private fun backup(): SettingsBackup {
        val st = _state.value
        return SettingsBackup(
            wifiOnlyLoop = st.settings.wifiOnlyLoop, dataSaver = st.settings.dataSaver, cacheMegabytes = st.settings.cacheMegabytes,
            contactEmail = st.settings.contactEmail, favorites = st.favorites.sorted(),
            places = st.places.map { BackupPlace(it.name, it.lat, it.lon) },
            product = st.product?.id ?: prefs.getString("product", null), sector = st.sector.name,
        )
    }

    /** Writes the settings as JSON to a file the user chose (the system "save as" picker). */
    suspend fun exportSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = SettingsBackup.encode(backup())
        val ok = withContext(Dispatchers.IO) {
            try { resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (e: Exception) { false }
        }
        update { it.copy(notice = if (ok) "Settings exported: ${backup().favorites.size} favorites, ${backup().places.size} places." else "Could not write the settings file.") }
    }

    /** Reads a settings file the user chose, validates it and applies it. Nothing changes if it is not a simplemodelview backup. */
    suspend fun importSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = withContext(Dispatchers.IO) {
            try { resolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().decodeToString() } } catch (e: Exception) { null }
        }
        val b = text?.let { SettingsBackup.decode(it) }
        if (b == null) { update { it.copy(notice = "That file is not a simplemodelview settings file.") }; return }
        val current = _state.value.settings
        setSettings(Settings(b.wifiOnlyLoop, b.dataSaver, b.cacheMegabytes, b.contactEmail.ifEmpty { current.contactEmail }))
        prefs.edit().putStringSet("favorites", b.favorites.toSet()).putString("places", encodePlaces(b.places.map { Place(it.name, it.lat, it.lon) })).apply()
        update { it.copy(favorites = b.favorites.toSet(), places = b.places.map { p -> Place(p.name, p.lat, p.lon) }) }
        if (isValidEmail(_state.value.settings.contactEmail)) dismissContactPrompt()
        Sector.all.firstOrNull { it.name == b.sector }?.let { setGlobe(false); pickSector(it) }
        val product = b.product?.let { id -> _state.value.products.firstOrNull { it.id == id } }
        if (product != null) pickProduct(product) else b.product?.let { prefs.edit().putString("product", it).apply() }
        update { it.copy(notice = "Settings imported: ${b.favorites.size} favorites, ${b.places.size} places.") }
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

    /** Puts the crosshair on a saved place (switching to CONUS when the current sector does not contain it). */
    fun goToPlace(place: Place) {
        val (c, r) = core.latLonToGrid(_state.value.product?.grid ?: Grid.REFS, place.lat, place.lon)
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

    private var lastContactPrompt = 0L

    /**
     * The contact address is optional but wanted (it goes into the User-Agent): without one, ask again at every launch and when the
     * app comes back after more than 30 minutes. Never blocks; "Not now" simply closes it.
     */
    fun promptForContact() {
        val now = System.currentTimeMillis()
        if (isValidEmail(_state.value.settings.contactEmail) || now - lastContactPrompt < 30 * 60 * 1000 && lastContactPrompt != 0L) return
        lastContactPrompt = now
        update { it.copy(contactPrompt = true) }
    }

    fun dismissContactPrompt() = update { it.copy(contactPrompt = false) }

    /** Estimated size of a loop video of the current product and area, for the share dialog. */
    fun estimateLoopMegabytes(area: ShareArea, size: VideoSize): Double {
        val v = resolveView(area)
        val pictureW = if (area == ShareArea.Globe && globeW > 0) 1500 else v.pixelWidth
        val pictureH = if (area == ShareArea.Globe && globeW > 0) 1500 * globeH / globeW else v.pixelHeight
        return VideoSize.estimateMegabytes(size, pictureW, pictureH + pictureW / 6, loopWindow(_state.value.times.size, _state.value.loopLength).count())   // + header and legend
    }

    var videoSize: VideoSize
        get() = VideoSize.byName(prefs.getString("video_size", null))
        set(value) { prefs.edit().putString("video_size", value.name).apply() }

    fun reportNotice(text: String) = update { it.copy(notice = text) }

    fun dismissNotice() = update { it.copy(notice = null) }

    fun dismissNewerCycle() = update { it.copy(newerCycle = null) }

    /** Chooses how many frames Play (and a loop video) covers: the first [frames], or 0 for the whole run. Restarts a running loop. */
    fun setLoopLength(frames: Int) {
        prefs.edit().putInt("loop_frames", frames).apply()
        val wasPlaying = _state.value.playing
        if (wasPlaying) stopPlaying()
        update { it.copy(loopLength = frames) }
        if (wasPlaying) startPlaying()
    }

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
            val window = loopWindow(st.times.size, st.loopLength)
            val count = window.count()
            val ready = BooleanArray(st.times.size)
            var finished = false
            try {
                // download the loop in order, a few frames at a time (politely), while the slideshow is already running on the frames that
                // have arrived: 1-5, then 1-10, then 1-20 as the rest load. The HTTP cache then serves each pass.
                val loader = launch {
                    val gate = Semaphore(3)
                    var done = 0
                    coroutineScope {
                        for (t in window) launch {
                            gate.withPermit {
                                try { repo.prefetchFrame(s, product, t, st.member); ready[t] = true } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                            }
                            done += 1
                            update { it.copy(prefetch = if (done == count) null else done / count.toFloat()) }
                        }
                    }
                    finished = true
                }
                if (_state.value.timeIndex in window) ready[_state.value.timeIndex] = true      // the frame on screen is loaded
                while (true) {
                    val at = _state.value.timeIndex
                    val next = nextPlayable(at, ready)
                    if (next == null || next == at) {
                        if (finished && ready.none { it }) throw RefsException("The loop's frames could not be downloaded.")
                        delay(150)
                        continue
                    }
                    update { it.copy(timeIndex = next) }
                    loadFrame()
                    // a short rest at the end of the cycle (longer once everything has loaded), then back to frame 1
                    delay(if (next < at) (if (finished) 1200L else 500L) else 250L)
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

    /** The UI reports which part of the picture is on screen (after pinch-zoom / pan). */
    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewport = floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
    }

    /** The grid window an export covers, drawn at a resolution that suits its size (about 1800 px wide, at most 8 px per cell). */
    private fun resolveView(area: ShareArea): View = when (area) {
        ShareArea.Globe -> view   // (the globe is drawn by buildArea, not as a grid window)
        ShareArea.Whole -> if (view.step > 1) View.whole(view.grid) else view
        is ShareArea.Meso -> if (area.sector.name == "CONUS") View.whole(view.grid) else area.sector.view(core, view.grid)
        ShareArea.Screen -> {
            val (l, t, r, b) = viewport.toList()
            val c0 = view.col0 + l * view.cols
            val c1 = view.col0 + r * view.cols
            val r0 = view.row0 + (1f - b) * view.rows      // image top is north
            val r1 = view.row0 + (1f - t) * view.rows
            val g = view.grid
            val col0 = floor(c0).toInt().coerceIn(0, g.columns - 2)
            val row0 = floor(r0).toInt().coerceIn(0, g.rows - 2)
            val cols = (kotlin.math.ceil(c1).toInt() - col0).coerceIn(2, g.columns - col0)
            val rows = (kotlin.math.ceil(r1).toInt() - row0).coerceIn(2, g.rows - row0)
            View("Screen area", col0, row0, cols, rows, (1800f / cols).toInt().coerceIn(1, if (g == Grid.REFS) 8 else 32), 1, g)
        }
    }

    /**
     * Renders every forecast time of the current product / sector into an MP4 and returns the file. [onProgress] gets (done, total).
     * Frames come from the HTTP cache when the loop has been played.
     */
    suspend fun buildLoopFile(area: ShareArea, size: VideoSize, onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.Default) {
        val s = store ?: return@withContext null
        val st = _state.value
        val product = st.product ?: return@withContext null
        val cycle = st.cycle ?: return@withContext null
        val window = loopWindow(st.times.size, st.loopLength)
        val n = window.count()
        if (n < 2) return@withContext null
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "spc_refs_loop_${product.id.filter { it.isLetterOrDigit() || it == '_' }.take(40)}_${cycle.label.filter { it.isDigit() }}.mp4")
        var exporter: LoopExporter? = null
        val v = resolveView(area)
        try {
            for ((k, t) in window.withIndex()) {
                val data = repo.readFrame(s, product, t, st.member)
                val style = withAutoStops(product.style, data)
                val map = areaBitmap(area, product, style, data, st, v)
                val hour = (st.times[t] - cycle.epochSeconds) / 3600
                val header = "Run ${cycle.label}   Valid ${formatUtc(st.times[t])}  (f${hour.toString().padStart(2, '0')})   Data: NOAA/NWS Storm Prediction Center (experimental)"
                var picture = renderer.shareImage(map, product, style, header, st.members)
                val (sw, sh) = size.scaled(picture.width, picture.height)
                if (sw != picture.width) picture = Bitmap.createScaledBitmap(picture, sw, sh, true)
                if (exporter == null) exporter = LoopExporter(file, picture.width, picture.height, size.bitrate(picture.width, picture.height, 4))
                exporter.add(picture, last = k == n - 1)
                onProgress(k + 1, n)
            }
            exporter?.finish()
            file
        } catch (e: Exception) {
            exporter?.abort()
            throw e
        }
    }

    /** The map picture of an export: a flat grid window, or the globe as turned now at export size. */
    private fun areaBitmap(area: ShareArea, product: Product, style: Style, data: FloatArray, st: UiState, v: View = resolveView(area)): Bitmap =
        if (area == ShareArea.Globe && globeW > 0) {
            val size = 1500
            val h = size * globeH / globeW
            globeRenderer.render(product.grid, data, style, st.members, st.cam.copy(radius = st.cam.radius * size / globeW), size, h, 1, dark = false)
        } else renderer.render(product, style, data, st.members, v, dark = false)

    /** Writes the shareable picture (header, map, color key) and returns the file, or null when there is nothing to share. */
    suspend fun buildShareFile(area: ShareArea = ShareArea.Whole): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val product = st.product ?: return@withContext null
        val data = values ?: return@withContext null
        val cycle = st.cycle ?: return@withContext null
        val style = withAutoStops(product.style, data)
        val map = areaBitmap(area, product, style, data, st)
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
