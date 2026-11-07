package simplemodelview

import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.sync.withPermit

/**
 * NOAA's Global Forecast System as the public AWS bucket `noaa-gfs-bdp-pds` has it: a run every six hours, one GRIB2 file per forecast hour
 * (`gfs.<yyyyMMdd>/<HH>/atmos/gfs.t<HH>z.pgrb2.0p25.f<FFF>`, 0.25 degrees, about 500 MB each) with a text index of the messages' byte ranges, so only the
 * few fields wanted are downloaded (0.5 to 0.8 MB each). Hourly to 120 h, then every 3 h to 384 h (16 days). The page is about the upper air.
 */
object GfsBucket {
    const val URL = "https://noaa-gfs-bdp-pds.s3.amazonaws.com"

    /** The marker in [NbmCycle.version] of a GFS run (its frames are [NbmFile]s of the same kind, so the loop code is shared). */
    const val VERSION = "gfs"
}

/** The name of a message in the index: variable, level text and the field's own conversion into the units shown. */
class GfsMessage(val variable: String, val level: String)

/** What a GFS product needs and how it is drawn: the messages (in this order: first is the color, or for a wind product height, u, v then the color). */
class GfsProduct(val id: String, val title: String, val group: String, val style: Style, val convert: (Float) -> Float = { it }, val message: GfsMessage?)

object GfsCatalog {
    const val HEIGHTS_AND_WINDS = "Heights and winds"
    const val SURFACE = "Surface"

    private fun kelvinToF(k: Float) = (k - 273.15f) * 1.8f + 32f
    private fun kelvinToC(k: Float) = k - 273.15f
    const val MS_TO_KNOTS = 1.9438445f

    val levels = listOf(250, 300, 500, 700, 850)

    /** The message behind a color of a level ([WindProducts.colors]); null for the wind speed, which is made from the u and v messages. */
    fun colorMessage(level: Int, colorKey: String): GfsMessage? = when (colorKey) {
        "t2m" -> GfsMessage("TMP", "2 m above ground")
        "tlev" -> GfsMessage("TMP", "$level mb")
        "td2m" -> GfsMessage("DPT", "2 m above ground")
        "rh2m" -> GfsMessage("RH", "2 m above ground")
        "rhlev" -> GfsMessage("RH", "$level mb")
        else -> null
    }

    /** How a color's numbers become what the style shows (temperatures in F or C, humidity as it is). */
    fun colorConvert(colorKey: String): (Float) -> Float = when (colorKey) {
        "t2m", "td2m" -> ::kelvinToF
        "tlev" -> ::kelvinToC
        else -> { v -> v }
    }

    fun colorStyle(colorKey: String): Style = when (colorKey) {
        "t2m", "td2m" -> Style(Kind.Blended, Palettes.temperature(true), units = "F", digits = 0)
        "tlev" -> Style(Kind.Blended, Palettes.temperature(false), units = "C", digits = 0)
        "rh2m", "rhlev" -> Style(Kind.Banded, Palettes.relativeHumidity, units = "%", digits = 0)
        else -> Style(Kind.Banded, Palettes.upperWind, hideBelow = 10.0, units = "kt", digits = 0)
    }

    /** The virtual wind-product id of a level and color (the REFS page's [WindProducts] ids, so the same renderer draws them), or null for an unknown color. */
    fun windId(level: Int, colorKey: String): String? = WindProducts.colors.firstOrNull { it.key == colorKey }?.let { WindProducts.id(level, it) }

    /** The surface fields (single messages) with their conversions. */
    val surface: List<GfsProduct> = listOf(
        GfsProduct("t2m_gfs", "2-m Temperature", SURFACE, Style(Kind.Blended, Palettes.temperature(true), units = "F", digits = 0), ::kelvinToF, GfsMessage("TMP", "2 m above ground")),
        GfsProduct("pmsl_gfs", "Mean Sea-Level Pressure", SURFACE, Style(Kind.Blended, Palettes.rainbow(985.0, 1035.0), units = "hPa", digits = 0), { it / 100f }, GfsMessage("PRMSL", "mean sea level")),
        GfsProduct("pwat_gfs", "Precipitable Water", SURFACE, Style(Kind.Banded, Palettes.precipitableWater, hideBelow = 0.0, units = "in", digits = 2), { it / 25.4f }, GfsMessage("PWAT", "entire atmosphere (considered as a single layer)")),
        GfsProduct("cape_gfs", "Surface-Based CAPE", SURFACE, Style(Kind.Banded, Palettes.cape, hideBelow = 100.0, units = "J/kg", digits = 0), { it }, GfsMessage("CAPE", "surface")),
    )

