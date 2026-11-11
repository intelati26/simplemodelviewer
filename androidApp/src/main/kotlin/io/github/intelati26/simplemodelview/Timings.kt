package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How long each stage of the last picture took, for the read-out Settings can switch on ("Show timings"): the data (download and decode, only when the frame was
 * not already kept), the native raster, the map lines, the contour lines, the barbs, and the whole picture. Stages that did not run keep their last time.
 */
object Timings {
    private val order = listOf("data", "raster", "lines", "contours", "barbs", "picture")
    private val times = HashMap<String, Long>()
    val line = MutableStateFlow("")

    @Synchronized
    fun record(stage: String, millis: Long) {
        times[stage] = millis
        line.value = order.mapNotNull { k -> times[k]?.let { "$k $it ms" } }.joinToString(" · ")
    }

    /** Runs [block] and records how long it took under [stage]. */
    inline fun <T> timed(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try { return block() } finally { record(stage, (System.nanoTime() - start) / 1_000_000) }
    }
}

/** The stage times of the last picture, in a small line; nothing unless [show]. */
@Composable
internal fun TimingsLine(show: Boolean) {
    if (!show) return
    val text by Timings.line.collectAsStateWithLifecycle()
    if (text.isNotEmpty()) Text(text, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
