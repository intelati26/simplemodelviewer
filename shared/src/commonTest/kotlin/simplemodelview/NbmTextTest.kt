package simplemodelview

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real blocks of NOAA's bulletins for Oklahoma City (the 12Z cycle of 2026-11-12), and a stand-in file with the real file's shape for the one-station fetch. */
class NbmTextTest {
private val kokcNbe = """
 KOKC    NBM  NBE GUIDANCE    11/12/2026  1200 UTC
   FRI 13 |SAT 14 |SUN 15 |MON 16 |TUE 17 |WED 18 |THU 19 |FRI 20  CLIMO 
 UTC    12| 00  12| 00  12| 00  12| 00  12| 00  12| 00  12| 00  12       
 FHR    24| 36  48| 60  72| 84  96|108 120|132 144|156 168|180 192       
 TXN    40| 56  36| 56  38| 49  37| 52  38| 56  41| 59  47| 66  50  38 60
 XND     3|  3   3|  3   4|  6   3|  3   4|  3   4|  4   4|  4   6
 TMP    41| 50  38| 52  42| 45  40| 48  42| 51  45| 56  51| 60  54
 TSD     3|  3   3|  4   4|  4   3|  2   4|  3   5|  4   5|  4   6
 DPT    33| 32  29| 35  41| 41  40| 41  41| 42  43| 49  51| 54  53
 DSD     4|  4   4|  5   5|  5   4|  5   5|  5   5|  5   5|  5   7
 WBG    38| 44  35| 46  41| 44  40| 45  41| 47  44| 52  50| 57  53
 SKY     2| 10  74| 73  91| 82  78| 58  72| 48  59| 64  69| 49  71
 SSD     4| 23  22| 22   8| 13  14| 22  17| 27  15| 19  17| 24  13
 WDR     5|  7  10| 13  13|  9   8| 13  16| 16  17| 17  17| 16  16
 WSP     6|  7   7| 11  14| 10   4|  4   5|  8  10| 13  10|  9   8
 WSD     1|  1   2|  3   4|  4   3|  3   3|  3   3|  4   3|  4   4
 GST     9|  9   9| 17  19| 14   5|  5   6| 12  14| 21  14| 15  12
 GSD     2|  3   2|  6   7|  7   4|  4   3|  6   5|  8   4|  8   6
 P12     1|  0   0|  5  69| 87  18|  1   0|  1   2| 12  22| 15  22  11 16
 Q12     0|  0   0|  0  46| 48   1|  0   0|  0   0|  0   4|  7   5
 Q24     0|      0|     46|     49|      0|      0|      4|     12  44
 DUR     0|  0   0|  0   7|  7   0|  0   0|  0   0|  0   1|  3   1
 T12     0|  0   0|  1   4|  4   1|  0   0|  0   2|  4  10|  4   8
 PZR     0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0
 PSN     0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0
 PPL     0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0
 PRA     0|  0   0|  5  54| 56   1|  0   1|  0   5| 12  20| 22  25
 S12     0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0
 S24     0|      0|      0|      0|      0|      0|      0|      0
 SLV    80| 77  67| 73  60| 68  62| 70  72| 81  90| 97  90| 91  91
 I12     0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0|  0   0
 SOL     0| 32   0| 33   0|  6   0|  5   0| 11   0|  7   0| 21   0
"""

private val kokcNbx = """
 KOKC    NBM  NBX GUIDANCE    11/12/2026  1200 UTC
      SAT 21| SUN 22| MON 23                                              
 UTC 00  12 |00  12 |00  12                                               
 FHR 204 216|228 240|252 264                                              
 TXN  68  53| 67  44| 60  39
 XND   5   8|  7   8|  7   7
 TMP  63  56| 60  46| 52  41
 TSD   5   8|  8   9|  6   8
 DPT  56  54| 51  43| 42  40
 DSD   7  10| 10  10|  9   9
 WBG  59  55| 55  45| 48  40
 SKY  53  57| 41  38| 40  42
 SSD  28  24| 17  23| 18  22
 WDR  16  16| 17  21| 20  28
 WSP  12  12| 10   9|  7   6
 WSD   4   4|  4   4|  5   4
 GST  20  17| 15  12|  9   8
 GSD   8   5|  8   6|  8   6
 P12  32  48| 43  29| 17  10
 Q12   2  25| 13  12|  2   1
 Q24      27|     25|      3
 DUR   1   4|  3   3|  0   0
 PZR   0   0|  0   0|  0   0
 PSN   0   0|  0   0|  0   0
 PPL   0   0|  0   0|  0   0
 PRA  35  43| 32  20| 13   7
 S12   0   0|  0   0|  0   0
 S24       0|      0|      0
 SLV  92  88| 78  62| 53  46
 I12   0   0|  0   0|  0   0
 SOL   5   5| 10   5|  7   2
"""

private val kokcNbs = """
 086092  NBM  NBS GUIDANCE    11/12/2026  1200 UTC
 DT /NOV  12/NOV  13                /NOV  14                /NOV  15      
 UTC  18 21 00 03 06 09 12 15 18 21 00 03 06 09 12 15 18 21 00 03 06 09 12 
 FHR  06 09 12 15 18 21 24 27 30 33 36 39 42 45 48 51 54 57 60 63 66 69 72 
 TXN        78          69          74          58          68          61
 XND         2           1           2           2           2           3
 TMP  76 76 74 74 73 72 70 71 72 72 69 65 63 61 58 62 65 66 65 64 63 64 64
 TSD   2  2  1  1  1  1  1  2  2  2  2  2  2  2  2  2  2  2  2  3  3  4  4
 DPT  67 67 68 67 68 68 67 63 58 55 55 54 53 51 48 48 49 50 51 51 53 54 56
 DSD   4  3  4  4  3  1  2  4  4  3  4  3  3  3  3  3  4  4  3  3  3  3  3
 WBG  72 72 71 70 70 69 68 68 68 65 63 61 58 57 54 58 61 60 59 59 59 59 61
 SKY  93 87 85 77 80 85 56 58 67 59 52 14 15 28 10 16 24 17 24 16 35 28 28
 SSD   7  9  9 25 13 10 36 32 32 29 28 23 20 33  9 23 23 17 23 15 14 15 24
 WDR  28 26 27 27 28 29 31 32 31 29 29 30 31 32 32 32 34 35 35 35 36 36  2
 WSP   7  6  4  4  6  8  9  9 10  9  7 10 13 13 13 12 12 12 10  8  8  9  9
 WSD   2  2  2  2  2  2  2  2  2  2  2  3  3  3  2  2  1  2  2  2  3  4  3
 GST  12 11  8  9 12 14 16 14 15 15 13 17 21 20 20 17 18 18 16 15 14 16 15
 GSD   3  3  2  2  3  3  3  2  3  3  3  3  3  3  3  2  2  3  3  3  4  4  3
 P06  36    21     8     5     5     1     0     0     0     0     0     0
 P12        43           8           5           0           0           0
 Q06   6     7     0     0     0     0     0     0     0     0     0     0
 Q12        13           0           0           0           0           0
 DUR         8           0           0           0           0           0
 T03   5  5  4  3  2  1  1  1  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
 T06  13    10     6     2     1     1     0     0     0     0     0     0
 T12        16           6           1           0           0           0
 PZR   0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
 PSN   0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
 PPL   0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
 PRA  32 19  8  3  4  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
 S06   0     0     0     0     0     0     0     0     0     0     0     0
 SLV 120120120120110110110100 85 73 76 77 71 67 57 47 44 46 49 56 61 62 64
 I06   0     0     0     0     0     0     0     0     0     0     0     0
 CIG 150160140150130130-88150230230-88-88-88-88-88-88-88-88-88 42 42 42-88
 IFC   0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  1  0  0
 LCB 100120120120 90 90 90140190190100 80 80-88-88-88130130190 42 42 42 50
 VIS 100100100100100100100100100100100100100100100100100100100100100100100
 IFV   0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0
 MHT  18 17  5  5  4  4 13 19 28 30  6 11 14 13 10 19 32 38 24 18 12 13 15
 TWD  27 25 28 27 28 31 32 32 30 30 30 31 32 33 32 33 33 35 35 35 34 34 36
 TWS   8  8  7  8 10 12 12 11 11 12 10 12 15 13 14 13 12 13 14 11 10 11 11
 SOL  32 12  0  0  0  0  1 47 63 24  2  0  0  0  2 54 68 41 15  0  0  0  1
 SWH   1  1  1  1  1  2  2  2  2  2  3  3  3  3  3  3  3  3  3  3-99-99-99
"""

