package simplemodelview

/**
 * NOAA's National Blend of Models as the public AWS bucket `noaa-nbm-pds` has it: one GeoTIFF per field and forecast hour under
 * `blendv5.0/conus/<yyyy>/<MM>/<dd>/<HH>00/<field>/blendv5.0_conus_<field>_<run>_<valid>.tif`. A new run starts every hour; every complete run
 * reaches about 11 days, with frames every hour for the first day and a half, then every 3 h, then every 6 h (daily extremes: every 24 h).
 */
object NbmBucket {
    const val URL = "https://noaa-nbm-pds.s3.amazonaws.com"
    const val REGION = "conus"

    /** The version folders to look in when the bucket cannot be asked (newest first). Normally they are found by listing the bucket (see [parseNbmVersions]). */
    val VERSIONS = listOf("blendv5.1", "blendv5.0")

    /** How many of the newest version folders are searched for runs (a new version's folder starts, and the old one's stops, on the day of the change). */
    const val VERSIONS_SEARCHED = 2

    /** A run at least this many hours long counts as "extended" (a short-range run stops after about 36 h). */
    const val EXTENDED_HOURS = 168
}

/** One run of the blend (init time, UTC). */
data class NbmCycle(val year: Int, val month: Int, val day: Int, val hour: Int, val version: String = "blendv5.0") {
    val epoch: Long get() = epochOf(year, month, day, hour, 0, 0)

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** "17Z" */
    val shortLabel: String get() = "${two(hour)}Z"

    /** "2026-10-30 17Z" */
    val label: String get() = "$year-${two(month)}-${two(day)} ${two(hour)}Z"

    /** The folder of this run (with a trailing slash). */
    val prefix: String get() = when {
        version == GfsBucket.VERSION -> "gfs.$year${two(month)}${two(day)}/${two(hour)}/atmos/"
        grib -> "blend.$year${two(month)}${two(day)}/${two(hour)}/core/"
        else -> "$version/${NbmBucket.REGION}/$year/${two(month)}/${two(day)}/${two(hour)}00/"
    }

    /** True for a run found in the NBM GRIB bucket (see [NbmGribBucket]). */
    val grib: Boolean get() = version == NbmGribBucket.VERSION

    companion object {
        fun ofEpoch(epoch: Long, version: String = "blendv5.0"): NbmCycle = civilFromEpoch(epoch).let { NbmCycle(it[0], it[1], it[2], it[3], version) }
    }
}

/** One field at one forecast time: its key, the run and valid times, and when it was uploaded (the bucket's LastModified), epoch seconds. */
data class NbmFile(val key: String, val run: Long, val valid: Long, val uploaded: Long?, val size: Long, val grib: Boolean = false, val field: String = "", val bucketUrl: String = "") {
    /** The colons of the time stamps are percent-encoded, as the bucket's own links do. A GRIB file is in the other bucket ([field] says which of its messages is meant). */
    val url: String get() = (bucketUrl.ifEmpty { if (grib) NbmGribBucket.URL else NbmBucket.URL }) + "/" + key.replace(":", "%3A")

    /** Hours after the run's start. */
    val leadHours: Int get() = ((valid - run) / 3600).toInt()

    /** What a decoded frame is kept under: a GRIB file holds every field, so the field is part of it. */
    val cacheKey: String
        get() = if (grib) key + "#" + this.field else key
}

/** "2026-10-30T17:00" -> epoch seconds, or null. */
internal fun nbmStamp(text: String): Long? {
    if (text.length != 16 || text[4] != '-' || text[7] != '-' || text[10] != 'T' || text[13] != ':') return null
    return try {
        epochOf(text.substring(0, 4).toInt(), text.substring(5, 7).toInt(), text.substring(8, 10).toInt(), text.substring(11, 13).toInt(), text.substring(14, 16).toInt(), 0)
    } catch (e: NumberFormatException) { null }
}

