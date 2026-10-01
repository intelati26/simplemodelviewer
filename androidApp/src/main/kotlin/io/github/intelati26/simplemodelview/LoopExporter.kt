package io.github.intelati26.simplemodelview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes rendered pictures into an H.264 MP4 (what messaging apps play inline). Frames are drawn onto the encoder's input
 * surface; every frame is shown for [frameMillis], the last one [lastMillis] longer so a loop reads as a loop.
 */
class LoopExporter(private val file: File, width: Int, height: Int, private val frameMillis: Int = 250, private val lastMillis: Int = 1200) {
    // encoders want dimensions that are multiples of 16; the picture is drawn at the top left on white
    val width = (width + 15) / 16 * 16
    val height = (height + 15) / 16 * 16

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val surface: android.view.Surface
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, this.width, this.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 1000 / frameMillis)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    /** Adds one picture; the [last] one is posted several times so it stays on screen for [lastMillis] more. */
    fun add(picture: Bitmap, last: Boolean) {
        repeat(if (last) 1 + lastMillis / frameMillis else 1) {
            val canvas: Canvas = surface.lockCanvas(null)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(picture, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
            drain(false)
        }
    }

    fun finish() {
        codec.signalEndOfInputStream()
        drain(true)
        codec.stop(); codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
        surface.release()
    }

    fun abort() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }; runCatching { muxer.release() }
        runCatching { surface.release() }
        file.delete()
    }

    /** Presentation time of the next frame on the surface is set by the system clock; we rewrite it from the frame index. */
    private fun drain(end: Boolean) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        info.presentationTimeUs = nextPts()
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // frames come out in the order they were drawn: space them evenly (the last post of a held frame lands lastMillis later)
    private var clockUs = 0L
    private fun nextPts(): Long {
        val t = clockUs
        clockUs += frameMillis * 1000L
        return t
    }
}
