package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer-3 verdict outranking the socket-derived one, which is what gives a measured path fault
 * its prominent warning.
 *
 * Driven through the real classifier rather than an injected snapshot, because the preference itself
 * is the behaviour under test: a battery mutant that ignored the probe verdict entirely SURVIVED
 * against a snapshot-injected test, which could not reach this code at all.
 */
class HaNetworkPathProbeVerdictTest {
    private val path = HaNetworkPath()
    private val interval = HaNetworkPath.PROBE_INTERVAL_MS

    /** A live socket with a window of healthy round trips, so the socket rules say HEALTHY. */
    private fun healthySocket(): Long {
        path.onSocketState(HaSocketState.LIVE)
        var t = 0L
        repeat(30) { path.onRoundTrip(t, 9L); t += interval }
        return t
    }

    @Test fun aLayer3VerdictOutranksAHealthySocketReading() {
        val t = healthySocket()
        assertEquals(HaNetworkPathSeverity.HEALTHY, path.snapshot(t).severity)

        // The echo probe measures a slow path. Nothing about the socket has changed, and under the
        // socket rules alone this panel would still read healthy — which is the whole defect.
        path.onPathProbeVerdict(HaNetworkPathSeverity.SEVERE, PathProbeCause.LATENCY)
        val snap = path.snapshot(t)
        assertEquals(HaNetworkPathSeverity.SEVERE, snap.severity)
        assertEquals(PathProbeCause.LATENCY, snap.cause)
        assertTrue("a measured path fault must reach the prominent surfaces", snap.degraded)
    }

    @Test fun aLayer3HealthyVerdictOutranksTheSocketsOwnSuspicion() {
        // The probe is the better instrument in both directions: if layer 3 is measurably fine, a
        // socket-derived suspicion is not promoted over it.
        path.onSocketState(HaSocketState.LIVE)
        var t = 0L
        repeat(10) { path.onRoundTrip(t, 9L); t += interval }
        path.onFailure(t, HaPathFailureKind.NETWORK); t += interval
        path.onFailure(t, HaPathFailureKind.NETWORK)
        assertEquals("the socket alone would call this severe", HaNetworkPathSeverity.SEVERE, path.snapshot(t).severity)

        path.onPathProbeVerdict(HaNetworkPathSeverity.HEALTHY, PathProbeCause.NONE)
        val snap = path.snapshot(t)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
        assertFalse(snap.degraded)
    }

    @Test fun aProbeThatCannotSpeakRestoresTheSocketRulesRatherThanClearingTheVerdict() {
        // Unsupported, unproven, or nothing measured yet all arrive here as null. A panel whose
        // platform denies the probe must be no worse off than one that never had it.
        path.onSocketState(HaSocketState.LIVE)
        var t = 0L
        repeat(10) { path.onRoundTrip(t, 9L); t += interval }
        path.onFailure(t, HaPathFailureKind.NETWORK); t += interval
        path.onFailure(t, HaPathFailureKind.NETWORK)

        path.onPathProbeVerdict(HaNetworkPathSeverity.HEALTHY, PathProbeCause.NONE)
        assertEquals(HaNetworkPathSeverity.HEALTHY, path.snapshot(t).severity)

        path.onPathProbeVerdict(null, PathProbeCause.NONE)
        val snap = path.snapshot(t)
        assertEquals("the socket rules come back", HaNetworkPathSeverity.SEVERE, snap.severity)
        assertEquals(PathProbeCause.LOSS, snap.cause)
    }

    @Test fun aLayer3VerdictNeverSurvivesTheSocketGoingAway() {
        val t = healthySocket()
        path.onPathProbeVerdict(HaNetworkPathSeverity.SEVERE, PathProbeCause.LATENCY)
        assertTrue(path.snapshot(t).degraded)
        // No socket, nothing to describe: a stale path verdict must not outlive the session it
        // belongs to, exactly as the socket-derived one does not.
        path.onSocketState(HaSocketState.STOPPED)
        val snap = path.snapshot(t)
        assertFalse(snap.measuring)
        assertFalse(snap.degraded)
    }

    @Test fun theStartupWindowSuppressesALayer3VerdictToo() {
        // Startup suppression is about the panel being unrepresentative of itself, which applies to
        // whichever instrument produced the verdict.
        val settling = HaNetworkPath(processStartElapsedMs = 0L)
        settling.onSocketState(HaSocketState.LIVE)
        settling.onPathProbeVerdict(HaNetworkPathSeverity.SEVERE, PathProbeCause.LATENCY)
        val snap = settling.snapshot(HaNetworkPath.STARTUP_SETTLE_MS - 1L)
        assertTrue(snap.settling)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
        assertFalse(snap.degraded)
    }
}
