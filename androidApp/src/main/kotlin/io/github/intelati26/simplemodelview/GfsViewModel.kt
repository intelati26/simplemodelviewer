package io.github.intelati26.simplemodelview

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import simplemodelview.BackupPlace
import simplemodelview.GfsCatalog
import simplemodelview.GfsGlobal
import simplemodelview.GlobeCamera
import simplemodelview.Grid
import simplemodelview.NbmCycle
import simplemodelview.NbmFile
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.SettingsBackup
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.WindProducts
import simplemodelview.formatUtc
import simplemodelview.globeInverse
import simplemodelview.isValidEmail
import simplemodelview.nbmDwellMillis
import simplemodelview.nbmLoopWindow
import simplemodelview.readout
import java.io.File
import kotlin.math.floor

/** The windows of the globe the GFS page shows (Lambert boxes of the REFS projection, like the sectors of the other pages). */
internal val GFS_SECTORS = listOf(
    Sector("CONUS", -125.0, 24.0, -66.5, 50.0),
    Sector("North America", -170.0, 8.0, -50.0, 72.0),
    Sector("Pacific", -180.0, 5.0, -105.0, 62.0),
    Sector("Atlantic", -105.0, 5.0, -10.0, 62.0),
) + Sector.all.drop(1)         // and the regional sectors of the other pages (Midwest, Great Lakes, ...)

data class GfsState(
    val busy: Boolean = true,
    val loadStatus: String? = null,
    val source: String? = null,                       // where the last field came from: NOMADS (cropped) or AWS (whole fields, slower)
    val error: String? = null,
    val notice: String? = null,
    val cycles: List<NbmCycle> = emptyList(),
    val cycle: NbmCycle? = null,
    val following: Boolean = true,
    val frames: List<NbmFile> = emptyList(),
    val index: Int = 0,
    val product: String = "hw",                       // "hw": heights and winds (level and color below), else the id of a surface field
    val level: Int = 500,
    val color: String = "speed",
    val sector: Sector = GFS_SECTORS[0],
    val map: Bitmap? = null,
    val style: Style? = null,
    val title: String = "",
    val crosshair: Pair<Float, Float>? = null,
    val tapLatLon: Pair<Double, Double>? = null,
    val readout: String? = null,
    val playing: Boolean = false,
    val prefetch: Float? = null,
    val settings: Settings = Settings(),
    val contactPrompt: Boolean = false,
    val loopSpanHours: Int = 0,
    val globe: Boolean = false,
    val cam: GlobeCamera = GlobeCamera(38.0, -97.0, 380.0),
) {
    val file: NbmFile? get() = frames.getOrNull(index)
}

/**
 * The GFS page: the Global Forecast System to 16 days, with the upper air in front (height contours and wind barbs at 250 to 850 mb over a color of your
 * choice) and a few surface fields. It follows the NBM page: runs, frames, a loop, a tap read-out, sectors, sharing a picture.
 */
class GfsViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val prefs = AppContainer.prefs
    private val repo = AppContainer.gfs
    private val renderer = MapRenderer(core, AppContainer.lines)
    private var dark = false
    private var frameJob: Job? = null
    private var playJob: Job? = null
    private var shown: Triple<Product, FloatArray, View>? = null
    private var globeShown: Pair<Product, FloatArray>? = null           // what the globe shows: the product and its values on GFS's own grid
    private val globeRenderer = GlobeRenderer(core, AppContainer.lines)
    private var globeW = 0
    private var globeH = 0
    // whole-globe frames are big (4 or 8 planes of a million cells): only a few are kept
    private val globalComputed = object : LinkedHashMap<String, GfsGlobal>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GfsGlobal>?) = size > 3
    }
    private var lastLoad = 0L
    private var pickedCycle: NbmCycle? = null
    private val grids = HashMap<String, Grid>()
    private var fallbackShown = false
    private val computed = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 40
    }

    private val _state = MutableStateFlow(
        GfsState(
            product = prefs.getString("gfs_product", "hw") ?: "hw",
            level = prefs.getInt("gfs_level", 500).takeIf { it in GfsCatalog.levels || it == WindProducts.STACK } ?: 500,
            color = prefs.getString("gfs_color", "speed")?.takeIf { k -> WindProducts.colors.any { it.key == k } } ?: "speed",
            sector = GFS_SECTORS.firstOrNull { it.name == prefs.getString("gfs_sector", null) } ?: GFS_SECTORS[0],
            settings = readSettings(),
            loopSpanHours = prefs.getInt("gfs_loop_hours", 0),
        )
    )
    val state: StateFlow<GfsState> = _state
    private inline fun update(f: (GfsState) -> GfsState) { _state.value = f(_state.value) }

    private fun readSettings() = Settings(
        prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(),
        prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false),
    ).withDisplayPrefs(prefs)

    init { load(); promptForContact() }

    // ---- runs and frames ----
    fun load() {
        stopPlaying()
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            var failure: Exception? = null
            for (attempt in 0..3) {
                try {
                    val now = System.currentTimeMillis() / 1000
                    val cycles = repo.listCycles(now)
                    if (cycles.isEmpty()) throw IllegalStateException("No recent GFS runs on the server.")
                    val hand = pickedCycle?.takeIf { it in cycles }
                    var note: String? = null
                    var chosen: Pair<NbmCycle, List<NbmFile>>? = null
                    if (hand != null) repo.listFrames(hand, "gfs").takeIf { it.isNotEmpty() }?.let { chosen = hand to it }
                    if (chosen == null) {
                        val first = cycles.first()
                        val firstFrames = repo.listFrames(first, "gfs")
                        val second = cycles.getOrNull(1)?.let { it to repo.listFrames(it, "gfs") }
                        // a run still being written has few frames: show the one before it until it catches up
                        chosen = if (second == null || firstFrames.size >= second.second.size * 0.9) first to firstFrames else second.also { note = "The ${first.shortLabel} run is still being written; showing ${it.first.shortLabel}." }
                    }
                    val picked = chosen ?: throw IllegalStateException("No recent GFS data.")
                    if (picked.second.isEmpty()) throw IllegalStateException("The newest GFS run has no files yet.")
                    lastLoad = System.currentTimeMillis()
                    val keep = _state.value.file?.valid
                    val frames = picked.second
                    val index = keep?.let { v -> frames.indices.minByOrNull { kotlin.math.abs(frames[it].valid - v) } } ?: frames.indexOfFirst { it.valid >= now - 1800 }.let { if (it < 0) frames.size - 1 else it }
                    update { it.copy(cycles = cycles, cycle = picked.first, following = hand == null, frames = frames, index = index, notice = note ?: it.notice) }
                    showCurrent()
                    return@launch
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    failure = e
                    if (attempt < 3) delay(2000L shl attempt)
                }
            }
            update { it.copy(busy = false, error = failure?.message ?: "Something went wrong") }
        }
    }

    fun refreshIfStale() {
        val st = _state.value
        if (System.currentTimeMillis() - lastLoad < 30 * 60 * 1000 || st.playing || st.busy || !st.following) return
        load()
    }

    fun pickCycle(cycle: NbmCycle?) { pickedCycle = cycle; load() }

    // ---- what is shown ----
    fun pickHeightsAndWinds() { prefs.edit().putString("gfs_product", "hw").apply(); update { it.copy(product = "hw", crosshair = null, readout = null) }; showFrame() }
    fun pickSurface(id: String) { prefs.edit().putString("gfs_product", id).apply(); update { it.copy(product = id, crosshair = null, readout = null) }; showFrame() }
    fun pickLevel(level: Int) { prefs.edit().putInt("gfs_level", level).apply(); update { it.copy(level = level, product = "hw") }; showFrame() }
    fun pickColor(key: String) { prefs.edit().putString("gfs_color", key).apply(); update { it.copy(color = key, product = "hw") }; showFrame() }

    fun pickSector(sector: Sector) {
        prefs.edit().putString("gfs_sector", sector.name).apply()
        stopPlaying()
        update { it.copy(sector = sector, globe = false, crosshair = null, readout = null, tapLatLon = null) }
        showFrame()
    }

    fun pickIndex(index: Int) { update { it.copy(index = index.coerceIn(0, maxOf(0, it.frames.size - 1))) }; showFrame() }

    fun setDark(isDark: Boolean) { if (dark == isDark) return; dark = isDark; if (shown != null || globeShown != null) showFrame() }

    fun dismissNotice() = update { it.copy(notice = null) }
    fun reportNotice(text: String) = update { it.copy(notice = text) }

    /** The box the sector needs: the sector and a margin (its Lambert box bends). NOMADS crops to it; AWS is read down to its south edge. */
    private fun regionFor(st: GfsState): DoubleArray = st.sector.let { doubleArrayOf(maxOf(-180.0, it.west - 3), maxOf(-90.0, it.south - 3), minOf(180.0, it.east + 3), minOf(90.0, it.north + 3)) }

    private fun gridFor(sector: Sector): Grid = grids.getOrPut(sector.name) { core.planLambertBox(sector.west, sector.south, sector.east, sector.north, 25_000.0) }

    private fun showFrame() {
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, loadStatus = null, error = e.message) } }
        }
    }

    private fun statusHandler(): (String) -> Unit = { text ->
            when {
                text.startsWith("Source: ") -> {
                    update { it.copy(source = text.removePrefix("Source: ")) }
                    if (text.startsWith("Source: NOMADS")) fallbackShown = false
                }
                // NOMADS did not deliver: say so once (the frames after it take the same slower road), until it works again
                text.startsWith("NOMADS:") -> update {
                    val reason = text.removePrefix("NOMADS: ").substringBefore(". Using")
                    it.copy(loadStatus = text, notice = if (fallbackShown) it.notice else "NOMADS did not answer ($reason). Loading the whole fields from AWS instead: bigger downloads and slower.")
                }.also { fallbackShown = true }
                else -> update { it.copy(loadStatus = text) }
            }
        }

    /** The product shown for the state's choice on [grid], and its title. */
    private fun productFor(st: GfsState, surface: simplemodelview.GfsProduct?, grid: Grid): Pair<Product, String> {
        if (surface != null) return Product(surface.id, surface.title, surface.group, surface.style, false, null, grid) to surface.title
        val id = GfsCatalog.windId(st.level, st.color) ?: "wind${st.level}_mean"
        val color = WindProducts.colors.firstOrNull { it.key == st.color }
        val top = if (st.level == WindProducts.STACK) 500 else st.level
        val title = (if (st.level == WindProducts.STACK) "500 mb Height with 850 / 700 / 500 mb Wind Barbs" else "${st.level} mb Height and Wind Barbs") + if (color == null || color.source == null) "" else " over ${if (color.key == "tlev" || color.key == "rhlev") color.title.replace("the level", "$top mb") else color.title}"
        return Product(id, title, GfsCatalog.HEIGHTS_AND_WINDS, GfsCatalog.colorStyle(st.color), false, null, grid) to title
    }

    /** The whole globe for the frame: whole fields on GFS's own grid (no crop), drawn on the sphere. */
    private suspend fun showGlobeFrame(st: GfsState, file: NbmFile) {
        val surface = GfsCatalog.surfaceById(st.product)
        val status = statusHandler()
        val key = "${file.url}|${st.product}|${st.level}|${st.color}"
        val data = globalComputed[key] ?: withContext(Dispatchers.Default) {
            Timings.timed("data") { if (surface != null) repo.loadFieldGlobal(file, surface, status) else repo.loadWindGlobal(file, st.level, st.color, status) }
        }.also { globalComputed[key] = it }
        val (product, title) = productFor(st, surface, data.grid)
        globeShown = product to data.values
        update { it.copy(style = product.style, title = title, loadStatus = null) }
        if (globeW > 0) renderGlobeNow(1) else update { it.copy(busy = false) }
    }

    // ---- the globe ----
    fun onGlobeTap(px: Float, py: Float) {
        val (product, values) = globeShown ?: return
        val ll = globeInverse(_state.value.cam, globeW, globeH, px.toDouble(), py.toDouble())
        if (ll == null) { clearTap(); return }
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, ll[0], ll[1])
        val column = Math.round(c).toInt().coerceIn(0, grid.columns - 1)
        val row = Math.round(r).toInt().coerceIn(0, grid.rows - 1)
        update { it.copy(readout = readout(core, product, values, emptyList(), column, row, trueFrameWinds = true), tapLatLon = ll[0] to ll[1]) }
    }

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
        if (_state.value.globe && globeShown != null) requestGlobe(coarse = false)
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
        val (product, values) = globeShown ?: return
        if (globeW == 0 || values.size < product.grid.cells) return
        val st = _state.value
        val bitmap = withContext(Dispatchers.Default) {
            // GFS's winds are east and north already (no map axes to turn from)
            globeRenderer.render(product.grid, values, product.style, emptyList(), st.cam, globeW, globeH, stride, dark, product, st.settings.contours, st.settings.barbDensity, windInTrueFrame = true)
        }
        update { it.copy(map = bitmap, busy = false) }
    }

    private suspend fun showCurrent() {
        val st = _state.value
        val file = st.file ?: return
        update { it.copy(busy = true, error = null) }
        if (st.globe) { showGlobeFrame(st, file); return }
        val target = gridFor(st.sector)
        val surface = GfsCatalog.surfaceById(st.product)
        val status = statusHandler()
        val key = "${file.url}|${st.product}|${st.level}|${st.color}|${st.sector.name}"
        val region = regionFor(st)
        val values = computed[key] ?: withContext(Dispatchers.Default) {
            Timings.timed("data") { if (surface != null) repo.loadField(file, surface, target, status, region, st.settings.gfsCrop) else repo.loadWind(file, st.level, st.color, target, status, region, st.settings.gfsCrop) }
        }.also { computed[key] = it }
        val (product, title) = productFor(st, surface, target)
        val scale = (1400 / target.columns).coerceIn(2, 8)
        val view = View(st.sector.name, 0, 0, target.columns, target.rows, scale, 1, target)
        val bitmap = withContext(Dispatchers.Default) {
            renderer.render(product, product.style, values, emptyList(), view, dark, contours = st.settings.contours, counties = st.settings.counties, barbDensity = st.settings.barbDensity, barbZoom = barbZoom)
        }
        shown = Triple(product, values, view)
        update { it.copy(map = bitmap, style = product.style, title = title, busy = false, loadStatus = null) }
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    // ---- taps ----
    private var barbZoom = 1f
    private var zoomJob: kotlinx.coroutines.Job? = null

    /** The UI reports which part of the picture is on screen: wind barbs are drawn again for the zoom (after a short rest) so they keep their size on screen. */
    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        if (_state.value.product != "hw" || _state.value.globe) return
        val zoom = 1f / maxOf(0.02f, right.coerceIn(0f, 1f) - left.coerceIn(0f, 1f))
        val step = Math.round(Math.log(zoom.toDouble()) / Math.log(1.4)).toInt().coerceAtLeast(0)
        val fraction = Math.pow(1.4, -step.toDouble()).toFloat()
        if (fraction == barbZoom) return
        zoomJob?.cancel()
        zoomJob = viewModelScope.launch { kotlinx.coroutines.delay(300); barbZoom = fraction; showFrame() }
    }

    fun onTap(fx: Float, fy: Float) {
        val (product, values, v) = shown ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { clearTap(); return }
        val column = v.col0 + floor(fx * v.cols).toInt().coerceIn(0, v.cols - 1)
        val row = v.row0 + v.rows - 1 - floor(fy * v.rows).toInt().coerceIn(0, v.rows - 1)
        val (lat, lon) = core.gridToLatLon(product.grid, column.toDouble(), row.toDouble())
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, values, emptyList(), column, row), tapLatLon = lat to lon) }
    }

    fun clearTap() = update { it.copy(crosshair = null, readout = null, tapLatLon = null) }

    // ---- settings ----
    fun setSettings(settings: Settings) {
        prefs.edit().putBoolean("wifi_only", settings.wifiOnlyLoop).putBoolean("data_saver", settings.dataSaver)
            .putInt("cache_mb", settings.cacheMegabytes).putString("contact_email", settings.contactEmail.trim()).putBoolean("contours", settings.contours)
            .putBoolean("minimal_ui", settings.minimalUi).putDisplay(settings).apply()
        val redraw = settings.contours != _state.value.settings.contours || settings.displayDiffers(_state.value.settings) || settings.gfsCrop != _state.value.settings.gfsCrop
        update { it.copy(settings = settings) }
        if (redraw && shown != null) showFrame()
    }

    fun reloadFromPrefs() = update { it.copy(settings = readSettings()) }

    private fun backup() = SettingsBackup(
        wifiOnlyLoop = _state.value.settings.wifiOnlyLoop, dataSaver = _state.value.settings.dataSaver, cacheMegabytes = _state.value.settings.cacheMegabytes,
        contactEmail = _state.value.settings.contactEmail, favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().sorted(), places = emptyList(),
        product = prefs.getString("product", null), sector = prefs.getString("sector", null),
    )

    suspend fun exportSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = SettingsBackup.encode(backup())
        val ok = withContext(Dispatchers.IO) { try { resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (e: Exception) { false } }
        update { it.copy(notice = if (ok) "Settings exported." else "Could not write the settings file.") }
    }

    suspend fun importSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = withContext(Dispatchers.IO) { try { resolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().decodeToString() } } catch (e: Exception) { null } }
        val b = text?.let { SettingsBackup.decode(it) }
        if (b == null) { update { it.copy(notice = "That file is not a simplemodelview settings file.") }; return }
        val current = _state.value.settings
        setSettings(current.copy(wifiOnlyLoop = b.wifiOnlyLoop, dataSaver = b.dataSaver, cacheMegabytes = b.cacheMegabytes, contactEmail = b.contactEmail.ifEmpty { current.contactEmail }))
        update { it.copy(notice = "Settings imported.") }
    }

    private var lastContactPrompt = 0L

    fun promptForContact() {
        val now = System.currentTimeMillis()
        if (isValidEmail(_state.value.settings.contactEmail) || now - lastContactPrompt < 30 * 60 * 1000 && lastContactPrompt != 0L) return
        lastContactPrompt = now
        update { it.copy(contactPrompt = true) }
    }

    fun dismissContactPrompt() = update { it.copy(contactPrompt = false) }

    // ---- loop ----
    fun setLoopSpan(hours: Int) {
        prefs.edit().putInt("gfs_loop_hours", hours).apply()
        val wasPlaying = _state.value.playing
        if (wasPlaying) stopPlaying()
        update { it.copy(loopSpanHours = hours) }
        if (wasPlaying) startPlaying()
    }

    fun togglePlay() { if (_state.value.playing) stopPlaying() else startPlaying() }

    private fun stopPlaying() { playJob?.cancel(); playJob = null; update { it.copy(playing = false, prefetch = null) } }

    private fun startPlaying() {
        val st = _state.value
        val window = nbmLoopWindow(st.frames, st.loopSpanHours, if (AppContainer.loopFromShown.value) st.index else 0)
        if (window.count() < 2) return
        frameJob?.cancel()
        update { it.copy(playing = true) }
        playJob = viewModelScope.launch {
            try {
                // download the frames' messages first (they are kept, so playing them is fast), then play
                val surface = GfsCatalog.surfaceById(st.product)
                val count = window.count()
                var done = 0
                for (i in window) {
                    if (st.globe) repo.prefetch(st.frames[i], st.level, st.color, surface, null, false) else repo.prefetch(st.frames[i], st.level, st.color, surface, regionFor(st), st.settings.gfsCrop)
                    done += 1
                    update { it.copy(prefetch = if (done == count) null else done / count.toFloat()) }
                }
                while (true) {
                    for (i in window) {
                        update { it.copy(index = i) }
                        showCurrent()
                        delay(maxOf(nbmDwellMillis(_state.value.frames, i, 150L), if (i == window.last) 1200L else 0L))
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(playing = false, prefetch = null, busy = false, error = e.message) }
            }
        }
    }

    // ---- sharing ----
    suspend fun buildShareFile(): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val file = st.file ?: return@withContext null
        val product = (if (st.globe) globeShown?.first else shown?.first) ?: return@withContext null
        val map = st.map ?: return@withContext null
        val c = simplemodelview.civilFromEpoch(file.valid)
        fun p(n: Int) = n.toString().padStart(2, '0')
        val header = "Valid ${formatUtc(file.valid)} (+${file.leadHours} h)   Run ${st.cycle?.label ?: ""}   Data: NOAA/NWS Global Forecast System (experimental viewer)"
        val image = renderer.shareImage(map, product, product.style, header, emptyList())
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "gfs_${product.id}_${c[0]}${p(c[1])}${p(c[2])}${p(c[3])}.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out
    }
}
