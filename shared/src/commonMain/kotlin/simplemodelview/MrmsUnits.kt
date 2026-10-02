package simplemodelview

import kotlin.math.abs
import kotlin.math.round

/** What a product measures, so its unit can be switched: lengths of rain / hail (in or mm), heights (kft or km), temperatures (F or C). */
enum class Quantity { None, Length, Height, Temperature }

/** The units the MRMS page shows. The catalogue's own styles are in US units (inches, kft, F); [metricLength] / [kilometres] / [celsius] switch them. */
data class Units(val metricLength: Boolean = false, val celsius: Boolean = false, val kilometres: Boolean = false)

/** A value rounded to something readable on a legend: 2 decimals under 1, 1 decimal under 10, whole numbers above (always whole numbers when [integers]). */
internal fun niceValue(v: Double, integers: Boolean = false): Double = when {
    integers || abs(v) >= 10 -> round(v)
    abs(v) >= 1 -> round(v * 10) / 10
    else -> round(v * 100) / 100
}

/** The style converted by shown' = [a] * shown + [b]: the stops, the stored-to-shown factor and offset, and the labels follow. */
private fun Style.converted(a: Double, b: Double, newUnits: String, newDigits: Int, integers: Boolean): Style {
    val moved = stops.map { Stop(a * it.value + b, it.rgb) }
    val rounded = moved.map { Stop(niceValue(it.value, integers), it.rgb) }
    // rounding must keep the thresholds apart; if it would not, keep the exact ones
    val usable = if (rounded.zipWithNext().all { (p, q) -> p.value < q.value }) rounded else moved
    return copy(
        stops = usable, factor = factor * a, offset = offset * a + b,
        hideBelow = if (hideBelow < -1e20) hideBelow else a * hideBelow + b, units = newUnits, digits = newDigits,
    )
}

/** The product's style in the chosen units (the catalogue's own style when nothing needs converting). */
fun MrmsProduct.styleIn(units: Units): Style = when {
    quantity == Quantity.Length && units.metricLength -> style.converted(25.4, 0.0, style.units.replace("in", "mm"), 1, false)
    quantity == Quantity.Height && units.kilometres -> style.converted(0.3048, 0.0, style.units.replace("kft", "km"), 2, false)
    quantity == Quantity.Temperature && units.celsius -> style.converted(1.0 / 1.8, -32.0 / 1.8, style.units.replace("F", "C"), 0, true)
    else -> style
}
