package simplemodelview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolarTest {
    private fun close(a: Double, b: Double, eps: Double = 1e-6) = assertTrue(abs(a - b) < eps, "$a vs $b")

    @Test fun thePoleIsTheMiddleAndTheEdgeLatitudeIsTheCircle() {
        val m = Polar.forward(true, -100.0, 100.0, 20.0, 201, 201, 90.0, 33.0)!!
        close(m[0], 100.5); close(m[1], 100.5)
        // the center longitude at the bottom of the circle, at the edge latitude
        val bottom = Polar.forward(true, -100.0, 100.0, 20.0, 201, 201, 20.0, -100.0)!!
        close(bottom[0], 100.5); close(bottom[1], 200.5)
        // 90 degrees east of it: to the right
        val right = Polar.forward(true, -100.0, 100.0, 20.0, 201, 201, 20.0, -10.0)!!
        close(right[0], 200.5); close(right[1], 100.5)
        assertNull(Polar.forward(true, -100.0, 100.0, 20.0, 201, 201, 19.0, -100.0))      // beyond the edge
        assertNull(Polar.forward(true, -100.0, 100.0, 20.0, 201, 201, -45.0, -100.0))     // the other hemisphere
    }

    @Test fun theSouthPoleHasItsCenterLongitudeAtTheTop() {
        val top = Polar.forward(false, 0.0, 100.0, 20.0, 201, 201, -20.0, 0.0)!!
        close(top[0], 100.5); close(top[1], 0.5)
        val pole = Polar.forward(false, 0.0, 100.0, 20.0, 201, 201, -90.0, 77.0)!!
        close(pole[0], 100.5); close(pole[1], 100.5)
    }

    @Test fun inverseUndoesForwardAndMatchesTheCoreForHalfTheRadius() {
        for (north in listOf(true, false)) for ((lat, lon) in listOf(75.0 to 10.0, 45.0 to -120.0, 30.0 to 179.0, 62.5 to -33.0)) {
            val la = if (north) lat else -lat
            val p = Polar.forward(north, -100.0, 120.0, 20.0, 301, 301, la, lon)!!
            val back = Polar.inverse(north, -100.0, 120.0, 20.0, 301, 301, p[0], p[1])!!
            close(back[0], la, 1e-6); close(abs(((back[1] - lon + 540.0) % 360.0) - 180.0), 0.0, 1e-6)
        }
        // half way along the radius is the latitude the core test checks (colatitude 2 atan(0.5 tan 35 deg))
        val half = Polar.inverse(true, -100.0, 100.0, 20.0, 201, 201, 150.5, 100.5)!!
        close(half[0], 90.0 - 2 * kotlin.math.atan(0.5 * kotlin.math.tan(35.0 * kotlin.math.PI / 180)) * 180 / kotlin.math.PI)
        assertNull(Polar.inverse(true, -100.0, 100.0, 20.0, 201, 201, 0.5, 0.5))           // a corner
        assertNotNull(Polar.inverse(true, -100.0, 100.0, 20.0, 201, 201, 100.5, 100.5))
        assertTrue(Polar.pixelsPerDegree(100.0, 20.0) > 0.5)
        assertEquals(20.0, Polar.EDGE_LAT)
    }
}

class PolarCameraTest {
    @Test fun draggingAroundThePoleTurnsTheMapAndRoundTrips() {
        val cam = PolarCamera(true, -100.0, 100.0)
        // a finger on the right of the middle moving down is a quarter turn clockwise on the screen
        val t = cam.turned(0.0, 100.0, 200.0, 100.0, 200, 200)
        assertTrue(abs(t.lon - cam.lon) > 20.0)
        val p = t.project(200, 200, 60.0, 10.0)!!
        val back = t.inverse(200, 200, p[0], p[1])!!
        assertTrue(abs(back[0] - 60.0) < 1e-6 && abs(back[1] - 10.0) < 1e-6)
        assertEquals(300.0, cam.zoomed(10.0, 50.0, 300.0).radius)
    }
}
