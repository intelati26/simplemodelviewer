package simplemodelview

/** How a field is drawn; ordinals match the core's SPC_BLENDED / SPC_BANDED / SPC_PAINTBALL. */
enum class Kind { Blended, Banded, Paintball }

/** Colours, units and the stored-to-shown conversion (shown = stored * factor + offset). */
data class Style(
    val kind: Kind = Kind.Blended,
    val stops: List<Stop> = emptyList(),
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val hideBelow: Double = -1e30,
    val units: String = "",
    val digits: Int = 1,
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

object Groups {
    const val SEVERE = "Severe storms"
    const val GREFS = "Calibrated severe probabilities (GREFS)"
    const val INSTABILITY = "Instability and shear"
    const val PRECIP = "Precipitation"
    const val SURFACE = "Surface and upper air"
    const val FIRE = "Fire weather"
    const val OTHER = "Other fields"
    val ordered = listOf(SEVERE, GREFS, INSTABILITY, PRECIP, SURFACE, FIRE, OTHER)
}

object Catalogue {
    /** GREFS: SPC's calibrated 4-h and 24-h probabilities of tornadoes, damaging wind and severe hail (a coarse 42 km grid). */
    private val grefsList = listOf(
        Triple("grefs-4hr/tor_prob", "4-h Tornado Probability (GREFS)", Groups.GREFS),
        Triple("grefs-4hr/wind_prob", "4-h Damaging Wind Probability (GREFS)", Groups.GREFS),
        Triple("grefs-4hr/hail_prob", "4-h Severe Hail Probability (GREFS)", Groups.GREFS),
        Triple("grefs-24hr/tor_prob", "24-h Tornado Probability (GREFS)", Groups.GREFS),
        Triple("grefs-24hr/wind_prob", "24-h Damaging Wind Probability (GREFS)", Groups.GREFS),
        Triple("grefs-24hr/hail_prob", "24-h Severe Hail Probability (GREFS)", Groups.GREFS),
    )
    private val allCurated = curatedList + grefsList
    private val curated = allCurated.associate { it.first to (it.second to it.third) }
    private val curatedOrder = allCurated.mapIndexed { i, t -> t.first to i }.toMap()

    /** Colours and units from the array's name and its `units` attribute (ported from the desktop viewer). */
    fun styleFor(id: String, units: String): Style {
        if (id.startsWith("grefs-")) {
            // stored as fractions; shown as percent with the colours of SPC's convective outlook probabilities
            val tornado = id.endsWith("tor_prob")
            val stops = if (tornado) listOf(2.0 to 0x008B00, 5.0 to 0x8B4726, 10.0 to 0xFFC800, 15.0 to 0xFF0000, 30.0 to 0xFF00FF, 45.0 to 0x912CEE, 60.0 to 0x104E8B)
            else listOf(5.0 to 0x8B4726, 15.0 to 0xFFC800, 30.0 to 0xFF0000, 45.0 to 0xFF00FF, 60.0 to 0x912CEE)
            return Style(Kind.Banded, stops.map { Stop(it.first, it.second) }, factor = 100.0, hideBelow = stops.first().first, units = "%", digits = 0)
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
        val (title, group) = curated[array.name] ?: (array.name.replace('_', ' ') to Groups.OTHER)
        return Product(array.name, title, group, styleFor(array.name, array.units), array.dims.size == 4,
            if (array.dims.size >= 3) array.kind.prefix + array.dims[array.dims.size - 3] else null, array.grid ?: Grid.REFS)
    }

    /** Every displayable field of a store (3-D / 4-D on the CONUS grid), in menu order: groups, then SPC's order within a group. */
    fun build(arrays: Collection<ArrayInfo>): List<Product> =
        arrays.filter { it.isGridField && it.name != "ptype_consensus" }   // (precipitation-type codes have no documented key)
            .map(::describe)
            .sortedWith(compareBy<Product>({ Groups.ordered.indexOf(it.group) }, { curatedOrder[it.id] ?: Int.MAX_VALUE }, { it.id }))
}
