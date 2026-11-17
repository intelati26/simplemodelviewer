package simplemodelview

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The surface fields drawn under the radar on the MRMS page: MRMS's own model temperature as a translucent fill, and dewpoint lines worked out from MRMS's
 * temperature and wet-bulb temperature (MRMS has no dewpoint field). The psychrometric formula needs the air pressure; it is taken from the height of the ground
 * (the standard atmosphere), which is as good as a live pressure for this: 10 hPa of error moves the dewpoint by about a third of a degree F.
 *
 * Drawing order and priority: the radar first, then its overlays, then the temperature fill, then the dewpoint lines; each layer is shown as soon as it is ready.
 */
object Underlay {
    const val TEMP = "Model_SurfaceTemp_00.50"
    const val WETBULB = "Model_WetBulbTemp_00.50"

    /**
     * MRMS's model fields are hourly (valid on the hour) but reach the bucket about 85 minutes after their time: the newest radar scans have no file near
     * them. So a scan takes the file nearest its time among those from [LOOKBACK_SECONDS] before it to [AHEAD_SECONDS] after it (the newest one, an hour
     * and more old, for live scans; the one of the nearest hour for older scans).
     */
    const val LOOKBACK_SECONDS = 3 * 3600L
    const val AHEAD_SECONDS = 1800L

    fun fileFor(files: List<MrmsFile>, validTime: Long): MrmsFile? =
        files.filter { it.validTime in (validTime - LOOKBACK_SECONDS)..(validTime + AHEAD_SECONDS) }.minByOrNull { kotlin.math.abs(it.validTime - validTime) }

    /** How opaque the temperature fill is: the radar goes over it, and the map should still read through it. */
    const val FILL_OPACITY = 0.55

    /** The dewpoint lines: every 10 F (5 C), the 60 F (15 C) line heavier (the moisture that matters for storms), in green. */
    fun dewpointSpec(celsius: Boolean) =
        if (celsius) ContourSpec(5.0, boldEvery = 1000.0, boldBase = 15.0, color = 0x0E6B2A) else ContourSpec(10.0, boldEvery = 1000.0, boldBase = 60.0, color = 0x0E6B2A)

    /** Pressure (hPa) of the standard atmosphere at [heightMeters] above sea level. */
    fun standardPressure(heightMeters: Double): Double = 1013.25 * (1.0 - 2.25577e-5 * heightMeters).coerceAtLeast(0.05).pow(5.25588)

    /** Saturation vapor pressure over water (hPa) at [tC] (Bolton 1980). */
    fun saturationVaporPressure(tC: Double): Double = 6.112 * exp(17.67 * tC / (tC + 243.5))

    /**
     * Dewpoint (C) from the temperature and the wet-bulb temperature (C) at [pressureHpa]: the vapor pressure is the saturation pressure at the wet-bulb less
     * the psychrometric term (a ventilated psychrometer's coefficient), and the dewpoint is where that pressure saturates. A wet-bulb above the temperature
     * (rounding) counts as saturated; air drier than the formula can say (a negative vapor pressure) gets the driest dewpoint it can (about -50 C).
     */
    fun dewpoint(tC: Double, twC: Double, pressureHpa: Double): Double {
        val tw = min(twC, tC)
        val gamma = 6.6e-4 * (1.0 + 0.00115 * tw)
        val e = max(0.06, saturationVaporPressure(tw) - gamma * pressureHpa * (tC - tw))
        val x = ln(e / 6.112)
        return 243.5 * x / (17.67 - x)
    }

    /** The dewpoint of every cell (C), NaN where the temperature or the wet-bulb is missing. [pressure] (hPa) is per cell; null: 1000 hPa everywhere. */
    fun dewpointField(t: FloatArray, tw: FloatArray, pressure: FloatArray?): FloatArray {
        val n = min(t.size, tw.size)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val a = t[i]
            val b = tw[i]
            if (a.isNaN() || b.isNaN()) { out[i] = Float.NaN; continue }
            val p = pressure?.getOrNull(i)?.takeIf { !it.isNaN() } ?: 1000f
            out[i] = dewpoint(a.toDouble(), b.toDouble(), p.toDouble()).toFloat()
        }
        return out
    }

    /** Pressure (hPa) from ground heights (m): the standard atmosphere; where the height is missing (the sea, outside the terrain grid) it is sea level. */
    fun pressureFromHeights(heights: FloatArray): FloatArray = FloatArray(heights.size) { i ->
        val h = heights[i]
        if (h.isNaN()) 1013.25f else standardPressure(h.toDouble()).toFloat()
    }
}

/** NOAA's Real-Time Mesoscale Analysis (rapid update, every 15 minutes, 2.5 km), on AWS: here only for the height of the ground under it. */
object RtmaBucket {
    const val URL = "https://noaa-rtma-pds.s3.amazonaws.com"

    /** The analysis file of the rapid-update RTMA for [epoch] (rounded down to the quarter hour). */
    fun analysisUrl(epoch: Long): String {
        val t = epoch - floorMod(epoch, 900L)
        val c = civilFromEpoch(t)
        fun two(n: Int) = n.toString().padStart(2, '0')
        val day = "${c[0]}${two(c[1])}${two(c[2])}"
        return "$URL/rtma2p5_ru.$day/rtma2p5_ru.t${two(c[3])}${two(c[4])}z.2dvaranl_ndfd.grb2"
    }
}

