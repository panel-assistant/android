package io.panelassistant.android.control

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.HelperGpioState
import io.panelassistant.android.platform.HelperRelayClass
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.ZigbeeReadFixture
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Helper observations and real root-actuator effects through the controller's production seams. */
class RelayControllerTest {
    @get:Rule val sysfs = TemporaryFolder()
    private val base = "/sys/class/strelay"
    private val fallback = "/sys/class/st_relay"

    private fun wire() = ZigbeeReadFixture().apply { version = "HELPER version=1.12.0 proto=1.12" }
    private fun ZigbeeReadFixture.reply(command: String, reply: String?) { replies[command] = reply?.toByteArray() }
    private fun ZigbeeReadFixture.count(selector: String, value: String) = reply("RELAYREAD $selector COUNT", "RELAYREAD $selector COUNT=$value\n")
    private fun ZigbeeReadFixture.value(selector: String, relay: Int, value: String) = reply("RELAYREAD $selector $relay", "RELAYREAD $selector RELAY=$relay VALUE=$value\n")
    private fun ZigbeeReadFixture.gpio(pin: Int, value: String = "?", prepared: String = "?") = reply("GPIOREAD $pin", "GPIOREAD $pin VALUE=$value PREPARED=$prepared\n")
    private fun controller(wire: ZigbeeReadFixture, root: RootShell = FakeRootShell(), primary: String? = base, fallbacks: List<String> = emptyList(), leds: Int? = null) =
        RelayController(fakeProfile(relayBase = primary, relayBaseFallbacks = fallbacks, buttonLedGpioBase = leds), root, wire.client)

    private fun directController(wire: ZigbeeReadFixture, root: RootShell, primary: String? = base, writable: Boolean = true, readable: Boolean = true): RelayController =
        RelayController(fakeProfile(relayBase = primary), root, wire.client, relayFile = { path ->
            object : File(sysfs.root, path.removePrefix("/sys/class/")) {
                override fun canWrite() = writable && super.canWrite()
                override fun canRead() = readable && super.canRead()
            }
        })

    @Test fun accessibleDeclaredRelaysWorkWithoutHelperOrRootAndReadFreshPhysicalState() {
        val nodes = sysfs.newFolder("strelay")
        val first = File(nodes, "relay1").apply { writeText("0\n") }
        val second = File(nodes, "relay2").apply { writeText("1\n") }
        val wire = wire().apply { missing = true }; val root = FakeRootShell(available = false)
        val relay = directController(wire, root)
        assertEquals(2, relay.count())
        assertEquals(false, relay.read(1)); assertEquals(true, relay.read(2))
        assertTrue(relay.set(1, true)); assertEquals("1", first.readText())
        second.writeText("0\n"); assertEquals(false, relay.read(2))
        first.writeText("unknown"); assertNull(relay.read(1))
        assertFalse(relay.set(0, true)); assertFalse(relay.set(3, true))
        first.delete(); first.mkdir(); assertFalse(relay.set(1, false)); assertNull(relay.read(1))
        assertTrue(root.ran.isEmpty()); assertTrue(root.outputRan.isEmpty()); assertEquals(0, root.availabilityChecks)
        assertTrue(wire.bytes.isEmpty())
    }

    @Test fun directAccessRequiresDeclaredSupportedBaseAndWritableReadableBinaryNodes() {
        val nodes = sysfs.newFolder("strelay")
        File(nodes, "relay1").writeText("0")
        File(nodes, "relay2").writeText("1")
        val custom = sysfs.newFolder("custom")
        File(custom, "relay1").writeText("0")
        for (primary in listOf(null, "/sys/class/custom", base)) {
            val wire = wire().apply { missing = true }; val root = FakeRootShell(available = false)
            val relay = directController(wire, root, primary, writable = false)
            assertEquals(0, relay.count()); assertFalse(relay.set(1, true)); assertNull(relay.read(1))
            assertEquals("0", File(nodes, "relay1").readText()); assertTrue(root.ran.isEmpty())
        }
        for (primary in listOf(null, "/sys/class/custom")) {
            val relay = directController(wire().apply { missing = true }, FakeRootShell(available = false), primary)
            assertEquals(0, relay.count()); assertFalse(relay.set(1, true))
        }
        assertEquals(0, directController(wire().apply { missing = true }, FakeRootShell(available = false), readable = false).count())
        File(nodes, "relay2").writeText("invalid")
        assertEquals(0, directController(wire().apply { missing = true }, FakeRootShell(available = false)).count())
    }

