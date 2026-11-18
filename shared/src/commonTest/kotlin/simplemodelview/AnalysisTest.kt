package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisTest {
    @Test
    fun fileNames() {
        val t = epochOf(2026, 11, 17, 12, 30, 0)
        assertEquals("https://noaa-rtma-pds.s3.amazonaws.com/rtma2p5_ru.20261117/rtma2p5_ru.t1230z.2dvaranl_ndfd.grb2", AnalysisBuckets.urls(AnalysisSource.RU, t).single())
        assertEquals("https://noaa-urma-pds.s3.amazonaws.com/urma2p5.20261117/urma2p5.t12z.2dvaranl_ndfd.grb2_wexp", AnalysisBuckets.urls(AnalysisSource.URMA, t).first())
        val old = epochOf(2015, 6, 1, 12, 0, 0)
        assertEquals("https://noaa-rtma-pds.s3.amazonaws.com/rtma2p5.20150601/rtma2p5.t12z.2dvaranl_ndfd.grb2_ext", AnalysisBuckets.urls(AnalysisSource.RTMA, old).first())
    }

    @Test
    fun framesRecentAndHistory() {
        val newest = epochOf(2026, 11, 17, 12, 30, 0)
        val recent = AnalysisBuckets.recentFrames(newest)
        assertEquals(25, recent.size)
        assertEquals(newest - 6 * 3600, recent.first().valid)
        assertTrue(recent.all { it.quarterHourly })
        val now = epochOf(2026, 11, 17, 12, 40, 0)
        val day = AnalysisBuckets.historyFrames(epochOf(2026, 11, 16, 12, 0, 0), epochOf(2026, 11, 17, 12, 0, 0), now)
        assertEquals(25, day.size)
        assertEquals(listOf(AnalysisSource.URMA, AnalysisSource.RTMA), day.first().sources, "old enough for URMA")
        assertEquals(AnalysisSource.RTMA, day.last().sources.first(), "too recent for URMA")
        val year = AnalysisBuckets.historyFrames(epochOf(2025, 1, 1, 0, 0, 0), epochOf(2025, 12, 31, 23, 0, 0), now)
        assertTrue(year.size <= 240 && year.size > 200)
    }

    private fun message(discipline: Int, category: Int, number: Int, surface: Int, length: Int): ByteArray {
        val b = ByteArray(length)
        "GRIB".encodeToByteArray().copyInto(b); b[6] = discipline.toByte(); b[7] = 2
        for (i in 0 until 8) b[8 + i] = (length.toLong() shr (8 * (7 - i))).toByte()
        b[16 + 3] = 34; b[16 + 4] = 4                       // section 4, 34 bytes
        b[16 + 9] = category.toByte(); b[16 + 10] = number.toByte(); b[16 + 22] = surface.toByte()
        return b
    }

    @Test
    fun headersNameTheParameters() {
        assertEquals("TMP" to 300L, analysisMessageHeader(message(0, 0, 0, 103, 300)))
        assertEquals("PRES" to 200L, analysisMessageHeader(message(0, 3, 0, 1, 200)))
        assertEquals(null, analysisMessageHeader(ByteArray(40)))
    }

    private class FileHttp(val files: Map<String, ByteArray>) : Http {
        val ranges = ArrayList<String>()
        override suspend fun get(url: String) = HttpResult(404, ByteArray(0))
        override suspend fun getRange(url: String, first: Long, lastInclusive: Long): HttpResult {
            ranges += "$url $first"
            val f = files[url] ?: return HttpResult(404, ByteArray(0))
            if (first >= f.size) return HttpResult(416, ByteArray(0))
            return HttpResult(206, f.copyOfRange(first.toInt(), minOf(f.size.toLong(), lastInclusive + 1).toInt()))
        }
    }

    private class SizeCore : Core by StubCore {
        override fun decodeGrib(message: ByteArray) = GridField(Grid(7, 1, 1), floatArrayOf(message.size.toFloat()))
    }

    @Test
    fun aLayoutIsLearnedOnceThenEachFieldIsOneRequest() = runTest {
        val file = message(0, 3, 5, 1, 900) + message(0, 0, 0, 103, 700) + message(0, 0, 6, 103, 600)
        val now = epochOf(2026, 11, 17, 14, 0, 0)
        val a = AnalysisFrame(epochOf(2026, 11, 17, 12, 0, 0), listOf(AnalysisSource.RTMA))
        val b = AnalysisFrame(epochOf(2026, 11, 17, 13, 0, 0), listOf(AnalysisSource.RTMA))
        val http = FileHttp(mapOf(AnalysisBuckets.urls(AnalysisSource.RTMA, a.valid).first() to file, AnalysisBuckets.urls(AnalysisSource.RTMA, b.valid).first() to file))
        val repo = AnalysisRepository(http, SizeCore())
        val first = assertNotNull(repo.load(a, listOf("TMP", "DPT"), now))
        assertEquals(listOf(700f, 600f), first.fields.map { it.values[0] })
        val before = http.ranges.size
        val second = assertNotNull(repo.load(b, listOf("TMP"), now))
        assertEquals(700f, second.fields[0].values[0])
        assertEquals(1, http.ranges.size - before, "a known layout: one request")
    }

    @Test
    fun missingSourceFallsBack() = runTest {
        val now = epochOf(2026, 11, 17, 23, 0, 0)
        val frame = AnalysisFrame(epochOf(2026, 11, 17, 12, 0, 0), listOf(AnalysisSource.URMA, AnalysisSource.RTMA))
        val http = FileHttp(mapOf(AnalysisBuckets.urls(AnalysisSource.RTMA, frame.valid).first() to message(0, 0, 0, 103, 400)))
        val read = assertNotNull(AnalysisRepository(http, SizeCore()).load(frame, listOf("TMP"), now))
        assertEquals(AnalysisSource.RTMA, read.source)
    }
}
