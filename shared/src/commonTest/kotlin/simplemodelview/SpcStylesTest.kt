package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpcStylesTest {
    private val base = Style(Kind.Blended, emptyList(), units = "J/kg", digits = 0)

    @Test fun everyTableIsWellFormed() {
        assertTrue(SpcColormaps.tables.size >= 60)
        for ((name, t) in SpcColormaps.tables) {
            assertEquals(t.levels.size, t.colors.size + 1, "$name: one color per band")
            assertTrue(t.levels.toList().zipWithNext().all { (a, b) -> a < b }, "$name: levels increase")
            assertTrue(t.opacity in 0.1..1.0, name)
        }
    }

    @Test fun aTableBecomesABandedStyleLikeTheViewers() {
        val s = siteStyle("sbcape_mean", base)
        val t = SpcColormaps.tables.getValue("sbcape_mean")
        assertEquals(Kind.Banded, s.kind)
        assertEquals(t.levels.size - 1, s.stops.size)                       // 20 bands, not the 10 the app used to have
        assertEquals(t.levels.first(), s.stops.first().value)
        assertEquals(t.levels.last(), s.top)                                 // the bands end at the last level
        assertEquals(0.9, s.opacity, 0.0)                                    // the viewer draws CAPE at 90 %
        assertEquals("J/kg", s.units)                                        // units, digits and factor stay the app's own
        assertEquals(0, s.digits)
        assertEquals(t.colors.toList(), s.stops.map { it.rgb })
    }

    @Test fun edgeColorsAndTransparency() {
        val temp = siteStyle("t2m_mean", Style(Kind.Blended, emptyList(), units = "F"))
        assertTrue(temp.under != null && temp.over != null)                  // colors beyond both ends, as the viewer has
        assertEquals(1.0, temp.opacity, 0.0)                                 // (no opacity set: fully opaque)
        val tor = siteStyle("grefs-4hr/tor_prob", Style(Kind.Banded, emptyList(), units = "%", digits = 0))
        assertEquals(listOf(2.0, 5.0, 10.0, 15.0, 30.0, 45.0), tor.stops.map { it.value })
        assertEquals(60.0, tor.top)
        assertTrue(tor.over != null)
    }

    @Test fun variablesWithoutATableKeepTheirOwnStyle() {
        assertEquals(base, siteStyle("cref_max", base))
        assertEquals(base, siteStyle("not_a_product", base))
        assertNull(base.top)
    }

    @Test fun whereTheViewerShowsAnotherUnitOrSign() {
        val wind = siteStyle("s10m_01h_max_mean", Style(Kind.Banded, emptyList(), units = "kt"))
        assertEquals("mph", wind.units); assertEquals(1.150779, wind.factor, 1e-9)       // the store has knots, the viewer shows mph
        val uh = siteStyle("uh25min_04h_min_overlap_min", Style(Kind.Banded, emptyList(), factor = -1.0, units = "m2/s2"))
        assertEquals(1.0, uh.factor, 0.0)                                                 // negative helicity stays negative
        assertTrue(uh.stops.first().value < 0)
    }
}
