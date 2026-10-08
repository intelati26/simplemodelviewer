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
import simplemodelview.BackupPlace
import simplemodelview.Grid
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
import simplemodelview.encodePlaces
import simplemodelview.formatClock
import simplemodelview.loopWindowEndingAt
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
import simplemodelview.overlayMarkerScale
import simplemodelview.overlayIsSparse
import simplemodelview.overlayThresholdChoices
import simplemodelview.overlayToleranceSeconds
import simplemodelview.defaultOverlayThresholdIndex
import simplemodelview.shownValue
import simplemodelview.fixed
import simplemodelview.nextPlayable
import simplemodelview.globeInverse
import simplemodelview.isValidEmail
import simplemodelview.mrmsStep
import simplemodelview.placeLabel
import simplemodelview.readout
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

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
    val style: Style? = null,
    val crosshair: Pair<Float, Float>? = null,   // fractions (0..1) of the picture
    val tapLatLon: Pair<Double, Double>? = null,
    val readout: String? = null,
    val playing: Boolean = false,
    val prefetch: Float? = null,                 // 0..1 while a loop is being downloaded
    val cellKilometers: Double? = null,          // resolution of the shown picture (after any decimation)
    val globe: Boolean = false,
    val cam: GlobeCamera = GlobeCamera(38.0, -97.0, 380.0),
    val favorites: Set<String> = emptySet(),
    val places: List<Place> = emptyList(),
    val settings: Settings = Settings(),
    val contactPrompt: Boolean = false,
    val units: Units = Units(),
    val live: Boolean = false,                   // the page is on screen and checks for newer scans by itself
    val loopLength: Int = 0,                     // how many of the newest scans Play and a loop video cover; 0 = all
    val overlays: List<OverlayLayer> = emptyList(),   // other products drawn over the first, in order (the last on top)
    val overlayFiles: Map<String, List<MrmsFile>> = emptyMap(),   // per overlay product directory: the files in the span of the radar frames
    val overlayStyles: List<Style> = emptyList(),     // as drawn (units, minimum, opacity), for the legend
    val overlayInfo: String? = null,                  // which overlay files are on the picture, or why one is missing
    val alertKinds: Set<AlertKind> = emptySet(),      // which alert layers are on (none by default)
    val alertShapes: List<AlertShape> = emptyList(),  // everything read for those layers
    val alertsAsOf: Long? = null,                     // when they were read (epoch seconds)
    val alertNote: String? = null,                    // why a layer could not be read
    val alertHits: List<AlertShape> = emptyList(),    // the outlines under the tapped point
    val alertDetail: Pair<String, String?>? = null,   // title and text of the alert being read (text null: loading)
) {
    val overlayName: String get() = overlayName(overlays)
    val file: MrmsFile? get() = files.getOrNull(index)
}

class MrmsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.mrms
    private val alertsRepo = AppContainer.alerts
    private val renderer = MapRenderer(core, AppContainer.lines)
    private val globeRenderer = GlobeRenderer(core, AppContainer.lines)

    private var dark = false
    private var frameJob: Job? = null
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
    private val cellBudget = 20_000_000L

    private val _state = MutableStateFlow(
        MrmsState(
            product = withSavedLevel(MrmsCatalog.byId(prefs.getString("mrms_product", null)) ?: MrmsCatalog.default),
            sector = Sector.all.firstOrNull { it.name == prefs.getString("mrms_sector", null) } ?: Sector.all[0],
            favorites = prefs.getStringSet("favorites", emptySet()).orEmpty(),
            places = decodePlaces(prefs.getString("places", "").orEmpty()),
            settings = readSettings(),
            units = readUnits(),
            loopLength = prefs.getInt("mrms_loop_frames", 0),
        )
    )
    val state: StateFlow<MrmsState> = _state

    private inline fun update(f: (MrmsState) -> MrmsState) { _state.value = f(_state.value) }

    private fun withSavedLevel(product: MrmsProduct) =
        if (product.levels.isEmpty()) product else product.copy(level = prefs.getInt("mrms_level_${product.id}", product.level).coerceIn(0, product.levels.size - 1))

    private fun readSettings() = Settings(
        prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(), prefs.getBoolean("contours", true),
    )

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
        if (st.playing || st.busy || st.error != null || st.files.isEmpty()) return
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
                if (_state.value.alertKinds.isNotEmpty()) { refreshAlerts(force = false); showFrameIfNewest() }
            }
        }
    }

    fun pickProduct(picked: MrmsProduct) {
        val product = withSavedLevel(picked)      // a 3D mosaic field comes back at the height last used for it
        prefs.edit().putString("mrms_product", product.id).apply()
        update { it.copy(product = product, crosshair = null, readout = null, tapLatLon = null) }
        load()
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
        update { it.copy(alertDetail = shape.title to shape.text) }
        if (shape.text != null) return
        viewModelScope.launch {
            val text = try { alertsRepo.text(shape) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            update { st -> if (st.alertDetail?.first == shape.title) st.copy(alertDetail = shape.title to (text ?: "No text is available for this alert.")) else st }
        }
    }

    fun dismissAlertText() = update { it.copy(alertDetail = null) }

    /** The outlines to draw on the picture [base]: only on the newest frame (they are of now, not of an older scan). */
    private fun alertDraws(base: View): List<AlertDraw> {
        val st = _state.value
        if (st.alertKinds.isEmpty() || st.files.isEmpty() || st.index != st.files.lastIndex) return emptyList()
        val s = base.scale.toFloat()
        return st.alertShapes.filter { it.kind in st.alertKinds }.map { shape ->
            val rings = shape.rings.map { ring ->
                val out = FloatArray(ring.size)
                var i = 0
                while (i + 1 < ring.size) {
                    val g = core.latLonToGrid(base.grid, ring[i], ring[i + 1])
                    out[i] = ((g[0] + 0.5) * s).toFloat()
                    out[i + 1] = ((base.rows - 0.5 - g[1]) * s).toFloat()
                    i += 2
                }
                out
            }
            AlertDraw(rings, shape.color, shape.dashed, fill = shape.kind != AlertKind.Discussion)
        }
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

    private class OverlayResult(val layer: Overlay, val shown: Triple<String, Product, FloatArray>, val info: String)
    private var shownOverlays: List<Triple<String, Product, FloatArray>> = emptyList()

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
            val frame = try { decode(ov, file, "$label|overlay", box, stepOf(ov), cache) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val g = frame.window.grid
            val (latA, lonA) = core.gridToLatLon(g, 0.0, 0.0).let { it[0] to it[1] }
            val (latB, lonB) = core.gridToLatLon(g, g.columns - 1.0, g.rows - 1.0).let { it[0] to it[1] }
            val a = core.latLonToGrid(base.grid, latA, lonA)
            val b = core.latLonToGrid(base.grid, latB, lonB)
            val dc = if (g.columns > 1) (b[0] - a[0]) / (g.columns - 1) else 1.0           // base cells per overlay cell
            val dr = if (g.rows > 1) (b[1] - a[1]) / (g.rows - 1) else 1.0
            val s = base.scale.toDouble()
            val dst = android.graphics.RectF(
                ((a[0] - 0.5 * dc + 0.5) * s).toFloat(), ((base.rows - (b[1] + 0.5 * dr)) * s).toFloat(),
                ((b[0] + 0.5 * dc + 0.5) * s).toFloat(), ((base.rows - (a[1] - 0.5 * dr)) * s).toFloat(),
            )
            val style = styleForLayer(layer)
            val one = View("overlay", 0, 0, g.columns, g.rows, 1, 1, g)
            val argb = IntArray(g.columns * g.rows)
            withContext(Dispatchers.Default) { core.render(frame.window.values, one, style, IntArray(10), argb) }
            val bitmap = Bitmap.createBitmap(argb, g.columns, g.rows, Bitmap.Config.ARGB_8888)
            val shortName = layerLabel(layer, layers.size)
            val product = Product(ov.directory, ov.displayTitle, ov.group, style, hasMembers = false, timeDimension = null, grid = g)
            // (a translucent layer is not thickened: drawing it several times would stack its opacity)
            results += OverlayResult(Overlay(bitmap, dst, overlayIsSparse(ov) && layer.opacity >= 1.0, overlayIsMarker(ov), overlayMarkerScale(ov)), Triple(shortName, product, frame.window.values), "$shortName ${formatClock(file.validTime)}")
        }
        return results
    }

    private fun overlayInfoFor(results: List<OverlayResult>): String? {
        val layers = _state.value.overlays
        if (layers.isEmpty()) return null
        if (results.isEmpty()) return "No ${overlayName(layers)} data near this time"
        val note = if (results.size < layers.size) "  (${layers.size - results.size} without data)" else ""
        return results.joinToString("  \u00B7  ") { it.info } + note
    }

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

    private fun stepFor(product: MrmsProduct, sector: Sector) =
        mrmsStep(product.cellDegrees, sector.west, sector.east, 2000, dataSaver)

    // the globe shows everything the files cover (20-55 N, 130-60 W) at about 1400 cells across
    private val globeBox = doubleArrayOf(-130.0, 20.0, -60.0, 55.0)
    private fun globeStep(product: MrmsProduct) = mrmsStep(product.cellDegrees, globeBox[0], globeBox[2], 1400, dataSaver)

    /** The decoded window of [file] for a box and step: cached for the shown sector and the globe, decoded on demand otherwise. */
    private suspend fun decode(product: MrmsProduct, file: MrmsFile, label: String, box: DoubleArray, step: Int, cache: Boolean = true): MrmsFrame {
        val key = "${file.key}|$label|$step"
        if (cache) decoded[key]?.let { return it }
        val f = withContext(Dispatchers.Default) { repo.loadFrame(product, file, box[0], box[1], box[2], box[3], step) }
        if (cache) { decoded[key] = f; trimCache() }
        return f
    }

    private fun sectorBox(sector: Sector, margin: Double = 0.05) =
        doubleArrayOf(sector.west - margin, sector.south - margin, sector.east + margin, sector.north + margin)

    private suspend fun showCurrent() {
        val st = _state.value
        val file = st.file ?: return
        val product = st.product
        update { it.copy(busy = true, error = null) }
        if (st.globe) {
            val frame = decode(product, file, "globe", globeBox, globeStep(product))
            shownGrid = frame.window.grid
            shown = synthetic(product, frame.window.grid) to frame.window.values
            update { it.copy(style = styleOf(product), cellKilometers = frame.window.sourceCellDegrees * globeStep(product) * 111.0) }
            if (globeW > 0) renderGlobeNow(1) else update { it.copy(busy = false) }
            return
        }
        val sector = st.sector
        val step = stepFor(product, sector)
        val frame = decode(product, file, sector.name, sectorBox(sector), step)
        val window = frame.window
        val grid = window.grid
        val v = View(sector.name, 0, 0, grid.columns, grid.rows, pixelScale(grid), 1, grid)
        val synthetic = synthetic(product, grid)
        val ov = overlayLayers(file, sector.name, sectorBox(sector), v, cache = true) { p -> stepFor(p, sector) }
        val bitmap = withContext(Dispatchers.Default) { renderer.render(synthetic, synthetic.style, window.values, emptyList(), v, dark, ov.map { it.layer }, alertDraws(v), contours = _state.value.settings.contours) }
        view = v
        shownGrid = grid
        shown = synthetic to window.values
        shownOverlays = ov.map { it.shown }
        update { it.copy(map = bitmap, style = synthetic.style, busy = false, cellKilometers = window.sourceCellDegrees * step * 111.0, overlayStyles = st.overlays.map { l -> styleForLayer(l) }, overlayInfo = overlayInfoFor(ov)) }
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
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
    fun onTap(fx: Float, fy: Float) {
        val (product, values) = shown ?: return
        val v = view ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { clearTap(); return }
        val column = floor(fx * v.cols).toInt().coerceIn(0, v.cols - 1)
        val row = v.rows - 1 - floor(fy * v.rows).toInt().coerceIn(0, v.rows - 1)
        val (lat, lon) = core.gridToLatLon(product.grid, column.toDouble(), row.toDouble())
        var text = readout(core, product, values, emptyList(), column, row)
        for ((name, op, ovValues) in shownOverlays) {
            val c = core.latLonToGrid(op.grid, lat, lon)
            val v = shownValue(op.style, ovValues, Math.round(c[0]).toInt(), Math.round(c[1]).toInt(), op.grid)
            text += "   \u00B7 $name: " + if (v.isNaN()) "none" else fixed(v, op.style.digits) + (if (op.style.units.isEmpty()) "" else " " + op.style.units)
        }
        val st = _state.value
        val hits = if (st.alertKinds.isNotEmpty() && st.index == st.files.lastIndex) shapesAt(st.alertShapes.filter { it.kind in st.alertKinds }, lat, lon) else emptyList()
        update { it.copy(crosshair = fx to fy, readout = text, tapLatLon = lat to lon, alertHits = hits) }
    }

    fun onGlobeTap(px: Float, py: Float) {
        val (product, values) = shown ?: return
        val ll = globeInverse(_state.value.cam, globeW, globeH, px.toDouble(), py.toDouble())
        if (ll == null) { clearTap(); return }
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, ll[0], ll[1])
        val column = Math.round(c).toInt()
        val row = Math.round(r).toInt()
        val text = if (column !in 0 until grid.columns || row !in 0 until grid.rows)
            "${placeLabel(ll[0], ll[1])}   outside the MRMS area" else readout(core, product, values, emptyList(), column, row)
        update { it.copy(readout = text, tapLatLon = ll[0] to ll[1]) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null, alertHits = emptyList()) }

    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewport = floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
    }

    // ---- the globe ----
    fun setGlobe(on: Boolean) {
        if (_state.value.globe == on) return
        stopPlaying()
        update { it.copy(globe = on, crosshair = null, readout = null, tapLatLon = null) }
        showFrame()
    }

    fun setGlobeSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == globeW && h == globeH)) return
        val first = globeW == 0
        globeW = w; globeH = h
        if (first) update { it.copy(cam = it.cam.copy(radius = 0.49 * minOf(w, h))) }
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
        val bitmap = withContext(Dispatchers.Default) {
            globeRenderer.render(product.grid, values, product.style, emptyList(), st.cam, globeW, globeH, stride, dark)
        }
        update { it.copy(map = bitmap, busy = false) }
    }

    // ---- places (bookmarks) ----
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
        val st = _state.value
        if (st.globe) {
            update { it.copy(cam = it.cam.copy(lat = place.lat, lon = place.lon), tapLatLon = place.lat to place.lon) }
            requestGlobe(coarse = false)
            return
        }
        val box = st.sector
        val inSector = place.lon in box.west..box.east && place.lat in box.south..box.north
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
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).putBoolean("contours", settings.contours).apply()
        val redraw = settings.dataSaver != _state.value.settings.dataSaver
        val linesChanged = settings.contours != _state.value.settings.contours
        update { it.copy(settings = settings) }
        if (redraw) { decoded.clear(); if (shown != null) showFrame() } else if (linesChanged && shown != null) showFrame()
    }

    private fun backup(): SettingsBackup {
        val st = _state.value
        return SettingsBackup(
            wifiOnlyLoop = st.settings.wifiOnlyLoop, dataSaver = st.settings.dataSaver, cacheMegabytes = st.settings.cacheMegabytes,
            contactEmail = st.settings.contactEmail, favorites = st.favorites.sorted(),
            places = st.places.map { BackupPlace(it.name, it.lat, it.lon) },
            product = prefs.getString("product", null), sector = prefs.getString("sector", null),
        )
    }

    suspend fun exportSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = SettingsBackup.encode(backup())
        val ok = withContext(Dispatchers.IO) {
            try { resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (e: Exception) { false }
        }
        update { it.copy(notice = if (ok) "Settings exported: ${backup().favorites.size} favorites, ${backup().places.size} places." else "Could not write the settings file.") }
    }

    suspend fun importSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = withContext(Dispatchers.IO) {
            try { resolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().decodeToString() } } catch (e: Exception) { null }
        }
        val b = text?.let { SettingsBackup.decode(it) }
        if (b == null) { update { it.copy(notice = "That file is not a simplemodelview settings file.") }; return }
        val current = _state.value.settings
        setSettings(Settings(b.wifiOnlyLoop, b.dataSaver, b.cacheMegabytes, b.contactEmail.ifEmpty { current.contactEmail }, current.contours))
        prefs.edit().putStringSet("favorites", b.favorites.toSet()).putString("places", encodePlaces(b.places.map { Place(it.name, it.lat, it.lon) })).apply()
        b.product?.let { prefs.edit().putString("product", it).apply() }       // (the SPC page's own choices)
        b.sector?.let { prefs.edit().putString("sector", it).apply() }
        update { it.copy(favorites = b.favorites.toSet(), places = b.places.map { p -> Place(p.name, p.lat, p.lon) }) }
        if (isValidEmail(_state.value.settings.contactEmail)) dismissContactPrompt()
        update { it.copy(notice = "Settings imported: ${b.favorites.size} favorites, ${b.places.size} places.") }
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
        val st = _state.value
        if (st.files.size < 2) return
        if (wifiOnlyLoop && isMetered()) {
            update { it.copy(notice = "Loops are set to download on Wi-Fi only. Connect to Wi-Fi or change this in Settings.") }
            return
        }
        playJob = viewModelScope.launch {
            update { it.copy(playing = true, prefetch = 0f) }
            val window = loopWindowEndingAt(st.files.size, st.loopLength, st.index)
            val count = window.count()
            val ready = BooleanArray(st.files.size)
            var finished = false
            try {
                // download in order, a few at a time (politely), while the slideshow already runs on the frames that have arrived:
                // 1-5, then 1-10, then 1-20 as the rest load; the HTTP cache then serves each pass
                launch {
                    val gate = Semaphore(3)
                    var done = 0
                    coroutineScope {
                        for (i in window) launch {
                            gate.withPermit {
                                try { repo.prefetch(st.files[i]); ready[i] = true; for (layer in st.overlays) overlayFileFor(layer, st.files[i])?.let { f -> try { repo.prefetch(f) } catch (e: CancellationException) { throw e } catch (_: Exception) { } } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
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
    private suspend fun areaBitmap(area: ShareArea, product: MrmsProduct, file: MrmsFile): Pair<Bitmap, Style> {
        val st = _state.value
        val style = styleOf(product)
        if (area == ShareArea.Globe && globeW > 0) {
            val frame = decode(product, file, "globe", globeBox, globeStep(product))
            val size = 1500
            val bitmap = withContext(Dispatchers.Default) {
                globeRenderer.render(frame.window.grid, frame.window.values, style, emptyList(), st.cam.copy(radius = st.cam.radius * size / globeW), size, size * globeH / globeW, 1, dark = false)
            }
            return bitmap to style
        }
        val v = view
        val frame: MrmsFrame
        val box: View
        val overlayResults: List<OverlayResult>
        if (area == ShareArea.Screen && v != null) {
            // the visible part, as a latitude / longitude box through the shown window's grid
            val (l, t, r, b) = viewport.toList()
            val g = v.grid
            val (south, west) = core.gridToLatLon(g, floor(l * g.columns).toDouble(), floor((1f - b) * g.rows).toDouble()).let { it[0] to it[1] }
            val (north, east) = core.gridToLatLon(g, ceil(r * g.columns) - 1.0, ceil((1f - t) * g.rows) - 1.0).let { it[0] to it[1] }
            val step = mrmsStep(product.cellDegrees, west, east, 2400, false)
            val coords = doubleArrayOf(west - 0.02, south - 0.02, east + 0.02, north + 0.02)
            frame = decode(product, file, "screen", coords, step, cache = false)
            val g2 = frame.window.grid
            box = View("Screen area", 0, 0, g2.columns, g2.rows, exportScale(g2.columns, g2.rows), 1, g2)
            overlayResults = overlayLayers(file, "screen", coords, box, cache = false) { p -> mrmsStep(p.cellDegrees, west, east, 2400, false) }
        } else {
            val sector = sectorOf(area)
            frame = decode(product, file, sector.name, sectorBox(sector), stepFor(product, sector))
            val g2 = frame.window.grid
            box = View(sector.name, 0, 0, g2.columns, g2.rows, exportScale(g2.columns, g2.rows), 1, g2)
            overlayResults = overlayLayers(file, sector.name, sectorBox(sector), box, cache = true) { p -> stepFor(p, sector) }
        }
        val bitmap = withContext(Dispatchers.Default) {
            renderer.render(synthetic(product, frame.window.grid), style, frame.window.values, emptyList(), box, dark = false, overlayResults.map { it.layer }, contours = _state.value.settings.contours)
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
        val (map, style) = areaBitmap(area, st.product, file)
        val image = renderer.shareImage(map, synthetic(st.product, shownGrid ?: Grid.REFS), style, header(file), emptyList())
        val out = File(sharedDir(), fileName(st.product, file, "png"))
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out
    }

    /** Renders every file of the loop into an MP4 and returns it. [onProgress] gets (done, total). Files come from the HTTP cache once played. */
    suspend fun buildLoopFile(area: ShareArea, size: VideoSize, onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val window = loopWindowEndingAt(st.files.size, st.loopLength, st.index)
        val n = window.count()
        if (n < 2) return@withContext null
        val dir = sharedDir()
        val out = File(dir, fileName(st.product, st.files.last(), "mp4"))
        var exporter: LoopExporter? = null
        try {
            for ((k, i) in window.withIndex()) {
                val file = st.files[i]
                val (map, style) = areaBitmap(area, st.product, file)
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
            area == ShareArea.Globe -> 1500 to 1500 * maxOf(globeH, 1) / maxOf(globeW, 1)
            g == null -> 1800 to 900
            else -> {
                val s = exportScale(g.columns, g.rows)
                g.columns * s to g.rows * s
            }
        }
        return VideoSize.estimateMegabytes(size, w, h + w / 6, loopWindowEndingAt(_state.value.files.size, _state.value.loopLength, _state.value.index).count())   // + header and legend
    }
}
