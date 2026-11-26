package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import simplemodelview.Grid
import simplemodelview.Kind
import simplemodelview.OutlookChoice
import simplemodelview.OutlookData
import simplemodelview.OutlookRepository
import simplemodelview.OutlookType
import simplemodelview.Place
import simplemodelview.Product
import simplemodelview.SpcDiscussion
import simplemodelview.SpcDiscussionText
import simplemodelview.Stop
import simplemodelview.Style
import simplemodelview.View
import simplemodelview.formatClock
import simplemodelview.outlookAt
import simplemodelview.placeLabel

/** What an outlook of [day] can show, for the kind chips: the hazards for Days 1 and 2, the categorical map and the severe chance for Day 3, the severe chance after it. */
internal fun outlookKindsFor(day: Int): List<OutlookType> = when {
    day <= 2 -> listOf(OutlookType.Categorical, OutlookType.Tornado, OutlookType.Wind, OutlookType.Hail)
    day == 3 -> listOf(OutlookType.Categorical, OutlookType.Probability)
    else -> listOf(OutlookType.Probability)
}

internal fun outlookChoiceOf(day: Int, type: OutlookType): OutlookChoice = OutlookChoice(day, when {
    day >= 4 -> OutlookType.Probability
    day == 3 -> if (type == OutlookType.Categorical) OutlookType.Categorical else OutlookType.Probability
    type == OutlookType.Probability -> OutlookType.Categorical
    else -> type
})

data class OutlookBrowserState(
    val day: Int = 1,
    val type: OutlookType = OutlookType.Categorical,
    val busy: Boolean = true,
    val note: String? = null,
    val map: Bitmap? = null,
    val data: OutlookData? = null,
    val crosshair: Pair<Float, Float>? = null,
    val tapped: String? = null,
    val placeRisks: List<Pair<String, String>> = emptyList(),
    val discussion: SpcDiscussion? = null,
    val discussionNote: String? = null,
)

/**
 * The SPC outlook as a page of its own: the whole country with the outlook areas of a chosen day and kind, the time it was issued, and the forecasters' discussion
 * under it. Files are SPC's public GIS data and the Iowa Mesonet's copy of the text product (see [OutlookRepository] and [SpcDiscussionText]).
 */
class OutlookBrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val core = AppContainer.core
    private val repo = OutlookRepository(AppContainer.http)
    private val renderer = MapRenderer(core, AppContainer.lines)
    private val view = View.CONUS.decimated(1)
    private val _state = MutableStateFlow(OutlookBrowserState())
    val state: StateFlow<OutlookBrowserState> = _state
    private inline fun update(f: (OutlookBrowserState) -> OutlookBrowserState) { _state.value = f(_state.value) }

    private var dark = false
    private var job: Job? = null
    private var textJob: Job? = null
    private val loaded = HashMap<String, Pair<OutlookData, Long>>()
    private val texts = HashMap<Int, Pair<SpcDiscussion, Long>>()

    init { show() }

    fun pickDay(day: Int) {
        val kinds = outlookKindsFor(day)
        update { it.copy(day = day, type = if (it.type in kinds) it.type else kinds.first(), crosshair = null, tapped = null) }
        show()
    }

    fun pickType(type: OutlookType) { update { it.copy(type = type, crosshair = null, tapped = null) }; show(textToo = false) }

    fun setDark(isDark: Boolean) { if (dark != isDark) { dark = isDark; if (_state.value.data != null) show(textToo = false) } }

    fun refresh() { loaded.clear(); texts.clear(); show() }

    private fun show(textToo: Boolean = true) {
        job?.cancel()
        val st = _state.value
        val choice = outlookChoiceOf(st.day, st.type)
        job = viewModelScope.launch {
            update { it.copy(busy = true, note = null) }
            try {
                val cached = loaded[choice.id]?.takeIf { System.currentTimeMillis() - it.second < 15 * 60_000L }
                val data = cached?.first ?: repo.load(choice).also { loaded[choice.id] = it to System.currentTimeMillis() }
                val bitmap = withContext(Dispatchers.Default) { draw(data) }
                val places = AppContainer.places.places.value.map { p -> p.name to riskAt(data, p) }
                update { it.copy(data = data, map = bitmap, busy = false, placeRisks = places) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, data = null, map = null, note = e.message ?: "The outlook could not be read.") }
            }
        }
        if (textToo) loadText(st.day)
    }

    private fun loadText(day: Int) {
        textJob?.cancel()
        texts[day]?.takeIf { System.currentTimeMillis() - it.second < 15 * 60_000L }?.let { update { s -> s.copy(discussion = it.first, discussionNote = null) }; return }
        update { it.copy(discussion = null, discussionNote = null) }
        textJob = viewModelScope.launch {
            try {
                val result = AppContainer.http.get(SpcDiscussionText.url(day))
                if (result.status != 200) throw IllegalStateException("The discussion could not be read (HTTP ${result.status}).")
                val parsed = SpcDiscussionText.parse(result.body.decodeToString()) ?: throw IllegalStateException("SPC has no discussion posted for this day yet.")
                texts[day] = parsed to System.currentTimeMillis()
                if (_state.value.day == day) update { it.copy(discussion = parsed, discussionNote = null) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (_state.value.day == day) update { it.copy(discussionNote = e.message ?: "The discussion could not be read.") }
            }
        }
    }

    /** The country with the outlook areas, no data under them: a transparent field on the REFS grid gives the map's background and lines. */
    private fun draw(data: OutlookData): Bitmap {
        val grid = Grid.REFS
        val empty = Product("outlook_base", "SPC outlook", "", Style(Kind.Banded, listOf(Stop(0.0, 0xFFFFFF), Stop(1.0, 0x000000)), units = ""), false, null, grid)
        return renderer.render(empty, empty.style, FloatArray(grid.cells) { Float.NaN }, emptyList(), view, dark, alerts = outlookDrawsOfData(core, data, view), counties = 2)
    }

    private fun riskAt(data: OutlookData, place: Place): String = outlookAt(data.shapes, place.lat, place.lon)?.title ?: "none"

    /** A tap on the picture, as fractions of it: what the outlook says there. */
    fun onTap(fx: Float, fy: Float) {
        val data = _state.value.data ?: return
        if (fx !in 0f..1f || fy !in 0f..1f) { update { it.copy(crosshair = null, tapped = null) }; return }
        val column = (fx * view.cols).coerceIn(0f, view.cols - 1f).toDouble()
        val row = (view.rows - 1 - fy * view.rows).coerceIn(0f, view.rows - 1f).toDouble()
        val ll = core.gridToLatLon(Grid.REFS, column, row)
        val shape = outlookAt(data.shapes, ll[0], ll[1])
        update { it.copy(crosshair = fx to fy, tapped = "${placeLabel(ll[0], ll[1])}: " + (shape?.title ?: "no risk area here")) }
    }
}

