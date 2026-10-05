package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The delivered rate is measured from what the encoder emitted, over a window, and says nothing until it can. */
class StreamStatsTest {

    @Test fun nothingIsClaimedUntilTwoFramesHaveBeenSeen() {
        val stats = StreamStats(windowMs = 5_000L)
        assertNull(stats.fps(0L))
        stats.onFrame(1_000L, 10_000)
        assertNull("one frame has no rate", stats.fps(1_000L))
        assertNull(stats.kbps(1_000L))
    }

    @Test fun theRateIsFramesOverTheirSpanAndBitsOverTheSameSpan() {
        val stats = StreamStats(windowMs = 5_000L)
        // 15 fps for two seconds at 10,000 bytes a frame.
        for (i in 0 until 31) stats.onFrame(1_000L + i * 1_000L / 15, 10_000)
        val fps = requireNotNull(stats.fps(3_000L))
        assertEquals(15.0, fps, 0.2)
        // 30 frames after the first, 80,000 bits each, over 2,000 ms.
        assertEquals(1_200, requireNotNull(stats.kbps(3_000L)))
    }

    @Test fun framesOlderThanTheWindowFallOutAndAResetForgetsEverything() {
        val stats = StreamStats(windowMs = 1_000L)
        stats.onFrame(0L, 100)
        stats.onFrame(500L, 100)
        stats.onFrame(5_000L, 100)
        assertNull("only the newest frame is inside the window", stats.fps(5_000L))
        stats.onFrame(5_500L, 100)
        assertEquals(2.0, requireNotNull(stats.fps(5_500L)), 0.01)
        stats.reset()
        assertNull(stats.fps(5_500L))
    }
}
