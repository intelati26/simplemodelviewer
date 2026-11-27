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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import simplemodelview.Cycle
import simplemodelview.CycleStore
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.globeForward
import simplemodelview.globeInverse
import simplemodelview.BackupPlace
import simplemodelview.Place
import simplemodelview.loopWindowAnchored
import simplemodelview.nextPlayable
import simplemodelview.VideoSize
import simplemodelview.SettingsBackup
import simplemodelview.isValidEmail
import simplemodelview.Product
import simplemodelview.decodePlaces
import simplemodelview.placeLabel
import simplemodelview.RefsException
import simplemodelview.Sector
import simplemodelview.WindProducts
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

data class Settings(val wifiOnlyLoop: Boolean = false, val dataSaver: Boolean = false, val cacheMegabytes: Int = 150, val contactEmail: String = "", val contours: Boolean = true, val minimalUi: Boolean = false,
    val barbDensity: Int = DEFAULT_BARB_DENSITY, val counties: Int = 0, val optionsInExport: Boolean = false, val gfsCrop: Boolean = true, val showTimings: Boolean = false) {
    /** What an export is drawn with: the screen's wind barb spacing and county lines only when [optionsInExport] is on, else the defaults. */
    fun forExport(): Settings = if (optionsInExport) this else copy(barbDensity = DEFAULT_BARB_DENSITY, counties = 0)
    fun displayDiffers(o: Settings) = barbDensity != o.barbDensity || counties != o.counties
}

const val DEFAULT_BARB_DENSITY = 3

fun Settings.withDisplayPrefs(prefs: android.content.SharedPreferences) =
    copy(barbDensity = prefs.getInt("barb_density", DEFAULT_BARB_DENSITY).coerceIn(1, 5), counties = prefs.getInt("counties", 0).coerceIn(0, 2), optionsInExport = prefs.getBoolean("options_in_export", false), gfsCrop = prefs.getBoolean("gfs_crop", true), showTimings = prefs.getBoolean("show_timings", false))

fun android.content.SharedPreferences.Editor.putDisplay(s: Settings): android.content.SharedPreferences.Editor =
    putInt("barb_density", s.barbDensity).putInt("counties", s.counties).putBoolean("options_in_export", s.optionsInExport).putBoolean("gfs_crop", s.gfsCrop).putBoolean("show_timings", s.showTimings)

data class UiState(
    val detail: DetailLayer? = null,           // a finer picture of the zoomed-in part, drawn after the zoom settles
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
    val focus: simplemodelview.MapFocus? = null,      // the part of the next map picture to zoom to (set on leaving the globe), cleared once applied
    val fileTime: Long? = null,                  // when the shown frame's file was uploaded (server Last-Modified, epoch s)
    val runComplete: Long? = null,               // when the run's metadata was last written: the run is complete about then
    val contactPrompt: Boolean = false,          // the "please enter your email" dialog is showing
    val notice: String? = null,                  // a short message over a working map (the error field replaces the map)
    val loopLength: Int = 0,                     // how many frames Play and a loop video cover (the first N); 0 = all
    val loopTimes: Pair<Long, Long>? = null,     // the loop as a range of valid times chosen on the time bar (overrides the length / span below)
    val windLevel: Int = 500,                    // the Heights & Winds product: the level (mb) and the key of what is in color under it
    val windColor: String = "speed",
    val other: Product? = null,                  // the product shown before this one (the compare shows it under this one)
    val compare: Boolean = false,
    val activePane: Int = 0,                     // the compare pane the product controls change: 0 above, 1 below (a tap on a pane selects it)
    val paneB: List<Bitmap>? = null,             // the other product's own field and, over it, the shared lines
    val titleB: String? = null,
    val styleB: Style? = null,
) {
    val validLabel: String
        get() = times.getOrNull(timeIndex)?.let { formatUtc(it) } ?: ""
    val forecastHour: Int?
        get() = cycle?.let { c -> times.getOrNull(timeIndex)?.let { ((it - c.epochSeconds) / 3600).toInt() } }
}

/** The level of the synoptic set up the extended point view opens. */
private const val WIND_LEVEL_FOR_SETUP = 500

