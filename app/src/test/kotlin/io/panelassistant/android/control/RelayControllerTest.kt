package io.panelassistant.android.control

import io.panelassistant.android.platform.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * RelayController over the RootShell seam — base resolution (primary vs firmware-renamed fallback),
 * relay read/write, inert-when-unprofiled, and button-LED gpio counting — with no device.
 */
class RelayControllerTest {

    private val base = "/sys/class/strelay"

    private fun relay(
        outputs: Map<String, String>,
        relayBase: String? = base,
        fallbacks: List<String> = emptyList(),
        ledBase: Int? = null,
    ): Pair<RelayController, FakeRootShell> {
        val root = FakeRootShell(outputs)
        return RelayController(
            fakeProfile(relayBase = relayBase, relayBaseFallbacks = fallbacks, buttonLedGpioBase = ledBase),
            root,
        ) to root
    }

    @Test fun countsRelayNodesUnderTheBase() {
        val (r, _) = relay(mapOf("ls $base" to "relay1 relay2 power"))
        assertEquals(2, r.count())
        assertTrue(r.available())
    }

    @Test fun inertWithNoRelayBase() {
        val (r, root) = relay(emptyMap(), relayBase = null)
        assertEquals(0, r.count())
        assertFalse(r.available())
        assertFalse(r.set(1, true))
        assertTrue(root.ran.isEmpty())
    }

