package simplemodelview

import kotlin.math.ceil

/**
 * An MRMS product as the public AWS bucket (`noaa-mrms-pds`) names it: one GRIB2 file per scan under `CONUS/<id>/<yyyymmdd>/`.
 * The files are PNG-packed GRIB2 on a regular latitude / longitude grid of [cellDegrees] (see core/src/mrms_decode.h).
 */
data class MrmsProduct(
    val id: String,                 // directory name, e.g. "MergedReflectivityQCComposite_00.50"
    val title: String,
    val pool: Pool,                 // how cells combine when a window is decimated
    val missingAtOrBelow: Double,   // stored values at or below this are "no coverage" / "no echo" codes
    val style: Style,
    val cadenceMinutes: Int,        // between files (2 for most, 30 / 60 for the long accumulations)
    val frames: Int,                // how many of the newest files the slider covers
    val cellDegrees: Double,        // the files' cell size
    val group: String,
    val quantity: Quantity = Quantity.None,   // what [style] measures, so its unit can be switched
    /** The 3D mosaic families have one server directory per height (km above sea level); [level] is the chosen index into this list. */
    val levels: List<Double> = emptyList(),
    val level: Int = 0,
) {
    /** The server directory this product's files are in: [id], plus the chosen height ("MergedZdr_03.00") for the 3D mosaic families. */
    val directory: String get() = if (levels.isEmpty()) id else "${id}_${levelName(levels[level.coerceIn(0, levels.size - 1)])}"

    /** The height as shown: "3.0 km (9.8 kft)"; empty for products without levels. */
    val levelLabel: String
        get() {
            if (levels.isEmpty()) return ""
            val km = levels[level.coerceIn(0, levels.size - 1)]
            val tenths = kotlin.math.round(km * 100).toInt() % 10 == 0     // 3.0, 0.5, 10.0 read with one decimal; 0.75, 1.25 with two
            return "${fixed(km, if (tenths) 1 else 2)} km (${fixed(km * 3.28084, 1)} kft)"
        }

    /** The title with the chosen height: "3D Reflectivity, 3.0 km (9.8 kft)". */
    val displayTitle: String get() = if (levels.isEmpty()) title else "$title, $levelLabel"
}

/** A level's directory suffix as the bucket spells it: 0.5 -> "00.50", 3.0 -> "03.00", 19.0 -> "19.00". */
fun levelName(km: Double): String {
    val hundredths = kotlin.math.round(km * 100).toInt()
    return (hundredths / 100).toString().padStart(2, '0') + "." + (hundredths % 100).toString().padStart(2, '0')
}

/** One file on the server: its key, the scan's valid time and when it was uploaded (the bucket's LastModified), epoch seconds. */
data class MrmsFile(val key: String, val validTime: Long, val uploaded: Long?, val size: Long) {
    val url: String get() = MrmsCatalog.BUCKET + "/" + key
}

/** A decoded frame of the shown sector. */
class MrmsFrame(val window: MrmsWindow, val file: MrmsFile)

private fun two(n: Int) = n.toString().padStart(2, '0')
private fun four(n: Int) = n.toString().padStart(4, '0')

/** Epoch seconds -> (year, month, day, hour, minute, second) in UTC. */
fun civilFromEpoch(epoch: Long): IntArray {
    val days = floorDiv(epoch, 86400L)
    val secs = floorMod(epoch, 86400L).toInt()
    val z = days + 719468
    val era = floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt()
    return intArrayOf(y, m, d, secs / 3600, secs % 3600 / 60, secs % 60)
}

internal fun epochOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
    daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second

/** "20261002-130039" from a file name "MRMS_<id>_20261002-130039.grib2.gz" -> epoch seconds, or null. */
fun mrmsValidTime(key: String): Long? {
    val name = key.substringAfterLast('/').removeSuffix(".grib2.gz")
    val stamp = name.takeLast(15)
    if (stamp.length != 15 || stamp[8] != '-' || !stamp.filterIndexed { i, _ -> i != 8 }.all { it.isDigit() }) return null
    return epochOf(stamp.substring(0, 4).toInt(), stamp.substring(4, 6).toInt(), stamp.substring(6, 8).toInt(),
        stamp.substring(9, 11).toInt(), stamp.substring(11, 13).toInt(), stamp.substring(13, 15).toInt())
}

/** "2026-10-02T13:01:25.000Z" -> epoch seconds, or null. */
internal fun isoToEpoch(iso: String): Long? {
    if (iso.length < 19) return null
    return try {
        epochOf(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt(), iso.substring(8, 10).toInt(),
            iso.substring(11, 13).toInt(), iso.substring(14, 16).toInt(), iso.substring(17, 19).toInt())
    } catch (e: NumberFormatException) { null }
}

/** The files of an S3 `ListObjectsV2` reply for one product, oldest first. Anything that does not look like a scan file is skipped. */
fun parseMrmsListing(xml: String): List<MrmsFile> {
    val files = ArrayList<MrmsFile>()
    var at = 0
    while (true) {
        val start = xml.indexOf("<Contents>", at)
        if (start < 0) break
        val end = xml.indexOf("</Contents>", start)
        if (end < 0) break
        val block = xml.substring(start, end)
        at = end
        val key = block.substringAfter("<Key>", "").substringBefore("</Key>")
        if (!key.endsWith(".grib2.gz")) continue
        val valid = mrmsValidTime(key) ?: continue
        val uploaded = isoToEpoch(block.substringAfter("<LastModified>", "").substringBefore("</LastModified>"))
        val size = block.substringAfter("<Size>", "").substringBefore("</Size>").toLongOrNull() ?: 0L
        files += MrmsFile(key, valid, uploaded, size)
    }
    return files.sortedBy { it.validTime }
}

