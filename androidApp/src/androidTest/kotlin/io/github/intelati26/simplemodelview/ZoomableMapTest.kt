package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The map must zoom with a two-finger pinch whatever the shape of the picture (the wide CONUS map and the squarer regional sectors). */
class ZoomableMapTest {
    @get:Rule val rule = createComposeRule()

    private fun visibleWidthAfter(width: Int, height: Int, gesture: androidx.compose.ui.test.TouchInjectionScope.() -> Unit): Float {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var visible = 1f
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                ZoomableMap(bitmap, null, "map", onViewport = { l, _, r, _ -> visible = r - l }, onTap = { _, _ -> })
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput(gesture)
        rule.waitForIdle()
        return visible
    }

    private fun pinchOut(): androidx.compose.ui.test.TouchInjectionScope.() -> Unit = {
        val c = center
        pinch(Offset(c.x - 40f, c.y), Offset(c.x - 260f, c.y), Offset(c.x + 40f, c.y), Offset(c.x + 260f, c.y), durationMillis = 400)
    }

    @Test fun pinchZoomsTheWideConusPicture() {
        val v = visibleWidthAfter(1799, 1059, pinchOut())
        assertTrue("CONUS pinch: visible width fraction $v", v < 0.9f)
    }

    @Test fun pinchZoomsARegionalPicture() {
        val v = visibleWidthAfter(1560, 1360, pinchOut())
        assertTrue("regional pinch: visible width fraction $v", v < 0.9f)
    }

    @Test fun pinchZoomsAnMrmsSizedRegionalPicture() {
        val v = visibleWidthAfter(2642, 1844, pinchOut())
        assertTrue("MRMS regional pinch: visible width fraction $v", v < 0.9f)
    }

    /** The picture changes shape while the screen stays up (CONUS, then a sector chosen from the menu): the pinch must follow the new picture. */
    @Test fun pinchStillZoomsAfterThePictureChangesShape() {
        val wide = Bitmap.createBitmap(1799, 1059, Bitmap.Config.ARGB_8888)
        val regional = Bitmap.createBitmap(1560, 1360, Bitmap.Config.ARGB_8888)
        val shown = androidx.compose.runtime.mutableStateOf(wide)
        var visible = 1f
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                ZoomableMap(shown.value, null, "map", onViewport = { l, _, r, _ -> visible = r - l }, onTap = { _, _ -> })
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput(pinchOut())            // zoom the first picture (this used to work)
        rule.waitForIdle()
        shown.value = regional                                  // a different sector arrives
        rule.waitForIdle()
        rule.onRoot().performTouchInput(pinchOut())            // ... and this one did nothing
        rule.waitForIdle()
        assertTrue("pinch after the picture changed: visible width fraction $visible", visible < 0.9f)
    }

    @Test fun doubleTapStillZooms() {
        val v = visibleWidthAfter(1560, 1360) { doubleClick(center) }
        assertTrue("regional double tap: visible width fraction $v", v < 0.9f)
    }
}
