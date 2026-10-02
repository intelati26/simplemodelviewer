package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockTest {
    private val t = Cycle(2026, 10, 1, 18).epochSeconds + 25 * 60       // 2026-10-01 18:25Z

    @Test fun clockText() {
        assertEquals("18:25Z", formatClock(t))
        assertEquals("18:25Z", formatClock(t, now = t + 3600))                 // same UTC day: no date
        assertEquals("10-01 18:25Z", formatClock(t, now = t + 6 * 3600))       // the next UTC day: say which day
        assertEquals("12-31 23:59Z", formatClock(Cycle(2026, 12, 31, 23).epochSeconds + 59 * 60, now = Cycle(2027, 1, 1, 3).epochSeconds))
        assertEquals("00:00Z", formatClock(Cycle(2026, 10, 2, 0).epochSeconds))
    }

    @Test fun ageText() {
        assertEquals("<1 min", formatAge(20))
        assertEquals("12 min", formatAge(12 * 60 + 5))
        assertEquals("4 h 04 min", formatAge(4 * 3600 + 4 * 60))
        assertEquals("<1 min", formatAge(-50))
    }

    @Test fun plusFromAValidOrCycleTime() {
        val v13 = Cycle(2026, 10, 1, 13).epochSeconds
        assertEquals("13Z+1:05", formatPlus(v13, 65 * 60))
        assertEquals("12Z+4:36", formatPlus(Cycle(2026, 10, 1, 12).epochSeconds, 4 * 3600 + 36 * 60 + 20))
        assertEquals("10Z-36:51", formatPlus(Cycle(2026, 10, 3, 10).epochSeconds, -(36 * 3600 + 51 * 60)))   // uploaded before a future valid time
        assertEquals("00Z+0:00", formatPlus(Cycle(2026, 10, 2, 0).epochSeconds, 0))
        assertEquals("09Z+10:05", formatPlus(Cycle(2026, 10, 2, 9).epochSeconds, 10 * 3600 + 5 * 60))
    }

    @Test fun frameCarriesTheFileTimeAndMetadataTimeIsKept() = runTest {
        val meta = """{"metadata":{"cref_max/.zarray":{"shape":[3,1059,1799],"chunks":[1,1059,1799],"dtype":"<f2","fill_value":"NaN","compressor":{"id":"blosc"}},
          "cref_max/.zattrs":{"_ARRAY_DIMENSIONS":["valid_time","y","x"],"units":"dBz"}}}"""
        val http = object : Http {
            override suspend fun get(url: String): HttpResult = when {
                url.endsWith("/.zmetadata") && "grefs" !in url -> HttpResult(200, meta.encodeToByteArray(), 1000L)
                url.contains("grefs") -> HttpResult(404, ByteArray(0))
                url.endsWith("cref_max/1.0.0") -> HttpResult(200, byteArrayOf(1), 1234L)
                else -> HttpResult(404, ByteArray(0))
            }
        }
        val repo = RefsRepository(http, StubCore)
        val store = repo.open(Cycle(2026, 10, 1, 18))
        assertEquals(1000L, store.metadataTime)
        val product = store.products.first()
        assertEquals(1234L, repo.readFrameTimed(store, product, 1, 0).fileTime)
        assertNull(repo.readFrameTimed(store, product, 2, 0).fileTime)             // never written: no time, NaN frame
    }
}
