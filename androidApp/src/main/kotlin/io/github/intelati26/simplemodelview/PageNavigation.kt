package io.github.intelati26.simplemodelview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import simplemodelview.Core
import simplemodelview.Place
import simplemodelview.Product
import simplemodelview.Sector
import simplemodelview.View
import simplemodelview.placeLabel
import simplemodelview.readout

/** A page's shown value at a place as a line of text (without moving the map or the crosshair), or where the place lies outside the picture. */
internal fun readAtPlace(
    core: Core, product: Product, values: FloatArray, members: List<String>, lat: Double, lon: Double,
    outside: String = "outside the area shown", trueFrameWinds: Boolean = false,
): String {
    val grid = product.grid
    val (c, r) = core.latLonToGrid(grid, lat, lon)
    val column = Math.round(c).toInt(); val row = Math.round(r).toInt()
    if (column !in 0 until grid.columns || row !in 0 until grid.rows) return "${placeLabel(lat, lon)}   $outside"
    return readout(core, product, values, members, column, row, trueFrameWinds)
}

/**
 * A page puts its crosshair on a saved [place]. On the globe it only turns the globe to it ([turnGlobeTo]). On the flat map, a place outside the
 * [sector] shown opens [openSector] first (nothing when [follow]: a live fix only moves the crosshair within the picture); then [tap] gets the place as
 * fractions of the picture ([view] is read after the sector has opened).
 */
internal fun CoroutineScope.goToPlaceOnPage(
    core: Core, place: Place, follow: Boolean, globe: Boolean, sector: Sector, wholeSector: Sector,
    turnGlobeTo: () -> Unit, openSector: suspend () -> Unit, view: () -> View?, tap: (Float, Float) -> Unit,
) {
    if (globe) { turnGlobeTo(); return }
    val inSector = place.lon in sector.west..sector.east && place.lat in sector.south..sector.north
    if (follow && !inSector) return
    launch {
        if (!inSector && sector.name != wholeSector.name) {
            try { openSector() } catch (e: CancellationException) { throw e } catch (e: Exception) { return@launch }
        }
        val v = view() ?: return@launch
        val (c, r) = core.latLonToGrid(v.grid, place.lat, place.lon)
        tap(((c + 0.5 - v.col0) / v.cols).toFloat(), ((v.row0 + v.rows - r - 0.5) / v.rows).toFloat())
    }
}

/** The favorites with [id] added or removed, saved (every page shares the one list). */
fun toggleFavorite(prefs: android.content.SharedPreferences, current: Set<String>, id: String): Set<String> {
    val favorites = if (id in current) current - id else current + id
    prefs.edit().putStringSet("favorites", favorites).apply()
    return favorites
}

/** The compare's second picture: its own field, the shared lines and alerts over it, its title and style. */
class SecondPane(val field: android.graphics.Bitmap, val over: android.graphics.Bitmap, val title: String, val style: simplemodelview.Style)

/** Thrown by a second-picture builder when the other product has nothing for this time: the text is shown as the notice. */
class NoSecondPane(message: String) : Exception(message)

/**
 * The part every page's second picture shares: nothing unless a compare is on and not playing (an old picture is cleared), else [build] draws it;
 * [NoSecondPane] or any failure clears it with a notice. [apply] puts the result (or null and the notice) into the page's state.
 */
suspend fun showSecondPane(wanted: Boolean, hasPane: Boolean, apply: (SecondPane?, String?) -> Unit, build: suspend () -> SecondPane) {
    if (!wanted) { if (hasPane) apply(null, null); return }
    try { apply(build(), null) } catch (e: CancellationException) { throw e } catch (e: Exception) { apply(null, e.message) }
}