/** The file whose valid time is closest to [validTime], or null when none is within [toleranceSeconds] (an overlay matched to a radar frame). */
fun nearestFile(files: List<MrmsFile>, validTime: Long, toleranceSeconds: Long): MrmsFile? =
    files.minByOrNull { kotlin.math.abs(it.validTime - validTime) }?.takeIf { kotlin.math.abs(it.validTime - validTime) <= toleranceSeconds }

/** The cells to skip so a window of [west]..[east] is about [targetColumns] cells wide (1 = every cell: the files' full resolution). */
fun mrmsStep(cellDegrees: Double, west: Double, east: Double, targetColumns: Int, dataSaver: Boolean): Int {
    val columns = (east - west) / cellDegrees
    val step = ceil(columns / targetColumns).toInt().coerceAtLeast(1)
    return if (dataSaver) step * 2 else step
}

/** Lists and decodes MRMS files from the public AWS bucket over [http]. */
class MrmsRepository(private val http: Http, private val core: Core) {
    /**
     * The newest [MrmsProduct.frames] files at or before [nowEpochSeconds], oldest first. Lists only the keys after a point a little before the
     * window (S3 lists in key order, and the day folders sort by date), so the reply is a few dozen entries, not a day's worth.
     */
    suspend fun listFiles(product: MrmsProduct, nowEpochSeconds: Long): List<MrmsFile> {
        for (extraHours in listOf(0L, 24L)) {
            val from = nowEpochSeconds - (product.frames + 3L) * product.cadenceMinutes * 60 - extraHours * 3600
            val c = civilFromEpoch(from)
            val prefix = "CONUS/${product.directory}/"
            val after = "$prefix${four(c[0])}${two(c[1])}${two(c[2])}/MRMS_${product.directory}_${four(c[0])}${two(c[1])}${two(c[2])}-${two(c[3])}${two(c[4])}${two(c[5])}.grib2.gz"
            val result = http.get("${MrmsCatalog.BUCKET}/?list-type=2&max-keys=1000&prefix=$prefix&start-after=$after")
            if (result.status != 200) throw RefsException("could not list MRMS files (HTTP ${result.status})")
            val files = parseMrmsListing(result.body.decodeToString()).filter { it.validTime <= nowEpochSeconds + 300 }
            if (files.isNotEmpty()) return files.takeLast(product.frames)
        }
        return emptyList()
    }

    /**
     * Every file of [product] with a valid time from [sinceEpochSeconds] to [nowEpochSeconds], oldest first (an overlay needs the files that fall in the
     * span of the radar frames being shown; its own newest-N window would not line up with them).
     */
    suspend fun listFilesSince(product: MrmsProduct, sinceEpochSeconds: Long, nowEpochSeconds: Long): List<MrmsFile> {
        val from = sinceEpochSeconds - 3L * product.cadenceMinutes * 60
        val c = civilFromEpoch(from)
        val prefix = "CONUS/${product.directory}/"
        val after = "$prefix${four(c[0])}${two(c[1])}${two(c[2])}/MRMS_${product.directory}_${four(c[0])}${two(c[1])}${two(c[2])}-${two(c[3])}${two(c[4])}${two(c[5])}.grib2.gz"
        val result = http.get("${MrmsCatalog.BUCKET}/?list-type=2&max-keys=1000&prefix=$prefix&start-after=$after")
        if (result.status != 200) throw RefsException("could not list MRMS files (HTTP ${result.status})")
        return parseMrmsListing(result.body.decodeToString()).filter { it.validTime >= sinceEpochSeconds - product.cadenceMinutes * 60 && it.validTime <= nowEpochSeconds + 300 }
    }

    /** Downloads the file (an HTTP cache keeps it) and decodes the [west]..[east] x [south]..[north] window, every [step]-th cell. */
    suspend fun loadFrame(
        product: MrmsProduct, file: MrmsFile, west: Double, south: Double, east: Double, north: Double, step: Int,
    ): MrmsFrame {
        val bytes = download(file)
        val window = try {
            core.decodeMrms(bytes, west, south, east, north, step, product.pool, product.missingAtOrBelow)
        } catch (e: Exception) {
            throw RefsException("could not decode ${product.title}: ${e.message}")
        }
        return MrmsFrame(window, file)
    }

    /** Downloads a file without decoding it, so the HTTP cache has it for a loop. */
    suspend fun prefetch(file: MrmsFile) { download(file) }

    private suspend fun download(file: MrmsFile): ByteArray {
        var result = http.get(file.url)
        if (result.status != 200 && result.status != 404 && result.status != 403) result = http.get(file.url)   // one retry
        return when (result.status) {
            200 -> if (result.body.isEmpty()) throw RefsException("empty MRMS file") else result.body
            404, 403 -> throw RefsException("that MRMS file is no longer on the server")
            else -> throw RefsException("the MRMS file could not be downloaded (HTTP ${result.status})")
        }
    }
}