/** The run and valid times of a key "…/blendv5.0_conus_temp_2026-10-30T00:00_2026-10-31T17:00.tif" (also with %3A), or null. */
fun nbmKeyTimes(key: String): Pair<Long, Long>? {
    val name = key.substringAfterLast('/').replace("%3A", ":").removeSuffix(".tif")
    if (name.length < 34) return null
    val valid = nbmStamp(name.takeLast(16)) ?: return null
    if (name[name.length - 17] != '_') return null
    val run = nbmStamp(name.substring(name.length - 33, name.length - 17)) ?: return null
    return run to valid
}

/** The files of an S3 `ListObjectsV2` reply, ordered by valid time; anything that does not look like a blend file is skipped. */
fun parseNbmListing(xml: String): List<NbmFile> {
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
        if (!key.endsWith(".tif")) continue
        val (run, valid) = nbmKeyTimes(key) ?: continue
        val uploaded = isoToEpoch(block.substringAfter("<LastModified>", "").substringBefore("</LastModified>"))
        val size = block.substringAfter("<Size>", "").substringBefore("</Size>").toLongOrNull() ?: 0L
        files += NbmFile(key, run, valid, uploaded, size)
    }
    return files.sortedBy { it.valid }
}

/**
 * The version folders (`blendv5.0`, `blendv5.1`, ...) of a listing of the bucket's top level made with a `/` delimiter, newest first. A new version of the
 * blend gets its own folder, so the page finds it without a new app version; anything else in the listing is ignored.
 */
fun parseNbmVersions(xml: String): List<String> {
    val found = ArrayList<Triple<Int, Int, String>>()
    var at = 0
    while (true) {
        val start = xml.indexOf("<Prefix>", at)
        if (start < 0) break
        val end = xml.indexOf("</Prefix>", start)
        if (end < 0) break
        at = end
        val name = xml.substring(start + 8, end).trimEnd('/')
        if (!name.startsWith("blendv")) continue
        val parts = name.removePrefix("blendv").split('.')
        if (parts.size != 2) continue
        val major = parts[0].toIntOrNull() ?: continue
        val minor = parts[1].toIntOrNull() ?: continue
        found += Triple(major, minor, name)
    }
    return found.sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second }).map { it.third }
}

/** The run folders ("0000", "1700", ...) of a day's listing made with a `/` delimiter, as hours. */
fun parseNbmRunHours(xml: String): List<Int> {
    val hours = ArrayList<Int>()
    var at = 0
    while (true) {
        val start = xml.indexOf("<Prefix>", at)
        if (start < 0) break
        val end = xml.indexOf("</Prefix>", start)
        if (end < 0) break
        at = end
        val folder = xml.substring(start + 8, end).trimEnd('/').substringAfterLast('/')
        if (folder.length == 4 && folder.endsWith("00") && folder.all { it.isDigit() }) folder.take(2).toInt().takeIf { it in 0..23 }?.let { hours += it }
    }
    return hours.distinct().sorted()
}

/**
 * How the cycle picker annotates a run: "extended, to day 11" for a run that reaches [NbmBucket.EXTENDED_HOURS] or more, "short range, to 36 h" for
 * one that stops sooner, "not ready" (or "checking...") when nothing is known. [leadHours] is the lead of the run's last frame of a reference field.
 */
fun nbmCycleNote(leadHours: Int?, checked: Boolean): String = when {
    leadHours == null -> if (checked) "not ready" else "checking…"
    leadHours >= NbmBucket.EXTENDED_HOURS -> "extended, to day ${(leadHours + 23) / 24}"
    else -> "short range, to $leadHours h"
}

/** A run is "extended" when its last frame is at least [NbmBucket.EXTENDED_HOURS] after the run starts. */
fun nbmIsExtended(leadHours: Int?): Boolean = leadHours != null && leadHours >= NbmBucket.EXTENDED_HOURS

/** The hours from frame [index] to the next one (the last frame: the step before it); 1 when there is only one frame. */
fun nbmStepHours(frames: List<NbmFile>, index: Int): Int {
    if (frames.size < 2) return 1
    val i = index.coerceIn(0, frames.size - 1)
    val h = if (i < frames.size - 1) frames[i + 1].valid - frames[i].valid else frames[i].valid - frames[i - 1].valid
    return maxOf(1, (h / 3600).toInt())
}

