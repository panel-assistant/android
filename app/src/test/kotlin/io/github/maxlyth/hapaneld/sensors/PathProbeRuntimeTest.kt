package io.github.maxlyth.hapaneld.sensors

import org.jetbrains.lincheck.Lincheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Ownership of the process-global probe holder across a service replacement.
 *
 * Service lifetimes overlap: a successor installs its monitor while its predecessor's teardown is
 * still running and will uninstall its own. Both cases below construct that overlap on purpose rather
 * than hoping a race shows up. The predecessor routes over IPv4 and the successor over IPv6, so the
 * family on a snapshot names the owner it came from.
 */
class PathProbeRuntimeTest {
    private val v4: InetAddress = InetAddress.getByName("127.0.0.1")
    private val v6: InetAddress = InetAddress.getByName("::1")

    private class Answering : PathEchoSource {
        override fun burst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long) =
            PathBurst(0L, sent = 1, received = 1, rttsMs = listOf(1L))
    }

    private fun live(route: InetAddress) = PathProbeMonitor(source = Answering()).apply {
        onRouteConnected(route)
        onSocketState(HaSocketState.LIVE)
    }

    /** Leave the process-global holder empty: replace whatever a case left installed, then retire that. */
    @After fun leaveTheHolderUnowned() {
        val sweeper = PathProbeMonitor(source = Answering())
        PathProbeRuntime.install(sweeper) { 0L }
        PathProbeRuntime.uninstall(sweeper)
    }

    @Test fun aPredecessorRetiringWhileItsSuccessorInstallsNeverClearsTheSuccessor() {
        // The model checker runs the two threads under every interleaving of their shared-memory
        // accesses, so the one where the predecessor passes its identity check, the successor installs,
        // and the predecessor then clears is exercised deterministically. In every order the successor
        // must be the one left installed.
        Lincheck.runConcurrentTest(INTERLEAVINGS) {
            val predecessor = live(v4)
            val successor = live(v6)
            PathProbeRuntime.install(predecessor) { 0L }
            val retiring = thread { PathProbeRuntime.uninstall(predecessor) }
            val replacing = thread { PathProbeRuntime.install(successor) { 0L } }
            retiring.join()
            replacing.join()
            assertEquals("the successor must survive its predecessor's retirement", "ipv6", PathProbeRuntime.snapshot()?.family)
        }
    }

    @Test fun aSnapshotBlockedMidReadWhileTheSuccessorInstallsNeverReturnsTheRetiredOwnersResult() {
        val predecessor = live(v4)
        val successor = live(v6)
        val reading = CountDownLatch(1)
        val successorInstalled = CountDownLatch(1)
        // The predecessor's own clock is the seam: the read has chosen its owner and is inside it.
        PathProbeRuntime.install(predecessor) {
            reading.countDown()
            successorInstalled.await(LATCH_SECONDS, TimeUnit.SECONDS)
            0L
        }
        val read = AtomicReference<PathProbeMonitor.Snapshot?>()
        val reader = thread { read.set(PathProbeRuntime.snapshot()) }
        assertTrue("the read never reached the predecessor's clock", reading.await(LATCH_SECONDS, TimeUnit.SECONDS))

        PathProbeRuntime.install(successor) { 0L }
        successorInstalled.countDown()
        reader.join(TimeUnit.SECONDS.toMillis(LATCH_SECONDS))
        assertFalse("the blocked read never finished", reader.isAlive)

        assertNull("a read begun under a retired owner must not return that owner's result", read.get())
        assertEquals("ipv6", PathProbeRuntime.snapshot()?.family)
    }

    private companion object {
        const val INTERLEAVINGS = 1_000
        const val LATCH_SECONDS = 5L
    }
}
