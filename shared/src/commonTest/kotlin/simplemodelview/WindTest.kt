package simplemodelview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindTest {
    @Test fun packLaysOutFourPlanesAndComputesSpeed() {
        val u = floatArrayOf(30f, Float.NaN, 0f)
        val v = floatArrayOf(40f, 5f, -10f)
        val z = floatArrayOf(5700f, 5710f, 5720f)
        val out = WindProducts.pack(3, z, u, v)
        assertEquals(12, out.size)
        assertEquals(50f, out[0], 1e-4f)
        assertTrue(out[1].isNaN())
        assertEquals(10f, out[2], 1e-4f)
        assertEquals(5710f, out[3 + 1])
        assertEquals(30f, out[3 * WindProducts.U])
        assertEquals(-10f, out[3 * WindProducts.V + 2])
        assertEquals(listOf(5700f, 5710f, 5720f), WindProducts.plane(out, 3, WindProducts.HEIGHT).toList())
    }

    @Test fun directionIsWhereTheWindComesFrom() {
        assertEquals(270.0, WindProducts.fromDegrees(20f, 0f), 1e-6)     // blowing east: a west wind
        assertEquals(180.0, WindProducts.fromDegrees(0f, 20f), 1e-6)     // blowing north: a south wind
        assertEquals(0.0, WindProducts.fromDegrees(0f, -20f), 1e-6)      // blowing south: a north wind
        assertEquals(225.0, WindProducts.fromDegrees(10f, 10f), 1e-6)    // from the southwest
        assertEquals("WSW", WindProducts.compassName(247.5))
        assertEquals("N", WindProducts.compassName(359.0))
    }

    @Test fun compassBearingFollowsTheMapsNorthAwayFromTheCentralMeridian() {
        assertEquals(270.0, WindProducts.compassDegrees(270.0, -97.5), 1e-9)
        assertTrue(WindProducts.compassDegrees(270.0, -70.0) > 270.0)    // east of the middle the map's north leans to the left of the grid's up
        assertTrue(WindProducts.compassDegrees(270.0, -125.0) < 270.0)
        assertTrue(abs(WindProducts.compassDegrees(270.0, -125.0) - 270.0 + 17.1) < 0.2)
    }

    @Test fun productsExistOnlyWithAllThreeArrays() {
        fun array(name: String) = ArrayInfo(name, StoreKind.REFS, listOf(60, 1059, 1799), listOf(1, 1059, 1799), "<f4", true, Double.NaN, listOf("valid_time", "y", "x"), "knot")
        val all = listOf("z500_mean", "u500_mean", "v500_mean", "z700_mean", "u700_mean").map(::array)
        val ids = Catalog.build(all).map { it.id }
        assertTrue("wind500_mean" in ids)
        assertTrue("wind700_mean" !in ids)      // no v700
        assertTrue("wind850_mean" !in ids)
    }

    @Test fun temperatureProductNeedsTheTemperatureArrayAndColorsThePackedFramesFirstPlane() {
        fun array(name: String) = ArrayInfo(name, StoreKind.REFS, listOf(60, 1059, 1799), listOf(1, 1059, 1799), "<f4", true, Double.NaN, listOf("valid_time", "y", "x"), "knot")
        val wind = listOf("z500_mean", "u500_mean", "v500_mean").map(::array)
        assertTrue("wind500_t2m_mean" !in Catalog.build(wind).map { it.id })                                   // no 2-m temperature in the store
        val ids = Catalog.build(wind + array("t2m_mean")).map { it.id }
        assertTrue("wind500_t2m_mean" in ids && "wind500_mean" in ids)
        assertEquals(500, WindProducts.planeLevel("wind500_t2m_mean"))
        assertEquals(500, WindProducts.planeLevel("wind500_mean"))
        assertEquals(850, WindProducts.planeLevel("wind850_t2m_mean"))
        assertEquals(null, WindProducts.planeLevel("t2m_mean"))
        assertEquals(Groups.SYNOPTIC, Groups.of("wind500_t2m_mean", false))
        // the first plane is the temperature, not the speed; height, u and v are as before
        val out = WindProducts.pack(2, floatArrayOf(5700f, 5710f), floatArrayOf(30f, 0f), floatArrayOf(40f, 0f), floatArrayOf(280f, 285f))
        assertEquals(280f, out[0]); assertEquals(285f, out[1])
        assertEquals(5710f, out[2 + 1])
        assertEquals(30f, out[2 * WindProducts.U])
    }

    private fun barbFor(speed: Float) = WindBarbs.glyph(100f, 100f, -speed, 0f, speed, 40f)   // a wind from the east

    @Test fun barbSymbols() {
        // staff + one line per barb; pennants separate. A 65 kt wind rounds to 65: one pennant, one long barb, one short barb.
        val b65 = barbFor(65f)
        assertEquals(1, b65.pennants.size / 6)
        assertEquals(1 + 2, b65.lines.size / 4)
        val b100 = barbFor(100f)
        assertEquals(2, b100.pennants.size / 6)
        assertEquals(1, b100.lines.size / 4)
        val b20 = barbFor(20f)
        assertEquals(0, b20.pennants.size)
        assertEquals(1 + 2, b20.lines.size / 4)
        val b7 = barbFor(7f)                       // rounds to 5: a single short barb
        assertEquals(1 + 1, b7.lines.size / 4)
    }

    @Test fun staffPointsIntoTheWindAndBarbsAreOnTheClockwiseSide() {
        // west wind (u > 0): staff to the west (left on the picture), barbs up (north); picture y is down
        val g = WindBarbs.glyph(100f, 100f, 25f, 0f, 25f, 40f)
        assertEquals(60f, g.lines[2], 1e-3f)            // staff end x
        assertEquals(100f, g.lines[3], 1e-3f)
        assertTrue(g.lines[7] < 100f)                   // a barb end is above the staff
        // north wind (v < 0): staff up (y smaller), barbs to the right
        val n = WindBarbs.glyph(100f, 100f, 0f, -25f, 25f, 40f)
        assertEquals(60f, n.lines[3], 1e-3f)
        assertTrue(n.lines[6] > 100f)
    }

    @Test fun latticeSkipsCalmAndMissingCells() {
        val grid = Grid(0, 8, 8)
        val u = FloatArray(64) { 20f }
        val v = FloatArray(64) { 0f }
        u[3 * 8 + 3] = Float.NaN
        val view = View("test", 0, 0, 8, 8, 4, 1, grid)
        val all = WindBarbs.glyphs(u, v, grid, view, 8f, 6f)              // every 2nd cell: 4 x 4, less the one with no value
        assertEquals(15, all.size)
        val calm = WindBarbs.glyphs(FloatArray(64), v, grid, view, 8f, 6f)
        assertTrue(calm.isEmpty())
    }
}
