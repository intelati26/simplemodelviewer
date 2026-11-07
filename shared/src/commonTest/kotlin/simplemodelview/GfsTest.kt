package simplemodelview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GfsTest {
    private val idx = """
        1:0:d=2026110612:PRMSL:mean sea level:120 hour fcst:
        349:259596491:d=2026110612:HGT:500 mb:120 hour fcst:
        350:260387660:d=2026110612:TMP:500 mb:120 hour fcst:
        356:265896224:d=2026110612:UGRD:500 mb:120 hour fcst:
        357:266432111:d=2026110612:VGRD:500 mb:120 hour fcst:
        400:270000000:d=2026110612:TMP:2 m above ground:120 hour fcst:
        401:271000000:d=2026110612:TMP:850 mb:anl:
    """.trimIndent()

    @Test fun messagesAreFoundByVariableLevelAndHour() {
        val entries = parseGribIndex(idx)
        assertEquals(259596491L to 260387659L, findGfsMessage(entries, GfsMessage("HGT", "500 mb"), 120))
        assertEquals(265896224L to 266432110L, findGfsMessage(entries, GfsMessage("UGRD", "500 mb"), 120))
        assertNull(findGfsMessage(entries, GfsMessage("HGT", "500 mb"), 121))
        assertEquals(271000000L to null, findGfsMessage(entries, GfsMessage("TMP", "850 mb"), 0))        // hour 0 is "anl"
    }

    @Test fun uAndVOfALevelTouchSoTheyAreOneRequest() {
        val entries = parseGribIndex(idx)
        val u = findGfsMessage(entries, GfsMessage("UGRD", "500 mb"), 120)!!
        val v = findGfsMessage(entries, GfsMessage("VGRD", "500 mb"), 120)!!
        assertEquals(1, coalesceRanges(listOf(u.first..u.second!!, v.first..(270000000L - 1))).size)
        val h = findGfsMessage(entries, GfsMessage("HGT", "500 mb"), 120)!!
        assertEquals(1, coalesceRanges(listOf(h.first..h.second!!, 260387660L..265896223L)).size)            // height and temperature touch too
    }

    @Test fun runsAndFramesAreRead() {
        val cycle = gfsCycle(2026, 11, 6, 12)
        assertEquals("gfs.20261106/12/atmos/", cycle.prefix)
        val keys = listOf("gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f000", "gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f000.idx",
            "gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f120", "gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f123", "gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f384",
            "gfs.20261106/12/atmos/gfs.t12z.pgrb2.1p00.f006")
        val xml = "<ListBucketResult>" + keys.joinToString("") { "<Contents><Key>$it</Key><LastModified>2026-11-06T16:00:00.000Z</LastModified><Size>500000000</Size></Contents>" } + "</ListBucketResult>"
        val frames = parseGfsListing(xml, cycle, "gfs")
        assertEquals(listOf(0, 120, 123, 384), frames.map { it.leadHours })
        assertEquals("https://noaa-gfs-bdp-pds.s3.amazonaws.com/gfs.20261106/12/atmos/gfs.t12z.pgrb2.0p25.f120", frames[1].url)
        assertEquals(3, nbmStepHours(frames, 1))                      // the loop code is the NBM page's: 120 -> 123 is a 3-hour step
    }

    @Test fun windIsTurnedOntoTheGridsAxes() {
        // on the central meridian the grid is not turned; a west wind there stays a west wind
        val (u, v) = GfsWinds.rotate(floatArrayOf(10f), floatArrayOf(0f), floatArrayOf(-97.5f))
        assertEquals(10f, u[0], 1e-4f); assertEquals(0f, v[0], 1e-4f)
        // 40 degrees of longitude east the grid's up is turned 0.6225 x 40 = 24.9 degrees: a wind from the west is no longer along the grid's x
        val (u2, v2) = GfsWinds.rotate(floatArrayOf(10f), floatArrayOf(0f), floatArrayOf(-57.5f))
        assertTrue(abs(u2[0] - 10f * kotlin.math.cos(Math.toRadians(24.9)).toFloat()) < 1e-3f)
        assertTrue(v2[0] > 0f)
        // the speed does not change, a missing component stays missing
        assertEquals(10f, kotlin.math.hypot(u2[0], v2[0]), 1e-3f)
        assertTrue(GfsWinds.rotate(floatArrayOf(Float.NaN), floatArrayOf(1f), floatArrayOf(0f)).first[0].isNaN())
        // it agrees with the compass bearing the REFS page's read-out computes from grid components
        val grid = WindProducts.fromDegrees(u2[0], v2[0])
        assertEquals(270.0, WindProducts.compassDegrees(grid, -57.5), 1e-3)
    }

    @Test fun windIdsAreTheRefsWindProductIds() {
        assertEquals("wind500_mean", GfsCatalog.windId(500, "speed"))
        assertEquals("wind250_t2m_mean", GfsCatalog.windId(250, "t2m"))
        assertEquals("wind850_rhlev_mean", GfsCatalog.windId(850, "rhlev"))
        assertEquals(null, GfsCatalog.windId(500, "nope"))
        assertEquals(250, WindProducts.planeLevel("wind250_t2m_mean"))
        assertEquals("t300_mean", WindProducts.parse("wind300_tlev_mean")!!.let { (l, c) -> WindProducts.colorSource(l, c) })
        assertTrue(GfsCatalog.levels.all { it in WindProducts.levels })
        assertEquals(32f, GfsCatalog.colorConvert("t2m")(273.15f), 1e-3f)
        assertEquals(0f, GfsCatalog.colorConvert("tlev")(273.15f), 1e-3f)
    }
}