    @Test fun inaccessibleDirectNodesRetainHelperTopologyAndPrivilegedWrites() {
        val nodes = sysfs.newFolder("strelay")
        File(nodes, "relay1").writeText("0")
        val wire = wire().apply { count("STRELAY", "1"); value("STRELAY", 1, "1") }
        val root = FakeRootShell()
        val relay = directController(wire, root, writable = false)
        assertEquals(1, relay.count()); assertEquals(true, relay.read(1)); assertTrue(relay.set(1, true))
        assertEquals("0", File(nodes, "relay1").readText())
        assertEquals(listOf("printf '%s' '1' > $base/relay1"), root.ran)
    }

    @Test fun primaryAndFallbackOrderDeterminesBothCountAndWriteBase() {
        for (primary in listOf("0", "?", "1")) {
            val wire = wire().apply { count("STRELAY", primary); count("ST_RELAY", "2") }
            val first = File(sysfs.root, "strelay").apply { mkdirs() }
            val second = File(sysfs.root, "st_relay").apply { mkdirs() }
            File(first, "relay1").writeText("0"); File(second, "relay1").writeText("0"); File(second, "relay2").writeText("0")
            val root = object : RootShell {
                override fun available() = true
                override fun runOutput(cmd: String): String? = null
                override fun runBytes(cmd: String): ByteArray? = null
                override fun run(cmd: String): Boolean {
                    val process = ProcessBuilder("sh", "-c", cmd.replace("/sys/class/", "${sysfs.root.absolutePath}/")).start()
                    return process.waitFor() == 0
                }
            }
            val relay = controller(wire, root, fallbacks = listOf(fallback))
            assertEquals(if (primary == "1") 1 else 2, relay.count())
            assertTrue(relay.set(1, true))
            assertEquals(if (primary == "1") "1" else "0", File(first, "relay1").readText())
            assertEquals(if (primary == "1") "0" else "1", File(second, "relay1").readText())
            assertEquals(if (primary == "1") listOf("RELAYREAD STRELAY COUNT") else listOf("RELAYREAD STRELAY COUNT", "RELAYREAD ST_RELAY COUNT"), wire.bytes.map { it.second })
        }
    }

    @Test fun unknownTopologyIsRetriedAndSuccessfulTopologyIsCached() {
        val wire = wire().apply { count("STRELAY", "?"); count("ST_RELAY", "0") }
        val relay = controller(wire, fallbacks = listOf(fallback))
        assertEquals(0, relay.count())
        wire.count("STRELAY", "2")
        assertEquals(2, relay.count()); assertEquals(2, relay.count())
        assertEquals(listOf("RELAYREAD STRELAY COUNT", "RELAYREAD ST_RELAY COUNT", "RELAYREAD STRELAY COUNT"), wire.bytes.map { it.second })
    }

    @Test fun absenceIsCachedOnlyWhenEveryCandidateIsKnownEmpty() {
        val wire = wire().apply { count("STRELAY", "0"); count("ST_RELAY", "0") }
        val relay = controller(wire, fallbacks = listOf(fallback))
        repeat(3) { assertEquals(0, relay.count()) }
        assertEquals(2, wire.bytes.size)
    }

    @Test fun unsupportedCustomBaseIsUnavailableWithoutPathSubmissionOrRootFallback() {
        val wire = wire(); val root = FakeRootShell()
        val relay = controller(wire, root, primary = "/sys/class/custom")
        repeat(3) { assertEquals(0, relay.count()); assertNull(relay.read(1)); assertFalse(relay.set(1, true)) }
        assertTrue(wire.bytes.isEmpty()); assertTrue(root.ran.isEmpty()); assertTrue(root.outputRan.isEmpty())
    }

    @Test fun relayStateIsFreshWhileTopologyIsCachedAndUnknownIsNotOff() {
        val wire = wire().apply { count("STRELAY", "2"); value("STRELAY", 1, "1") }
        val root = FakeRootShell(); val relay = controller(wire, root)
        assertTrue(relay.available()); assertEquals(true, relay.read(1))
        wire.value("STRELAY", 1, "0"); assertEquals(false, relay.read(1))
        wire.value("STRELAY", 1, "?"); assertNull(relay.read(1)); assertFalse(relay.get(1))
        assertNull(relay.read(0)); assertNull(relay.read(3)); assertFalse(relay.set(3, true))
        assertEquals(1, wire.bytes.count { it.second.endsWith("COUNT") })
        assertEquals(4, wire.bytes.count { it.second == "RELAYREAD STRELAY 1" })
        assertTrue(root.outputRan.isEmpty()); assertTrue(root.ran.isEmpty())
    }

