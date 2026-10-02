package simplemodelview

/**
 * Loop-video quality presets. Measured on real reflectivity loops (24 clean frames, H.264): about 31% of the pixels change between
 * frames (92% of the data area), so the cost is the data itself; the map and background are free. What shrinks a file is resolution and
 * the bits allowed per pixel (see docs/loop-video-size.md). [bitsPerPixel] is per pixel per frame at 4 frames a second.
 */
enum class VideoSize(val label: String, val width: Int, val bitsPerPixel: Double) {
    SMALL("Small", 800, 0.80),
    MEDIUM("Medium", 960, 0.90),
    LARGE("Large", 1280, 1.00);

    /** Target bitrate in bits per second for a picture of [pixelWidth] x [pixelHeight] (already scaled to this size). */
    fun bitrate(pixelWidth: Int, pixelHeight: Int, framesPerSecond: Int): Int =
        (pixelWidth.toDouble() * pixelHeight * framesPerSecond * bitsPerPixel).toInt().coerceIn(150_000, 12_000_000)

    /** The picture's size after scaling to this preset's width (never enlarged), height kept in proportion. */
    fun scaled(pixelWidth: Int, pixelHeight: Int): Pair<Int, Int> =
        if (pixelWidth <= width) pixelWidth to pixelHeight else width to (pixelHeight.toLong() * width / pixelWidth).toInt()

    companion object {
        /** The encoder (variable bitrate) spends about 90% of its target on this kind of picture (measured: 2.83 of 3.1 MB, 4.55 of 5.1 MB). */
        private const val UNDERSPEND = 0.9

        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: MEDIUM

        /** Estimated file size in megabytes for [frames] time steps of a [pixelWidth] x [pixelHeight] picture, 4 fps plus the held last frame. */
        fun estimateMegabytes(size: VideoSize, pixelWidth: Int, pixelHeight: Int, frames: Int, frameMillis: Int = 250, holdMillis: Int = 1200): Double {
            val (w, h) = size.scaled(pixelWidth, pixelHeight)
            val seconds = (frames * frameMillis + holdMillis) / 1000.0
            return size.bitrate(w, h, 1000 / frameMillis) * seconds / 8 / 1e6 * UNDERSPEND
        }
    }
}