/**
 * How long a loop shows frame [index]: [baseMillis] for each forecast hour it stands for, so an hourly frame is shown for 1 x the base, a 3-hourly one for
 * 3 x and a 6-hourly one for 6 x, and a day of forecast takes the same time to play whatever the frame spacing.
 */
fun nbmDwellMillis(frames: List<NbmFile>, index: Int, baseMillis: Long): Long = nbmStepHours(frames, index) * baseMillis

/** A loop length offered on the NBM page: the first [hours] of the run (0 = all of it). */
data class NbmLoopSpan(val label: String, val hours: Int)

/** The spans offered for a run whose frames reach [totalHours] after its first one: round numbers shorter than the run, then all of it. */
fun nbmLoopSpans(totalHours: Int): List<NbmLoopSpan> =
    listOf(12 to "12 h", 24 to "24 h", 48 to "2 days", 72 to "3 days", 120 to "5 days", 168 to "7 days")
        .filter { it.first < totalHours }.map { NbmLoopSpan(it.second, it.first) } + NbmLoopSpan("all", 0)

/** The frames a loop of [spanHours] covers (0 = all): from the first one to the last within the span (at least two frames when there are two). */
fun nbmLoopWindow(frames: List<NbmFile>, spanHours: Int, start: Int = 0): IntRange {
    if (frames.isEmpty()) return IntRange.EMPTY
    val from = start.coerceIn(0, frames.size - 1)
    val window = when {
        spanHours == 0 -> from until frames.size
        spanHours < 0 -> from..minOf(frames.size - 1, from + (-spanHours) - 1)       // that many frames ("steps"), whatever their spacing
        else -> {
            val limit = frames[from].valid + spanHours * 3600L
            from..frames.indexOfLast { it.valid <= limit }
        }
    }
    // a loop of one frame is nothing to play: at the end of the run, the last two frames
    return if (window.count() < 2 && frames.size >= 2) minOf(from, frames.size - 2)..(minOf(from, frames.size - 2) + 1) else window
}

/** The frame counts a loop can be given in steps instead of hours; a span of -N stands for N steps (see [nbmLoopWindow]). */
val NBM_LOOP_STEPS = listOf(12, 24)

/** "Tue 03 Nov 06Z, +85 h" for a frame: its valid time and its lead after the run. */
fun nbmFrameLabel(file: NbmFile): String = "${formatUtc(file.valid)}  (+${file.leadHours} h)"

/** Lists, downloads and decodes the blend's files over [http]. */
class NbmRepository(private val http: Http, private val core: Core, private val store: GribStore = MemoryGribStore()) {
    /** The runs from the last [hours] hours up to [nowEpochSeconds], newest first (each day's folder lists the runs it holds). */
    private var versionsAt = 0L
    private var versions: List<String> = emptyList()

