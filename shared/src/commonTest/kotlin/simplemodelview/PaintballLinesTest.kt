package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaintballLinesTest {
    @Test fun maskHasOneBitPerMemberAtOrAboveTheThreshold() {
        val a = floatArrayOf(10f, 40f, Float.NaN)
        val b = floatArrayOf(45f, 39f, Float.NaN)
        val mask = PaintballLines.mask(listOf(a, b), 40f)
        assertEquals(2f, mask[0])          // only member 1
        assertEquals(1f, mask[1])          // only member 0
        assertTrue(mask[2].isNaN())        // no member has data
        val share = PaintballLines.percent(mask, 2)
        assertEquals(listOf(50f, 50f), share.take(2))
        assertEquals(100f, PaintballLines.percent(floatArrayOf(3f), 2)[0])
        assertTrue(share[2].isNaN())
    }

    @Test fun blurKeepsAFlatFieldAndSpreadsAPeak() {
        val flat = FloatArray(20 * 10) { 40f }
        assertTrue(PaintballLines.blur(flat, 20, 10, 3).all { kotlin.math.abs(it - 40f) < 1e-3f })
        val peak = FloatArray(21 * 21).also { it[10 * 21 + 10] = 100f }
        val soft = PaintballLines.blur(peak, 21, 21, 3)
        assertTrue(soft[10 * 21 + 10] < 100f && soft[10 * 21 + 13] > 0f)
        val gap = floatArrayOf(10f, Float.NaN, 10f)
        assertTrue(PaintballLines.blur(gap, 3, 1, 1)[1].isNaN())
    }

    @Test fun neighborhoodShareCountsAMemberWithinTheRadius() {
        val cols = 9
        val mask = FloatArray(cols) { 0f }
        mask[0] = 1f            // member 0 reaches the threshold in cell 0
        mask[8] = 2f            // member 1 in cell 8
        val share = PaintballLines.neighborhoodPercent(mask, cols, 1, 2, 2)
        assertEquals(50f, share[2])     // within 2 cells of member 0 only
        assertEquals(0f, share[4])      // too far from both
        assertEquals(50f, share[6])
    }

    @Test fun specsAreFoundById() {
        assertEquals("cref", PaintballLines.spec("pbl_cref")?.source)
        assertEquals(null, PaintballLines.spec("cref"))
        assertEquals(Groups.STORM, Groups.of("pbl_uh25", false))
    }

    @Test fun discussionGraphicUrls() {
        assertEquals("https://www.spc.noaa.gov/products/md/mcd2437.png", discussionImageUrl("2437", spc = true))
        assertEquals("https://www.wpc.ncep.noaa.gov/metwatch/images/mcd0051.gif", discussionImageUrl("51", spc = false))
    }
}
