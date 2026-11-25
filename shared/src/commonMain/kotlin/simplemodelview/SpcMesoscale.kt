package simplemodelview

import kotlin.math.abs

/** A Storm Prediction Center mesoscale discussion: a short note about an area where something is developing (severe potential, a watch, heavy snow). */
class MesoscaleDiscussion(
    val number: Int,
    val areas: String,
    /** "Severe potential...Watch unlikely", "Tornado Watch 706", "Heavy snow". */
    val concerning: String,
    val validFrom: Long,
    val validTo: Long,
    val summary: String,
    val discussion: String,
    val forecaster: String?,
    /** The area it covers: lat0, lon0, lat1, lon1, ... (west-negative longitudes); empty when the product has none. */
    val polygon: DoubleArray,
) {
    fun activeAt(now: Long) = validTo > now
    fun covers(lat: Double, lon: Double) = polygon.size >= 6 && pointInRing(lat, lon, polygon)
    val isWatchRelated: Boolean get() = concerning.contains("Watch", ignoreCase = true) && !concerning.contains("unlikely", ignoreCase = true)
}

object SpcMesoscale {
    /** The latest [limit] products, newest first (the Iowa Environmental Mesonet's copy of the NWS text; SPC's own GIS files carry no discussion text). */
    fun url(limit: Int = 12) = "https://mesonet.agron.iastate.edu/cgi-bin/afos/retrieve.py?pil=SWOMCD&limit=$limit&fmt=text"

    private val issueLine = Regex("""SPC MCD (\d{2})(\d{4})""")
    private val validLine = Regex("""Valid (\d{2})(\d{4})Z - (\d{2})(\d{4})Z""")

    /** The mesoscale discussions in [raw] (several products, as the archive returns them); [now] decides which month and year the day numbers belong to. */
    fun parseAll(raw: String, now: Long): List<MesoscaleDiscussion> =
        raw.replace("\r", "").split(Regex("""(?m)^ACUS11 KWNS""")).drop(1).mapNotNull { parse("ACUS11 KWNS$it", now) }

    fun parse(chunk: String, now: Long): MesoscaleDiscussion? {
        val number = Regex("""Mesoscale Discussion (\d+)""").find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val v = validLine.find(chunk) ?: return null
        val lines = chunk.lines().map { it.trimEnd() }
        fun block(from: Int): String { val out = ArrayList<String>(); var i = from; while (i < lines.size && lines[i].isNotBlank()) { out += lines[i].trim(); i++ }; return out.joinToString(" ") }
        val areas = lines.indexOfFirst { it.startsWith("Areas affected...") }.let { if (it < 0) "" else block(it).removePrefix("Areas affected...").trim() }
        val concerning = lines.indexOfFirst { it.startsWith("Concerning...") }.let { if (it < 0) "" else block(it).removePrefix("Concerning...").trim().trimEnd('.') }
        // the day numbers carry no month: the month (and year) that puts the issue time nearest now
        val issue = issueLine.find(chunk)
        val anchorDay = (issue?.groupValues?.get(1) ?: v.groupValues[1]).toInt()
        val base = civilFromEpoch(now)
        val months = listOf(-1, 0, 1).map { d -> var m = base[1] + d; var y = base[0]; if (m < 1) { m = 12; y -= 1 } else if (m > 12) { m = 1; y += 1 }; y to m }
        val (year, month) = months.minByOrNull { (y, m) -> abs(epochOf(y, m, anchorDay.coerceIn(1, 28), 12, 0, 0) - now) } ?: (base[0] to base[1])
        fun at(day: Int, hhmm: String, afterDay: Int? = null): Long {
            var y = year; var m = month
            if (afterDay != null && day < afterDay) { m += 1; if (m > 12) { m = 1; y += 1 } }
            return epochOf(y, m, day, hhmm.substring(0, 2).toInt(), hhmm.substring(2, 4).toInt(), 0)
        }
        val startDay = v.groupValues[1].toInt()
        val from = at(startDay, v.groupValues[2])
        val to = at(v.groupValues[3].toInt(), v.groupValues[4], afterDay = startDay)
        fun section(label: String): String {
            val at = lines.indexOfFirst { it.startsWith(label) }
            if (at < 0) return ""
            val out = ArrayList<String>(); var i = at
            while (i < lines.size) {
                val l = lines[i].trim()
                if (i > at && (l.startsWith("DISCUSSION...") || l.startsWith("SUMMARY...") || l.startsWith("..") || l.startsWith("...Please see") || l.startsWith("ATTN") || l.startsWith("LAT...LON"))) break
                out += if (i == at) l.removePrefix(label) else l
                i++
            }
            return out.joinToString(" ").replace(Regex("""\s+"""), " ").trim()
        }
        val forecaster = lines.firstOrNull { it.startsWith("..") && !it.startsWith("...") }?.trim()
        val polygon = ArrayList<Double>()
        val ll = lines.indexOfFirst { it.startsWith("LAT...LON") }
        if (ll >= 0) {
            var i = ll
            val tokens = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) { tokens += lines[i].removePrefix("LAT...LON").trim().split(Regex("""\s+""")); i++ }
            for (t in tokens) if (t.length == 8 && t.all { it.isDigit() }) {
                val lat = t.substring(0, 4).toInt() / 100.0
                var lon = t.substring(4, 8).toInt() / 100.0
                if (lon < 40.0) lon += 100.0
                polygon += lat; polygon += -lon
            }
        }
        return MesoscaleDiscussion(number, areas, concerning, from, to, section("SUMMARY..."), section("DISCUSSION..."), forecaster, polygon.toDoubleArray())
    }
}