    @Test fun missingOldRefusedAndMalformedHelperNeverFallsBackToRoot() {
        for (mode in 0..3) {
            val wire = wire().apply {
                count("STRELAY", "2"); gpio(147, "1", "1")
                when (mode) {
                    0 -> missing = true
                    1 -> version = "HELPER version=1.10.0 proto=1.10"
                    2 -> { reply("RELAYREAD STRELAY COUNT", "ERR\n"); reply("GPIOREAD 147", "ERR\n") }
                    3 -> { reply("RELAYREAD STRELAY COUNT", "RELAYREAD STRELAY COUNT=02\n"); reply("GPIOREAD 147", "GPIOREAD 148 VALUE=1 PREPARED=1\n") }
                }
            }
            val root = FakeRootShell(); val relay = controller(wire, root, leds = 147)
            repeat(2) { assertEquals(0, relay.count()); assertNull(relay.read(1)); assertNull(relay.ledRead(0)); relay.warmUp() }
            assertTrue(root.ran.isEmpty()); assertTrue(root.outputRan.isEmpty()); assertTrue(root.isolatedOutputRan.isEmpty()); assertEquals(0, root.availabilityChecks)
        }
    }

    @Test fun explicitRelayWritesUseResolvedHelperTopologyAndKeepFailuresVisible() {
        val wire = wire().apply { count("STRELAY", "2") }; val root = FakeRootShell()
        val relay = controller(wire, root)
        assertTrue(relay.set(1, true)); assertTrue(relay.set(2, false))
        root.runResult = false; assertFalse(relay.set(1, false))
        assertEquals(listOf("printf '%s' '1' > $base/relay1", "printf '%s' '0' > $base/relay2", "printf '%s' '0' > $base/relay1"), root.ran)
        assertTrue(root.outputRan.isEmpty())
    }

    @Test fun declaredLedCountAndOutOfRangeAccessNeverProbeOrPrepareOtherPins() {
        val wire = wire(); val root = ActuatorRoot(); val relay = controller(wire, root, primary = null, leds = 147)
        assertEquals(4, relay.ledCount()); assertNull(relay.ledRead(-1)); assertNull(relay.ledRead(4))
        assertFalse(relay.ledSet(-1, true)); assertFalse(relay.ledSet(4, true))
        assertTrue(wire.bytes.isEmpty()); assertTrue(root.prepared.isEmpty()); assertTrue(root.writes.isEmpty())
    }

    @Test fun explicitLedWritesPrepareOnceAndFailedWriteWithdrawsTheProof() {
        val root = ActuatorRoot(); val relay = controller(wire(), root, primary = null, leds = 147)
        assertTrue(relay.ledSet(0, true)); assertTrue(relay.ledSet(0, false))
        assertEquals(listOf(147), root.prepared)
        root.writeOk = false; assertFalse(relay.ledSet(0, true))
        root.writeOk = true; assertTrue(relay.ledSet(0, true))
        assertEquals(listOf(147, 147), root.prepared)
        assertEquals(4, root.writes.size)
    }

    @Test fun failedPreparationIsRetryableAndRestartDoesNotInheritPreparation() {
        val root = ActuatorRoot().apply { prepareOk = false }
        val relay = controller(wire(), root, primary = null, leds = 147)
        assertFalse(relay.ledSet(0, true)); assertTrue(root.writes.isEmpty())
        root.prepareOk = true; assertTrue(relay.ledSet(0, true))
        assertTrue(controller(wire(), root, primary = null, leds = 147).ledSet(0, true))
        assertEquals(listOf(147, 147, 147), root.prepared)
    }

    @Test fun readingValueDoesNotSeedPreparationEvenWhenHelperReportsPrepared() {
        val wire = wire().apply { gpio(147, "1", "1") }; val root = ActuatorRoot()
        val relay = controller(wire, root, primary = null, leds = 147)
        repeat(3) { assertEquals(true, relay.ledRead(0)) }
        assertTrue(relay.ledSet(0, true)); assertEquals(listOf(147), root.prepared)
        wire.gpio(147, "0", "0"); assertEquals(false, relay.ledRead(0))
        wire.gpio(147, "?", "1"); assertNull(relay.ledRead(0))
    }

    @Test fun warmUpUsesOnlyIndependentPreparedProofAndLeavesOtherPinsLazy() {
        val wire = wire().apply { gpio(147, "1", "0"); gpio(148, "?", "1"); gpio(149, "0", "?"); gpio(150, "1", "1") }
        val root = ActuatorRoot(); val relay = controller(wire, root, primary = null, leds = 147)
        relay.warmUp()
        assertTrue(root.prepared.isEmpty()); assertTrue(root.writes.isEmpty())
        (0..3).forEach { assertTrue(relay.ledSet(it, true)) }
        assertEquals(listOf(147, 149), root.prepared)
        assertEquals(4, wire.bytes.size)
    }

