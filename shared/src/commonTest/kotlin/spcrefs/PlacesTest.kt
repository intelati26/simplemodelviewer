package spcrefs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun pointSeriesSamplesTheRightCellAtEveryTime() = runTest {
        val meta = """{"metadata":{
          "cref_max/.zarray":{"shape":[3,1059,1799],"chunks":[1,1059,1799],"dtype":"<f2","fill_value":"NaN","compressor":{"id":"blosc"}},
          "cref_max/.zattrs":{"_ARRAY_DIMENSIONS":["valid_time","y","x"],"units":"dBz"},
          "valid_time/.zarray":{"shape":[3],"chunks":[1],"dtype":"<i8","fill_value":0,"compressor":null}}}"""
        val http = object : Http {
            override suspend fun get(url: String): HttpResult {
                if (url.endsWith("/valid_time/0")) return HttpResult(200, ByteArray(8) { if (it == 0) 0 else 0 })
                if (url.contains("/valid_time/")) return HttpResult(200, ByteArray(8) { if (it == 0) url.last().digitToInt().toByte() else 0 })
                // chunk "t.0.0": the body's first byte is t
                val t = url.substringAfterLast("/").substringBefore(".").toInt()
                return HttpResult(200, byteArrayOf(t.toByte()))
            }
        }
        val core = object : Core by StubCore {
            // value at every cell = first byte, except one cell (col 3, row 3) which is 10 + that
            override fun decodeFloats(raw: ByteArray, dtype: String, compressed: Boolean, count: Int) =
                FloatArray(count) { raw[0].toFloat() }.also { it[3 * GRID_COLUMNS + 3] = 10f + raw[0] }
            override fun latLonToGrid(lat: Double, lon: Double) = doubleArrayOf(3.4, 2.6)   // rounds to (3, 3)
        }
        val repo = RefsRepository(http, core)
        val arrays = parseZMetadata(meta)
        val store = CycleStore(Cycle(2026, 10, 1, 12), arrays, Catalogue.build(arrays.values))
        val progress = mutableListOf<Float>()
        val series = repo.pointSeries(store, store.products.first(), 0, 35.0, -97.0, core) { progress += it }
        assertEquals(listOf(10.0, 11.0, 12.0), series.values)
        assertEquals("dBZ", series.units)
        assertEquals(12.0, series.max)
        assertEquals(2, series.peakIndex)
        assertTrue(progress.last() == 1f)
    }
}
