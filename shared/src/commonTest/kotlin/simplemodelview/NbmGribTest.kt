package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NbmGribTest {
    private val idx = """
        1:0:d=2026110423:APTMP:2 m above ground:12 hour fcst:
        2:2087870:d=2026110423:CDCB:reserved:12 hour fcst:
        35:38753999:d=2026110423:DPT:2 m above ground:12 hour fcst:
        36:40602406:d=2026110423:DPT:2 m above ground:12 hour fcst:ens std dev
        133:129432216:d=2026110423:TMP:2 m above ground:12 hour fcst:
        134:131308057:d=2026110423:TMP:2 m above ground:12 hour fcst:ens std dev
        140:141234789:d=2026110423:VIS:surface:12 hour fcst:prob <1609.34:prob fcst 2/6:probability forecast
        garbage line
    """.trimIndent()

    @Test fun indexIsParsedAndAMessageFoundByNameAndHour() {
        val entries = parseGribIndex(idx)
        assertEquals(7, entries.size)
        val temp = NbmGribFields.byId.getValue("temp")
        assertEquals(129432216L to 131308056L, findGribMessage(entries, temp, 12))       // up to the byte before the next message
        assertNull(findGribMessage(entries, temp, 13))                                    // not that hour
        assertEquals(38753999L to 40602405L, findGribMessage(entries, NbmGribFields.byId.getValue("dewpoint"), 12))
        assertNull(findGribMessage(entries, NbmGribFields.byId.getValue("vis"), 12))      // only a probability of it is in the index: not the field
        assertEquals(0L to 2087869L, findGribMessage(entries, NbmGribFields.byId.getValue("appttemp"), 12))
    }

    @Test fun unitsAreTheGeoTiffsUnits() {
        assertEquals(32f, NbmGribFields.byId.getValue("temp").convert(273.15f), 1e-3f)
        assertEquals(212f, NbmGribFields.byId.getValue("dewpoint").convert(373.15f), 1e-2f)
        assertEquals(19.44f, NbmGribFields.byId.getValue("windspd").convert(10f), 1e-2f)
        assertEquals(1.0f, NbmGribFields.byId.getValue("pwat").convert(25.4f), 1e-5f)
        assertEquals(1.0f, NbmGribFields.byId.getValue("vis").convert(1609.344f), 1e-5f)
        assertEquals(10.0f, NbmGribFields.byId.getValue("ceil").convert(304.8f), 1e-4f)
        // every GRIB-backed field is a product of the catalog
        assertTrue(NbmGribFields.byId.keys.all { NbmCatalog.byId(it) != null })
    }

    @Test fun runHoursAndFramesAreRead() {
        val days = "<ListBucketResult><CommonPrefixes><Prefix>blend.20261104/21/</Prefix></CommonPrefixes><CommonPrefixes><Prefix>blend.20261104/23/</Prefix></CommonPrefixes></ListBucketResult>"
        assertEquals(listOf(21, 23), parseGribRunHours(days))
        val cycle = NbmCycle(2026, 11, 4, 23, NbmGribBucket.VERSION)
        assertTrue(cycle.grib)
        assertEquals("blend.20261104/23/core/", cycle.prefix)
        val listing = "<ListBucketResult>" + listOf("001", "002", "049", "052").joinToString("") {
            "<Contents><Key>blend.20261104/23/core/blend.t23z.core.f$it.co.grib2</Key><LastModified>2026-11-04T23:40:00.000Z</LastModified><Size>1000</Size></Contents>" +
                "<Contents><Key>blend.20261104/23/core/blend.t23z.core.f$it.co.grib2.idx</Key><LastModified>2026-11-04T23:40:00.000Z</LastModified><Size>10</Size></Contents>"
        } + "</ListBucketResult>"
        val frames = parseGribListing(listing, cycle, "temp")
        assertEquals(listOf(1, 2, 49, 52), frames.map { it.leadHours })
        assertTrue(frames.all { it.grib && it.field == "temp" })
        assertEquals("https://noaa-nbm-grib2-pds.s3.amazonaws.com/blend.20261104/23/core/blend.t23z.core.f001.co.grib2", frames[0].url)
    }

    private class FakeHttp(val handler: (String, String?) -> HttpResult) : Http {
        val urls = ArrayList<String>()
        override suspend fun get(url: String): HttpResult { urls += url; return handler(url, null) }
        override suspend fun getRange(url: String, first: Long, lastInclusive: Long): HttpResult { urls += "$url [$first-$lastInclusive]"; return handler(url, "$first-$lastInclusive") }
    }

    private class GribCore : Core by StubCore {
        override fun decodeGrib(message: ByteArray) = GridField(Grid(7, 2, 1), floatArrayOf(273.15f, Float.NaN))
    }

    @Test fun aGribFrameIsOneRangeRequestDecodedAndConverted() = runTest {
        val http = FakeHttp { url, range ->
            when {
                url.endsWith(".idx") -> HttpResult(200, idx.encodeToByteArray())
                range != null -> HttpResult(206, ByteArray(10) { 1 })
                else -> HttpResult(404, ByteArray(0))
            }
        }
        val file = NbmFile("blend.20261104/23/core/blend.t23z.core.f012.co.grib2", NbmCycle(2026, 11, 4, 23).epoch, NbmCycle(2026, 11, 4, 23).epoch + 12 * 3600, null, 200_000_000L, grib = true, field = "temp")
        val field = NbmRepository(http, GribCore()).loadFrame(file)
        assertEquals(32f, field.values[0], 1e-3f)
        assertTrue(field.values[1].isNaN())
        assertEquals(7, field.grid.id)
        assertTrue(http.urls.any { it.endsWith("[129432216-131308056]") })
        // a field that is not in the GRIB files, and an hour without the message
        assertFailsWith<RefsException> { NbmRepository(http, GribCore()).loadFrame(file.copy(field = "pop06")) }
        assertFailsWith<RefsException> { NbmRepository(http, GribCore()).loadFrame(NbmFile(file.key.replace("f012", "f013"), file.run, file.run + 13 * 3600, null, 1L, grib = true, field = "temp")) }
    }

    @Test fun newerGribRunsGoOnTopWhenTheRenderedFilesAreBehind() = runTest {
        val now = epochOf(2026, 11, 5, 16, 0, 0)
        val http = FakeHttp { url, _ ->
            when {
                !url.contains("prefix=") -> HttpResult(200, "<ListBucketResult><CommonPrefixes><Prefix>blendv5.0/</Prefix></CommonPrefixes></ListBucketResult>".encodeToByteArray())
                "blendv5.0/conus/2026/11/03/" in url -> HttpResult(200, "<ListBucketResult><CommonPrefixes><Prefix>blendv5.0/conus/2026/11/03/1100/</Prefix></CommonPrefixes></ListBucketResult>".encodeToByteArray())
                url.startsWith(NbmGribBucket.URL) && "prefix=blend.20261105/" in url -> HttpResult(200, "<ListBucketResult><CommonPrefixes><Prefix>blend.20261105/14/</Prefix></CommonPrefixes><CommonPrefixes><Prefix>blend.20261105/15/</Prefix></CommonPrefixes></ListBucketResult>".encodeToByteArray())
                else -> HttpResult(404, ByteArray(0))
            }
        }
        val cycles = NbmRepository(http, StubCore).listCycles(now, hours = 60)
        assertEquals(listOf(15 to true, 14 to true, 11 to false), cycles.map { it.hour to it.grib })
        // fresh rendered files: the GRIB bucket is not even asked
        val fresh = FakeHttp { url, _ ->
            if (!url.contains("prefix=")) HttpResult(200, "<ListBucketResult><CommonPrefixes><Prefix>blendv5.0/</Prefix></CommonPrefixes></ListBucketResult>".encodeToByteArray())
            else if ("blendv5.0/conus/2026/11/05/" in url) HttpResult(200, "<ListBucketResult><CommonPrefixes><Prefix>blendv5.0/conus/2026/11/05/1500/</Prefix></CommonPrefixes></ListBucketResult>".encodeToByteArray())
            else HttpResult(404, ByteArray(0))
        }
        assertEquals(listOf(false), NbmRepository(fresh, StubCore).listCycles(now, hours = 12).map { it.grib })
        assertTrue(fresh.urls.none { it.startsWith(NbmGribBucket.URL) })
        assertNotNull(cycles.first().epoch)
    }
}
