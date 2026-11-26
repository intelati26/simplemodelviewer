package io.github.intelati26.simplemodelview

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import simplemodelview.RainDay

class RainHistoryTest {
    private val now = 1_800_000_000L
    private fun day(seed: Int) = RainDay(4, 2, 40.0, -100.0, 0.01, 0.01, listOf(RainDay.Strip(0, 2, ByteArray(10) { (it + seed).toByte() })))
    private fun store() = RainDayStore(Files.createTempDirectory("rain").toFile(), clock = { now })

    @Test fun roundTripSkipsKeptDaysAndKeepsTheRest() {
        val a = store()
        val ends = listOf(now - 3 * 86400L, now - 2 * 86400L, now - 86400L)
        ends.forEachIndexed { i, e -> a.write(e, day(i)) }
        val zip = ByteArrayOutputStream().also { assertEquals(3, RainHistory.write(a, it)) }.toByteArray()
        val b = store()
        b.write(ends[0], day(9))                       // already there: left as it is
        val r = RainHistory.read(b, ByteArrayInputStream(zip), now)
        assertEquals(2, r.added); assertEquals(1, r.had); assertEquals(0, r.bad)
        assertEquals(3, b.size().second)
        assertEquals(9.toByte(), b.read(ends[0])!!.strips[0].bytes[0])     // the kept one was not overwritten
        assertEquals(1.toByte(), b.read(ends[1])!!.strips[0].bytes[0])
    }

    @Test fun badEntriesAndOldDaysAreNotWritten() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("day-${now - 86400L}.rd")); z.write(day(0).toBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("day-${now - 86400L * 5}.rd")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()      // damaged
            z.putNextEntry(ZipEntry("../evil.rd")); z.write(day(0).toBytes()); z.closeEntry()                           // not a day's name
            z.putNextEntry(ZipEntry("day-${now - 200 * 86400L}.rd")); z.write(day(0).toBytes()); z.closeEntry()       // older than kept
        }
        val s = store()
        val r = RainHistory.read(s, ByteArrayInputStream(out.toByteArray()), now)
        assertEquals(1, r.added); assertEquals(1, r.had); assertEquals(2, r.bad)
        assertEquals(1, s.size().second)
        assertTrue(s.has(now - 86400L))
    }
}
