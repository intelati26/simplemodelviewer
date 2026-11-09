package simplemodelview

/**
 * A National Blend of Models field: the bucket's folder name, a title and how it is drawn. A field belongs to a [family] (the product the picker lists): the
 * fields of a family differ only in time window ([a]), percentile or threshold ([b]), which the page offers as drop lists instead of separate products.
 */
data class NbmProduct(
    val id: String, val title: String, val group: String, val style: Style,
    val family: String = id, val familyTitle: String = title, val a: String = "", val b: String = "",
) {
    /** The id as the picker's product type has it (the bucket's folder names are short and could clash with the REFS arrays'). */
    val pickerId: String get() = "nbm_$id"
}

/** A product of the picker: its fields (variants), the first being the one shown when it is picked. */
class NbmFamily(val id: String, val title: String, val group: String, val variants: List<NbmProduct>) {
    val first: NbmProduct get() = variants.first()
    /** The values of the first drop list (time window, hour or 24 h maximum, ...), in order. */
    val firstChoices: List<String> get() = variants.map { it.a }.distinct()
    /** The values of the second drop list (percentile, threshold) that go with [a], in order. */
    fun secondChoices(a: String): List<String> = variants.filter { it.a == a }.map { it.b }
    /** The variant for the choices, or the closest one: the same second choice under another first one when there is one, else the first under that. */
    fun variant(a: String, b: String): NbmProduct = variants.firstOrNull { it.a == a && it.b == b } ?: variants.firstOrNull { it.a == a } ?: first
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
    const val OTHER = "Fire and more"

    val groups = listOf(TEMPERATURE, WIND, PRECIP, SKY, STORMS, PROBABILITY, OTHER)

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
    private fun two(h: Int) = h.toString().padStart(2, '0')

    const val BLEND = "Blend"
    private const val LOW = "10th percentile (low end)"
    private const val MEDIAN = "Median"
    private const val HIGH = "90th percentile (high end)"

    /** Collects the products of one family; [add] is the field id with its first and second choice. */
    private class FamilyBuilder(val id: String, val title: String, val group: String, val style: Style) {
        val fields = ArrayList<NbmProduct>()
        fun add(field: String, a: String = "", b: String = "") {
            fields += NbmProduct(field, title + listOf(a, b).filter { it.isNotEmpty() }.joinToString("") { ", $it" }, group, style, id, title, a, b)
        }
        /** The blend's own field and its 10th, 50th and 90th percentiles. */
        fun spread(blend: String?, a: String = "", percentile: (Int) -> String) {
            if (blend != null) add(blend, a, BLEND)
            add(percentile(10), a, LOW); add(percentile(50), a, MEDIAN); add(percentile(90), a, HIGH)
        }
        fun build() = NbmFamily(id, title, group, fields)
    }

    private fun family(id: String, title: String, group: String, style: Style, body: FamilyBuilder.() -> Unit) = FamilyBuilder(id, title, group, style).apply(body).build()

