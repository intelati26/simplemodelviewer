package simplemodelview

/** How a field is drawn; ordinals match the core's SPC_BLENDED / SPC_BANDED / SPC_PAINTBALL / SPC_CATEGORICAL. */
enum class Kind { Blended, Banded, Paintball, Categorical }

/** Colours, units and the stored-to-shown conversion (shown = stored * factor + offset). */
data class Style(
    val kind: Kind = Kind.Blended,
    val stops: List<Stop> = emptyList(),
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val hideBelow: Double = -1e30,
    val units: String = "",
    val digits: Int = 1,
    /** Categorical fields: the name of each stop's code (parallel to [stops]). */
    val labels: List<String> = emptyList(),
)

/** An SPC REFS product: an array in the store plus how to show it. */
data class Product(
    val id: String,          // array name in the Zarr store
    val title: String,
    val group: String,
    val style: Style,
    val hasMembers: Boolean,       // 4-D (member, time, y, x): one member at a time
    val timeDimension: String?,    // key of the time coordinate array (prefixed like the product's own for secondary stores)
    val grid: Grid = Grid.REFS,
)

/** The buckets of SPC's own REFS viewer menu, in its order (the viewer's "Overlays" and "Member Viewer" aside from the last, which holds single-member fields). */
object Groups {
    const val GUIDANCE = "Guidance"
    const val SYNOPTIC = "Synoptic"
    const val SEVERE = "Severe"
    const val WINTER = "Winter"
    const val FIRE = "Fire"
    const val PRECIP = "Precipitation"
    const val STORM = "Storm Attributes"
    const val MEMBERS = "Member Viewer"
    const val OTHER = "Other fields"
    val ordered = listOf(GUIDANCE, SYNOPTIC, SEVERE, WINTER, FIRE, PRECIP, STORM, MEMBERS, OTHER)

    /** The bucket a product belongs to, matching the site's menu. [hasMembers]: a single-member (4-D) field. */
    fun of(id: String, hasMembers: Boolean): String = when {
        id.startsWith("grefs-") || id.startsWith("refsct-") -> GUIDANCE
        hasMembers -> MEMBERS
        id.startsWith("s10m_01h") || id.startsWith("rh2m") || id.startsWith("fosberg") || id.startsWith("gust") -> FIRE
        listOf("z500", "z700", "z850", "t500", "t700", "t850", "t2m", "td2m", "pmsl", "cld_", "pwat").any { id.startsWith(it) } -> SYNOPTIC
        listOf("sbcape", "mucape", "sbcinh", "srh", "shrmag", "stp").any { id.startsWith(it) } -> SEVERE
        id.startsWith("snow") || id.startsWith("frzr") -> WINTER
        id.startsWith("qpf") -> PRECIP
        listOf("cref", "maxref", "uh25", "uh03", "wmax", "s10m").any { id.startsWith(it) } -> STORM
        else -> OTHER
    }
}

object Catalogue {
    /** GREFS: SPC's calibrated 4-h and 24-h probabilities of tornadoes, damaging wind and severe hail (a coarse 40.6 km grid), and REFSCT thunder probability. */
    private val grefsList = listOf(
        "grefs-4hr/tor_prob" to "4-h Tornado Probability (GREFS)",
        "grefs-4hr/wind_prob" to "4-h Damaging Wind Probability (GREFS)",
        "grefs-4hr/hail_prob" to "4-h Severe Hail Probability (GREFS)",
        "grefs-24hr/tor_prob" to "24-h Tornado Probability (GREFS)",
        "grefs-24hr/wind_prob" to "24-h Damaging Wind Probability (GREFS)",
        "grefs-24hr/hail_prob" to "24-h Severe Hail Probability (GREFS)",
        // REFSCT: thunder probability, the viewer's "Guidance" menu has these three (there is no lightning-flash product in the data)
        "refsct-1hr/thunder_prob" to "1-h Thunder Probability (REFSCT)",
        "refsct-4hr/thunder_prob" to "4-h Thunder Probability (REFSCT)",
        "refsct-full/thunder_prob" to "Remainder of Convective Day Thunder Probability (REFSCT)",
    )
    private val allCurated = curatedList + grefsList
    private val curated = allCurated.toMap()
    private val curatedOrder = allCurated.mapIndexed { i, t -> t.first to i }.toMap()

