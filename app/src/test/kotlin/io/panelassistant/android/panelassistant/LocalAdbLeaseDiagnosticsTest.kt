package io.panelassistant.android.panelassistant

import io.panelassistant.android.control.ManagedNetworkAdb
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class LocalAdbLeaseDiagnosticsTest {
    @After fun clearOwnership() { ManagedNetworkAdb.managed = false; ManagedNetworkAdb.recoveryHold = false }

    private open class Endpoint : LocalAdbEndpoint {
        @Volatile var status: LocalAdbState? = LocalAdbState(false, false)
        var beatResult: () -> Boolean = { true }
        var armResult = true
        val commands = CopyOnWriteArrayList<String>()
        val nonces = CopyOnWriteArrayList<String>()
        override fun state() = status
        override fun arm(nonce: String): Boolean {
            commands += "arm"; nonces += nonce
            if (armResult) status = LocalAdbState(true, false)
            return armResult
        }
        override fun beat(nonce: String): Boolean { commands += "beat"; nonces += nonce; return beatResult() }
        override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? = true
        override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? = null
    }

    private class Harness(val scope: TestScope) {
        val endpoint = Endpoint()
        var healthy = true
        val lines = mutableListOf<String>()
        val diagnostics = LocalAdbLeaseDiagnostics({ scope.testScheduler.currentTime }, lines::add)
        val runtime = LocalAdbRuntime(scope.backgroundScope, endpoint, { false }, { JSONObject() }, { false },
            StandardTestDispatcher(scope.testScheduler), { it() }, healthy = { healthy }, leaseDiagnostics = diagnostics)
        fun events(kind: String) = lines.map(::fields).filter { it["event"] == kind }
    }

    @Test fun successfulRenewalsReportTheInitialArmBaselineAndEveryBeatWithoutCredentials() = runTest {
        val installedSummary = LocalAdbLeaseDiagnosticHolder.summary()
        val h = Harness(this)
        h.runtime.session("secret-carrier-token", true); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("arm", "beat", "beat"), h.endpoint.commands)
        val beats = h.events("beat")
        assertEquals(2, beats.size)
        assertEquals("-1", beats[0]["gap_ms"])
        assertEquals("30000", beats[0]["renewal_gap_ms"])
        assertEquals("30000", beats[1]["gap_ms"])
        assertEquals("30000", beats[1]["renewal_gap_ms"])
        assertEquals(listOf("1", "2", "3"), h.lines.map(::fields).map { it["seq"] })
        assertTrue(h.lines.all { it.contains("process_id=") && it.contains("runtime_id=") && it.contains("app_build=") && it.contains("helper_build=") })
        assertFalse(h.lines.any { it.contains("secret-carrier-token") || h.endpoint.nonces.any(it::contains) })
        assertTrue(h.diagnostics.summary().contains("beat_gap_count=1 beat_gap_max_ms=30000 beat_gap_p99_upper_ms=30000 beat_gap_p999_upper_ms=30000"))
        assertTrue(h.diagnostics.summary().contains("healthy_renewal_gap_count=2"))
        assertEquals("constructor-only runtimes must not replace the installed diagnostic source", installedSummary, LocalAdbLeaseDiagnosticHolder.summary())
    }

    @Test fun skippedTicksKeepTheLastSuccessfulRenewalAndSeparateUnhealthyIntervals() = runTest {
        val h = Harness(this)
        h.runtime.session("pa", true); runCurrent()
        h.healthy = false
        advanceTimeBy(30_000); runCurrent()
        h.healthy = true; h.endpoint.status = null
        advanceTimeBy(30_000); runCurrent()
        h.endpoint.status = LocalAdbState(true, false); h.endpoint.beatResult = { false }
        advanceTimeBy(30_000); runCurrent()
        h.endpoint.beatResult = { throw IOException("socket unavailable") }
        advanceTimeBy(30_000); runCurrent()
        h.endpoint.beatResult = { true }
        advanceTimeBy(30_000); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("unhealthy", "state_unavailable", "beat_false", "socket_error"), h.events("skip").map { it["reason"] })
        val beats = h.events("beat")
        assertEquals("150000", beats[0]["renewal_gap_ms"])
        assertEquals("true", beats[0]["unhealthy_since_renewal"])
        assertEquals("30000", beats[1]["renewal_gap_ms"])
        assertEquals("false", beats[1]["unhealthy_since_renewal"])
        assertEquals(listOf("arm", "beat", "beat", "beat", "beat"), h.endpoint.commands)
        assertTrue(h.diagnostics.summary().contains("renewal_gap_count=2 renewal_gap_max_ms=150000"))
        assertTrue(h.diagnostics.summary().contains("healthy_renewal_gap_count=1 healthy_renewal_gap_max_ms=30000"))
        for (reason in listOf("unhealthy", "state_unavailable", "beat_false", "socket_error")) assertTrue(h.diagnostics.summary().contains("skip_$reason=1"))
    }

    @Test fun rearmingCensorsThePriorBeatChainAndRecordsTimeUntilTheFirstHealthyArm() = runTest {
        val h = Harness(this)
        h.healthy = false
        h.runtime.session("pa", true); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        h.healthy = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, h.events("arm").size)
        assertEquals("60000", h.events("arm").single()["startup_to_arm_ms"])
        assertEquals("true", h.events("arm").single()["unhealthy_since_renewal"])
        advanceTimeBy(30_000); runCurrent()
        h.endpoint.status = LocalAdbState(false, false)
        advanceTimeBy(30_000); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("arm", "beat", "arm", "beat"), h.endpoint.commands)
        assertEquals(listOf("-1", "-1"), h.events("beat").map { it["gap_ms"] })
        assertEquals(listOf("30000", "30000"), h.events("beat").map { it["renewal_gap_ms"] })
        assertEquals("false", h.events("arm")[1]["first_arm"])
        assertTrue(h.events("beat").all { it["unhealthy_since_renewal"] == "false" })
        assertTrue(h.diagnostics.summary().contains("beat_gap_count=0"))
        assertTrue("reARM intervals must remain in the healthy renewal distribution", h.diagnostics.summary().contains("healthy_renewal_gap_count=3"))
    }

    @Test fun rejectedArmDoesNotCreateASuccessfulRenewalBaseline() = runTest {
        val h = Harness(this)
        h.endpoint.armResult = false
        h.runtime.session("pa", true); runCurrent()
        assertEquals(1, h.events("skip").size)
        assertEquals("arm_false", h.events("skip").single()["reason"])
        assertTrue(h.events("arm").isEmpty())
        h.endpoint.status = LocalAdbState(true, false)
        advanceTimeBy(30_000); runCurrent()
        assertEquals("-1", h.events("beat").single()["renewal_gap_ms"])
        assertTrue(h.diagnostics.summary().contains("renewal_gap_count=0"))
    }

    @Test fun histogramRetainsTheWholeCollectionWindowAndBoundsItsTailPercentiles() = runTest {
        val h = Harness(this)
        h.runtime.session("pa", true); runCurrent()
        // 1000 short gaps followed by one delayed renewal; older observations must remain counted.
        repeat(1000) { advanceTimeBy(30_000); runCurrent() }
        h.endpoint.beatResult = { false }
        repeat(12) { advanceTimeBy(30_000); runCurrent() }
        h.endpoint.beatResult = { true }
        h.endpoint.status = LocalAdbState(false, false)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, h.events("arm").size)
        assertEquals("390000", h.events("arm").last()["renewal_gap_ms"])
        val summary = h.diagnostics.summary()
        assertTrue(summary, summary.contains("renewal_gap_count=1001 renewal_gap_max_ms=390000 renewal_gap_p99_upper_ms=30000 renewal_gap_p999_upper_ms=30000"))
        assertTrue(summary, summary.contains("scope=process_local reset=process_or_runtime_restart"))
        assertTrue(summary, summary.contains("percentiles=fixed_histogram_upper_bounds_ms"))
    }

    @Test fun suspendTimeDoesNotInflateLeaseGapsButRemainsInElapsedRecoveryTiming() = runTest {
        var uptime = 0L
        var elapsed = 0L
        val lines = mutableListOf<String>()
        val diagnostics = LocalAdbLeaseDiagnostics({ uptime }, lines::add, { elapsed })
        val endpoint = Endpoint()
        var healthy = false
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), { it() }, healthy = { healthy }, leaseDiagnostics = diagnostics)
        runtime.session("pa", true); runCurrent()
        healthy = true; uptime = 30_000; elapsed = 330_000
        advanceTimeBy(30_000); runCurrent()
        val arms = lines.map(::fields).filter { it["event"] == "arm" }
        assertEquals(1, arms.size)
        val arm = arms.single()
        assertEquals("30000", arm["startup_to_arm_ms"])
        assertEquals("330000", arm["startup_to_arm_elapsed_ms"])
        uptime = 60_000; elapsed = 660_000
        advanceTimeBy(30_000); runCurrent()
        val beats = lines.map(::fields).filter { it["event"] == "beat" }
        assertEquals(1, beats.size)
        assertEquals("30000", beats.single()["renewal_gap_ms"])
        assertEquals("660000", beats.single()["elapsed_ms"])
        assertEquals("60000", beats.single()["uptime_ms"])
        assertEquals("660000", beats.single()["process_age_ms"])
        assertEquals("uptime_monotonic", beats.single()["gap_clock"])
        assertTrue(diagnostics.summary().contains("renewal_gap_count=1 renewal_gap_max_ms=30000"))
    }

    @Test fun brokenDiagnosticSinksAndClocksPreserveArmBeatAndManagementResults() = runTest {
        for (brokenClock in listOf(false, true)) {
            val endpoint = Endpoint()
            val diagnostics = LocalAdbLeaseDiagnostics({ if (brokenClock) error("clock") else testScheduler.currentTime }, { error("sink") })
            val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject().put("ok", true) }, { false },
                StandardTestDispatcher(testScheduler), { it() }, leaseDiagnostics = diagnostics)
            runtime.session("pa", true); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            val reply = runtime.request("adb.prepare", JSONObject(), { true })
            assertEquals("applied", reply.outcome)
            assertTrue((reply.result as JSONObject).getBoolean("ok"))
            assertEquals(listOf("arm", "beat"), endpoint.commands)
            assertTrue(diagnostics.summary().contains("arms=1 beats=1"))
        }
    }

    @Test fun waitingForManagementEffectsDelaysOneBeatWithoutInventingASkippedTick() = runTest {
        val timer = StandardTestDispatcher(testScheduler)
        val executor = Executors.newFixedThreadPool(2)
        val dispatched = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renewed = CountDownLatch(1)
        val now = AtomicLong()
        var waitingForBeat = false
        val dispatcher = object : CoroutineDispatcher(), Delay {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executor.execute { block.run(); if (waitingForBeat) dispatched.countDown() else ready.countDown() }
            }
            override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
                (timer as Delay).scheduleResumeAfterDelay(timeMillis, continuation)
        }
        val runtimeScope = CoroutineScope(SupervisorJob() + dispatcher)
        val lines = CopyOnWriteArrayList<String>()
        val diagnostics = LocalAdbLeaseDiagnostics(now::get, { lines += it; if (it.contains("event=beat ")) renewed.countDown() })
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(runtimeScope, endpoint, { false }, {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); JSONObject()
        }, { false }, dispatcher, { it() }, leaseDiagnostics = diagnostics)
        try {
            runtime.session("pa", true); assertTrue(ready.await(5, TimeUnit.SECONDS))
            val request = async(start = CoroutineStart.UNDISPATCHED) { runtime.request("adb.prepare", JSONObject(), { true }) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waitingForBeat = true; now.set(30_000)
            advanceTimeBy(30_000); runCurrent()
            assertTrue("tick must reach the occupied effects queue", dispatched.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("arm"), endpoint.commands)
            now.set(42_000); release.countDown()
            assertEquals("applied", request.await().outcome)
            assertTrue(renewed.await(5, TimeUnit.SECONDS))
            assertEquals("12000", lines.map(::fields).single { it["event"] == "lock_wait" }["wait_ms"])
            assertEquals("42000", lines.map(::fields).single { it["event"] == "beat" }["renewal_gap_ms"])
            assertTrue(lines.map(::fields).none { it["event"] == "skip" })
            assertTrue(diagnostics.summary().contains("lock_wait_ge1000_count=1 lock_wait_ge1000_max_ms=12000"))
            assertEquals(listOf("arm", "beat"), endpoint.commands)
        } finally {
            release.countDown(); runtimeScope.cancel(); executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    companion object {
        private fun fields(line: String) = line.split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
