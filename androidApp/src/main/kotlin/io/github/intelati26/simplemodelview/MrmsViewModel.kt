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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import simplemodelview.Underlay
import simplemodelview.SurfacePick
import simplemodelview.BackupPlace
import simplemodelview.FrameTarget
import simplemodelview.Grid
import simplemodelview.RefsException
import simplemodelview.gridCoverBox
import simplemodelview.lambertCellMeters
import simplemodelview.GlobeCamera
import simplemodelview.MrmsCatalog
import simplemodelview.MrmsFile
import simplemodelview.MrmsFrame
import simplemodelview.MrmsProduct
import simplemodelview.Place
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.SettingsBackup
import simplemodelview.Style
import simplemodelview.Units
import simplemodelview.styleIn
import simplemodelview.VideoSize
import simplemodelview.View
import simplemodelview.decodePlaces
import simplemodelview.formatClock
import simplemodelview.loopWindowEndingAt
import simplemodelview.loopWindowFromShown
import simplemodelview.AlertKind
import simplemodelview.AlertShape
import simplemodelview.OverlayLayer
import simplemodelview.shapesAt
import simplemodelview.OverlayPresets
import simplemodelview.OverlaySpec
import simplemodelview.decodeOverlays
import simplemodelview.encodeOverlays
import simplemodelview.nearestFile
import simplemodelview.overlayName
import simplemodelview.overlayIsMarker
import simplemodelview.overlayMarkerColor
import simplemodelview.overlayMarkerScale
import simplemodelview.overlayIsSparse
import simplemodelview.overlayThresholdChoices
import simplemodelview.overlayToleranceSeconds
import simplemodelview.defaultOverlayThresholdIndex
import simplemodelview.shownValue
import simplemodelview.fixed
import simplemodelview.nextPlayable
import simplemodelview.globeForward
import simplemodelview.globeInverse
import simplemodelview.isValidEmail
import simplemodelview.mrmsHasHalo
import simplemodelview.mrmsStep
import simplemodelview.placeLabel
import simplemodelview.readout
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/** A finer picture of the part of the map that is zoomed into, drawn over the whole-sector picture; the edges are fractions of that picture (top-down). */
class DetailLayer(val bitmap: Bitmap, val left: Float, val top: Float, val right: Float, val bottom: Float, val cellKilometers: Double)

/** The MRMS page: a product, a sector (or the globe), the newest scans of that product and the one being shown. */
data class MrmsState(
    val busy: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val product: MrmsProduct = MrmsCatalog.default,
    val sector: Sector = Sector.all[0],
    val files: List<MrmsFile> = emptyList(),
    val index: Int = 0,
    val map: Bitmap? = null,
    val detail: DetailLayer? = null,
    val style: Style? = null,
    val crosshair: Pair<Float, Float>? = null,   // fractions (0..1) of the picture
    val tapLatLon: Pair<Double, Double>? = null,
    val readout: String? = null,
    val playing: Boolean = false,
    val prefetch: Float? = null,                 // 0..1 while a loop is being downloaded
    val cellKilometers: Double? = null,          // resolution of the shown picture (after any decimation)
    val globe: Boolean = false,
    val cam: GlobeCamera = GlobeCamera(38.0, -97.0, 380.0),
    val focus: simplemodelview.MapFocus? = null,      // the part of the next map picture to zoom to (set on leaving the globe), cleared once applied
    val favorites: Set<String> = emptySet(),
    val places: List<Place> = emptyList(),
    val settings: Settings = Settings(),
    val contactPrompt: Boolean = false,
    val units: Units = Units(),
    val live: Boolean = false,                   // the page is on screen and checks for newer scans by itself
    val loopLength: Int = 0,                     // how many of the newest scans Play and a loop video cover; 0 = all
    val loopTimes: Pair<Long, Long>? = null,     // the loop as a range of valid times chosen on the time bar (overrides the length / span below)
    val rotationChoice: String = OverlayPresets.DEFAULT_ROTATION,   // the product of the Rotation switch
    val hailChoice: String = OverlayPresets.DEFAULT_HAIL,   // the product of the Hail switch
    val overlays: List<OverlayLayer> = emptyList(),   // other products drawn over the first, in order (the last on top)
    val overlayFiles: Map<String, List<MrmsFile>> = emptyMap(),   // per overlay product directory: the files in the span of the radar frames
    val overlayStyles: List<Style> = emptyList(),     // as drawn (units, minimum, opacity), for the legend
    val overlayInfo: String? = null,                  // which overlay files are on the picture, or why one is missing
    val underTemp: Boolean = false,                   // the surface temperature drawn under the radar (a translucent fill)
    val underDew: Boolean = false,                    // surface dewpoint lines over the picture
    val stations: Boolean = false,                    // METAR station plots over everything (aviationweather.gov)
    val stationDensity: Int = 2,                      // 1 as before, 2 and 3 closer up and down
    val station: StationDetail? = null,               // the station tapped: its reports and chart
    val rainSince: Long? = null,                      // the rainfall total from this time to now is shown instead of the radar (epoch seconds)
    val rainInfo: String? = null,                     // the span the total covers and its sources, or what is missing
    val alertKinds: Set<AlertKind> = emptySet(),      // which alert layers are on (none by default)
    val alertShapes: List<AlertShape> = emptyList(),  // everything read for those layers
    val alertsAsOf: Long? = null,                     // when they were read (epoch seconds)
    val alertNote: String? = null,                    // why a layer could not be read
    val alertHits: List<AlertShape> = emptyList(),    // the outlines under the tapped point
    val alertImage: android.graphics.Bitmap? = null,   // the graphic of the discussion being read
    val alertDetail: Pair<String, String?>? = null,   // title and text of the alert being read (text null: loading)
) {
    val overlayName: String get() = overlayName(overlays)
    val file: MrmsFile? get() = files.getOrNull(index)
}

/** How long a layer may take before the picture so far is shown while it loads (see MrmsViewModel.showCurrent). */
private const val STAGE_WAIT_MS = 120L

/** A tapped station: its report, then its last day of reports and its 5-minute wind as they arrive. */
data class StationDetail(val metar: simplemodelview.Metar, val history: List<simplemodelview.Metar>?, val fiveMinute: List<simplemodelview.FiveMinute>?, val error: String?)

class MrmsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val handoff = GlobeHandoff(core)
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.mrms
    private val rainDays = AppContainer.rainDays
    private val alertsRepo = AppContainer.alerts
    private val outlook = AppContainer.outlook
    private val renderer = MapRenderer(core, AppContainer.lines)
    private val globeRenderer = GlobeRenderer(core, AppContainer.lines)

    private var dark = false
    private var frameJob: Job? = null
    private var detailJob: Job? = null
    private var playJob: Job? = null
    private var shown: Pair<Product, FloatArray>? = null     // what the picture shows, for the read-out
    private var shownGrid: Grid? = null                      // the grid of what the (flat or globe) picture shows
    private var view: View? = null
    private var viewport = floatArrayOf(0f, 0f, 1f, 1f)      // visible part of the flat picture: left, top, right, bottom fractions
    private var globeW = 0
    private var globeH = 0
    private var lastLoad = 0L

    // decoded windows, newest use last; the budget keeps a loop from filling the heap (4 bytes per cell)
    private val decoded = LinkedHashMap<String, MrmsFrame>(16, 0.75f, true)
    // at most a quarter of the heap the app is allowed (4 bytes a cell), and never more than 20 million cells
    private val cellBudget = minOf(20_000_000L, Runtime.getRuntime().maxMemory() / 16)

    private val _state = MutableStateFlow(
        MrmsState(
            product = withSavedLevel(MrmsCatalog.byId(prefs.getString("mrms_product", null)) ?: MrmsCatalog.default),
            sector = Sector.all.firstOrNull { it.name == prefs.getString("mrms_sector", null) } ?: Sector.all[0],
            favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(),
            rotationChoice = prefs.getString("overlay_rotation", null)?.takeIf { it in OverlayPresets.rotationChoices } ?: OverlayPresets.DEFAULT_ROTATION,
            hailChoice = prefs.getString("overlay_hail", null)?.takeIf { it in OverlayPresets.hailChoices } ?: OverlayPresets.DEFAULT_HAIL,
            places = decodePlaces(prefs.getString("places", "").orEmpty()),
            settings = readSettings(),
            units = readUnits(),
            loopLength = prefs.getInt("mrms_loop_frames", 0),
            underTemp = prefs.getBoolean("mrms_under_t", false),
            underDew = prefs.getBoolean("mrms_under_td", false),
            stations = prefs.getBoolean("mrms_stations", false),
            stationDensity = prefs.getInt("mrms_station_density", 2).coerceIn(1, 3),
        )
    )
    val state: StateFlow<MrmsState> = _state

    // the saved places are one list for every page: this page's copy follows it
    init { viewModelScope.launch { AppContainer.places.places.collect { p -> update { it.copy(places = p) } } } }

    private inline fun update(f: (MrmsState) -> MrmsState) { _state.value = f(_state.value) }

    private fun withSavedLevel(product: MrmsProduct) =
        if (product.levels.isEmpty()) product else product.copy(level = prefs.getInt("mrms_level_${product.id}", product.level).coerceIn(0, product.levels.size - 1))

    private fun readSettings() = Settings(
        prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(), prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false),
    ).withDisplayPrefs(prefs)

    private fun readUnits() = Units(prefs.getBoolean("u_metric_length", false), prefs.getBoolean("u_celsius", false), prefs.getBoolean("u_km", false))

    private fun styleOf(product: MrmsProduct) = product.styleIn(_state.value.units)

    /** Switches rain / hail (in or mm), temperature (F or C) and height (kft or km) units; the picture is redrawn from the cached data. */
    fun setUnits(units: Units) {
        prefs.edit().putBoolean("u_metric_length", units.metricLength).putBoolean("u_celsius", units.celsius).putBoolean("u_km", units.kilometers).apply()
        update { it.copy(units = units) }
        if (shown != null) showFrame()
    }

    private val dataSaver get() = prefs.getBoolean("data_saver", false)
    private val wifiOnlyLoop get() = prefs.getBoolean("wifi_only", false)

    init {
        update { it.copy(overlays = layersFrom(decodeOverlays(prefs.getString("mrms_overlays", null))), alertKinds = savedAlertKinds()) }
        load(); promptForContact()
        // a newly read (or changed) SPC outlook is drawn at once
        viewModelScope.launch { overlayChanges().collect { if (shown != null && !_state.value.playing) { if (_state.value.globe) requestGlobe(coarse = false) else showFrame() } } }
        if (_state.value.alertKinds.isNotEmpty()) viewModelScope.launch { refreshAlerts(force = true); showFrameIfNewest() }
    }

    /** The SPC page may have changed favorites, places or settings while this page was away. */
    fun reloadFromPrefs() = update {
        it.copy(favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(), places = decodePlaces(prefs.getString("places", "").orEmpty()), settings = readSettings())
    }

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
                    lastLoad = System.currentTimeMillis()
                    update { it.copy(files = files, index = files.size - 1) }
                    refreshOverlayFiles()
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

    /**
     * Coming back to the page after a while: look for newer scans. When the newest one was on screen, jump to the new newest; otherwise
     * keep the file being looked at.
     */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastLoad < 3 * 60 * 1000) return
        viewModelScope.launch { checkForNewer() }
    }

    /** One look at the server: if a newer scan exists, add it (and follow it when the newest was on screen). Never shows an error. */
    private suspend fun checkForNewer() {
        val st = _state.value
        if (st.playing || st.busy || st.error != null || st.files.isEmpty() || st.rainSince != null) return
        try {
            val files = repo.listFiles(st.product, System.currentTimeMillis() / 1000)
            lastLoad = System.currentTimeMillis()
            if (files.isEmpty() || files.last().key == st.files.last().key || _state.value.product.id != st.product.id) return
            val atNewest = st.index == st.files.size - 1
            val keep = st.file?.key
            update { it.copy(files = files, index = if (atNewest) files.size - 1 else files.indexOfFirst { f -> f.key == keep }.coerceAtLeast(0)) }
            refreshOverlayFiles()
            if (atNewest) showFrame()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* a failed check is not an error to show */ }
    }

    private var liveJob: Job? = null

    /**
     * While the page is on screen, look for newer scans by itself: about every half cadence, at least once a minute and at most every five
     * (a 2-minute product is checked each minute, an hourly one every five), so a viewer left open follows the radar.
     */
    fun setLive(on: Boolean) {
        liveJob?.cancel()
        liveJob = null
        update { it.copy(live = on) }
        if (!on) return
        liveJob = viewModelScope.launch {
            while (true) {
                delay((_state.value.product.cadenceMinutes * 30L).coerceIn(60L, 300L) * 1000)
                checkForNewer()
                AppContainer.lightning.refreshIfStale()
                AppContainer.probSevere.refreshIfStale()
                if (_state.value.alertKinds.isNotEmpty()) { refreshAlerts(force = false); showFrameIfNewest() }
            }
        }
    }

    /** The product [delta] places along in the catalog (the arrows beside the product chip). */
    fun stepProduct(delta: Int) {
        val all = MrmsCatalog.all
        val at = all.indexOfFirst { it.id == _state.value.product.id }
        all.getOrNull(at + delta)?.let { pickProduct(it) }
    }

    fun pickProduct(picked: MrmsProduct) {
        if (picked.rainDays > 0) {
            // a long rainfall total: added up from the newest whole hour back (not kept as the product to open with: it is not on the server)
            stopPlaying(); frameJob?.cancel()
            val end = System.currentTimeMillis() / 1000 / 3600 * 3600
            update { it.copy(product = picked, rainSince = end - picked.rainDays * 86400L, rainInfo = null, globe = false, crosshair = null, readout = null, tapLatLon = null) }
            showFrame()
            return
        }
        val product = withSavedLevel(picked)      // a 3D mosaic field comes back at the height last used for it
        prefs.edit().putString("mrms_product", product.id).apply()
        update { it.copy(product = product, rainSince = null, rainInfo = null, crosshair = null, readout = null, tapLatLon = null) }
        load()
    }

    /** The total on screen again from the newest data (a long total is counted back from the newest hour again). */
    fun updateRain() {
        val st = _state.value
        if (st.product.rainDays > 0) pickProduct(st.product) else setRainSince(st.rainSince)
    }

    /** Chooses the height of a 3D mosaic field (an index into its levels): the newest scans of that height's directory are loaded. */
    fun pickLevel(index: Int) {
        val current = _state.value.product
        if (current.levels.isEmpty()) return
        val level = index.coerceIn(0, current.levels.size - 1)
        if (level == current.level) return
        prefs.edit().putInt("mrms_level_${current.id}", level).apply()
        update { it.copy(product = current.copy(level = level)) }
        load()
    }

    // ---- alerts: warnings, watches and discussions drawn over the newest frame ----
    private fun savedAlertKinds(): Set<AlertKind> =
        prefs.getString("mrms_alerts", "").orEmpty().split(',').mapNotNull { n -> AlertKind.entries.firstOrNull { it.name == n } }.toSet()

    private var lastDiscussionRead = 0L

    /** Turns the alert layers on or off (all off by default); the chosen ones are read at once and refreshed while the page is on screen. */
    fun setAlertKinds(kinds: Set<AlertKind>) {
        prefs.edit().putString("mrms_alerts", kinds.joinToString(",") { it.name }).apply()
        update { it.copy(alertKinds = kinds, alertShapes = it.alertShapes.filter { s -> s.kind in kinds }, alertHits = emptyList(), alertNote = null) }
        viewModelScope.launch { refreshAlerts(force = true); showFrameIfNewest() }
    }

    // ---- warnings and watches of the past: a frame that is not the newest shows what was in force at its own time (from the archive) ----
    private val archivedAlerts = LinkedHashMap<String, List<AlertShape>>()

    private fun isNewest(file: MrmsFile): Boolean = _state.value.files.lastOrNull()?.key == file.key

    private fun archiveKey(file: MrmsFile, kinds: Set<AlertKind>) = "${file.validTime / 600}|${kinds.sorted().joinToString(",")}"        // frames ten minutes apart share one reading

    /** The warnings and watches to draw on [file]'s picture without waiting for anything: the live ones on the newest frame, an archived reading already made otherwise. */
    private fun alertShapesNow(file: MrmsFile): List<AlertShape> {
        val st = _state.value
        val kinds = st.alertKinds
        if (kinds.isEmpty()) return emptyList()
        if (isNewest(file)) return st.alertShapes.filter { it.kind in kinds }
        val past = kinds - AlertKind.Discussion
        return archivedAlerts[archiveKey(file, past)]?.filter { it.kind in past && (it.issued ?: 0L) <= file.validTime && (it.expires ?: Long.MAX_VALUE) > file.validTime } ?: emptyList()
    }

    /** [alertShapesNow], reading the archive first when this frame has no reading yet (a loop that is playing keeps to what has been read, so it does not stall). */
    private suspend fun alertShapesFor(file: MrmsFile): List<AlertShape> {
        val st = _state.value
        if (st.alertKinds.isEmpty() || isNewest(file)) return alertShapesNow(file)
        val past = st.alertKinds - AlertKind.Discussion
        if (past.isEmpty()) return emptyList()
        if (archiveKey(file, past) !in archivedAlerts && !st.playing) readArchivedAlerts(file, past)
        return alertShapesNow(file)
    }

    private suspend fun readArchivedAlerts(file: MrmsFile, kinds: Set<AlertKind>) {
        val key = archiveKey(file, kinds)
        val shapes = try { kotlinx.coroutines.withTimeoutOrNull(12_000L) { alertsRepo.archived(file.validTime, kinds) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return          // not read: nothing is drawn, and the next time asks again
        archivedAlerts[key] = shapes
        while (archivedAlerts.size > 60) archivedAlerts.remove(archivedAlerts.keys.first())
    }

    /** Before a loop plays: the archived warnings of its frames, read a few at a time, so every frame has them when it is shown. */
    private suspend fun prefetchArchivedAlerts(files: List<MrmsFile>) {
        val kinds = _state.value.alertKinds - AlertKind.Discussion
        if (kinds.isEmpty()) return
        for (file in files.filter { !isNewest(it) }.distinctBy { archiveKey(it, kinds) }) {
            if (archiveKey(file, kinds) !in archivedAlerts) readArchivedAlerts(file, kinds)
        }
    }

    private fun showFrameIfNewest() { val st = _state.value; if (st.files.isNotEmpty() && st.index == st.files.lastIndex && !st.playing) showFrame() }

    /** Reads the enabled layers: watches / warnings every call, discussions at most every five minutes (unless [force]). A failure is a note, never an error screen. */
    private suspend fun refreshAlerts(force: Boolean) {
        val kinds = _state.value.alertKinds
        if (kinds.isEmpty()) { update { it.copy(alertShapes = emptyList(), alertsAsOf = null, alertNote = null, alertHits = emptyList()) }; return }
        val now = System.currentTimeMillis() / 1000
        var note: String? = null
        val shapes = ArrayList<AlertShape>()
        if (AlertKind.Warning in kinds || AlertKind.Watch in kinds) {
            try { shapes += alertsRepo.warningsAndWatches(now).filter { it.kind in kinds } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { note = "Watches and warnings could not be read" }
        }
        if (AlertKind.Discussion in kinds) {
            if (force || now - lastDiscussionRead > 300) {
                lastDiscussionRead = now
                try { shapes += alertsRepo.discussions(now) } catch (e: CancellationException) { throw e } catch (_: Exception) { note = (note ?: "") + " Discussions could not be read" }
            } else shapes += _state.value.alertShapes.filter { it.kind == AlertKind.Discussion }
        }
        update { it.copy(alertShapes = shapes, alertsAsOf = now, alertNote = note?.trim()) }
    }

    /** Opens the full text of an alert (a warning's text is fetched from the NWS; a discussion's is already known). */
    fun showAlertText(shape: AlertShape) {
        update { it.copy(alertDetail = shape.title to shape.text, alertImage = null) }
        if (shape.imageUrl != null) viewModelScope.launch {
            val bytes = try { alertsRepo.image(shape) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return@launch
            val bitmap = withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return@launch
            update { st -> if (st.alertDetail?.first == shape.title) st.copy(alertImage = bitmap) else st }
        }
        if (shape.text != null) return
        viewModelScope.launch {
            val text = try { alertsRepo.text(shape) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            update { st -> if (st.alertDetail?.first == shape.title) st.copy(alertDetail = shape.title to (text ?: "No text is available for this alert.")) else st }
        }
    }

    fun dismissAlertText() = update { it.copy(alertDetail = null, alertImage = null) }

    /** The outlines to draw on the picture [base]: only on the newest frame (they are of now, not of an older scan). */
    private fun alertDraws(base: View, shapes: List<AlertShape>): List<AlertDraw> {
        val s = base.scale.toFloat()
        return alertDrawsWith(shapes) { lat, lon ->
            val g = core.latLonToGrid(base.grid, lat, lon)
            doubleArrayOf((g[0] + 0.5) * s, (base.rows - 0.5 - g[1]) * s)
        }
    }

    /** The same outlines for any picture ([project] puts a place on it; an outline that goes over a globe's horizon is drawn as lines). */
    private fun alertDrawsWith(shapes: List<AlertShape>, project: PlaceProjection): List<AlertDraw> {
        val drawn = shapes.map { shape ->
            val parts = shape.rings.map { projectRing(it, project) }
            val rings = parts.flatMap { it.first }
            val watch = shape.kind == AlertKind.Watch
            shape to (if (parts.all { it.second }) AlertDraw(rings, shape.color, shape.dashed, fill = watch, shade = watch, heavy = shape.kind == AlertKind.Warning)   // watches: shaded and hatched; warnings and discussions: outlines
                else AlertDraw(rings, shape.color, shape.dashed, fill = false, open = true, heavy = shape.kind == AlertKind.Warning))
        }
        // a watch comes county by county: the counties of one watch are one area, so only its outer edge gets an outline
        val (watches, others) = drawn.partition { it.first.kind == AlertKind.Watch && !it.second.open }
        val merged = watches.groupBy { (shape, _) -> shape.detailUrl ?: "${shape.title}|${shape.issued}|${shape.expires}" }.values.map { group ->
            val first = group.first().second
            AlertDraw(group.flatMap { it.second.rings }, first.color, first.dashed, fill = true, shade = true)
        }
        return merged + others.filter { it.second.rings.isNotEmpty() }.map { it.second }
    }

    // ---- overlay: other products over the first ----
    private fun overlayIndexFor(p: MrmsProduct): Int {
        val choices = overlayThresholdChoices(styleOf(p))
        if (choices.isEmpty()) return 0
        val saved = prefs.getInt("mrms_overlay_idx_${p.id}", -1)
        return if (saved in choices.indices) saved else defaultOverlayThresholdIndex(p, choices.size)
    }

    /** A layer's style as drawn: its own palette, units and colors, nothing below its minimum, at its opacity. */
    private fun styleForLayer(layer: OverlayLayer): Style {
        val style = styleOf(layer.product)
        val choices = overlayThresholdChoices(style)
        val min = if (choices.isEmpty()) style.hideBelow else maxOf(style.hideBelow, choices[layer.thresholdIndex.coerceIn(0, choices.size - 1)])
        return style.copy(hideBelow = min, opacity = layer.opacity)
    }

    private fun layersFrom(specs: List<OverlaySpec>): List<OverlayLayer> = specs.mapNotNull { sp ->
        val base = MrmsCatalog.byId(sp.id) ?: return@mapNotNull null
        val product = if (base.levels.isEmpty()) base else base.copy(level = sp.level.coerceIn(0, base.levels.size - 1))
        OverlayLayer(product, if (sp.index >= 0) sp.index else overlayIndexFor(product), sp.opacity)
    }

    /** Sets the whole stack (empty: none), saves it, lists the overlay files in the span of the radar frames and redraws. */
    private fun setOverlays(layers: List<OverlayLayer>) {
        stopPlaying()
        prefs.edit().putString("mrms_overlays", encodeOverlays(layers.map { OverlaySpec(it.product.id, it.product.level, it.thresholdIndex, it.opacity) })).apply()
        shownOverlays = emptyList()
        update { it.copy(overlays = layers, overlayFiles = emptyMap(), overlayStyles = emptyList(), overlayInfo = null) }
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            refreshOverlayFiles()
            try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, error = e.message) } }
        }
    }

    /** One product as the overlay (null: none). */
    fun pickOverlay(picked: MrmsProduct?) {
        val layers = picked?.let { withSavedLevel(it) }?.let { listOf(OverlayLayer(it, overlayIndexFor(it))) } ?: emptyList()
        setOverlays(layers)
    }

    /** Any mix of rotation, hail and lightning (none: no overlay); a switch that is already on keeps its product. */
    fun pickOverlayCombo(rotation: Boolean, hail: Boolean, lightning: Boolean) {
        val st = _state.value
        val now = OverlayPresets.comboOf(st.overlays.map { it.product.id })
        val specs = OverlayPresets.combo(if (rotation) now?.rotation ?: st.rotationChoice else null, if (hail) now?.hail ?: st.hailChoice else null, lightning)
        if (specs.isEmpty()) pickOverlay(null) else setOverlays(layersFrom(specs))
    }

    /** The product of the Rotation switch (saved); the stack is redrawn with it when rotation is on. */
    fun setRotationChoice(id: String) {
        if (id !in OverlayPresets.rotationChoices) return
        prefs.edit().putString("overlay_rotation", id).apply()
        val now = OverlayPresets.comboOf(_state.value.overlays.map { it.product.id })
        update { it.copy(rotationChoice = id) }
        if (now?.rotation != null) setOverlays(layersFrom(OverlayPresets.combo(id, now.hail, now.lightning)))
    }

    /** The product of the Hail switch (saved); the stack is redrawn with it when hail is on. */
    fun setHailChoice(id: String) {
        if (id !in OverlayPresets.hailChoices) return
        prefs.edit().putString("overlay_hail", id).apply()
        val now = OverlayPresets.comboOf(_state.value.overlays.map { it.product.id })
        update { it.copy(hailChoice = id) }
        if (now?.hail != null) setOverlays(layersFrom(OverlayPresets.combo(now.rotation, id, now.lightning)))
    }

    /** A ready-made stack, such as the lightning nest (strikes of the last 30 min / 5 min / 1 min at 20 / 50 / 100 % opacity). */
    fun pickOverlayPreset(name: String) {
        OverlayPresets.specs(name)?.let { setOverlays(layersFrom(it)) }
    }

    /** Limits a single overlay to values from the chosen minimum up (an index into its choices). */
    fun setOverlayThreshold(index: Int) {
        val layers = _state.value.overlays
        val only = layers.singleOrNull() ?: return
        prefs.edit().putInt("mrms_overlay_idx_${only.product.id}", index).apply()
        val updated = listOf(only.copy(thresholdIndex = index))
        prefs.edit().putString("mrms_overlays", encodeOverlays(updated.map { OverlaySpec(it.product.id, it.product.level, it.thresholdIndex, it.opacity) })).apply()
        update { it.copy(overlays = updated) }
        showFrame()
    }

    private suspend fun refreshOverlayFiles() {
        val st = _state.value
        val first = st.files.firstOrNull() ?: return
        if (st.overlays.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val lists = HashMap<String, List<MrmsFile>>()
        for (layer in st.overlays) {
            lists[layer.product.directory] = try { repo.listFilesSince(layer.product, first.validTime - 120, now) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
        update { it.copy(overlayFiles = lists) }
    }

    private fun overlayFileFor(layer: OverlayLayer, base: MrmsFile): MrmsFile? =
        nearestFile(_state.value.overlayFiles[layer.product.directory].orEmpty(), base.validTime, overlayToleranceSeconds(layer.product))

    /** A layer of the shown picture for the read-out: its values are not kept (they are as big as the picture), [values] gets them again when a tap needs them. */
    private class ShownOverlay(val name: String, val product: Product, val values: suspend () -> FloatArray)
    private class OverlayResult(val layer: Overlay, val shown: ShownOverlay, val info: String)
    private var shownOverlays: List<ShownOverlay> = emptyList()
    private var globeLayers: List<GlobeOverlay> = emptyList()           // the overlays as the globe draws them
    private var readoutJob: Job? = null

    /** What names a layer in the read-out and the file times: its own title, or only what tells the layers of a stack apart ("5 min"). */
    private fun layerLabel(layer: OverlayLayer, stack: Int) =
        layer.product.displayTitle.let { if (stack > 1) it.substringAfterLast(", ", it) else it.substringBefore(" (") }

    /**
     * The overlay layers for one radar frame, in drawing order. Each takes the overlay file nearest in time, cut from the same latitude / longitude box,
     * and is drawn with its own style (minimum, opacity) and placed on the base picture by where its cells fall (its grid can differ a little from the
     * base's: another cell size). A layer without a file nearby is skipped.
     */
    private suspend fun overlayLayers(baseFile: MrmsFile, label: String, box: DoubleArray, base: View, cache: Boolean, stepOf: (MrmsProduct) -> Int): List<OverlayResult> {
        val layers = _state.value.overlays
        val results = ArrayList<OverlayResult>()
        for (layer in layers) {
            val ov = layer.product
            val file = overlayFileFor(layer, baseFile) ?: continue
            val frame = try { decode(ov, file, "$label|overlay|${base.grid.id}", gridCoverBox(core, base.grid), stepOf(ov), cache, FrameTarget.Onto(base.grid)) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val g = frame.window.grid
            // (on the base's own grid: the picture is the base's, stretched over all of it)
            val dst = android.graphics.RectF(0f, 0f, (g.columns * base.scale).toFloat(), (g.rows * base.scale).toFloat())
            val style = styleForLayer(layer)
            val one = View("overlay", 0, 0, g.columns, g.rows, 1, 1, g)
            val argb = IntArray(g.columns * g.rows)
            withContext(Dispatchers.Default) { core.render(frame.window.values, one, style, IntArray(10), argb) }
            val bitmap = Bitmap.createBitmap(argb, g.columns, g.rows, Bitmap.Config.ARGB_8888)
            val shortName = layerLabel(layer, layers.size)
            val product = Product(ov.directory, ov.displayTitle, ov.group, style, hasMembers = false, timeDimension = null, grid = g)
            // (a translucent layer is not thickened: drawing it several times would stack its opacity)
            results += OverlayResult(Overlay(bitmap, dst, overlayIsSparse(ov) && layer.opacity >= 1.0, overlayIsMarker(ov), overlayMarkerScale(ov), halo = mrmsHasHalo(ov), markerColor = overlayMarkerColor(ov)), ShownOverlay(shortName, product) { decode(ov, file, "$label|overlay|${base.grid.id}", gridCoverBox(core, base.grid), stepOf(ov), cache, FrameTarget.Onto(base.grid)).window.values }, "$shortName ${formatClock(file.validTime)}")
        }
        return results
    }

    /** The overlay fields for the globe: each decoded onto the globe's own grid [grid], with how it is drawn and what a tap reads from it. */
    private suspend fun globeOverlays(baseFile: MrmsFile, grid: Grid): List<Pair<GlobeOverlay, ShownOverlay>> {
        val layers = _state.value.overlays
        val out = ArrayList<Pair<GlobeOverlay, ShownOverlay>>()
        for (layer in layers) {
            val ov = layer.product
            val file = overlayFileFor(layer, baseFile) ?: continue
            val label = "globe|overlay|${grid.id}"
            val frame = try { decode(ov, file, label, gridCoverBox(core, grid), globeStep(ov), true, FrameTarget.Onto(grid)) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val g = frame.window.grid
            val style = styleForLayer(layer)
            val shortName = layerLabel(layer, layers.size)
            val product = Product(ov.directory, ov.displayTitle, ov.group, style, hasMembers = false, timeDimension = null, grid = g)
            out += GlobeOverlay(g, frame.window.values, style, overlayIsMarker(ov), overlayMarkerScale(ov), mrmsHasHalo(ov), overlayMarkerColor(ov)) to
                ShownOverlay(shortName, product) { frame.window.values }
        }
        return out
    }

    private fun overlayInfoFor(results: List<OverlayResult>): String? {
        val layers = _state.value.overlays
        if (layers.isEmpty()) return null
        if (results.isEmpty()) return "No ${overlayName(layers)} data near this time"
        val note = if (results.size < layers.size) "  (${layers.size - results.size} without data)" else ""
        return results.joinToString("  \u00B7  ") { it.info } + note
    }

    // ---- under the radar: the surface analysis (RTMA, every 15 minutes; NBM when there is none), temperature as a fill and dewpoint as lines ----
    private val surface = AppContainer.surface
    private val underGrids = LinkedHashMap<Int, Grid>(8, 0.75f, true)          // a picture grid's id -> the coarse grid its surface fields are put on
    private val underValues = LinkedHashMap<String, FloatArray>(8, 0.75f, true) // a field put on a grid, by file, field and grid
    private var globeUnder: GlobeOverlay? = null
    private var globeLines: List<FieldLines> = emptyList()

    // ---- METAR stations: the last thing drawn, from the Aviation Weather Center ----
    private val metars = AppContainer.metars
    private var shownPlots: List<StationPlot> = emptyList()

    fun setStations(on: Boolean) {
        prefs.edit().putBoolean("mrms_stations", on).apply()
        update { it.copy(stations = on) }
        if (shown != null && !_state.value.playing) showFrame()
    }

    fun setStationDensity(level: Int) {
        prefs.edit().putInt("mrms_station_density", level).apply()
        update { it.copy(stationDensity = level) }
        if (shown != null && !_state.value.playing && _state.value.stations) showFrame()
    }

    /** The reports in force at [time] inside the lat / lon box [box] (west, south, east, north), the bulk file read again when it is a few minutes old. */
    private suspend fun stationReports(time: Long, box: DoubleArray): List<simplemodelview.Metar> {
        val now = System.currentTimeMillis() / 1000
        if (now - time < 3 * 3600) try { metars.refresh(now) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        return metars.at(time, box[0], box[1], box[2], box[3])
    }

    private suspend fun plotsFor(file: MrmsFile, v: View, box: DoubleArray): List<StationPlot> {
        if (!_state.value.stations) return emptyList()
        val reports = stationReports(file.validTime, box)
        return withContext(Dispatchers.Default) { stationPlots(core, reports, v, _state.value.units.celsius, _state.value.stationDensity) }
    }

    /** Opens a station: its last day of reports (AWC) and its 5-minute wind (IEM, which AWC does not carry), for the chart. */
    fun openStation(m: simplemodelview.Metar) {
        update { it.copy(station = StationDetail(m, null, null, null)) }
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            val history = try { metars.history(m.id, 24, now) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(station = it.station?.takeIf { s -> s.metar.id == m.id }?.copy(error = e.message ?: "could not read the reports")) }
                emptyList()
            }
            update { it.copy(station = it.station?.takeIf { s -> s.metar.id == m.id }?.copy(history = history)) }
            val five = try { metars.fiveMinute(m.id, now - 24 * 3600, now) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            update { it.copy(station = it.station?.takeIf { s -> s.metar.id == m.id }?.copy(fiveMinute = five)) }
        }
    }

    fun closeStation() = update { it.copy(station = null) }

    // ---- rainfall from a time to now: multi-sensor totals for the whole hours, radar-only for the rest ----
    /** Shows the rainfall total from [since] to now (null: back to the radar). On the flat map. */
    fun setRainSince(since: Long?) {
        if (since == null && _state.value.product.rainDays > 0) { pickProduct(MrmsCatalog.default); return }       // (back from a long total: to the radar)
        stopPlaying()
        update { it.copy(rainSince = since, rainInfo = null, globe = if (since != null) false else it.globe, crosshair = null, readout = null, tapLatLon = null) }
        showFrame()
    }

    /** The newest file time of an MRMS product (one listing), or null. */
    private suspend fun newestOf(id: String): Long? {
        val p = MrmsCatalog.byId(id) ?: return null
        return try { repo.listFiles(p, System.currentTimeMillis() / 1000).lastOrNull()?.validTime } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /**
     * A whole UTC day from the day cache, filled (from the day's 24 h multi-sensor Pass 2 total, the final one) when it is not there yet; null when that file
     * is not available (not yet posted, no network) or cannot be read, and the caller falls back to ordinary files.
     */
    private suspend fun dayFrame(piece: simplemodelview.RainSince.Piece, box: DoubleArray, sector: Sector, grid: Grid?): MrmsFrame? {
        val dir = "MultiSensor_QPE_24H_Pass2_00.00"
        val p = MrmsCatalog.byId(dir) ?: return null
        val file = MrmsFile(simplemodelview.RainSince.key(dir, piece.end), piece.end, null, 0)
        return try {
            var day = withContext(Dispatchers.IO) { rainDays.read(piece.end) }
            if (day == null) {
                val bytes = repo.download(file)
                day = withMemory { withContext(Dispatchers.Default) { simplemodelview.RainDayFile.build(core, bytes, DeflatePacker, p.missingAtOrBelow) } }
                val made = day
                withContext(Dispatchers.IO) { rainDays.write(piece.end, made) }
            }
            val d = day!!
            withMemory { withContext(Dispatchers.Default) { repo.loadFrameFromDay(p, file, d, DeflatePacker, box[0], box[1], box[2], box[3], stepFor(p, sector), grid?.let { FrameTarget.Onto(it) } ?: FrameTarget.Planned) } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private suspend fun showRain(st: MrmsState, since: Long) {
        update { it.copy(busy = true, error = null, rainInfo = "Finding the newest totals\u2026") }
        withContext(Dispatchers.IO) { rainDays.expire() }
        val multiEnd = newestOf("MultiSensor_QPE_01H_Pass2_00.00") ?: newestOf("MultiSensor_QPE_01H_Pass1_00.00")
        val radarEnd = newestOf("RadarOnly_QPE_15M_00.00") ?: (System.currentTimeMillis() / 1000)
        val plan = simplemodelview.RainSince.plan(since, multiEnd, radarEnd)
        if (plan.pieces.isEmpty()) { update { it.copy(busy = false, rainInfo = "Nothing to add yet: the start is after the newest radar total.") }; return }
        val sector = st.sector
        val box = sectorBox(sector)
        var grid: Grid? = null
        var sum: FloatArray? = null
        var missing = 0
        var fellBack = 0
        for ((k, piece) in plan.pieces.withIndex()) {
            update { it.copy(rainInfo = "Adding ${k + 1} of ${plan.pieces.size}: ${formatClock(piece.start)} \u2192 ${formatClock(piece.end)}") }
            var added = false
            if (piece.isDay) {
                val frame = dayFrame(piece, box, sector, grid)
                if (frame != null) {
                    if (grid == null) { grid = frame.window.grid; sum = frame.window.values.copyOf() } else simplemodelview.RainSince.addInto(sum!!, frame.window.values)
                    added = true
                }
            }
            if (!added) for ((n, dir) in piece.directories.withIndex()) {
                val p = MrmsCatalog.byId(dir) ?: continue
                val file = MrmsFile(simplemodelview.RainSince.key(dir, piece.end), piece.end, null, 0)
                val frame = try {
                    decode(p, file, "rain|${sector.name}", box, stepFor(p, sector), cache = false, target = grid?.let { FrameTarget.Onto(it) } ?: FrameTarget.Planned)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
                if (grid == null) { grid = frame.window.grid; sum = frame.window.values.copyOf() } else simplemodelview.RainSince.addInto(sum!!, frame.window.values)
                if (piece.multiSensor && dir.startsWith("RadarOnly")) fellBack += 1
                added = true
                break
            }
            if (!added) missing += 1
        }
        val g = grid ?: run { update { it.copy(busy = false, rainInfo = "The rainfall totals could not be read.") }; return }
        val values = sum!!
        val styleProduct = MrmsCatalog.byId("MultiSensor_QPE_72H_Pass2_00.00") ?: st.product
        val product = Product("rain_since", "Rainfall since ${formatClock(plan.start, 0)}", styleProduct.group, styleOf(styleProduct), hasMembers = false, timeDimension = null, grid = g)
        val v = View(sector.name, 0, 0, g.columns, g.rows, pixelScale(g), 1, g)
        val bitmap = withMemory { withContext(Dispatchers.Default) { renderer.render(product, product.style, values, emptyList(), v, dark, contours = false, counties = _state.value.settings.counties, barbDensity = _state.value.settings.barbDensity) } }
        view = v
        shownGrid = g
        shown = product to values
        shownOverlays = emptyList()
        shownPlots = emptyList()          // (no station plots over the total)
        val sources = buildString {
            append("${formatClock(plan.start, 0)} \u2192 ${formatClock(plan.end, 0)}")
            if (plan.multiFrom != null && plan.multiTo != null) append("  \u00B7  multi-sensor ${formatClock(plan.multiFrom, 0)} \u2192 ${formatClock(plan.multiTo, 0)}, radar-only the rest") else append("  \u00B7  radar-only")
            if (fellBack > 0) append("  \u00B7  $fellBack multi-sensor total${if (fellBack > 1) "s" else ""} missing, radar-only used")
            if (missing > 0) append("  \u00B7  $missing piece${if (missing > 1) "s" else ""} missing (not counted)")
        }
        update { it.copy(map = bitmap, detail = null, focus = handoff.takeFocus(v), style = product.style, busy = false, rainInfo = sources, overlayStyles = emptyList(), overlayInfo = null) }
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    fun setUnderTemp(on: Boolean) = setUnder(on, _state.value.underDew)
    fun setUnderDew(on: Boolean) = setUnder(_state.value.underTemp, on)

    private fun setUnder(temp: Boolean, dew: Boolean) {
        prefs.edit().putBoolean("mrms_under_t", temp).putBoolean("mrms_under_td", dew).apply()
        update { it.copy(underTemp = temp, underDew = dew) }
        if (shown != null && !_state.value.playing) showFrame()
    }

    /** The surface file for a scan (the newest analysis at or before it), or null when there is none (or no network). */
    private suspend fun underPick(base: MrmsFile): SurfacePick? =
        try { surface.pick(base.validTime, System.currentTimeMillis() / 1000) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** The grid (about 3 km) the surface fields are put on for a picture's grid: the analysis is 2.5 km, and needs no 1 km cells. The picture's own when it is not finer. */
    private fun underGridFor(grid: Grid): Grid = synchronized(underGrids) {
        underGrids[grid.id]?.let { return@synchronized it }
        val a = core.gridToLatLon(grid, grid.columns / 2.0, grid.rows / 2.0)
        val b = core.gridToLatLon(grid, grid.columns / 2.0 + 1.0, grid.rows / 2.0)
        val cellMeters = 111_000.0 * Math.hypot(b[0] - a[0], (b[1] - a[1]) * Math.cos(Math.toRadians(a[0])))
        val g = if (cellMeters >= 2500.0) grid
            else (try { core.planLambertCrop(grid, 0.0, 0.0, grid.columns - 1.0, grid.rows - 1.0, 3000.0) } catch (e: Exception) { null }) ?: grid
        underGrids[grid.id] = g
        while (underGrids.size > 8) underGrids.remove(underGrids.keys.first())
        g
    }

    /** Where the whole of [coarse] lies on the picture of [v], in its pixels (both grids are in the same projection: a scale and a shift). */
    private fun rectOn(coarse: Grid, v: View): android.graphics.RectF {
        if (coarse.id == v.grid.id) return android.graphics.RectF(0f, 0f, v.pixelWidth.toFloat(), v.pixelHeight.toFloat())
        val sw = core.gridToLatLon(coarse, -0.5, -0.5)
        val ne = core.gridToLatLon(coarse, coarse.columns - 0.5, coarse.rows - 0.5)
        val a = core.latLonToGrid(v.grid, sw[0], sw[1])
        val b = core.latLonToGrid(v.grid, ne[0], ne[1])
        val s = v.scale.toDouble() / v.step
        fun x(c: Double) = ((c - v.col0 + 0.5) * s).toFloat()
        fun y(r: Double) = ((v.row0 + v.rows - 0.5 - r) * s).toFloat()
        return android.graphics.RectF(x(a[0]), y(b[1]), x(b[0]), y(a[1]))
    }

    /** One surface field of [pick] put on [grid] (C), downloaded and decoded as needed; null when it cannot be had. */
    private suspend fun underField(pick: SurfacePick, dew: Boolean, grid: Grid): FloatArray? {
        val key = "${pick.url}|${if (dew) "td" else "t"}|${grid.id}"
        synchronized(underValues) { underValues[key] }?.let { return it }
        val values = try {
            val field = withContext(Dispatchers.IO) { if (dew) surface.dewpoint(pick) else surface.temperature(pick) }
            withContext(Dispatchers.Default) { core.reproject(field.values, field.grid, grid) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return null } catch (_: OutOfMemoryError) { return null }
        synchronized(underValues) { underValues[key] = values; while (underValues.size > 6) underValues.remove(underValues.keys.first()) }
        return values
    }

    private class UnderTemp(val values: FloatArray, val grid: Grid, val pick: SurfacePick)

    /** The surface temperature for a scan on [grid], or null when there is none near it. */
    private suspend fun underTemp(base: MrmsFile, grid: Grid): UnderTemp? {
        val pick = underPick(base) ?: return null
        return underField(pick, false, grid)?.let { UnderTemp(it, grid, pick) }
    }

    /** The dewpoint of the same file as [t], on its grid. */
    private suspend fun underDew(t: UnderTemp): FloatArray? = underField(t.pick, true, t.grid)

    private fun tempStyle() = MrmsCatalog.byId(Underlay.TEMP_STYLE)?.let { styleOf(it) } ?: Style()

    /** The temperature as a picture of its own grid, translucent, stretched over [dst] of the picture. */
    private suspend fun underFill(t: UnderTemp, dst: android.graphics.RectF): Overlay {
        val g = t.grid
        val one = View("under", 0, 0, g.columns, g.rows, 1, 1, g)
        val argb = IntArray(g.cells)
        withContext(Dispatchers.Default) { core.render(t.values, one, tempStyle().copy(opacity = Underlay.FILL_OPACITY), IntArray(10), argb) }
        return Overlay(Bitmap.createBitmap(argb, g.columns, g.rows, Bitmap.Config.ARGB_8888), dst, bold = false)
    }

    private fun dewLines(t: UnderTemp, td: FloatArray, dst: android.graphics.RectF?) =
        FieldLines(td, t.grid, tempStyle(), Underlay.dewpointSpec(_state.value.units.celsius), "td|${t.pick.url}|${t.grid.id}", dst)

    private fun tempShown(t: UnderTemp) =
        ShownOverlay("T", Product("surface_t", "Surface temperature", MrmsCatalog.MODEL, tempStyle(), hasMembers = false, timeDimension = null, grid = t.grid)) { t.values }

    private fun dewShown(t: UnderTemp, td: FloatArray) =
        ShownOverlay("Td", Product("surface_td", "Surface dewpoint", MrmsCatalog.MODEL, tempStyle(), hasMembers = false, timeDimension = null, grid = t.grid)) { td }

    /** The surface layers of one flat picture, all at once (a zoomed-in detail, an export). */
    private class UnderParts(val fill: Overlay?, val lines: List<FieldLines>)

    private suspend fun underParts(base: MrmsFile, v: View): UnderParts {
        val st = _state.value
        if (!st.underTemp && !st.underDew) return UnderParts(null, emptyList())
        val t = underTemp(base, underGridFor(v.grid)) ?: return UnderParts(null, emptyList())
        val dst = rectOn(t.grid, v)
        val fill = if (st.underTemp) underFill(t, dst) else null
        val td = if (st.underDew) underDew(t) else null
        return UnderParts(fill, listOfNotNull(td?.let { dewLines(t, it, dst) }))
    }

    private fun joinInfo(overlays: String?, under: List<String>): String? =
        (listOfNotNull(overlays) + under).joinToString("  \u00B7  ").ifEmpty { null }

    fun pickSector(sector: Sector) {
        prefs.edit().putString("mrms_sector", sector.name).apply()
        stopPlaying()
        update { it.copy(sector = sector, globe = false, crosshair = null, readout = null, tapLatLon = null) }
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

    // a meso sector keeps every cell of the files (1 km, rotation 500 m); the whole of CONUS is too wide for that, and gets finer cells as it is zoomed into (see scheduleDetail)
    private fun stepFor(product: MrmsProduct, sector: Sector) =
        mrmsStep(product.cellDegrees, sector.west, sector.east, if (sector.east - sector.west > 30.0) 3500 else 3000, dataSaver)

    // the globe shows everything the files cover (20-55 N, 130-60 W) at about 1400 cells across
    private val globeBox = doubleArrayOf(-130.0, 20.0, -60.0, 55.0)
    private fun globeStep(product: MrmsProduct) = mrmsStep(product.cellDegrees, globeBox[0], globeBox[2], 1400, dataSaver)

    /** The decoded window of [file] for a box and step: cached for the shown sector and the globe, decoded on demand otherwise. */
    private suspend fun decode(
        product: MrmsProduct, file: MrmsFile, label: String, box: DoubleArray, step: Int, cache: Boolean = true, target: FrameTarget = FrameTarget.LatLon,
    ): MrmsFrame {
        val key = "${file.key}|$label|$step"
        if (cache) decoded[key]?.let { return it }
        val f = withMemory { withContext(Dispatchers.Default) { Timings.timed("data") { repo.loadFrame(product, file, box[0], box[1], box[2], box[3], step, target) } } }
        if (cache) { decoded[key] = f; trimCache() }
        return f
    }

    /** Runs [block]; when the heap is full it lets go of every cached window and tries once more, and then reports it as an error (not a crash). */
    private suspend fun <T> withMemory(block: suspend () -> T): T = try {
        block()
    } catch (e: OutOfMemoryError) {
        decoded.clear()
        System.gc()
        try { block() } catch (e: OutOfMemoryError) { throw RefsException("Not enough memory for this view. Zoom in, or turn off some overlays.") }
    }

    private fun sectorBox(sector: Sector, margin: Double = 0.05) =
        doubleArrayOf(sector.west - margin, sector.south - margin, sector.east + margin, sector.north + margin)

    /**
     * Shows the current file. The layers come in order of priority, each shown as soon as it is ready: the radar field, its overlays, the temperature
     * underneath, the dewpoint lines. A layer that is already at hand (cached) is not shown on its own; only one that has to be downloaded or decoded first
     * lets the picture so far be shown while it is fetched. A playing loop shows each frame once, whole.
     */
    private suspend fun showCurrent() {
        val st = _state.value
        if (st.rainSince != null) { showRain(st, st.rainSince); return }
        val file = st.file ?: return
        val product = st.product
        update { it.copy(busy = true, error = null) }
        val progressive = !st.playing
        if (st.globe) {
            outlook.want(file.validTime)
            val frame = decode(product, file, "globe", globeBox, globeStep(product))
            val grid = frame.window.grid
            shownGrid = grid
            shown = synthetic(product, grid) to frame.window.values
            globeLayers = emptyList(); globeUnder = null; globeLines = emptyList(); shownOverlays = emptyList()
            update { it.copy(style = styleOf(product), cellKilometers = frame.window.sourceCellDegrees * globeStep(product) * 111.0) }
            var dirty = true
            val under = ArrayList<ShownOverlay>()
            var overlayShown: List<ShownOverlay> = emptyList()
            suspend fun paint(final: Boolean) {
                if (dirty) {
                    dirty = false
                    shownOverlays = overlayShown + under
                    if (globeW > 0) renderGlobeNow(1, busy = !final)
                }
                if (final) update { it.copy(busy = false) }
            }
            suspend fun <T> stage(load: suspend () -> T): T = staged(progressive, { paint(final = false) }, load)
            val layers = stage { globeOverlays(file, grid) }
            if (layers.isNotEmpty()) { globeLayers = layers.map { it.first }; overlayShown = layers.map { it.second }; dirty = true }
            if (st.underTemp || st.underDew) {
                val t = stage { underTemp(file, grid) }
                if (t != null) {
                    under += tempShown(t)
                    if (st.underTemp) { globeUnder = GlobeOverlay(t.grid, t.values, tempStyle().copy(opacity = Underlay.FILL_OPACITY)); dirty = true }
                    if (st.underDew) stage { underDew(t) }?.let { td ->
                        globeLines = listOf(dewLines(t, td, null)); under += dewShown(t, td); dirty = true
                    }
                }
            }
            paint(final = true)
            return
        }
        val sector = st.sector
        val step = stepFor(product, sector)
        val frame = decode(product, file, sector.name, sectorBox(sector), step, target = FrameTarget.Planned)
        val window = frame.window
        val grid = window.grid
        val v = View(sector.name, 0, 0, grid.columns, grid.rows, pixelScale(grid), 1, grid)
        val synthetic = synthetic(product, grid)
        outlook.want(file.validTime)
        val alertShapes = alertShapesFor(file)
        view = v
        shownGrid = grid
        shown = synthetic to window.values
        var ov: List<OverlayResult> = emptyList()
        var fill: Overlay? = null
        var lines: List<FieldLines> = emptyList()
        var plots: List<StationPlot> = emptyList()
        val under = ArrayList<ShownOverlay>()
        val underInfo = ArrayList<String>()
        var dirty = true
        suspend fun paint(final: Boolean) {
            if (dirty) {
                dirty = false
                val bitmap = withMemory { withContext(Dispatchers.Default) { renderer.render(synthetic, synthetic.style, window.values, emptyList(), v, dark, ov.map { it.layer }, alertDraws(v, alertShapes) + outlookDraws(core, outlook, v, file.validTime) + lightningDraws(core, AppContainer.lightning.state.value, v, file.validTime) + probSevereDraws(core, AppContainer.probSevere.state.value, v, file.validTime), contours = _state.value.settings.contours, counties = _state.value.settings.counties, barbDensity = _state.value.settings.barbDensity, halo = mrmsHasHalo(product), underlay = fill, fieldLines = lines, stations = plots) } }
                shownOverlays = ov.map { it.shown } + under
                shownPlots = plots
                val styles = st.overlays.map { l -> styleForLayer(l) } + listOfNotNull(fill?.let { tempStyle() })
                update { it.copy(map = bitmap, detail = null, focus = handoff.takeFocus(v), style = synthetic.style, busy = !final, cellKilometers = window.sourceCellDegrees * step * 111.0, overlayStyles = styles, overlayInfo = joinInfo(overlayInfoFor(ov), underInfo)) }
            } else if (final) update { it.copy(busy = false) }
        }
        suspend fun <T> stage(load: suspend () -> T): T = staged(progressive, { paint(final = false) }, load)
        ov = stage { overlayLayers(file, sector.name, sectorBox(sector), v, cache = true) { p -> stepFor(p, sector) } }
        if (ov.isNotEmpty()) dirty = true
        // priority: the surface temperature, then the station reports, then the dewpoint lines (drawn in their own places: the plots stay on top)
        val t = if (st.underTemp || st.underDew) stage { underTemp(file, underGridFor(grid)) } else null
        if (st.underTemp || st.underDew) {
            if (t == null) underInfo += "No surface temperature near this time"
            else {
                under += tempShown(t)
                underInfo += t.pick.label
                if (st.underTemp) { fill = underFill(t, rectOn(t.grid, v)); dirty = true }
            }
            dirty = true      // (the read-out note changes too)
        }
        if (st.stations) {
            plots = stage { plotsFor(file, v, sectorBox(sector, 0.5)) }
            if (plots.isNotEmpty()) dirty = true
        } else shownPlots = emptyList()
        if (st.underDew && t != null) {
            val td = stage { underDew(t) }
            if (td == null) underInfo += "no dewpoint"
            else { lines = listOf(dewLines(t, td, rectOn(t.grid, v))); under += dewShown(t, td); dirty = true }
        }
        paint(final = true)
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
        scheduleDetail()
    }

    /** Runs [load]; when it is not done within a moment (a download, a decode), [showSoFar] first shows what is ready. Not progressive: just [load]. */
    private suspend fun <T> staged(progressive: Boolean, showSoFar: suspend () -> Unit, load: suspend () -> T): T {
        if (!progressive) return load()
        return coroutineScope {
            val job = async { load() }
            withTimeoutOrNull(STAGE_WAIT_MS) { job.await() } ?: run { showSoFar(); job.await() }
        }
    }

    private fun synthetic(product: MrmsProduct, grid: Grid) =
        Product(product.directory, product.displayTitle, product.group, styleOf(product), hasMembers = false, timeDimension = null, grid = grid)

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

    // ---- taps and read-outs ----
    /** A tap on the flat picture, given as fractions of its width and height. */
    fun onScrub(fx: Float, fy: Float) = onTap(fx, fy, openStations = false)

    fun onTap(fx: Float, fy: Float, openStations: Boolean = true) {
        val (product, values) = shown ?: return
        val v = view ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { clearTap(); return }
        if (openStations && _state.value.stations && shownPlots.isNotEmpty()) {
            val px = fx * v.pixelWidth; val py = fy * v.pixelHeight
            val reach = stationTextSize(v.pixelWidth) * 1.6f
            shownPlots.minByOrNull { (it.x - px) * (it.x - px) + (it.y - py) * (it.y - py) }
                ?.takeIf { (it.x - px) * (it.x - px) + (it.y - py) * (it.y - py) <= reach * reach }?.let { openStation(it.metar) }
        }
        val column = floor(fx * v.cols).toInt().coerceIn(0, v.cols - 1)
        val row = v.rows - 1 - floor(fy * v.rows).toInt().coerceIn(0, v.rows - 1)
        val (lat, lon) = core.gridToLatLon(product.grid, column.toDouble(), row.toDouble())
        var text = readout(core, product, values, emptyList(), column, row)
        outlook.readoutAt(lat, lon, _state.value.file?.validTime)?.let { text += "   \u00B7 $it" }
        val st = _state.value
        val hits = st.file?.let { f -> shapesAt(alertShapesNow(f), lat, lon) } ?: emptyList()
        update { it.copy(crosshair = fx to fy, readout = text, tapLatLon = lat to lon, alertHits = hits) }
        appendOverlayReadouts(text, lat, lon)
    }

    /** The overlay layers' values at a tap are fetched (from the cache, or decoded again from the downloaded file): the read-out follows a moment later. */
    private fun appendOverlayReadouts(text: String, lat: Double, lon: Double) {
        val layers = shownOverlays
        if (layers.isEmpty()) return
        readoutJob?.cancel()
        readoutJob = viewModelScope.launch {
            var more = text
            for (layer in layers) {
                val values = try { layer.values() } catch (e: CancellationException) { throw e } catch (_: Exception) { continue } catch (_: OutOfMemoryError) { continue }
                val op = layer.product
                val c = core.latLonToGrid(op.grid, lat, lon)
                val v = shownValue(op.style, values, Math.round(c[0]).toInt(), Math.round(c[1]).toInt(), op.grid)
                more += "   \u00B7 ${layer.name}: " + if (v.isNaN()) "none" else fixed(v, op.style.digits) + (if (op.style.units.isEmpty()) "" else " " + op.style.units)
                if (_state.value.tapLatLon == lat to lon) update { it.copy(readout = more) }
            }
        }
    }

    fun onGlobeTap(px: Float, py: Float) {
        val (product, values) = shown ?: return
        val ll = globeInverse(_state.value.cam, globeW, globeH, px.toDouble(), py.toDouble())
        if (ll == null) { clearTap(); return }
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, ll[0], ll[1])
        val column = Math.round(c).toInt()
        val row = Math.round(r).toInt()
        var text = if (column !in 0 until grid.columns || row !in 0 until grid.rows)
            "${placeLabel(ll[0], ll[1])}   outside the MRMS area" else readout(core, product, values, emptyList(), column, row)
        outlook.readoutAt(ll[0], ll[1], _state.value.file?.validTime)?.let { text += "   \u00B7 $it" }
        val hits = _state.value.file?.let { f -> shapesAt(alertShapesNow(f), ll[0], ll[1]) } ?: emptyList()
        update { it.copy(readout = text, tapLatLon = ll[0] to ll[1], alertHits = hits) }
        appendOverlayReadouts(text, ll[0], ll[1])
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null, alertHits = emptyList()) }

    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewport = floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
        scheduleDetail()
    }

    /**
     * When the picture is zoomed into and its cells are coarser than the files', decode the visible part afresh at the finest step that
     * fits (as an export of the screen area does) and show it over the overview once the zoom has settled. Meso sectors are already at
     * every cell, so this is mostly CONUS.
     */
    private fun scheduleDetail() {
        detailJob?.cancel()
        val st = _state.value
        val v = view
        val file = st.file
        val (l, t, r, b) = viewport.toList()
        val zoomedIn = (r - l) * (b - t) < 0.5f
        if (!zoomedIn || v == null || file == null || st.globe || st.playing || st.map == null || stepFor(st.product, st.sector) <= 1) {
            if (st.detail != null) update { it.copy(detail = null) }
            return
        }
        val product = st.product
        val sector = st.sector
        detailJob = viewModelScope.launch {
            delay(400)
            try {
                val layer = detailLayer(product, file, v)
                val now = _state.value
                if (now.file?.key == file.key && now.product == product && now.sector == sector && !now.globe && view === v) update { it.copy(detail = layer) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private suspend fun detailLayer(product: MrmsProduct, file: MrmsFile, v: View): DetailLayer? {
        val w = visibleWindow(v)
        val step = mrmsStep(product.cellDegrees, w.west, w.east, 3500, false)
        val crop = core.planLambertCrop(v.grid, w.colMin, w.rowMin, w.colMax, w.rowMax, lambertCellMeters(product.cellDegrees * step, w.south, w.north)) ?: return null
        val coords = doubleArrayOf(w.west - 0.05, w.south - 0.05, w.east + 0.05, w.north + 0.05)
        val frame = decode(product, file, "detail", coords, step, cache = false, target = FrameTarget.Onto(crop))
        val g2 = frame.window.grid
        val box = View("Detail", 0, 0, g2.columns, g2.rows, pixelScale(g2), 1, g2)
        val ov = overlayLayers(file, "detail", coords, box, cache = false) { p -> mrmsStep(p.cellDegrees, w.west, w.east, 3500, false) }
        val under = underParts(file, box)
        val plots = plotsFor(file, box, coords)
        val synthetic = synthetic(product, g2)
        val bitmap = withMemory { withContext(Dispatchers.Default) {
            val alertShapes = alertShapesFor(file)
            renderer.render(synthetic, synthetic.style, frame.window.values, emptyList(), box, dark, ov.map { it.layer }, alertDraws(box, alertShapes) + outlookDraws(core, outlook, box, file.validTime) + lightningDraws(core, AppContainer.lightning.state.value, box, file.validTime) + probSevereDraws(core, AppContainer.probSevere.state.value, box, file.validTime), contours = _state.value.settings.contours, counties = _state.value.settings.counties, barbDensity = _state.value.settings.barbDensity, halo = mrmsHasHalo(product), underlay = under.fill, fieldLines = under.lines, stations = plots)
        } }
        val cols = v.grid.columns.toFloat(); val rows = v.grid.rows.toFloat()
        return DetailLayer(bitmap, (w.colMin / cols).toFloat(), (1.0 - (w.rowMax + 1.0) / rows).toFloat(), ((w.colMax + 1.0) / cols).toFloat(), (1.0 - w.rowMin / rows).toFloat(), frame.window.sourceCellDegrees * step * 111.0)
    }

    /** The cells of the shown (Lambert) grid that are on screen, and the lat / lon box that surely covers them. */
    private class VisibleWindow(val colMin: Double, val rowMin: Double, val colMax: Double, val rowMax: Double, val south: Double, val north: Double, val west: Double, val east: Double)

    private fun visibleWindow(v: View): VisibleWindow {
        val (l, t, r, b) = viewport.toList()
        val g = v.grid
        val colMin = floor(l * g.columns).toDouble(); val colMax = ceil(r * g.columns) - 1.0
        val rowMin = floor((1f - b) * g.rows).toDouble(); val rowMax = ceil((1f - t) * g.rows) - 1.0
        var south = 90.0; var north = -90.0; var west = 360.0; var east = -360.0
        for (k in 0..24) {
            val f = k / 24.0
            for ((c, rr) in listOf(colMin + f * (colMax - colMin) to rowMin, colMin + f * (colMax - colMin) to rowMax, colMin to rowMin + f * (rowMax - rowMin), colMax to rowMin + f * (rowMax - rowMin))) {
                val ll = core.gridToLatLon(g, c, rr)
                south = minOf(south, ll[0]); north = maxOf(north, ll[0]); west = minOf(west, ll[1]); east = maxOf(east, ll[1])
            }
        }
        return VisibleWindow(colMin, rowMin, colMax, rowMax, south, north, west, east)
    }

    // ---- the globe ----
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

    private suspend fun renderGlobeNow(stride: Int, busy: Boolean = false) {
        val (product, values) = shown ?: return
        if (globeW == 0 || values.size < product.grid.cells) return
        val st = _state.value
        val file = st.file
        val project: PlaceProjection = { lat, lon -> globeForward(st.cam, globeW, globeH, lat, lon) }
        val alertShapes = if (file == null) emptyList() else alertShapesFor(file)
        val bitmap = withContext(Dispatchers.Default) {
            val alerts = if (file == null) emptyList() else alertDrawsWith(alertShapes, project) + outlookDrawsOn(outlook, file.validTime, project) + lightningDrawsOn(AppContainer.lightning.state.value, file.validTime, project) + probSevereDrawsOn(AppContainer.probSevere.state.value, file.validTime, project)
            globeRenderer.render(product.grid, values, product.style, emptyList(), st.cam, globeW, globeH, stride, dark, product, st.settings.contours, st.settings.barbDensity, alerts = alerts, counties = st.settings.counties, overlays = globeLayers, baseHalo = mrmsHasHalo(st.product), underlay = globeUnder, fieldLines = globeLines)
        }
        update { it.copy(map = bitmap, busy = busy) }
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
        var text = if (column !in 0 until grid.columns || row !in 0 until grid.rows) "${placeLabel(lat, lon)}   outside the area shown" else readout(core, product, values, emptyList(), column, row)
        outlook.readoutAt(lat, lon, _state.value.file?.validTime)?.let { text += "   \u00B7 $it" }
        return text
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
            if (!inSector && st.sector.name != "CONUS") {
                update { it.copy(sector = Sector.all[0]) }
                try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, error = e.message) }; return@launch }
            }
            val v = view ?: return@launch
            val (c, r) = core.latLonToGrid(v.grid, place.lat, place.lon)
            onTap(((c + 0.5) / v.cols).toFloat(), ((v.rows - r - 0.5) / v.rows).toFloat())
        }
    }

    // ---- settings ----
    fun setSettings(settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).putBoolean("contours", settings.contours).putBoolean("minimal_ui", settings.minimalUi).putDisplay(settings).apply()
        val redraw = settings.dataSaver != _state.value.settings.dataSaver
        val linesChanged = settings.contours != _state.value.settings.contours || settings.displayDiffers(_state.value.settings)
        update { it.copy(settings = settings) }
        if (redraw) { decoded.clear(); if (shown != null) showFrame() } else if (linesChanged && shown != null) showFrame()
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
        val text = withContext(Dispatchers.IO) {
            try { resolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().decodeToString() } } catch (e: Exception) { null }
        }
        val b = text?.let { SettingsBackup.decode(it) }
        if (b == null) { update { it.copy(notice = "That file is not a simplemodelview settings file.") }; return false }
        val current = _state.value.settings
        setSettings(current.copy(wifiOnlyLoop = b.wifiOnlyLoop, dataSaver = b.dataSaver, cacheMegabytes = b.cacheMegabytes, contactEmail = b.contactEmail.ifEmpty { current.contactEmail }))
        prefs.edit().putStringSet("favorites", b.favorites.toSet()).apply()
        AppContainer.places.replaceAll(b.places.map { Place(it.name, it.lat, it.lon) })
        b.product?.let { prefs.edit().putString("product", it).apply() }       // (the SPC page's own choices)
        b.sector?.let { prefs.edit().putString("sector", it).apply() }
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
    /** Chooses how many of the newest scans Play (and a loop video) covers, or 0 for all of them. Restarts a running loop. */
    fun setLoopLength(frames: Int) {
        prefs.edit().putInt("mrms_loop_frames", frames).apply()
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

    /** The scans Play and a loop video cover: the range chosen on the time bar, else the newest N (or from the one shown). */
    fun loopWindowFor(st: MrmsState): IntRange =
        st.loopTimes?.let { (a, b) -> simplemodelview.loopIndices(st.files.map { it.validTime }, a, b) }
            ?: (if (AppContainer.loopFromShown.value) loopWindowFromShown(st.files.size, st.loopLength, st.index) else loopWindowEndingAt(st.files.size, st.loopLength, st.index))

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
            val window = loopWindowFor(st)
            val count = window.count()
            val ready = BooleanArray(st.files.size)
            var finished = false
            try {
                // download in order, a few at a time (politely), while the slideshow already runs on the frames that have arrived:
                // 1-5, then 1-10, then 1-20 as the rest load; the HTTP cache then serves each pass
                launch {
                    val gate = Semaphore(3)
                    var done = 0
                    val underOn = st.underTemp || st.underDew
                    val underFetched = java.util.Collections.synchronizedSet(HashSet<String>())
                    coroutineScope {
                        launch { prefetchArchivedAlerts(window.map { st.files[it] }) }          // the warnings and watches that were in force at each frame
                        for (i in window) launch {
                            gate.withPermit {
                                try {
                                    repo.prefetch(st.files[i]); ready[i] = true
                                    for (layer in st.overlays) overlayFileFor(layer, st.files[i])?.let { f -> try { repo.prefetch(f) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
                                    // the surface analysis last: one file (two messages) serves the scans of a quarter hour
                                    if (underOn) underPick(st.files[i])?.takeIf { underFetched.add(it.url) }?.let { pick -> try { surface.prefetch(pick) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
                                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
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
                    delay(if (next < at) (if (finished) 1200L else 500L) else 150L)
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

    /** The sector an export of [area] starts from (a meso area, or the one on screen). */
    private fun sectorOf(area: ShareArea) = (area as? ShareArea.Meso)?.sector ?: _state.value.sector

    /** Whole pixels per cell for an export: about 1800 px wide, at most 8 per cell, under about 8 million pixels. */
    private fun exportScale(cols: Int, rows: Int): Int {
        var s = ceil(1800.0 / cols).toInt().coerceIn(1, 8)
        while (s > 1 && cols.toLong() * s * rows * s > 8_000_000L) s -= 1
        return s
    }

    /**
     * The map picture of an export for one file. The screen area is decoded afresh at the finest step that suits its size, so a zoomed-in
     * export has 1 km (rotation 500 m) detail, not the cells of the screen's overview.
     */
    private suspend fun areaBitmap(area: ShareArea, product: MrmsProduct, file: MrmsFile, side: Int = GLOBE_EXPORT_SIDE): Pair<Bitmap, Style> {
        val st = _state.value
        val style = styleOf(product)
        if (area == ShareArea.Globe && globeW > 0) {
            val frame = decode(product, file, "globe", globeBox, globeStep(product))
            outlook.ensure(file.validTime)
            val cam = st.cam.copy(radius = st.cam.radius * side / minOf(globeW, globeH))       // the globe as turned now, on a square at export size
            val project: PlaceProjection = { la, lo -> globeForward(cam, side, side, la, lo) }
            val layers = globeOverlays(file, frame.window.grid).map { it.first }
            var underlay: GlobeOverlay? = null
            var fieldLines: List<FieldLines> = emptyList()
            if (st.underTemp || st.underDew) {
                underTemp(file, frame.window.grid)?.let { t ->
                    if (st.underTemp) underlay = GlobeOverlay(t.grid, t.values, tempStyle().copy(opacity = Underlay.FILL_OPACITY))
                    if (st.underDew) underDew(t)?.let { td -> fieldLines = listOf(dewLines(t, td, null)) }
                }
            }
            val shapes = alertShapesFor(file)
            val bitmap = withContext(Dispatchers.Default) {
                val alerts = alertDrawsWith(shapes, project) + outlookDrawsOn(outlook, file.validTime, project) + lightningDrawsOn(AppContainer.lightning.state.value, file.validTime, project) + probSevereDrawsOn(AppContainer.probSevere.state.value, file.validTime, project)
                globeRenderer.render(
                    frame.window.grid, frame.window.values, style, emptyList(), cam, side, side, 1, dark = false, product = synthetic(product, frame.window.grid), contours = st.settings.contours, barbDensity = st.settings.forExport().barbDensity,
                    alerts = alerts, counties = st.settings.forExport().counties, overlays = layers, baseHalo = mrmsHasHalo(product), underlay = underlay, fieldLines = fieldLines,
                )
            }
            return bitmap to style
        }
        val v = view
        val frame: MrmsFrame
        val box: View
        val overlayResults: List<OverlayResult>
        if (area == ShareArea.Screen && v != null) {
            // the visible part: a window of the shown (Lambert) grid, with the lat / lon box that surely covers it decoded and put onto it
            val g = v.grid
            val w = visibleWindow(v)
            val colMin = w.colMin; val rowMin = w.rowMin; val colMax = w.colMax; val rowMax = w.rowMax
            val south = w.south; val north = w.north; val west = w.west; val east = w.east
            val step = mrmsStep(product.cellDegrees, west, east, 2400, false)
            val crop = core.planLambertCrop(g, colMin, rowMin, colMax, rowMax, lambertCellMeters(product.cellDegrees * step, south, north)) ?: return Pair(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), style)
            val coords = doubleArrayOf(west - 0.05, south - 0.05, east + 0.05, north + 0.05)
            frame = decode(product, file, "screen", coords, step, cache = false, target = FrameTarget.Onto(crop))
            val g2 = frame.window.grid
            box = View("Screen area", 0, 0, g2.columns, g2.rows, exportScale(g2.columns, g2.rows), 1, g2)
            overlayResults = overlayLayers(file, "screen", coords, box, cache = false) { p -> mrmsStep(p.cellDegrees, west, east, 2400, false) }
        } else {
            val sector = sectorOf(area)
            frame = decode(product, file, sector.name, sectorBox(sector), stepFor(product, sector), target = FrameTarget.Planned)
            val g2 = frame.window.grid
            box = View(sector.name, 0, 0, g2.columns, g2.rows, exportScale(g2.columns, g2.rows), 1, g2)
            overlayResults = overlayLayers(file, sector.name, sectorBox(sector), box, cache = true) { p -> stepFor(p, sector) }
        }
        outlook.ensure(file.validTime)
        val under = underParts(file, box)
        val plots = plotsFor(file, box, gridCoverBox(core, box.grid))
        val bitmap = withContext(Dispatchers.Default) {
            renderer.render(synthetic(product, frame.window.grid), style, frame.window.values, emptyList(), box, dark = false, overlayResults.map { it.layer }, underlay = under.fill, fieldLines = under.lines, stations = plots, alerts = outlookDraws(core, outlook, box, file.validTime) + lightningDraws(core, AppContainer.lightning.state.value, box, file.validTime) + probSevereDraws(core, AppContainer.probSevere.state.value, box, file.validTime), contours = _state.value.settings.contours, counties = _state.value.settings.forExport().counties, barbDensity = _state.value.settings.forExport().barbDensity, halo = mrmsHasHalo(product))
        }
        return bitmap to style
    }

    private fun header(file: MrmsFile): String {
        val name = overlayName(_state.value.overlays)
        return "Valid ${formatClock(file.validTime, 0)}" + (if (name.isNotEmpty()) "   + $name" else "") + "   Data: NOAA/NSSL MRMS (experimental viewer)"
    }

    private fun fileName(product: MrmsProduct, file: MrmsFile, extension: String): String {
        val c = simplemodelview.civilFromEpoch(file.validTime)
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "mrms_${product.directory.filter { it.isLetterOrDigit() || it == '_' }.take(44)}_${c[0]}${p(c[1])}${p(c[2])}${p(c[3])}${p(c[4])}.$extension"
    }

    private fun sharedDir() = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    /** Writes the shareable picture (header, map, color key) and returns the file, or null when there is nothing to share. */
    suspend fun buildShareFile(area: ShareArea = ShareArea.Whole): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val file = st.file ?: return@withContext null
        // a rainfall total: the picture is the total on screen (the sector's grid), not a radar file
        if (st.rainSince != null) {
            val (p, values) = shown ?: return@withContext null
            val g = shownGrid ?: return@withContext null
            if (p.id != "rain_since") return@withContext null
            // the part of the total on screen, or the whole of it (the sector)
            val box = if (area == ShareArea.Screen) {
                val (l, t, r, b) = viewport.toList()
                val col0 = floor(l * g.columns).toInt().coerceIn(0, g.columns - 2)
                val row0 = floor((1f - b) * g.rows).toInt().coerceIn(0, g.rows - 2)       // the picture's top is north
                val cols = (ceil(r * g.columns).toInt() - col0).coerceIn(2, g.columns - col0)
                val rows = (ceil((1f - t) * g.rows).toInt() - row0).coerceIn(2, g.rows - row0)
                View("Screen area", col0, row0, cols, rows, exportScale(cols, rows), 1, g)
            } else View("Rainfall", 0, 0, g.columns, g.rows, exportScale(g.columns, g.rows), 1, g)
            val map = renderer.render(p, p.style, values, emptyList(), box, dark = false, contours = false, counties = st.settings.forExport().counties, barbDensity = st.settings.forExport().barbDensity)
            val info = st.rainInfo?.takeIf { it.isNotBlank() } ?: ""
            // the title says where the total starts: the line says where it ends and what it is made of
            val parts = info.split("  \u00B7  ")
            val through = parts.firstOrNull()?.substringAfter("\u2192 ", "")?.trim().orEmpty()
            val line = (listOfNotNull("through $through".takeIf { through.isNotEmpty() }) + parts.drop(1) + "NOAA/NSSL MRMS").joinToString("  \u00B7  ")
            val image = renderer.shareImage(map, p, p.style, line, emptyList())
            val out = File(sharedDir(), "mrms_rainfall_${simplemodelview.civilFromEpoch(st.rainSince).let { c -> "%04d%02d%02d%02d".format(c[0], c[1], c[2], c[3]) }}_to_${simplemodelview.civilFromEpoch(file.validTime).let { c -> "%04d%02d%02d%02d%02d".format(c[0], c[1], c[2], c[3], c[4]) }}.png")
            out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return@withContext out
        }
        val (map, style) = areaBitmap(area, st.product, file)
        val image = renderer.shareImage(map, synthetic(st.product, shownGrid ?: Grid.REFS), style, header(file), emptyList())
        val out = File(sharedDir(), fileName(st.product, file, "png"))
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out
    }

    /** Renders every file of the loop into an MP4 and returns it. [onProgress] gets (done, total). Files come from the HTTP cache once played. */
    suspend fun buildLoopFile(area: ShareArea, size: VideoSize, onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val window = loopWindowFor(st)
        val n = window.count()
        if (n < 2) return@withContext null
        val dir = sharedDir()
        val out = File(dir, fileName(st.product, st.files.last(), "mp4"))
        var exporter: LoopExporter? = null
        try {
            for ((k, i) in window.withIndex()) {
                val file = st.files[i]
                val (map, style) = areaBitmap(area, st.product, file, GLOBE_LOOP_SIDE)
                var picture = renderer.shareImage(map, synthetic(st.product, shownGrid ?: Grid.REFS), style, header(file), emptyList())
                val (sw, sh) = size.scaled(picture.width, picture.height)
                if (sw != picture.width) picture = Bitmap.createScaledBitmap(picture, sw, sh, true)
                if (exporter == null) exporter = LoopExporter(out, picture.width, picture.height, size.bitrate(picture.width, picture.height, 4))
                exporter.add(picture, last = k == n - 1)
                onProgress(k + 1, n)
            }
            exporter?.finish()
            out
        } catch (e: Exception) {
            exporter?.abort()
            throw e
        }
    }

    /** Estimated size of a loop video of the current product and area, for the share dialog. */
    fun estimateLoopMegabytes(area: ShareArea, size: VideoSize): Double {
        val g = shownGrid
        val (w, h) = when {
            area == ShareArea.Globe -> GLOBE_LOOP_SIDE to GLOBE_LOOP_SIDE
            g == null -> 1800 to 900
            else -> {
                val s = exportScale(g.columns, g.rows)
                g.columns * s to g.rows * s
            }
        }
        return VideoSize.estimateMegabytes(size, w, h + w / 6, loopWindowFor(_state.value).count())   // + header and legend
    }
}
