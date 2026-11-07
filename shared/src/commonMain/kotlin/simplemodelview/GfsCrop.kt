package simplemodelview

/**
 * NOAA's NOMADS "grib filter" crops a GFS file to a box and picks the fields on the server, so a region comes as a small GRIB2 file (tens of kilobytes) instead
 * of whole global messages. The same GFS run as the AWS copy; the app finds runs and frames on AWS and asks NOMADS for the data, and falls back to the AWS
 * byte ranges when NOMADS does not answer (see [GfsRepository]).
 */
object Nomads {
    const val GFS_FILTER = "https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl"
}

/** How a message is named in the filter's request and in the file it returns: parameter and level. */
class GfsCropSpec(val variable: String, val levelParam: String, val category: Int, val number: Int, val levelType: Int, val levelValue: Int)

/** The filter's names for a [GfsMessage], or null when this app does not know the field. */
fun gfsCropSpec(message: GfsMessage): GfsCropSpec? {
    val (category, number) = when (message.variable) {
        "HGT" -> 3 to 5; "UGRD" -> 2 to 2; "VGRD" -> 2 to 3; "TMP" -> 0 to 0; "RH" -> 1 to 1; "DPT" -> 0 to 6
        "PRMSL" -> 3 to 1; "PWAT" -> 1 to 3; "CAPE" -> 7 to 6
        else -> return null
    }
    val level = message.level
    return when {
        level.endsWith(" mb") -> level.removeSuffix(" mb").toIntOrNull()?.let { GfsCropSpec(message.variable, "lev_${it}_mb", category, number, 100, it * 100) }
        level == "2 m above ground" -> GfsCropSpec(message.variable, "lev_2_m_above_ground", category, number, 103, 2)
        level == "mean sea level" -> GfsCropSpec(message.variable, "lev_mean_sea_level", category, number, 101, 0)
        level == "surface" -> GfsCropSpec(message.variable, "lev_surface", category, number, 1, 0)
        level.startsWith("entire atmosphere") -> GfsCropSpec(message.variable, "lev_entire_atmosphere_%28considered_as_a_single_layer%29", category, number, 200, 0)
        else -> null
    }
}

/**
 * The filter request for [messages] of a GFS [file] (an [NbmFile] of the GFS bucket) inside [region] (west, south, east, north in degrees, west-negative): every
 * variable at every level named (the filter returns each combination that exists, so a message may come that was not asked for), and the box in 0..360 east.
 */
fun nomadsGfsUrl(file: NbmFile, messages: List<GfsMessage>, region: DoubleArray): String? {
    val specs = messages.map { gfsCropSpec(it) ?: return null }
    val run = civilFromEpoch(file.run)
    fun two(n: Int) = n.toString().padStart(2, '0')
    val name = file.key.substringAfterLast('/')
    fun east(lon: Double) = ((lon % 360.0) + 360.0) % 360.0
    fun num(v: Double) = ((v * 100).toLong() / 100.0).toString().removeSuffix(".0")
    val vars = specs.map { it.variable }.distinct().joinToString("") { "&var_$it=on" }
    val levels = specs.map { it.levelParam }.distinct().joinToString("") { "&$it=on" }
    return "${Nomads.GFS_FILTER}?dir=%2Fgfs.${run[0]}${two(run[1])}${two(run[2])}%2F${two(run[3])}%2Fatmos&file=$name$vars$levels" +
        "&subregion=&toplat=${num(region[3])}&leftlon=${num(east(region[0]))}&rightlon=${num(east(region[2]))}&bottomlat=${num(region[1])}"
}

/** The messages of a GRIB2 file of several messages (the filter returns one per variable and level), each as its own bytes; anything that is not a message is dropped. */
fun splitGribMessages(bytes: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var at = 0
    while (at + 16 <= bytes.size) {
        if (bytes[at] != 'G'.code.toByte() || bytes[at + 1] != 'R'.code.toByte() || bytes[at + 2] != 'I'.code.toByte() || bytes[at + 3] != 'B'.code.toByte()) { at += 1; continue }
        var length = 0L
        for (i in 8 until 16) length = (length shl 8) or (bytes[at + i].toLong() and 0xFF)
        if (length < 20 || at + length > bytes.size) break
        out += bytes.copyOfRange(at, at + length.toInt())
        at += length.toInt()
    }
    return out
}

/** True when a decoded message is [spec]'s (parameter and level). */
fun GribMeta.matches(spec: GfsCropSpec) = category == spec.category && number == spec.number && levelType == spec.levelType && levelValue == spec.levelValue
