package spcrefs

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadoutTest {
    private val core = object : Core by StubCore {
        override fun gridToLatLon(grid: Grid, column: Double, row: Double) = doubleArrayOf(39.1, -97.2)
    }
    private val grid = FloatArray(GRID_COLUMNS * GRID_ROWS) { Float.NaN }

    private fun product(id: String, units: String) =
        Product(id, id, Groups.OTHER, Catalogue.styleFor(id, units), false, "valid_time")

    @Test fun fixedText() {
        assertEquals("42.50", fixed(42.5, 2))
        assertEquals("-0.30", fixed(-0.3, 2))
        assertEquals("7", fixed(6.6, 0))
        assertEquals("0.00", fixed(-0.001, 2))
    }

    @Test fun valueReadout() {
        grid[5 * GRID_COLUMNS + 7] = 42.5f
        assertEquals("39.10N 97.20W   42.5 dBZ", readout(core, product("cref_max", "dBz"), grid, emptyList(), 7, 5))
        assertEquals("39.10N 97.20W   no data", readout(core, product("cref_max", "dBz"), grid, emptyList(), 8, 5))
    }

    @Test fun probabilityIsPercent() {
        grid[0] = 0.37f
        assertEquals("39.10N 97.20W   37 %", readout(core, product("x_prob_t>1", "dimensionless"), grid, emptyList(), 0, 0))
    }

    @Test fun paintballReadout() {
        grid[0] = (1 or 4).toFloat()
        val members = listOf("HRRR", "RRFS", "REFS M01")
        assertEquals("39.10N 97.20W   2 of 10 members: HRRR, REFS M01",
            readout(core, product("uh_pb_x", "dimensionless"), grid, members, 0, 0))
    }

    @Test fun autoStopsSpanTheData() {
        grid[0] = 10f; grid[1] = 30f
        val s = withAutoStops(Style(Kind.Blended, emptyList(), units = "x"), grid)
        assertEquals(10.0, s.stops.first().value)
        assertEquals(30.0, s.stops.last().value)
    }
}
