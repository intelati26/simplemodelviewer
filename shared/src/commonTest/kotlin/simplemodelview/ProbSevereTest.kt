package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProbSevereTest {
    private val placefile = """
        ; a comment
        Title: ProbSevere
        RefreshSeconds: 60
        Threshold: 999
        Color: 255 0 0
        Line: 3, 0, "storm 1 hover"
          35.0, -97.0
          35.5, -97.0
          35.5, -96.5
          35.0, -97.0
        End:
        Color: 0 0 255
        Polygon:
          40.0, -100.0
          40.5, -100.0
          40.5, -99.5
        End:
        Polygon:
          41.0, -90.0, 10, 20, 30
          41.5, -90.0, 10, 20, 30
          41.5, -89.5, 10, 20, 30
        End:
        Text: 35.2, -96.9, 1, "label", "hover"
        Object: 36.0, -97.0
          Line: 2, 0
            0, 0
            10, 10
          End:
          Text: 0, 0, 1, "inside"
        End:
        Line: 1, 0
          30.0, -80.0
          31.0, -81.0
        End:
    """.trimIndent()

    @Test fun readsLinesAndPolygonsWithTheirColorsAndSkipsTheRest() {
        val shapes = parsePlacefileShapes(placefile)
        assertEquals(4, shapes.size)
        assertEquals(0xFF0000, shapes[0].color); assertEquals(false, shapes[0].closed); assertEquals(8, shapes[0].points.size)
        assertEquals(0x0000FF, shapes[1].color); assertEquals(true, shapes[1].closed)
        assertEquals(0x0A141E, shapes[2].color)                     // from the first point's own color
        assertEquals(0x0000FF, shapes[3].color)                     // the color in force; the Object's pixel offsets gave no outline
        assertEquals(30.0, shapes[3].points[0], 1e-9)
    }

    @Test fun damagedTextGivesFewerOutlinesNotAnException() {
        assertEquals(0, parsePlacefileShapes("").size)
        assertEquals(0, parsePlacefileShapes("Line: 1, 0\n  not, numbers\nEnd:\nPolygon:\n 95.0, 10.0\n End:").size)
        parsePlacefileShapes("Line:\n 1, 2\n 3, 4")                 // no End:
    }

    private class FakeHttp(val status: Int, val body: String) : Http {
        var url = ""
        override suspend fun get(url: String): HttpResult { this.url = url; return HttpResult(status, body.encodeToByteArray()) }
    }

    @Test fun loadsFromThePastedLinkOnly() = runTest {
        val http = FakeHttp(200, placefile)
        assertEquals(4, ProbSevereRepository(http) { "https://example.org/ps.txt" }.load().size)
        assertEquals("https://example.org/ps.txt", http.url)
        assertFailsWith<RefsException> { ProbSevereRepository(http) { null }.load() }
        assertFailsWith<RefsException> { ProbSevereRepository(FakeHttp(503, "")) { "https://example.org/p" }.load() }
        assertTrue(ProbSevere.INFO_PAGE.startsWith("https://cimss.ssec.wisc.edu/probsevere"))
    }
}