    /** The picker's products. Where the files have several time windows, percentiles or thresholds of one quantity they are choices of one product. */
    val families: List<NbmFamily> = listOf(
        family("temp", "Temperature", TEMPERATURE, temperature) { add("temp", b = BLEND); add("tempp10", b = LOW); add("tempp90", b = HIGH) },
        family("appttemp", "Apparent temperature", TEMPERATURE, temperature) { spread("appttemp") { "appttempp$it" } },
        family("dewpoint", "Dewpoint", TEMPERATURE, dewpoint) { add("dewpoint") },
        family("maxt", "Maximum temperature (daily)", TEMPERATURE, temperature) { add("maxt") },
        family("mint", "Minimum temperature (daily)", TEMPERATURE, temperature) { add("mint") },

        family("windspd", "Wind speed", WIND, wind) { spread("windspd") { "wspdp$it" } },
        family("windgust", "Wind gust", WIND, wind) { spread("windgust") { "wgustp$it" } },
        family("wspd24max", "Wind speed, 24 h maximum", WIND, wind) { spread(null) { "wspd24maxp$it" } },
        family("wgust24max", "Wind gust, 24 h maximum", WIND, wind) { spread(null) { "wgust24maxp$it" } },
        family("windheight", "Wind speed at a height", WIND, wind) { add("windspd30", "30 m"); add("windspd80", "80 m") },
        family("trwspd", "Transport wind speed", WIND, wind) { add("trwspd") },

        family("qpf", "Rainfall", PRECIP, rain) {
            add("qpf01", "1 h", BLEND); add("qpf06", "6 h", BLEND); add("qpf06p90", "6 h", HIGH); add("qpf06total", "Total so far", BLEND)
        },
        family("pop", "Chance of precipitation", PRECIP, probability) { add("pop01", "1 h"); add("pop06", "6 h"); add("pop12", "12 h") },
        family("snowamt", "Snowfall", PRECIP, snow) {
            for (h in listOf(1, 6, 24, 48, 72)) { add("snowamt${two(h)}", "$h h", BLEND); add("snow${two(h)}p10", "$h h", LOW); add("snow${two(h)}p50", "$h h", MEDIAN); add("snow${two(h)}p90", "$h h", HIGH) }
            add("snowamt06total", "Total so far", BLEND)
        },
        family("ficeac", "Ice accretion", PRECIP, iceAmount) {
            add("ficeac01", "1 h", BLEND)
            for (h in listOf(6, 24, 48, 72)) { add("ficeac${two(h)}", "$h h", BLEND); add("ice${two(h)}p10", "$h h", LOW); add("ice${two(h)}p50", "$h h", MEDIAN); add("ice${two(h)}p90", "$h h", HIGH) }
        },
        family("ptype", "Precipitation type", PRECIP, probability) { add("prbrain", "Chance of rain"); add("prbsnow", "Chance of snow"); add("prbsleet", "Chance of sleet"); add("prbfzrain", "Chance of freezing rain") },
        family("snowdepth", "Snow depth", PRECIP, snow) { add("snowdepth") },
        family("snowlr", "Snow-to-liquid ratio", PRECIP, snowRatio) { add("snowlr") },
        family("snowlvl", "Snow level", PRECIP, snowLevel) { add("snowlvl") },
        family("precipduration", "Hours of precipitation", PRECIP, hours) { add("precipduration") },

        family("sky", "Cloud cover", SKY, cloud) { add("sky") },
        family("rh", "Relative humidity", SKY, humidity) { spread("rh", "Hourly") { "rhp$it" }; add("maxrh", "Daily maximum"); add("minrh", "Daily minimum") },
        family("pwat", "Precipitable water", SKY, pwat) { add("pwat", b = BLEND); add("pwat10", b = LOW); add("pwat50", b = MEDIAN); add("pwat90", b = HIGH) },
        family("vis", "Visibility", SKY, visibility) { add("vis") },
        family("ceil", "Cloud ceiling", SKY, ceiling) { add("ceil") },
        family("dswrf", "Downward solar radiation", SKY, solar) { add("dswrf") },
        family("mixhgt", "Mixing height", SKY, mixingHeight) { add("mixhgt") },

        family("sbcape", "Surface-based CAPE", STORMS, cape) { add("sbcape", b = BLEND); add("sbcapep10", b = LOW); add("sbcapep50", b = MEDIAN); add("sbcapep90", b = HIGH) },
        family("tstm", "Chance of thunderstorms", STORMS, probability) { add("tstm01", "1 h"); add("tstm03", "3 h"); add("tstm06", "6 h"); add("tstm12", "12 h") },
        family("maxref", "Maximum radar reflectivity", STORMS, reflectivity) { add("maxref") },
        family("veril", "Vertically integrated liquid", STORMS, vil) { add("veril") },

        family("probwspd", "Chance of wind speed", PROBABILITY, probability) {
            for (t in listOf("07", "11", "17", "22", "30", "34", "48", "64")) add("probwspdgt$t", "Any hour", "${t.toInt()} kt or more")
            for (t in listOf("11", "17", "22", "34", "48", "64")) add("probwspd24maxgt$t", "24 h maximum", "${t.toInt()} kt or more")
        },
        family("probwgust", "Chance of gusts", PROBABILITY, probability) {
            for (t in listOf("17", "22", "30", "34", "41", "48", "56", "64")) add("probwgustgt$t", "Any hour", "${t.toInt()} kt or more")
            for (t in listOf("22", "34", "41", "48", "56", "64")) add("probwgust24maxgt$t", "24 h maximum", "${t.toInt()} kt or more")
        },
        family("probtemp", "Chance of a temperature", PROBABILITY, probability) { add("probtempgt090", b = "90 F or more"); add("probtemplt032", b = "32 F or less") },
        family("probappttemp", "Chance of a feels-like temperature", PROBABILITY, probability) {
            for (t in listOf("090", "095", "100", "105", "110", "120")) add("probappttempgte$t", b = "${t.toInt()} F or more")
            add("probappttemplte000", b = "0 F or less"); add("probappttempltem010", b = "-10 F or less"); add("probappttempltem020", b = "-20 F or less")
        },
        family("probqpf", "Chance of rain amount", PROBABILITY, probability) { add("probqpf24gt100", "24 h", "1 in or more") },
        family("probasnow", "Chance of snowfall", PROBABILITY, probability) {
            val steps = mapOf(
                1 to listOf("001", "010", "015", "020", "025", "030", "040"), 6 to listOf("001", "010", "020", "040", "060", "080", "100", "120"),
                24 to listOf("001", "010", "020", "040", "060", "080", "100", "120", "180", "240"),
                48 to listOf("010", "020", "040", "060", "080", "120", "180", "240", "300", "360"),
                72 to listOf("010", "040", "060", "080", "120", "180", "240", "300", "360", "480"),
            )
            for ((h, list) in steps) for (t in list) add("probasnow${two(h)}gt$t", "$h h", "${number(t.toInt() / 10.0)} in or more")
            for (h in listOf(24, 48, 72)) for (t in steps.getValue(h)) add("calprobasnow${h}gt$t", "$h h, calibrated", "${number(t.toInt() / 10.0)} in or more")
        },
        family("probficeac", "Chance of ice accretion", PROBABILITY, probability) {
            for (h in listOf(6, 24, 48, 72)) for (t in listOf("001", "010", "025", "050", "100")) add("probficeac${two(h)}gt$t", "$h h", "${number(t.toInt() / 100.0)} in or more")
        },
        family("cigplt", "Chance of a low ceiling", PROBABILITY, probability) {
            for (t in listOf("500", "1000", "2000")) add("cigplt$t", b = "under $t ft")
            for (t in listOf("3000", "6500")) add("cigplte$t", b = "$t ft or less")
        },
        family("visplt", "Chance of low visibility", PROBABILITY, probability) {
            for (t in listOf("1", "2", "3")) add("visplt$t", b = "under $t mi")
            add("visplte5", b = "5 mi or less")
        },

        family("fosindx", "Fire weather index (Fosberg)", OTHER, fireIndex) { add("fosindx") },
    )

    /** Every field of every product (the bucket's folder names are unique). */
    val all: List<NbmProduct> = families.flatMap { it.variants }

    val default = all.first()

    fun byId(id: String?) = all.firstOrNull { it.id == id }
    fun byPickerId(id: String?) = all.firstOrNull { it.pickerId == id }
    fun familyOf(product: NbmProduct) = families.first { it.id == product.family }
}
