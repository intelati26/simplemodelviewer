package simplemodelview

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Moving between the flat map and the globe without losing the place: the map's on-screen box becomes the globe's first view, and the
 * globe's on-screen box becomes the map's sector and zoom. Boxes are [west, south, east, north] in degrees (west-negative longitudes).
 */

/** The globe that has [box] filling a [width] x [height] screen: centered on the box, its radius as large as keeps every sampled edge point on the near side and on screen. */
fun globeFitting(box: DoubleArray, width: Int, height: Int, minRadius: Double, maxRadius: Double): GlobeCamera {
    val lat = ((box[1] + box[3]) / 2).coerceIn(-89.0, 89.0)
    val lon = wrapLon((box[0] + box[2]) / 2)
    val unit = GlobeCamera(lat, lon, 1.0)
    var maxX = 1e-9; var maxY = 1e-9
    for (i in 0..16) {
        val t = i / 16.0
        val la = box[1] + (box[3] - box[1]) * t
        val lo = box[0] + (box[2] - box[0]) * t
        for ((a, b) in listOf(box[1] to lo, box[3] to lo, la to box[0], la to box[2])) {
            val p = globeForward(unit, 0, 0, a, wrapLon(b)) ?: return GlobeCamera(lat, lon, minRadius)   // over the horizon: show as much as the globe can
            maxX = max(maxX, abs(p[0])); maxY = max(maxY, abs(p[1]))
        }
    }
    val radius = min(width / 2.0 / maxX, height / 2.0 / maxY) * 0.97
    return GlobeCamera(lat, lon, radius.coerceIn(minRadius, maxRadius))
}

/**
 * The sector the map should open on for a globe on a [width] x [height] screen: the smallest meso sector that holds the globe's center and is at
 * least as wide as the globe's view across its middle row (a tall screen shows far more north and south than a sector has, which does not count);
 * otherwise CONUS, which the map then zooms into.
 */
fun sectorForGlobe(cam: GlobeCamera, width: Int, height: Int, sectors: List<Sector> = Sector.all): Sector {
    val conus = sectors.first { it.name == "CONUS" }
    val left = globeInverse(cam, width, height, 0.0, height / 2.0)
    val right = globeInverse(cam, width, height, width.toDouble(), height / 2.0)
    if (left == null || right == null) return conus                                 // the sphere's edge is on screen: wider than any sector
    val span = abs(wrapLon(right[1] - left[1]))
    return sectors.filter { it.name != "CONUS" && cam.lat in it.south..it.north && cam.lon in it.west..it.east && it.east - it.west >= span * 0.85 }
        .minByOrNull { (it.east - it.west) * (it.north - it.south) } ?: conus
}

/**
 * The part of [view]'s picture a globe shows on its [width] x [height] screen, as fractions of the picture (left, top, right, bottom; the top is
 * north), or null when that is the whole picture (or nearly), none of it, or too small to matter. [toLatLon] gives the latitude / longitude of a (column, row).
 */
fun focusFromGlobe(view: View, cam: GlobeCamera, width: Int, height: Int, toLatLon: (Double, Double) -> DoubleArray): MapFocus? {
    val n = 40
    var l = 2.0; var r = -1.0; var t = 2.0; var b = -1.0; var al = 2.0; var ar = -1.0; var any = false
    for (i in 0..n) for (j in 0..n) {
        val u = i / n.toDouble(); val v = j / n.toDouble()                       // fractions of the picture, v from its top
        val ll = toLatLon(view.col0 + u * (view.cols - 1), view.row0 + (1.0 - v) * (view.rows - 1))
        val p = globeForward(cam, width, height, ll[0], ll[1]) ?: continue
        if (p[0] < 0.0 || p[0] > width || p[1] < 0.0 || p[1] > height) continue
        t = min(t, v); b = max(b, v)
        any = true
        // the width is measured across the picture's middle, as the map is fitted by its width: toward the top and bottom a tall globe screen shows wider (converging meridians)
        if (v in 0.35..0.65) { l = min(l, u); r = max(r, u) } else { al = min(al, u); ar = max(ar, u) }
    }
    if (!any) return null
    if (r < l) { l = al; r = ar }
    val step = 1.0 / n
    val left = (l - step).coerceAtLeast(0.0); val right = (r + step).coerceAtMost(1.0)
    val top = (t - step).coerceAtLeast(0.0); val bottom = (b + step).coerceAtMost(1.0)
    if (right - left < 0.02 || bottom - top < 0.02) return null
    if ((right - left) * (bottom - top) > 0.9) return null
    return MapFocus(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/** The part of a picture to zoom to, as fractions of it (left, top, right, bottom; the top of the picture is north). */
data class MapFocus(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** The latitude / longitude box of the part of [view]'s picture that the fractions (left, top, right, bottom) show. */
fun viewportBox(view: View, vp: FloatArray, toLatLon: (Double, Double) -> DoubleArray): DoubleArray {
    val colMin = view.col0 + vp[0] * view.cols.toDouble(); val colMax = view.col0 + vp[2] * view.cols.toDouble() - 1.0
    val rowMin = view.row0 + (1.0 - vp[3]) * view.rows; val rowMax = view.row0 + (1.0 - vp[1]) * view.rows - 1.0
    var south = 90.0; var north = -90.0; var west = 360.0; var east = -360.0
    for (k in 0..24) {
        val f = k / 24.0
        val ca = colMin + f * (colMax - colMin); val ra = rowMin + f * (rowMax - rowMin)
        for ((c, r) in listOf(ca to rowMin, ca to rowMax, colMin to ra, colMax to ra)) {
            val ll = toLatLon(c, r)
            south = min(south, ll[0]); north = max(north, ll[0]); west = min(west, ll[1]); east = max(east, ll[1])
        }
    }
    return doubleArrayOf(west, south, east, north)
}

private fun wrapLon(lon: Double) = ((lon + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