    @Test fun anExtendedBulletinIsReadByColumn() {
        val t = parseNbmTextBlock(kokcNbe.trimStart('\n'))!!
        assertEquals("KOKC", t.station)
        assertEquals("NBE", t.kind)
        assertEquals(NbmCycle(2026, 11, 12, 12).epoch, t.cycleEpoch)
        assertEquals(listOf(24, 36, 48, 60, 72, 84, 96, 108, 120, 132, 144, 156, 168, 180, 192), t.hours)
        assertEquals(NbmCycle(2026, 11, 13, 12).epoch, t.validTimes.first())
        assertEquals(listOf(41, 50, 38, 52, 42, 45, 40, 48, 42, 51, 45, 56, 51, 60, 54), t.values("TMP"))
        assertEquals(listOf(3, 3, 3, 4, 4, 4, 3, 2, 4, 3, 5, 4, 5, 4, 6), t.values("TSD"))
        assertEquals(listOf(1, 0, 0, 5, 69, 87, 18, 1, 0, 1, 2, 12, 22, 15, 22), t.values("P12"))
        // Q24 (the 24-hour amount) has a value only under the 24, 48, 72 ... columns: the blank fields are missing, not the next value shifted
        assertEquals(listOf<Int?>(0, null, 0, null, 46, null, 49, null, 0, null, 0, null, 4, null, 12), t.values("Q24"))
        assertEquals(15, t.values("SLV").size)
        assertEquals(List(15) { null }, t.values("NOPE"))
    }

