package simplemodelview

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the globe is looking: the point at its center and the sphere's radius in pixels. */
data class GlobeCamera(val lat: Double, val lon: Double, val radius: Double) {
    /** Drags the surface under the finger: [dx] / [dy] pixels (right / down) turn the globe the other way. */
    fun dragged(dx: Double, dy: Double): GlobeCamera {
        val k = 180.0 / PI / radius
        val newLat = (lat + dy * k).coerceIn(-85.0, 85.0)
        val cosLat = maxOf(cos(lat * PI / 180.0), 0.2)            // near the poles a horizontal drag turns the globe faster
        var newLon = lon - dx * k / cosLat
        newLon = ((newLon + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
        return copy(lat = newLat, lon = newLon)
    }

    fun zoomed(factor: Double, min: Double, max: Double) = copy(radius = (radius * factor).coerceIn(min, max))
}

/** Pixel position of a latitude / longitude on a globe drawn in a [width] x [height] buffer; null when it is on the far side. */
fun globeForward(cam: GlobeCamera, width: Int, height: Int, lat: Double, lon: Double): DoubleArray? {
    val phi = lat * PI / 180.0
    val phi0 = cam.lat * PI / 180.0
    val dl = (lon - cam.lon) * PI / 180.0
    val cosC = sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dl)
    if (cosC <= 0.0) return null
    val x = cos(phi) * sin(dl)
    val y = cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dl)
    return doubleArrayOf(width / 2.0 + x * cam.radius, height / 2.0 - y * cam.radius)
}

/** Latitude / longitude under a pixel; null when the pixel is outside the sphere. */
fun globeInverse(cam: GlobeCamera, width: Int, height: Int, px: Double, py: Double): DoubleArray? {
    val x = (px - width / 2.0) / cam.radius
    val y = (height / 2.0 - py) / cam.radius
    val rho = hypot(x, y)
    if (rho > 1.0) return null
    if (rho < 1e-12) return doubleArrayOf(cam.lat, cam.lon)
    val c = asin(rho)
    val phi0 = cam.lat * PI / 180.0
    val lat = asin(cos(c) * sin(phi0) + y * sin(c) * cos(phi0) / rho)
    val lon = cam.lon * PI / 180.0 + atan2(x * sin(c), rho * cos(phi0) * cos(c) - y * sin(phi0) * sin(c))
    val lonDeg = (((lon * 180.0 / PI + 540.0) % 360.0) + 360.0) % 360.0 - 180.0
    return doubleArrayOf(lat * 180.0 / PI, lonDeg)
}

/** Angular size of the sphere's visible cap from a camera [radius]: how many degrees the view spans edge to edge across [width]. */
fun globeSpanDegrees(radius: Double, width: Int): Double =
    if (width / 2.0 >= radius) 180.0 else 2 * acos(sqrt(1 - (width / 2.0 / radius).let { it * it })) * 180.0 / PI
