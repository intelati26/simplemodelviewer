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

    @Test fun colorsAreVirtualProductsAndTheChooserFoldsThemAway() {
        fun array(name: String) = ArrayInfo(name, StoreKind.REFS, listOf(60, 1059, 1799), listOf(1, 1059, 1799), "<f4", true, Double.NaN, listOf("valid_time", "y", "x"), "knot")
        val wind = listOf("z500_mean", "u500_mean", "v500_mean").map(::array)
        val bare = Catalog.build(wind).map { it.id }
        assertTrue("wind500_mean" in bare && "wind500_t2m_mean" !in bare)                                      // no 2-m temperature in the store
        val ids = Catalog.build(wind + listOf("t2m_mean", "t500_mean", "td2m_mean", "rh2m_mean").map(::array)).map { it.id }
        for (id in listOf("wind500_mean", "wind500_t2m_mean", "wind500_tlev_mean", "wind500_td2m_mean", "wind500_rh2m_mean", "heightwind")) assertTrue(id in ids, id)
        assertTrue("wind700_t2m_mean" !in ids)                                                                 // no 700 mb arrays
        assertEquals(500, WindProducts.planeLevel("wind500_tlev_mean"))
        assertEquals("t500_mean", WindProducts.parse("wind500_tlev_mean")!!.let { (l, c) -> WindProducts.colorSource(l, c) })
        assertEquals(null, WindProducts.planeLevel("t2m_mean"))
        assertEquals(null, WindProducts.planeLevel("heightwind"))
        assertEquals(Groups.SYNOPTIC, Groups.of("wind500_rh2m_mean", false))
        val shown = Catalog.forPicker(Catalog.build(wind + array("t2m_mean"))).map { it.id }
        assertTrue("heightwind" in shown)
        assertTrue(shown.none { it.startsWith("wind") })
        // the first plane is the colored field, not the speed; height, u and v are as before
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

    @Test fun theStackHasItsOwnIdsAndEightPlanes() {
        assertEquals("windstack_mean", WindProducts.id(WindProducts.STACK))
        assertEquals(WindProducts.STACK, WindProducts.level("windstack_mean"))
        val td = WindProducts.colors.first { it.key == "td2m" }
        assertEquals("windstack_td2m_mean", WindProducts.id(WindProducts.STACK, td))
        assertEquals(WindProducts.STACK to td, WindProducts.parse("windstack_td2m_mean"))
        assertEquals("t500_mean", WindProducts.colorSource(WindProducts.STACK, WindProducts.colors.first { it.key == "tlev" }))
        assertEquals(listOf("z500_mean", "u850_mean", "v850_mean", "u700_mean", "v700_mean", "u500_mean", "v500_mean"), WindProducts.stackSources())
        val cells = 2
        val winds = (0 until 6).map { k -> FloatArray(cells) { (k * 10 + it).toFloat() } }
        val frame = WindProducts.packStack(cells, floatArrayOf(5000f, 5100f), winds)
        assertEquals(cells * WindProducts.STACK_PLANES, frame.size)
        assertTrue(WindProducts.isStackFrame(frame, cells))
        assertEquals(5100f, frame[cells * WindProducts.HEIGHT + 1])
        assertEquals(winds[2][1], frame[cells * WindProducts.stackU(1) + 1])       // 700 mb u
        assertEquals(winds[5][0], frame[cells * WindProducts.stackV(2)])           // 500 mb v
        assertEquals(WindProducts.speed(winds[4][0], winds[5][0]), frame[0])       // the first plane: the 500 mb speed
        assertEquals(WindProducts.STACK, WindProducts.planeLevel("windstack_mean"))
    }

    @Test fun theStackNeedsTheMessagesOfThreeLevels() {
        val wanted = GfsRepository(object : Http { override suspend fun get(url: String) = HttpResult(404, ByteArray(0)) }, StubCore).windMessages(WindProducts.STACK, "speed")
        assertEquals(listOf("HGT:500 mb", "UGRD:850 mb", "VGRD:850 mb", "UGRD:700 mb", "VGRD:700 mb", "UGRD:500 mb", "VGRD:500 mb"), wanted.map { "${it.variable}:${it.level}" })
        assertEquals("TMP:500 mb", GfsRepository(object : Http { override suspend fun get(url: String) = HttpResult(404, ByteArray(0)) }, StubCore).windMessages(WindProducts.STACK, "tlev").last().let { "${it.variable}:${it.level}" })
    }
}
