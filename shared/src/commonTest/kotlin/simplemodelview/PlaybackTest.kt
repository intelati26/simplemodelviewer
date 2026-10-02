package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTest {
    private fun ready(n: Int, total: Int) = BooleanArray(total) { it < n }

    @Test fun cyclesTheFramesThatHaveArrivedAndWrapsToTheFirst() {
        val r = ready(5, 20)                     // frames 1-5 are in
        assertEquals(listOf(1, 2, 3, 4, 0, 1), generateSequence(0) { nextPlayable(it, r) }.drop(1).take(6).toList())
    }

    @Test fun newFramesJoinTheCycleAsTheyArrive() {
        val r = ready(5, 20)
        var at = 4
        assertEquals(0, nextPlayable(at, r))      // end of 1-5: back to 1
        r[5] = true; r[6] = true                  // two more arrive
        assertEquals(5, nextPlayable(at, r))      // now the cycle is longer: 1-7
        at = 6
        assertEquals(0, nextPlayable(at, r))
    }

    @Test fun skipsFramesThatFailedAndHandlesOddStarts() {
        val r = booleanArrayOf(true, false, true, true)
        assertEquals(2, nextPlayable(0, r))
        assertEquals(0, nextPlayable(3, r))
        assertEquals(2, nextPlayable(1, r))       // the frame on screen need not be ready itself
        val first = booleanArrayOf(false, true, true)
        assertEquals(1, nextPlayable(2, first))   // frame 1 failed: wraps to the first one that is ready
    }

    @Test fun lonelyOrEmpty() {
        assertEquals(0, nextPlayable(0, ready(1, 10)))
        assertNull(nextPlayable(3, BooleanArray(10)))
        assertNull(nextPlayable(0, BooleanArray(0)))
    }

    @Test fun loopWindowsAndOptions() {
        assertEquals(0 until 60, loopWindow(60, 0))                         // all
        assertEquals(0 until 12, loopWindow(60, 12))                        // the first 12: 1-12
        assertEquals(48 until 60, loopWindow(60, 12, fromEnd = true))       // the newest 12
        assertEquals(0 until 5, loopWindow(5, 12))                          // longer than the series: all of it
        assertEquals(0 until 0, loopWindow(0, 6))
        assertEquals(listOf(6, 12, 24, 36, 0), loopLengthOptions(60))
        assertEquals(listOf(6, 12, 24, 0), loopLengthOptions(30))
        assertEquals(listOf(6, 0), loopLengthOptions(12))
        assertEquals(listOf(0), loopLengthOptions(4))
    }
}
