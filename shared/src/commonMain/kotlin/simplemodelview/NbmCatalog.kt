package simplemodelview

/** A National Blend of Models field: the bucket's folder name, a title and how it is drawn. The stored values are in the units the GeoTIFFs hold (see [NbmCatalog]). */
data class NbmProduct(val id: String, val title: String, val group: String, val style: Style) {
    /** The id as the picker's product type has it (the bucket's folder names are short and could clash with the REFS arrays'). */
    val pickerId: String get() = "nbm_$id"
}

/**
 * The blend fields the app offers, named as the bucket's folders are. Units were checked against the GRIB2 files of the same run (2026-10-30):
 * temperatures and dewpoints in degrees F, winds and gusts in knots, rain, snow and precipitable water in inches, visibility in miles, ceiling in hundreds of
 * feet (1075 = unlimited), CAPE in J/kg, percentages in percent. Missing cells are the files' nodata value (-9999), which the decoder turns into NaN.
 */
object NbmCatalog {
    const val TEMPERATURE = "Temperature"
    const val WIND = "Wind"
    const val PRECIP = "Rain and snow"
    const val SKY = "Sky and visibility"
    const val STORMS = "Storms"
    const val SPREAD = "Percentiles and ranges"
    const val PROBABILITY = "Probabilities"
    const val OTHER = "Fire and more"

    val groups = listOf(TEMPERATURE, WIND, PRECIP, SKY, STORMS, SPREAD, PROBABILITY, OTHER)

    private val dewpoint = Style(
        Kind.Blended,
        listOf(-20 to 0x6b4423, 0 to 0x9c6b30, 20 to 0xc9a66b, 32 to 0xe6dcb4, 45 to 0xb5e08a, 55 to 0x66c25a, 65 to 0x1f9e63, 70 to 0x0f7f8c, 75 to 0x3a5fcd, 80 to 0x6a2c91)
            .map { Stop(it.first.toDouble(), it.second) },
        units = "F", digits = 0,
    )
    private val temperature = Style(Kind.Blended, Palettes.temperature(true), units = "F", digits = 0)
    private val wind = Style(Kind.Banded, Palettes.windSpeed(1.0), hideBelow = 10.0, units = "kt", digits = 0)
    private val probability = Style(Kind.Banded, Palettes.probability, hideBelow = 10.0, units = "%", digits = 0)
    private val rain = Style(Kind.Banded, Palettes.qpf, hideBelow = 0.01, units = "in", digits = 2)
    private val snow = Style(Kind.Banded, Palettes.snow, hideBelow = 0.1, units = "in", digits = 1)
    private val cape = Style(Kind.Banded, Palettes.cape, hideBelow = 100.0, units = "J/kg", digits = 0)
    private val cloud = Style(Kind.Blended, Palettes.cloud(0x4a4a4a), hideBelow = 10.0, units = "%", digits = 0)
    private val humidity = Style(Kind.Banded, Palettes.relativeHumidity, units = "%", digits = 0)
    private val pwat = Style(Kind.Banded, Palettes.precipitableWater, hideBelow = 0.0, units = "in", digits = 2)
    private val visibility = Style(
        Kind.Banded, bands(listOf(0, 0.25, 0.5, 1, 2, 3, 5, 7), listOf(0xd01010, 0xff4a00, 0xffa000, 0xffe14a, 0xa7e03a, 0x4fc34f, 0x9cc7f2, 0xdfeaf6)),
        top = 10.0, units = "mi", digits = 1,
    )
    // stored in hundreds of feet, shown in feet; 107 500 ft is the files' "no ceiling", so the bands end at 20 000 ft
    private val ceiling = Style(
        Kind.Banded, bands(listOf(0, 200, 500, 1000, 2000, 3000, 5000, 10000), listOf(0xd01010, 0xff4a00, 0xffa000, 0xffe14a, 0xa7e03a, 0x4fc34f, 0x9cc7f2, 0xdfeaf6)),
        factor = 100.0, top = 20000.0, units = "ft", digits = 0,
    )

