package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer-3 latency as PATH evidence, and the prominent warning it is now entitled to raise.
 *
 * The distinction this rests on: an echo is answered by the target's kernel, so its round trip
 * contains no part of Home Assistant's own response time and none of this panel's thread scheduling.
 * That is what makes it a fact about the network, where the WebSocket round trip never was — and it
 * is why the earlier downgrade of latency applied to the application layer only.
 *
 * The founding case is a reporter with seconds of latency who blamed the panel and was resolved only
 * by a live debugging conversation. Under loss-only rules that person still gets nothing.
 */
class PathProbeLatencyTest {
    private val echoes = PathProbeHistory.ECHOES_PER_BURST

    private fun history() = PathProbeHistory()

    /** A burst whose echoes all took [rtt], with nothing lost. */
    private fun burst(atMs: Long, rtt: Long) =
        PathBurst(atMs, sent = echoes, received = echoes, rttsMs = List(echoes) { rtt })

    private fun proven(h: PathProbeHistory): PathProbeHistory {
        h.record(burst(0L, 3L))
        return h
    }

    @Test fun aHealthyPathIsHealthyAcrossTheRangeARealLinkProduces() {
        // Wired well under a millisecond, ordinary Wi-Fi in single digits, a weak or busy wireless
        // link and a tunnelled remote site in the low tens.
        val h = proven(history())
        var t = 30_000L
        listOf(1L, 4L, 14L, 11L, 6L, 2L).forEach { h.record(burst(t, it)); t += 30_000L }
        assertEquals(HaNetworkPathSeverity.HEALTHY, h.severity())
        assertEquals(PathProbeCause.NONE, requireNotNull(h.verdict()).cause)
    }

    @Test fun sustainedLatencyPastTheWarningLineIsAPathWarningNamedAsLatency() {
        val h = proven(history())
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS) { h.record(burst(t, PathProbeHistory.WARN_LATENCY_MS + 20L)); t += 30_000L }
        val verdict = requireNotNull(h.verdict())
        assertEquals(HaNetworkPathSeverity.WARNING, verdict.severity)
        assertEquals("the wording must name latency, not lost packets", PathProbeCause.LATENCY, verdict.cause)
    }

    @Test fun theReportedFaultIsSevereEvenWithNothingLost() {
        // The founding case: seconds of path latency, zero packet loss. Loss-only rules said nothing.
        val h = proven(history())
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS) { h.record(burst(t, 2_400L)); t += 30_000L }
        val verdict = requireNotNull(h.verdict())
        assertEquals(HaNetworkPathSeverity.SEVERE, verdict.severity)
        assertEquals(PathProbeCause.LATENCY, verdict.cause)
        assertEquals("nothing was lost", 0.0, h.aggregate().lossPercent, 0.001)
    }

    @Test fun theLatencyLinesAreStrictSoExactlyOnThemIsNotOverThem() {
        val warn = proven(history())
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS) { warn.record(burst(t, PathProbeHistory.WARN_LATENCY_MS)); t += 30_000L }
        assertEquals(HaNetworkPathSeverity.HEALTHY, warn.severity())

        val severe = proven(history())
        t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS) { severe.record(burst(t, PathProbeHistory.SEVERE_LATENCY_MS)); t += 30_000L }
        assertEquals(HaNetworkPathSeverity.WARNING, severe.severity())
    }

    @Test fun oneIsolatedSpikeIsDiagnosticRatherThanAlarming() {
        // A weak wireless link produces the occasional peak many times its own average; one bad
        // echo, or one bad burst, must never be a verdict.
        val h = proven(history())
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS - 1) { h.record(burst(t, 5L)); t += 30_000L }
        h.record(PathBurst(t, sent = echoes, received = echoes, rttsMs = listOf(5L, 5L, 5L, 5L, 3_000L)))
        assertEquals(HaNetworkPathSeverity.HEALTHY, h.severity())
    }

    @Test fun lossOutranksLatencyWhenBothAreWrong() {
        // A path that is dropping packets AND slow is described by the stronger fact, because lost
        // packets are what a person should go and look at first.
        val h = proven(history())
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS) {
            h.record(PathBurst(t, sent = echoes, received = 1, rttsMs = listOf(3_000L)))
            t += 30_000L
        }
        val verdict = requireNotNull(h.verdict())
        assertEquals(HaNetworkPathSeverity.SEVERE, verdict.severity)
        assertEquals(PathProbeCause.LOSS, verdict.cause)
    }

    @Test fun anUnprovenProbeStillSaysNothingHoweverSlowItLooks() {
        // The availability rule outranks latency exactly as it outranks loss: a host that filters
        // ICMP has no round trips at all, and one that has never answered proves nothing.
        val h = history()
        var t = 0L
        repeat(3) { h.record(PathBurst(t, sent = echoes, received = 0, rttsMs = emptyList())); t += 30_000L }
        assertNull(h.severity())
        assertNull(h.verdict())
    }

    @Test fun theLatencyLinesClearWhatAWorkingWirelessLinkProduces() {
        // Documents the calibration rather than trusting it. A weak or busy wireless link peaks in
        // the tens of milliseconds; the warning line has to sit clear of that whole range, and the
        // severe line clear again of the warning.
        assertTrue(
            "the warning line must clear the peaks a working wireless link produces",
            PathProbeHistory.WARN_LATENCY_MS >= 100L,
        )
        assertTrue(PathProbeHistory.SEVERE_LATENCY_MS > PathProbeHistory.WARN_LATENCY_MS)
    }
}
