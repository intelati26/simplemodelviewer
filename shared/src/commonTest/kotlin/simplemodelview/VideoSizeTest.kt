package simplemodelview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoSizeTest {
    @Test fun scalingNeverEnlargesAndKeepsProportions() {
        assertEquals(960 to 565, VideoSize.MEDIUM.scaled(1799, 1059))
        assertEquals(800 to 470, VideoSize.SMALL.scaled(1799, 1059))
        assertEquals(500 to 300, VideoSize.LARGE.scaled(500, 300))          // already smaller than the preset
    }

    @Test fun presetsAreOrderedBySize() {
        val mb = VideoSize.entries.map { VideoSize.estimateMegabytes(it, 1799, 1059, 57) }
        assertTrue(mb[0] < mb[1] && mb[1] < mb[2], mb.toString())
    }

    @Test fun estimatesMatchWhatWasMeasured() {
        // 57 steps of the whole US with header and legend (picture 1799 x ~1260): about 2.8 / 4.5 MB measured on the emulator
        assertEquals(2.8, VideoSize.estimateMegabytes(VideoSize.SMALL, 1799, 1059 + 1799 / 6, 57), 0.5)
        assertEquals(4.5, VideoSize.estimateMegabytes(VideoSize.MEDIUM, 1799, 1059 + 1799 / 6, 57), 0.6)
        assertEquals(7.4, VideoSize.estimateMegabytes(VideoSize.LARGE, 1799, 1059, 57), 1.2)
    }

    @Test fun bitrateIsClamped() {
        assertEquals(150_000, VideoSize.SMALL.bitrate(16, 16, 4))
        assertEquals(12_000_000, VideoSize.LARGE.bitrate(8000, 8000, 4))
    }

    @Test fun byNameFallsBackToMedium() {
        assertEquals(VideoSize.SMALL, VideoSize.byName("SMALL"))
        assertEquals(VideoSize.MEDIUM, VideoSize.byName(null))
        assertEquals(VideoSize.MEDIUM, VideoSize.byName("huge"))
    }
}
