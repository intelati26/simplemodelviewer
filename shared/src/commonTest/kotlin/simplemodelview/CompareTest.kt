package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompareTest {
    private val cols = 60; private val rows = 40

    /** A field of -10 dBZ with a block of [dbz] at columns [x0], rows [y0] of size 8 x 8. */
    private fun blob(x0: Int, y0: Int, dbz: Float = 50f) = FloatArray(cols * rows) { i -> val x = i % cols; val y = i / cols; if (x in x0 until x0 + 8 && y in y0 until y0 + 8) dbz else -10f }

    @Test fun aPerfectMatchScoresOne() {
        val s = CompareScore.score(blob(20, 10), blob(20, 10), cols, rows)
        assertEquals(1.0, s.csi[1], 1e-9); assertEquals(1.0, s.bias[1], 1e-9); assertEquals(1.0, s.fss, 1e-9); assertEquals(0.0, s.meanAbsError, 1e-9)
    }

    @Test fun aShiftedBlobMissesCellForCellButMatchesInANeighborhood() {
        val s = CompareScore.score(blob(20, 10), blob(40, 10), cols, rows)   // 20 cells apart: no overlap at all
        assertEquals(0.0, s.csi[1], 1e-9)
        val near = CompareScore.score(blob(20, 10), blob(24, 10), cols, rows)   // 4 cells apart: overlapping, and inside the 15-cell neighborhood
        assertTrue(near.csi[1] in 0.2..0.8, near.csi[1].toString())
        assertTrue(near.fss > 0.9, near.fss.toString())
        assertTrue(near.fss > s.fss)
    }

    @Test fun aModelWithTooMuchHasABiasOverOne() {
        val big = FloatArray(cols * rows) { i -> if (i % cols in 18 until 34 && i / cols in 8 until 24) 50f else -10f }
        val s = CompareScore.score(big, blob(20, 10), cols, rows)
        assertTrue(s.bias[1] > 3.0, s.bias[1].toString())
        assertEquals(64.0 / 256.0, s.csi[1], 1e-9)
    }

    @Test fun quietMapsHaveNoScoreAndMissingCellsAreLeftOut() {
        val quiet = FloatArray(cols * rows) { -10f }
        val s = CompareScore.score(quiet, quiet, cols, rows)
        assertTrue(s.fss.isNaN() && s.csi[1].isNaN() && s.meanAbsError.isNaN())
        val gone = blob(20, 10).also { for (i in 0 until cols * 20) it[i] = Float.NaN }   // the top half unusable
        assertEquals((rows - 20) * cols, CompareScore.score(blob(20, 10), gone, cols, rows).cells)
    }

    @Test fun aRegionScoresOnlyItsCells() {
        val s = CompareScore.score(blob(20, 10), blob(20, 10), cols, rows, intArrayOf(0, 0, 9, 9))
        assertEquals(100, s.cells)
    }

    @Test fun theDifferenceIsModelMinusRadarWhereThereIsAnEcho() {
        val d = CompareScore.difference(blob(20, 10, 40f), blob(20, 10, 55f))
        assertEquals(-15f, d[10 * cols + 21], 1e-6f)
        assertTrue(d[0].isNaN())   // no echo in either
    }

    @Test fun membersAreRankedByTheirMeanNeighborhoodScore() {
        fun sc(f: Double) = MatchScore(CompareScore.THRESHOLDS, DoubleArray(3), DoubleArray(3), f, 0.0, 1)
        val ranked = rankMembers(listOf(MemberRank("a", 0, listOf(sc(0.5), sc(0.7))), MemberRank("b", 1, listOf(sc(0.9), null)), MemberRank("quiet", 2, listOf(sc(Double.NaN))), MemberRank("c", 3, listOf(sc(0.3)))))
        assertEquals(listOf("b", "a", "c", "quiet"), ranked.map { it.name })
    }

    @Test fun earlyTimesAreTheRunsFirstHoursThatRadarCovers() {
        val start = 1_000_000L
        val times = (0..10).map { start + it * 3600L }
        assertEquals(listOf(0, 1, 2, 3), earlyTimeIndices(start, times, start + 5 * 3600L))
        assertEquals(listOf(0, 1), earlyTimeIndices(start, times, start + 3600L + 60))
        assertEquals(3, nearestTimeIndex(times, start + 3 * 3600L + 100))
    }
}
