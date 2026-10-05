package io.panelassistant.android

import io.panelassistant.android.util.ServiceRuntimeOwner
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsRuntimeReconcilerTest {
    private data class FakeRuntime(val name: String)

    @Test fun dhcpDuringStartingReplaysExactlyOnceAfterRunning() {
        val owner = ServiceRuntimeOwner(FakeRuntime("initial"), "mdns-startup-race-test")
        val applied = Collections.synchronizedList(mutableListOf<Pair<Long, String?>>())
        val reconciler = MdnsRuntimeReconciler<FakeRuntime>(
            owner = owner,
            revalidate = { observation, lanIp ->
                if (owner.isCurrent(observation)) applied += observation.generation to lanIp?.primary
            },
        )
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)

        try {
            val startup = owner.start {
                startupEntered.countDown()
                assertTrue(releaseStartup.await(2, TimeUnit.SECONDS))
            }
            assertTrue("startup path was not exercised", startupEntered.await(2, TimeUnit.SECONDS))
            assertNull("STARTING must not expose a borrowable runtime", owner.observe())

            assertFalse(
                "DHCP during STARTING must be retained, not applied early",
                reconciler.networkChanged(addresses("::1", "127.0.0.1", "192.0.2.78")),
            )
            assertTrue(applied.isEmpty())

            releaseStartup.countDown()
            assertTrue(startup.get(2, TimeUnit.SECONDS))
            assertTrue("successful startup must replay retained DHCP truth", reconciler.runtimeRunning())
            assertEquals(listOf(1L to "192.0.2.78"), applied.toList())
        } finally {
            releaseStartup.countDown()
            owner.shutdown(2_000) {}
        }
    }

    @Test fun newestTopologyIncludingLossWinsBeforeStartupCompletes() {
        val owner = ServiceRuntimeOwner(FakeRuntime("initial"), "mdns-latest-topology-test")
        val applied = Collections.synchronizedList(mutableListOf<Pair<Long, String?>>())
        val reconciler = MdnsRuntimeReconciler<FakeRuntime>(
            owner = owner,
            revalidate = { observation, lanIp ->
                if (owner.isCurrent(observation)) applied += observation.generation to lanIp?.primary
            },
        )
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)

        try {
            val startup = owner.start {
                startupEntered.countDown()
                assertTrue(releaseStartup.await(2, TimeUnit.SECONDS))
            }
            assertTrue(startupEntered.await(2, TimeUnit.SECONDS))
            assertFalse(reconciler.networkChanged(addresses("192.0.2.77")))
            assertFalse(reconciler.networkChanged(addresses("192.0.2.78")))
            assertFalse("default-network loss must clear the retained address", reconciler.networkLost())

            releaseStartup.countDown()
            assertTrue(startup.get(2, TimeUnit.SECONDS))
            assertTrue(reconciler.runtimeRunning())
            assertEquals("a stale pre-loss address must never be replayed", listOf(1L to null), applied.toList())
        } finally {
            releaseStartup.countDown()
            owner.shutdown(2_000) {}
        }
    }

    @Test fun noNetworkObservationFailedStartupAndShutdownCannotReplay() {
        val owner = ServiceRuntimeOwner(FakeRuntime("failed"), "mdns-failed-startup-test")
        val applied = Collections.synchronizedList(mutableListOf<Pair<Long, String?>>())
        val reconciler = MdnsRuntimeReconciler<FakeRuntime>(
            owner = owner,
            revalidate = { observation, lanIp ->
                if (owner.isCurrent(observation)) applied += observation.generation to lanIp?.primary
            },
        )
        assertFalse("no callback means there is nothing to reconcile", reconciler.runtimeRunning())
        assertFalse(reconciler.networkChanged(addresses("192.0.2.78")))
        assertFalse(owner.start { error("startup failed") }.get(2, TimeUnit.SECONDS))
        assertFalse("a failed runtime cannot accept retained topology", reconciler.runtimeRunning())
        assertTrue(applied.isEmpty())
        assertTrue(owner.shutdown(2_000) {})
        assertFalse("shutdown must fence later callbacks", reconciler.networkChanged(addresses("192.0.2.79")))
        assertTrue(applied.isEmpty())
    }

    @Test fun callbacksAfterReplacementUseTheCurrentRuntimeGeneration() {
        val owner = ServiceRuntimeOwner(FakeRuntime("initial"), "mdns-generation-test")
        val applied = Collections.synchronizedList(mutableListOf<Pair<Long, String?>>())
        val reconciler = MdnsRuntimeReconciler<FakeRuntime>(
            owner = owner,
            revalidate = { observation, lanIp ->
                if (owner.isCurrent(observation)) applied += observation.generation to lanIp?.primary
            },
        )
        try {
            assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
            assertTrue(reconciler.networkChanged(addresses("192.0.2.78")))
            val replacementEntered = CountDownLatch(1)
            val releaseReplacement = CountDownLatch(1)
            val replacement = owner.reconfigure(
                retire = {},
                build = { FakeRuntime("replacement") },
                start = {
                    replacementEntered.countDown()
                    assertTrue(releaseReplacement.await(2, TimeUnit.SECONDS))
                },
            )
            assertTrue("replacement path was not exercised", replacementEntered.await(2, TimeUnit.SECONDS))
            assertNull("RECONFIGURING must not expose the successor", owner.observe())
            assertFalse(reconciler.networkChanged(addresses("192.0.2.79")))
            releaseReplacement.countDown()
            assertTrue(replacement.get(2, TimeUnit.SECONDS))
            assertTrue("replacement completion must replay retained topology", reconciler.runtimeRunning())

            assertEquals(listOf(1L to "192.0.2.78", 2L to "192.0.2.79"), applied.toList())
        } finally {
            owner.shutdown(2_000) {}
        }
    }

    private fun addresses(vararg values: String): List<InetAddress> = values.map(InetAddress::getByName)
}
