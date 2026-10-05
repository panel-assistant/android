package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The probe's ownership and failure boundaries — the three defects the first submission was held on.
 *
 * Each of these is a seam something can outlive: a burst outlives the session that claimed it, an
 * adapter can throw anything a particular ROM feels like, and a socket can go away while a
 * measurement is still in the air. None of them is reachable from the pure schedule or classifier
 * tests, which is exactly why they were missed.
 */
class PathProbeMonitorTest {
    private val target: InetAddress = InetAddress.getByName("127.0.0.1")
    private var now = 0L
    private val clock: () -> Long = { now }

    /** A source whose behaviour each test dictates. */
    private class FakeSource(
        var burst: PathBurst? = PathBurst(0L, sent = 5, received = 5, rttsMs = List(5) { 3L }),
        var thrown: Throwable? = null,
    ) : PathEchoSource {
        var calls = 0
        override fun burst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long): PathBurst? {
            calls++
            thrown?.let { throw it }
            return burst
        }
    }

    private fun live(source: FakeSource): PathProbeMonitor =
        PathProbeMonitor(source = source).apply {
            onRouteConnected(target)
            onSocketState(HaSocketState.LIVE)
        }

    @Test fun aBurstIsClaimedOnlyWithATargetALiveSocketAndADueSchedule() {
        val monitor = PathProbeMonitor(source = FakeSource())
        assertNull("no target yet", monitor.claimBurst(0L))
        monitor.onRouteConnected(target)
        assertNull("no live socket yet", monitor.claimBurst(0L))
        monitor.onSocketState(HaSocketState.LIVE)
        assertNotNull(monitor.claimBurst(0L))
    }

    @Test fun burstsNeverOverlap() {
        val monitor = live(FakeSource())
        val first = monitor.claimBurst(0L)
        assertNotNull(first)
        // Due or not, a second claim while one is in flight must be refused: overlapping bursts race
        // for the same sequence numbers and the loss figure becomes fiction.
        now = 10L * 60_000L
        assertNull("a burst is already in flight", monitor.claimBurst(now))
    }

    @Test fun anAdapterThatThrowsAnythingAtAllIsContainedRatherThanPropagated() {
        // The held defect: a refusal peculiar to one ROM can surface as any throwable, and escaping
        // here reaches a coroutine that can take the process down.
        val source = FakeSource(thrown = UnsatisfiedLinkError("no ICMP on this ROM"))
        val monitor = live(source)
        val claim = requireNotNull(monitor.claimBurst(0L))
        monitor.runBurst(claim, clock) // must not throw
        assertEquals(1, source.calls)
        // And the failure must not leave the probe wedged: a later burst can still be claimed.
        now = 10L * 60_000L
        assertNotNull("a contained failure must release the in-flight flag", monitor.claimBurst(now))
    }

    @Test fun aNullBurstMarksThePlatformUnsupportedRatherThanCountingLoss() {
        val monitor = live(FakeSource(burst = null))
        val claim = requireNotNull(monitor.claimBurst(0L))
        monitor.runBurst(claim, clock)
        val snap = requireNotNull(monitor.snapshot(0L))
        assertEquals(PathProbeAvailability.UNSUPPORTED, snap.availability)
        assertNull("an unsupported platform states no verdict", snap.severity)
    }

    @Test fun aResultFromAnEndedSessionIsDiscardedRatherThanAttributedToItsSuccessor() {
        // The held defect: a burst outlives the socket it was claimed for, and without an identity
        // on the way back its evidence lands in whatever session happens to be current.
        val monitor = live(FakeSource())
        val claim = requireNotNull(monitor.claimBurst(0L))
        // The socket dies and a new one is established before the burst reports back, which is what
        // "in flight" means here: the claim is older than the session that will receive its result.
        monitor.onSocketState(HaSocketState.STOPPED)
        monitor.onRouteConnected(target)
        monitor.onSocketState(HaSocketState.LIVE)
        monitor.runBurst(claim, clock)
        val snap = requireNotNull(monitor.snapshot(0L))
        assertEquals("stale evidence must not reach the successor", 0, snap.bursts)
        assertEquals(PathProbeAvailability.UNPROVEN, snap.availability)
    }

    @Test fun aResultFromAReplacedSocketIsDiscardedEvenWithoutAStoppedState() {
        val monitor = live(FakeSource())
        val claim = requireNotNull(monitor.claimBurst(0L))
        // Ordinary reconnects report CONNECTING and then a replacement route without STOPPED.
        // The old burst must not become evidence for that replacement socket.
        monitor.onSocketState(HaSocketState.CONNECTING)
        monitor.onRouteConnected(InetAddress.getByName("127.0.0.2"))
        monitor.onSocketState(HaSocketState.LIVE)
        monitor.runBurst(claim, clock)
        val snap = requireNotNull(monitor.snapshot(0L))
        assertEquals("stale route evidence must be discarded", 0, snap.bursts)
        assertEquals(PathProbeAvailability.UNPROVEN, snap.availability)
    }

    @Test fun aStoppedSocketDiscardsTheEvidenceAndTheCadence() {
        val monitor = live(FakeSource())
        val claim = requireNotNull(monitor.claimBurst(0L))
        monitor.runBurst(claim, clock)
        assertEquals(1, requireNotNull(monitor.snapshot(0L)).bursts)
        monitor.onSocketState(HaSocketState.STOPPED)
        assertNull("a panel holding no socket describes no path", monitor.snapshot(0L))
    }

    @Test fun reconnectingKeepsMeasuringSoAnOutageStaysReportable() {
        val monitor = live(FakeSource())
        val claim = requireNotNull(monitor.claimBurst(0L))
        monitor.runBurst(claim, clock)
        // CONNECTING after a live socket is exactly when a broken path must still be described.
        monitor.onSocketState(HaSocketState.CONNECTING)
        assertNotNull(monitor.snapshot(0L))
        assertEquals(1, requireNotNull(monitor.snapshot(0L)).bursts)
    }

    @Test fun silenceEscalatesTheNextBurstWithoutWaitingForTheInterval() {
        val monitor = live(FakeSource())
        val claim = requireNotNull(monitor.claimBurst(0L))
        monitor.runBurst(claim, clock)
        now = 1_000L
        assertNull("not due yet", monitor.claimBurst(now))
        monitor.onSocketSilence(now)
        assertNotNull("silence must bring the burst forward", monitor.claimBurst(now))
    }

    @Test fun anUnsupportedPlatformStillReportsItselfAfterTheSocketGoes() {
        // "This platform cannot probe" is a fact about the panel, not about the session, so it must
        // survive a socket that comes and goes rather than being forgotten with it.
        val monitor = live(FakeSource(burst = null))
        val claim = requireNotNull(monitor.claimBurst(0L))
        monitor.runBurst(claim, clock)
        monitor.onSocketState(HaSocketState.STOPPED)
        val snap = requireNotNull(monitor.snapshot(0L))
        assertEquals(PathProbeAvailability.UNSUPPORTED, snap.availability)
        assertFalse(snap.bursts > 0)
    }

    @Test fun theSnapshotCarriesTheCadenceAndTheAgeOfTheLastBurst() {
        val monitor = live(FakeSource())
        assertEquals(-1L, requireNotNull(monitor.snapshot(0L)).lastBurstAgeMs)
        val claim = requireNotNull(monitor.claimBurst(0L))
        monitor.runBurst(claim, clock)
        val snap = requireNotNull(monitor.snapshot(5_000L))
        assertEquals(5_000L, snap.lastBurstAgeMs)
        assertEquals(PathProbeSchedule.MIN_INTERVAL_MS, snap.intervalMs)
        assertTrue(snap.received > 0)
    }
}