    private val iceAmount = Style(Kind.Banded, Palettes.iceAccretion, hideBelow = 0.005, units = "in", digits = 2)
    private val reflectivity = Style(Kind.Banded, Palettes.reflectivity, units = "dBZ", digits = 0)
    private val vil = Style(Kind.Blended, Palettes.rainbow(0.0, 40.0), hideBelow = 1.0, units = "kg/m\u00B2", digits = 1)
    private val solar = Style(Kind.Blended, Palettes.rainbow(0.0, 1000.0), units = "W/m\u00B2", digits = 0)
    // stored in hundreds of feet like the ceiling, shown in feet
    private val mixingHeight = Style(Kind.Blended, Palettes.rainbow(0.0, 6000.0), factor = 100.0, units = "ft", digits = 0)
    private val snowLevel = Style(Kind.Blended, Palettes.rainbow(0.0, 14000.0), hideBelow = 1.0, units = "ft", digits = 0)
    private val snowRatio = Style(Kind.Blended, Palettes.rainbow(5.0, 20.0), hideBelow = 1.0, units = ":1", digits = 0)
    private val fireIndex = Style(Kind.Blended, Palettes.rainbow(0.0, 60.0), units = "", digits = 0)
    private val hours = Style(Kind.Blended, Palettes.rainbow(0.0, 12.0), hideBelow = 0.5, units = "h", digits = 0)

    private fun number(d: Double) = if (d == kotlin.math.floor(d)) d.toInt().toString() else d.toString()
    private fun percentile(p: Int) = when (p) { 10 -> "10th percentile (low end)"; 50 -> "median"; else -> "90th percentile (high end)" }
    private val levels = listOf(10, 50, 90)
    private fun spread(title: String, style: Style, id: (Int) -> String) = levels.map { NbmProduct(id(it), "$title, ${percentile(it)}", SPREAD, style) }

    /** The 10th, 50th and 90th percentile fields (the files have 15 levels of each) and the threshold chances (the files' ids carry the threshold). */
    private val generated: List<NbmProduct> = buildList {
        addAll(spread("Apparent temperature", temperature) { "appttempp$it" })
        addAll(spread("Wind gust", wind) { "wgustp$it" })
        addAll(spread("Wind speed", wind) { "wspdp$it" })
        addAll(spread("Wind speed, 24 h max", wind) { "wspd24maxp$it" })
        addAll(spread("Wind gust, 24 h max", wind) { "wgust24maxp$it" })
        addAll(spread("Relative humidity", humidity) { "rhp$it" })
        addAll(spread("Precipitable water", pwat) { "pwat$it" })
        addAll(spread("Surface-based CAPE", cape) { "sbcapep$it" })
        for (h in listOf(1, 6, 24, 48, 72)) addAll(spread("Snowfall, $h h", snow) { "snow${h.toString().padStart(2, '0')}p$it" })
        for (h in listOf(6, 24, 48, 72)) addAll(spread("Ice accretion, $h h", iceAmount) { "ice${h.toString().padStart(2, '0')}p$it" })
        // chances of reaching a threshold: the id's digits are the threshold (snow in tenths of an inch, ice in hundredths)
        fun chances(stem: String, steps: List<String>, title: (String) -> String) { for (t in steps) add(NbmProduct(stem + t, title(t), PROBABILITY, probability)) }
        chances("probwspdgt", listOf("07", "11", "17", "22", "30", "34", "48", "64")) { "Chance of wind ${it.toInt()} kt or more" }
        chances("probwgustgt", listOf("17", "22", "30", "34", "41", "48", "56", "64")) { "Chance of gusts of ${it.toInt()} kt or more" }
        chances("probwspd24maxgt", listOf("11", "17", "22", "34", "48", "64")) { "Chance of wind ${it.toInt()} kt or more, 24 h" }
        chances("probwgust24maxgt", listOf("22", "34", "41", "48", "56", "64")) { "Chance of gusts of ${it.toInt()} kt or more, 24 h" }
        chances("probappttemp", listOf("gte090", "gte095", "gte100", "gte105", "gte110", "gte120")) { "Chance of feels-like ${it.removePrefix("gte").toInt()} F or more" }
        chances("probappttemp", listOf("lte000", "ltem010", "ltem020")) {
            "Chance of feels-like " + (if (it.startsWith("ltem")) "-" else "") + it.dropWhile { c -> c.isLetter() }.toInt() + " F or less"
        }
        val snowSteps = mapOf(
            1 to listOf("001", "010", "015", "020", "025", "030", "040"), 6 to listOf("001", "010", "020", "040", "060", "080", "100", "120"),
            24 to listOf("001", "010", "020", "040", "060", "080", "100", "120", "180", "240"),
            48 to listOf("010", "020", "040", "060", "080", "120", "180", "240", "300", "360"),
            72 to listOf("010", "040", "060", "080", "120", "180", "240", "300", "360", "480"),
        )
        for ((h, steps) in snowSteps) chances("probasnow${h.toString().padStart(2, '0')}gt", steps) { "Chance of ${number(it.toInt() / 10.0)} in of snow in $h h" }
        for (h in listOf(24, 48, 72)) chances("calprobasnow${h}gt", snowSteps.getValue(h)) { "Chance of ${number(it.toInt() / 10.0)} in of snow in $h h (calibrated)" }
        for (h in listOf(6, 24, 48, 72)) chances("probficeac${h.toString().padStart(2, '0')}gt", listOf("001", "010", "025", "050", "100")) { "Chance of ${number(it.toInt() / 100.0)} in of ice in $h h" }
        chances("cigplt", listOf("500", "1000", "2000")) { "Chance of a ceiling under $it ft" }
        chances("cigplte", listOf("3000", "6500")) { "Chance of a ceiling of $it ft or less" }
        chances("visplt", listOf("1", "2", "3")) { "Chance of visibility under $it mi" }
        chances("visplte", listOf("5")) { "Chance of visibility of $it mi or less" }
    }

