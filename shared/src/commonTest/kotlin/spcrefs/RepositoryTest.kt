package spcrefs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val zmeta = """
{"metadata":{
 ".zattrs":{},
 "cref_max/.zarray":{"shape":[19,1059,1799],"chunks":[1,1059,1799],"dtype":"<f2","fill_value":"NaN","compressor":{"id":"blosc"}},
 "cref_max/.zattrs":{"_ARRAY_DIMENSIONS":["valid_time","y","x"],"units":"dBz"},
 "uh25_04h_max_overlap_pb_t>99.85p/.zarray":{"shape":[10,19,1059,1799],"chunks":[1,1,1059,1799],"dtype":"<i4","fill_value":0,"compressor":{"id":"blosc"}},
 "uh25_04h_max_overlap_pb_t>99.85p/.zattrs":{"_ARRAY_DIMENSIONS":["member","valid_time","y","x"],"units":"dimensionless"},
 "latitude/.zarray":{"shape":[1059,1799],"chunks":[1059,1799],"dtype":"<f8","fill_value":"NaN","compressor":{"id":"blosc"}},
 "latitude/.zattrs":{"_ARRAY_DIMENSIONS":["y","x"]},
 "valid_time/.zarray":{"shape":[19],"chunks":[1],"dtype":"<f8","fill_value":"NaN","compressor":null}
}}
""".trimIndent()

class MetaTest {
    @Test fun parsesArrays() {
        val a = parseZMetadata(zmeta)
        assertEquals(4, a.size)
        val c = a.getValue("cref_max")
        assertEquals(listOf(19, 1059, 1799), c.shape)
        assertTrue(c.compressed && c.isGridField && c.fillValue!!.isNaN())
        assertEquals("dBz", c.units)
        assertEquals(listOf("valid_time", "y", "x"), c.dims)
    }

    @Test fun catalogueKeepsGridFieldsOnly() {
        val products = Catalogue.build(parseZMetadata(zmeta).values)
        assertEquals(listOf("cref_max", "uh25_04h_max_overlap_pb_t>99.85p"), products.map { it.id })
        assertEquals("Composite Reflectivity (ensemble maximum)", products[0].title)
        assertEquals(Kind.Banded, products[0].style.kind)
        assertEquals(10.0, products[0].style.hideBelow)
        assertEquals(Kind.Paintball, products[1].style.kind)
        assertTrue(products[1].hasMembers)
        assertEquals("valid_time", products[1].timeDimension)
    }

    @Test fun styles() {
        assertEquals(-1.0, Catalogue.styleFor("uh25min_04h_min_overlap_min", "meter ** 2 / second ** 2").factor)
        assertEquals(100.0, Catalogue.styleFor("qpf_06h_acc_prob_t>001", "dimensionless").factor)
        assertEquals(0.01, Catalogue.styleFor("pmsl_mean", "pascal").factor)
        assertEquals("kt", Catalogue.styleFor("gust_mean", "knot").units)
    }

    @Test fun chunkKeys() = assertEquals("2.5.0.0", chunkKey(listOf(2, 5, 0, 0)))

    @Test fun timeFormatting() {
        val c = Cycle(2026, 10, 1, 18)
        assertEquals(1790877600L, c.epochSeconds)
        assertEquals("Thu 01 Oct 18Z", formatUtc(c.epochSeconds))
        assertEquals("Fri 02 Oct 00Z", formatUtc(c.epochSeconds + 6 * 3600))
    }

    @Test fun missingMetadataIsReported() = runTest {
        val http = object : Http { override suspend fun get(url: String) = HttpResult(404, ByteArray(0)) }
        val repo = RefsRepository(http, StubCore)
        val e = runCatching { repo.open(Cycle(2026, 10, 1, 18)) }.exceptionOrNull()
        assertTrue(e is RefsException && "404" in e.message!!)
    }

    @Test fun findsCyclesFromNow() = runTest {
        val have = setOf(Cycle(2026, 10, 1, 12)).map { it.storeUrl + "/.zgroup" }.toSet()
        val http = object : Http { override suspend fun get(url: String) = HttpResult(if (url in have) 200 else 404, ByteArray(0)) }
        val repo = RefsRepository(http, StubCore)
        // 2026-10-01 17:30 UTC -> latest possible cycle is 12Z
        val now = Cycle(2026, 10, 1, 17).epochSeconds + 1800
        assertEquals(listOf(Cycle(2026, 10, 1, 12)), repo.findCycles(now, wanted = 1))
    }
}

internal object StubCore : Core {
    override fun inflate(raw: ByteArray) = raw
    override fun decodeFloats(raw: ByteArray, dtype: String, compressed: Boolean, count: Int) = FloatArray(count)
    override fun latLonToGrid(lat: Double, lon: Double) = doubleArrayOf(0.0, 0.0)
    override fun gridToLatLon(column: Double, row: Double) = doubleArrayOf(0.0, 0.0)
    override fun render(values: FloatArray, view: View, style: Style, memberRgb: IntArray, argb: IntArray) {}
    override fun renderGlobe(values: FloatArray, width: Int, height: Int, radius: Double, centerLat: Double, centerLon: Double, stride: Int,
                             domainArgb: Int, style: Style, memberRgb: IntArray, argb: IntArray) {}
}