    @Test fun failedAndThrowingWarmUpLeaveExplicitPreparationAvailable() {
        for (fail in listOf(false, true)) {
            val daemon = object : Daemon by FakeDaemon() {
                override fun gpioRead(pin: Int, timeoutMs: Long): HelperGpioState? = if (fail) error("failed helper") else null
            }
            val root = ActuatorRoot(); val relay = RelayController(fakeProfile(buttonLedGpioBase = 147), root, daemon)
            relay.warmUp(); assertTrue(relay.ledSet(0, true)); assertTrue(relay.ledSet(0, false))
            assertEquals(listOf(147), root.prepared)
        }
    }

    @Test fun warmUpSharesFiveSecondsAndDoesNotAcceptAReplyAfterItsBudget() {
        var now = 0L
        val timeouts = mutableListOf<Long>(); val pins = mutableListOf<Int>()
        val daemon = object : Daemon by FakeDaemon() {
            override fun gpioRead(pin: Int, timeoutMs: Long): HelperGpioState {
                pins += pin; timeouts += timeoutMs; now += timeoutMs * 1_000_000L
                return HelperGpioState(null, true)
            }
        }
        val root = ActuatorRoot()
        val relay = RelayController(fakeProfile(buttonLedGpioBase = 147), root, daemon, nowNs = { now })
        relay.warmUp()
        assertEquals(listOf(147, 148, 149, 150), pins)
        assertEquals(listOf(1_500L, 1_500L, 1_500L, 500L), timeouts)
        assertEquals(5_000_000_000L, now)
        assertTrue(relay.ledSet(2, true)); assertTrue(relay.ledSet(3, true))
        assertEquals(listOf(150), root.prepared)
    }

    @Test(timeout = 10_000) fun blockedTopologyAndStateReadsNeverHoldTheActuatorMonitor() {
        for (operation in listOf("count", "read", "ledRead")) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val root = ActuatorRoot()
            val daemon = object : Daemon by FakeDaemon() {
                override fun relayCount(relayClass: HelperRelayClass): Int? {
                    if (operation == "count") { entered.countDown(); release.await() }
                    return 1
                }
                override fun relayRead(relayClass: HelperRelayClass, relay: Int): Boolean? { entered.countDown(); release.await(); return true }
                override fun gpioRead(pin: Int, timeoutMs: Long): HelperGpioState { entered.countDown(); release.await(); return HelperGpioState(true, true) }
            }
            val relay = RelayController(fakeProfile(relayBase = base, buttonLedGpioBase = 147), root, daemon)
            val worker = Thread { when (operation) { "count" -> relay.count(); "read" -> relay.read(1); else -> relay.ledRead(0) } }.apply { isDaemon = true; start() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(ledSetWithin(relay))
                assertEquals(listOf(147), root.prepared)
            } finally { release.countDown(); worker.join(2_000) }
            assertFalse(worker.isAlive)
        }
    }

    @Test(timeout = 10_000) fun failedWriteOrPreparationWhileWarmUpIsBlockedCannotBeReprovedByItsStaleReply() {
        for (preparationFailure in listOf(false, true)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val daemon = object : Daemon by FakeDaemon() {
                override fun gpioRead(pin: Int, timeoutMs: Long): HelperGpioState {
                    if (pin == 147) { entered.countDown(); release.await() }
                    return HelperGpioState(null, pin == 147)
                }
            }
            val root = ActuatorRoot().apply { if (preparationFailure) prepareOk = false else writeOk = false }
            val relay = RelayController(fakeProfile(buttonLedGpioBase = 147), root, daemon)
            val worker = Thread { relay.warmUp() }.apply { isDaemon = true; start() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(ledSetWithin(relay))
            } finally { release.countDown(); worker.join(2_000) }
            assertFalse(worker.isAlive)
            root.prepareOk = true; root.writeOk = true; assertTrue(relay.ledSet(0, true))
            assertEquals(listOf(147, 147), root.prepared)
        }
    }

    private fun ledSetWithin(relay: RelayController): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit(Callable { relay.ledSet(0, true) }).get(2, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    private class ActuatorRoot : RootShell by FakeRootShell() {
        var prepareOk = true; var writeOk = true
        val prepared = mutableListOf<Int>(); val writes = mutableListOf<Pair<String, String>>()
        override fun prepareOutputGpio(gpio: Int): Boolean { prepared += gpio; return prepareOk }
        override fun writeSysfs(path: String, value: String): Boolean { writes += path to value; return writeOk }
    }
}
