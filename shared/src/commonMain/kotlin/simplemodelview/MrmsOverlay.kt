package simplemodelview

import kotlin.math.roundToInt

/** How far from a radar frame's valid time an overlay file may be and still be drawn on it: a scan and a half of the overlay's own cadence, at least 2.5 minutes. */
fun overlayToleranceSeconds(overlay: MrmsProduct): Long = maxOf(150L, overlay.cadenceMinutes * 90L)

/**
 * The minimum values an overlay can be limited to ("show only rotation above ..."): up to six of the style's own band edges, from the lowest to the
 * highest. Category fields have none (they are drawn whole).
 */
fun overlayThresholdChoices(style: Style): List<Double> {
    if (style.kind == Kind.Categorical || style.kind == Kind.Paintball) return emptyList()
    val v = style.stops.map { it.value }
    if (v.size <= 6) return v
    return (0 until 6).map { v[it * (v.size - 1) / 5] }.distinct()
}

/** Which of the choices an overlay starts at: strikes show from the lowest value, rotation and hail only from a high one. */
fun defaultOverlayThresholdIndex(product: MrmsProduct, choices: Int): Int {
    if (choices <= 1) return 0
    val fraction = when (product.group) {
        MrmsCatalog.LIGHTNING -> 0.0
        MrmsCatalog.ROTATION -> 0.6
        MrmsCatalog.HAIL -> 0.4
        else -> 0.4
    }
    return (fraction * (choices - 1)).roundToInt()
}

/** Overlays of sparse fields are drawn a little bolder so single cells (a strike, a thin track) stay visible. */
fun overlayIsSparse(product: MrmsProduct) = product.group == MrmsCatalog.LIGHTNING || product.group == MrmsCatalog.ROTATION || product.group == MrmsCatalog.HAIL