    fun surfaceById(id: String?) = surface.firstOrNull { it.id == id }
}

/** A run of the GFS: [NbmCycle] with the GFS marker (its folder is `gfs.<date>/<HH>/atmos/`). */
fun gfsCycle(year: Int, month: Int, day: Int, hour: Int) = NbmCycle(year, month, day, hour, GfsBucket.VERSION)

/** The frames of a run's listing (`...atmos/gfs.t12z.pgrb2.0p25.f` keys, indexes skipped), as [NbmFile]s of [fieldId]'s GRIB source, ordered by lead. */
fun parseGfsListing(xml: String, cycle: NbmCycle, fieldId: String): List<NbmFile> {
    val files = ArrayList<NbmFile>()
    var at = 0
    while (true) {
        val start = xml.indexOf("<Contents>", at)
        if (start < 0) break
        val end = xml.indexOf("</Contents>", start)
        if (end < 0) break
        val block = xml.substring(start, end)
        at = end
        val key = block.substringAfter("<Key>", "").substringBefore("</Key>")
        val name = key.substringAfterLast('/')
        if (!name.startsWith("gfs.t") || ".pgrb2.0p25.f" !in name || name.endsWith(".idx")) continue
        val lead = name.substringAfterLast(".f").toIntOrNull() ?: continue
        val uploaded = isoToEpoch(block.substringAfter("<LastModified>", "").substringBefore("</LastModified>"))
        val size = block.substringAfter("<Size>", "").substringBefore("</Size>").toLongOrNull() ?: 0L
        files += NbmFile(key, cycle.epoch, cycle.epoch + lead * 3600L, uploaded, size, grib = true, field = fieldId, bucketUrl = GfsBucket.URL)
    }
    return files.sortedBy { it.valid }
}

/** The byte range of a message by its names; the time text is "N hour fcst" ("anl" for the analysis, hour 0). */
fun findGfsMessage(index: List<GribIndexEntry>, message: GfsMessage, leadHours: Int): Pair<Long, Long?>? {
    val time = if (leadHours == 0) "anl" else "$leadHours hour fcst"
    val at = index.indexOfFirst { it.variable == message.variable && it.level == message.level && it.time == time && it.extra.isEmpty() }
    if (at < 0) return null
    return index[at].offset to index.getOrNull(at + 1)?.offset?.let { it - 1 }
}

/**
 * East / north wind components turned into a Lambert grid's own axes (the barbs and the read-out of the wind products take them that way): at a cell on
 * [longitude] the grid's "up" is [WindProducts.CONVERGENCE] x (longitude + 97.5) degrees off north, for the REFS projection the sector grids are planned on.
 */
object GfsWinds {
    fun rotate(east: FloatArray, north: FloatArray, longitude: FloatArray): Pair<FloatArray, FloatArray> {
        val u = FloatArray(east.size)
        val v = FloatArray(east.size)
        for (i in east.indices) {
            val e = east[i]
            val n = north[i]
            if (e.isNaN() || n.isNaN()) { u[i] = Float.NaN; v[i] = Float.NaN; continue }
            val a = Math.toRadians(WindProducts.CONVERGENCE * (longitude[i] + 97.5))
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            u[i] = e * c - n * s
            v[i] = n * c + e * s
        }
        return u to v
    }
}

/** Loads GFS runs, frames and fields over [http] (messages by byte range, kept in [store]) and puts them on a sector's Lambert grid with [core]. */
class GfsRepository(private val http: Http, private val core: Core, private val store: GribStore = MemoryGribStore(24)) {
    /** The runs within [hours] of now, newest first. */
    suspend fun listCycles(nowEpochSeconds: Long, hours: Int = 72): List<NbmCycle> {
        val found = ArrayList<NbmCycle>()
        val earliest = nowEpochSeconds - hours * 3600L
        var day = nowEpochSeconds - nowEpochSeconds % 86400L
        while (day + 86400L > earliest) {
            val c = civilFromEpoch(day)
            val prefix = "gfs.${c[0]}${c[1].toString().padStart(2, '0')}${c[2].toString().padStart(2, '0')}/"
            val result = http.get("${GfsBucket.URL}/?list-type=2&delimiter=/&prefix=$prefix")
            if (result.status == 200) {
                for (h in parseGribRunHours(result.body.decodeToString())) {
                    val cycle = gfsCycle(c[0], c[1], c[2], h)
                    if (cycle.epoch <= nowEpochSeconds + 300 && cycle.epoch >= earliest) found += cycle
                }
            } else if (result.status != 404) throw RefsException("could not list the GFS runs (HTTP ${result.status})")
            day -= 86400L
        }
        return found.sortedByDescending { it.epoch }
    }

