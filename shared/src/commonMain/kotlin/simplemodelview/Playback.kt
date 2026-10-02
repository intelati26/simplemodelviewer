package simplemodelview

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
