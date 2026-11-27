package simplemodelview

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The surface fields drawn under the radar on the MRMS page: the 2 m temperature as a translucent fill and the 2 m dewpoint as lines, from NOAA's
 * Real-Time Mesoscale Analysis (rapid update: every 15 minutes, 2.5 km, posted about 15 minutes after its time), or, when no analysis is there, the
 * National Blend of Models' forecast for the nearest hour (same grid). Values are kept in degrees C.
 *
 * Drawing order and priority: the radar first, then its overlays, then the temperature fill, then the dewpoint lines; each layer is shown as soon as it is ready.
 */
object Underlay {
    /** The MRMS product whose temperature colors (and F / C units) the fill uses. */
    const val TEMP_STYLE = "Model_SurfaceTemp_00.50"

    /** How opaque the temperature fill is: the radar goes over it, and the map should still read through it. */
    const val FILL_OPACITY = 0.55

    /** How far back from a radar scan an analysis is looked for (15-minute steps). */
    const val LOOKBACK_SECONDS = 3600L

    /** The dewpoint lines: every 10 F (5 C), the 60 F (15 C) line heavier (the moisture that matters for storms), in green. */
    fun dewpointSpec(celsius: Boolean) =
        if (celsius) ContourSpec(5.0, boldEvery = 1000.0, boldBase = 15.0, color = 0x0E6B2A, smoothing = 4) else ContourSpec(10.0, boldEvery = 1000.0, boldBase = 60.0, color = 0x0E6B2A, smoothing = 4)

    /** The temperature lines: every 10 F (5 C), the freezing line heavier, in red. */
    fun temperatureSpec(celsius: Boolean) =
        if (celsius) ContourSpec(5.0, boldEvery = 1000.0, boldBase = 0.0, color = 0xB3261E, smoothing = 4) else ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0, color = 0xB3261E, smoothing = 4)

    /** The relative humidity lines: every 10 %, the 50 % and 100 % lines heavier, in blue. */
    fun humiditySpec() = ContourSpec(10.0, boldEvery = 50.0, boldBase = 0.0, color = 0x1F5FBF, smoothing = 6)   // humidity is rough: smoothed more, so the lines read as areas

    /** Relative humidity (%, 0 to 100) from the temperature and dewpoint in degrees C (the Magnus formula); NaN where either is missing. */
    fun relativeHumidity(tempC: Float, dewC: Float): Float {
        if (tempC.isNaN() || dewC.isNaN()) return Float.NaN
        val rh = 100.0 * kotlin.math.exp(17.625 * dewC / (243.04 + dewC) - 17.625 * tempC / (243.04 + tempC))
        return rh.coerceIn(0.0, 100.0).toFloat()
    }

    /** [relativeHumidity] over two fields of the same size. */
    fun relativeHumidity(tempC: FloatArray, dewC: FloatArray): FloatArray = FloatArray(minOf(tempC.size, dewC.size)) { relativeHumidity(tempC[it], dewC[it]) }
}

/** NOAA's Real-Time Mesoscale Analysis, rapid update, on AWS: one file every 15 minutes with an `.idx`. */
object RtmaBucket {
    const val URL = "https://noaa-rtma-pds.s3.amazonaws.com"

    /** The analysis file of the rapid-update RTMA for [epoch] (rounded down to the quarter hour). */
    fun analysisUrl(epoch: Long): String {
        val t = epoch - floorMod(epoch, 900L)
        val c = civilFromEpoch(t)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "$URL/rtma2p5_ru.${c[0]}${two(c[1])}${two(c[2])}/rtma2p5_ru.t${two(c[3])}${two(c[4])}z.2dvaranl_ndfd.grb2"
    }

    /** The analysis times tried for a scan at [validTime]: its quarter hour, then each earlier one back to [Underlay.LOOKBACK_SECONDS]. */
    fun candidates(validTime: Long): List<Long> {
        val first = validTime - floorMod(validTime, 900L)
        return (0..(Underlay.LOOKBACK_SECONDS / 900L).toInt()).map { first - it * 900L }
    }
}

/** The NBM files tried for a scan at [validTime] when there is no analysis: the hour nearest it, from the runs 1 to 6 hours before (the 1-hour forecast first). */
fun nbmFallbacks(validTime: Long): List<Pair<String, Int>> {
    val hour = (validTime + 1800L) - floorMod(validTime + 1800L, 3600L)
    return (1..6).map { lead ->
        val run = hour - lead * 3600L
        val c = civilFromEpoch(run)
        fun two(n: Int) = n.toString().padStart(2, '0')
        val h = two(c[3])
        "${NbmGribBucket.URL}/blend.${c[0]}${two(c[1])}${two(c[2])}/$h/core/blend.t${h}z.core.f${lead.toString().padStart(3, '0')}.co.grib2" to lead
    }
}

