package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The burst classifier, and above all the rule that keeps it honest: silence is only evidence of
 * loss once the probe has been SEEN to work.
 *
 * A Home Assistant behind a reverse proxy or a host that simply drops ICMP will never answer an
 * echo, however perfect the network is. Reporting that as total packet loss would put a permanent
 * severe warning on a link that is at that moment carrying a live WebSocket, which is the same class
 * of false accusation this whole piece of work exists to remove.
 */
class PathProbeHistoryTest {
    private val history = PathProbeHistory()
    private val echoes = PathProbeHistory.ECHOES_PER_BURST

    private fun burst(atMs: Long, received: Int, rtt: Long = 4L) = PathBurst(
        atMs = atMs,
        sent = echoes,
        received = received,
        rttsMs = List(received) { rtt },
    )

    @Test fun anUnprovenProbeSaysNothingHoweverMuchSilenceItSees() {
        // Ten bursts, nothing back, every one of them. On an ICMP-filtered host this is the normal
        // steady state and it must never become a verdict.
        var t = 0L
        repeat(10) { history.record(burst(t, received = 0)); t += 30_000L }
        assertEquals(PathProbeAvailability.UNPROVEN, history.state)
        assertNull("silence from an unproven probe is not loss", history.severity())
        assertEquals(0, history.aggregate().consecutiveDeadBursts)
    }

    @Test fun oneAnsweredEchoProvesTheProbeAndSilenceBecomesEvidenceFromThenOn() {
        history.record(burst(0L, received = echoes))
        assertEquals(PathProbeAvailability.PROVEN, history.state)
        assertEquals(HaNetworkPathSeverity.HEALTHY, history.severity())
        history.record(burst(30_000L, received = 0))
        history.record(burst(60_000L, received = 0))
        assertEquals(HaNetworkPathSeverity.SEVERE, history.severity())
    }

    @Test fun anUnsupportedPlatformNeverRecordsAndNeverJudges() {
        history.markUnsupported()
        history.record(burst(0L, received = echoes))
        assertEquals(PathProbeAvailability.UNSUPPORTED, history.state)
        assertNull(history.severity())
        assertEquals(0, history.aggregate().bursts)
    }

    @Test fun sustainedPartialLossIsAWarningAndExactlyOnTheSevereLineIsNotOverIt() {
        history.record(burst(0L, received = echoes))
        // Six bursts of five with one echo lost in each: 20% overall, which is exactly the severe
        // line. The thresholds are strict, so this is the heaviest loss that is still only a warning.
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS) { history.record(burst(t, received = echoes - 1)); t += 30_000L }
        val onTheLine = history.aggregate()
        assertEquals(PathProbeHistory.SEVERE_LOSS_PERCENT, onTheLine.lossPercent, 0.001)
        assertEquals(HaNetworkPathSeverity.WARNING, history.severity())
    }

    @Test fun lossPastTheSevereLineIsSevere() {
        val h = PathProbeHistory()
        h.record(burst(0L, received = echoes))
        // Two echoes lost in each burst: 40%, unambiguously past the line.
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS) { h.record(burst(t, received = echoes - 2)); t += 30_000L }
        assertTrue("loss ${h.aggregate().lossPercent}", h.aggregate().lossPercent > PathProbeHistory.SEVERE_LOSS_PERCENT)
        assertEquals(HaNetworkPathSeverity.SEVERE, h.severity())
    }

    @Test fun oneLostEchoAcrossAFullHistoryIsHealthyRatherThanAWarning() {
        // A single dropped echo in thirty is ordinary on any Wi-Fi and must not alarm.
        history.record(burst(0L, received = echoes))
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS - 2) { history.record(burst(t, received = echoes)); t += 30_000L }
        history.record(burst(t, received = echoes - 1))
        val agg = history.aggregate()
        assertTrue("loss ${agg.lossPercent}", agg.lossPercent <= PathProbeHistory.WARN_LOSS_PERCENT)
        assertEquals(HaNetworkPathSeverity.HEALTHY, history.severity())
    }

    @Test fun theVerdictAgesOutAsCleanBurstsPushTheBadOnesOut() {
        history.record(burst(0L, received = echoes))
        var t = 30_000L
        repeat(2) { history.record(burst(t, received = 0)); t += 30_000L }
        assertEquals(HaNetworkPathSeverity.SEVERE, history.severity())
        // Recovery is not an event: enough clean bursts to fill the history and the verdict clears.
        repeat(PathProbeHistory.MAX_BURSTS) { history.record(burst(t, received = echoes)); t += 30_000L }
        assertEquals(HaNetworkPathSeverity.HEALTHY, history.severity())
        assertEquals(0, history.aggregate().consecutiveDeadBursts)
    }

    @Test fun theHistoryIsBoundedToItsMostRecentBursts() {
        history.record(burst(0L, received = echoes))
        var t = 30_000L
        repeat(PathProbeHistory.MAX_BURSTS * 3) { history.record(burst(t, received = echoes)); t += 30_000L }
        assertEquals(PathProbeHistory.MAX_BURSTS, history.aggregate().bursts)
    }

    @Test fun resetKeepsAnUnsupportedVerdictButForgetsAProvenOne() {
        history.record(burst(0L, received = echoes))
        assertEquals(PathProbeAvailability.PROVEN, history.state)
        history.reset()
        assertEquals("a re-demanded socket proves the probe again", PathProbeAvailability.UNPROVEN, history.state)
        val unsupported = PathProbeHistory().apply { markUnsupported(); reset() }
        assertEquals("the platform does not change under us", PathProbeAvailability.UNSUPPORTED, unsupported.state)
    }

    @Test fun theAggregateCarriesCountsAndPercentilesAndNothingIdentifying() {
        history.record(PathBurst(0L, sent = 5, received = 5, rttsMs = listOf(2L, 3L, 4L, 9L, 40L)))
        val agg = history.aggregate()
        assertEquals(1, agg.bursts)
        assertEquals(5, agg.sent)
        assertEquals(5, agg.received)
        assertEquals(0.0, agg.lossPercent, 0.001)
        assertEquals(4L, agg.p50Ms)
        assertEquals(40L, agg.maxMs)
        assertTrue(agg.jitterMs > 0L)
    }
}
