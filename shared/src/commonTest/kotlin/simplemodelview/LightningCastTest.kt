package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightningCastTest {
    private val feed = """
        Title: NOAA/CIMSS LightningCast GOES-East
        RefreshSeconds: 120
        TimeRange: 2026-10-30T17:50:00Z 2026-10-30T17:55:00Z
        Color: 80 201 134
        Line: 2, 0, "LightningCast >= 10%"
        35.5, -97.25
        35.6, -97.0
        35.7, -96.5
        End:
        Color: 255 80 80
        Line: 2, 0, "LightningCast >= 70%"
        36.0, -97.0
        36.1, -96.9
        End:
        TimeRange: 2026-10-30T17:55:00Z 2026-10-30T18:00:00Z
        Color: 255 255 81
        Line: 2, 0, "LightningCast >= 30%"
        40.0, -90.0
        40.5, -89.5
        End:
        Line: 2, 0, "no percent here"
        41.0, -90.0
        41.5, -89.5
        End:
        Line: 2, 0, "LightningCast >= 50%"
        42.0, -90.0
        End:
    """.trimIndent()

    @Test fun parsesScansAndContours() {
        val frames = parseLightningCast(feed)
        assertEquals(2, frames.size)
        assertEquals(epochOf(2026, 10, 30, 17, 50, 0), frames[0].start)
        assertEquals(listOf(10, 70), frames[0].contours.map { it.percent })
        assertEquals(0x50C986, frames[0].contours[0].color)
        assertEquals(0xFF5050, frames[0].contours[1].color)
        assertEquals(listOf(35.5, -97.25, 35.6, -97.0, 35.7, -96.5), frames[0].contours[0].points.toList())
        // a contour without a percent, and one with a single point, are dropped
        assertEquals(listOf(30), frames[1].contours.map { it.percent })
        assertEquals(0xFFFF51, frames[1].contours[0].color)
    }

    @Test fun orderAndDamage() {
        val reversed = feed.replace("17:50:00Z 2026-10-30T17:55:00Z", "18:10:00Z 2026-10-30T18:15:00Z")
        assertEquals(listOf(epochOf(2026, 10, 30, 17, 55, 0), epochOf(2026, 10, 30, 18, 10, 0)), parseLightningCast(reversed).map { it.start })
        assertEquals(0, parseLightningCast("").size)
        assertEquals(0, parseLightningCast("<html>not a placefile</html>").size)
        assertEquals(0, parseLightningCast("Color: 1 2 3\nLine: 1, 0, \">= 10%\"\n1,2\n3,4\nEnd:").size)      // contours before any scan have nowhere to go
        assertEquals(1, parseLightningCast("TimeRange: garbage\nTimeRange: 2026-10-30T17:50:00Z x\nLine: 1, 0, \">= 10%\"\nx, y\n1.0, 2.0\n3.0, 4.0\nEnd:").size)
    }

    @Test fun picksTheScanForATime() {
        val frames = parseLightningCast(feed)
        assertEquals(frames[1], lightningFrameFor(frames, null))
        assertEquals(frames[0], lightningFrameFor(frames, epochOf(2026, 10, 30, 17, 52, 0)))
        assertEquals(frames[1], lightningFrameFor(frames, epochOf(2026, 10, 30, 17, 55, 0)))
        assertEquals(frames[0], lightningFrameFor(frames, epochOf(2026, 10, 30, 12, 0, 0)))      // before them all: the first
        assertNull(lightningFrameFor(emptyList(), null))
    }

    private class FakeHttp(val status: Int, val body: String) : Http {
        var url = ""
        override suspend fun get(url: String): HttpResult { this.url = url; return HttpResult(status, body.encodeToByteArray()) }
    }

    @Test fun loadsTheFeed() = runTest {
        val http = FakeHttp(200, feed)
        assertEquals(2, LightningCastRepository(http).load().size)
        assertEquals("https://cimss.ssec.wisc.edu/severe_conv/NOAACIMSS_PLTG_GOES-East_CONUS_LOOP", http.url)
        assertFailsWith<RefsException> { LightningCastRepository(FakeHttp(503, "")).load() }
        assertFailsWith<RefsException> { LightningCastRepository(FakeHttp(200, "nothing")).load() }
        assertTrue(LightningCast.FEED.startsWith("https://cimss.ssec.wisc.edu/"))
    }
}