    /** Every forecast hour of a run (a run still being written has fewer). The frames carry [fieldId] for the page's cache. */
    suspend fun listFrames(cycle: NbmCycle, fieldId: String): List<NbmFile> {
        val c = cycle
        val filePrefix = c.prefix + "gfs.t${c.hour.toString().padStart(2, '0')}z.pgrb2.0p25.f"
        val result = http.get("${GfsBucket.URL}/?list-type=2&max-keys=1000&prefix=$filePrefix")
        if (result.status != 200) throw RefsException("could not list the GFS files (HTTP ${result.status})")
        return parseGfsListing(result.body.decodeToString(), cycle, fieldId)
    }

    private val indexes = LinkedHashMap<String, List<GribIndexEntry>>(8, 0.75f, true)

    private suspend fun index(file: NbmFile): List<GribIndexEntry> = indexes[file.url] ?: run {
        val result = http.get(file.url + ".idx")
        if (result.status != 200) throw RefsException("the GFS index could not be read (HTTP ${result.status})")
        parseGribIndex(result.body.decodeToString()).also { indexes[file.url] = it; while (indexes.size > 8) indexes.remove(indexes.keys.first()) }
    }

    /**
     * The messages of [wanted] in the file, by name: from the store, else downloaded (messages that touch are one request, see [coalesceRanges]) and kept.
     * Keys of the result are "VARIABLE:level".
     */
    private suspend fun messages(file: NbmFile, wanted: List<GfsMessage>, status: (String) -> Unit): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        val missing = ArrayList<Triple<String, Long, Long>>()
        val entries by lazy { kotlinx.coroutines.CompletableDeferred<List<GribIndexEntry>>() }
        var idx: List<GribIndexEntry>? = null
        for (m in wanted) {
            val name = "${m.variable}:${m.level}"
            store.get(file.url, name)?.let { out[name] = it; return@let }
            if (name in out) continue
            if (idx == null) { status("Reading the GFS index…"); idx = index(file) }
            val (first, last) = findGfsMessage(idx, m, file.leadHours) ?: throw RefsException("that hour has no ${m.variable} at ${m.level}")
            missing += Triple(name, first, last ?: (file.size - 1))
        }
        if (missing.isEmpty()) return out
        for (range in coalesceRanges(missing.map { it.second..it.third })) {
            status("Downloading ${(range.last - range.first + 1) / 1000} kB…")
            val part = http.getRange(file.url, range.first, range.last)
            if (part.status != 200 && part.status != 206 || part.body.size.toLong() != range.last - range.first + 1) throw RefsException("the GFS data could not be downloaded (HTTP ${part.status})")
            for ((name, first, last) in missing) if (first >= range.first && last <= range.last) {
                val bytes = part.body.copyOfRange((first - range.first).toInt(), (last - range.first + 1).toInt())
                out[name] = bytes
                store.put(file.url, name, bytes)
            }
        }
        return out
    }

    /** The messages a wind product needs, in the order [loadWind] reads them: height, u, v, and the color field unless it is the speed. */
    fun windMessages(level: Int, colorKey: String): List<GfsMessage> =
        listOfNotNull(GfsMessage("HGT", "$level mb"), GfsMessage("UGRD", "$level mb"), GfsMessage("VGRD", "$level mb"), GfsCatalog.colorMessage(level, colorKey))

    private val nomadsGate = kotlinx.coroutines.sync.Semaphore(2)     // NOMADS is asked politely: two requests at a time, a short rest after each

    /** The cropped file of [wanted] inside [region] (see [nomadsGfsUrl]): from the store, else asked of NOMADS and kept. Throws when NOMADS does not deliver. */
    private suspend fun cropDownload(file: NbmFile, wanted: List<GfsMessage>, region: DoubleArray, status: (String) -> Unit): ByteArray {
        val url = nomadsGfsUrl(file, wanted, region) ?: throw RefsException("the NOMADS filter does not know one of the fields")
        // the file as NOMADS sent it is kept as it is, with the other frames of the same run, box and fields in one subset file (a message per forecast hour)
        val run = civilFromEpoch(file.run)
        fun two(n: Int) = n.toString().padStart(2, '0')
        val source = "https://nomads.ncep.noaa.gov/gfs.${run[0]}${two(run[1])}${two(run[2])}/${two(run[3])}/atmos|box=${region.joinToString(",")}|" +
            wanted.joinToString(",") { "${it.variable}:${it.level}" }
        val name = file.key.substringAfterLast('/')
        store.get(source, name)?.let { return it }
        status("Asking NOMADS for the region\u2026")
        val result = nomadsGate.withPermit { http.get(url).also { kotlinx.coroutines.delay(150) } }
        if (result.status != 200 || result.body.size < 100 || result.body[0] != 'G'.code.toByte()) throw RefsException("NOMADS did not return the data (HTTP ${result.status})")
        store.put(source, name, result.body)
        return result.body
    }

    /**
     * The decoded fields of [wanted] (keys "VARIABLE:level"), unconverted: cropped to [region] by NOMADS when a region is given, and from the AWS byte ranges when
     * that is not given or NOMADS does not deliver (the reason is passed to [status]).
     */
    private suspend fun fields(file: NbmFile, wanted: List<GfsMessage>, region: DoubleArray?, status: (String) -> Unit): Map<String, GridField> {
        if (region != null) {
            try {
                val bytes = cropDownload(file, wanted, region, status)
                status("Source: NOMADS (cropped)")
                status("Decoding\u2026")
                val decoded = splitGribMessages(bytes).map { decodeRaw(it) }
                return wanted.associate { m ->
                    val spec = gfsCropSpec(m)!!
                    "${m.variable}:${m.level}" to (decoded.firstOrNull { it.meta?.matches(spec) == true } ?: throw RefsException("NOMADS sent no ${m.variable} at ${m.level}"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status("NOMADS: ${e.message}. Using AWS\u2026")
            }
        }
        val bytes = messages(file, wanted, status)
        status(if (region != null) "Source: AWS (NOMADS did not answer)" else "Source: AWS")
        status("Decoding\u2026")
        return wanted.associate { "${it.variable}:${it.level}" to decodeRaw(bytes.getValue("${it.variable}:${it.level}")) }
    }

    private fun decodeRaw(bytes: ByteArray): GridField = try { core.decodeGrib(bytes) } catch (e: Exception) { throw RefsException("could not decode the GFS message: ${e.message}") }

    /** Downloads (and keeps) what a frame of a product needs, so a loop plays without waiting; a wind product when [product] is null. */
    suspend fun prefetch(file: NbmFile, level: Int, colorKey: String, product: GfsProduct?, region: DoubleArray? = null) {
        val wanted = if (product != null) listOfNotNull(product.message) else windMessages(level, colorKey)
        if (region != null) {
            try { cropDownload(file, wanted, region) { }; return } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* AWS below */ }
        }
        messages(file, wanted) { }
    }

    private fun plane(field: GridField, convert: (Float) -> Float, target: Grid): FloatArray {
        val v = field.values
        for (i in v.indices) v[i] = convert(v[i])
        return core.reproject(v, field.grid, target)
    }

    /** A single field of [product] on [target] (a sector's Lambert grid); [region] crops the download (see [fields]). */
    suspend fun loadField(file: NbmFile, product: GfsProduct, target: Grid, status: (String) -> Unit = {}, region: DoubleArray? = null): FloatArray {
        val message = product.message ?: throw RefsException("not a single field")
        val field = fields(file, listOf(message), region, status).getValue("${message.variable}:${message.level}")
        return plane(field, product.convert, target)
    }

    /**
     * The four planes of a wind product (see [WindProducts.pack]) for [level] over [colorKey], on [target]: the color field (the speed when the color is
     * "speed"), the height, and the wind turned onto the grid's axes, in knots.
     */
    suspend fun loadWind(file: NbmFile, level: Int, colorKey: String, target: Grid, status: (String) -> Unit = {}, region: DoubleArray? = null): FloatArray {
        val wanted = windMessages(level, colorKey)
        val fields = fields(file, wanted, region, status)
        fun field(m: GfsMessage) = fields.getValue("${m.variable}:${m.level}")
        val knots: (Float) -> Float = { it * GfsCatalog.MS_TO_KNOTS }
        val height = plane(field(wanted[0]), { it }, target)
        val east = plane(field(wanted[1]), knots, target)
        val north = plane(field(wanted[2]), knots, target)
        val (u, v) = GfsWinds.rotate(east, north, core.gridLongitudes(target))
        val colorPlane = wanted.getOrNull(3)?.let { plane(field(it), GfsCatalog.colorConvert(colorKey), target) }
        return WindProducts.pack(target.cells, height, u, v, colorPlane)
    }
}
