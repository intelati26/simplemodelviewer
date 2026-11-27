package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContourNoteTest {
    private val t = ContourSpec(10.0)

    @Test fun namesTheNearestLevelAndHowFarAway() {
        // 84 F, falling 1 F per 10 km: the 80 line is 4 F, 40 km away
        assertEquals("nearest line 80, ~40 km", t.nearestNote(84.0, 1.0 / 10_000, metric = true))
    }

    @Test fun onTheLineAndFlatField() {
        assertEquals("on the 80 line", t.nearestNote(80.1, 1e-4, true))
        assertEquals("nearest line 80", t.nearestNote(83.0, 0.0, true))
        assertNull(ContourSpec(0.0).nearestNote(83.0, 1e-4, true))
    }
}
