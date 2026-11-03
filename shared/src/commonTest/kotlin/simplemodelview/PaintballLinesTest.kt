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
        val field = PaintballLines.memberField(mask, 1, FloatArray(3))
        assertEquals(listOf(1f, 0f), field.take(2))
        assertTrue(field[2].isNaN())
    }

    @Test fun specsAreFoundById() {
        assertEquals("cref", PaintballLines.spec("pbl_cref")?.source)
        assertEquals(null, PaintballLines.spec("cref"))
        assertEquals(Groups.STORM, Groups.of("pbl_uh25", false))
    }
}