    @Test fun theSuperExtendedBulletinContinuesTheExtendedOne() {
        val t = parseNbmTextBlock(kokcNbx.trimStart('\n'))!!
        assertEquals("NBX", t.kind)
        assertEquals(listOf(204, 216, 228, 240, 252, 264), t.hours)
        assertEquals(NbmCycle(2026, 11, 12, 12).epoch + 264 * 3600L, t.validTimes.last())
        assertEquals(listOf<Int?>(63, 56, 60, 46, 52, 41), t.values("TMP"))
    }

    @Test fun theShortRangeBulletinHasThreeHourlyColumnsWithoutSeparators() {
        val t = parseNbmTextBlock(kokcNbs.trimStart('\n'))!!
        assertEquals("NBS", t.kind)
        assertEquals((6..72 step 3).toList(), t.hours)
        // the snow level touches its neighbors ("120120120"): each three-character field is its own value
        assertEquals(listOf(120, 120, 120, 120, 110, 110, 110, 100, 85, 73), t.values("SLV").take(10))
        assertEquals(76, t.values("TMP").first())
    }

    @Test fun textThatIsNotABulletinIsNull() {
        assertNull(parseNbmTextBlock(""))
        assertNull(parseNbmTextBlock("hello\nworld"))
    }

    // ---- fetching one station ----

