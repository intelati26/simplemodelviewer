package io.github.intelati26.simplemodelview

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import simplemodelview.MesoscaleDiscussion
import simplemodelview.OutlookData
import simplemodelview.OutlookRepository
import simplemodelview.OutlookType
import simplemodelview.Place
import simplemodelview.SpcDiscussion
import simplemodelview.SpcDiscussionText
import simplemodelview.SpcMesoscale
import simplemodelview.TodayText
import simplemodelview.formatClock
import simplemodelview.outlookAt
import simplemodelview.placeLabel

/** What SPC says about one place for one outlook day. */
data class PlaceOutlook(
    val level: Int,
    val categoryTitle: String?,
    val tornado: Int?, val wind: Int?, val hail: Int?,     // percent; null where the day has no such map
    val severeChance: Int?,                                // Day 3: the total severe chance
    val top: Pair<String, Int>?,
)

data class TodayState(
    val place: Place? = null,
    val places: List<Place> = emptyList(),
    val day: Int = 1,
    val busy: Boolean = true,
    val error: String? = null,
    val outlook: PlaceOutlook? = null,
    val discussion: SpcDiscussion? = null,
    val covering: List<MesoscaleDiscussion> = emptyList(),
    val nearby: List<MesoscaleDiscussion> = emptyList(),
    val mesoNote: String? = null,
    val thunder: ThunderData? = null,
    val thunderNote: String? = null,
    val thunderBusy: Boolean = false,
    val line: Int = 25,
    val updated: Long? = null,
)

/**
 * The Today screen's data for the active place (a saved place chosen here): the day's SPC outlook read at that point, the forecasters' discussion headline, and the
 * mesoscale discussions that are active now, those over the place first. Files are SPC's public GIS data and the Iowa Mesonet's copy of the text products.
 */