/** The outlook browser page. */
@Composable
internal fun OutlookScreen(vm: OutlookBrowserViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { vm.setDark(dark) }
    var full by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SPC outlook", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (s.busy) BusySpinner(Modifier.padding(end = 2.dp))
                TextButton(onClick = vm::refresh, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Reload") }
                PageMenu("outlook", onOpen)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 1..8) FilterChip(selected = s.day == d, onClick = { vm.pickDay(d) }, label = { Text("Day $d") })
            }
            val kinds = outlookKindsFor(s.day)
            if (kinds.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in kinds) FilterChip(selected = s.type == t, onClick = { vm.pickType(t) }, label = { Text(if (t == OutlookType.Probability) "Severe chance" else t.label) })
            }
            val data = s.data
            val times = data?.let { d -> listOfNotNull(d.issued?.let { "Issued ${formatClock(it, System.currentTimeMillis() / 1000)}" }, d.valid?.let { v -> "valid ${formatClock(v, System.currentTimeMillis() / 1000)}" + (d.expires?.let { " to ${formatClock(it, System.currentTimeMillis() / 1000)}" } ?: "") }).joinToString("  ·  ") }
            Text(s.note ?: times ?: " ", fontSize = 12.sp, color = if (s.note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            Box(Modifier.fillMaxWidth().height(300.dp).background(if (dark) Color(0xFF202428) else Color(0xFFEDEFF2))) {
                s.map?.let { ZoomableMap(it, s.crosshair, "SPC outlook map", onViewport = { _, _, _, _ -> }, onTap = vm::onTap) }
            }
            s.tapped?.let { Text(it, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            data?.let { d ->
                val levels = d.shapes.filter { !it.hatched }.sortedBy { it.rank }
                if (levels.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (l in levels) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(Color(0xFF000000.toInt() or l.color)))
                        Text(" " + l.title.removeSuffix(" Risk"), fontSize = 11.sp)
                    }
                } else if (!s.busy) Text("No risk areas on this map.", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (s.placeRisks.isNotEmpty()) Text("Your places: " + s.placeRisks.joinToString("  ·  ") { (n, r) -> "$n – ${r.removeSuffix(" Risk")}" }, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Discussion(s.discussion, s.discussionNote, full) { full = !full }
        }
    }
}

/** The forecasters' discussion: the headline and summary first, the rest on a tap. */
@Composable
private fun Discussion(d: SpcDiscussion?, note: String?, full: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("The forecasters' discussion", fontWeight = FontWeight.SemiBold)
        if (d == null) { Text(note ?: "Reading the discussion…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
        Text(listOfNotNull(d.title, d.issued, d.valid).joinToString("  ·  "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        d.headline?.let { Text(it, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
        val shown = if (full) d.sections else d.sections.take(1)
        for (sec in shown) {
            Text(sec.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            for (p in sec.paragraphs) Text(p, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (d.sections.size > 1) TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (full) "Show less" else "Read the full discussion") }
        if (full) d.forecaster?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
