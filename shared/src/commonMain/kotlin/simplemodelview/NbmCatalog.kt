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
    const val PROBABILITY = "Probabilities"

    val groups = listOf(TEMPERATURE, WIND, PRECIP, SKY, STORMS, PROBABILITY)

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
        NbmProduct("wgustp90", "Wind gust, 90th percentile", WIND, wind),

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
    )

    val default = all.first()

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun byPickerId(id: String?) = all.firstOrNull { it.pickerId == id }
}
