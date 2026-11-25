package io.github.intelati26.simplemodelview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import simplemodelview.Cycle
import simplemodelview.CompareScore
import simplemodelview.CycleStore
import simplemodelview.FrameTarget
import simplemodelview.Grid
import simplemodelview.MatchScore
import simplemodelview.MemberRank
import simplemodelview.MrmsCatalog
import simplemodelview.MrmsFile
import simplemodelview.Product
import simplemodelview.Style
import simplemodelview.RefsException
import simplemodelview.View
import simplemodelview.cycleStart
import simplemodelview.differenceStyle
import simplemodelview.earlyTimeIndices
import simplemodelview.gridCoverBox
import simplemodelview.nearestFile
import simplemodelview.rankMembers
import java.io.File

enum class CompareMode { Side, Overlay, Difference, Grid }

/** One pane of the grid layout: its label and its picture (null while it is being drawn). */
class PaneImage(val label: String, val bitmap: Bitmap?)

data class CompareState(
    val busy: Boolean = true,
    val status: String? = null,
    val error: String? = null,
    val run: Cycle? = null,
    /** The valid times offered (epoch seconds) with their forecast hours, and the one shown. */
    val times: List<Long> = emptyList(),
    val hours: List<Int> = emptyList(),
    val timeIndex: Int = 0,
    val mode: CompareMode = CompareMode.Side,
    val opacity: Float = 0.6f,
    val members: List<String> = emptyList(),
    val member: Int = 0,
    /** Members best first with their scores, and how many of the early times are scored so far (done, of). */
    val ranks: List<MemberRank> = emptyList(),
    val scored: Pair<Int, Int>? = null,
    val scoredHours: List<Int> = emptyList(),
    /** True when the ranking is for the part of the map on screen, false for the whole map. */
    val inView: Boolean = false,
    /** The grid layout: the radar and the best [gridCount] members (all of them when it is at least their number), as differences from the radar or as they are. */
    val gridCount: Int = 5,
    val gridDiff: Boolean = false,
    val tiles: List<PaneImage> = emptyList(),
    val model: Bitmap? = null,
    val obs: Bitmap? = null,
    val diff: Bitmap? = null,
    val obsTime: Long? = null,
    val modelTime: Long? = null,
)

/**
 * MRMS composite reflectivity against the members of the newest REFS run, on REFS's own grid: the radar is decoded at 3 km (maximum of the cells) and put onto that
 * grid, so the two pictures line up cell for cell and a difference is a subtraction. Every member's first hours are scored against the radar at the same
 * valid times (see [CompareScore]); the best match is shown first. "Which member has the initial conditions best."
 */
class CompareViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val repo = AppContainer.repo
    private val mrms = AppContainer.mrms
    private val renderer = MapRenderer(core, AppContainer.lines)
    private val radar = MrmsCatalog.byId("MergedReflectivityQCComposite_00.50") ?: throw IllegalStateException("MRMS composite reflectivity is not in the catalog")
    private val view = View.CONUS
    private val columns = view.cols
    private val rows = view.rows

    private val _state = MutableStateFlow(CompareState())
    val state: StateFlow<CompareState> = _state
    private inline fun update(f: (CompareState) -> CompareState) { _state.value = f(_state.value) }

    private var dark = false
    private var store: CycleStore? = null
    private var product: Product? = null
    private var files: List<MrmsFile> = emptyList()
    private var timeSteps: List<Int> = emptyList()       // the offered times as indices into the run's time axis
    private var scoredSteps: List<Int> = emptyList()      // the early ones that are scored
    private val radarCache = HashMap<Int, FloatArray>()   // time step -> radar dBZ on the REFS grid (NaN outside its coverage, -10 where there is no echo)
    private var autoMember = true
    private var loadJob: Job? = null
    private var scoreJob: Job? = null
    private var region: IntArray? = null                  // the cells on screen (first column, first row, last column, last row), null: the whole map
    private var showJob: Job? = null
    private var viewportF = floatArrayOf(0f, 0f, 1f, 1f)  // the part of the picture on screen, as fractions (left, top, right, bottom)

    // drawn panes by what they show, so another mode, another member or a re-order of the grid needs no new drawing; kept to about 100 MB
    private val tileCache = LinkedHashMap<String, Bitmap>(32, 0.75f, true)
    private var tileBytes = 0L
    private fun cacheGet(key: String): Bitmap? = synchronized(tileCache) { tileCache[key] }
    private fun cachePut(key: String, bitmap: Bitmap) = synchronized(tileCache) {
        tileCache.put(key, bitmap)?.let { tileBytes -= it.byteCount }
        tileBytes += bitmap.byteCount
        val it = tileCache.entries.iterator()
        while (tileBytes > 100_000_000L && tileCache.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            tileBytes -= e.value.byteCount; it.remove()
        }
    }

    init { load() }

    fun load() {
        loadJob?.cancel(); showJob?.cancel(); scoreJob?.cancel()
        radarCache.clear(); synchronized(tileCache) { tileCache.clear(); tileBytes = 0L }; autoMember = true
        loadJob = viewModelScope.launch {
            update { it.copy(busy = true, error = null, status = "Finding the newest run…", ranks = emptyList(), scored = null) }
            try {
                open()
                showCurrent()
                startScoring()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, status = null, error = e.message ?: e.toString()) }
            }
        }
    }

    /** The newest run that has frames at the radar's current time, its members and valid times, and the radar files since the run began. */
    private suspend fun open() {
        val now = System.currentTimeMillis() / 1000
        val cycles = repo.findCycles(now)
        if (cycles.isEmpty()) throw RefsException("Could not reach SPC's REFS data.")
        var chosen: Triple<CycleStore, Product, List<Long>>? = null
        for (cycle in cycles.take(3)) {
            val s = try { repo.open(cycle) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val p = s.products.firstOrNull { it.id == "cref" } ?: continue
            // a run still being written has gaps in its time axis: the next older one is used
            val times = try { repo.validTimes(s, p) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val start = cycleStart(cycle)
            val first = times.indexOfFirst { it >= start }
            if (first < 0) continue
            // a run that exists may not have its first hours written yet: its first chunk tells
            if (repo.readFrameTimed(s, p, first, 0).fileTime == null) continue
            chosen = Triple(s, p, times)
            break
        }
        val (s, p, times) = chosen ?: throw RefsException("No recent REFS run has its reflectivity yet.")
        store = s; product = p
        val run = s.cycle
        val start = cycleStart(run)
        update { it.copy(status = "Reading the radar…") }
        files = mrms.listFilesSince(radar, start - 600, now)
        if (files.isEmpty()) throw RefsException("No MRMS reflectivity files since the ${run.label} run began.")
        val latest = files.last().validTime
        val early = earlyTimeIndices(start, times, latest)
        if (early.isEmpty()) throw RefsException("The radar does not cover any time step of the ${run.label} run yet.")
        val nowStep = times.indices.lastOrNull { times[it] in start..(latest + 300) } ?: early.last()
        scoredSteps = early
        timeSteps = (early + nowStep).distinct().sorted()
        val members = repo.members(s)
        val shown = members.indexOf("RRFS Ctl").takeIf { it >= 0 } ?: 0
        update {
            it.copy(run = run, times = timeSteps.map { i -> times[i] }, hours = timeSteps.map { i -> ((times[i] - start) / 3600).toInt() },
                timeIndex = timeSteps.indexOf(nowStep), members = members, member = shown, scoredHours = early.map { i -> ((times[i] - start) / 3600).toInt() })
        }
    }

    /** The radar at a time step of the run, on the REFS grid. */
    private suspend fun radarAt(step: Int): FloatArray? {
        radarCache[step]?.let { return it }
        val s = store ?: return null
        val times = repo.validTimes(s, product!!)
        val file = nearestFile(files, times[step], 360) ?: return null
        val cover = gridCoverBox(core, Grid.REFS)
        val west = maxOf(cover[0], -130.0); val south = maxOf(cover[1], 20.0); val east = minOf(cover[2], -60.0); val north = minOf(cover[3], 55.0)
        val filled = withContext(Dispatchers.Default) {
            val w = mrms.loadFrame(radar, file, west, south, east, north, 3, FrameTarget.LatLon).window
            val onto = core.reproject(w.values, w.grid, Grid.REFS)
            val seen = core.reproject(FloatArray(w.values.size) { 1f }, w.grid, Grid.REFS)
            FloatArray(onto.size) { i -> if (seen[i].isNaN()) Float.NaN else if (onto[i].isNaN()) -10f else onto[i] }
        }
        radarCache[step] = filled
        return filled
    }

    private suspend fun modelAt(step: Int, member: Int): FloatArray = repo.readFrameTimed(store!!, product!!, step, member).values

    /** The members the grid shows, best match first (member order until the ranking is there), at most [CompareState.gridCount]. */
    private fun gridMembers(st: CompareState): List<Int> {
        val order = if (st.ranks.isNotEmpty()) st.ranks.map { it.index } else st.members.indices.toList()
        return order.take(st.gridCount.coerceAtLeast(1))
    }

    /** A member's name as a title: "REFS M01" and "RRFS" as they are, "HRRR" as "REFS HRRR" (the model runs in the REFS store). */
    private fun memberTitle(name: String?) = (name ?: "member").let { if (it.startsWith("REFS") || it.startsWith("RRFS")) it else "REFS $it" }

    private fun diffProduct(p: Product) = p.copy(id = "cref_difference", title = "Model minus radar", hasMembers = false, style = differenceStyle)

    /** One pane: drawn from the cache when it has been drawn before, else from [values] (null: nothing to draw). */
    private suspend fun pane(key: String, prod: Product, style: Style, v: View, values: suspend () -> FloatArray?): Bitmap? {
        cacheGet(key)?.let { return it }
        val data = values() ?: return null
        val bitmap = withContext(Dispatchers.Default) { renderer.render(prod, style, data, emptyList(), v, dark) }
        cachePut(key, bitmap)
        return bitmap
    }

    /** Draws the shown time and member: the model, the radar and their difference (or the grid of members), all on the same cells. */
    private suspend fun showCurrent() {
        val st = _state.value
        val p = product ?: return
        val step = timeSteps.getOrNull(st.timeIndex) ?: return
        update { it.copy(busy = true, status = "Drawing…") }
        val obs = radarAt(step)
        val file = obs?.let { nearestFile(files, st.times.getOrNull(st.timeIndex) ?: 0L, 360) }
        if (st.mode == CompareMode.Grid) { showGrid(st, p, step, obs, file); return }
        val full = view
        val radarMap = obs?.let { pane("R|$step|1|$dark", p, p.style, full) { it } }
        val modelMap = pane("M|$step|${st.member}|1|$dark", p, p.style, full) { modelAt(step, st.member) }
        val diffMap = obs?.let { o -> pane("D|$step|${st.member}|1|$dark", diffProduct(p), differenceStyle, full) { CompareScore.difference(modelAt(step, st.member), o) } }
        update { it.copy(model = modelMap, obs = radarMap, diff = diffMap, obsTime = file?.validTime, modelTime = it.times.getOrNull(it.timeIndex), busy = false, status = null, error = if (obs == null) "No radar frame within 6 minutes of that time." else null) }
    }

    /** The grid: the radar, then the best members in order, each as soon as it is drawn (larger layouts are drawn at half the cells to keep the memory down). */
    private suspend fun showGrid(st: CompareState, p: Product, step: Int, obs: FloatArray?, file: MrmsFile?) {
        val members = gridMembers(st)
        val diff = st.gridDiff
        val dec = if (members.size + (if (diff) 0 else 1) > 4) 2 else 1
        val v = view.decimated(dec)
        fun rankLabel(m: Int): String {
            val at = st.ranks.indexOfFirst { it.index == m }
            val score = st.ranks.getOrNull(at)?.headline?.takeIf { !it.isNaN() }
            return (if (score != null) "${at + 1}. " else "") + (st.members.getOrNull(m) ?: "member") + (if (score != null) "  %.2f".format(score) else "")
        }
        val labels = (if (diff) emptyList() else listOf("Radar")) + members.map { if (diff) "${rankLabel(it)}  minus radar" else rankLabel(it) }
        update { it.copy(tiles = labels.map { l -> PaneImage(l, null) }, obsTime = file?.validTime, modelTime = it.times.getOrNull(it.timeIndex), error = if (obs == null) "No radar frame within 6 minutes of that time." else null) }
        fun set(i: Int, bitmap: Bitmap?) = update { s -> s.copy(tiles = s.tiles.mapIndexed { k, t -> if (k == i) PaneImage(t.label, bitmap) else t }) }
        var i = 0
        if (!diff) { set(i, obs?.let { pane("R|$step|$dec|$dark", p, p.style, v) { it } }); i += 1 }
        for (m in members) {
            val bitmap = if (diff) obs?.let { o -> pane("D|$step|$m|$dec|$dark", diffProduct(p), differenceStyle, v) { CompareScore.difference(modelAt(step, m), o) } }
            else pane("M|$step|$m|$dec|$dark", p, p.style, v) { modelAt(step, m) }
            set(i, bitmap); i += 1
        }
        update { it.copy(busy = false, status = null) }
    }

    private fun startScoring() {
        scoreJob?.cancel()
        scoreJob = viewModelScope.launch {
            try { scoreMembers() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, status = null, error = e.message ?: e.toString()) } }
        }
    }

    /**
     * The map's pan and zoom, reported as fractions of the picture: once it rests, the members are scored again for the cells on screen (the best match over the
     * Plains is not always the best over the Southeast).
     */
    fun setViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewportF = floatArrayOf(left, top, right, bottom)
        val whole = (right - left) * (bottom - top) > 0.97f
        val next = if (whole) null else intArrayOf(
            kotlin.math.floor(left * columns).toInt(), kotlin.math.floor((1f - bottom) * rows).toInt(),
            kotlin.math.ceil(right * columns).toInt() - 1, kotlin.math.ceil((1f - top) * rows).toInt() - 1,   // the picture's top is north, and row 0 is south
        )
        val same = (next == null && region == null) || (next != null && region != null && next.indices.all { kotlin.math.abs(next[it] - region!![it]) <= 6 })
        if (same || store == null) return
        region = next
        scoreJob?.cancel()
        scoreJob = viewModelScope.launch {
            kotlinx.coroutines.delay(700)           // let the zoom settle
            try { scoreMembers() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    /** Scores every member's early hours against the radar, a few at a time; the list fills in as hours finish. */
    private suspend fun scoreMembers() {
        val area = region
        val s = store ?: return
        val p = product ?: return
        val members = _state.value.members
        if (members.isEmpty()) return
        update { it.copy(inView = area != null, scored = 0 to scoredSteps.size) }
        val scores = Array(members.size) { arrayOfNulls<MatchScore>(scoredSteps.size) }
        val gate = Semaphore(3)
        for ((k, step) in scoredSteps.withIndex()) {
            val obs = radarAt(step)
            if (obs != null) coroutineScope {
                members.indices.map { m ->
                    async(Dispatchers.Default) {
                        gate.withPermit {
                            val field = try { repo.readFrameTimed(s, p, step, m) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            if (field != null && field.fileTime != null) scores[m][k] = CompareScore.score(field.values, obs, columns, rows, area)
                        }
                    }
                }.awaitAll()
            }
            val ranked = rankMembers(members.indices.map { m -> MemberRank(members[m], m, scores[m].toList()) })
            update { it.copy(ranks = ranked, scored = (k + 1) to scoredSteps.size) }
        }
        // until a member is chosen by hand, the best match is the one shown
        val best = _state.value.ranks.firstOrNull { !it.headline.isNaN() }
        if (autoMember && best != null && best.index != _state.value.member) {
            update { it.copy(member = best.index) }
            if (_state.value.mode != CompareMode.Grid) showCurrent()
        }
        if (_state.value.mode == CompareMode.Grid) redraw()       // the grid shows the best members: in the new order
    }

    fun pickTime(index: Int) {
        if (index !in timeSteps.indices || index == _state.value.timeIndex) return
        update { it.copy(timeIndex = index) }
        redraw()
    }

    fun pickMember(member: Int) {
        autoMember = false
        update { it.copy(member = member) }
        redraw()
    }

    /**
     * The comparison as one picture on white: every pane drawn for the same area (the part of the map on screen, or all of it), the run, member and times, one color
     * key and the scores of the members shown. The panes are drawn again at a size that suits the area, not copied from the screen.
     */
    suspend fun buildShareFile(wholeMap: Boolean = false, withScores: Boolean = true): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val p = product ?: return@withContext null
        val step = timeSteps.getOrNull(st.timeIndex) ?: return@withContext null
        val obs = radarAt(step)
        val gm = gridMembers(st)
        val grid = st.mode == CompareMode.Grid
        val difference = st.mode == CompareMode.Difference || (grid && st.gridDiff)
        val n = when (st.mode) { CompareMode.Side -> 2; CompareMode.Overlay, CompareMode.Difference -> 1; CompareMode.Grid -> gm.size + (if (st.gridDiff) 0 else 1) }
        // the area: what is on screen (a zoomed-in part), else the whole map
        val vp = viewportF
        val whole = wholeMap || (vp[2] - vp[0]) * (vp[3] - vp[1]) > 0.97f
        val areaAspect = if (whole) columns.toFloat() / rows else (vp[2] - vp[0]) * columns / ((vp[3] - vp[1]) * rows)
        val ncols = when { n <= 1 -> 1; n == 2 -> if (areaAspect >= 1.25f) 1 else 2; n <= 4 -> 2; n <= 9 -> 3; else -> 4 }
        val tileW = 3600 / ncols
        val v = if (whole) View.CONUS.decimated(kotlin.math.ceil(columns.toDouble() / tileW).toInt().coerceAtLeast(1)) else {
            val col0 = kotlin.math.floor(vp[0] * columns).toInt().coerceIn(0, columns - 2)
            val row0 = kotlin.math.floor((1f - vp[3]) * rows).toInt().coerceIn(0, rows - 2)           // the picture's top is north
            val cols = (kotlin.math.ceil(vp[2] * columns).toInt() - col0).coerceIn(2, columns - col0)
            val rws = (kotlin.math.ceil((1f - vp[1]) * rows).toInt() - row0).coerceIn(2, rows - row0)
            View("Export", col0, row0, cols, rws, (tileW / cols).coerceIn(1, 8), 1)
        }
        fun draw(prod: Product, style: Style, values: FloatArray) = renderer.render(prod, style, values, emptyList(), v, dark = false, background = 0xFFFFFFFF.toInt())
        fun rank(m: Int): Pair<Int, Double?> { val at = st.ranks.indexOfFirst { it.index == m }; return at to st.ranks.getOrNull(at)?.headline?.takeIf { !it.isNaN() } }
        fun rankLabel(m: Int): String { val (at, score) = rank(m); return (if (score != null) "${at + 1}. " else "") + memberTitle(st.members.getOrNull(m)) + (if (score != null) "  %.2f".format(score) else "") }
        val panes = ArrayList<Pair<String, Bitmap?>>()
        when (st.mode) {
            CompareMode.Side -> {
                panes += "Radar (MRMS)" to obs?.let { draw(p, p.style, it) }
                panes += rankLabel(st.member) to draw(p, p.style, modelAt(step, st.member))
            }
            CompareMode.Overlay -> {
                val out = draw(p, p.style, modelAt(step, st.member)).copy(Bitmap.Config.ARGB_8888, true)
                if (obs != null) {
                    // the radar over the model, on white where there is no echo: drawn on its own transparent layer so only its echoes go over
                    val layer = renderer.render(p, p.style, obs, emptyList(), v, dark = false, background = 0x00FFFFFF)
                    Canvas(out).drawBitmap(layer, 0f, 0f, Paint().apply { alpha = (st.opacity * 255).toInt() })
                }
                panes += "${memberTitle(st.members.getOrNull(st.member))} under, radar over (${(st.opacity * 100).toInt()} %)" to out
            }
            CompareMode.Difference -> panes += "${memberTitle(st.members.getOrNull(st.member))} minus radar" to obs?.let { draw(diffProduct(p), differenceStyle, CompareScore.difference(modelAt(step, st.member), it)) }
            CompareMode.Grid -> {
                if (!st.gridDiff) panes += "Radar (MRMS)" to obs?.let { draw(p, p.style, it) }
                for (m in gm) panes += (if (st.gridDiff) "${rankLabel(m)}  minus radar" else rankLabel(m)) to
                    (if (st.gridDiff) obs?.let { draw(diffProduct(p), differenceStyle, CompareScore.difference(modelAt(step, m), it)) } else draw(p, p.style, modelAt(step, m)))
            }
        }
        val tw = v.pixelWidth; val th = v.pixelHeight
        val gap = maxOf(6, tw / 120)
        val nrows = (panes.size + ncols - 1) / ncols
        val sheet = Bitmap.createBitmap(ncols * tw + (ncols - 1) * gap, nrows * th + (nrows - 1) * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.WHITE)
        val labelSize = maxOf(16f, tw / 34f)
        val ink = Paint().apply { isAntiAlias = true; textSize = labelSize; color = Color.WHITE; isFakeBoldText = true }
        val box = Paint().apply { color = 0xAA000000.toInt() }
        for ((i, pr) in panes.withIndex()) {
            val x = (i % ncols) * (tw + gap).toFloat(); val y = (i / ncols) * (th + gap).toFloat()
            pr.second?.let { c.drawBitmap(it, x, y, null) }
            val w = ink.measureText(pr.first)
            c.drawRect(x + labelSize * 0.4f, y + labelSize * 0.4f, x + labelSize * 0.4f + w + labelSize * 0.6f, y + labelSize * 2.0f, box)
            c.drawText(pr.first, x + labelSize * 0.7f, y + labelSize * 1.5f, ink)
        }
        val mode = when (st.mode) { CompareMode.Side -> "side by side"; CompareMode.Overlay -> "overlay"; CompareMode.Difference -> "difference"; CompareMode.Grid -> if (st.gridDiff) "differences, ${gm.size} members" else "radar and ${gm.size} members" }
        val legendStyle = if (difference) differenceStyle else p.style
        val header = buildString {
            st.run?.let { append("REFS ${it.label}") }
            st.times.getOrNull(st.timeIndex)?.let { append("  ·  f%02d valid %s".format(st.hours.getOrElse(st.timeIndex) { 0 }, simplemodelview.formatClock(it))) }
            st.obsTime?.let { append("  ·  radar ${simplemodelview.formatClock(it)}") }
            append(if (whole) "  ·  whole map" else "  ·  the area on screen")
        }
        val framed = renderer.shareImage(sheet, p.copy(title = "Radar vs REFS reflectivity, $mode", style = legendStyle), legendStyle, header, emptyList())
        // the scores of the members shown (the best six when one is)
        val scored = if (!withScores) emptyList() else if (grid) gm.mapNotNull { m -> st.ranks.firstOrNull { it.index == m } } else st.ranks.take(6)
        // the table is the same size whatever the layout (a picture of three columns is wide, the table need not be): a fixed text size, the names in a column as wide as the longest, then a fixed width for each score
        val textSize = 30f
        val x = maxOf(12f, framed.width / 90f)
        val bold = Paint().apply { isAntiAlias = true; color = Color.BLACK; this.textSize = textSize; isFakeBoldText = true }
        val plain = Paint().apply { isAntiAlias = true; color = Color.BLACK; this.textSize = textSize }
        val hoursText = st.scoredHours.joinToString("/") { "f%02d".format(it) }
        val nameWidth = maxOf(bold.measureText("Initial-condition match"), scored.map { r -> plain.measureText("${st.ranks.indexOf(r) + 1}. ${r.name}") }.maxOrNull() ?: 0f) + textSize * 1.2f
        val cell = textSize * 2.6f
        // the notes go under the table in small print
        val noteSize = textSize * 0.62f
        val note = Paint().apply { isAntiAlias = true; color = 0xFF555555.toInt(); this.textSize = noteSize }
        val tableWidth = nameWidth + st.scoredHours.size * cell + textSize * 0.4f + bold.measureText("mean")
        // the notes are broken to the table's width, not run on past it
        fun wrap(text: String): List<String> {
            val lines = ArrayList<String>(); var line = ""
            for (word in text.split(' ')) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && note.measureText(tryLine) > tableWidth) { lines += line; line = word } else line = tryLine
            }
            if (line.isNotEmpty()) lines += line
            return lines
        }
        val noteLines = wrap("Neighborhood score (fractions skill score): the share of 35 dBZ echo agreeing with the radar within 30 km, 1 is a perfect match. " +
            "Columns: $hoursText, then the mean, which sets the order. Scored over ${if (st.inView) "the area on screen" else "the whole map"}.")
        val footer = if (scored.isEmpty()) 0 else (textSize * (scored.size + 2.4f) + noteSize * (noteLines.size * 1.25f + 0.6f)).toInt()
        val out = Bitmap.createBitmap(framed.width, framed.height + footer, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawColor(Color.WHITE)
        oc.drawBitmap(framed, 0f, 0f, null)
        if (scored.isNotEmpty()) {
            var y = framed.height + textSize * 1.4f
            oc.drawText("Initial-condition match", x, y, bold)
            for ((i, h) in st.scoredHours.withIndex()) oc.drawText("f%02d".format(h), x + nameWidth + i * cell, y, bold)
            oc.drawText("mean", x + nameWidth + st.scoredHours.size * cell + textSize * 0.4f, y, bold)
            for (r in scored) {
                y += textSize * 1.15f
                fun f(d: Double?) = if (d == null || d.isNaN()) "-" else "%.2f".format(d)
                oc.drawText("${st.ranks.indexOf(r) + 1}. ${r.name}", x, y, plain)
                for ((i, sc) in r.scores.withIndex()) oc.drawText(f(sc?.fss), x + nameWidth + i * cell, y, plain)
                oc.drawText(f(r.headline), x + nameWidth + r.scores.size * cell + textSize * 0.4f, y, bold)
            }
            y += textSize * 0.5f
            for (line in noteLines) { y += noteSize * 1.25f; oc.drawText(line, x, y, note) }
        }
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "compare_${st.run?.label?.filter { it.isLetterOrDigit() } ?: "run"}_${mode.filter { it.isLetterOrDigit() || it == ' ' }.replace(' ', '_')}.png"
        File(dir, name).also { f -> f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun setMode(mode: CompareMode) {
        if (_state.value.mode == mode) return
        update { it.copy(mode = mode) }
        if (store != null) redraw()
    }
    fun setGridCount(count: Int) { update { it.copy(gridCount = count) }; if (store != null) redraw() }
    fun setGridDiff(on: Boolean) { update { it.copy(gridDiff = on) }; if (store != null) redraw() }
    fun setOpacity(opacity: Float) = update { it.copy(opacity = opacity.coerceIn(0f, 1f)) }

    fun setDark(isDark: Boolean) {
        if (dark == isDark) return
        dark = isDark
        if (store != null) redraw()
    }

    private fun redraw() {
        showJob?.cancel()
        showJob = viewModelScope.launch {
            try { showCurrent() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { it.copy(busy = false, status = null, error = e.message) } }
        }
    }
}
