package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufkitTest {
    private val text = """
SNPARM = PRES;TMPC;TMWC;DWPC;THTE;DRCT;SKNT;OMEG;CFRL;HGHT
STNPRM = SHOW;LIFT;SWET;KINX;LCLP;PWAT;TOTL;CAPE;LCLT;CINS;EQLV;LFCT;BRCH

STID = KOKC STNM = 723530 TIME = 261127/0300
SLAT = 35.40 SLON = -97.60 SELV = 393.0
STIM = 0

SHOW = 15.35 LIFT = 16.13 SWET = 74.90 KINX = -11.26
LCLP = 889.15 PWAT = 8.71 TOTL = 22.85 CAPE = 0.00
EQLV = -9999.00 LFCT = -9999.00

PRES TMPC TMWC DWPC THTE DRCT SKNT OMEG
CFRL HGHT
973.10 2.84 0.56 -2.97 287.14 270.00 10.00 0.00
0.00 400.48
925.10 5.44 1.53 -4.21 293.76 180.00 20.00 0.00
0.00 814.36
500.00 -15.66 -18.52 -30.58 312.56 -9999.00 -9999.00 0.00
0.00 5387.66

STID = KOKC STNM = 723530 TIME = 261127/0400
SLAT = 35.40 SLON = -97.60 SELV = 393.0
STIM = 1

CAPE = 12.50

PRES TMPC TMWC DWPC THTE DRCT SKNT OMEG
CFRL HGHT
970.00 2.00 0.50 -3.00 287.00 90.00 5.00 0.00
0.00 410.00
850.00 1.00 0.00 -5.00 290.00 0.00 15.00 0.00
0.00 1500.00
700.00 -5.00 -7.00 -12.00 300.00 270.00 25.00 0.00
0.00 3100.00

STN YYMMDD/HHMM PMSL PRES SKTC STC1
 723530 261127/0300 1015.00 973.00 2.00 3.00
 723530 261127/0400 1015.50 973.50 2.10 3.10
"""

    @Test fun readsTheStationAndEveryHour() {
        val f = assertNotNull(Bufkit.parse(text))
        assertEquals("KOKC", f.id); assertEquals(35.40, f.lat, 1e-9); assertEquals(-97.60, f.lon, 1e-9); assertEquals(393.0, f.elevationM, 1e-9)
        assertEquals(2, f.soundings.size)
        assertEquals(listOf(0, 1), f.soundings.map { it.forecastHour })
        assertEquals(3600L, f.soundings[1].valid - f.soundings[0].valid)
        assertEquals(f.soundings[0].valid, f.run)
        assertEquals(epochOf(2026, 11, 27, 3, 0, 0), f.soundings[0].valid)
    }

    @Test fun theSurfaceTableAfterTheLastProfileIsNotLevels() {
        assertEquals(3, assertNotNull(Bufkit.parse(text)).soundings[1].levels.size)
    }

    @Test fun readsTheLevelsAndMarksMissingValues() {
        val s = assertNotNull(Bufkit.parse(text)).soundings[0]
        assertEquals(3, s.levels.size)
        val low = s.levels[0]
        assertEquals(973.10, low.pressure, 1e-9); assertEquals(2.84, low.tempC, 1e-9); assertEquals(-2.97, low.dewC, 1e-9); assertEquals(0.56, low.wetBulbC, 1e-9)
        assertEquals(270.0, low.dirDeg, 1e-9); assertEquals(10.0, low.speedKt, 1e-9); assertEquals(400.48, low.heightM, 1e-9)
        assertTrue(s.levels[2].dirDeg.isNaN() && s.levels[2].speedKt.isNaN())              // -9999: missing
        assertNull(s.levels[2].uv())
    }

    @Test fun readsTheFilesOwnNumbers() {
        val s = assertNotNull(Bufkit.parse(text))
        assertEquals(889.15, s.soundings[0].indices["LCLP"]!!, 1e-9); assertEquals(8.71, s.soundings[0].indices["PWAT"]!!, 1e-9)
        assertTrue(s.soundings[0].indices["EQLV"]!!.isNaN())                              // -9999: none
        assertEquals(12.5, s.soundings[1].indices["CAPE"]!!, 1e-9)
    }

    @Test fun aWindFromTheWestBlowsEast() {
        val (u, v) = SoundingLevel(900.0, 0.0, 0.0, 0.0, 270.0, 10.0, 1000.0).uv()!!
        assertEquals(10.0, u, 1e-9); assertEquals(0.0, v, 1e-9)
        val (u2, v2) = SoundingLevel(900.0, 0.0, 0.0, 0.0, 180.0, 20.0, 1000.0).uv()!!       // from the south: toward the north
        assertEquals(0.0, u2, 1e-9); assertEquals(20.0, v2, 1e-9)
    }

    @Test fun nothingToReadIsNull() {
        assertNull(Bufkit.parse("not a bufkit file"))
        assertNull(Bufkit.parse("SNPARM = PRES;TMPC;DWPC;HGHT\n"))
    }

    @Test fun theUrlsFollowTheFeedsNames() {
        assertEquals("https://meteor.geol.iastate.edu/data/bufkit/rrfs/rrfs_kokc.buf", BufkitModel.RRFS.url("KOKC"))
        assertEquals("https://meteor.geol.iastate.edu/data/bufkit/gfs/gfs3_kokc.buf", BufkitModel.GFS.url("KOKC"))
    }

    @Test fun distanceBetweenTwoPlaces() {
        assertEquals(0.0, Bufkit.distanceKm(35.0, -97.0, 35.0, -97.0), 1e-6)
        assertEquals(111.2, Bufkit.distanceKm(35.0, -97.0, 36.0, -97.0), 0.5)
    }

    @Test fun theDirectoryListingNamesTheStations() {
        val listing = """<a href="?C=N;O=D">Name</a><a href="gfs3_kokc.buf">gfs3_kokc.buf</a> <a href="gfs3_1v4.buf">x</a><a href="gfs3_kbuf.buf">y</a><a href="rrfs_kict.buf">z</a>"""
        assertEquals(setOf("KOKC", "1V4", "KBUF"), BufkitModel.GFS.stationsIn(listing))
        assertEquals(setOf("KICT"), BufkitModel.RRFS.stationsIn(listing))
        assertEquals(emptySet(), BufkitModel.GFS.stationsIn("nothing here"))
    }

    @Test fun theNearestStationsWithAFileComeNearestFirst() {
        val stations = listOf(
            NwsStation("KPWA", "Oklahoma City, Wiley Post", 35.53, -97.65), NwsStation("KOKC", "Oklahoma City, Will Rogers", 35.39, -97.60),
            NwsStation("KOUN", "Norman", 35.18, -97.44), NwsStation("KTIK", "Tinker AFB", 35.42, -97.39),
        )
        val got = Bufkit.nearest(stations, setOf("KOKC", "KOUN", "KTIK"), 35.40, -97.60)        // KPWA has no file
        assertEquals(listOf("KOKC", "KTIK", "KOUN"), got.map { it.id })
        assertTrue(got[0].km < 2.0 && got[0].km < got[1].km && got[1].km < got[2].km)
        assertEquals(1, Bufkit.nearest(stations, setOf("KOKC", "KOUN"), 35.40, -97.60, max = 1).size)
        assertEquals(emptyList(), Bufkit.nearest(stations, emptySet(), 35.40, -97.60))
    }
}
