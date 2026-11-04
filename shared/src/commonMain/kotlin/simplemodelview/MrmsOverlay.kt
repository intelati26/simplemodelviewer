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
    const val ROTATION_LIGHTNING = "rotation-lightning"

    /** Strikes by recency, a cross each in the color of its window (1 / 5 / 15 / 30 min); the older windows are drawn first and a little fainter. */
    val lightningNest = listOf(
        OverlaySpec("NLDN_CG_030min_AvgDensity_00.00", index = 0, opacity = 0.5),
        OverlaySpec("NLDN_CG_015min_AvgDensity_00.00", index = 0, opacity = 0.65),
        OverlaySpec("NLDN_CG_005min_AvgDensity_00.00", index = 0, opacity = 0.85),
        OverlaySpec("NLDN_CG_001min_AvgDensity_00.00", index = 0, opacity = 1.0),
    )

    /** The last hour's rotation tracks (0-2 km, wxqt's swath style) under the lightning nest. */
    val rotationLightning = listOf(OverlaySpec("RotationTrack60min_00.50", index = -1, opacity = 1.0)) + lightningNest

    private val spans = listOf(30, 60, 120, 240, 360, 1440)

    /** The rotation products a switch can use: tracks over 0-2 km and 3-6 km, and the shear as it is now. */
    val rotationChoices = spans.map { "RotationTrack${it}min_00.50" } + spans.map { "RotationTrackML${it}min_00.50" } +
        listOf("MergedAzShear_0-2kmAGL_00.50", "MergedAzShear_3-6kmAGL_00.50")

    /** The hail products a switch can use: swaths, the size now, the probability of severe hail and the severe hail index. */
    val hailChoices = spans.map { "MESH_Max_${it}min_00.50" } + listOf("MESH_00.50", "POSH_00.50", "SHI_00.50")

    const val DEFAULT_ROTATION = "RotationTrack60min_00.50"
    const val DEFAULT_HAIL = "MESH_Max_60min_00.50"

    /** Which products a stack of ids is made of: a hail product, a rotation product (either may be missing) and the lightning nest. */
    data class Combo(val rotation: String?, val hail: String?, val lightning: Boolean)

    /** Any mix of a hail product, a rotation product and the lightning nest, drawn in that order (the strikes on top). */
    fun combo(rotation: String?, hail: String?, lightning: Boolean): List<OverlaySpec> = buildList {
        if (hail != null) add(OverlaySpec(hail, index = -1, opacity = 1.0))
        if (rotation != null) add(OverlaySpec(rotation, index = -1, opacity = 1.0))
        if (lightning) addAll(lightningNest)
    }

    /** The mix a stack of product ids is, or null when it is something else. */
    fun comboOf(ids: List<String>): Combo? {
        if (ids.isEmpty()) return null
        var i = 0
        var hail: String? = null
        var rotation: String? = null
        if (ids[i] in hailChoices) { hail = ids[i]; i += 1 }
        if (i < ids.size && ids[i] in rotationChoices) { rotation = ids[i]; i += 1 }
        val rest = ids.drop(i)
        if (rest.isNotEmpty() && rest != lightningNest.map { it.id }) return null
        return Combo(rotation, hail, rest.isNotEmpty())
    }

    fun specs(name: String): List<OverlaySpec>? = when (name) { LIGHTNING_NEST -> lightningNest; ROTATION_LIGHTNING -> rotationLightning; else -> null }
}

/** The overlay as shown to the user: "Lightning nest" for that preset, otherwise the layers' titles. */
fun overlayName(layers: List<OverlayLayer>): String = when {
    layers.isEmpty() -> ""
    layers.map { it.product.id } == OverlayPresets.lightningNest.map { it.id } -> "Lightning nest"
    layers.size > 1 && OverlayPresets.comboOf(layers.map { it.product.id }) != null -> OverlayPresets.comboOf(layers.map { it.product.id })!!.let { (r, h, l) ->
        listOfNotNull(if (r != null) "Rotation" else null, if (h != null) "Hail" else null, if (l) "Lightning" else null).mapIndexed { i, p -> if (i == 0) p else p.lowercase() }.joinToString(" + ")
    }
    else -> layers.joinToString(" + ") { it.product.displayTitle }
}

/** Saved form of a stack: "id|level|index|opacity" per layer, joined by ";" (ids never contain either character). */
fun encodeOverlays(specs: List<OverlaySpec>): String = specs.joinToString(";") { "${it.id}|${it.level}|${it.index}|${it.opacity}" }

fun decodeOverlays(text: String?): List<OverlaySpec> = text.orEmpty().split(';').mapNotNull { part ->
    val f = part.split('|')
    if (f.size != 4 || f[0].isBlank()) return@mapNotNull null
    OverlaySpec(f[0], f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: -1, (f[3].toDoubleOrNull() ?: 1.0).coerceIn(0.05, 1.0))
}.take(6)

/** Hail swaths and rotation tracks are edged in black with a white halo outside it (wxqt's swath style), so they read over any reflectivity color. */
fun mrmsHasHalo(product: MrmsProduct) = product.group == MrmsCatalog.ROTATION || product.group == MrmsCatalog.HAIL

/** Strike density is drawn as a cross per strike (a strike is one 1 km cell: far too small to see as a cell); area fields are not. */
fun overlayIsMarker(product: MrmsProduct) = product.id.startsWith("NLDN_")

/** The color of a strike's cross by how long a window the product covers: 1 min yellow, 5 min orange, 15 min red, 30 min purple (0xRRGGBB); null for other products. */
fun overlayMarkerColor(product: MrmsProduct): Int? {
    if (!overlayIsMarker(product)) return null
    return when (Regex("""_(\d+)min_""").find(product.id)?.groupValues?.get(1)?.toIntOrNull()) {
        null -> null
        in 0..1 -> 0xFFE000
        in 2..5 -> 0xFF8A00
        in 6..15 -> 0xE02020
        else -> 0xA020C0
    }
}

/**
 * How big a strike's cross is relative to the base size: always 1 (the windows are told apart by color, see [overlayMarkerColor]).
 */
fun overlayMarkerScale(product: MrmsProduct): Float = 1f   // the crosses are all one size: the color says how old they are
