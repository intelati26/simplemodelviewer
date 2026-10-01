package spcrefs

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Fixed-decimal text without platform formatting ("42.50"). */
fun fixed(value: Double, digits: Int): String {
    if (digits <= 0) return value.roundToLong().toString()
    val scale = 10.0.pow(digits)
    val scaled = (abs(value) * scale).roundToLong()
    val whole = scaled / scale.toLong()
    val frac = (scaled % scale.toLong()).toString().padStart(digits, '0')
    return (if (value < 0 && scaled != 0L) "-" else "") + "$whole.$frac"
}

/** The value of a cell as shown (stored * factor + offset); NaN when there is none. */
fun shownValue(style: Style, values: FloatArray, column: Int, row: Int, grid: Grid = Grid.REFS): Double {
    if (column !in 0 until grid.columns || row !in 0 until grid.rows || values.size < grid.cells) return Double.NaN
    val v = values[row * grid.columns + column]
    return if (v.isNaN()) Double.NaN else v * style.factor + style.offset
}

/** Text for the read-out at a grid cell: "39.10N 97.20W   42.5 dBZ", or "6 of 10 members: ..." for paintball products. */
fun readout(core: Core, product: Product, values: FloatArray, members: List<String>, column: Int, row: Int): String {
    val grid = product.grid
    if (column !in 0 until grid.columns || row !in 0 until grid.rows || values.size < grid.cells) return ""
    val (lat, lon) = core.gridToLatLon(grid, column + 0.5, row + 0.5)
    val where = "${fixed(abs(lat), 2)}${if (lat >= 0) "N" else "S"} ${fixed(abs(lon), 2)}${if (lon < 0) "W" else "E"}   "
    val raw = values[row * grid.columns + column]
    if (raw.isNaN()) return where + "no data"
    val style = product.style
    if (style.kind == Kind.Paintball) {
        val mask = raw.toInt()
        val names = (0 until 10).filter { mask and (1 shl it) != 0 }.mapNotNull { members.getOrNull(it)?.let(Palettes::memberDisplayName) }
        val count = (0 until 10).count { mask and (1 shl it) != 0 }
        return where + "$count of 10 members" + if (names.isEmpty()) "" else ": " + names.joinToString(", ")
    }
    val shown = raw * style.factor + style.offset
    return where + fixed(shown, style.digits) + if (style.units.isEmpty()) "" else " " + style.units
}

/** Products with no colour table get a rainbow spanning the picture's own range. */
fun withAutoStops(style: Style, values: FloatArray): Style {
    if (style.kind == Kind.Paintball || style.stops.isNotEmpty()) return style
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (v in values) {
        if (v.isNaN()) continue
        val shown = v * style.factor + style.offset
        if (shown < lo) lo = shown
        if (shown > hi) hi = shown
    }
    if (lo > hi) return style
    if (hi - lo < 1e-9) hi = lo + 1.0
    return style.copy(stops = Palettes.rainbow(lo, hi))
}
