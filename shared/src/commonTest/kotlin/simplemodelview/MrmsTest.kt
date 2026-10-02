package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrmsTest {
    private val cref = MrmsCatalogue.default

    private fun listing(vararg stamps: Pair<String, String>, product: String = cref.id) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Name>noaa-mrms-pds</Name>""")
        for ((stamp, uploaded) in stamps) {
            val day = stamp.substringBefore('-')
            append("<Contents><Key>CONUS/$product/$day/MRMS_${product}_$stamp.grib2.gz</Key><LastModified>$uploaded</LastModified>")
            append("<ETag>&quot;abc&quot;</ETag><Size>1538970</Size><StorageClass>INTELLIGENT_TIERING</StorageClass></Contents>")
        }
        append("</ListBucketResult>")
    }

    @Test fun validTimeFromKey() {
        // 2026-10-02 13:00:39 UTC
        val expected = daysFromCivil(2026, 10, 2) * 86400 + 13 * 3600 + 39
        assertEquals(expected, mrmsValidTime("CONUS/X/20261002/MRMS_X_00.50_20261002-130039.grib2.gz"))
        assertEquals(1790946039L, expected)       // matches GRIB_VALID_TIME that GDAL reported for the real file
        assertNull(mrmsValidTime("CONUS/X/20261002/MRMS_X.latest.grib2.gz"))
        assertNull(mrmsValidTime("CONUS/X/20261002/MRMS_X_2026100a-130039.grib2.gz"))
    }

    @Test fun civilRoundTrip() {
        for (t in listOf(0L, 1790946039L, 951782400L, 1709251199L)) {
            val c = civilFromEpoch(t)
            assertEquals(t, epochOf(c[0], c[1], c[2], c[3], c[4], c[5]))
        }
        assertEquals(listOf(2026, 10, 2, 13, 0, 39), civilFromEpoch(1790946039L).toList())
    }

    @Test fun parsesListing() {
        val xml = listing("20261002-125840" to "2026-10-02T12:59:22.000Z", "20261002-130039" to "2026-10-02T13:01:25.000Z")
        val files = parseMrmsListing(xml)
        assertEquals(2, files.size)
        assertEquals(1790946039L, files[1].validTime)
        assertEquals(1790946085L, files[1].uploaded)          // upload 46 s after the scan
        assertEquals(1538970L, files[1].size)
        assertEquals("https://noaa-mrms-pds.s3.amazonaws.com/CONUS/${cref.id}/20261002/MRMS_${cref.id}_20261002-130039.grib2.gz", files[1].url)
    }

    @Test fun listingIsOrderedAndSkipsOtherKeys() {
        val xml = listing("20261002-130039" to "2026-10-02T13:01:25.000Z", "20261002-125840" to "2026-10-02T12:59:22.000Z") +
            "<Contents><Key>CONUS/${cref.id}/readme.txt</Key><LastModified>2026-10-02T00:00:00.000Z</LastModified><Size>3</Size></Contents>"
        assertEquals(listOf(1790945920L, 1790946039L), parseMrmsListing(xml).map { it.validTime })
        assertEquals(0, parseMrmsListing("<ListBucketResult></ListBucketResult>").size)
        assertEquals(0, parseMrmsListing("not xml at all").size)
    }

    @Test fun listsOnlyAfterTheWindowAndKeepsTheNewest() = runTest {
        val requested = ArrayList<String>()
        val now = 1790946100L     // 13:01:40
        val stamps = (0 until 40).map { i ->
            val c = civilFromEpoch(now - 60 - i * 120L)
            fun p(n: Int) = n.toString().padStart(2, '0')
            "${c[0]}${p(c[1])}${p(c[2])}-${p(c[3])}${p(c[4])}${p(c[5])}" to "2026-10-02T13:01:25.000Z"
        }.reversed()
        val http = object : Http {
            override suspend fun get(url: String): HttpResult { requested += url; return HttpResult(200, listing(*stamps.toTypedArray()).encodeToByteArray()) }
        }
        val files = MrmsRepository(http, StubCore).listFiles(cref, now)
        assertEquals(cref.frames, files.size)
        assertEquals(stamps.last().first.let { mrmsValidTime("x_$it.grib2.gz") }, files.last().validTime)   // the newest
        assertEquals(1, requested.size)
        val url = requested.single()
        assertTrue("prefix=CONUS/${cref.id}/" in url)
        // starts (frames + 3) scans back: 33 x 2 min before 13:01:40 = 11:55:40
        assertTrue("start-after=CONUS/${cref.id}/20261002/MRMS_${cref.id}_20261002-115540.grib2.gz" in url, url)
    }

    @Test fun listAcrossMidnightNamesThePreviousDay() = runTest {
        var first = ""
        val http = object : Http { override suspend fun get(url: String): HttpResult { if (first.isEmpty()) first = url; return HttpResult(200, listing().encodeToByteArray()) } }
        val now = epochOf(2026, 10, 2, 0, 20, 0)
        MrmsRepository(http, StubCore).listFiles(cref, now)
        assertTrue("start-after=CONUS/${cref.id}/20261001/MRMS_${cref.id}_20261001-23" in first, first)
    }

    @Test fun emptyListingTriesAWiderWindowThenGivesUp() = runTest {
        var calls = 0
        val http = object : Http { override suspend fun get(url: String): HttpResult { calls += 1; return HttpResult(200, listing().encodeToByteArray()) } }
        assertEquals(emptyList(), MrmsRepository(http, StubCore).listFiles(cref, 1790946100L))
        assertEquals(2, calls)
    }

    @Test fun listingFailureIsAnError() = runTest {
        val http = object : Http { override suspend fun get(url: String) = HttpResult(503, ByteArray(0)) }
        assertFailsWith<RefsException> { MrmsRepository(http, StubCore).listFiles(cref, 1790946100L) }
    }

    @Test fun stepKeepsAWindowAboutTheTargetWidth() {
        assertEquals(3, mrmsStep(0.01, -125.0, -66.5, 2000, false))      // CONUS: 5850 cells -> 1950 wide
        assertEquals(1, mrmsStep(0.01, -98.1, -84.9, 2000, false))       // a meso sector keeps every 1 km cell
        assertEquals(6, mrmsStep(0.005, -125.0, -66.5, 2000, false))     // the 500 m rotation grid
        assertEquals(6, mrmsStep(0.01, -125.0, -66.5, 2000, true))       // data saver halves the width again
    }

    @Test fun catalogueIsConsistent() {
        assertEquals(MrmsCatalogue.all.size, MrmsCatalogue.all.map { it.id }.toSet().size)
        for (p in MrmsCatalogue.all) {
            assertTrue(p.group in MrmsCatalogue.groups, p.id)
            assertTrue(p.style.stops.isNotEmpty() && p.style.stops.zipWithNext().all { (a, b) -> a.value < b.value }, p.id)
            assertTrue(p.frames > 1 && p.cadenceMinutes > 0, p.id)
        }
        // the three products asked for first, with their server directory names
        assertEquals("MergedReflectivityQCComposite_00.50", MrmsCatalogue.default.id)
        assertTrue(MrmsCatalogue.byId("RotationTrack60min_00.50") != null)
        assertTrue(MrmsCatalogue.byId("MultiSensor_QPE_24H_Pass2_00.00") != null)
        assertNull(MrmsCatalogue.byId("nope"))
        // accumulations are stored in mm and shown in inches
        assertEquals(25.4 * 1.0 / 25.4, 1.0, 1e-12)
        assertEquals(1.0, MrmsCatalogue.byId("MultiSensor_QPE_24H_Pass2_00.00")!!.style.factor * 25.4, 1e-12)
    }

    @Test fun downloadErrorsAreReadable() = runTest {
        val file = MrmsFile("CONUS/x/20261002/MRMS_x_20261002-130039.grib2.gz", 1790946039L, null, 1L)
        val gone = object : Http { override suspend fun get(url: String) = HttpResult(404, ByteArray(0)) }
        val e = assertFailsWith<RefsException> { MrmsRepository(gone, StubCore).loadFrame(cref, file, -100.0, 30.0, -90.0, 40.0, 1) }
        assertTrue("no longer" in e.message!!)
        var calls = 0
        val flaky = object : Http { override suspend fun get(url: String): HttpResult { calls += 1; return if (calls == 1) HttpResult(500, ByteArray(0)) else HttpResult(200, ByteArray(8)) } }
        val frame = MrmsRepository(flaky, StubCore).loadFrame(cref, file, -100.0, 30.0, -90.0, 40.0, 1)
        assertEquals(2, calls)                      // one retry
        assertEquals(file, frame.file)
    }
}
