package spcrefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobeTest {
    private val cam = GlobeCamera(38.5, -97.5, 300.0)

    @Test fun centreMapsToTheMiddle() {
        val p = assertNotNull(globeForward(cam, 800, 600, 38.5, -97.5))
        assertEquals(400.0, p[0], 1e-9); assertEquals(300.0, p[1], 1e-9)
    }

    @Test fun northIsUpEastIsRight() {
        val north = assertNotNull(globeForward(cam, 800, 600, 45.0, -97.5))
        val east = assertNotNull(globeForward(cam, 800, 600, 38.5, -90.0))
        assertTrue(north[1] < 300.0 && kotlin.math.abs(north[0] - 400.0) < 1e-6)
        assertTrue(east[0] > 400.0)
    }

    @Test fun farSideIsHidden() = assertNull(globeForward(cam, 800, 600, -38.5, 82.5))

    @Test fun forwardAndInverseRoundTrip() {
        for ((lat, lon) in listOf(35.0 to -100.0, 48.0 to -80.0, 25.0 to -120.0, 38.5 to -97.5)) {
            val p = assertNotNull(globeForward(cam, 800, 600, lat, lon))
            val back = assertNotNull(globeInverse(cam, 800, 600, p[0], p[1]))
            assertEquals(lat, back[0], 1e-6); assertEquals(lon, back[1], 1e-6)
        }
    }

    @Test fun outsideTheSphereHasNoLocation() = assertNull(globeInverse(cam, 800, 600, 5.0, 5.0))

    @Test fun draggingTurnsTheGlobe() {
        // dragging the surface to the right brings western longitudes to the centre; dragging down brings the north into view
        val c = cam.dragged(dx = 100.0, dy = 0.0)
        assertTrue(c.lon < cam.lon)
        val d = cam.dragged(dx = 0.0, dy = 100.0)
        assertTrue(d.lat > cam.lat)
        assertEquals(85.0, GlobeCamera(80.0, 0.0, 100.0).dragged(0.0, 10000.0).lat)
        assertTrue(GlobeCamera(0.0, 179.0, 100.0).dragged(-200.0, 0.0).lon in -180.0..180.0)
    }

    @Test fun zoomIsClamped() = assertEquals(900.0, cam.zoomed(100.0, 100.0, 900.0).radius)
}
