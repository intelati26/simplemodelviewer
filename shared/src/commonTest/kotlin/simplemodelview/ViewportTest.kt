package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportTest {
    private fun assertNear(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-4f)

    @Test fun unzoomedShowsAllOfTheWidth() {
        val f = visibleFractions(1000f, 2000f, 1.5f, 1f, 0f, 0f)   // picture is 1000 x 667, centred in a tall container
        assertNear(0f, f[0]); assertNear(1f, f[2])
        assertNear(0f, f[1]); assertNear(1f, f[3])                  // all of the height too (clamped)
    }

    @Test fun heightLimitedPictureIsFittedInsideTheContainer() {
        // a 1.5:1 picture in a 1200 x 400 container is 600 x 400, centred: it spans the middle half of the width
        val f = visibleFractions(1200f, 400f, 1.5f, 1f, 0f, 0f)
        assertNear(0f, f[0]); assertNear(1f, f[2]); assertNear(0f, f[1]); assertNear(1f, f[3])
        val zoomed = visibleFractions(1200f, 400f, 1.5f, 2f, 0f, 0f)
        // at 2x the 1200 px container shows 600 px of picture: all of its width, the middle half of its height
        assertNear(0f, zoomed[0]); assertNear(1f, zoomed[2]); assertNear(0.25f, zoomed[1]); assertNear(0.75f, zoomed[3])
    }

    @Test fun zoomedInShowsTheMiddle() {
        val f = visibleFractions(1000f, 1000f / 1.5f, 1.5f, 2f, 0f, 0f)
        assertNear(0.25f, f[0]); assertNear(0.75f, f[2])
        assertNear(0.25f, f[1]); assertNear(0.75f, f[3])
    }

    @Test fun panningMovesTheWindow() {
        // dragging the picture 250 px to the right at 2x reveals more of its left side: the window shifts left by 125 px = 0.125
        val f = visibleFractions(1000f, 1000f / 1.5f, 1.5f, 2f, 250f, 0f)
        assertNear(0.125f, f[0]); assertNear(0.625f, f[2])
    }
}
