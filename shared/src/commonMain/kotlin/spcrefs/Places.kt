package spcrefs

import kotlin.math.abs
import kotlin.math.roundToInt

/** A saved place (west-negative longitude). */
data class Place(val name: String, val lat: Double, val lon: Double)

/** One place per line: "name|lat|lon". Names may not contain '|' or newlines (they are replaced). */
fun encodePlaces(places: List<Place>): String =
    places.joinToString("\n") { "${it.name.replace('|', '/').replace('\n', ' ')}|${it.lat}|${it.lon}" }

fun decodePlaces(text: String): List<Place> = text.lines().mapNotNull { line ->
    val parts = line.split('|')
    if (parts.size != 3) return@mapNotNull null
    val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
    val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
    Place(parts[0], lat, lon)
}

/** "39.10N 97.20W" */
fun placeLabel(lat: Double, lon: Double) =
    "${fixed(abs(lat), 2)}${if (lat >= 0) "N" else "S"} ${fixed(abs(lon), 2)}${if (lon < 0) "W" else "E"}"

/** The product's value at a point for every forecast time, in shown units (NaN where there is none; member count for paintball). */
class PointSeries(val times: List<Long>, val values: List<Double>, val units: String, val digits: Int) {
    val finite get() = values.filter { !it.isNaN() }
    val max get() = finite.maxOrNull()
    val peakIndex get() = values.indices.filter { !values[it].isNaN() }.maxByOrNull { values[it] }
}

/**
 * Walks every forecast time of [product] at (lat, lon). Each time is one chunk download + decode (the HTTP cache makes a
 * second pass cheap). [onProgress] gets 0..1.
 */
suspend fun RefsRepository.pointSeries(
    store: CycleStore, product: Product, memberIndex: Int, lat: Double, lon: Double, core: Core, onProgress: (Float) -> Unit = {},
): PointSeries {
    val times = validTimes(store, product)
    val (c, r) = core.latLonToGrid(lat, lon)
    val column = c.roundToInt()
    val row = r.roundToInt()
    if (column !in 0 until GRID_COLUMNS || row !in 0 until GRID_ROWS) throw RefsException("That place is outside the REFS grid")
    val style = product.style
    val out = ArrayList<Double>(times.size)
    for (t in times.indices) {
        val frame = readFrame(store, product, t, memberIndex)
        val raw = frame[row * GRID_COLUMNS + column]
        out += when {
            raw.isNaN() -> Double.NaN
            style.kind == Kind.Paintball -> (0 until 10).count { raw.toInt() and (1 shl it) != 0 }.toDouble()
            else -> raw * style.factor + style.offset
        }
        onProgress((t + 1f) / times.size)
    }
    return PointSeries(times, out, if (style.kind == Kind.Paintball) "members" else style.units, if (style.kind == Kind.Paintball) 0 else style.digits)
}
