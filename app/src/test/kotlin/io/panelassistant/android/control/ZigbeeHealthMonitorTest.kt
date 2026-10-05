package io.panelassistant.android.control

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZigbeeHealthMonitorTest {
    private fun observation(
        layout: ZigbeeGatewayLayout = ZigbeeGatewayLayout.VENDOR_NATIVE,
        pid: Int? = 10,
        cpu: Double? = 3.3,
        guardCpu: Double? = 0.1,
        role: String? = "Repeater",
        netInfo: ZigbeeNetInfo? = ZigbeeNetInfo(7, 123, 0x1234),
    ) = ZigbeeGatewayObservation(
        present = true,
        layout = layout,
        packageVersion = "sonoff-3.5.0",
        productVersion = "3.8.0",
        gatewayPid = pid,
        gatewayCpu = cpu,
        guardPid = 11,
        guardCpu = guardCpu,
        role = role,
        netInfo = netInfo,
        recursiveWatchdogAssignment = false,
    )

    @Test fun cpuNormalizationSupportsWholeMachineAndOneCoreScales() {
        assertEquals(86.0, normalizeGatewayCpu(86.0, 4, false)!!, 0.01)
        assertEquals(86.0, normalizeGatewayCpu(21.5, 4, true)!!, 0.01)
        assertEquals(80.0, gatewayCpuFromJiffyDelta(20, 100, 4)!!, 0.01)
        assertNull(normalizeGatewayCpu(-1.0, 4, false))
        assertNull(gatewayCpuFromJiffyDelta(1, 0, 4))
    }

    @Test fun netInfoParserIsBoundedAndReturnsOnlyHealthFields() {
        val parsed = ZigbeeNetInfoParser.parse(
            """noise {"networkKey":"secret","radioMac":"aa:bb","netinfo":{"roleType":0,"nodeId":"0","panId":"0xffff"}}""",
        )
        assertTrue(parsed!!.explicitlyInvalid)
        assertFalse(parsed.toString().contains("secret"))
        assertNull(ZigbeeNetInfoParser.parse("{".padEnd(5000, 'x')))
    }

    @Test fun canonicalNetInfoShapeDetectsInvalidAndValidPanIds() {
        val invalid = ZigbeeNetInfoParser.parse(
            """{"sdk":"v6.78","stackprofile":2,"panid":65535,"mac":"private","key":"secret"}""",
        )
        assertTrue(invalid!!.explicitlyInvalid)
        assertEquals(0xffffL, invalid.panId)
        assertFalse(invalid.toString().contains("secret"))
        assertFalse(invalid.toString().contains("private"))

        val joinedPan = ZigbeeNetInfoParser.parse("""{"panid":22335,"channel":15}""")
        assertEquals(22335L, joinedPan!!.panId)
        assertFalse(joinedPan.explicitlyInvalid)
    }

    @Test fun netInfoAcquisitionPrefersCanonicalFileAndBoundsBothSources() {
        val command = zigbeeNetInfoCommand("/vendor/bin/siliconlabs_host")
        assertTrue(command.contains("[ -s /data/vendor/siliconlabs_host/netinfo ]"))
        assertTrue(command.contains("head -c 4097 /data/vendor/siliconlabs_host/netinfo"))
        assertTrue(command.contains("/vendor/bin/siliconlabs_host/zgateway.log"))
        assertTrue(command.endsWith("head -c 4097; fi"))
    }

    @Test fun mqttAttributesExcludeRawSensitiveGatewayData() {
        val attributes = ZigbeeHealthSnapshot(
            state = ZigbeeHealthState.HEALTHY,
            joined = true,
            role = "Repeater",
        ).mqttAttributes()
        assertFalse(attributes.contains("network_key"))
        assertFalse(attributes.contains("password"))
        assertFalse(attributes.contains("radio_mac"))
        assertFalse(attributes.contains("netinfo"))
    }

    @Test fun startupGracePreventsContainmentUntilItExpires() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 15 * 60_000L)
        policy.resetGrace(1)
        repeat(5) {
            val decision = policy.evaluate(
                1 + it * 60_000L,
                true,
                observation(cpu = 90.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
            )
            assertEquals(ZigbeeHealthState.STARTING, decision.state)
            assertFalse(decision.shouldContain)
        }
        repeat(4) {
            val after = policy.evaluate(
                15 * 60_000L + 1 + it,
                true,
                observation(cpu = 90.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
            )
            assertFalse(after.shouldContain)
        }
        val after = policy.evaluate(
            15 * 60_000L + 10,
            true,
            observation(cpu = 90.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
        )
        assertTrue(after.shouldContain)
    }

    @Test fun fiveConsecutiveHighCpuSamplesAreRequired() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        repeat(4) {
            assertFalse(
                policy.evaluate(
                    2 + it.toLong(),
                    true,
                    observation(cpu = 51.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
                ).shouldContain,
            )
        }
        assertTrue(
            policy.evaluate(
                10,
                true,
                observation(cpu = 51.0, role = null, netInfo = ZigbeeNetInfo(0, 0, 0xffff)),
            ).shouldContain,
        )
    }

    @Test fun threePidChangesWithinTenMinutesDeclareRunaway() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        listOf(10, 11, 12, 13).forEachIndexed { index, pid ->
            val decision = policy.evaluate(
                2 + index * 60_000L,
                true,
                observation(pid = pid, cpu = 2.0),
            )
            if (pid == 13) assertTrue(decision.shouldContain)
        }
    }

    @Test fun sampledDownTransitionsStillCountDifferentPidRestarts() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        var now = 2L
        var decision = policy.evaluate(now++, true, observation(pid = 10, cpu = 2.0))
        listOf(11, 12, 13).forEach { pid ->
            decision = policy.evaluate(now++, true, observation(pid = null, cpu = null))
            assertFalse(decision.shouldContain)
            decision = policy.evaluate(now++, true, observation(pid = pid, cpu = 2.0))
        }
        assertEquals(3, decision.restartCount)
        assertTrue(decision.shouldContain)
    }

    @Test fun startupAndConfiguredOffPidChangesDoNotCountAsRestarts() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 100)
        policy.resetGrace(1)
        policy.evaluate(2, true, observation(pid = 10))
        policy.evaluate(3, true, observation(pid = null))
        assertEquals(0, policy.evaluate(4, true, observation(pid = 11)).restartCount)
        assertEquals(0, policy.evaluate(101, true, observation(pid = 11)).restartCount)

        policy.evaluate(102, false, observation(pid = 12))
        policy.evaluate(103, false, observation(pid = null))
        assertEquals(0, policy.evaluate(104, false, observation(pid = 13)).restartCount)
        policy.resetGrace(104)
        assertEquals(0, policy.evaluate(205, true, observation(pid = 13)).restartCount)
    }

    @Test fun joinedHighCpuWarnsWithoutContainment() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        var decision = policy.evaluate(2, true, observation(cpu = 80.0))
        repeat(4) { decision = policy.evaluate(3L + it, true, observation(cpu = 80.0)) }
        assertEquals(ZigbeeHealthState.DEGRADED_HIGH_CPU, decision.state)
        assertFalse(decision.shouldContain)
    }

    @Test fun unknownLayoutFailsSafe() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        var decision = policy.evaluate(
            2, true,
            observation(
                layout = ZigbeeGatewayLayout.UNKNOWN,
                cpu = 90.0,
                role = null,
                netInfo = ZigbeeNetInfo(0, 0, 0xffff),
            ),
        )
        repeat(4) {
            decision = policy.evaluate(
                3L + it,
                true,
                observation(
                    layout = ZigbeeGatewayLayout.UNKNOWN,
                    cpu = 90.0,
                    role = null,
                    netInfo = ZigbeeNetInfo(0, 0, 0xffff),
                ),
            )
        }
        assertEquals(ZigbeeHealthState.UNKNOWN, decision.state)
        assertFalse(decision.shouldContain)
    }

    @Test fun recognizedFourXRunawayCanUseContainment() {
        val policy = ZigbeeHealthPolicy(startupGraceMs = 0)
        policy.resetGrace(1)
        var decision = policy.evaluate(
            2, true,
            observation(
                layout = ZigbeeGatewayLayout.VENDOR_4X,
                cpu = 90.0,
                role = null,
                netInfo = ZigbeeNetInfo(0, 0, 0xffff),
            ),
        )
        repeat(4) {
            decision = policy.evaluate(
                3L + it,
                true,
                observation(
                    layout = ZigbeeGatewayLayout.VENDOR_4X,
                    cpu = 90.0,
                    role = null,
                    netInfo = ZigbeeNetInfo(0, 0, 0xffff),
                ),
            )
        }
        assertTrue(decision.shouldContain)
    }

    @Test fun containmentIsSingleShotAndExplicitRetryRearmsIt() {
        var now = 1L
        var contains = 0
        val source = object : ZigbeeGatewayHealthSource {
            override fun observe() = observation(
                cpu = 90.0,
                role = null,
                netInfo = ZigbeeNetInfo(0, 0, 0xffff),
            )
            override fun contain(layout: ZigbeeGatewayLayout): ZigbeeContainmentResult {
                contains++
                return ZigbeeContainmentResult.COMPLETE
            }
        }
        val monitor = ZigbeeHealthMonitor(
            configuredOn = { true },
            source = source,
            onContain = {},
            onSnapshot = { _, _ -> },
            nowMs = { now++ },
            policy = ZigbeeHealthPolicy(startupGraceMs = 0),
        )
        repeat(6) { monitor.sample() }
        repeat(6) { monitor.sample() }
        assertEquals(1, contains)
        monitor.explicitRetry()
        assertTrue(monitor.awaitIdleForTest())
        repeat(6) { monitor.sample() }
        assertEquals(2, contains)
        monitor.stop()
    }

    @Test fun explicitRetryWaitsBehindActiveSampleAndCannotRacePolicyState() {
        val sampleEntered = CountDownLatch(1)
        val releaseSample = CountDownLatch(1)
        val retryPublished = CountDownLatch(1)
        val source = object : ZigbeeGatewayHealthSource {
            override fun observe(): ZigbeeGatewayObservation {
                sampleEntered.countDown()
                assertTrue(releaseSample.await(2, TimeUnit.SECONDS))
                return observation()
            }

            override fun contain(layout: ZigbeeGatewayLayout) = ZigbeeContainmentResult.FAILED
        }
        val monitor = ZigbeeHealthMonitor(
            configuredOn = { true },
            source = source,
            onContain = {},
            onSnapshot = { snapshot, _ ->
                if (snapshot.state == ZigbeeHealthState.STARTING && snapshot.observedAtMs > 0L) {
                    retryPublished.countDown()
                }
            },
            nowMs = generateSequence(1L) { it + 1L }.iterator()::next,
            policy = ZigbeeHealthPolicy(startupGraceMs = 0),
        )
        try {
            monitor.start()
            assertTrue(sampleEntered.await(2, TimeUnit.SECONDS))
            monitor.explicitRetry()
            assertFalse(retryPublished.await(100, TimeUnit.MILLISECONDS))
            releaseSample.countDown()
            assertTrue(retryPublished.await(2, TimeUnit.SECONDS))
            assertTrue(monitor.awaitIdleForTest())
            assertEquals(ZigbeeHealthState.STARTING, monitor.snapshot().state)
        } finally {
            releaseSample.countDown()
            monitor.stop()
        }
    }

    @Test fun stoppedMonitorSuppressesBlockedSampleContainmentAndPublication() {
        val sampleEntered = CountDownLatch(1)
        val releaseSample = CountDownLatch(1)
        var contains = 0
        var snapshots = 0
        val source = object : ZigbeeGatewayHealthSource {
            override fun observe(): ZigbeeGatewayObservation {
                sampleEntered.countDown()
                while (true) {
                    try {
                        releaseSample.await()
                        break
                    } catch (_: InterruptedException) {
                        // Model a root probe which does not honour executor interruption.
                    }
                }
                return observation(
                    cpu = 90.0,
                    role = null,
                    netInfo = ZigbeeNetInfo(0, 0, 0xffff),
                )
            }

            override fun contain(layout: ZigbeeGatewayLayout): ZigbeeContainmentResult {
                contains++
                return ZigbeeContainmentResult.COMPLETE
            }
        }
        val monitor = ZigbeeHealthMonitor(
            configuredOn = { true },
            source = source,
            onContain = { contains++ },
            onSnapshot = { _, _ -> snapshots++ },
            nowMs = { 1L },
            policy = ZigbeeHealthPolicy(startupGraceMs = 0, requiredHighCpuSamples = 1),
        )
        monitor.start()
        assertTrue(sampleEntered.await(2, TimeUnit.SECONDS))

        val stopThread = Thread(monitor::stop).apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (monitor.lifecycleGeneration() == 1L && System.nanoTime() < deadline) Thread.yield()
        assertTrue(monitor.lifecycleGeneration() > 1L)
        releaseSample.countDown()
        stopThread.join(2_000)

        assertFalse(stopThread.isAlive)
        assertEquals(0, contains)
        assertEquals(0, snapshots)
        assertEquals(ZigbeeHealthState.UNKNOWN, monitor.snapshot().state)
    }

    @Test fun stopReportsAnInterruptIgnoringSampleAsUndrained() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = object : ZigbeeGatewayHealthSource {
            override fun observe(): ZigbeeGatewayObservation {
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Model a diagnostic command which does not honour cancellation.
                    }
                }
                return observation()
            }
            override fun contain(layout: ZigbeeGatewayLayout) = ZigbeeContainmentResult.FAILED
        }
        val monitor = ZigbeeHealthMonitor({ true }, source, {}, { _, _ -> })
        try {
            monitor.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(monitor.stop())
        } finally {
            release.countDown()
        }
    }

    @Test fun slowObservationIsFollowedByAFullDelayInsteadOfACatchUpBurst() {
        val calls = AtomicInteger()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstCompletedAt = AtomicLong()
        val secondStartedAt = AtomicLong()
        val intervalMs = 100L
        val source = object : ZigbeeGatewayHealthSource {
            override fun observe(): ZigbeeGatewayObservation {
                when (calls.incrementAndGet()) {
                    1 -> {
                        firstEntered.countDown()
                        assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                        firstCompletedAt.set(System.nanoTime())
                    }
                    2 -> {
                        secondStartedAt.set(System.nanoTime())
                        secondEntered.countDown()
                    }
                }
                return observation()
            }

            override fun contain(layout: ZigbeeGatewayLayout) = ZigbeeContainmentResult.FAILED
        }
        val monitor = ZigbeeHealthMonitor(
            configuredOn = { true },
            source = source,
            onContain = {},
            onSnapshot = { _, _ -> },
            sampleIntervalMs = intervalMs,
        )
        try {
            monitor.start()
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            Thread.sleep(intervalMs * 2)
            releaseFirst.countDown()
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS))

            val quietMs = TimeUnit.NANOSECONDS.toMillis(secondStartedAt.get() - firstCompletedAt.get())
            assertTrue("slow sample must still be followed by the full quiet interval: ${quietMs}ms", quietMs >= 75L)
        } finally {
            releaseFirst.countDown()
            monitor.stop()
        }
    }

    @Test fun startIsIdempotentAcrossDuplicateServiceCommands() {
        val monitor = ZigbeeHealthMonitor(
            configuredOn = { false },
            source = object : ZigbeeGatewayHealthSource {
                override fun observe() = observation(pid = null)
                override fun contain(layout: ZigbeeGatewayLayout) = ZigbeeContainmentResult.FAILED
            },
            onContain = {},
            onSnapshot = { _, _ -> },
        )
        monitor.start()
        assertEquals(1L, monitor.lifecycleGeneration())
        monitor.start()
        assertEquals(1L, monitor.lifecycleGeneration())
        monitor.stop()
    }
}
