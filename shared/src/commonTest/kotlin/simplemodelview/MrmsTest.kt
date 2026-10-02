package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrmsTest {
    private val cref = MrmsCatalog.default

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

    @Test fun catalogIsConsistent() {
        assertEquals(MrmsCatalog.all.size, MrmsCatalog.all.map { it.id }.toSet().size)
        for (p in MrmsCatalog.all) {
            assertTrue(p.group in MrmsCatalog.groups, p.id)
            assertTrue(p.style.stops.isNotEmpty(), p.id)
            if (p.style.kind != Kind.Categorical) assertTrue(p.style.stops.zipWithNext().all { (a, b) -> a.value < b.value }, p.id)
            else assertEquals(p.style.stops.size, p.style.labels.size, p.id)
            assertTrue(p.frames > 1 && p.cadenceMinutes > 0, p.id)
        }
        // the three products asked for first, with their server directory names
        assertEquals("MergedReflectivityQCComposite_00.50", MrmsCatalog.default.id)
        assertTrue(MrmsCatalog.byId("RotationTrack60min_00.50") != null)
        assertTrue(MrmsCatalog.byId("MultiSensor_QPE_24H_Pass2_00.00") != null)
        assertNull(MrmsCatalog.byId("nope"))
        // accumulations are stored in mm and shown in inches
        assertEquals(25.4 * 1.0 / 25.4, 1.0, 1e-12)
        assertEquals(1.0, MrmsCatalog.byId("MultiSensor_QPE_24H_Pass2_00.00")!!.style.factor * 25.4, 1e-12)
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

    @Test fun unitsConvertStylesSensibly() {
        val us = Units()
        val metric = Units(metricLength = true, celsius = true, kilometers = true)
        for (p in MrmsCatalog.all) {
            assertEquals(p.style, p.styleIn(us), p.id)                         // US units are the catalog's own
            val m = p.styleIn(metric)
            if (p.quantity == Quantity.None) assertEquals(p.style, m, p.id) else assertTrue(m != p.style, p.id)
            if (m.kind != Kind.Categorical) assertTrue(m.stops.zipWithNext().all { (a, b) -> a.value < b.value }, "${p.id} thresholds stay ordered")
            assertEquals(p.style.stops.size, m.stops.size, p.id)
        }
        // 24 h rain: the same stored mm, now shown as mm; the first band starts at 0.25 mm (0.01 in) and 1 in is 25 mm
        val rain = MrmsCatalog.byId("MultiSensor_QPE_24H_Pass2_00.00")!!
        val inches = rain.styleIn(us)
        val mm = rain.styleIn(Units(metricLength = true))
        assertEquals("in", inches.units); assertEquals("mm", mm.units)
        assertEquals(1.0, mm.factor, 1e-12)                                    // stored in mm
        assertEquals(0.25, mm.stops.first().value, 1e-9)
        assertTrue(mm.stops.any { it.value == 25.0 })
        // a rate keeps its "per hour"
        assertEquals("mm/hr", MrmsCatalog.byId("PrecipRate_00.00")!!.styleIn(Units(metricLength = true)).units)
        // echo tops: stored in km, shown in kft by default and in km on request (the factor becomes 1)
        val top = MrmsCatalog.byId("EchoTop_30_00.50")!!
        assertEquals("kft", top.styleIn(us).units); assertEquals("km", top.styleIn(Units(kilometers = true)).units)
        assertEquals(1.0, top.styleIn(Units(kilometers = true)).factor, 1e-9)
        // temperature: stored in C; F = C * 1.8 + 32 by default, plain C on request
        val temp = MrmsCatalog.byId("Model_SurfaceTemp_00.50")!!
        assertEquals(1.8, temp.styleIn(us).factor, 1e-9); assertEquals(32.0, temp.styleIn(us).offset, 1e-9)
        val c = temp.styleIn(Units(celsius = true))
        assertEquals(1.0, c.factor, 1e-9); assertEquals(0.0, c.offset, 1e-9); assertEquals("C", c.units)
        assertTrue(c.stops.any { it.value == 0.0 })                            // 32 F is 0 C
    }

    @Test fun precipitationTypeIsCategorical() {
        val p = MrmsCatalog.byId("PrecipFlag_00.00")!!
        assertEquals(Kind.Categorical, p.style.kind)
        assertEquals(Pool.Mode, p.pool)
        assertEquals(setOf(96.0, 91.0, 10.0, 7.0, 6.0, 3.0, 1.0), p.style.stops.map { it.value }.toSet())
        assertEquals("Hail", p.style.labels[p.style.stops.indexOfFirst { it.value == 7.0 }])
        // the read-out names the category
        val grid = Grid(2, 2, 1)
        val product = Product(p.id, p.title, p.group, p.style, false, null, grid)
        val core = object : Core by StubCore { override fun gridToLatLon(grid: Grid, column: Double, row: Double) = doubleArrayOf(35.0, -97.0) }
        assertTrue("Convective" in readout(core, product, floatArrayOf(6f, 0f), emptyList(), 0, 0))
        assertTrue("Tropical stratiform" in readout(core, product, floatArrayOf(91f, 0f), emptyList(), 0, 0))
        assertTrue("none" in readout(core, product, floatArrayOf(91f, 0f), emptyList(), 1, 0))
        assertTrue("code 4" in readout(core, product, floatArrayOf(4f, 0f), emptyList(), 0, 0))
        assertTrue("no data" in readout(core, product, floatArrayOf(Float.NaN, 0f), emptyList(), 0, 0))
    }

    @Test fun mosaicHeightsAreTheBucketsThirtyThreeDirectories() {
        val levels = MrmsCatalog.mosaicLevels
        assertEquals(33, levels.size)
        assertEquals(0.5, levels.first(), 0.0); assertEquals(19.0, levels.last(), 0.0)
        assertEquals(levels.sorted(), levels)
        val names = levels.map(::levelName)
        assertEquals(listOf("00.50", "00.75", "01.00", "01.25"), names.take(4))
        assertEquals(listOf("03.00", "03.50", "04.00"), names.subList(10, 13))
        assertEquals(listOf("09.00", "10.00", "11.00"), names.subList(22, 25))
        assertEquals("19.00", names.last())
        assertEquals(33, names.toSet().size)
    }

    @Test fun aLevelProductNamesItsDirectoryAndHeight() {
        val z = MrmsCatalog.byId("MergedZdr")!!
        assertEquals(33, z.levels.size)
        assertEquals("MergedZdr_03.00", z.directory)                         // starts at 3 km
        val low = z.copy(level = 0)
        assertEquals("MergedZdr_00.50", low.directory)
        assertEquals("0.5 km (1.6 kft)", low.levelLabel)
        assertEquals("3.0 km (9.8 kft)", z.levelLabel)
        assertEquals("0.75 km (2.5 kft)", z.copy(level = 1).levelLabel)
        assertEquals("19.0 km (62.3 kft)", z.copy(level = 32).levelLabel)
        assertEquals("3D Differential Reflectivity, ZDR (by height), 3.0 km (9.8 kft)", z.displayTitle)
        assertEquals("MergedReflectivityQC_19.00", MrmsCatalog.byId("MergedReflectivityQC")!!.copy(level = 99).copy(level = 32).directory)
        // an ordinary product is its own directory and has no height
        val cref = MrmsCatalog.default
        assertEquals(cref.id, cref.directory); assertEquals("", cref.levelLabel); assertEquals(cref.title, cref.displayTitle)
    }

    @Test fun listingUsesTheChosenHeightsDirectory() = runTest {
        var url = ""
        val http = object : Http { override suspend fun get(url0: String): HttpResult { if (url.isEmpty()) url = url0; return HttpResult(200, listing().encodeToByteArray()) } }
        val rhohv = MrmsCatalog.byId("MergedRhoHV")!!.copy(level = MrmsCatalog.mosaicLevels.indexOf(10.0))
        MrmsRepository(http, StubCore).listFiles(rhohv, 1790946100L)
        assertTrue("prefix=CONUS/MergedRhoHV_10.00/" in url, url)
        assertTrue("start-after=CONUS/MergedRhoHV_10.00/20261002/MRMS_MergedRhoHV_10.00_" in url, url)
    }
}
