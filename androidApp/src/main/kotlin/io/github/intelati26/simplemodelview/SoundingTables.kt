package io.github.intelati26.simplemodelview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import simplemodelview.SHARP_MISSING
import simplemodelview.SharpAnalysis
import simplemodelview.SharpIndices
import simplemodelview.SharpParcel
import simplemodelview.SharpSars
import simplemodelview.SharpTornadoProb
import simplemodelview.SharpWind
import simplemodelview.SoundingHazard
import simplemodelview.gone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal val TABLE = FontFamily.Monospace

private fun num(v: Double, digits: Int) = if (gone(v)) "--" else "%.${digits}f".format(v)
private fun dirSpeed(w: SharpWind) = if (w.valid) "%.0f/%.0f".format(w.direction(), w.speed()) else "--"

/**
 * A grid of cells as wxqt draws SPC's tables: a header row, shaded alternate rows, the first column the row names when [labelColumn]. A tap on a header cell calls [onHeader] with its index
 * (the parcel table uses it to pick the parcel the skew-T draws; [selectedColumn] is shaded).
 */
@Composable
internal fun GridTable(header: List<String>, rows: List<List<String>>, labelColumn: Boolean = true, selectedColumn: Int = -1, onHeader: ((Int) -> Unit)? = null, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val headerFill = MaterialTheme.colorScheme.secondaryContainer
    val stripe = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val singleColumn = header.size == 1
    Column(modifier.fillMaxWidth().padding(top = 6.dp).background(Color.Transparent)) {
        @Composable fun cells(values: List<String>, bold: Boolean, fill: Color, header: Boolean) {
            Row(Modifier.fillMaxWidth().background(fill)) {
                for ((c, v) in values.withIndex()) {
                    val selected = c == selectedColumn
                    Text(
                        v, Modifier.weight(if (c == 0 && labelColumn) 1.45f else 1f)
                            .then(if (header && onHeader != null && c > 0) Modifier.clickable { onHeader(c) } else Modifier)
                            .background(if (selected) Color(0x55FF9800) else Color.Transparent).padding(vertical = 2.dp, horizontal = 3.dp),
                        fontSize = 11.sp, lineHeight = 13.sp, maxLines = if (singleColumn) 3 else 1, fontWeight = if (bold || (c == 0 && labelColumn) || selected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = if (c == 0 && labelColumn) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
                        fontFamily = if (c == 0 && labelColumn) FontFamily.Default else TABLE,
                    )
                }
            }
        }
        cells(header, true, headerFill, true)
        for ((r, row) in rows.withIndex()) cells(row, false, if (r % 2 == 0) Color.Transparent else stripe, false)
        androidx.compose.material3.HorizontalDivider(color = line)
    }
}

/** SPC's parcel table, the other way up as wxqt lays it out: a column for each of the surface-based, mixed-layer, most unstable and forecast-surface parcels; a tap on its name draws it on the skew-T. */
@Composable
internal fun ParcelGrid(a: SharpAnalysis, selected: Int, onSelect: (Int) -> Unit) {
    val ps = listOf(a.sb, a.ml, a.mu, a.fcst)
    fun row(name: String, f: (SharpParcel) -> String) = listOf(name) + ps.map { if (it.valid) f(it) else "--" }
    GridTable(
        listOf("", "SB", "ML", "MU", "FCST"),
        listOf(
            row("CAPE") { num(it.cape, 0) }, row("CINH") { num(it.cin, 0) }, row("LI 500") { num(it.liftedIndex500, 1) }, row("LCL m") { num(it.lclHght, 0) }, row("LFC m") { num(it.lfcHght, 0) },
            row("EL m") { num(it.elHght, 0) }, row("MPL m") { num(it.mplHght, 0) }, row("CAPE 0-3") { num(it.cape3km, 0) },
            // normalized CAPE: CAPE over the depth from the LFC to the EL (Blanchard 1998), m/s2
            row("NCAPE") { p -> if (!gone(p.lfcHght) && !gone(p.elHght) && p.elHght > p.lfcHght && p.cape > 0.0) "%.2f".format(p.cape / (p.elHght - p.lfcHght)) else "--" },
        ),
        selectedColumn = selected + 1, onHeader = { onSelect(it - 1) },
    )
}

/** SPC's kinematics block: for each layer the SRH (where SPC prints one), the bulk shear, the mean wind and the storm-relative mean wind (right mover), as direction / speed in knots. */
@Composable
internal fun KinematicsGrid(a: SharpAnalysis) {
    fun row(name: String, srh: String, k: simplemodelview.SharpLayerKinematics) = listOf(name, srh, if (k.shear.valid) "%.0f".format(k.shear.speed()) else "--", dirSpeed(k.mean), dirSpeed(k.stormRelative))
    GridTable(
        listOf("", "SRH", "Shear kt", "MnWind", "SRW"),
        listOf(
            row("SFC-1 km", num(a.srh01, 0), a.kin1), row("SFC-3 km", num(a.srh03, 0), a.kin3), row("Eff Inflow", num(a.effectiveSrh, 0), a.kinEff), row("SFC-6 km", "", a.kin6),
            row("SFC-8 km", "", a.kin8), row("LCL-EL cloud", "", a.kinCloud), row("Eff Shear", "", a.kinEbwd),
        ),
    )
}

/** The rest of SPC's tables: storm motions, the composite parameters, the water, the lapse rates, the precipitation type and the indices. */
@Composable
internal fun IndexGrids(a: SharpAnalysis) {
    GridTable(
        listOf("Bunkers", "Right", "Left", "Eff inflow"),
        listOf(listOf("dir / kt", dirSpeed(a.rightMover), dirSpeed(a.leftMover), if (a.effective.valid) "${a.effective.botAgl.roundToInt()}-${a.effective.topAgl.roundToInt()} m" else "--")),
    )
    GridTable(listOf("STP fix", "STP eff", "SCP", "SHIP"), listOf(listOf(num(a.stpFixed, 1), num(a.stpEffective, 1), num(a.supercell, 1), num(a.hail, 2))), labelColumn = false)
    GridTable(
        listOf("PW in", "DCAPE", "0C m", "WBZ m", "Conv T C", "RH sfc %"),
        listOf(listOf(num(a.precipitableWaterIn, 2), num(a.dcape, 0), num(a.freezingLevelAgl, 0), num(a.wetBulbZeroAgl, 0), num(a.convectiveTemp, 1), num(a.surfaceRh, 0))), labelColumn = false,
    )
    GridTable(listOf("", "0-3", "3-6 AGL", "850-500", "700-500"), listOf(listOf("Lapse C/km", num(a.lapse03, 1), num(a.lapse36, 1), num(a.lapse850500, 1), num(a.lapse700500, 1))))
    GridTable(listOf("", "low 100 mb", "0-3 km"), listOf(listOf("Mean w g/kg", num(a.meanMixingLow100, 1), num(a.meanMixing03, 1))))
    // SPC's "best guess precip type" (SHARPpy's watch_type.py)
    val g = a.precip
    GridTable(
        listOf("Best guess precip type"),
        listOf(listOf(
            (g.type.ifEmpty { "--" }) + (if (!gone(g.surfaceTempC)) "  -  sfc temp ${"%.1f".format(g.surfaceTempC * 1.8 + 32.0)} F" else "") +
                (if (g.phase >= 0 && !gone(g.sourcePressure)) ", source ~${g.sourcePressure.roundToInt()} mb (${g.sourceTemp.roundToInt()} C)" else if (g.phase < 0) ", no saturated layer below 5 km" else ""),
        )), labelColumn = false,
    )
    GridTable(
        listOf("Corfidi", "Up", "Down", "Crit angle"),
        listOf(listOf("dir / kt", if (a.corfidi.valid) dirSpeed(a.corfidi.upshear) else "--", if (a.corfidi.valid) dirSpeed(a.corfidi.downshear) else "--", num(a.criticalAngle, 0))),
    )
    GridTable(
        listOf("MaxT F", "DownT F", "BRN shear", "4-6 km SRW"),
        listOf(listOf(if (gone(a.maxTempC)) "--" else "%.0f".format(a.maxTempC * 1.8 + 32.0), if (gone(a.downTempC)) "--" else "%.0f".format(a.downTempC * 1.8 + 32.0), num(a.brnShear, 0), dirSpeed(a.kin46.stormRelative))), labelColumn = false,
    )
    GridTable(listOf("K Idx", "T Tot", "Mid RH %", "Low RH %"), listOf(listOf(num(a.kIndex, 0), num(a.totalTotals, 0), num(a.midRh, 0), num(a.lowRh, 0))), labelColumn = false)
    GridTable(listOf("ESP", "MMP %", "WNDG", "SigSvr"), listOf(listOf(num(a.esp, 1), if (gone(a.mmp)) "--" else "%.0f".format(a.mmp * 100.0), num(a.wndg, 1), num(a.sigSevere, 0))), labelColumn = false)
}

/** SHARPpy's text box in the STP panel: the chance of an EF2+ tornado given each parameter alone (SPC prints the same list). */
@Composable
internal fun TornadoProbBox(a: SharpAnalysis) {
    val ebwd = if (a.kinEbwd.shear.valid) a.kinEbwd.shear.speed() else SHARP_MISSING
    val palette = listOf(Color(0xFFB07040), Color(0xFFCC9933), Color(0xFFFFFFFF), Color(0xFFFFFF00), Color(0xFFFF4040), Color(0xFFE700DF))
    val lines = listOf(
        "based on MLCAPE:" to SharpTornadoProb.fromMlCape(if (a.ml.valid) a.ml.cape else SHARP_MISSING), "based on MLLCL:" to SharpTornadoProb.fromMlLcl(if (a.ml.valid) a.ml.lclHght else SHARP_MISSING),
        "based on ESRH:" to SharpTornadoProb.fromEffectiveSrh(a.effectiveSrh), "based on EBWD:" to SharpTornadoProb.fromEffectiveShear(ebwd),
        "based on STP_fixed:" to SharpTornadoProb.fromStpFixed(a.stpFixed), "based on STP_effective:" to SharpTornadoProb.fromStpEffective(a.stpEffective),
    )
    Column(Modifier.fillMaxWidth().background(Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(6.dp)) {
        Text("Prob EF2+ torn with supercell", color = Color(0xFFEBEBEB), fontSize = 9.sp, lineHeight = 11.sp)
        Text("Sounding CLIMO = .15 sigtor", color = Color(0xFFEBEBEB), fontSize = 9.sp, lineHeight = 11.sp)
        for ((name, r) in lines) Row(Modifier.fillMaxWidth().padding(top = 1.dp)) {
            Text(name, Modifier.weight(1f), color = palette[r.colour.coerceIn(0, 5)], fontSize = 9.sp, lineHeight = 11.sp)
            Text(if (r.probability < 0.0) "--" else "%.2f".format(r.probability), color = palette[r.colour.coerceIn(0, 5)], fontSize = 9.sp, lineHeight = 11.sp)
        }
    }
}

/** SPC's big orange numbers: the supercell composite, the significant tornado parameter (effective and fixed layer) and the significant hail parameter. */
@Composable
internal fun CompositeNumbers(a: SharpAnalysis) {
    val orange = Color(0xFFE65100)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for ((label, v) in listOf("Supercell" to a.supercell, "STP (eff layer)" to a.stpEffective, "STP (fixed layer)" to a.stpFixed, "Sig Hail" to a.hail)) {
            Text("$label = ${num(v, 1)}", color = orange, fontWeight = FontWeight.Bold, fontFamily = TABLE, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

private fun describeAnalogue(id: String): String {
    // "yymmddhh.SITE" -> "yyyy-mm-dd HHZ (SITE)": the analogues run from 1950 (50 to 99 are 19xx)
    val parts = id.split('.')
    if (parts.size != 2 || parts[0].length != 8) return id
    val d = parts[0]; val yy = d.substring(0, 2).toInt()
    return "${if (yy >= 50) 1900 + yy else 2000 + yy}-${d.substring(2, 4)}-${d.substring(4, 6)} ${d.substring(6, 8)}Z (${parts[1]})"
}

/** SARS (SHARPpy's analogues panel): the probability line, the number of loose matches, then the quality matches (date, site, and the tornado class or the hail size). */
@Composable
internal fun SarsPanel(a: SharpAnalysis) {
    @Composable fun side(heading: String, r: SharpSars.Result, tornado: Boolean) {
        Text(heading, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (!r.valid) { Text("No analogues (missing data)", fontSize = 11.sp); return }
        if (r.looseMatches > 0) {
            val percent = (r.probability * 100.0).roundToInt()
            Text("SARS: $percent% ${if (tornado) "TOR" else "SIG"}   (${r.looseMatches} loose matches)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (percent >= 50) Color(0xFFD500F9) else MaterialTheme.colorScheme.onSurface)
        } else Text("No loose matches", fontSize = 11.sp)
        if (r.quality.isEmpty()) { Text("No Quality Matches", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
        for (m in r.quality.take(16)) {
            val (color, tag) = if (tornado) (if (m.category == 2) Color(0xFFD32F2F) else if (m.category == 1) Color(0xFF0288D1) else Color(0xFF8D6E63)) to (if (m.category == 2) "SIG" else if (m.category == 1) "WEAK" else "NON")
                else (if (m.size >= 2.0) Color(0xFFD32F2F) else Color(0xFF0288D1)) to "%.2f".format(m.size)
            Text("${describeAnalogue(m.id)}  $tag", color = color, fontFamily = TABLE, fontSize = 11.sp, lineHeight = 13.sp)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("SARS - Sounding Analogue System", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        side("SUPERCELL", a.sarsSupercell, true)
        side("SGFNT HAIL", a.sarsHail, false)
        Text("Historical soundings with a likeness to this one (Thompson, Jewell: NOAA SPC).", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- the possible hazard type (SPC's flow chart), first on the screen ----

internal fun hazardColor(type: String) = when (type) {
    "PDS TOR" -> Color(0xFF6A1B9A); "TOR" -> Color(0xFFC62828); "MRGL TOR" -> Color(0xFFEF6C00); "SVR" -> Color(0xFFF9A825); "MRGL SVR" -> Color(0xFFFBC02D)
    "FLASH FLOOD" -> Color(0xFF2E7D32); "BLIZZARD" -> Color(0xFF1565C0); "EXCESSIVE HEAT" -> Color(0xFFAD1457); else -> Color(0xFF757575)
}

/**
 * SHARPpy's "possible hazard type", walked from the sounding's parameters: the tornado steps first, then the severe, flash flood, blizzard and heat steps. A tap shows the
 * walk with every test and the value it was given. It is the model profile at one point, not a forecast; the flash-flood test uses the reanalysis climatology of the place and day, and cannot hold outside the data (the contiguous U.S. and its edges).
 */
@Composable
internal fun HazardBanner(a: SharpAnalysis, pw: simplemodelview.PwClimate.Normal?) {
    var open by remember { mutableStateOf(false) }
    val result = remember(a, pw) { SoundingHazard.classify(SoundingHazard.params(a), pw?.meanIn ?: Double.NaN, pw?.sigmaIn ?: Double.NaN) }
    val color = hazardColor(result.type)
    val unknown = result.nodes.filter { it.reached }.flatMap { it.lines }.count { it.state == SoundingHazard.State.Unknown }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).background(color.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable { open = !open }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Possible hazard type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(result.type, color = color, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp)
            }
            Text(if (open) "Hide chart \u25B4" else "Show chart \u25BE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        val d = result.deciding
        Text(
            if (d != null) "Step ${d.id} holds: ${d.lines.size} test${if (d.lines.size == 1) "" else "s"} met" else "No step of the chart holds" + if (unknown > 0) "; $unknown test${if (unknown == 1) " has" else "s have"} no value here" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        if (open) {
            val stateMark = mapOf(SoundingHazard.State.Pass to "\u2713", SoundingHazard.State.Fail to "\u2717", SoundingHazard.State.Unknown to "?")
            for (n in result.nodes) {
                if (!n.reached) continue
                Text("${n.id} \u00B7 ${n.outcome}" + if (n.passed) "  \u2190 this one" else "", Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, fontFamily = TABLE, fontSize = 12.sp, lineHeight = 14.sp, color = if (n.passed) color else MaterialTheme.colorScheme.onSurface)
                for (c in n.lines) Text(
                    "${stateMark[c.state]} ${c.text}  [${c.value}]", fontFamily = TABLE, fontSize = 11.sp, lineHeight = 13.sp,
                    color = when (c.state) { SoundingHazard.State.Pass -> Color(0xFF2E7D32); SoundingHazard.State.Fail -> Color(0xFFC62828); SoundingHazard.State.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
            if (result.nodes.any { !it.reached }) Text("The steps after the one that holds are not walked.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("After SHARPpy's and SPC's flow chart. Model guidance at one grid point, not a forecast. The flash-flood step compares the precipitable water with NCEP/NCAR reanalysis 1991-2020 (mean plus two standard deviations at the place and day); outside the data it cannot hold.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
