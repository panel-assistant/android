package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Which address family the socket actually connected on.
 *
 * The selected family and same-connection DNS fact come through one callback about the upgraded
 * socket. Neither fact claims the unused family is reachable.
 */
class PathProbeFamilyTest {
    private val v4: InetAddress = InetAddress.getByName("127.0.0.1")
    private val v6: InetAddress = InetAddress.getByName("::1")

    private class Silent : PathEchoSource {
        override fun burst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long) =
            PathBurst(0L, sent = 1, received = 1, rttsMs = listOf(1L))
    }

    private fun monitor() = PathProbeMonitor(source = Silent())

    @Test fun theFamilyIsTheOneTheSocketActuallyConnectedOn() {
        val m = monitor()
        m.onRouteConnected(v4)
        m.onSocketState(HaSocketState.LIVE)
        assertEquals("ipv4", requireNotNull(m.snapshot(0L)).family)

        val m6 = monitor()
        m6.onRouteConnected(v6)
        m6.onSocketState(HaSocketState.LIVE)
        assertEquals("ipv6", requireNotNull(m6.snapshot(0L)).family)
    }

    @Test fun noRouteMeansNoFamilyClaimRatherThanAGuess() {
        val m = monitor()
        m.onSocketState(HaSocketState.LIVE)
        assertNull("a panel with no route names no family", requireNotNull(m.snapshot(0L)).family)
    }

    @Test fun theFamilyFollowsAReconnectOntoTheOtherFamily() {
        val m = monitor()
        m.onRouteConnected(v6)
        m.onSocketState(HaSocketState.LIVE)
        assertEquals("ipv6", requireNotNull(m.snapshot(0L)).family)
        // The v6 path dies and the connect race lands on v4. The reported family must follow the
        // socket, not the first route ever seen.
        m.onRouteConnected(v4)
        assertEquals("ipv4", requireNotNull(m.snapshot(0L)).family)
    }

    @Test fun aStoppedSocketReportsNoResolutionRatherThanTheLastHostsOne() {
        val m = monitor()
        m.onRouteConnected(v4)
        m.onSocketState(HaSocketState.LIVE)
        m.onSocketState(HaSocketState.STOPPED)
        // Nothing is measured at all once the socket has gone, so there is no observation to report.
        assertNull(m.snapshot(0L))
    }

    /** The defining case: on one family while both resolved. */
    @Test fun aPanelUsingOneOfTwoResolvedFamiliesSaysSoOnEverySurface() {
        val m = monitor()
        m.onRouteConnected(v4)
        m.onSocketState(HaSocketState.LIVE)
        PathProbeRuntime.install(m) { 0L }
        try {
            val line = PathProbeRuntime.diagnosticLine()
            assertTrue(line, line.contains("family=ipv4"))
            val json = org.json.JSONObject(PathProbeRuntime.statusJson())
            assertEquals("ipv4", json.getString("family"))
        } finally {
            PathProbeRuntime.uninstall(m)
        }
    }

    @Test fun theRouteFactsAreReportedEvenWhenTheProbeItselfCannotRun() {
        // Knowing which family the socket is on matters MOST when the platform refuses the probe,
        // because then there is no other layer-3 evidence at all.
        val unsupported = object : PathEchoSource {
            override fun burst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long) = null
        }
        val m = PathProbeMonitor(source = unsupported)
        m.onRouteConnected(v4)
        m.onSocketState(HaSocketState.LIVE)
        val claim = requireNotNull(m.claimBurst(0L))
        m.runBurst(claim) { 0L }
        PathProbeRuntime.install(m) { 0L }
        try {
            val line = PathProbeRuntime.diagnosticLine()
            assertTrue(line, line.contains("state=unsupported"))
            assertTrue("an unsupported probe must still name the family", line.contains("family=ipv4"))
        } finally {
            PathProbeRuntime.uninstall(m)
        }
    }
}
