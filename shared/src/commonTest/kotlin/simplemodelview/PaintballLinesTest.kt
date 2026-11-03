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

    @Test fun specsAreFoundById() {
        assertEquals("cref", PaintballLines.spec("pbl_cref")?.source)
        assertEquals(null, PaintballLines.spec("cref"))
        assertEquals(Groups.STORM, Groups.of("pbl_uh25", false))
    }
}
