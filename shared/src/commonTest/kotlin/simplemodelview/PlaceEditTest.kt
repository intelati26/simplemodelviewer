package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceEditTest {
    private val a = Place("Home", 35.0, -97.0); private val b = Place("Work", 36.0, -96.0); private val c = Place("Cabin", 37.0, -95.0)

    @Test fun anEditedPlaceKeepsItsPosition() {
        val out = editPlace(listOf(a, b, c), b, Place("Office", 36.2, -96.1))
        assertEquals(listOf("Home", "Office", "Cabin"), out.map { it.name })
        assertEquals(36.2, out[1].lat, 0.0)
    }

    @Test fun aNameAlreadyTakenIsReplaced() {
        val out = editPlace(listOf(a, b, c), c, Place("Home", 37.5, -95.5))
        assertEquals(listOf("Work", "Home"), out.map { it.name })
        assertEquals(37.5, out.last().lat, 0.0)
    }

    @Test fun aBlankNameBecomesTheCoordinates() {
        assertEquals("35.50N 97.25W", editPlace(listOf(a), a, Place("  ", 35.5, -97.25)).single().name)
    }

    @Test fun coordinatesAreChecked() {
        assertTrue(validLatLon(35.0, -97.0)); assertFalse(validLatLon(95.0, 0.0)); assertFalse(validLatLon(0.0, 200.0)); assertFalse(validLatLon(Double.NaN, 0.0))
    }
}
