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
 * The height of the ground (m) on RTMA's 2.5 km grid: one GRIB message (about 7 MB) from any recent analysis file, read once and then kept by the app
 * ([read] / [write]: the platform's file), since the ground does not move. Null while it cannot be had (no network the first time).
 */
class RtmaTerrain(private val http: Http, private val core: Core, private val read: () -> ByteArray?, private val write: (ByteArray) -> Unit) {
    private var field: GridField? = null
    private var failedAt = 0L
    private val lock = Mutex()

    suspend fun field(nowEpoch: Long): GridField? = lock.withLock {
        field?.let { return@withLock it }
        val kept = try { read() } catch (e: Exception) { null }
        kept?.let { bytes -> decode(bytes)?.let { field = it; return@withLock it } }
        if (nowEpoch - failedAt < 600) return@withLock null           // a failed download is not tried again for ten minutes
        val bytes = download(nowEpoch)
        if (bytes == null) { failedAt = nowEpoch; return@withLock null }
        val decoded = decode(bytes) ?: run { failedAt = nowEpoch; return@withLock null }
        try { write(bytes) } catch (e: Exception) { }
        field = decoded
        decoded
    }

    private fun decode(bytes: ByteArray): GridField? = try { core.decodeGrib(bytes) } catch (e: Exception) { null }

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
