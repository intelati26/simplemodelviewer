package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContourTest {
    private fun grid(cols: Int, rows: Int, f: (Int, Int) -> Float) = FloatArray(cols * rows) { f(it % cols, it / cols) }

    @Test fun levelsAreMultiplesOfTheInterval() {
        assertEquals(listOf(-10.0, 0.0, 10.0), ContourSpec(10.0).levels(-12.0, 14.0))
        assertTrue(ContourSpec(10.0).levels(5.0, 9.0).isEmpty())
        assertTrue(ContourSpec(1.0).levels(0.0, 1e6).isEmpty())     // too many lines: none
    }

    @Test fun boldAndLabels() {
        val s = ContourSpec(60.0, boldEvery = 300.0, labelDivisor = 10.0)
        assertTrue(s.isBold(5400.0)); assertTrue(!s.isBold(5460.0))
        assertEquals("546", s.label(5460.0))
        assertTrue(ContourSpec(10.0, boldEvery = 1000.0, boldBase = 32.0).isBold(32.0))
    }

    @Test fun straightLineAtTheRightPlace() {
        // value = column: the level 2.5 is the vertical line x = 2.5, from the bottom to the top of the window
        val v = grid(6, 5) { c, _ -> c.toFloat() }
        val chains = Contours.trace(v, 6, 0, 0, 5, 4, 2.5f)
        assertEquals(1, chains.size)
        val ch = chains[0]
        assertEquals(5, ch.size / 2)
        for (i in ch.indices step 2) assertEquals(2.5f, ch[i], 1e-5f)
        assertEquals(setOf(0f, 1f, 2f, 3f, 4f), (1 until ch.size step 2).map { ch[it] }.toSet())
    }

    @Test fun circleIsOneClosedLoop() {
        val v = grid(21, 21) { c, r -> ((c - 10) * (c - 10) + (r - 10) * (r - 10)).toFloat() }   // r^2
        val chains = Contours.trace(v, 21, 0, 0, 20, 20, 25f)                                   // radius 5
        assertEquals(1, chains.size)
        val ch = chains[0]
        assertEquals(ch[0], ch[ch.size - 2]); assertEquals(ch[1], ch[ch.size - 1])
        for (i in ch.indices step 2) {
            val d = kotlin.math.hypot(ch[i] - 10f, ch[i + 1] - 10f)
            assertTrue(kotlin.math.abs(d - 5f) < 0.3f, "radius $d")
        }
    }

    @Test fun twoSeparateLinesAreTwoChains() {
        val v = grid(9, 4) { c, _ -> if (c < 4) c.toFloat() else (8 - c).toFloat() }            // a ridge at column 4
        assertEquals(2, Contours.trace(v, 9, 0, 0, 8, 3, 1.5f).size)
    }

    @Test fun nanBreaksTheLine() {
        val v = grid(6, 5) { c, r -> if (c == 2 && r == 2) Float.NaN else c.toFloat() }
        val n = Contours.trace(v, 6, 0, 0, 5, 4, 2.5f).size
        assertTrue(n >= 1)
    }

    @Test fun pixelsAndUnits() {
        // stored in Pa, shown in hPa (factor 0.01); value rises with column; view starts at column 1, scale 2
        val g = Grid.REFS
        val v = FloatArray(g.cells) { (100000 + (it % g.columns) * 100).toFloat() }
        val view = View("t", 1, 0, 10, 10, 2, 1, g)
        val lines = Contours.lines(v, g, view, Style(factor = 0.01), ContourSpec(4.0, boldEvery = 20.0))
        assertTrue(lines.isNotEmpty())
        // 1000 hPa is stored 100000 = column 0 (outside), 1004 is column 4 -> x = (4 - 1 + 0.5) * 2 = 7
        val l = lines.first { it.level == 1004.0 }
        assertEquals(7f, l.points[0], 1e-3f)
        assertEquals("1004", l.label)
    }

    @Test fun specs() {
        assertEquals(60.0, Contours.specFor("z500_mean", "m")!!.interval)
        assertEquals(10.0, Contours.specFor("t2m_mean", "F")!!.interval)
        assertEquals(null, Contours.specFor("sbcape_mean", "J/kg"))
    }
}

class MrmsContourSpecTest {
    @Test fun modelFieldsGetLines() {
        assertEquals(2.0, Contours.specFor("Model_0degC_Height_00.50", "kft")!!.interval)
        assertEquals(0.5, Contours.specFor("Model_0degC_Height_00.50", "km")!!.interval)
        assertEquals(5.0, Contours.specFor("Model_WetBulbTemp_00.50", "C")!!.interval)
        assertEquals(null, Contours.specFor("MergedReflectivityQCComposite_00.50", "dBZ"))
    }
}

class ContourSmoothingTest {
    @Test fun noiseDoesNotMakeSquiggles() {
        // a ramp across the columns with a one-cell dip behind the 5.5 line: unsmoothed it makes a small loop of its own, smoothed it does not
        val cols = 21; val rows = 21
        val v = FloatArray(cols * rows) { (it % cols) / 2f }
        v[10 * cols + 13] -= 3f
        val raw = Contours.trace(v, cols, 0, 0, cols - 1, rows - 1, 5.5f).size
        val sm = Contours.trace(Contours.smoothed(v, cols, 0, 0, cols - 1, rows - 1, 2), cols, 0, 0, cols - 1, rows - 1, 5.5f)
        assertTrue(raw > 1)
        assertEquals(1, sm.size)
    }

    @Test fun smoothingKeepsARampAndNaN() {
        val cols = 15; val rows = 9
        val v = FloatArray(cols * rows) { (it % cols).toFloat() }
        v[4 * cols + 10] = Float.NaN
        val s = Contours.smoothed(v, cols, 0, 0, cols - 1, rows - 1, 2)
        assertTrue(s[4 * cols + 10].isNaN())
        assertEquals(4f, s[4 * cols + 4], 1e-4f)
    }

    @Test fun chaikinKeepsTheEndsOfAnOpenLineAndClosesALoop() {
        val open = Contours.chaikin(floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f), false, 2)
        assertEquals(0f, open[0]); assertEquals(0f, open[1])
        assertEquals(4f, open[open.size - 2]); assertEquals(4f, open[open.size - 1])
        assertTrue(open.size > 6)
        val ring = Contours.chaikin(floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f, 0f, 0f), true, 1)
        assertEquals(ring[0], ring[ring.size - 2]); assertEquals(ring[1], ring[ring.size - 1])
    }
}
