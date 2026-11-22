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
import simplemodelview.RefsException
import simplemodelview.View
import simplemodelview.cycleStart
import simplemodelview.differenceStyle
import simplemodelview.earlyTimeIndices
import simplemodelview.gridCoverBox
import simplemodelview.nearestFile
import simplemodelview.rankMembers
import java.io.File

enum class CompareMode { Side, Overlay, Difference }

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

    init { load() }

    fun load() {
        loadJob?.cancel(); showJob?.cancel(); scoreJob?.cancel()
        radarCache.clear(); autoMember = true
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

    /** Draws the shown time and member: the model, the radar and their difference, all on the same cells. */
    private suspend fun showCurrent() {
        val st = _state.value
        val p = product ?: return
        val step = timeSteps.getOrNull(st.timeIndex) ?: return
        update { it.copy(busy = true, status = "Drawing…") }
        val obs = radarAt(step)
        val model = modelAt(step, st.member)
        val bitmaps = withContext(Dispatchers.Default) {
            val names = emptyList<String>()
            val modelMap = renderer.render(p, p.style, model, names, view, dark)
            val obsMap = obs?.let { renderer.render(p, p.style, it, names, view, dark) }
            val diffMap = obs?.let {
                val diffProduct = p.copy(id = "cref_difference", title = "Model minus radar", hasMembers = false, style = differenceStyle)
                renderer.render(diffProduct, differenceStyle, CompareScore.difference(model, it), names, view, dark)
            }
            Triple(modelMap, obsMap, diffMap)
        }
        val s = store
        val file = obs?.let { nearestFile(files, _state.value.times.getOrNull(st.timeIndex) ?: 0L, 360) }
        update { it.copy(model = bitmaps.first, obs = bitmaps.second, diff = bitmaps.third, obsTime = file?.validTime, modelTime = it.times.getOrNull(it.timeIndex), busy = false, status = null, error = if (obs == null) "No radar frame within 6 minutes of that time." else null) }
        if (s == null) return
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
            showCurrent()
        }
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

    /** The comparison as one picture: what the screen shows (both panes, the overlay or the difference) over the whole map, with the run, member and times, a color key and the best members' scores. */
    suspend fun buildShareFile(): File? = withContext(Dispatchers.Default) {
        val st = _state.value
        val p = product ?: return@withContext null
        val model = st.model ?: return@withContext null
        val radarMap = st.obs
        val width = model.width
        fun label(canvas: Canvas, text: String, size: Float) {
            val paint = Paint().apply { isAntiAlias = true; textSize = size; color = Color.WHITE; isFakeBoldText = true }
            val w = paint.measureText(text)
            canvas.drawRect(size * 0.4f, size * 0.4f, size * 0.4f + w + size * 0.6f, size * 2.0f, Paint().apply { color = 0xAA000000.toInt() })
            canvas.drawText(text, size * 0.7f, size * 1.5f, paint)
        }
        val labelSize = maxOf(16f, width / 70f)
        val (picture, style, mode) = when (st.mode) {
            CompareMode.Side -> {
                val out = Bitmap.createBitmap(width, model.height * 2, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                if (radarMap != null) c.drawBitmap(radarMap, 0f, 0f, null)
                c.drawBitmap(model, 0f, model.height.toFloat(), null)
                label(c, "Radar (MRMS)", labelSize)
                c.save(); c.translate(0f, model.height.toFloat()); label(c, "REFS ${st.members.getOrNull(st.member) ?: ""}", labelSize); c.restore()
                Triple(out, p.style, "side by side")
            }
            CompareMode.Overlay -> {
                val out = model.copy(Bitmap.Config.ARGB_8888, true)
                if (radarMap != null) Canvas(out).drawBitmap(radarMap, 0f, 0f, Paint().apply { alpha = (st.opacity * 255).toInt() })
                label(Canvas(out), "REFS under, radar over (radar ${(st.opacity * 100).toInt()} %)", labelSize)
                Triple(out, p.style, "overlay")
            }
            CompareMode.Difference -> {
                val out = (st.diff ?: return@withContext null).copy(Bitmap.Config.ARGB_8888, true)
                label(Canvas(out), "REFS minus radar", labelSize)
                Triple(out, differenceStyle, "difference")
            }
        }
        val titleProduct = p.copy(title = "Radar vs REFS reflectivity, $mode", style = style)
        val header = buildString {
            st.run?.let { append("REFS ${it.label}") }
            st.members.getOrNull(st.member)?.let { append("  ·  $it") }
            st.times.getOrNull(st.timeIndex)?.let { append("  ·  f%02d valid %s".format(st.hours.getOrElse(st.timeIndex) { 0 }, simplemodelview.formatClock(it))) }
            st.obsTime?.let { append("  ·  radar ${simplemodelview.formatClock(it)}") }
        }
        val framed = renderer.shareImage(picture, titleProduct, style, header, emptyList())
        // the best matches under the picture
        val rows = st.ranks.take(6)
        val textSize = maxOf(18f, width / 60f)
        val footer = if (rows.isEmpty()) 0 else (textSize * (rows.size + 3.2f)).toInt()
        val out = Bitmap.createBitmap(framed.width, framed.height + footer, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(framed, 0f, 0f, null)
        if (rows.isNotEmpty()) {
            val bold = Paint().apply { isAntiAlias = true; color = Color.BLACK; this.textSize = textSize; isFakeBoldText = true }
            val plain = Paint().apply { isAntiAlias = true; color = Color.BLACK; this.textSize = textSize }
            val x = maxOf(12f, width / 90f)
            var y = framed.height + textSize * 1.4f
            c.drawText("Best initial-condition match ${if (st.inView) "(area on screen)" else "(whole map)"}: neighborhood score at 35 dBZ, 45 km, f${st.scoredHours.joinToString("/") { "%02d".format(it) }}, mean", x, y, bold)
            for ((i, r) in rows.withIndex()) {
                y += textSize * 1.1f
                fun f(v: Double?) = if (v == null || v.isNaN()) "-" else "%.2f".format(v)
                c.drawText("${i + 1}. ${r.name}", x, y, plain)
                c.drawText(r.scores.joinToString("   ") { f(it?.fss) } + "     " + f(r.headline), width * 0.62f, y, plain)
            }
        }
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "compare_${st.run?.label?.filter { it.isLetterOrDigit() } ?: "run"}_${(st.members.getOrNull(st.member) ?: "member").filter { it.isLetterOrDigit() }}_$mode.png".replace(' ', '_')
        File(dir, name).also { f -> f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun setMode(mode: CompareMode) = update { it.copy(mode = mode) }
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
