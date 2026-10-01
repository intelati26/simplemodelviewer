package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals

class PlacesTest {
    @Test fun roundTrip() {
        val places = listOf(Place("Norman, OK", 35.22, -97.44), Place("a|b", -1.5, 10.0))
        val back = decodePlaces(encodePlaces(places))
        assertEquals(listOf("Norman, OK", "a/b"), back.map { it.name })
        assertEquals(35.22, back[0].lat)
        assertEquals(emptyList(), decodePlaces(""))
        assertEquals(emptyList(), decodePlaces("broken|line"))
    }

    @Test fun labels() = assertEquals("35.22N 97.44W", placeLabel(35.22, -97.44))
}
