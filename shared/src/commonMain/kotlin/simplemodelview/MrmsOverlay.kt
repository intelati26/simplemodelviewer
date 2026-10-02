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

/** One overlay: a product drawn over the base, limited to values from its [thresholdIndex]-th minimum up, at [opacity] (1 = solid). */
data class OverlayLayer(val product: MrmsProduct, val thresholdIndex: Int, val opacity: Double = 1.0)

/** What is saved of an overlay layer: the product's id (and the height of a 3D mosaic field), the minimum's index (-1: the product's default) and the opacity. */
data class OverlaySpec(val id: String, val level: Int = 0, val index: Int = -1, val opacity: Double = 1.0)

/** Ready-made stacks of layers. They are drawn in order, so the last is on top. */
object OverlayPresets {
    const val LIGHTNING_NEST = "lightning-nest"

    /** Strikes by recency: the last 30 minutes as a faint shadow (20 %), the last 5 minutes at half strength, the last minute solid. */
    val lightningNest = listOf(
        OverlaySpec("NLDN_CG_030min_AvgDensity_00.00", index = 0, opacity = 0.2),
        OverlaySpec("NLDN_CG_005min_AvgDensity_00.00", index = 0, opacity = 0.5),
        OverlaySpec("NLDN_CG_001min_AvgDensity_00.00", index = 0, opacity = 1.0),
    )

    fun specs(name: String): List<OverlaySpec>? = if (name == LIGHTNING_NEST) lightningNest else null
}

/** The overlay as shown to the user: "Lightning nest" for that preset, otherwise the layers' titles. */
fun overlayName(layers: List<OverlayLayer>): String = when {
    layers.isEmpty() -> ""
    layers.map { it.product.id } == OverlayPresets.lightningNest.map { it.id } -> "Lightning nest"
    else -> layers.joinToString(" + ") { it.product.displayTitle }
}

/** Saved form of a stack: "id|level|index|opacity" per layer, joined by ";" (ids never contain either character). */
fun encodeOverlays(specs: List<OverlaySpec>): String = specs.joinToString(";") { "${it.id}|${it.level}|${it.index}|${it.opacity}" }

fun decodeOverlays(text: String?): List<OverlaySpec> = text.orEmpty().split(';').mapNotNull { part ->
    val f = part.split('|')
    if (f.size != 4 || f[0].isBlank()) return@mapNotNull null
    OverlaySpec(f[0], f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: -1, (f[3].toDoubleOrNull() ?: 1.0).coerceIn(0.05, 1.0))
}.take(6)

/** Strike density is drawn as a dot per strike (a strike is one 1 km cell: far too small to see as a cell); area fields such as the lightning probability are not. */
fun overlayIsMarker(product: MrmsProduct) = product.id.startsWith("NLDN_")

/**
 * How big a strike's dot is relative to the base size, by how long a window the product covers: 1 minute 1.0, 5 minutes about 1.7, 30 minutes about 2.5.
 * Drawn in order, the layers of the lightning nest become nested rings: a small solid core, a larger half-strength ring, a larger faint halo.
 */
fun overlayMarkerScale(product: MrmsProduct): Float {
    val minutes = Regex("""_(\d+)min_""").find(product.id)?.groupValues?.get(1)?.toIntOrNull() ?: return 1f
    return (1.0 + 0.45 * kotlin.math.ln(minutes.coerceAtLeast(1).toDouble())).toFloat()
}