/** Which surface file a scan uses: its [url], when it is valid, its index, and what to call it ("RTMA 23:30", "NBM 00:00, 1 h forecast"). */
class SurfacePick(val url: String, val valid: Long, val index: List<GribIndexEntry>, val rtma: Boolean, val lead: Int) {
    val label: String get() = if (rtma) "RTMA ${formatClock(valid)}" else "NBM ${formatClock(valid)} (${lead} h fcst)"
}

/**
 * The surface analysis for the MRMS page: picks the file for a scan (the newest RTMA analysis at or before it, else NBM) and gets its 2 m temperature and
 * dewpoint: only those messages are downloaded (byte ranges, from the file's index), kept in [store] like the other GRIB messages, and decoded on demand.
 */
class SurfaceRepository(private val http: Http, private val core: Core, private val store: GribStore = MemoryGribStore()) {
    private val lock = Mutex()
    private val indexes = LinkedHashMap<String, List<GribIndexEntry>>(32, 0.75f, true)
    private val missing = HashMap<String, Long>()          // url -> when its index was not there (tried again after a while: it may just not be posted yet)

    private val tmp = GribField("TMP", "2 m above ground")
    private val dpt = GribField("DPT", "2 m above ground")

    /** The file for a scan at [validTime], or null when there is neither an analysis in the last hour nor an NBM forecast for its hour. */
    suspend fun pick(validTime: Long, nowEpoch: Long): SurfacePick? {
        for (t in RtmaBucket.candidates(validTime)) {
            if (t > nowEpoch) continue
            val url = RtmaBucket.analysisUrl(t)
            index(url, nowEpoch)?.let { return SurfacePick(url, t, it, rtma = true, lead = 0) }
        }
        val hour = (validTime + 1800L) - floorMod(validTime + 1800L, 3600L)
        for ((url, lead) in nbmFallbacks(validTime)) {
            index(url, nowEpoch)?.let { return SurfacePick(url, hour, it, rtma = false, lead = lead) }
        }
        return null
    }

    private suspend fun index(url: String, nowEpoch: Long): List<GribIndexEntry>? {
        lock.withLock {
            indexes[url]?.let { return it }
            missing[url]?.let { if (nowEpoch - it < 180) return null }
        }
        val result = try { http.get("$url.idx") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        val parsed = if (result?.status == 200) parseGribIndex(result.body.decodeToString()).takeIf { it.isNotEmpty() } else null
        lock.withLock {
            if (parsed != null) { indexes[url] = parsed; missing.remove(url); while (indexes.size > 48) indexes.remove(indexes.keys.first()) }
            else if (result != null) missing[url] = nowEpoch
        }
        return parsed
    }

    /** The 2 m temperature (C) of [pick] on its own grid. */
    suspend fun temperature(pick: SurfacePick): GridField = field(pick, tmp)

    /** The 2 m dewpoint (C) of [pick] on its own grid. */
    suspend fun dewpoint(pick: SurfacePick): GridField = field(pick, dpt)

    /** Downloads both messages of [pick] (a loop's prefetch), without decoding them. */
    suspend fun prefetch(pick: SurfacePick) { message(pick, tmp); message(pick, dpt) }

    private suspend fun field(pick: SurfacePick, f: GribField): GridField {
        val bytes = message(pick, f)
        val decoded = try { core.decodeGrib(bytes) } catch (e: Exception) { throw RefsException("could not decode the ${f.variable} message: ${e.message}") }
        val v = decoded.values
        for (i in v.indices) v[i] = v[i] - 273.15f
        return GridField(decoded.grid, v)
    }

    private suspend fun message(pick: SurfacePick, f: GribField): ByteArray {
        val name = f.variable
        store.get(pick.url, name)?.let { return it }
        val time = if (pick.rtma) "anl" else "${pick.lead} hour fcst"
        val at = pick.index.indexOfFirst { it.variable == f.variable && it.level == f.level && it.time == time && it.extra.isEmpty() }
        val next = pick.index.getOrNull(at + 1) ?: throw RefsException("no $name message in ${pick.label}")
        val first = pick.index[at].offset
        val last = next.offset - 1
        val part = http.getRange(pick.url, first, last)
        if ((part.status != 200 && part.status != 206) || part.body.size.toLong() != last - first + 1) throw RefsException("the $name message could not be downloaded (HTTP ${part.status})")
        store.put(pick.url, name, part.body)
        return part.body
    }
}