/**
 * The height of the ground (m) at 5 km: made once from RTMA's 2.5 km terrain (one GRIB message of about 7.5 MB from any recent analysis file), smoothed and
 * put on a 5 km grid of the MRMS area, then kept by the app ([read] / [write]: the platform's file, see [encodeTerrain]) since the ground does not move.
 * 5 km is plenty for a pressure: 100 m of height is about 11 hPa, a third of a degree F of dewpoint. Null while it cannot be had (no network the first time).
 */
class RtmaTerrain(private val http: Http, private val core: Core, private val read: () -> ByteArray?, private val write: (ByteArray) -> Unit) {
    private var field: GridField? = null
    private var failedAt = 0L
    private val lock = Mutex()

    suspend fun field(nowEpoch: Long): GridField? = lock.withLock {
        field?.let { return@withLock it }
        val grid = try { core.planLambertBox(TERRAIN_BOX[0], TERRAIN_BOX[1], TERRAIN_BOX[2], TERRAIN_BOX[3], TERRAIN_CELL_METERS) } catch (e: Exception) { return@withLock null }
        val kept = try { read() } catch (e: Exception) { null }
        kept?.let { decodeTerrain(it, grid.columns, grid.rows) }?.let { return@withLock GridField(grid, it).also { f -> field = f } }
        if (nowEpoch - failedAt < 600) return@withLock null           // a failed download is not tried again for ten minutes
        val bytes = download(nowEpoch)
        val full = bytes?.let { try { core.decodeGrib(it) } catch (e: Exception) { null } }
        if (full == null) { failedAt = nowEpoch; return@withLock null }
        // a 3 x 3 mean first, so the 5 km cells take the mean height around them rather than one 2.5 km cell (or the highest of a few)
        val values = core.reproject(smooth3(full.values, full.grid.columns, full.grid.rows), full.grid, grid)
        try { write(encodeTerrain(values, grid.columns, grid.rows)) } catch (e: Exception) { }
        GridField(grid, values).also { field = it }
    }

    /** The surface height message of an analysis an hour or more old (surely uploaded), trying earlier hours when one is missing. */
    private suspend fun download(nowEpoch: Long): ByteArray? {
        var t = nowEpoch - floorMod(nowEpoch, 3600L) - 3600L
        repeat(6) {
            val url = RtmaBucket.analysisUrl(t)
            val idx = try { http.get("$url.idx") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (idx != null && idx.status == 200) {
                val index = parseGribIndex(idx.body.decodeToString())
                val at = index.indexOfFirst { it.variable == "HGT" && it.level == "surface" }
                val next = index.getOrNull(at + 1)
                if (at >= 0 && next != null) {
                    val first = index[at].offset
                    val last = next.offset - 1
                    val part = http.getRange(url, first, last)
                    if ((part.status == 206 || part.status == 200) && part.body.size.toLong() == last - first + 1) return part.body
                }
            }
            t -= 3600L
        }
        return null
    }
}

/** The area of the kept terrain (west, south, east, north): the MRMS domain and a margin. */
val TERRAIN_BOX = doubleArrayOf(-131.0, 19.0, -59.0, 56.0)
const val TERRAIN_CELL_METERS = 5000.0

/** The kept terrain: "TRN1", columns, rows, then each height in whole metres as a 16-bit number (big-endian; -32768 where there is none). */
fun encodeTerrain(values: FloatArray, columns: Int, rows: Int): ByteArray {
    val n = columns * rows
    val out = ByteArray(12 + 2 * n)
    "TRN1".encodeToByteArray().copyInto(out)
    fun int(at: Int, v: Int) { out[at] = (v ushr 24).toByte(); out[at + 1] = (v ushr 16).toByte(); out[at + 2] = (v ushr 8).toByte(); out[at + 3] = v.toByte() }
    int(4, columns); int(8, rows)
    for (i in 0 until n) {
        val h = values.getOrElse(i) { Float.NaN }
        val v = if (h.isNaN()) -32768 else kotlin.math.round(h).toInt().coerceIn(-32767, 32767)
        out[12 + 2 * i] = (v shr 8).toByte(); out[13 + 2 * i] = v.toByte()
    }
    return out
}

/** The heights of a kept terrain, or null when the bytes are not one of [columns] x [rows]. */
fun decodeTerrain(bytes: ByteArray, columns: Int, rows: Int): FloatArray? {
    if (bytes.size < 12 || bytes.decodeToString(0, 4) != "TRN1") return null
    fun int(at: Int) = ((bytes[at].toInt() and 255) shl 24) or ((bytes[at + 1].toInt() and 255) shl 16) or ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
    if (int(4) != columns || int(8) != rows) return null
    val n = columns * rows
    if (bytes.size < 12 + 2 * n) return null
    return FloatArray(n) { i ->
        val v = ((bytes[12 + 2 * i].toInt() shl 8) or (bytes[13 + 2 * i].toInt() and 255)).toShort().toInt()
        if (v == -32768) Float.NaN else v.toFloat()
    }
}

/** A 3 x 3 mean of a grid (NaN cells left out; a cell with no values around it stays NaN). */
fun smooth3(values: FloatArray, columns: Int, rows: Int): FloatArray {
    val out = FloatArray(values.size)
    for (r in 0 until rows) for (c in 0 until columns) {
        var sum = 0.0
        var count = 0
        for (dr in -1..1) {
            val rr = r + dr
            if (rr < 0 || rr >= rows) continue
            for (dc in -1..1) {
                val cc = c + dc
                if (cc < 0 || cc >= columns) continue
                val v = values[rr * columns + cc]
                if (!v.isNaN()) { sum += v; count += 1 }
            }
        }
        out[r * columns + c] = if (count == 0) Float.NaN else (sum / count).toFloat()
    }
    return out
}