    /** Colours and units from the array's name and its `units` attribute (ported from the desktop viewer). */
    fun styleFor(id: String, units: String): Style {
        if (id.startsWith("refsct-")) {
            // already in percent (0-100), unlike GREFS's fractions
            return Style(Kind.Banded, bands(listOf(10, 20, 30, 40, 50, 60, 70, 80, 90),
                listOf(0xfef0a8, 0xfed976, 0xfeb24c, 0xfd8d3c, 0xfc4e2a, 0xe31a1c, 0xbd0026, 0x800026, 0x4d004b)), hideBelow = 10.0, units = "%", digits = 0)
        }
        if (id.startsWith("grefs-")) {
            // stored in percent already (wind peaks ~17, tornado ~3; verified on real chunks), shown with the colours of SPC's convective outlook probabilities
            val tornado = id.endsWith("tor_prob")
            val stops = if (tornado) listOf(2.0 to 0x008B00, 5.0 to 0x8B4726, 10.0 to 0xFFC800, 15.0 to 0xFF0000, 30.0 to 0xFF00FF, 45.0 to 0x912CEE, 60.0 to 0x104E8B)
            else listOf(5.0 to 0x8B4726, 15.0 to 0xFFC800, 30.0 to 0xFF0000, 45.0 to 0xFF00FF, 60.0 to 0x912CEE)
            return Style(Kind.Banded, stops.map { Stop(it.first, it.second) }, factor = 1.0, hideBelow = stops.first().first, units = "%", digits = 0)
        }
        if ("_pb_" in id) return Style(Kind.Paintball, units = "members")
        if ("nmep" in id || "_prob" in id)
            return Style(Kind.Banded, Palettes.probability, factor = 100.0, hideBelow = 5.0, units = "%", digits = 0)
        val banded = Style(Kind.Banded)
        return when (units) {
            "dBz" -> banded.copy(stops = Palettes.reflectivity, units = "dBZ", hideBelow = 10.0)
            "meter ** 2 / second ** 2" ->
                if ("srh" in id) banded.copy(stops = Palettes.helicity, hideBelow = 50.0, units = "m2/s2", digits = 0)
                else banded.copy(stops = Palettes.updraftHelicity, hideBelow = 25.0, units = "m2/s2", digits = 0,
                    factor = if ("uh25min" in id) -1.0 else 1.0)   // negative (anticyclonic) helicity shown as a magnitude
            "inch" -> when {
                id.startsWith("snow") -> banded.copy(stops = Palettes.snow, hideBelow = 0.1, units = "in", digits = 2)
                id.startsWith("frzr_fram") -> banded.copy(stops = Palettes.iceAccretion, hideBelow = 0.01, units = "in", digits = 2)
                id.startsWith("frzr") -> banded.copy(stops = Palettes.freezingRain, hideBelow = 0.01, units = "in", digits = 2)
                id.startsWith("pwat") -> banded.copy(stops = Palettes.precipitableWater, hideBelow = 0.0, units = "in", digits = 2)
                else -> banded.copy(stops = Palettes.qpf, hideBelow = 0.01, units = "in", digits = 2)
            }
            "percent" ->
                if (id.startsWith("cld_")) Style(Kind.Blended, Palettes.cloud(if ("low" in id) 0x19199e else if ("mid" in id) 0x3c7d2b else 0x8c2721),
                    hideBelow = 10.0, units = "%", digits = 0)
                else banded.copy(stops = Palettes.relativeHumidity, units = "%", digits = 0)
            "degree_Fahrenheit" -> Style(Kind.Blended, Palettes.temperature(true), units = "F")
            "degree_Celsius" -> Style(Kind.Blended, Palettes.temperature(false), units = "C")
            "knot" ->
                if (id.length > 1 && (id[0] == 'u' || id[0] == 'v') && ("_mean" in id || "shr" in id))
                    Style(Kind.Blended, Palettes.component, units = "kt")
                else banded.copy(stops = Palettes.windSpeed(1.0), hideBelow = 10.0, units = "kt")
            "mile / hour" -> banded.copy(stops = Palettes.windSpeed(1.15), hideBelow = 11.5, units = "mph")
            "meter / second" -> banded.copy(stops = Palettes.updraftSpeed, hideBelow = 10.0, units = "m/s")
            "joule / kilogram" ->
                if ("cinh" in id) banded.copy(stops = Palettes.cin, factor = -1.0, hideBelow = 25.0, units = "J/kg", digits = 0)
                else banded.copy(stops = Palettes.cape, hideBelow = 100.0, units = "J/kg", digits = 0)
            "meter" -> Style(Kind.Blended, when {
                "z500" in id -> Palettes.rainbow(5000.0, 6000.0)
                "z700" in id -> Palettes.rainbow(2700.0, 3300.0)
                else -> Palettes.rainbow(1200.0, 1650.0)
            }, units = "m", digits = 0)
            "pascal" -> Style(Kind.Blended, Palettes.rainbow(985.0, 1035.0), factor = 0.01, units = "hPa")
            "dimensionless" ->
                if ("stp" in id) banded.copy(stops = Palettes.stp, hideBelow = 0.5)
                else Style(Kind.Blended, Palettes.rainbow(0.0, 70.0))
            else -> Style(Kind.Blended, emptyList(), units = units)   // no table: the picture's own range is used
        }
    }

    fun describe(array: ArrayInfo): Product {
        val title = curated[array.name] ?: array.name.replace('_', ' ')
        val group = Groups.of(array.name, array.dims.size == 4)
        return Product(array.name, title, group, styleFor(array.name, array.units), array.dims.size == 4,
            if (array.dims.size >= 3) array.kind.prefix + array.dims[array.dims.size - 3] else null, array.grid ?: Grid.REFS)
    }

    /** Every displayable field of a store (3-D / 4-D on the CONUS grid), in menu order: groups, then SPC's order within a group. */
    fun build(arrays: Collection<ArrayInfo>): List<Product> =
        arrays.filter { it.isGridField && it.name != "ptype_consensus" }   // (precipitation-type codes have no documented key)
            .map(::describe)
            .sortedWith(compareBy<Product>({ Groups.ordered.indexOf(it.group) }, { curatedOrder[it.id] ?: Int.MAX_VALUE }, { it.id }))
}