    /** Fields the v5.1 files add: shorter and longer amounts, precipitation types, more storm and surface fields. */
    private val added: List<NbmProduct> = listOf(
        NbmProduct("windspd30", "Wind speed, 30 m above ground", WIND, wind),
        NbmProduct("windspd80", "Wind speed, 80 m above ground", WIND, wind),
        NbmProduct("trwspd", "Transport wind speed", WIND, wind),
        NbmProduct("pop01", "Chance of precipitation, 1 h", PRECIP, probability),
        NbmProduct("qpf01", "Rainfall, 1 h", PRECIP, rain),
        NbmProduct("snowamt01", "Snowfall, 1 h", PRECIP, snow),
        NbmProduct("snowamt24", "Snowfall, 24 h", PRECIP, snow),
        NbmProduct("snowamt48", "Snowfall, 48 h", PRECIP, snow),
        NbmProduct("snowamt72", "Snowfall, 72 h", PRECIP, snow),
        NbmProduct("snowdepth", "Snow depth", PRECIP, snow),
        NbmProduct("snowlr", "Snow-to-liquid ratio", PRECIP, snowRatio),
        NbmProduct("snowlvl", "Snow level", PRECIP, snowLevel),
        NbmProduct("ficeac01", "Ice accretion, 1 h", PRECIP, iceAmount),
        NbmProduct("ficeac06", "Ice accretion, 6 h", PRECIP, iceAmount),
        NbmProduct("ficeac24", "Ice accretion, 24 h", PRECIP, iceAmount),
        NbmProduct("ficeac48", "Ice accretion, 48 h", PRECIP, iceAmount),
        NbmProduct("ficeac72", "Ice accretion, 72 h", PRECIP, iceAmount),
        NbmProduct("prbrain", "Chance the precipitation is rain", PRECIP, probability),
        NbmProduct("prbsnow", "Chance the precipitation is snow", PRECIP, probability),
        NbmProduct("prbsleet", "Chance the precipitation is sleet", PRECIP, probability),
        NbmProduct("prbfzrain", "Chance the precipitation is freezing rain", PRECIP, probability),
        NbmProduct("precipduration", "Hours of precipitation", PRECIP, hours),
        NbmProduct("maxrh", "Maximum relative humidity (daily)", SKY, humidity),
        NbmProduct("minrh", "Minimum relative humidity (daily)", SKY, humidity),
        NbmProduct("dswrf", "Downward solar radiation", SKY, solar),
        NbmProduct("mixhgt", "Mixing height", SKY, mixingHeight),
        NbmProduct("tstm01", "Chance of thunderstorms, 1 h", STORMS, probability),
        NbmProduct("tstm03", "Chance of thunderstorms, 3 h", STORMS, probability),
        NbmProduct("tstm06", "Chance of thunderstorms, 6 h", STORMS, probability),
        NbmProduct("maxref", "Maximum radar reflectivity", STORMS, reflectivity),
        NbmProduct("veril", "Vertically integrated liquid", STORMS, vil),
        NbmProduct("fosindx", "Fire weather index (Fosberg)", OTHER, fireIndex),
    )

