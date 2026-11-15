package simplemodelview

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * The polar stereographic map of one hemisphere (what the core's polar renderer draws): the pole at the middle of a picture, a circle of [radius] pixels reaching
 * to [edgeLat] (the same side as the pole), the center longitude at the bottom of the circle for the north pole and at the top for the south, as the globe looks
 * from over a pole. The distance from the middle grows as tan(colatitude / 2). Same maths as `Raster::polarToLatLon` in the core.
 */
object Polar {
    /** The latitude at the circle's edge by default: a vortex chart reaches from the pole to about 20 degrees. */
    const val EDGE_LAT = 20.0

    private fun rad(d: Double) = d * PI / 180.0

    /** Pixel (x, y) of a place, or null beyond the circle's edge. */
    fun forward(north: Boolean, centerLon: Double, radius: Double, edgeLat: Double, width: Int, height: Int, lat: Double, lon: Double): DoubleArray? {
        val colat = if (north) 90.0 - lat else 90.0 + lat
        val edgeColat = 90.0 - edgeLat
        if (colat > edgeColat + 1e-9) return null
        val r = radius * tan(rad(colat) / 2) / tan(rad(edgeColat) / 2)
        val dl = rad(lon - centerLon)
        val x = r * sin(dl)
        val yUp = if (north) -r * cos(dl) else r * cos(dl)
        return doubleArrayOf(width / 2.0 + x, height / 2.0 - yUp)
    }

    /** [latitude, longitude] under a pixel, or null outside the circle. */
    fun inverse(north: Boolean, centerLon: Double, radius: Double, edgeLat: Double, width: Int, height: Int, px: Double, py: Double): DoubleArray? {
        val x = (px - width / 2.0) / radius
        val yUp = (height / 2.0 - py) / radius
        val rho = hypot(x, yUp)
        if (rho > 1.0) return null
        val edgeColat = rad(90.0 - edgeLat)
        val colat = 2 * atan(rho * tan(edgeColat / 2)) * 180.0 / PI
        val lat = if (north) 90.0 - colat else -90.0 + colat
        val dl = if (rho < 1e-12) 0.0 else if (north) atan2(x, -yUp) else atan2(x, yUp)
        val lon = (((centerLon + dl * 180.0 / PI + 540.0) % 360.0) + 360.0) % 360.0 - 180.0
        return doubleArrayOf(lat, lon)
    }

    /** Pixels per degree of latitude about half way to the edge (what sets the lattice step of the barbs). */
    fun pixelsPerDegree(radius: Double, edgeLat: Double): Double {
        val edgeHalf = rad(90.0 - edgeLat) / 2
        val mid = edgeHalf / 2
        return radius / tan(edgeHalf) * 0.5 * (1 + tan(mid) * tan(mid)) * PI / 180.0
    }
}

/** A polar map: which pole, the longitude at the bottom of the circle (top for the south), and the circle's radius in pixels. Dragging turns it; it has no tilt. */
data class PolarCamera(val north: Boolean, val lon: Double, val radius: Double, val edgeLat: Double = Polar.EDGE_LAT) {
    /** A drag at ([px], [py]) by ([dx], [dy]) turns the map by the angle the finger moves about the pole. */
    fun turned(dx: Double, dy: Double, px: Double, py: Double, width: Int, height: Int): PolarCamera {
        // the angle of the finger about the middle changes by the drag; the map turns by that angle
        val ax = px - width / 2.0; val ay = py - height / 2.0
        val before = atan2(ax, ay)
        val after = atan2(ax + dx, ay + dy)
        var d = (after - before) * 180.0 / PI
        while (d > 180) d -= 360; while (d < -180) d += 360
        val n = lon - (if (north) d else -d)
        return copy(lon = ((n + 540.0) % 360.0 + 360.0) % 360.0 - 180.0)
    }
    fun zoomed(factor: Double, min: Double, max: Double) = copy(radius = (radius * factor).coerceIn(min, max))
    fun project(width: Int, height: Int, lat: Double, lon: Double) = Polar.forward(north, this.lon, radius, edgeLat, width, height, lat, lon)
    fun inverse(width: Int, height: Int, px: Double, py: Double) = Polar.inverse(north, lon, radius, edgeLat, width, height, px, py)
}