/** The member the member viewer opens on: the RRFS control member (shown as "RRFS Ctl"). */
private const val DEFAULT_MEMBER = "RRFS"

class RefsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val handoff = GlobeHandoff(core)
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.repo
    private val lines = AppContainer.lines
    private val renderer = MapRenderer(core, lines)
    private val globeRenderer = GlobeRenderer(core, lines)
    private val globe = GlobeDriver(viewModelScope, { renderGlobeNow(it) }) { turn -> update { it.copy(cam = turn(it.cam)) } }
    private val globeW get() = globe.w
    private val globeH get() = globe.h

    private var store: CycleStore? = null
    private var values: FloatArray? = null
    private var view: View = View.CONUS
    private var dark = false
    private var viewport = floatArrayOf(0f, 0f, 1f, 1f)   // visible part of the picture as fractions: left, top, right, bottom
    private var frameJob: Job? = null
    private var playJob: Job? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        UiState(
            favorites = migrateWindFavorites(prefs.getStringSet("favorites", emptySet()).orEmpty()),
            settings = Settings(prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(), prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false)).withDisplayPrefs(prefs),
            places = decodePlaces(prefs.getString("places", "").orEmpty()),
            sector = Sector.all.firstOrNull { it.name == prefs.getString("sector", null) } ?: Sector.all[0],
            loopLength = prefs.getInt("loop_frames", 0),
            windLevel = prefs.getInt("wind_level", 500).takeIf { it in WindProducts.levels || it == WindProducts.STACK } ?: 500,
            windColor = prefs.getString("wind_color", "speed")?.takeIf { k -> WindProducts.colors.any { it.key == k } } ?: "speed",
        )
    )
    val state: kotlinx.coroutines.flow.StateFlow<UiState> = _state

    // the saved places are one list for every page: this page's copy follows it
    init { viewModelScope.launch { AppContainer.places.places.collect { p -> update { it.copy(places = p) } } } }

    private inline fun update(f: (UiState) -> UiState) { _state.value = f(_state.value) }

    /** The MRMS page may have changed favorites, places or settings while this page was away. */
    fun reloadFromPrefs() = update {
        it.copy(
            favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(), places = decodePlaces(prefs.getString("places", "").orEmpty()),
            settings = Settings(prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(), prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false)).withDisplayPrefs(prefs),
        )
    }

    private val outlook = AppContainer.outlook

    // the extended point view asks for the synoptic set up at a valid time: Heights & Winds (500 mb) at the time step nearest it
    private var wantedValid: Long? = null
    private var wantWind = false
    private var wantedCycle: Cycle? = null

    /**
     * Shows Heights & Winds at 500 mb at the time step nearest [valid] (epoch seconds), from [run] when it is given (the one the extended view found to have the time);
     * done when the run is open, otherwise as soon as it is.
     */
    fun showValid(valid: Long, run: Cycle? = null) {
        wantedValid = valid
        wantWind = true
        stopPlaying()
        if (run != null && run != _state.value.cycle) {
            wantedCycle = run
            if (store != null) viewModelScope.launch {
                update { it.copy(busy = true, error = null) }
                try { wantedCycle = null; openCycle(run) } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, error = e.message) } }
            }
            return
        }
        if (store != null && _state.value.products.isNotEmpty()) pickWindLevel(WIND_LEVEL_FOR_SETUP)
    }

    init {
        load(); promptForContact()
        // a newly read (or changed) SPC outlook is drawn at once
        viewModelScope.launch { overlayChanges(lightning = false).collect { if (values != null) rerender() } }
    }

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
                    openCycle(wantedCycle?.takeIf { it in cycles }?.also { wantedCycle = null } ?: cycles.first())
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
        val wind = if (!wantWind) null else {
            val color = WindProducts.colors.firstOrNull { it.key == _state.value.windColor } ?: WindProducts.colors.first()
            (opened.products.firstOrNull { it.id == WindProducts.id(WIND_LEVEL_FOR_SETUP, color) } ?: opened.products.firstOrNull { it.id == WindProducts.id(WIND_LEVEL_FOR_SETUP) }).also { if (it != null) wantWind = false }
        }
        val product = wind ?: opened.products.firstOrNull { it.id == last }
            ?: opened.products.firstOrNull { it.id == "cref" } ?: opened.products.firstOrNull { it.id == "cref_max" } ?: opened.products.first()   // (starts at F000 / F001, unlike the 4-h products)
        val members = repo.members(opened)
        // a (new) run always opens at its earliest time step: F000 / F001, not wherever the previous run's slider was
        // the member shown is remembered by name (default: the RRFS control member, "RRFS Ctl"), so it survives a new run
        val wanted = prefs.getString("member_name", DEFAULT_MEMBER)
        val memberIndex = members.indexOf(wanted).takeIf { it >= 0 } ?: members.indexOf(DEFAULT_MEMBER).takeIf { it >= 0 } ?: 0
        update { it.copy(cycle = cycle, products = opened.products, members = members, member = memberIndex, timeIndex = 0) }
        selectProductInternal(product, keepHour = false)
    }

    /** The virtual product of the level and color chosen with the chips, when the cycle has it. */
    private fun windProduct(level: Int, colorKey: String): Product? {
        val color = WindProducts.colors.firstOrNull { it.key == colorKey } ?: return null
        val id = WindProducts.id(level, color)
        return _state.value.products.firstOrNull { it.id == id }
    }

    fun pickWindLevel(level: Int) {
        val st = _state.value
        val product = windProduct(level, st.windColor) ?: windProduct(level, "speed") ?: return
        prefs.edit().putInt("wind_level", level).apply()
        update { it.copy(windLevel = level, windColor = WindProducts.parse(product.id)?.second?.key ?: it.windColor) }
        pickProduct(product)
    }

    fun pickWindColor(colorKey: String) {
        val st = _state.value
        val product = windProduct(st.windLevel, colorKey) ?: return
        prefs.edit().putString("wind_color", colorKey).apply()
        update { it.copy(windColor = colorKey) }
        pickProduct(product)
    }

    /** Favorites made before the wind products were folded into one picker entry now point at that entry. */
    private fun migrateWindFavorites(favorites: Set<String>): Set<String> {
        if (favorites.none { WindProducts.isWind(it) }) return favorites
        val migrated = favorites.filter { !WindProducts.isWind(it) }.toSet() + WindProducts.CHOOSER_ID
        prefs.edit().putStringSet("favorites", migrated).apply()
        return migrated
    }

    fun pickProduct(picked: Product) {
        // the picker's single Heights & Winds entry opens the level and color last used
        val product = if (picked.id == WindProducts.CHOOSER_ID) windProduct(_state.value.windLevel, _state.value.windColor) ?: windProduct(_state.value.windLevel, "speed") ?: return else picked
        val cur = _state.value
        if (cur.compare && cur.activePane == 1 && cur.other != null) {
            // the pane below: only that picture is replaced (the time, zoom and the other pane stay)
            update { it.copy(other = product, paneB = null, titleB = null, styleB = null) }
            viewModelScope.launch { rerender() }
            return
        }
        stopPlaying()
        if (!cur.compare) cur.product?.takeIf { it.id != product.id }?.let { before -> update { it.copy(other = before) } }
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
        WindProducts.parse(product.id)?.let { (level, color) -> update { it.copy(windLevel = level, windColor = color.key) } }
        val times = repo.validTimes(s, product)
        val before = _state.value
        val hour = if (keepHour) before.forecastHour else null
        val wanted = wantedValid
        // a run that is still being written has not reached the time asked for: the run before it (and so on back) is the one that shows it
        if (wanted != null && times.isNotEmpty() && times.last() < wanted - 3599) {
            val older = _state.value.cycles.dropWhile { it != s.cycle }.drop(1).firstOrNull()
            if (older != null) {
                wantWind = true
                openCycle(older)
                return
            }
        }
        val index = if (wanted != null && times.isNotEmpty()) {
            wantedValid = null
            times.indices.minByOrNull { kotlin.math.abs(times[it] - wanted) } ?: 0
        } else if (hour == null) 0 else times.indexOfFirst { (it - s.cycle.epochSeconds) / 3600 == hour.toLong() }.coerceAtLeast(0)
        update { it.copy(product = product, times = times, timeIndex = index) }
        loadFrame()
    }

    /** Shows the previous product under this one (or back to one picture). */
    fun setCompare(on: Boolean) {
        if (_state.value.compare == on) return
        update { it.copy(compare = on, paneB = null, titleB = null, styleB = null, activePane = 0) }
        if (on && _state.value.globe) { showMapHere(); return }      // the globe has no compare: on to the flat map of the region it is looking at
        if (on) viewModelScope.launch { rerender() }
    }

    /** The compare's second picture: the other product at the time nearest the one shown, as its own field only, with the lines and alerts over it once. */
    private suspend fun computePaneB(product: Product, data: FloatArray, v: View, valid: Long?) {
        val st = _state.value
        val s = store
        val b = st.other
        if (!st.compare || st.playing || s == null || b == null || valid == null) { if (st.paneB != null) update { it.copy(paneB = null, titleB = null, styleB = null) }; return }
        try {
            val times = repo.validTimes(s, b)
            val idx = times.indices.minByOrNull { kotlin.math.abs(times[it] - valid) }?.takeIf { kotlin.math.abs(times[it] - valid) <= 1800 }
            if (idx == null) { update { it.copy(paneB = null, notice = "${b.title} has no time near this one.") }; return }
            val frame = repo.readFrameTimed(s, b, idx, st.member)
            val sector = st.sector
            val settings = st.settings
            val (over, field, style) = withContext(Dispatchers.Default) {
                val bv = if (b.grid == product.grid) v else if (sector.name == "CONUS") View.whole(b.grid) else sector.view(core, b.grid)
                val style = withAutoStops(b.style, frame.values)
                Triple(
                    renderer.render(product, product.style, data, st.members, v, dark, alerts = outlookDraws(core, outlook, v, valid), counties = settings.counties, part = RenderPart.Over),
                    renderer.render(b, style, frame.values, st.members, bv, dark, contours = settings.contours, barbDensity = settings.barbDensity, part = RenderPart.Field),
                    style,
                )
            }
            update { it.copy(paneB = listOf(field, over), titleB = b.title, styleB = style) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(paneB = null, notice = e.message) } }
    }

    /** The product [delta] places along in the picker's order (the arrows beside the product chip). */
    /** The product the controls are working on: the one above, or in a compare the one of the selected pane. */
    fun activeProduct(st: UiState = _state.value): Product? = if (st.compare && st.activePane == 1) st.other ?: st.product else st.product

    fun selectPane(i: Int) { if (_state.value.activePane != i) update { it.copy(activePane = i) } }

    /** In a compare: the two panes change places (the one above becomes the one below). */
    fun swapPanes() {
        val st = _state.value
        val o = st.other ?: return
        val mine = st.product ?: return
        stopPlaying()
        update { it.copy(other = mine, paneB = null, titleB = null, styleB = null, busy = true, error = null) }
        viewModelScope.launch {
            try { selectProductInternal(o, keepHour = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun stepProduct(delta: Int) {
        val st = _state.value
        val at = st.products.indexOfFirst { it.id == activeProduct(st)?.id }
        st.products.getOrNull(at + delta)?.let { pickProduct(it) }
    }

    /**
     * The frame that is valid now: from the newest run (opened when another run is shown), the time step nearest the current time (the last one
     * if the run has not reached it, the first if it is not due yet).
     */
    fun goToNow() {
        stopPlaying()
        viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try {
                val newest = _state.value.cycles.firstOrNull()
                if (newest == null) { update { it.copy(busy = false) }; return@launch }
                if (_state.value.cycle != newest) openCycle(newest)
                val now = System.currentTimeMillis() / 1000
                val times = _state.value.times
                val index = times.indices.minByOrNull { kotlin.math.abs(times[it] - now) } ?: 0
                update { it.copy(timeIndex = index, busy = false) }
                loadFrame()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message) }
            }
        }
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
        val frame = Timings.timed("data") { repo.readFrameTimed(s, product, st.timeIndex, st.member) }
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
        val valid = _state.value.times.getOrNull(_state.value.timeIndex)
        outlook.want(valid)
        val (bitmap, style, v) = withContext(Dispatchers.Default) {
            val g = product.grid
            val v = if (sector.name == "CONUS") View.whole(g).decimated(if (_state.value.settings.dataSaver && g == Grid.REFS) 2 else 1) else sector.view(core, g)
            val style = withAutoStops(product.style, data)
            Triple(renderer.render(product, style, data, members, v, dark, alerts = outlookDraws(core, outlook, v, valid), contours = _state.value.settings.contours, counties = _state.value.settings.counties, barbDensity = _state.value.settings.barbDensity), style, v)
        }
        view = v
        update { it.copy(map = bitmap, style = style, busy = false, detail = null, focus = handoff.takeFocus(v)) }
        computePaneB(product, data, v, valid)
        scheduleDetail()
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    // ---- the globe ----
    /** Turns the globe view on or off (a sector choice turns it off). */
    fun setGlobe(on: Boolean) {
        if (_state.value.globe == on) return
        val enterBox = if (on) handoff.boxOf(view, viewport) else null
        if (on) handoff.cancelLeave()
        handoff.enter = if (globeW == 0) enterBox else null
        update { it.copy(globe = on, cam = if (enterBox != null && globeW > 0) handoff.fit(enterBox, globeW, globeH) else it.cam, crosshair = null, readout = null, tapLatLon = null) }
        if (values != null) viewModelScope.launch { rerender() }
    }

    /** Back to the flat map from the globe: the sector that holds what the globe shows, zoomed to it. */
    fun showMapHere() {
        val cam = _state.value.cam
        if (globeW == 0) return
        handoff.leaveFrom(cam, globeW, globeH)
        val sector = simplemodelview.sectorForGlobe(cam, globeW, globeH)
        prefs.edit().putString("sector", sector.name).apply()
        update { it.copy(sector = sector, globe = false, crosshair = null, readout = null, tapLatLon = null) }
        viewModelScope.launch { rerender() }
    }

    fun focusApplied() = update { it.copy(focus = null) }

    /** The globe's drawing area in pixels; the first call also fits the globe to the screen. */
    fun setGlobeSize(w: Int, h: Int) = globe.setSize(w, h, onFirst = { w, h -> update { it.copy(cam = handoff.enter?.let { b -> handoff.fit(b, w, h) } ?: it.cam.copy(radius = 0.49 * minOf(w, h))) } }, after = { handoff.enter = null }, ready = { _state.value.globe && values != null })

    fun globeGesture(dx: Float, dy: Float, zoom: Float) = globe.gesture(dx, dy, zoom)

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

    private fun requestGlobe(coarse: Boolean) = globe.request(coarse)

    private suspend fun renderGlobeNow(stride: Int) {
        val product = _state.value.product ?: return
        val data = values ?: return
        if (globeW == 0 || data.size < product.grid.cells) return
        val st = _state.value
        val valid = st.times.getOrNull(st.timeIndex)
        outlook.want(valid)
        val (bitmap, style) = withContext(Dispatchers.Default) {
            val style = withAutoStops(product.style, data)
            val alerts = outlookDrawsOn(outlook, valid) { lat, lon -> globeForward(st.cam, globeW, globeH, lat, lon) }
            globeRenderer.render(product.grid, data, style, st.members, st.cam, globeW, globeH, stride, dark, product, st.settings.contours, st.settings.barbDensity, alerts = alerts, counties = st.settings.counties) to style
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
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, data, it.members, column, row) + (outlook.readoutAt(lat, lon, _state.value.times.getOrNull(_state.value.timeIndex))?.let { t -> "   \u00B7 $t" } ?: ""), tapLatLon = lat to lon) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null) }

    // ---- settings ----
    fun setSettings(settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).putBoolean("contours", settings.contours).putBoolean("minimal_ui", settings.minimalUi).putDisplay(settings).apply()
        val redraw = settings.dataSaver != _state.value.settings.dataSaver || settings.contours != _state.value.settings.contours || settings.displayDiffers(_state.value.settings)
        update { it.copy(settings = settings) }
        if (redraw && values != null) viewModelScope.launch { rerender() }
    }

    // ---- settings export / import ----
    private fun backup(): SettingsBackup {
        val st = _state.value
        return SettingsTransfer.withPrefs(SettingsBackup(
            wifiOnlyLoop = st.settings.wifiOnlyLoop, dataSaver = st.settings.dataSaver, cacheMegabytes = st.settings.cacheMegabytes,
            contactEmail = st.settings.contactEmail, favorites = st.favorites.sorted(),
            places = st.places.map { BackupPlace(it.name, it.lat, it.lon) },
            product = st.product?.id ?: prefs.getString("product", null), sector = st.sector.name,
        ), prefs)
    }

    /** Writes the settings as JSON to a file the user chose (the system "save as" picker). */
    suspend fun exportSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val ok = writeSettingsFile(resolver, uri, backup())
        update { it.copy(notice = if (ok) "Settings exported: ${backup().favorites.size} favorites, ${backup().places.size} places." else "Could not write the settings file.") }
    }

    /** Reads a settings file the user chose, validates it and applies it. Nothing changes if it is not a simplemodelview backup. */
    /** True when display or source choices were imported: the app restarts to take them up. */
    suspend fun importSettings(resolver: android.content.ContentResolver, uri: android.net.Uri): Boolean {
        RainHistory.importIfArchive(resolver, uri)?.let { note -> update { it.copy(notice = note) }; return false }      // (a rain history file from the export: no restart)
        val b = readSettingsFile(resolver, uri)
        if (b == null) { update { it.copy(notice = "That file is not a simplemodelview settings file.") }; return false }
        val current = _state.value.settings
        setSettings(current.copy(wifiOnlyLoop = b.wifiOnlyLoop, dataSaver = b.dataSaver, cacheMegabytes = b.cacheMegabytes, contactEmail = b.contactEmail.ifEmpty { current.contactEmail }))
        prefs.edit().putStringSet("favorites", b.favorites.toSet()).apply()
        AppContainer.places.replaceAll(b.places.map { Place(it.name, it.lat, it.lon) })
        update { it.copy(favorites = b.favorites.toSet(), places = b.places.map { p -> Place(p.name, p.lat, p.lon) }) }
        if (isValidEmail(_state.value.settings.contactEmail)) dismissContactPrompt()
        val extras = SettingsTransfer.apply(prefs, b)
        Sector.all.firstOrNull { it.name == b.sector }?.let { setGlobe(false); pickSector(it) }
        val product = b.product?.let { id -> _state.value.products.firstOrNull { it.id == id } }
        if (product != null) pickProduct(product) else b.product?.let { prefs.edit().putString("product", it).apply() }
        update { it.copy(notice = "Settings imported: ${b.favorites.size} favorites, ${b.places.size} places" + (if (extras > 0) ", $extras display and source choices" else "") + ".") }
        return extras > 0
    }

    // ---- places ----


    /** The shown field's value at a place, as a line of text, without moving the map or the crosshair. */
    fun readAt(lat: Double, lon: Double): String {
        val product = _state.value.product ?: return "Nothing is shown yet"
        val data = values ?: return "Nothing is shown yet"
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, lat, lon)
        val column = Math.round(c).toInt(); val row = Math.round(r).toInt()
        return if (column !in 0 until grid.columns || row !in 0 until grid.rows) "${placeLabel(lat, lon)}   outside the REFS area" else readout(core, product, data, _state.value.members, column, row)
    }


    /** Puts the crosshair on a saved place (switching to CONUS when the current sector does not contain it). */
    /** [follow]: a live fix; it only moves the crosshair, and never reloads another sector to reach it. */
    fun goToPlace(place: Place, follow: Boolean = false) {
        val (c, r) = core.latLonToGrid(_state.value.product?.grid ?: Grid.REFS, place.lat, place.lon)
        var inView = c >= view.col0 && c <= view.col0 + view.cols - 1 && r >= view.row0 && r <= view.row0 + view.rows - 1
        if (follow && !inView) return
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


    /**
     * The contact address is optional but wanted (it goes into the User-Agent): without one, ask again at every launch and when the
     * app comes back after more than 30 minutes. Never blocks; "Not now" simply closes it.
     */
    fun promptForContact() { if (AppContainer.contactPromptDue(_state.value.settings.contactEmail)) update { it.copy(contactPrompt = true) } }

    fun dismissContactPrompt() = update { it.copy(contactPrompt = false) }

    /** Estimated size of a loop video of the current product and area, for the share dialog. */
    fun estimateLoopMegabytes(area: ShareArea, size: VideoSize): Double {
        val v = resolveView(area)
        val pictureW = if (area == ShareArea.Globe && globeW > 0) GLOBE_LOOP_SIDE else v.pixelWidth
        val pictureH = if (area == ShareArea.Globe && globeW > 0) GLOBE_LOOP_SIDE else v.pixelHeight
        return VideoSize.estimateMegabytes(size, pictureW, pictureH + pictureW / 6, loopWindowFor(_state.value).count())   // + header and legend
    }

    fun reportNotice(text: String) = update { it.copy(notice = text) }

    fun dismissNotice() = update { it.copy(notice = null) }

    fun dismissNewerCycle() = update { it.copy(newerCycle = null) }

    /** Chooses how many frames Play (and a loop video) covers: the first [frames], or 0 for the whole run. Restarts a running loop. */
    fun setLoopLength(frames: Int) {
        prefs.edit().putInt("loop_frames", frames).apply()
        val wasPlaying = _state.value.playing
        if (wasPlaying) stopPlaying()
        update { it.copy(loopLength = frames, loopTimes = null) }
        if (wasPlaying) startPlaying()
    }

    /** The loop as a range of valid times chosen on the time bar (null: the page's usual loop). It follows the times, so a new scan or run does not shift it. */
    fun setLoopTimes(range: Pair<Long, Long>?) {
        val wasPlaying = _state.value.playing
        if (wasPlaying) stopPlaying()
        update { it.copy(loopTimes = range) }
        if (wasPlaying) startPlaying()
    }

    /** The frames Play and a loop video cover: the range chosen on the time bar, else the first N (or from the one shown). */
    fun loopWindowFor(st: UiState): IntRange =
        st.loopTimes?.let { (a, b) -> simplemodelview.loopIndices(st.times, a, b) }
            ?: loopWindowAnchored(st.times.size, st.loopLength, st.timeIndex, AppContainer.loopFromShown.value)

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
        if (st.settings.wifiOnlyLoop && AppContainer.isMetered()) {
            update { it.copy(notice = "Loops are set to download on Wi-Fi only. Connect to Wi-Fi or change this in Settings.") }
            return
        }
        playJob = viewModelScope.launch {
            update { it.copy(playing = true, prefetch = 0f) }
            val window = loopWindowFor(st)
            outlook.wantAll(window.map { st.times[it] })      // the outlook of every day the loop reaches, ready when the loop gets there
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

    /** The UI reports which part of the picture is on screen (after pinch-zoom / pan): a finer picture of it is drawn once the zoom settles. */
    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewport = clampedViewport(left, top, right, bottom)
        scheduleDetail()
    }

    private var detailJob: Job? = null

    /** After the zoom has rested a moment, only the cells on screen are drawn again, finer (contours, barbs and lines at the finer scale too), over the overview. */
    private fun scheduleDetail() {
        detailJob?.cancel()
        val st = _state.value
        val product = st.product
        val data = values
        val v = view
        val (l, t, r, b) = viewport.toList()
        val window = if (product == null || data == null || st.globe || st.playing || data.size < product.grid.cells) null else detailWindow(v, l, t, r, b)
        if (window == null) { if (st.detail != null) update { it.copy(detail = null) }; return }
        detailJob = viewModelScope.launch {
            delay(400)
            try {
                val valid = st.times.getOrNull(st.timeIndex)
                val settings = _state.value.settings
                val style = withAutoStops(product!!.style, data!!)
                val bitmap = withContext(Dispatchers.Default) {
                    renderer.render(product, style, data, st.members, window.view, dark, alerts = outlookDraws(core, outlook, window.view, valid), contours = settings.contours, counties = settings.counties, barbDensity = settings.barbDensity)
                }
                if (view === v && values === data && _state.value.product == product) update { it.copy(detail = DetailLayer(bitmap, window.left, window.top, window.right, window.bottom, 3.0)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
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
        val window = loopWindowFor(st)
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
                outlook.ensure(st.times[t])
                val map = areaBitmap(area, product, style, data, st, v, st.times[t], GLOBE_LOOP_SIDE)
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
    private fun areaBitmap(area: ShareArea, product: Product, style: Style, data: FloatArray, st: UiState, v: View = resolveView(area), valid: Long? = st.times.getOrNull(st.timeIndex), side: Int = GLOBE_EXPORT_SIDE): Bitmap =
        if (area == ShareArea.Globe && globeW > 0) {
            val cam = st.cam.copy(radius = st.cam.radius * side / minOf(globeW, globeH))       // the globe as turned now, on a square at export size
            globeRenderer.render(
                product.grid, data, style, st.members, cam, side, side, 1, dark = false, product = product, contours = st.settings.contours, barbDensity = st.settings.forExport().barbDensity,
                alerts = outlookDrawsOn(outlook, valid) { la, lo -> globeForward(cam, side, side, la, lo) }, counties = st.settings.forExport().counties,
            )
        } else renderer.render(product, style, data, st.members, v, dark = false, alerts = outlookDraws(core, outlook, v, valid), contours = st.settings.contours, counties = st.settings.forExport().counties, barbDensity = st.settings.forExport().barbDensity)

    /** Writes the shareable picture (header, map, color key) and returns the file, or null when there is nothing to share. */
    /** The compare's second picture for an export: the other product at the time nearest this one, drawn in full on [area]. Null: none near. */
    private suspend fun areaBitmapB(area: ShareArea, st: UiState, product: Product, valid: Long?): Triple<Bitmap, Product, Style>? {
        val s = store ?: return null
        val b = st.other ?: return null
        if (valid == null || area == ShareArea.Globe) return null
        val times = repo.validTimes(s, b)
        val idx = times.indices.minByOrNull { kotlin.math.abs(times[it] - valid) }?.takeIf { kotlin.math.abs(times[it] - valid) <= 1800 } ?: return null
        val frame = repo.readFrameTimed(s, b, idx, st.member)
        val style = withAutoStops(b.style, frame.values)
        val v = if (b.grid == product.grid) resolveView(area) else View.whole(b.grid)
        return Triple(areaBitmap(area, b, style, frame.values, st, v, valid), b, style)
    }

    suspend fun buildShareFile(area: ShareArea = ShareArea.Whole, size: VideoSize? = null): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val product = st.product ?: return@withContext null
        val data = values ?: return@withContext null
        val cycle = st.cycle ?: return@withContext null
        val style = withAutoStops(product.style, data)
        outlook.ensure(st.times.getOrNull(st.timeIndex))
        val map = areaBitmap(area, product, style, data, st)
        val header = "Run ${cycle.label}   Valid ${st.validLabel}" + (st.forecastHour?.let { "  (f${it.toString().padStart(2, '0')})" } ?: "") +
            "   Data: NOAA/NWS Storm Prediction Center (experimental)"
        val b = if (st.compare && st.other != null) areaBitmapB(area, st, product, st.times.getOrNull(st.timeIndex)) else null
        val image = if (b != null) renderer.shareCompareImage(map, b.first, product.title, st.other!!.title, product, style, b.second, b.third, header, size?.width ?: 0)
            else renderer.shareImage(map, product, style, header, st.members)
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, (if (b != null) "compare_" else "") + "spc_refs_${product.id.filter { it.isLetterOrDigit() || it == '_' }.take(40)}_${cycle.label.filter { it.isDigit() }}_f${st.timeIndex}.png")
        image.savePng(file)
        file
    }
}
