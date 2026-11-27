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
