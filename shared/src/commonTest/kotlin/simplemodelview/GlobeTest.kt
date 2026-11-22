package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobeTest {
    private val cam = GlobeCamera(38.5, -97.5, 300.0)

    @Test fun centerMapsToTheMiddle() {
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
        // dragging the surface to the right brings western longitudes to the center; dragging down brings the north into view
        val c = cam.dragged(dx = 100.0, dy = 0.0)
        assertTrue(c.lon < cam.lon)
        val d = cam.dragged(dx = 0.0, dy = 100.0)
        assertTrue(d.lat > cam.lat)
        assertEquals(90.0, GlobeCamera(80.0, 0.0, 100.0).dragged(0.0, 10000.0).lat)
        assertTrue(GlobeCamera(0.0, 179.0, 100.0).dragged(-200.0, 0.0).lon in -180.0..180.0)
    }

    @Test fun zoomIsClamped() = assertEquals(900.0, cam.zoomed(100.0, 100.0, 900.0).radius)
}

class GlobeLinkTest {
    private val conus = doubleArrayOf(-125.0, 24.0, -66.5, 50.0)
    // a stand-in grid: column = longitude + 125, row = latitude - 24 (row 0 south; the picture's top is north)
    private val view = View("t", 0, 0, 60, 27, 1)
    private val toLatLon = { c: Double, r: Double -> doubleArrayOf(r + 24.0, c - 125.0) }

    @Test fun aFittedGlobeShowsTheBoxOnScreen() {
        val cam = globeFitting(conus, 1080, 1800, 300.0, 20000.0)
        for ((la, lo) in listOf(24.0 to -125.0, 50.0 to -66.5, 24.0 to -66.5, 50.0 to -125.0)) {
            val p = assertNotNull(globeForward(cam, 1080, 1800, la, lo))
            assertTrue(p[0] in 0.0..1080.0 && p[1] in 0.0..1800.0, "corner on screen: ${p.toList()}")
        }
        assertEquals(37.0, cam.lat, 1e-9)
    }

    @Test fun aSmallBoxMakesAMuchBiggerGlobe() {
        val big = globeFitting(conus, 1080, 1800, 300.0, 20000.0).radius
        val small = globeFitting(doubleArrayOf(-98.1, 34.4, -84.9, 43.6), 1080, 1800, 300.0, 20000.0).radius
        assertTrue(small > 3 * big)
    }

    @Test fun theSectorIsTheSmallestOneThatHoldsTheGlobesView() {
        val midwest = globeFitting(doubleArrayOf(-98.1, 34.4, -84.9, 43.6), 1080, 2400, 300.0, 40000.0)
        assertEquals("Midwest", sectorForGlobe(midwest, 1080, 2400).name)
        assertEquals("CONUS", sectorForGlobe(globeFitting(conus, 1080, 2400, 300.0, 40000.0), 1080, 2400).name)
        assertEquals("CONUS", sectorForGlobe(GlobeCamera(26.0, -81.0, 5000.0), 1080, 2400).name)   // south Florida is in no sector
        assertEquals("CONUS", sectorForGlobe(GlobeCamera(38.0, -97.0, 300.0), 1080, 2400).name)    // the whole sphere
    }

    @Test fun theFocusIsWhatTheGlobeShowsOfThePicture() {
        // a globe fitted to lon -105..-95, lat 30..40 on a tall screen: the picture's columns 20..30 (plus a margin) and every row it reaches
        val cam = globeFitting(doubleArrayOf(-105.0, 30.0, -95.0, 40.0), 1080, 1800, 300.0, 40000.0)
        val f = assertNotNull(focusFromGlobe(view, cam, 1080, 1800, toLatLon))
        assertTrue(f.left in 0.25f..0.34f && f.right in 0.49f..0.57f, f.toString())
        assertNull(focusFromGlobe(view, globeFitting(conus, 1080, 1800, 300.0, 40000.0), 1080, 1800, toLatLon))   // all of it shows
        assertNull(focusFromGlobe(view, GlobeCamera(0.0, 100.0, 400.0), 1080, 1800, toLatLon))                    // none of it
    }
}
