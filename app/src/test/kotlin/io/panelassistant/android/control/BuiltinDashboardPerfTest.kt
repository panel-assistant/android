package io.panelassistant.android.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The built-in renderer's responsiveness self-measurement in [BuiltinDashboard]: time-to-interactive
 * (cold launch→connected + warm reload TTIs) and the 24h involuntary-reload count. All pure — every
 * method takes caller-supplied elapsed-realtime millis — so this runs on the JVM with no Android.
 *
 * [BuiltinDashboard] is a process-global object shared across the JVM, so reset its measurement state
 * before AND after each test (matching [BuiltinDashboardNavbarTest]).
 */
class BuiltinDashboardPerfTest {
    private val DAY = 24 * 60 * 60_000L
    private val MAX = 120_000L // TTI_MAX_MS

    @Before @After fun reset() = BuiltinDashboard.resetRendererPerf()

    @Test fun noDataBeforeAnyLoad() {
        val p = BuiltinDashboard.rendererPerf(1_000)
        assertEquals(-1L, p.coldTtiMs)
        assertEquals(-1L, p.warmTtiMedianMs)
        assertEquals(0, p.reloads24h)
    }

    @Test fun coldTtiIsFirstLoadStartToFirstConnected() {
        BuiltinDashboard.recordLoadStart(1_000)
        BuiltinDashboard.recordConnected(6_000)
        assertEquals(5_000L, BuiltinDashboard.rendererPerf(6_000).coldTtiMs)
    }

    /** A failed initial load that watchdog-reloads before the first connect must still measure cold from
     *  the ORIGINAL launch, not the retry — the number a user's stopwatch sees is launch→interactive. */
    @Test fun coldSpansAnIntermediateReloadBeforeFirstConnect() {
        BuiltinDashboard.recordLoadStart(0)      // launch (cold origin)
        BuiltinDashboard.recordLoadStart(1_000)  // watchdog reload before any connect
        BuiltinDashboard.recordConnected(5_000)
        assertEquals(5_000L, BuiltinDashboard.rendererPerf(5_000).coldTtiMs) // 5s from launch, not 4s from retry
    }

    @Test fun warmTtiUsesLatestLoadStartAndIsConsumeOnce() {
        BuiltinDashboard.recordLoadStart(0); BuiltinDashboard.recordConnected(3_000) // cold
        BuiltinDashboard.recordLoadStart(10_000)
        BuiltinDashboard.recordConnected(10_800) // warm = 800
        BuiltinDashboard.recordConnected(10_900) // duplicate connect, no pending start → dropped
        assertEquals(800L, BuiltinDashboard.rendererPerf(11_000).warmTtiMedianMs)
    }

    @Test fun bareReconnectWithNoFreshLoadIsIgnored() {
        BuiltinDashboard.recordLoadStart(0); BuiltinDashboard.recordConnected(2_000) // cold
        BuiltinDashboard.recordConnected(9_000) // websocket flap, no reload → not a load, dropped
        assertEquals(-1L, BuiltinDashboard.rendererPerf(9_000).warmTtiMedianMs)
    }

    @Test fun warmRingCapsAtTenKeepingNewest() {
        BuiltinDashboard.recordLoadStart(0); BuiltinDashboard.recordConnected(1) // cold, consumed
        // 12 warm samples of increasing duration; only the last 10 (durations 30..120) survive.
        for (i in 1..12) {
            val start = 1_000L * i
            BuiltinDashboard.recordLoadStart(start)
            BuiltinDashboard.recordConnected(start + i * 10L) // duration = i*10
        }
        // Retained durations: 30,40,50,60,70,80,90,100,110,120 → median (index 5 of 10) = 80.
        assertEquals(80L, BuiltinDashboard.rendererPerf(999_999).warmTtiMedianMs)
    }

    @Test fun warmMedianOfOddCount() {
        BuiltinDashboard.recordLoadStart(0); BuiltinDashboard.recordConnected(1) // cold
        listOf(300L, 100L, 200L).forEachIndexed { i, dur ->
            val start = 10_000L + i * 1_000L
            BuiltinDashboard.recordLoadStart(start); BuiltinDashboard.recordConnected(start + dur)
        }
        assertEquals(200L, BuiltinDashboard.rendererPerf(30_000).warmTtiMedianMs) // sorted 100,200,300 → 200
    }

    @Test fun nonPositiveAndOversizeGapsDropped() {
        BuiltinDashboard.recordLoadStart(1_000)
        BuiltinDashboard.recordConnected(1_000) // 0ms cold gap → dropped (cold latched but unset)
        assertEquals(-1L, BuiltinDashboard.rendererPerf(1_000).coldTtiMs)

        // A later warm load with an absurd (> 2 min) gap is also dropped.
        BuiltinDashboard.recordLoadStart(2_000)
        BuiltinDashboard.recordConnected(2_000 + MAX + 1)
        assertEquals(-1L, BuiltinDashboard.rendererPerf(200_000).warmTtiMedianMs)
    }

    @Test fun coldGapAtExactlyMaxIsKept() {
        BuiltinDashboard.recordLoadStart(0)
        BuiltinDashboard.recordConnected(MAX) // exactly the ceiling → in range
        assertEquals(MAX, BuiltinDashboard.rendererPerf(MAX).coldTtiMs)
    }

    @Test fun reloads24hCountsWithinWindowAndPrunesOlder() {
        BuiltinDashboard.recordRendererReload(1_000)
        BuiltinDashboard.recordRendererReload(2_000)
        assertEquals(2, BuiltinDashboard.reloads24h(2_000))
        // Read 24h+ after the first stamp: the 1_000 stamp falls out of the window, the 2_000 stays.
        assertEquals(1, BuiltinDashboard.reloads24h(2_000 + DAY))
        // Well past both → empty.
        assertEquals(0, BuiltinDashboard.reloads24h(3_000 + DAY))
    }

    @Test fun reloadCountSurfacesInSnapshot() {
        BuiltinDashboard.recordRendererReload(5_000)
        assertEquals(1, BuiltinDashboard.rendererPerf(5_000).reloads24h)
    }
}
