package io.panelassistant.android.control

import org.junit.Assert.*
import org.junit.Test

/** Existing controller/root APIs keep this passive caller proof compilable on the old base. */
class RelayHelperReadBehaviorTest {
    @Test fun topologyDiscoveryNeverLaunchesRootWhenHelperIsUnavailable() {
        val root = recordingRoot()
        val relay = RelayController(fakeProfile(relayBase = BASE), root)
        repeat(3) { relay.count() }
        assertNoRootRead(root)
    }

    @Test fun relayStateNeverFallsBackToRootWhenHelperIsUnavailable() {
        val root = recordingRoot()
        val relay = RelayController(fakeProfile(relayBase = BASE), root)
        repeat(3) { assertNull(relay.read(1)) }
        assertNoRootRead(root)
    }

    @Test fun buttonLedStateNeverFallsBackToRootWhenHelperIsUnavailable() {
        val root = recordingRoot()
        val relay = RelayController(fakeProfile(buttonLedGpioBase = 147), root)
        repeat(3) { assertNull(relay.ledRead(0)) }
        assertNoRootRead(root)
    }

    @Test fun startupWarmUpNeverLaunchesRootWhenHelperIsUnavailable() {
        val root = recordingRoot()
        val relay = RelayController(fakeProfile(buttonLedGpioBase = 147), root)
        repeat(3) { relay.warmUp() }
        assertNoRootRead(root)
    }

    @Test fun undeclaredHardwareRemainsInertWithoutRoot() {
        val root = recordingRoot()
        val relay = RelayController(fakeProfile(), root)
        assertEquals(0, relay.count()); assertEquals(0, relay.ledCount())
        assertNull(relay.read(1)); assertNull(relay.ledRead(0)); relay.warmUp()
        assertFalse(relay.set(1, true)); assertFalse(relay.ledSet(0, true))
        assertNoRootRead(root)
    }

    private fun recordingRoot() = FakeRootShell(mapOf("ls $BASE" to "relay1 relay2", "cat $BASE/relay1" to "1", "cat /sys/class/gpio/gpio147/value" to "1"))

    private fun assertNoRootRead(root: FakeRootShell) {
        assertTrue("passive reads launched root: ${root.outputRan}", root.outputRan.isEmpty())
        assertTrue("startup probe launched root: ${root.isolatedOutputRan}", root.isolatedOutputRan.isEmpty())
        assertTrue(root.ran.isEmpty()); assertEquals(0, root.availabilityChecks)
    }

    companion object { private const val BASE = "/sys/class/strelay" }
}