    val all: List<NbmProduct> = listOf(
        NbmProduct("temp", "Temperature", TEMPERATURE, temperature),
        NbmProduct("appttemp", "Apparent temperature", TEMPERATURE, temperature),
        NbmProduct("dewpoint", "Dewpoint", TEMPERATURE, dewpoint),
        NbmProduct("maxt", "Maximum temperature (daily)", TEMPERATURE, temperature),
        NbmProduct("mint", "Minimum temperature (daily)", TEMPERATURE, temperature),
        NbmProduct("tempp10", "Temperature, 10th percentile (cool end)", TEMPERATURE, temperature),
        NbmProduct("tempp90", "Temperature, 90th percentile (warm end)", TEMPERATURE, temperature),

        NbmProduct("windspd", "Wind speed", WIND, wind),
        NbmProduct("windgust", "Wind gust", WIND, wind),

        NbmProduct("pop06", "Chance of precipitation, 6 h", PRECIP, probability),
        NbmProduct("pop12", "Chance of precipitation, 12 h", PRECIP, probability),
        NbmProduct("qpf06", "Rainfall, 6 h", PRECIP, rain),
        NbmProduct("qpf06total", "Rainfall, total so far", PRECIP, rain),
        NbmProduct("qpf06p90", "Rainfall, 6 h, 90th percentile (wet end)", PRECIP, rain),
        NbmProduct("snowamt06", "Snowfall, 6 h", PRECIP, snow),
        NbmProduct("snowamt06total", "Snowfall, total so far", PRECIP, snow),

        NbmProduct("sky", "Cloud cover", SKY, cloud),
        NbmProduct("rh", "Relative humidity", SKY, humidity),
        NbmProduct("pwat", "Precipitable water", SKY, pwat),
        NbmProduct("vis", "Visibility", SKY, visibility),
        NbmProduct("ceil", "Cloud ceiling", SKY, ceiling),

        NbmProduct("sbcape", "Surface-based CAPE", STORMS, cape),
        NbmProduct("tstm12", "Chance of thunderstorms, 12 h", STORMS, probability),

        NbmProduct("probtempgt090", "Chance of 90 F or more", PROBABILITY, probability),
        NbmProduct("probtemplt032", "Chance of 32 F or less", PROBABILITY, probability),
        NbmProduct("probwgustgt34", "Chance of gusts of 34 kt or more", PROBABILITY, probability),
        NbmProduct("probqpf24gt100", "Chance of 1 in of rain in 24 h", PROBABILITY, probability),
        NbmProduct("probasnow06gt040", "Chance of 4 in of snow in 6 h", PROBABILITY, probability),
    ).let { core -> core + (added + generated).filter { p -> core.none { it.id == p.id } }.distinctBy { it.id } }

    val default = all.first()

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun byPickerId(id: String?) = all.firstOrNull { it.pickerId == id }
}
