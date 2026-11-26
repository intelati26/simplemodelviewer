package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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

/** What SPC says about one place for one outlook day. */
data class PlaceOutlook(
    val level: Int,
    val categoryTitle: String?,
    val tornado: Int?, val wind: Int?, val hail: Int?,     // percent; null where the day has no such map
    val severeChance: Int?,                                // Day 3: the total severe chance
    val top: Pair<String, Int>?,
    val map: simplemodelview.OutlookData? = null,         // the categorical outlook's areas, for the small picture
    val panels: List<Pair<String, simplemodelview.OutlookData>> = emptyList(),   // the day's maps for the discussion card: categorical, then tornado / wind / hail (Day 3: severe)
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
    val wind: WindData? = null,
    val windNote: String? = null,
    val windBusy: Boolean = false,
    val gustLine: Int = 40,
    val point: PointForecast? = null,
    val pointNote: String? = null,
    val pointBusy: Boolean = false,
    val updated: Long? = null,
)

/**
 * The Today screen's data for the active place (a saved place chosen here): the day's SPC outlook read at that point, the forecasters' discussion headline, and the
 * mesoscale discussions that are active now, those over the place first. Files are SPC's public GIS data and the Iowa Mesonet's copy of the text products.
 */
class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppContainer.prefs
    private val repo = OutlookRepository(AppContainer.http)
    private val _state = MutableStateFlow(TodayState(line = AppContainer.prefs.getInt("today_thunder_line", simplemodelview.ThunderTiming.DEFAULT_LINE), gustLine = AppContainer.prefs.getInt("today_gust_line", simplemodelview.WindTiming.DEFAULT_LINE)))
    val state: StateFlow<TodayState> = _state
    private inline fun update(f: (TodayState) -> TodayState) { _state.value = f(_state.value) }
    private var job: Job? = null
    private var thunderJob: Job? = null
    private val thunderReader = ThunderReader(AppContainer.core, AppContainer.repo)
    private var windJob: Job? = null
    private var pointJob: Job? = null
    private val pointReader = PointForecastReader(AppContainer.nbmText, AppContainer.nws)
    private val windReader = WindReader(AppContainer.core, AppContainer.repo)

    init {
        viewModelScope.launch { AppContainer.places.places.collect { list -> update { s -> s.copy(places = list, place = pick(list, s.place?.name ?: prefs.getString("today_place", null))) }; load() } }
    }

    private fun pick(list: List<Place>, name: String?): Place? = list.firstOrNull { it.name == name } ?: list.firstOrNull()

    fun pickPlace(place: Place) { prefs.edit().putString("today_place", place.name).apply(); update { it.copy(place = place) }; load() }
    fun pickDay(day: Int) { update { it.copy(day = day) }; load() }
    fun setLine(line: Int) { prefs.edit().putInt("today_thunder_line", line).apply(); update { it.copy(line = line) } }
    fun setGustLine(line: Int) { prefs.edit().putInt("today_gust_line", line).apply(); update { it.copy(gustLine = line) } }
    fun refresh() = load()

    private fun loadPoint(place: Place?) {
        pointJob?.cancel()
        if (place == null) { update { it.copy(point = null, pointNote = null, pointBusy = false) }; return }
        pointJob = viewModelScope.launch {
            update { it.copy(pointBusy = true, pointNote = null) }
            try {
                val data = pointReader.read(place, System.currentTimeMillis() / 1000)
                update { it.copy(point = data, pointNote = if (data == null) "The National Blend forecast is not available for this place." else null, pointBusy = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(point = null, pointNote = e.message ?: "The forecast could not be read.", pointBusy = false) }
            }
        }
    }

    private fun loadWind(place: Place?) {
        windJob?.cancel()
        if (place == null) { update { it.copy(wind = null, windNote = null, windBusy = false) }; return }
        windJob = viewModelScope.launch {
            update { it.copy(windBusy = true, windNote = null) }
            try {
                val data = windReader.read(place, System.currentTimeMillis() / 1000)
                update { it.copy(wind = data, windNote = if (data == null) "The model's gust forecast is not available yet." else null, windBusy = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                update { it.copy(wind = null, windNote = e.message ?: "The gust forecast could not be read.", windBusy = false) }
            }
        }
    }

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
        loadWind(_state.value.place)
        loadPoint(_state.value.place)
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
        val catData = cat.await()
        val categoryTitle = atPoint(catData, p)?.title
        // no area on a hazard map is 0 %, but a map that could not be read is unknown
        fun pct(d: OutlookData?): Int? = d?.let { TodayText.percent(atPoint(it, p)?.title) ?: 0 }
        val hazardData = hazards.map { it.await() }
        val severeData = severe?.await()
        val (t, w, h) = hazardData.map { d -> pct(d) }.let { if (it.size == 3) Triple(it[0], it[1], it[2]) else Triple(null, null, null) }
        val panels = listOf("Categorical" to catData) + (if (day <= 2) listOf("Tornado", "Wind", "Hail").zip(hazardData) else listOf("Severe" to severeData)).map { (n, d) -> n to d }
        PlaceOutlook(TodayText.level(categoryTitle), categoryTitle, t, w, h, severeData?.let { pct(it) }, TodayText.topThreat(t, w, h), catData, panels.mapNotNull { (n, d) -> d?.let { n to it } })
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
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
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
    // Material's text styles carry a line height of about 1.5 times the size, which is most of the space between the lines here: 1.2 keeps the text as large (and following the system's font size) with less air
    androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.copy(lineHeight = 1.2.em, lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None))) {
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
                TextButton(onClick = { AppMode.set(!AppMode.simple) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (AppMode.simple) "Simple" else "Advanced", fontSize = 13.sp) }
                PageMenu("today", onOpen)
            }
            if (AppMode.undecided) androidx.compose.material3.AlertDialog(
                onDismissRequest = { AppMode.set(true) },
                title = { Text("How would you like to use it?") },
                text = { Text("Simple shows Today, the radar, the outlook, the REFS models and the extended forecast. Advanced adds the other model pages, analysis and Compare. You can change this any time from the page menu or the button at the top of Today.") },
                confirmButton = { TextButton(onClick = { AppMode.set(true) }) { Text("Simple") } },
                dismissButton = { TextButton(onClick = { AppMode.set(false) }) { Text("Advanced") } },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((d, label) in listOf(1 to "Today", 2 to "Tomorrow", 3 to "Day 3")) FilterChip(selected = s.day == d, onClick = { vm.pickDay(d) }, label = { Text(label) })
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

                // ---- the outlook at the place
                run {
                    val o = s.outlook
                    ExpandCard(
                        "outlook", "\u26A0\uFE0F", "SPC outlook for ${s.place?.name ?: "your place"}",
                        summary = {
                            if (s.place == null) Text("Save a place to see what the outlook says for it.", fontSize = 14.sp)
                            else if (o == null) Text(if (s.busy) "Reading the outlook…" else "No outlook could be read.", fontSize = 14.sp)
                            else {
                                Text(TodayText.sentence(o.level), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (o.level >= 2) warn else MaterialTheme.colorScheme.onSurface)
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    val colors = listOf(Color(0xFF97C459), Color(0xFFEF9F27), Color(0xFFD85A30), Color(0xFFE24B4A), Color(0xFF993556))
                                    for (i in 1..5) Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(2.dp)).background(if (i == o.level) colors[i - 1] else colors[i - 1].copy(alpha = 0.25f)))
                                }
                                if (s.day <= 2) Text("\uD83C\uDF2A\uFE0F ${o.tornado ?: "–"}%   \uD83D\uDCA8 ${o.wind ?: "–"}%   \uD83E\uDDCA ${o.hail ?: "–"}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else o.severeChance?.let { Text("Severe chance here: $it%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        },
                    ) {
                        if (o != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { for (n in listOf("Marginal", "Slight", "Enhanced", "Moderate", "High")) Text(n, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (s.day <= 2) {
                                Text("Tornado ${o.tornado ?: "–"}% · Wind ${o.wind ?: "–"}% · Hail ${o.hail ?: "–"}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TodayText.topThreatLine(o.top)?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                            }
                        }
                        TextButton(onClick = { onOpen("outlook") }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("See the national outlook") }
                    }
                }

                // ---- the general forecast at the nearest station
                PointCard(s.point, s.pointNote, s.pointBusy, s.place?.name) { onOpen("point") }

                // ---- thunder chance and timing
                ThunderCard(s.thunder, s.thunderNote, s.thunderBusy, s.place?.name, s.line, vm::setLine)

                // ---- wind
                WindCard(s.wind, s.windNote, s.windBusy, s.place?.name, s.gustLine, vm::setGustLine, s.outlook, s.day, s.thunder)

                // ---- mesoscale discussions
                ExpandCard(
                    "meso", "\uD83D\uDCCD", "Mesoscale discussions", startOpen = s.covering.isNotEmpty(),
                    summary = {
                        val first = s.covering.firstOrNull()
                        when {
                            s.mesoNote != null && s.covering.isEmpty() && s.nearby.isEmpty() -> Text(s.mesoNote!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            first != null -> {
                                Text("${s.covering.size} over your place · ${first.concerning}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = warn)
                                if (s.nearby.isNotEmpty()) Text("${s.nearby.size} more active elsewhere", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            s.nearby.isNotEmpty() -> Text("${s.nearby.size} active, none over your place", fontSize = 15.sp)
                            else -> Text(if (s.busy) "Reading…" else "None active right now", fontSize = 15.sp)
                        }
                    },
                ) {
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
                    if (s.covering.isEmpty() && s.nearby.isEmpty() && s.mesoNote == null) Text("Nothing to read: no mesoscale discussion is active.", fontSize = 13.sp)
                }

                // ---- the forecasters' words
                s.discussion?.let { d ->
                    ExpandCard(
                        "forecasters", "\uD83D\uDCAC", "What the forecasters say",
                        icon = s.outlook?.map?.let { m -> { OutlookThumb(m.shapes, s.place?.lat, s.place?.lon, Modifier.width(36.dp).height(24.dp)) } },
                        summary = { d.headline?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } },
                    ) {
                        // the day's maps: categorical and the three hazards (Day 3: categorical and the severe chance), each with its name
                        val panels = s.outlook?.panels.orEmpty()
                        if (panels.isNotEmpty()) for (row in panels.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((name, data) in row) Column(Modifier.weight(1f).clickable { onOpen("outlook") }, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                OutlookThumb(data.shapes, s.place?.lat, s.place?.lon, Modifier.fillMaxWidth().aspectRatio(1.8f))
                                Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                        d.sections.firstOrNull()?.paragraphs?.firstOrNull()?.let { Text(it, fontSize = 13.sp, maxLines = 8) }
                        TextButton(onClick = { onOpen("outlook") }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Read the full discussion") }
                    }
                }

                // ---- the way into everything else
                Card {
                    Text("Look closer", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, name) in listOf("mrms" to "Radar", "outlook" to "Outlook", "spc" to "REFS models", "compare" to "Radar vs model", "nbm" to "NBM", "gfs" to "GFS").filter { AppMode.offers(it.first) }) FilterChip(selected = false, onClick = { onOpen(id) }, label = { Text(pageLabel(id, name)) })
                    }
                }
                s.updated?.let { Text("Read ${formatClock(it, now)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    }
}