class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppContainer.prefs
    private val repo = OutlookRepository(AppContainer.http)
    private val _state = MutableStateFlow(TodayState(line = AppContainer.prefs.getInt("today_thunder_line", simplemodelview.ThunderTiming.DEFAULT_LINE)))
    val state: StateFlow<TodayState> = _state
    private inline fun update(f: (TodayState) -> TodayState) { _state.value = f(_state.value) }
    private var job: Job? = null
    private var thunderJob: Job? = null
    private val thunderReader = ThunderReader(AppContainer.core, AppContainer.repo)

    init {
        viewModelScope.launch { AppContainer.places.places.collect { list -> update { s -> s.copy(places = list, place = pick(list, s.place?.name ?: prefs.getString("today_place", null))) }; load() } }
    }

    private fun pick(list: List<Place>, name: String?): Place? = list.firstOrNull { it.name == name } ?: list.firstOrNull()

    fun pickPlace(place: Place) { prefs.edit().putString("today_place", place.name).apply(); update { it.copy(place = place) }; load() }
    fun pickDay(day: Int) { update { it.copy(day = day) }; load() }
    fun setLine(line: Int) { prefs.edit().putInt("today_thunder_line", line).apply(); update { it.copy(line = line) } }
    fun refresh() = load()

    private fun loadThunder(place: Place?) {
        thunderJob?.cancel()
        if (place == null) { update { it.copy(thunder = null, thunderNote = null, thunderBusy = false) }; return }
        thunderJob = viewModelScope.launch {
            update { it.copy(thunderBusy = true, thunderNote = null) }
            try {
                val data = thunderReader.read(place, System.currentTimeMillis() / 1000)
                update { it.copy(thunder = data, thunderNote = if (data == null) "The model's thunder chance is not available yet." else null, thunderBusy = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(thunder = null, thunderNote = e.message ?: "The thunder chance could not be read.", thunderBusy = false) }
            }
        }
    }

    private fun load() {
        job?.cancel()
        loadThunder(_state.value.place)
        val st = _state.value
        val place = st.place
        job = viewModelScope.launch {
            update { it.copy(busy = true, error = null) }
            try {
                coroutineScope {
                    val outlookJob = async { place?.let { readOutlook(it, st.day) } }
                    val textJob = async { try { readDiscussion(st.day) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                    val mcdJob = async { try { readMesoscale() } catch (e: CancellationException) { throw e } catch (e: Exception) { null to (e.message ?: "The mesoscale discussions could not be read.") } }
                    val outlook = outlookJob.await()
                    val (mcds, note) = mcdJob.await()
                    val now = System.currentTimeMillis() / 1000
                    val active = (mcds ?: emptyList()).filter { it.activeAt(now) }
                    val covering = if (place == null) emptyList() else active.filter { it.covers(place.lat, place.lon) }
                    update { it.copy(outlook = outlook, discussion = textJob.await(), covering = covering, nearby = active - covering.toSet(), mesoNote = note, busy = false, updated = now) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(busy = false, error = e.message ?: "Today could not be read.") }
            }
        }
    }

    private fun atPoint(data: OutlookData, p: Place) = outlookAt(data.shapes, p.lat, p.lon)

    private suspend fun readOutlook(p: Place, day: Int): PlaceOutlook = coroutineScope {
        val cat = async { repo.load(outlookChoiceOf(day, OutlookType.Categorical)) }
        val hazards = if (day <= 2) listOf(OutlookType.Tornado, OutlookType.Wind, OutlookType.Hail).map { t -> async { try { repo.load(outlookChoiceOf(day, t)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } } } else emptyList()
        val severe = if (day == 3) async { try { repo.load(outlookChoiceOf(3, OutlookType.Probability)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } } else null
        val categoryTitle = atPoint(cat.await(), p)?.title
        // no area on a hazard map is 0 %, but a map that could not be read is unknown
        fun pct(d: OutlookData?): Int? = d?.let { TodayText.percent(atPoint(it, p)?.title) ?: 0 }
        val (t, w, h) = hazards.map { it.await() }.map { d -> pct(d) }.let { if (it.size == 3) Triple(it[0], it[1], it[2]) else Triple(null, null, null) }
        PlaceOutlook(TodayText.level(categoryTitle), categoryTitle, t, w, h, severe?.await()?.let { pct(it) }, TodayText.topThreat(t, w, h))
    }

    private suspend fun readDiscussion(day: Int): SpcDiscussion? {
        val r = AppContainer.http.get(SpcDiscussionText.url(day))
        return if (r.status == 200) SpcDiscussionText.parse(r.body.decodeToString()) else null
    }

    private suspend fun readMesoscale(): Pair<List<MesoscaleDiscussion>?, String?> {
        val r = AppContainer.http.get(SpcMesoscale.url(12))
        if (r.status != 200) return null to "The mesoscale discussions could not be read (HTTP ${r.status})."
        return SpcMesoscale.parseAll(r.body.decodeToString(), System.currentTimeMillis() / 1000) to null
    }
}

private val warn = Color(0xFFB3261E)

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** The Today screen: the outlook for your place in words, the mesoscale discussions over it, the forecasters' headline, and the way into everything else. */
@Composable
internal fun TodayScreen(vm: TodayViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    var placeMenu by remember { mutableStateOf(false) }
    var expandedMcd by remember { mutableStateOf<Int?>(null) }
    val now = System.currentTimeMillis() / 1000
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Today", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Box {
                        Text((s.place?.name ?: "Choose a place") + " ▾", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.clickable { placeMenu = true })
                        DropdownMenu(expanded = placeMenu, onDismissRequest = { placeMenu = false }) {
                            for (p in s.places) DropdownMenuItem(text = { Text(p.name) }, onClick = { placeMenu = false; vm.pickPlace(p) })
                            if (s.places.isEmpty()) DropdownMenuItem(text = { Text("Save a place on any map page first") }, onClick = { placeMenu = false })
                        }
                    }
                }
                if (s.busy) BusySpinner(Modifier.padding(end = 2.dp))
                TextButton(onClick = vm::refresh, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Reload") }
                PageMenu("today", onOpen)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((d, label) in listOf(1 to "Today", 2 to "Tomorrow", 3 to "Day 3")) FilterChip(selected = s.day == d, onClick = { vm.pickDay(d) }, label = { Text(label) })
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

                // ---- the outlook at the place
                Card {
                    Text("SPC outlook for ${s.place?.name ?: "your place"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val o = s.outlook
                    if (s.place == null) Text("Save a place to see what the outlook says for it.", fontSize = 14.sp)
                    else if (o == null) Text(if (s.busy) "Reading the outlook…" else "No outlook could be read.", fontSize = 14.sp)
                    else {
                        Text(TodayText.sentence(o.level), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (o.level >= 2) warn else MaterialTheme.colorScheme.onSurface)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp)) {
                            val colors = listOf(Color(0xFF97C459), Color(0xFFEF9F27), Color(0xFFD85A30), Color(0xFFE24B4A), Color(0xFF993556))
                            for (i in 1..5) Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(2.dp)).background(if (i == o.level) colors[i - 1] else colors[i - 1].copy(alpha = 0.25f)))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { for (n in listOf("Marginal", "Slight", "Enhanced", "Moderate", "High")) Text(n, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (s.day <= 2) {
                            Text("Tornado ${o.tornado ?: "–"}% · Wind ${o.wind ?: "–"}% · Hail ${o.hail ?: "–"}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TodayText.topThreatLine(o.top)?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                        } else o.severeChance?.let { Text("Severe chance here: $it%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onClick = { onOpen("outlook") }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("See the national outlook") }
                }

                // ---- thunder chance and timing
                ThunderCard(s.thunder, s.thunderNote, s.thunderBusy, s.place?.name, s.line, vm::setLine)

                // ---- mesoscale discussions
                if (s.covering.isNotEmpty() || s.nearby.isNotEmpty() || s.mesoNote != null) Card {
                    Text("Mesoscale discussions", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.mesoNote?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                    for (m in s.covering + s.nearby) {
                        val here = m in s.covering
                        Column(Modifier.fillMaxWidth().clickable { expandedMcd = if (expandedMcd == m.number) null else m.number }.padding(vertical = 4.dp)) {
                            Text("${if (here) "Covers your place · " else ""}MD ${m.number} · ${m.concerning}", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (here) warn else MaterialTheme.colorScheme.onSurface)
                            Text("${m.areas} · until ${formatClock(m.validTo, now)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (m.summary.isNotBlank()) Text(m.summary, fontSize = 13.sp, maxLines = if (expandedMcd == m.number) Int.MAX_VALUE else 2)
                            if (expandedMcd == m.number && m.discussion.isNotBlank()) Text(m.discussion, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                else if (!s.busy) Card { Text("Mesoscale discussions", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("None active right now.", fontSize = 14.sp) }

                // ---- the forecasters' words
                s.discussion?.let { d ->
                    Card {
                        Text("What the forecasters say", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        d.headline?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                        d.sections.firstOrNull()?.paragraphs?.firstOrNull()?.let { Text(it, fontSize = 13.sp, maxLines = 6) }
                        TextButton(onClick = { onOpen("outlook") }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Read the full discussion") }
                    }
                }

                // ---- the way into everything else
                Card {
                    Text("Look closer", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, name) in listOf("mrms" to "Radar", "outlook" to "Outlook", "spc" to "Models (REFS)", "compare" to "Radar vs model", "nbm" to "NBM", "gfs" to "GFS")) FilterChip(selected = false, onClick = { onOpen(id) }, label = { Text(name) })
                    }
                }
                s.updated?.let { Text("Read ${formatClock(it, now)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