    @Test fun setWritesRelayNode() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2"))
        assertTrue(r.set(1, true))
        assertTrue(root.ran.contains("printf '%s' '1' > $base/relay1"))
        assertTrue(r.set(2, false))
        assertTrue(root.ran.contains("printf '%s' '0' > $base/relay2"))
    }

    @Test fun getReadsRelayState() {
        val (r, _) = relay(mapOf("ls $base" to "relay1", "cat $base/relay1" to "1"))
        assertTrue(r.get(1))
    }

    @Test fun stateAuditsReuseOneRelayTopologyProbe() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2", "cat $base/relay1" to "1"))
        assertEquals(2, r.count())
        repeat(5) { assertTrue(r.read(1) == true) }
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
        assertEquals(5, root.outputRan.count { it.contains("cat $base/relay1") })
    }

    @Test fun unavailableRelayProbeIsRetriedThenSuccessfulTopologyIsCached() {
        val root = SequencedRootShell(mapOf("ls $base" to listOf(null, "relay1 relay2")))
        val r = RelayController(fakeProfile(relayBase = base), root)

        assertEquals(0, r.count())
        assertEquals(2, r.count())
        assertEquals(2, r.count())
        assertEquals(2, root.outputRan.count { it.contains("ls $base") })
    }

    @Test fun capturedUnavailableRootRouteSkipsDiscoveryWithoutCachingAbsence() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2"))

        assertEquals(0, r.count(allowRootProbe = false))
        assertTrue(root.outputRan.isEmpty())
        assertEquals(2, r.count(allowRootProbe = true))
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
        assertEquals(2, r.count(allowRootProbe = false))
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
    }

    @Test fun confirmedRelayAbsenceIsCached() {
        val root = SequencedRootShell(mapOf("ls $base" to listOf("power")))
        val r = RelayController(fakeProfile(relayBase = base), root)

        assertEquals(0, r.count())
        assertEquals(0, r.count())
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
    }

    @Test fun fallbackBaseUsedWhenPrimaryHasNoNodes() {
        val alt = "/sys/class/st_relay"
        val (r, _) = relay(
            outputs = mapOf("ls $base" to "", "ls $alt" to "relay1"),
            fallbacks = listOf(alt),
        )
        assertEquals(1, r.count())
    }

    @Test fun relayDiscoveryStopsAtTheFirstMissingIndex() {
        val (r, _) = relay(mapOf("ls $base" to "relay1 relay3 relay99"))

        assertEquals(1, r.count())
    }

    @Test fun relayDiscoveryRequiresCanonicalNodeNames() {
        val (r, _) = relay(mapOf("ls $base" to "relay01 relay-1 relayx"))

        assertEquals(0, r.count())
    }

    @Test fun outOfRangeRelayNeverReachesTheRootWriter() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2"))

        assertFalse(r.set(0, true))
        assertFalse(r.set(3, true))
        assertEquals(null, r.read(3))
        assertFalse(root.ran.any { it.contains("relay0") || it.contains("relay3") })
        assertFalse(root.outputRan.any { it.contains("cat $base/relay3") })
    }

    @Test fun ledCountUsesDeclaredTopologyWithoutMutatingGpio() {
        val ledBase = 147
        val (r, root) = relay(emptyMap(), ledBase = ledBase)
        assertEquals(4, r.ledCount())
        assertTrue(root.outputRan.isEmpty())
    }

    @Test fun stateAuditsDoNotExportOrChangeButtonLedTopology() {
        val ledBase = 147
        val outputs = mapOf("cat /sys/class/gpio/gpio147/value" to "1")
        val (r, root) = relay(outputs, ledBase = ledBase)
        assertEquals(4, r.ledCount())
        repeat(3) { assertTrue(r.ledRead(0) == true) }
        assertTrue(root.ran.isEmpty())
        assertEquals(0, root.outputRan.count { it.contains("/sys/class/gpio/export") })
        assertEquals(3, root.outputRan.count { it.contains("cat /sys/class/gpio/gpio147/value") })
    }

    @Test fun gpioIsPreparedOnlyWhenAnLedWriteIsRequested() {
        val ledBase = 147
        val root = SequencedRootShell(mapOf("gpio147" to listOf("ready")))
        val r = RelayController(fakeProfile(buttonLedGpioBase = ledBase), root)

        assertEquals(4, r.ledCount())
        assertTrue(root.outputRan.isEmpty())
        assertTrue(r.ledSet(0, true))
        assertEquals(1, root.outputRan.count { it.contains("gpio147") })
        assertTrue(root.ran.contains("printf '%s' '1' > /sys/class/gpio/gpio147/value"))
    }

    @Test fun directionFailureDoesNotExposeInputPinAsUsable() {
        val ledBase = 147
        val root = SequencedRootShell(mapOf("gpio147" to listOf("input")))
        val r = RelayController(fakeProfile(buttonLedGpioBase = ledBase), root)

        assertEquals(4, r.ledCount())
        assertFalse(r.ledSet(0, true))
        assertTrue(root.ran.isEmpty())
        assertEquals(1, root.outputRan.size)
        root.outputRan.forEach { command ->
            assertTrue(command.contains("cat /sys/class/gpio/gpio147/direction"))
            assertTrue(command.contains("[ \"\$direction\" = out ]"))
            assertTrue(command.contains("[ -w /sys/class/gpio/gpio147/value ]"))
        }
    }

    @Test fun provenGpioPreparationIsReusedAcrossWrites() {
        val root = SequencedRootShell(mapOf("gpio147" to listOf("ready")))
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

        assertTrue(r.ledSet(0, true))
        assertTrue(r.ledSet(0, false))

        // Issue #93: one privileged preparation round trip for the pin, not one per command.
        assertEquals(1, root.outputRan.count { it.contains("gpio147") })
        assertEquals(2, root.ran.count { it.contains("/sys/class/gpio/gpio147/value") })
    }

    @Test fun aFailedValueWriteInvalidatesTheProvenPreparation() {
        val root = SequencedRootShell(
            outputs = mapOf("gpio147" to listOf("ready")),
            runResults = listOf(false, true),
        )
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

        assertFalse(r.ledSet(0, true))
        assertTrue(r.ledSet(0, true))

        // The failed write may mean the proven export/direction no longer holds; re-verify it.
        assertEquals(2, root.outputRan.count { it.contains("gpio147") })
    }

    @Test fun aFailedPreparationIsNeverCached() {
        val root = SequencedRootShell(mapOf("gpio147" to listOf("input", "ready")))
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

        assertFalse(r.ledSet(0, true))
        assertTrue(r.ledSet(0, true))

        assertEquals(2, root.outputRan.count { it.contains("gpio147") })
    }

    @Test fun preparationDoesNotSurviveAControllerRestart() {
        val root = SequencedRootShell(mapOf("gpio147" to listOf("ready", "ready")))

        assertTrue(RelayController(fakeProfile(buttonLedGpioBase = 147), root).ledSet(0, true))
        assertTrue(RelayController(fakeProfile(buttonLedGpioBase = 147), root).ledSet(0, true))

        // A restart forgets proven pins, so a reboot-reset GPIO is re-verified on first use.
        assertEquals(2, root.outputRan.count { it.contains("gpio147") })
    }

    @Test fun aSuccessfulReadNeverProvesPreparation() {
        val root = SequencedRootShell(
            mapOf(
                "cat /sys/class/gpio/gpio147/value" to listOf("1"),
                "gpio147" to listOf("ready"),
            ),
        )
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

        repeat(3) { assertTrue(r.ledRead(0) == true) }
        assertTrue(r.ledSet(0, true))

        // Reads must not populate the preparation cache — only a proven `ready` write path may.
        assertEquals(1, root.outputRan.count { it.contains("/sys/class/gpio/export") || it.contains("direction") })
    }

    @Test fun outOfRangeButtonLedNeverAddressesAnotherGpio() {
        val ledBase = 147
        val outputs = (0 until 4).associate { "gpio${ledBase + it}" to "ready" }
        val (r, root) = relay(outputs, ledBase = ledBase)

        assertFalse(r.ledSet(-1, true))
        assertFalse(r.ledSet(4, true))
        assertEquals(null, r.ledRead(4))
        assertFalse(root.ran.any { it.contains("gpio146") || it.contains("gpio151") })
        assertFalse(root.outputRan.any { it.contains("gpio146") || it.contains("gpio151") })
    }

    // --- Issue #93 startup warm-up ---

    @Test fun warmUpLetsTheFirstCommandAfterStartSkipPreparation() {
        val root = LaneRootShell(warmUpReply = { "147\n148\n149\n150\n" })
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

        r.warmUp()
        assertTrue(r.ledSet(0, true))
        assertTrue(r.ledSet(3, false))

        assertEquals(0, root.preparations())
        assertEquals(
            listOf(
                "printf '%s' '1' > /sys/class/gpio/gpio147/value",
                "printf '%s' '0' > /sys/class/gpio/gpio150/value",
            ),
            root.ran,
        )
    }

    @Test fun warmUpIsReadOnlyAndOffTheInteractiveLane() {
        val root = LaneRootShell(warmUpReply = { "147\n" })
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

        r.warmUp()

        assertTrue(root.ran.isEmpty())
        assertTrue(root.outputRan.isEmpty())
        assertEquals(1, root.isolatedRan.size)
        val probe = root.isolatedRan.single()
        (147..150).forEach { assertTrue(probe.contains("/sys/class/gpio/gpio$it/direction")) }
        assertFalse(probe.contains("/sys/class/gpio/export"))
        // Only stderr redirections: no export, direction or value write.
        assertFalse(Regex("(^|[^2])>").containsMatchIn(probe))
    }

    @Test fun warmUpProvesOnlyTheReportedPins() {
        val root = LaneRootShell(warmUpReply = { "148\n" })
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

        r.warmUp()
        assertTrue(r.ledSet(0, true))
        assertTrue(r.ledSet(1, true))

        // After a reboot nothing is exported: unreported pins still prepare lazily.
        assertEquals(listOf(147), root.preparedPins)
    }

    @Test fun warmUpIsInertWithoutDeclaredButtonLeds() {
        val root = LaneRootShell(warmUpReply = { "147\n" })

        RelayController(fakeProfile(relayBase = base), root).warmUp()

        assertTrue(root.isolatedRan.isEmpty() && root.outputRan.isEmpty() && root.ran.isEmpty())
    }

    @Test fun aFailedOrThrowingWarmUpLeavesTheLazyPathIntact() {
        for (reply in listOf<() -> String?>({ null }, { throw IllegalStateException("probe failed") })) {
            val root = LaneRootShell(warmUpReply = reply)
            val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)

            r.warmUp()
            assertTrue(r.ledSet(0, true))
            assertTrue(r.ledSet(0, false))

            assertEquals(listOf(147), root.preparedPins)
        }
    }

    @Test(timeout = 10_000)
    fun aBlockedWarmUpNeverDelaysARealCommandWhichReverifies() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = LaneRootShell(warmUpReply = {
            entered.countDown()
            release.await()
            "147\n"
        })
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)
        val warmUp = startWarmUp(r)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // The warm-up is still blocked: the command must complete on its own, re-verifying the pin.
            assertEquals("the command waited behind the warm-up", true, ledSetWithin(r, 0, true))
            assertEquals(listOf(147), root.preparedPins)
        } finally {
            release.countDown()
            warmUp.join(5_000)
        }
    }

    @Test(timeout = 10_000)
    fun aFailureDuringWarmUpStillForcesReverification() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = LaneRootShell(
            warmUpReply = {
                entered.countDown()
                release.await()
                "147\n"   // observed before the write below failed
            },
            runResults = listOf(false, true, true),
        )
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)
        val warmUp = startWarmUp(r)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("the command waited behind the warm-up", false, ledSetWithin(r, 0, true))
        } finally {
            release.countDown()
            warmUp.join(5_000)
        }
        assertFalse("the warm-up must finish before the next command", warmUp.isAlive)
        assertTrue(r.ledSet(0, true))

        // The stale warm-up proof must not re-prove the pin that failure invalidated.
        assertEquals(listOf(147, 147), root.preparedPins)
    }

    @Test(timeout = 10_000)
    fun aFailedPreparationDuringWarmUpStillForcesReverification() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = LaneRootShell(
            warmUpReply = {
                entered.countDown()
                release.await()
                "147\n"   // observed before the preparation below failed
            },
            prepareReplies = listOf("input", "ready"),
        )
        val r = RelayController(fakeProfile(buttonLedGpioBase = 147), root)
        val warmUp = startWarmUp(r)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("the command waited behind the warm-up", false, ledSetWithin(r, 0, true))
        } finally {
            release.countDown()
            warmUp.join(5_000)
        }
        assertFalse("the warm-up must finish before the next command", warmUp.isAlive)
        assertTrue(r.ledSet(0, true))

        assertEquals(listOf(147, 147), root.preparedPins)
    }

    private fun startWarmUp(r: RelayController) = Thread { r.warmUp() }.apply { isDaemon = true; start() }

    /** [RelayController.ledSet] from another thread, or null when it was still waiting after two seconds. */
    private fun ledSetWithin(r: RelayController, i: Int, on: Boolean): Boolean? {
        val command = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
        return try {
            command.submit(Callable { r.ledSet(i, on) }).get(2, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            null
        } finally {
            command.shutdown()
        }
    }

    /**
     * A root lane shaped like production: [run]/[runOutput] share one monitor (the interactive persistent
     * shell), while [runOutputIsolatedBounded] is independent of it. A warm-up that took the interactive
     * lane or the controller monitor would therefore block a concurrent command in these tests.
     */
    private class LaneRootShell(
        private val warmUpReply: () -> String?,
        runResults: List<Boolean> = emptyList(),
        prepareReplies: List<String> = emptyList(),
    ) : RootShell {
        private val lane = Any()
        private val runResults = runResults.toMutableList()
        private val prepareReplies = prepareReplies.toMutableList()
        val ran = mutableListOf<String>()
        val outputRan = mutableListOf<String>()
        val isolatedRan = mutableListOf<String>()
        val preparedPins = mutableListOf<Int>()

        fun preparations() = synchronized(lane) { preparedPins.size }

        override fun available(): Boolean = true

        override fun run(cmd: String): Boolean = synchronized(lane) {
            ran += cmd
            if (runResults.isNotEmpty()) runResults.removeAt(0) else true
        }

        override fun runOutput(cmd: String): String? = synchronized(lane) {
            outputRan += cmd
            Regex("\\[ -e /sys/class/gpio/gpio(\\d+) ]").find(cmd)?.let {
                preparedPins += it.groupValues[1].toInt()
                if (prepareReplies.isNotEmpty()) prepareReplies.removeAt(0) else "ready"
            }
        }

        override fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long): String? {
            synchronized(isolatedRan) { isolatedRan += cmd }
            return warmUpReply()
        }

        override fun runBytes(cmd: String): ByteArray? = null

        override fun fireAndForget(cmd: String): Boolean = run(cmd)
    }

    private class SequencedRootShell(
        outputs: Map<String, List<String?>>,
        runResults: List<Boolean> = emptyList(),
    ) : RootShell {
        private val outputs = outputs.mapValues { (_, values) -> values.toMutableList() }
        private val runResults = runResults.toMutableList()
        val ran = mutableListOf<String>()
        val outputRan = mutableListOf<String>()

        override fun available(): Boolean = true

        override fun run(cmd: String): Boolean {
            ran += cmd
            return if (runResults.isNotEmpty()) runResults.removeAt(0) else true
        }

        override fun runOutput(cmd: String): String? {
            outputRan += cmd
            val responses = outputs.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { cmd.contains(it.key) }
                ?.value
                ?: return null
            return if (responses.size > 1) responses.removeAt(0) else responses.firstOrNull()
        }

        override fun runBytes(cmd: String): ByteArray? = null

        override fun fireAndForget(cmd: String): Boolean {
            ran += cmd
            return true
        }
    }
}
