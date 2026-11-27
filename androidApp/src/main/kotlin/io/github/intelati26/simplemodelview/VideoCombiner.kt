package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import simplemodelview.VideoSize
import java.io.File

/**
 * Puts finished loop videos together: [stack] shows two above one another (each picture of either is drawn when it changes, so two loops of different
 * frame times still run side by side in time), [join] plays them one after another. The frames are read from the files and encoded again.
 */
internal object VideoCombiner {
    /** A finished video: when each of its pictures starts (microseconds), how long it plays, and its size. */
    private class Clip(val file: File) {
        val starts: LongArray
        val durationUs: Long
        val width: Int
        val height: Int
        val frames = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }

        init {
            val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            width = format.getInteger(MediaFormat.KEY_WIDTH)
            height = format.getInteger(MediaFormat.KEY_HEIGHT)
            durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val list = ArrayList<Long>()
            while (extractor.sampleTime >= 0) { list += extractor.sampleTime; extractor.advance() }
            extractor.release()
            starts = list.toLongArray()
        }

        /** The picture that is on screen at [index] of [starts]. */
        fun picture(index: Int): Bitmap = frames.getFrameAtTime(starts[index], MediaMetadataRetriever.OPTION_CLOSEST) ?: throw IllegalStateException("could not read a picture of ${file.name}")

        /** The index of the picture on screen at [us]. */
        fun indexAt(us: Long): Int {
            var lo = 0; var hi = starts.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (starts[mid] <= us) lo = mid else hi = mid - 1 }
            return lo
        }

        fun close() { runCatching { frames.release() } }
    }

    /** [a] above [b] (b scaled to a's width): one picture for each moment either of them changes. [onProgress] gets (done, total). */
    suspend fun stack(a: File, b: File, out: File, size: VideoSize, onProgress: (Int, Int) -> Unit): File = withContext(Dispatchers.Default) {
        val ca = Clip(a); val cb = Clip(b)
        try {
            val times = (ca.starts.toList() + cb.starts.toList()).distinct().sorted()
            val total = maxOf(ca.durationUs, cb.durationUs)
            val width = ca.width
            val bHeight = (cb.height.toLong() * width / cb.width).toInt().coerceAtLeast(1)
            val sized = size.scaled(width, ca.height + bHeight)
            val scale = sized.first.toFloat() / width
            val exporter = newExporter(out, sized.first, sized.second, size, times)
            try {
                var lastA = -1; var lastB = -1
                var composed: Bitmap? = null
                for ((k, t) in times.withIndex()) {
                    val ia = ca.indexAt(t); val ib = cb.indexAt(t)
                    if (composed == null || ia != lastA || ib != lastB) {
                        val pa = ca.picture(ia); val pb = cb.picture(ib)
                        composed = Bitmap.createBitmap(sized.first, sized.second, Bitmap.Config.ARGB_8888).also { bm ->
                            val canvas = Canvas(bm)
                            canvas.drawColor(Color.WHITE)
                            canvas.drawBitmap(Bitmap.createScaledBitmap(pa, sized.first, (ca.height * scale).toInt().coerceAtLeast(1), true), 0f, 0f, null)
                            canvas.drawBitmap(Bitmap.createScaledBitmap(pb, sized.first, (bHeight * scale).toInt().coerceAtLeast(1), true), 0f, (ca.height * scale), null)
                        }
                        lastA = ia; lastB = ib
                    }
                    val end = if (k + 1 < times.size) times[k + 1] else total
                    exporter.addFor(composed, ((end - t) / 1000).toInt().coerceAtLeast(1), last = k == times.size - 1)
                    onProgress(k + 1, times.size)
                }
                exporter.finish()
            } catch (e: Exception) { exporter.abort(); throw e }
            out
        } finally { ca.close(); cb.close() }
    }

    /** [files] one after another (each drawn on the first one's size). */
    suspend fun join(files: List<File>, out: File, size: VideoSize, onProgress: (Int, Int) -> Unit): File = withContext(Dispatchers.Default) {
        val clips = files.map { Clip(it) }
        try {
            val first = clips.first()
            val sized = size.scaled(first.width, first.height)
            val count = clips.sumOf { it.starts.size }
            val exporter = newExporter(out, sized.first, sized.second, size, clips.flatMap { it.starts.toList() })
            try {
                var done = 0
                for ((c, clip) in clips.withIndex()) {
                    for (i in clip.starts.indices) {
                        val picture = clip.picture(i)
                        val fitted = Bitmap.createBitmap(sized.first, sized.second, Bitmap.Config.ARGB_8888).also { bm ->
                            val canvas = Canvas(bm)
                            canvas.drawColor(Color.WHITE)
                            val k = minOf(sized.first.toFloat() / picture.width, sized.second.toFloat() / picture.height)
                            canvas.drawBitmap(Bitmap.createScaledBitmap(picture, (picture.width * k).toInt().coerceAtLeast(1), (picture.height * k).toInt().coerceAtLeast(1), true), 0f, 0f, null)
                        }
                        val end = if (i + 1 < clip.starts.size) clip.starts[i + 1] else clip.durationUs
                        exporter.addFor(fitted, ((end - clip.starts[i]) / 1000).toInt().coerceAtLeast(1), last = c == clips.size - 1 && i == clip.starts.size - 1)
                        onProgress(++done, count)
                    }
                }
                exporter.finish()
            } catch (e: Exception) { exporter.abort(); throw e }
            out
        } finally { clips.forEach { it.close() } }
    }

    /** An encoder at [width] x [height]: its base frame time is the shortest step in [starts] (50 ms to 1 s), the bitrate per picture as for any loop. */
    private fun newExporter(out: File, width: Int, height: Int, size: VideoSize, starts: List<Long>): LoopExporter {
        val steps = starts.sorted().zipWithNext { x, y -> y - x }.filter { it > 0 }
        val frameMillis = ((steps.minOrNull() ?: 250_000L) / 1000).toInt().coerceIn(50, 1000)
        return LoopExporter(out, width, height, size.bitrate(width, height, 1000 / frameMillis), frameMillis = frameMillis)
    }
}
