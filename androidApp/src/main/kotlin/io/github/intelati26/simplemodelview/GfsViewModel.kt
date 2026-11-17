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
import simplemodelview.GfsHistory
import simplemodelview.PolarCamera
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
import simplemodelview.globeForward
import simplemodelview.globeInverse
import simplemodelview.isValidEmail
import simplemodelview.nbmDwellMillis
import simplemodelview.nbmLoopWindow
import simplemodelview.readout
import java.io.File
import kotlin.math.floor

/** The windows of the globe the GFS page shows (Lambert boxes of the REFS projection, like the sectors of the other pages). */
/** The least memory the drawn globe pictures of a loop may use; a phone with more memory gets a sixth of it, up to 768 MB (see frameBudget). */
private const val FRAME_BUDGET = 192L * 1024 * 1024

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
    val detail: DetailLayer? = null,
    val globe: Boolean = false,
    val cam: GlobeCamera = GlobeCamera(38.0, -97.0, 380.0),
    val history: Boolean = false,             // the frames are the analyses (hour 0) of the runs of the past days, not one run's forecast hours
    val historyDays: Int = 5,
    val historyRange: Pair<Long, Long>? = null,   // a hand-picked span (epoch seconds); null = the past [historyDays] days
    val since: Long? = null,                  // the start (epoch seconds) of the precipitation-since product
    val polar: PolarCamera? = null,           // set: the globe page shows this polar stereographic map instead of the sphere
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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GfsGlobal>?) = size > 4
    }
    // behind the few kept for certain: more decoded frames held softly, which the system takes back when memory is short (a loop's later passes find most of their frames)
    private val globalSoft = object : LinkedHashMap<String, java.lang.ref.SoftReference<GfsGlobal>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.lang.ref.SoftReference<GfsGlobal>>?) = size > 60
    }
    /**
     * Whole drawn pictures of globe and polar frames, for playing a loop at one view: a loop draws each frame once (while it loads) and then only swaps pictures.
     * The key names everything a picture depends on (frame, product, view, size, look, outlook); held up to [FRAME_BUDGET] bytes, the least recently used going first.
     */
    private class CachedFrame(val bitmap: Bitmap, val product: Product, val title: String)
    private val frameCache = LinkedHashMap<String, CachedFrame>(32, 0.75f, true)
    private var frameCacheBytes = 0L

    /** How many bytes of pictures to keep: a sixth of the phone's memory, from 192 to 768 MB (a high-resolution screen's picture is 15 MB: a 24 hour loop is 25 of them). */
    private val frameBudget: Long = try {
        val am = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        (info.totalMem / 6).coerceIn(FRAME_BUDGET, 768L * 1024 * 1024)
    } catch (_: Exception) { FRAME_BUDGET }

    private fun cacheHasRoom(bytes: Long) = synchronized(frameCache) { frameCacheBytes + bytes <= frameBudget }

    private fun cacheGet(key: String): CachedFrame? = synchronized(frameCache) { frameCache[key] }

    /**
     * Keeps a picture. [evict]: make room by dropping the least recently used ones; without it (a loop playing) a picture that does not fit is not kept, since a
     * loop shows its frames in a circle and dropping the oldest would drop exactly the one wanted next: the frames that fit stay, every pass.
     */
    private fun cachePut(key: String, frame: CachedFrame, evict: Boolean = true) = synchronized(frameCache) {
        val bytes = frame.bitmap.allocationByteCount.toLong()
        if (!evict && key !in frameCache && frameCacheBytes + bytes > frameBudget) return@synchronized
        frameCache.put(key, frame)?.let { frameCacheBytes -= it.bitmap.allocationByteCount }
        frameCacheBytes += bytes
        val it = frameCache.entries.iterator()
        while (frameCacheBytes > frameBudget && frameCache.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            frameCacheBytes -= eldest.value.bitmap.allocationByteCount
            it.remove()                // (not recycled: the picture may be on screen)
        }
    }

    private fun pictureKey(st: GfsState, file: NbmFile) =
        "${frameKeyOf(st, file)}|${st.cam}|${st.polar}|${globeW}x$globeH|$dark|${st.settings.contours}|${st.settings.barbDensity}|${st.settings.counties}|${outlook.state.value.version}"

    private var globeFrameKey: String? = null            // the name of the globe frame shown: its contour lines are kept under it
    private var prepareJob: Job? = null
    private var lastLoad = 0L
    private var pickedCycle: NbmCycle? = null
    private val grids = HashMap<String, Grid>()
    private var fallbackShown = false
    private val computed = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 40
    }

    private val _state = MutableStateFlow(
        GfsState(
            product = (prefs.getString("gfs_product", "hw") ?: "hw").let { if (it == GfsHistory.SINCE_ID) "hw" else it },      // the since product needs a start time, which is not kept
            level = prefs.getInt("gfs_level", 500).takeIf { it in GfsCatalog.levels || it == WindProducts.STACK } ?: 500,
            color = prefs.getString("gfs_color", "speed")?.takeIf { k -> WindProducts.colors.any { it.key == k } } ?: "speed",
            sector = GFS_SECTORS.firstOrNull { it.name == prefs.getString("gfs_sector", null) } ?: GFS_SECTORS[0],
            settings = readSettings(),
            loopSpanHours = prefs.getInt("gfs_loop_hours", 24),      // a day of hourly frames: enough to see the movement
            historyDays = prefs.getInt("gfs_history_days", 5),
        )
    )
    val state: StateFlow<GfsState> = _state
    private inline fun update(f: (GfsState) -> GfsState) { _state.value = f(_state.value) }

    private fun readSettings() = Settings(
        prefs.getBoolean("wifi_only", false), prefs.getBoolean("data_saver", false), prefs.getInt("cache_mb", 150), prefs.getString("contact_email", "").orEmpty(),
        prefs.getBoolean("contours", true), prefs.getBoolean("minimal_ui", false),
    ).withDisplayPrefs(prefs)

    private val outlook = AppContainer.outlook

    init {
        load(); promptForContact()
        // a newly read (or changed) SPC outlook is drawn at once
        viewModelScope.launch { overlayChanges(lightning = false).collect { if (!_state.value.playing && (shown != null || globeShown != null)) { if (_state.value.globe) requestGlobe(coarse = false) else showFrame() } } }
    }

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
                    if (_state.value.history) {
                        val range = _state.value.historyRange ?: ((now - _state.value.historyDays * 86400L) to now)
                        val frames = repo.listHistory(range.first, range.second, "gfs")
                        if (frames.isEmpty()) throw IllegalStateException("No GFS analyses found for those days.")
                        lastLoad = System.currentTimeMillis()
                        val keep = _state.value.file?.valid
                        val index = keep?.let { v -> frames.indices.minByOrNull { kotlin.math.abs(frames[it].valid - v) } } ?: frames.lastIndex
                        update { it.copy(frames = frames, index = index, cycle = null, following = true) }
                        showCurrent()
                        return@launch
                    }
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
                    val keep = wantedValid?.also { wantedValid = null } ?: _state.value.file?.valid
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

    fun pickCycle(cycle: NbmCycle?) { pickedCycle = cycle; update { it.copy(history = false) }; load() }

    /** The analyses of the past [days] days (a frame per run) in place of one run's forecast hours: play it, or save it as a loop video. 0 leaves history. */
    fun setHistory(days: Int) {
        if (days > 0) prefs.edit().putInt("gfs_history_days", days).apply()
        stopPlaying()
        update { it.copy(history = days > 0, historyDays = if (days > 0) days else it.historyDays, historyRange = null, frames = if (days > 0) emptyList() else it.frames, index = 0) }
        load()
    }

    /** The analyses from [from] to [to] (epoch seconds; the bucket reaches back to 2021-03-23): every run for a few weeks, thinned beyond that. */
    fun setHistoryRange(from: Long, to: Long) {
        stopPlaying()
        update { it.copy(history = true, historyRange = from to to, frames = emptyList(), index = 0) }
        load()
    }

    // the extended point view asks for the synoptic set up at a valid time: Heights & Winds at 500 mb at the frame nearest it
    private var wantedValid: Long? = null

    /** Shows heights and winds at 500 mb at the frame nearest [valid] (epoch seconds); done when the run's frames are listed, otherwise as soon as they are. */
    fun showValid(valid: Long) {
        wantedValid = valid
        stopPlaying()
        prefs.edit().putString("gfs_product", "hw").putInt("gfs_level", 500).apply()
        update { it.copy(product = "hw", level = 500, crosshair = null, readout = null) }
        val frames = _state.value.frames
        if (frames.isEmpty()) return
        wantedValid = null
        update { it.copy(index = frames.indices.minByOrNull { i -> kotlin.math.abs(frames[i].valid - valid) } ?: 0) }
        showFrame()
    }

    // ---- what is shown ----
    fun pickHeightsAndWinds() { prefs.edit().putString("gfs_product", "hw").apply(); update { it.copy(product = "hw", crosshair = null, readout = null) }; showFrame() }
    private fun sinceTitle(st: GfsState, file: NbmFile) = "Precipitation from ${formatUtc((st.since ?: 0L) / 21600L * 21600L)} to ${formatUtc(file.valid)}"

    /** Precipitation from [start] (epoch seconds, back to 2021-03-23) to the time of the frame shown: it follows the slider and plays as a loop. */
    fun pickPrecipSince(start: Long) {
        prefs.edit().putString("gfs_product", GfsHistory.SINCE_ID).apply()
        update { it.copy(product = GfsHistory.SINCE_ID, since = start, crosshair = null, readout = null) }
        showFrame()
    }

    fun pickSurface(id: String) { prefs.edit().putString("gfs_product", id).apply(); update { it.copy(product = id, crosshair = null, readout = null) }; showFrame() }
    fun pickLevel(level: Int) { prefs.edit().putInt("gfs_level", level).apply(); update { it.copy(level = level, product = "hw") }; showFrame() }
    fun pickColor(key: String) { prefs.edit().putString("gfs_color", key).apply(); update { it.copy(color = key, product = "hw") }; showFrame() }

    fun pickSector(sector: Sector) {
        prefs.edit().putString("gfs_sector", sector.name).apply()
        stopPlaying()
        update { it.copy(sector = sector, globe = false, polar = null, crosshair = null, readout = null, tapLatLon = null) }
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
        return Product(id, title, GfsCatalog.HEIGHTS_AND_WINDS, GfsCatalog.colorStyle(st.color, top), false, null, grid) to title
    }

    private fun frameKeyOf(st: GfsState, file: NbmFile) = "${file.url}|${st.product}|${st.since}|${st.level}|${st.color}"

    /** The decoded whole-globe fields of a frame: kept for the last few frames, else read (the messages are on the device once downloaded) and decoded. */
    private suspend fun globalData(st: GfsState, file: NbmFile): GfsGlobal {
        val since = st.product == GfsHistory.SINCE_ID
        val surface = GfsCatalog.surfaceById(if (since) "apcptot_gfs" else st.product)
        val status = statusHandler()
        val key = frameKeyOf(st, file)
        synchronized(globalComputed) { globalComputed[key] ?: globalSoft[key]?.get()?.also { globalComputed[key] = it } }?.let { return it }
        return withContext(Dispatchers.Default) {
            Timings.timed("data") {
                if (since) {
                    var grid: Grid? = null
                    val values = repo.precipitationSince(st.since ?: throw IllegalStateException("no start time"), file, "gfs", "globe", { f, p -> repo.loadFieldGlobal(f, p, status).also { grid = it.grid }.values }, { d, n -> status("Precipitation: $d of $n runs…") })
                    GfsGlobal(grid ?: Grid.REFS, values)
                } else if (surface != null) repo.loadFieldGlobal(file, surface, status) else repo.loadWindGlobal(file, st.level, st.color, status)
            }
        }.also { synchronized(globalComputed) { globalComputed[key] = it; globalSoft[key] = java.lang.ref.SoftReference(it) } }
    }

    /**
     * Gets the next frame of a loop ready while this one is shown (its fields decoded, its contour lines traced and turned into places), so the turn to it is
     * only the drawing. A flat map keeps every frame of a loop already; this is for the globe and the polar maps.
     */
    private suspend fun prepareFrame(index: Int, render: Boolean = false) {
        val st = _state.value
        if (!st.globe) return
        val file = st.frames.getOrNull(index) ?: return
        val since = st.product == GfsHistory.SINCE_ID
        val surface = GfsCatalog.surfaceById(if (since) "apcptot_gfs" else st.product)
        val data = globalData(st, file)
        val (product, title0) = productFor(st, surface, data.grid)
        if (st.settings.contours) globeRenderer.warmContours(data.grid, data.values, product.style, product, frameKeyOf(st, file))
        // the whole picture too: a loop that fits the memory is drawn once, here, and played from these
        if (render && globeW > 0) {
            val key = pictureKey(st, file)
            if (cacheGet(key) == null && cacheHasRoom(globeW.toLong() * globeH * 4)) {
                val title = if (since) sinceTitle(st, file) else title0
                val bitmap = withContext(Dispatchers.Default) { renderGlobeBitmap(st, product, data.values, file, frameKeyOf(st, file), 1) }
                cachePut(key, CachedFrame(bitmap, product, title), evict = false)
            }
        }
    }

    /** The whole globe for the frame: whole fields on GFS's own grid (no crop), drawn on the sphere. */
    private suspend fun showGlobeFrame(st: GfsState, file: NbmFile) {
        if (st.playing) cacheGet(pictureKey(st, file))?.let { c ->
            // a loop playing: the picture drawn ahead, nothing decoded (a tap waits until it is paused: see stopPlaying)
            globeShown = null
            globeFrameKey = frameKeyOf(st, file)
            update { it.copy(map = c.bitmap, style = c.product.style, title = c.title, busy = false, loadStatus = null) }
            return
        }
        val since = st.product == GfsHistory.SINCE_ID
        val surface = GfsCatalog.surfaceById(if (since) "apcptot_gfs" else st.product)
        val data = globalData(st, file)
        globeFrameKey = frameKeyOf(st, file)
        val (product, title0) = productFor(st, surface, data.grid)
        val title = if (since) sinceTitle(st, file) else title0
        globeShown = product to data.values
        update { it.copy(style = product.style, title = title, loadStatus = null) }
        if (globeW > 0) renderGlobeNow(1) else update { it.copy(busy = false) }
    }

    // ---- the globe ----
    fun onGlobeTap(px: Float, py: Float) {
        val (product, values) = globeShown ?: return
        val st = _state.value
        val ll = if (st.polar != null) st.polar.inverse(globeW, globeH, px.toDouble(), py.toDouble()) else globeInverse(st.cam, globeW, globeH, px.toDouble(), py.toDouble())
        if (ll == null) { clearTap(); return }
        val grid = product.grid
        val (c, r) = core.latLonToGrid(grid, ll[0], ll[1])
        val column = Math.round(c).toInt().coerceIn(0, grid.columns - 1)
        val row = Math.round(r).toInt().coerceIn(0, grid.rows - 1)
        update { it.copy(readout = readout(core, product, values, emptyList(), column, row, trueFrameWinds = true) + (outlook.readoutAt(ll[0], ll[1], _state.value.file?.valid, _state.value.file?.run)?.let { t -> "   \u00B7 $t" } ?: ""), tapLatLon = ll[0] to ll[1]) }
    }

    fun setGlobe(on: Boolean) {
        if (_state.value.globe == on && _state.value.polar == null) return
        stopPlaying()
        update { it.copy(globe = on, polar = null, crosshair = null, readout = null, tapLatLon = null) }
        showFrame()
    }

    /** The globe turned to look straight down on a pole. */
    fun setGlobePole(north: Boolean) {
        stopPlaying()
        val wasGlobe = _state.value.globe && _state.value.polar == null
        update { it.copy(globe = true, polar = null, cam = it.cam.copy(lat = if (north) 90.0 else -90.0, lon = if (north) -100.0 else 80.0), crosshair = null, readout = null, tapLatLon = null) }
        if (wasGlobe && globeShown != null) requestGlobe(coarse = false) else showFrame()
    }

    /** The polar stereographic map of one hemisphere (the pole in the middle, out to 20 degrees). */
    fun setPolar(north: Boolean) {
        stopPlaying()
        val wasGlobe = _state.value.globe
        val radius = if (globeW > 0) 0.48 * minOf(globeW, globeH) else 300.0
        update { it.copy(globe = true, polar = PolarCamera(north, if (north) -100.0 else 80.0, radius), crosshair = null, readout = null, tapLatLon = null) }
        if (wasGlobe && globeShown != null) requestGlobe(coarse = false) else showFrame()
    }

    fun polarGesture(dx: Float, dy: Float, zoom: Float, cx: Float, cy: Float) {
        val minR = 0.3 * minOf(globeW, globeH)
        update { st -> st.polar?.let { st.copy(polar = it.turned(dx.toDouble(), dy.toDouble(), cx.toDouble(), cy.toDouble(), globeW, globeH).zoomed(zoom.toDouble(), minR, minR * 30)) } ?: st }
        requestGlobe(coarse = true)
        settleJob?.cancel()
        settleJob = viewModelScope.launch { delay(180); requestGlobe(coarse = false) }
    }

    fun setGlobeSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == globeW && h == globeH)) return
        val first = globeW == 0
        globeW = w; globeH = h
        if (first) update { it.copy(cam = it.cam.copy(radius = 0.49 * minOf(w, h)), polar = it.polar?.copy(radius = 0.48 * minOf(w, h))) }
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

    /** A place on the globe or polar map as the page has it now (null where it is not shown). */
    private fun globeProjection(st: GfsState, width: Int, height: Int): PlaceProjection =
        { lat, lon -> st.polar?.project(width, height, lat, lon) ?: if (st.polar == null) globeForward(st.cam, width, height, lat, lon) else null }

    /** One picture of a frame's globe or polar map at the view [st] has (GFS's winds are east and north already: no map axes to turn from). */
    private fun renderGlobeBitmap(st: GfsState, product: Product, values: FloatArray, file: NbmFile?, frameKey: String?, stride: Int): Bitmap {
        val valid = file?.valid
        val run = file?.run
        outlook.want(valid, run)
        return globeRenderer.render(
            product.grid, values, product.style, emptyList(), st.cam, globeW, globeH, stride, dark, product, st.settings.contours, st.settings.barbDensity, windInTrueFrame = true,
            polar = st.polar, counties = st.settings.counties, frameKey = frameKey, alerts = outlookDrawsOn(outlook, valid, run, globeProjection(st, globeW, globeH)),
        )
    }

    private suspend fun renderGlobeNow(stride: Int) {
        val (product, values) = globeShown ?: return
        if (globeW == 0 || values.size < product.grid.cells) return
        val st = _state.value
        val file = st.file
        val frameKey = globeFrameKey
        val bitmap = withContext(Dispatchers.Default) { renderGlobeBitmap(st, product, values, file, frameKey, stride) }
        if (stride == 1 && file != null && frameKey != null) cachePut(pictureKey(st, file), CachedFrame(bitmap, product, st.title), evict = !st.playing)
        update { it.copy(map = bitmap, busy = false) }
    }

    private suspend fun showCurrent() {
        val st = _state.value
        val file = st.file ?: return
        update { it.copy(busy = true, error = null) }
        if (st.globe) { showGlobeFrame(st, file); return }
        val target = gridFor(st.sector)
        val since = st.product == GfsHistory.SINCE_ID
        val surface = GfsCatalog.surfaceById(if (since) "apcptot_gfs" else st.product)
        val status = statusHandler()
        val key = "${file.url}|${st.product}|${st.since}|${st.level}|${st.color}|${st.sector.name}"
        val region = regionFor(st)
        val values = computed[key] ?: withContext(Dispatchers.Default) {
            Timings.timed("data") {
                if (since) repo.precipitationSince(st.since ?: throw IllegalStateException("no start time"), file, "gfs", "sector|${st.sector.name}|${st.settings.gfsCrop}", { f, p -> repo.loadField(f, p, target, status, region, st.settings.gfsCrop) }, { d, n -> status("Precipitation: $d of $n runs…") })
                else if (surface != null) repo.loadField(file, surface, target, status, region, st.settings.gfsCrop) else repo.loadWind(file, st.level, st.color, target, status, region, st.settings.gfsCrop)
            }
        }.also { computed[key] = it }
        val (product, title0) = productFor(st, surface, target)
        val title = if (since) sinceTitle(st, file) else title0
        val scale = (1400 / target.columns).coerceIn(2, 8)
        val view = View(st.sector.name, 0, 0, target.columns, target.rows, scale, 1, target)
        outlook.want(file.valid, file.run)
        val bitmap = withContext(Dispatchers.Default) {
            renderer.render(product, product.style, values, emptyList(), view, dark, alerts = outlookDraws(core, outlook, view, file.valid, file.run), contours = st.settings.contours, counties = st.settings.counties, barbDensity = st.settings.barbDensity)
        }
        shown = Triple(product, values, view)
        update { it.copy(map = bitmap, style = product.style, title = title, busy = false, loadStatus = null, detail = null) }
        _state.value.crosshair?.let { (fx, fy) -> onTap(fx, fy) }
    }

    // ---- taps ----
    private var detailJob: Job? = null

    /** The UI reports which part of the picture is on screen: once the zoom has settled, only the cells on screen are drawn again, finer, over the overview. */
    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        detailJob?.cancel()
        val st = _state.value
        val (product, values, base) = shown ?: return
        val window = if (st.globe || st.playing) null else detailWindow(base, left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
        if (window == null) { if (st.detail != null) update { it.copy(detail = null) }; return }
        detailJob = viewModelScope.launch {
            delay(400)
            try {
                val settings = _state.value.settings
                val valid = _state.value.file?.valid
                val run = _state.value.file?.run
                val bitmap = withContext(Dispatchers.Default) {
                    renderer.render(product, product.style, values, emptyList(), window.view, dark, alerts = outlookDraws(core, outlook, window.view, valid, run), contours = settings.contours, counties = settings.counties, barbDensity = settings.barbDensity)
                }
                if (shown?.second === values) update { it.copy(detail = DetailLayer(bitmap, window.left, window.top, window.right, window.bottom, 25.0)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    fun onTap(fx: Float, fy: Float) {
        val (product, values, v) = shown ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { clearTap(); return }
        val column = v.col0 + floor(fx * v.cols).toInt().coerceIn(0, v.cols - 1)
        val row = v.row0 + v.rows - 1 - floor(fy * v.rows).toInt().coerceIn(0, v.rows - 1)
        val (lat, lon) = core.gridToLatLon(product.grid, column.toDouble(), row.toDouble())
        update { it.copy(crosshair = fx to fy, readout = readout(core, product, values, emptyList(), column, row) + (outlook.readoutAt(lat, lon, _state.value.file?.valid, _state.value.file?.run)?.let { t -> "   \u00B7 $t" } ?: ""), tapLatLon = lat to lon) }
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

    private fun backup() = SettingsTransfer.withPrefs(SettingsBackup(
        wifiOnlyLoop = _state.value.settings.wifiOnlyLoop, dataSaver = _state.value.settings.dataSaver, cacheMegabytes = _state.value.settings.cacheMegabytes,
        contactEmail = _state.value.settings.contactEmail, favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().sorted(), places = emptyList(),
        product = prefs.getString("product", null), sector = prefs.getString("sector", null),
    ), prefs)

    suspend fun exportSettings(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val text = SettingsBackup.encode(backup())
        val ok = withContext(Dispatchers.IO) { try { resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null } catch (e: Exception) { false } }
        update { it.copy(notice = if (ok) "Settings exported." else "Could not write the settings file.") }
    }

    /** True when display or source choices were imported: the app restarts to take them up. */
    suspend fun importSettings(resolver: android.content.ContentResolver, uri: android.net.Uri): Boolean {
        val text = withContext(Dispatchers.IO) { try { resolver.openInputStream(uri)?.use { it.readBytes().take(1_000_000).toByteArray().decodeToString() } } catch (e: Exception) { null } }
        val b = text?.let { SettingsBackup.decode(it) }
        if (b == null) { update { it.copy(notice = "That file is not a simplemodelview settings file.") }; return false }
        val current = _state.value.settings
        setSettings(current.copy(wifiOnlyLoop = b.wifiOnlyLoop, dataSaver = b.dataSaver, cacheMegabytes = b.cacheMegabytes, contactEmail = b.contactEmail.ifEmpty { current.contactEmail }))
        val extras = SettingsTransfer.apply(prefs, b)
        update { it.copy(notice = "Settings imported" + (if (extras > 0) " ($extras display and source choices)" else "") + ".") }
        return extras > 0
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

    private fun stopPlaying() {
        val fromPictures = _state.value.playing && _state.value.globe && globeShown == null       // the frame on screen was a kept picture: its fields are read again for taps
        playJob?.cancel(); playJob = null; prepareJob?.cancel(); prepareJob = null
        update { it.copy(playing = false, prefetch = null) }
        if (fromPictures) { frameJob?.cancel(); frameJob = viewModelScope.launch { try { showCurrent() } catch (e: CancellationException) { throw e } catch (_: Exception) { } } }
    }

    private fun startPlaying() {
        val st = _state.value
        val window = loopWindow(st)
        if (window.count() < 2) return
        outlook.wantAllAs(window.map { st.frames[it].valid to st.frames[it].run })      // the outlook of every day the loop reaches, ready when the loop gets there
        frameJob?.cancel()
        update { it.copy(playing = true) }
        playJob = viewModelScope.launch {
            try {
                // download the frames' messages first (they are kept, so playing them is fast), then play
                val surface = GfsCatalog.surfaceById(st.product)
                val count = window.count()
                var done = 0
                for (i in window) {
                    if (st.product == GfsHistory.SINCE_ID) Unit else if (st.globe) repo.prefetch(st.frames[i], st.level, st.color, surface, null, false) else repo.prefetch(st.frames[i], st.level, st.color, surface, regionFor(st), st.settings.gfsCrop)
                    if (st.globe) prepareFrame(i, render = true)           // the contour lines and the whole picture of every frame (as many as fit the memory) are made while it loads
                    done += 1
                    update { it.copy(prefetch = if (done == count) null else done / count.toFloat()) }
                }
                while (true) {
                    for (i in window) {
                        update { it.copy(index = i) }
                        showCurrent()
                        val upcoming = window.firstOrNull { it > i } ?: window.first
                        prepareJob?.cancel()
                        prepareJob = viewModelScope.launch(Dispatchers.Default) { try { prepareFrame(upcoming) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
                        delay(maxOf(if (st.history) 450L else nbmDwellMillis(_state.value.frames, i, 150L), if (i == window.last) 1200L else 0L))
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(playing = false, prefetch = null, busy = false, error = e.message) }
            }
        }
    }

    private fun loopWindow(st: GfsState): IntRange =
        if (st.history) st.frames.indices else nbmLoopWindow(st.frames, st.loopSpanHours, if (AppContainer.loopFromShown.value) st.index else 0)

    var videoSize: simplemodelview.VideoSize
        get() = simplemodelview.VideoSize.byName(prefs.getString("video_size", null))
        set(value) { prefs.edit().putString("video_size", value.name).apply() }

    /** The loop (all the analyses in history, else the loop span) drawn as the page shows it now, as an MP4. [onProgress] gets (done, total). */
    suspend fun buildLoopFile(onProgress: (Int, Int) -> Unit): File? {
        stopPlaying()
        val st = _state.value
        val window = loopWindow(st)
        val n = window.count()
        if (n < 2) return null
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "gfs_loop_${st.product}_${st.frames[window.last].valid}.mp4")
        var exporter: LoopExporter? = null
        try {
            for ((k, i) in window.withIndex()) {
                update { it.copy(index = i) }
                outlook.ensure(st.frames[i].valid, st.frames[i].run)    // the outlook of that day, read before the frame is drawn
                showCurrent()                         // draws the frame (and downloads what it needs)
                val now = _state.value
                val file = now.file ?: continue
                val product = (if (now.globe) globeShown?.first else shown?.first) ?: continue
                val map = exportMap(now, GLOBE_LOOP_SIDE) ?: continue
                val header = "Valid ${formatUtc(file.valid)} " + (if (now.history) "(analysis)" else "(+${file.leadHours} h)   Run ${now.cycle?.label ?: ""}") + "   Data: NOAA/NWS Global Forecast System (experimental viewer)"
                val size = videoSize
                var picture = withContext(Dispatchers.Default) { renderer.shareImage(map, product, product.style, header, emptyList()) }
                val (sw, sh) = size.scaled(picture.width, picture.height)
                if (sw != picture.width) picture = Bitmap.createScaledBitmap(picture, sw, sh, true)
                if (exporter == null) exporter = LoopExporter(out, picture.width, picture.height, size.bitrate(picture.width, picture.height, 4))
                exporter.add(picture, last = k == n - 1)
                onProgress(k + 1, n)
            }
            exporter?.finish()
            return if (exporter != null) out else null
        } catch (e: Exception) {
            exporter?.abort()
            throw e
        }
    }

    /**
     * The picture to export: the globe or polar map drawn again on a square [side] pixels across (the screen's rotation and zoom kept), so a saved picture or loop
     * frame is far finer than the screen; the flat map is already drawn at its own scale.
     */
    private suspend fun exportMap(st: GfsState, side: Int): Bitmap? {
        if (!st.globe || globeW == 0) return st.map
        val (product, values) = globeShown ?: return st.map
        val f = side.toDouble() / minOf(globeW, globeH)
        return withContext(Dispatchers.Default) {
            globeRenderer.render(
                product.grid, values, product.style, emptyList(), st.cam.copy(radius = st.cam.radius * f), side, side, 1, dark, product,
                st.settings.contours, st.settings.barbDensity, windInTrueFrame = true, polar = st.polar?.let { it.copy(radius = it.radius * f) }, counties = st.settings.counties, frameKey = globeFrameKey,
                alerts = outlookDrawsOn(outlook, st.file?.valid, st.file?.run, globeProjection(st.copy(cam = st.cam.copy(radius = st.cam.radius * f), polar = st.polar?.let { it.copy(radius = it.radius * f) }), side, side)),
            )
        }
    }

    // ---- sharing ----
    suspend fun buildShareFile(): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val file = st.file ?: return@withContext null
        val product = (if (st.globe) globeShown?.first else shown?.first) ?: return@withContext null
        val map = exportMap(st, GLOBE_EXPORT_SIDE) ?: return@withContext null
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