    /** The newest version folders of the bucket, asked for at most every six hours; the built-in list when the bucket does not answer. */
    private suspend fun versionFolders(nowEpochSeconds: Long): List<String> {
        if (versions.isNotEmpty() && nowEpochSeconds - versionsAt < 6 * 3600) return versions
        val found = try {
            val result = http.get("${NbmBucket.URL}/?list-type=2&delimiter=/")
            if (result.status == 200) parseNbmVersions(result.body.decodeToString()).take(NbmBucket.VERSIONS_SEARCHED) else emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        if (found.isNotEmpty()) { versions = found; versionsAt = nowEpochSeconds }
        return found.ifEmpty { versions.ifEmpty { NbmBucket.VERSIONS } }
    }

    suspend fun listCycles(nowEpochSeconds: Long, hours: Int = 48): List<NbmCycle> {
        val folders = versionFolders(nowEpochSeconds)
        val found = ArrayList<NbmCycle>()
        val earliest = nowEpochSeconds - hours * 3600L
        var day = nowEpochSeconds - nowEpochSeconds % 86400L
        while (day + 86400L > earliest) {
            val c = civilFromEpoch(day)
            for (version in folders) {
                val prefix = "$version/${NbmBucket.REGION}/${c[0]}/${c[1].toString().padStart(2, '0')}/${c[2].toString().padStart(2, '0')}/"
                val result = http.get("${NbmBucket.URL}/?list-type=2&delimiter=/&prefix=$prefix")
                if (result.status == 200) {
                    for (h in parseNbmRunHours(result.body.decodeToString())) {
                        val cycle = NbmCycle(c[0], c[1], c[2], h, version)
                        if (cycle.epoch <= nowEpochSeconds + 300 && cycle.epoch >= earliest && found.none { it.epoch == cycle.epoch }) found += cycle    // (the newer version wins a run both have)
                    }
                } else if (result.status != 404) throw RefsException("could not list the blend's runs (HTTP ${result.status})")
            }
            day -= 86400L
        }
        val tiffs = found.sortedByDescending { it.epoch }
        // when the rendered files are behind, the GRIB runs newer than they are go on top (a field the GRIB files lack falls back to the older rendered run)
        val newest = tiffs.firstOrNull()?.epoch
        if (newest == null || nowEpochSeconds - newest > 2 * 3600L) {
            val grib = try { listGribCycles(nowEpochSeconds, hours) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            val ahead = grib.filter { newest == null || it.epoch - newest >= NbmGribBucket.PREFER_AFTER_HOURS * 3600L }
            if (ahead.isNotEmpty()) return ahead + tiffs
        }
        return tiffs
    }

    /** The runs of the GRIB bucket within [hours] of now, newest first. */
    private suspend fun listGribCycles(nowEpochSeconds: Long, hours: Int): List<NbmCycle> {
        val found = ArrayList<NbmCycle>()
        val earliest = nowEpochSeconds - hours * 3600L
        var day = nowEpochSeconds - nowEpochSeconds % 86400L
        while (day + 86400L > earliest) {
            val c = civilFromEpoch(day)
            val prefix = "blend.${c[0]}${c[1].toString().padStart(2, '0')}${c[2].toString().padStart(2, '0')}/"
            val result = http.get("${NbmGribBucket.URL}/?list-type=2&delimiter=/&prefix=$prefix")
            if (result.status == 200) {
                for (h in parseGribRunHours(result.body.decodeToString())) {
                    val cycle = NbmCycle(c[0], c[1], c[2], h, NbmGribBucket.VERSION)
                    if (cycle.epoch <= nowEpochSeconds + 300 && cycle.epoch >= earliest) found += cycle
                }
            } else if (result.status != 404) throw RefsException("could not list the blend's GRIB runs (HTTP ${result.status})")
            day -= 86400L
        }
        return found.sortedByDescending { it.epoch }
    }

    /** Every frame of [fieldId] in [cycle], ordered by valid time (empty when the run does not have that field yet). */
    suspend fun listFrames(fieldId: String, cycle: NbmCycle): List<NbmFile> {
        if (cycle.grib) {
            if (!NbmGribFields.supports(fieldId)) return emptyList()
            val result = http.get("${NbmGribBucket.URL}/?list-type=2&max-keys=1000&prefix=${cycle.prefix}")
            if (result.status != 200) throw RefsException("could not list the blend's GRIB files (HTTP ${result.status})")
            return parseGribListing(result.body.decodeToString(), cycle, fieldId)
        }
        val result = http.get("${NbmBucket.URL}/?list-type=2&max-keys=1000&prefix=${cycle.prefix}$fieldId/")
        if (result.status != 200) throw RefsException("could not list the blend's files (HTTP ${result.status})")
        return parseNbmListing(result.body.decodeToString())
    }

    /**
     * The lead, in hours, of the last frame of [fieldId] in [cycle], or null when it has none: one small request that lists only the frames from
     * [NbmBucket.EXTENDED_HOURS] on (the listing is in key order, and a key's valid time sorts after its run), and a second only for a short run.
     */
    suspend fun lastLead(fieldId: String, cycle: NbmCycle): Int? {
        if (cycle.grib) return listFrames(fieldId, cycle).lastOrNull()?.leadHours
        fun stamp(epoch: Long): String {
            val c = civilFromEpoch(epoch)
            fun p(n: Int) = n.toString().padStart(2, '0')
            return "${c[0]}-${p(c[1])}-${p(c[2])}T${p(c[3])}:00"
        }
        val prefix = "${cycle.prefix}$fieldId/${cycle.version}_${NbmBucket.REGION}_${fieldId}_${stamp(cycle.epoch)}_"
        for (after in listOf(NbmBucket.EXTENDED_HOURS, 0)) {
            val start = prefix + stamp(cycle.epoch + after * 3600L - 3600L) + ".tif"
            val result = http.get("${NbmBucket.URL}/?list-type=2&max-keys=1000&prefix=${cycle.prefix}$fieldId/&start-after=${start.replace(":", "%3A")}")
            if (result.status != 200) return null
            val last = parseNbmListing(result.body.decodeToString()).lastOrNull()
            if (last != null) return last.leadHours
        }
        return null
    }

    /** Downloads a file (an HTTP cache keeps it) and decodes it whole: the field on the blend's own Lambert grid. */
    suspend fun loadFrame(file: NbmFile, status: (String) -> Unit = {}): GridField {
        if (file.grib) return loadGribFrame(file, status)
        val bytes = download(file)
        return try {
            core.decodeGeotiff(bytes)
        } catch (e: Exception) {
            throw RefsException("could not decode the blend file: ${e.message}")
        }
    }

    /** Downloads a file without decoding it, so the HTTP cache has it for a loop. */
    suspend fun prefetch(file: NbmFile) { if (file.grib) gribMessage(file) { } else download(file) }

    /** A GRIB frame: the file's index says where the field's message is, only that byte range is downloaded, decoded and put into the GeoTIFFs' units. */
    private suspend fun loadGribFrame(file: NbmFile, status: (String) -> Unit): GridField {
        val field = NbmGribFields.byId[file.field] ?: throw RefsException("that field is not in NOAA's GRIB files")
        val bytes = gribMessage(file, status)
        status("Decoding\u2026")
        val decoded = try {
            core.decodeGrib(bytes)
        } catch (e: Exception) {
            throw RefsException("could not decode the GRIB message: ${e.message}")
        }
        val values = decoded.values
        for (i in values.indices) values[i] = field.convert(values[i])
        return GridField(decoded.grid, values)
    }

    // the indexes of the last files; the messages themselves are in the [store] (a range request is not kept by the HTTP cache)
    private val indexes = LinkedHashMap<String, List<GribIndexEntry>>(16, 0.75f, true)

    /** The bytes of the file's message: from the cache, or its index and then just its byte range. [status] says which step is running. */
    private suspend fun gribMessage(file: NbmFile, status: (String) -> Unit): ByteArray {
        store.get(file.url, file.field)?.let { return it }
        val field = NbmGribFields.byId[file.field] ?: throw RefsException("that field is not in NOAA's GRIB files")
        val index = indexes[file.url] ?: run {
            status("Reading the GRIB index\u2026")
            val result = http.get(file.url + ".idx")
            if (result.status != 200) throw RefsException("the GRIB index could not be read (HTTP ${result.status})")
            parseGribIndex(result.body.decodeToString()).also { indexes[file.url] = it; while (indexes.size > 24) indexes.remove(indexes.keys.first()) }
        }
        val (first, last) = findGribMessage(index, field, file.leadHours) ?: throw RefsException("that hour has no ${field.variable} message")
        val end = last ?: (file.size - 1)
        status("Downloading ${field.variable} (${(end - first + 1) / 1000} kB)\u2026")
        val part = http.getRange(file.url, first, end)
        if (part.status != 200 && part.status != 206 || part.body.isEmpty()) throw RefsException("the GRIB message could not be downloaded (HTTP ${part.status})")
        store.put(file.url, file.field, part.body)
        return part.body
    }

    private suspend fun download(file: NbmFile): ByteArray {
        var result = http.get(file.url)
        if (result.status != 200 && result.status != 404 && result.status != 403) result = http.get(file.url)   // one retry
        return when (result.status) {
            200 -> if (result.body.isEmpty()) throw RefsException("empty blend file") else result.body
            404, 403 -> throw RefsException("that blend file is no longer on the server")
            else -> throw RefsException("the blend file could not be downloaded (HTTP ${result.status})")
        }
    }
}
