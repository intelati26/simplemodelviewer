package io.github.intelati26.simplemodelview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real screen (live data, header and footer over the map): a pinch must zoom the CONUS map and a regional sector alike. Needs the network. */
class AppZoomTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, millis: Long = 90_000) =
        rule.waitUntil(millis) { rule.onAllNodesWithTextSafe(text) }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextSafe(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun changedFraction(before: android.graphics.Bitmap, after: android.graphics.Bitmap): Double {
        var changed = 0; var total = 0
        val w = before.width; val h = before.height
        for (y in (h * 0.3).toInt() until (h * 0.7).toInt() step 6) for (x in 0 until w step 6) {
            total += 1
            if (before.getPixel(x, y) != after.getPixel(x, y)) changed += 1
        }
        return changed.toDouble() / total
    }

    private fun save(bitmap: android.graphics.Bitmap, name: String) {
        val dir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun pinchChange(tag: String): Double {
        rule.waitForIdle()
        Thread.sleep(2500)
        val before = rule.onRoot().captureToImage().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        rule.onRoot().performTouchInput {
            val c = center
            pinch(Offset(c.x - 40f, c.y), Offset(c.x - 330f, c.y), Offset(c.x + 40f, c.y), Offset(c.x + 330f, c.y), durationMillis = 500)
        }
        rule.waitForIdle()
        Thread.sleep(1500)
        val after = rule.onRoot().captureToImage().asAndroidBitmap()
        save(before, "${tag}_before"); save(after, "${tag}_after")
        return changedFraction(before, after)
    }

    @Test fun pinchOnConusAndOnARegion() {
        if (rule.onAllNodes(androidx.compose.ui.test.hasText("Not now")).fetchSemanticsNodes().isEmpty()) { try { waitFor("Not now", 20_000) } catch (_: Throwable) { } }
        if (rule.onAllNodes(androidx.compose.ui.test.hasText("Not now")).fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("Not now").performClick()
        waitFor("Share")
        // the app remembers the last sector: start from CONUS whatever it was
        if (rule.onAllNodes(androidx.compose.ui.test.hasText("CONUS")).fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithText("Central").performClick()
            rule.onNodeWithText("CONUS").performClick()
        }
        Thread.sleep(8000)
        val conus = pinchChange("conus")
        // back out (double tap resets the zoom), then a regional sector
        rule.onRoot().performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        rule.onNodeWithText("CONUS").performClick()
        rule.onNodeWithText("Central").performClick()
        waitFor("Central")
        Thread.sleep(8000)
        val region = pinchChange("region")
        assertTrue("pinch changed the picture by: CONUS $conus, region $region (each should be well above 0.05)", conus > 0.05 && region > 0.05)
    }
}