    /** A stand-in for the real file's shape: several sorted runs one after another, blocks of different lengths, the main run ending in letters (W..Z). */
    private class Standin(runs: List<List<String>>, val kind: String = "NBX") {
        val ids = runs.flatten()
        val bytes: ByteArray
        init {
            val out = StringBuilder("1\n                                                                          \n")
            for (id in ids) {
                out.append(" ").append(id.padEnd(7)).append(" NBM  $kind GUIDANCE    11/12/2026  1200 UTC\n")
                out.append("      SAT 21| SUN 22| MON 23\nUTC 00  12 |00  12 |00  12\nFHR 204 216|228 240|252 264\n")
                val rows = 6 + (id.hashCode() and 15)       // blocks of different lengths
                for (r in 0 until rows) out.append(" T").append(('A' + r % 26)).append(r % 10).append("  ${50 + r}  ${51 + r}| ${52 + r}  ${53 + r}| ${54 + r}  ${55 + r}\n")
                out.append("                                                                          \n")
            }
            bytes = out.toString().encodeToByteArray()
        }
    }

    private class FakeHttp(private val file: Standin, private val key: String) : Http {
        var ranges = 0
        override suspend fun get(url: String): HttpResult {
            if ("list-type=2" !in url) return HttpResult(404, ByteArray(0))
            val prefix = url.substringAfter("prefix=")
            return if (prefix == key) HttpResult(200, "<Contents><Key>$key</Key><Size>${file.bytes.size}</Size></Contents>".encodeToByteArray())
            else HttpResult(200, "<ListBucketResult></ListBucketResult>".encodeToByteArray())
        }
        override suspend fun getRange(url: String, first: Long, lastInclusive: Long): HttpResult {
            ranges++
            val to = minOf(lastInclusive, file.bytes.size - 1L).toInt()
            return HttpResult(206, file.bytes.copyOfRange(first.toInt(), to + 1))
        }
    }

    private fun letters(prefix: String, n: Int): List<String> = (0 until n).map { prefix + ('A' + it / 26 % 26) + ('A' + it % 26) }

    private fun standin(): Standin {
        val main = listOf("086092", "188557", "2A1", "2M2", "356033") + letters("K", 2600) + letters("P", 500) + letters("W", 520).map { it + "1" }
        val buoys = listOf("46001", "46035", "46060") + letters("A", 200).map { it } + letters("Z", 100)
        val more = listOf("51000", "51001") + letters("D", 150)
        val tail = listOf("41051", "41052") + letters("S", 100)
        return Standin(listOf(main, buoys, more, tail))
    }

    @Test fun anyStationOfAFileWithSeveralSortedRunsIsFoundWithFewRequests() = runTest {
        val file = standin()
        val key = "blend.20261112/12/text/blend_nbxtx.t12z"
        val http = FakeHttp(file, key)
        val repo = NbmTextRepository(http)
        val found = repo.newest("NBX", NbmCycle(2026, 11, 12, 13).epoch + 600)
        assertNotNull(found)
        assertEquals(NbmCycle(2026, 11, 12, 12).epoch, found.cycleEpoch)
        assertEquals(file.bytes.size.toLong(), found.size)
        val sample = file.ids.filterIndexed { i, _ -> i % 7 == 0 } + listOf(file.ids.last(), file.ids.first(), "WAA1", letters("W", 520).last() + "1")
        var worst = 0
        for (id in sample) {
            val before = http.ranges
            val table = repo.station(found, id)
            assertNotNull(table, "station $id")
            assertEquals(id, table.station)
            worst = maxOf(worst, http.ranges - before)
        }
        assertTrue(worst < 60, "the most range requests for one station was $worst")
        // once the file is indexed a station of the main run costs a dozen small reads
        val before = http.ranges
        assertNotNull(repo.station(found, "KBC"))
        assertTrue(http.ranges - before < 20, "${http.ranges - before} reads")
        // a station the file does not have
        assertNull(repo.station(found, "KZZZ9"))
    }

    @Test fun noFileNoStation() = runTest {
        val file = standin()
        val http = FakeHttp(file, "blend.20261112/12/text/blend_nbxtx.t12z")
        assertNull(NbmTextRepository(http).newest("NBX", NbmCycle(2026, 11, 30, 12).epoch, hoursBack = 3))
        assertNull(NbmTextRepository(http).newest("NOPE", 0))
    }
}
