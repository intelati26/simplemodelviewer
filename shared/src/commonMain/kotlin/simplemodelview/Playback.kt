package simplemodelview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The frame a slideshow shows after [current] when it plays only the frames that have arrived ([ready]): the next ready one, wrapping round to the
 * first ready one (normally frame 1). Playing starts at once with the first few frames and each new frame joins the cycle as it downloads
 * (1-5, then 1-10, then 1-20, ...). Null when no frame is ready; [current] itself when it is the only one.
 */
fun nextPlayable(current: Int, ready: BooleanArray): Int? {
    if (ready.isEmpty()) return null
    for (k in 1..ready.size) {
        val i = (current + k) % ready.size
        if (ready[i]) return i
    }
    return null
}

/** The loop lengths offered for a series of [total] frames (0 = all of them): a few round numbers shorter than the series, then all. */
fun loopLengthOptions(total: Int): List<Int> = listOf(6, 12, 24, 36).filter { it < total } + 0

/**
 * The frames a loop of [length] covers (0 = all): the first [length] frames (1-5, 1-10, ... as in a model run played from the start), or with
 * [fromEnd] the newest [length] (radar scans, where the latest ones matter).
 */
fun loopWindow(total: Int, length: Int, fromEnd: Boolean = false): IntRange =
    if (length <= 0 || length >= total) 0 until total else if (fromEnd) (total - length) until total else 0 until length

/**
 * The [length] frames of a loop that ends at frame [end] (the one on screen): frames end-length+1 .. end, or the first [length] frames when there
 * are fewer than that before it. 0 (or a length of the whole series) = all frames. Radar loops use this, so "12 frames" is the 12 up to the time shown.
 */
fun loopWindowEndingAt(total: Int, length: Int, end: Int): IntRange {
    if (length <= 0 || length >= total) return 0 until total
    val last = end.coerceIn(0, total - 1)
    val start = (last - length + 1).coerceAtLeast(0)
    return start until (start + length)
}

/**
 * The [length] frames of a loop that starts at frame [shown] (the one on screen): shown .. shown+length-1, fewer when the series ends sooner (but at least
 * two frames when there are two). 0 (or a length of the rest of the series) = from the frame shown to the end.
 */
fun loopWindowFromShown(total: Int, length: Int, shown: Int): IntRange {
    if (total <= 0) return IntRange.EMPTY
    var start = shown.coerceIn(0, total - 1)
    val end = if (length <= 0) total else minOf(total, start + length)
    if (end - start < 2 && total >= 2) start = total - 2
    return start until maxOf(end, start + minOf(2, total - start))
}

/** The window of a frame-counted loop: from the frame shown when [fromShown], else the page's usual one ([loopWindow]). */
fun loopWindowAnchored(total: Int, length: Int, shown: Int, fromShown: Boolean, fromEnd: Boolean = false): IntRange =
    if (fromShown) loopWindowFromShown(total, length, shown) else loopWindow(total, length, fromEnd)

/**
 * A loop that plays while it downloads, the same on every page: the frames of [window] are fetched in order, [parallel] at a time, and the slideshow
 * runs at once on the frames that have arrived (1-5, then 1-10, then 1-20, ...). [fetch] gets one frame ready and says whether it is (it may also
 * fetch what the frame needs besides); [alongside] runs next to the downloads. [current] is the frame on screen (it counts as ready); [show] puts
 * the next one on screen; [dwell] is how long it stays (going back to the first frame rests at least a little longer, more once everything has
 * loaded). [onProgress] gets the fraction downloaded, then null. Runs until cancelled; fails when no frame could be downloaded.
 */
suspend fun playWhileLoading(
    window: IntRange,
    size: Int,
    current: () -> Int,
    fetch: suspend (Int) -> Boolean,
    onProgress: (Float?) -> Unit,
    show: suspend (Int) -> Unit,
    dwell: (Int) -> Long,
    parallel: Int = 3,
    alongside: suspend CoroutineScope.() -> Unit = {},
): Unit = coroutineScope {
    val count = window.count()
    val ready = BooleanArray(size)
    var finished = false
    launch {
        val gate = Semaphore(parallel)
        var done = 0
        coroutineScope {
            launch { alongside() }
            for (i in window) launch {
                gate.withPermit {
                    val ok = try { fetch(i) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (ok) ready[i] = true
                }
                done += 1
                onProgress(if (done == count) null else done / count.toFloat())
            }
        }
        finished = true
    }
    if (current() in window) ready[current()] = true          // the frame on screen is loaded
    while (true) {
        val at = current()
        val next = nextPlayable(at, ready)
        if (next == null || next == at) {
            if (finished && ready.none { it }) throw IllegalStateException("The loop's frames could not be downloaded.")
            delay(150)
            continue
        }
        show(next)
        val wrap = if (next < at) (if (finished) 1200L else 500L) else 0L
        delay(maxOf(dwell(next), wrap))
    }
}
