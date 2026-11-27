package io.github.intelati26.simplemodelview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import simplemodelview.GlobeCamera

/**
 * What every page with a globe does the same way: it knows the globe's size on screen, draws one picture at a time (requests that arrive meanwhile collapse
 * into one follow-up with the newest camera), and answers a drag or pinch with a coarse picture at once and the full one when the fingers rest.
 * [render] draws one picture at the given stride (4: coarse, 1: full); [turnCamera] changes the page's camera.
 */
class GlobeDriver(
    private val scope: CoroutineScope,
    private val render: suspend (stride: Int) -> Unit,
    private val turnCamera: ((GlobeCamera) -> GlobeCamera) -> Unit,
) {
    var w = 0; private set
    var h = 0; private set
    private var settleJob: Job? = null
    private var busy = false
    private var dirty = false
    private var coarse = false

    /**
     * The globe's screen size is known (again). The first time, [onFirst] sets the camera to fit it; [after] runs every time; then the picture is drawn
     * when [ready] says there is something to draw.
     */
    fun setSize(width: Int, height: Int, onFirst: (Int, Int) -> Unit, after: () -> Unit, ready: () -> Boolean) {
        if (width <= 0 || height <= 0 || (width == w && height == h)) return
        val first = w == 0
        w = width; h = height
        if (first) onFirst(width, height)
        after()
        if (ready()) request(coarse = false)
    }

    /** Drag ([dx], [dy] pixels) and pinch ([zoom] factor) on the globe. */
    fun gesture(dx: Float, dy: Float, zoom: Float) {
        val minR = 0.3 * minOf(w, h)
        turnCamera { it.dragged(dx.toDouble(), dy.toDouble()).zoomed(zoom.toDouble(), minR, minR * 60) }
        settle()
    }

    /** A coarse picture now, the full one when no further change comes for a moment (also for a page's own gesture, such as GFS's polar map). */
    fun settle() {
        request(coarse = true)
        settleJob?.cancel()
        settleJob = scope.launch { delay(180); request(coarse = false) }
    }

    /** One render at a time; while one runs, further requests collapse into a single follow-up with the newest camera. */
    fun request(coarse: Boolean) {
        dirty = true
        this.coarse = coarse
        if (busy) return
        busy = true
        scope.launch {
            try {
                while (dirty) {
                    dirty = false
                    render(if (this@GlobeDriver.coarse) 4 else 1)
                }
            } finally { busy = false }
        }
    }
}
