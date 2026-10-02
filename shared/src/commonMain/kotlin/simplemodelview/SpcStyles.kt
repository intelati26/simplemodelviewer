package simplemodelview

/**
 * The style of a stored variable as SPC's own REFS viewer draws it: its color table ([SpcColormaps], captured from the viewer), banded like the
 * viewer's (it looks colors up with nearest-neighbor, after the GPU has interpolated the values), with its colors below the first level and
 * above the last, and its layer opacity. [base] supplies what the viewer's table does not say (units, digits, the stored-to-shown factor).
 * A variable the viewer has no table for keeps [base].
 */
internal fun siteStyle(id: String, base: Style): Style {
    val key = id.substringAfterLast('/')                 // "grefs-4hr/tor_prob" is the viewer's "tor_prob"
    val t = SpcColormaps.tables[key] ?: return base
    val styled = base.copy(
        kind = Kind.Banded,
        stops = t.levels.dropLast(1).mapIndexed { i, level -> Stop(level, t.colors[i]) },
        hideBelow = -1e30, top = t.levels.last(), under = t.under, over = t.over, opacity = t.opacity,
    )
    return when {
        key == "s10m_01h_max_mean" -> styled.copy(factor = 1.150779, units = "mph")   // the viewer shows this one in mph (the store has knots)
        key.startsWith("uh25min") -> styled.copy(factor = 1.0)                         // the viewer shows negative helicity as negative values
        else -> styled
    }
}
