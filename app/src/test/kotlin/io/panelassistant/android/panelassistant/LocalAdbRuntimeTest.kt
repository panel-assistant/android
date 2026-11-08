package io.panelassistant.android.panelassistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Delay
import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
class LocalAdbRuntimeTest {
    @After fun clearEffectiveOwnership() {
        io.panelassistant.android.control.ManagedNetworkAdb.managed = false
        io.panelassistant.android.control.ManagedNetworkAdb.recoveryHold = false
    }
    private open class Endpoint : LocalAdbEndpoint {
        var status: LocalAdbState? = LocalAdbState(false, false)
        val effects: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        override fun state() = status
        override fun arm(nonce: String): Boolean { effects += "arm"; status = LocalAdbState(true, false); return true }
        override fun beat(nonce: String): Boolean { effects += "beat"; return true }
        override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? {
            if (!submit { effects += "recover"; status = LocalAdbState(false, true) }) return null
            return true
        }
        override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? {
            if (!submit { effects += "shell ${String(command)} $timeoutMs" }) return null
            return JSONObject().put("stdout_b64", "b2s=").put("exit_code", 7).put("timed_out", false).put("truncated", false)
        }
        var commandReply: LocalAdbCommandReply = LocalAdbCommandReply.Fields(mapOf("model" to "NSPanel", "accessibility" to "1"))
        override fun command(verb: String, args: List<String>, submit: (() -> Unit) -> Boolean): LocalAdbCommandReply? {
            if (!submit { effects += (listOf("cmd", verb) + args).joinToString(" ") }) return null
            return commandReply
        }
    }

    @Test fun automaticMainOperationsAdmitOutsideTheTransitionAndRecheckAuthorityAtSubmission() = runTest {
        val wire = io.panelassistant.android.util.MainRecoveryWireFixture()
        var inTransition = false
        var armWrites = 0
        val runtime = LocalAdbRuntime(backgroundScope, RootLocalAdbEndpoint(wire.client), { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { action ->
            inTransition = true
            try { action() } finally { inTransition = false }
        })
        wire.phase = { assertFalse("identity admission must leave session transitions available", inTransition) }
        wire.beforeOperation = { assertFalse("transport preparation must leave session transitions available", inTransition) }
        wire.afterWrite = {
            if (String(wire.requests.last()).startsWith("ARM ")) { assertTrue(inTransition); armWrites++ }
        }
        runtime.session("first", true); runCurrent()
        assertEquals(1, armWrites)
        wire.beforeOperation = { header ->
            if (header.startsWith("RECOVER ")) runtime.session("replacement", true)
        }
        runtime.session("closed", false); runCurrent()
        assertTrue(wire.commands.none { it.second.startsWith("RECOVER ") })
    }

    @Test fun theMainTransportRechecksSessionAuthorityAfterItsIdentityAndBeforePayloadSubmission() = runTest {
        val wire = io.panelassistant.android.util.MainRecoveryWireFixture()
        val endpoint = RootLocalAdbEndpoint(wire.client)
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("initial", true); runCurrent()
        wire.requests.clear()
        wire.beforeOperation = { header -> if (header.startsWith("SHELL ")) runtime.session("replacement", false) }
        val result = runtime.request("adb.shell", JSONObject().put("command", "id").put("timeout_ms", 100), { true })
        assertEquals("refused", result.outcome)
        assertEquals("expired", result.code)
        assertTrue(wire.requests.none { String(it).startsWith("SHELL ") })
        assertTrue(wire.commands.none { it.second.startsWith("SHELL ") })
    }

    @Test fun aGrantedSessionReplacesAStaleBuildBeforeArmingItsSuccessor() = runTest {
        val endpoint = Endpoint().apply { status = LocalAdbState(true, false, buildId = "0".repeat(64)) }
        var bootstraps = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, {
            bootstraps++
            assertTrue("replacement must still observe the stale owner", endpoint.status!!.managed)
            endpoint.status = LocalAdbState(false, false)
            true
        }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("granted", true); runCurrent()
        assertEquals(1, bootstraps)
        assertEquals(listOf("arm"), endpoint.effects)
        val ready = runtime.request("adb.ready", JSONObject().put("include_build_id", true), { true }).result as JSONObject
        assertEquals(io.panelassistant.android.BuildConfig.HELPER_BUILD_ID, ready.getString("build_id"))
        assertTrue(ready.getBoolean("managed"))
    }

    @Test fun aMatchingBuildIsArmedWithoutReplacingItsDaemon() = runTest {
        val endpoint = Endpoint()
        var bootstraps = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, {
            bootstraps++; false
        }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("granted", true); runCurrent()
        assertEquals(0, bootstraps)
        assertEquals(listOf("arm"), endpoint.effects)
        val ready = runtime.request("adb.ready", JSONObject().put("include_build_id", true), { true }).result as JSONObject
        assertEquals(io.panelassistant.android.BuildConfig.HELPER_BUILD_ID, ready.getString("build_id"))
    }

    @Test fun aFailedReplacementNeverArmsOrRenewsTheStaleDaemonAndRetriesOnTheHeartbeat() = runTest {
        val endpoint = Endpoint().apply { status = LocalAdbState(true, false, buildId = "0".repeat(64)) }
        var bootstraps = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, {
            bootstraps++
            if (bootstraps > 2) endpoint.status = LocalAdbState(false, false)
            bootstraps > 2
        }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("granted", true); runCurrent()
        assertEquals(1, bootstraps)
        assertTrue(endpoint.effects.isEmpty())
        val ready = runtime.request("adb.ready", JSONObject().put("include_build_id", true), { true }).result as JSONObject
        assertEquals("0".repeat(64), ready.getString("build_id"))
        advanceTimeBy(30_001); runCurrent()
        assertEquals(2, bootstraps)
        assertTrue("a failed heartbeat replacement must not renew the old lease", endpoint.effects.isEmpty())
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, bootstraps)
        assertEquals(listOf("arm"), endpoint.effects)
    }

    @Test fun aLostEnsureReplyStillArmsOnlyTheFreshExpectedEndpoint() = runTest {
        val endpoint = Endpoint().apply { status = null }
        var bootstraps = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, {
            bootstraps++; endpoint.status = LocalAdbState(false, false); false
        }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("granted", true); runCurrent()
        assertEquals(1, bootstraps)
        assertEquals(listOf("arm"), endpoint.effects)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf("arm", "beat"), endpoint.effects)
    }

    @Test fun anEnsureReadyReplyNeverAuthorizesAMissingOrStaleEndpoint() = runTest {
        for (stale in listOf(null, LocalAdbState(true, false, buildId = "0".repeat(64)))) {
            val endpoint = Endpoint().apply { status = stale }
            var bootstraps = 0
            val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, {
                bootstraps++; true
            }, StandardTestDispatcher(testScheduler), { it() })
            runtime.session("granted", true); runCurrent()
            advanceTimeBy(30_001); runCurrent()
            assertEquals(2, bootstraps)
            assertTrue("READY cannot grant or renew another build", endpoint.effects.isEmpty())
        }
    }

    @Test fun preparingTheSeparateAssetApiNeverBootstrapsRecovery() = runTest {
        val endpoint = Endpoint()
        var preparations = 0
        var bootstraps = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, {
            preparations++; JSONObject().put("path", "prepared-asset")
        }, { bootstraps++; false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("granted", true); runCurrent()
        val answer = runtime.request("adb.prepare", JSONObject(), { true })
        assertEquals("applied", answer.outcome)
        assertEquals("prepared-asset", (answer.result as JSONObject).optString("path"))
        assertEquals(1, preparations)
        assertEquals(0, bootstraps)
    }

    @Test fun readyPreservesTheLegacyShapeUnlessBuildIdentityIsExplicitlyRequested() = runTest {
        val endpoint = Endpoint().apply { status = LocalAdbState(true, true, buildId = "0".repeat(64)) }
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("granted", true); runCurrent()
        val legacyKeys = setOf("ready", "managed", "recovery_hold", "security_unverified")
        for (args in listOf(JSONObject(), JSONObject().put("include_build_id", false))) {
            val reply = runtime.request("adb.ready", args, { true })
            assertEquals("applied", reply.outcome)
            val ready = reply.result as JSONObject
            assertEquals(legacyKeys, ready.keys().asSequence().toSet())
            assertTrue(ready.getBoolean("ready"))
            assertTrue(ready.getBoolean("managed"))
            assertTrue(ready.getBoolean("recovery_hold"))
        }
        for (args in listOf(JSONObject().put("include_build_id", "true"), JSONObject().put("include_build_id", 1), JSONObject().put("include_build_id", JSONObject.NULL), JSONObject().put("other", true), JSONObject().put("include_build_id", true).put("other", true))) {
            val reply = runtime.request("adb.ready", args, { true })
            assertEquals("refused", reply.outcome)
            assertEquals("invalid_value", reply.code)
            assertNull(reply.result)
        }
        val ready = runtime.request("adb.ready", JSONObject().put("include_build_id", true), { true }).result as JSONObject
        assertEquals(legacyKeys + "build_id", ready.keys().asSequence().toSet())
        assertEquals("0".repeat(64), ready.getString("build_id"))
        assertTrue(endpoint.effects.isEmpty())
    }

    @Test fun optedInReadyReportsNoBuildWhenNoDaemonIsAvailable() = runTest {
        val endpoint = Endpoint().apply { status = null }
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("granted", true); runCurrent()
        val legacy = runtime.request("adb.ready", JSONObject(), { true }).result as JSONObject
        assertFalse(legacy.has("build_id"))
        assertFalse(legacy.getBoolean("ready"))
        val ready = runtime.request("adb.ready", JSONObject().put("include_build_id", true), { true }).result as JSONObject
        assertFalse(ready.getBoolean("ready"))
        assertTrue(ready.has("build_id"))
        assertTrue(ready.isNull("build_id"))
        assertTrue(endpoint.effects.isEmpty())
    }

    @Test fun capabilityRollbackReopensInsteadOfRenewingManagedClosure() = runTest {
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("new-pa", true); runCurrent()
        assertEquals(listOf("arm"), endpoint.effects)
        runtime.session("old-pa", false); runCurrent()
        assertEquals(listOf("arm", "recover"), endpoint.effects)
        assertEquals(LocalAdbState(false, true), endpoint.status)
    }

    @Test fun aHardenedCompatibilityRollbackKeepsHealthMonitoringWithoutGrantingMaintenance() = runTest {
        val endpoint = Endpoint()
        var bootstrap = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { bootstrap++; false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("initial-old-pa", false); runCurrent()
        assertTrue(endpoint.effects.isEmpty())
        assertEquals(0, bootstrap)
        runtime.session("compatible", true); runCurrent()
        runtime.session("old-pa", false); runCurrent()
        assertEquals(listOf("arm"), endpoint.effects)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf("arm", "beat"), endpoint.effects)
        assertEquals(0, bootstrap)
        val reply = runtime.request("adb.shell", JSONObject().put("command", "id").put("timeout_ms", 1000), { true })
        assertEquals("expired", reply.code)
        assertTrue(endpoint.effects.none { it.startsWith("shell") || it == "recover" })
    }

    @Test fun anUnresponsiveUiStopsRenewalEvenWhileTheProcessAndSessionRemainAlive() = runTest {
        val endpoint = Endpoint()
        var uiHealthy = true
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() }, healthy = { uiHealthy })
        runtime.session("pa", true); runCurrent()
        assertEquals(listOf("arm"), endpoint.effects)
        uiHealthy = false
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf("arm"), endpoint.effects)
        assertNotNull(endpoint.state())
        uiHealthy = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("arm", "beat"), endpoint.effects)
    }

    @Test fun aLostDaemonIsBootstrappedAndRearmedWithoutLosingTheHeartbeatTaskOnATransientFailure() = runTest {
        val endpoint = Endpoint()
        var bootstraps = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, {
            bootstraps++
            if (bootstraps == 1) error("temporary bootstrap failure")
            endpoint.status = LocalAdbState(false, false)
            true
        }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("pa", true); runCurrent()
        endpoint.status = null
        advanceTimeBy(30_001); runCurrent()
        assertEquals(1, bootstraps)
        assertEquals(listOf("arm"), endpoint.effects)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, bootstraps)
        assertEquals(listOf("arm", "arm"), endpoint.effects)
    }

    @Test fun expiredFinalAdmissionNeverExecutesOrRetriesTheScript() = runTest {
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        var checks = 0
        val result = runtime.request("adb.shell", JSONObject().put("command", "printf hello").put("timeout_ms", 1000), admit = { ++checks == 1 })
        assertEquals("refused", result.outcome)
        assertEquals("expired", result.code)
        assertEquals(2, checks)
        assertTrue(endpoint.effects.isEmpty())
    }

    @Test fun shellPreservesNonzeroStatusAndExecutesExactlyOnce() = runTest {
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val result = runtime.request("adb.shell", JSONObject().put("command", "printf ok; exit 7").put("timeout_ms", 800), admit = { true })
        assertEquals("applied", result.outcome)
        assertEquals(7, (result.result as JSONObject).getInt("exit_code"))
        assertEquals(listOf("shell printf ok; exit 7 800"), endpoint.effects)
    }

    @Test fun aShellQueuedAtTheDispatcherNeedsApprovalIfHardenedBeforeSubmission() = dispatcherPolicyChange("adb.shell")
    @Test fun recoveryQueuedAtTheDispatcherNeedsApprovalIfHardenedBeforeSubmission() = dispatcherPolicyChange("adb.recover")

    private fun dispatcherPolicyChange(op: String) = runTest {
        val endpoint = Endpoint()
        var hardened = false
        var approvals = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { hardened }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), { it() }, authorize = { _, _, _ -> approvals++; io.panelassistant.android.http.DeferredSessionApproval("approval_denied") })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val answer = async(UnconfinedTestDispatcher(testScheduler)) { runtime.request(op, maintenanceArgs(op), { true }) }
        assertFalse(answer.isCompleted)
        hardened = true
        runCurrent()
        assertEquals("approval_denied", answer.await().code)
        assertEquals(1, approvals)
        assertTrue(endpoint.effects.isEmpty())
    }

    @Test fun aShellWaitingForTheShellQueueNeedsTheCurrentPhysicalApproval() = mutexPolicyChange("adb.shell")
    @Test fun recoveryWaitingForTheEffectsQueueNeedsTheCurrentPhysicalApproval() = mutexPolicyChange("adb.recover")

    private fun mutexPolicyChange(op: String) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = object : CoroutineDispatcher() {
            @Volatile var afterDispatch: (() -> Unit)? = null
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executor.execute { block.run(); afterDispatch?.invoke() }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var blockReady = false
        var hardened = false
        var approvals = 0
        val endpoint = object : Endpoint() {
            override fun state(): LocalAdbState? {
                if (blockReady) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return super.state()
            }
            override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? {
                val result = super.shell(command, timeoutMs, submit)
                if (result != null && String(command) == "first") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return result
            }
        }
        val runtime = LocalAdbRuntime(scope, endpoint, { hardened }, { JSONObject() }, { false }, dispatcher, { it() },
            authorize = { _, _, _ -> approvals++; io.panelassistant.android.http.DeferredSessionApproval("approval_denied") })
        try {
            runtime.session("pa", true)
            val initial = async { runtime.request("adb.ready", JSONObject(), { true }) }
            initial.await(); endpoint.effects.clear()
            blockReady = op == "adb.recover"
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.request(if (blockReady) "adb.ready" else "adb.shell",
                    if (blockReady) JSONObject() else maintenanceArgs("adb.shell", "first"), { true })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            dispatcher.afterDispatch = { queued.countDown() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { runtime.request(op, maintenanceArgs(op), { true }) }
            assertTrue(queued.await(5, TimeUnit.SECONDS))
            io.panelassistant.android.control.RemoteDebugSecurityTransitionGate.withLock { hardened = true }
            release.countDown(); first.await()
            assertEquals("approval_denied", second.await().code)
            assertEquals(1, approvals)
            assertTrue(endpoint.effects.none { it == "recover" || it.startsWith("shell id ") })
        } finally {
            release.countDown(); scope.cancel(); executor.shutdownNow()
        }
    }

    private fun maintenanceArgs(op: String, command: String = "id") =
        if (op == "adb.shell") JSONObject().put("command", command).put("timeout_ms", 1000) else JSONObject()

    @Test fun aShellApprovalMustStillBeLiveAtSubmission() = approvalAtSubmission("adb.shell", "expire")
    @Test fun recoveryApprovalMustStillBeLiveAtSubmission() = approvalAtSubmission("adb.recover", "expire")
    @Test fun aReplacementSessionCannotUseAnApprovedShell() = approvalAtSubmission("adb.shell", "replace")
    @Test fun aReplacementSessionCannotUseApprovedRecovery() = approvalAtSubmission("adb.recover", "replace")
    @Test fun aModeChangeCannotReuseAnEarlierHardenedApproval() = approvalAtSubmission("adb.shell", "mode")

    private fun approvalAtSubmission(op: String, change: String) = runTest {
        var now = 0L
        var hardened = true
        var id: String? = null
        var submissions = 0
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { now }, ttlMs = 1000)
        broker.onRequested = { id = it.id }
        lateinit var runtime: LocalAdbRuntime
        fun beforeSubmission() {
            if (++submissions != 2) return
            when (change) {
                "expire" -> now = 1000
                "replace" -> runtime.session("replacement", true)
                "mode" -> {
                    io.panelassistant.android.control.RemoteDebugSecurityTransitionGate.mutate { hardened = false }
                    io.panelassistant.android.control.RemoteDebugSecurityTransitionGate.mutate { hardened = true }
                }
            }
        }
        val endpoint = object : Endpoint() {
            override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? {
                beforeSubmission(); return super.shell(command, timeoutMs, submit)
            }
            override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? {
                beforeSubmission(); return super.recover(nonce, submit)
            }
        }
        runtime = LocalAdbRuntime(backgroundScope, endpoint, { hardened }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, pending, admitted ->
                io.panelassistant.android.http.awaitSessionApproval(
                    io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, "Panel maintenance", broker, pending, pollMs = 1, admitted = admitted)
            })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val answer = async { runtime.request(op, maintenanceArgs(op), { true }) }
        runCurrent()
        now = 999
        assertTrue(broker.approve(requireNotNull(id)))
        advanceTimeBy(1); runCurrent()
        assertEquals(if (change == "replace") "expired" else "approval_timeout", answer.await().code)
        assertTrue(endpoint.effects.none { it == "recover" || it.startsWith("shell") })
        assertEquals(io.panelassistant.android.security.ApprovalBroker.State.ABSENT, broker.state(requireNotNull(id)))
        assertTrue(broker.pending().isEmpty())
    }

    @Test fun sessionReplacementWhileApprovalIsPendingWithdrawsTheExactRecord() = runTest {
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        var id: String? = null
        broker.onRequested = { id = it.id }
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, pending, admitted ->
                io.panelassistant.android.http.awaitSessionApproval(
                    io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, "Panel maintenance", broker, pending, pollMs = 1, admitted = admitted)
            })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val answer = async { runtime.request("adb.shell", maintenanceArgs("adb.shell"), { true }) }
        runCurrent(); assertEquals(1, broker.pending().size)
        runtime.session("replacement", true)
        advanceTimeBy(1); runCurrent()
        assertEquals("expired", answer.await().code)
        assertEquals(io.panelassistant.android.security.ApprovalBroker.State.ABSENT, broker.state(requireNotNull(id)))
        assertTrue(endpoint.effects.none { it == "recover" || it.startsWith("shell") })
    }

    @Test fun aClosedManagementCarrierCannotSubmitItsPreparedShell() = closedCarrier(false)
    @Test fun aClosedManagementCarrierWithdrawsItsPendingApproval() = closedCarrier(true)

    private fun closedCarrier(pendingApproval: Boolean) = runTest {
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        var approvalId: String? = null
        broker.onRequested = { approvalId = it.id }
        lateinit var requests: PanelAssistantManagementRequests
        val endpoint = object : Endpoint() {
            override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? {
                if (!pendingApproval) requests.close()
                return super.shell(command, timeoutMs, submit)
            }
        }
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { pendingApproval }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, pending, admitted ->
                io.panelassistant.android.http.awaitSessionApproval(
                    io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, "Panel maintenance", broker, pending, pollMs = 1, admitted = admitted)
            })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        requests = PanelAssistantManagementRequests(backgroundScope,
            PanelAssistantManagement.of({ error("snapshot") }, { _, _ -> null }, localAdb = runtime),
            PanelAssistantSession(5, "pa", listOf("management", "local_adb"), "1"), { testScheduler.currentTime })
        requests.onRequest(PanelAssistantSessionEvent.Manage("id", "pa", "adb.shell", null, 1000, maintenanceArgs("adb.shell")))
        runCurrent()
        if (pendingApproval) {
            assertEquals(1, broker.pending().size)
            requests.close(); runCurrent()
            assertEquals(io.panelassistant.android.security.ApprovalBroker.State.ABSENT, broker.state(requireNotNull(approvalId)))
        }
        assertTrue(endpoint.effects.isEmpty())
    }

    @Test fun submissionSerializesModeSessionAndCloseButItsLongReplyLeavesRecoveryAndHeartbeatFree() = runTest {
        val writing = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val allowReply = CountDownLatch(1)
        val initialReady = CountDownLatch(1)
        val replacementReady = CountDownLatch(1)
        val beat = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val armed = java.util.concurrent.atomic.AtomicInteger()
        val timer = StandardTestDispatcher(testScheduler)
        val dispatcher = object : CoroutineDispatcher(), Delay {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executor.execute {
                    block.run()
                    if (armed.get() >= 1) initialReady.countDown()
                    if (armed.get() >= 2) replacementReady.countDown()
                }
            }
            override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
                (timer as Delay).scheduleResumeAfterDelay(timeMillis, continuation)
        }
        val hardened = java.util.concurrent.atomic.AtomicBoolean()
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        val approvalRequested = CountDownLatch(1)
        var approvalId: String? = null
        broker.onRequested = { approvalId = it.id; approvalRequested.countDown() }
        val endpoint = object : Endpoint() {
            override fun arm(nonce: String): Boolean = super.arm(nonce).also { armed.incrementAndGet() }
            override fun beat(nonce: String): Boolean = super.beat(nonce).also { beat.countDown() }
            override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? {
                val result = super.shell(command, timeoutMs) { write ->
                    submit { writing.countDown(); check(allowWrite.await(10, TimeUnit.SECONDS)); write() }
                }
                if (result != null) { reading.countDown(); check(allowReply.await(10, TimeUnit.SECONDS)) }
                return result
            }
        }
        val runtimeScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]) + dispatcher)
        val runtime = LocalAdbRuntime(runtimeScope, endpoint, hardened::get, { JSONObject() }, { false }, dispatcher, { it() },
            authorize = { payload, pending, admitted ->
                io.panelassistant.android.http.awaitSessionApproval(
                    io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, "Panel maintenance", broker, pending, pollMs = 1, admitted = admitted)
            })
        val requests = PanelAssistantManagementRequests(backgroundScope,
            PanelAssistantManagement.of({ error("snapshot") }, { _, _ -> null }, localAdb = runtime),
            PanelAssistantSession(5, "pa", listOf("management", "local_adb"), "1"), { testScheduler.currentTime })
        val attempts = CountDownLatch(3)
        val published = CountDownLatch(3)
        val threads = mutableListOf<Thread>()
        try {
            runtime.session("pa", true); assertTrue(initialReady.await(5, TimeUnit.SECONDS))
            requests.onRequest(PanelAssistantSessionEvent.Manage("long", "pa", "adb.shell", null, 1000, maintenanceArgs("adb.shell")))
            runCurrent(); assertTrue(writing.await(5, TimeUnit.SECONDS))
            for (action in listOf<() -> Unit>(
                { io.panelassistant.android.control.RemoteDebugSecurityTransitionGate.mutate { hardened.set(true) } },
                { runtime.session("replacement", true) },
                requests::close,
            )) threads += Thread { attempts.countDown(); action(); published.countDown() }.also { it.start() }
            assertTrue(attempts.await(5, TimeUnit.SECONDS))
            assertFalse(published.await(100, TimeUnit.MILLISECONDS))
            assertEquals("no authority change can cross an admitted write", 3L, published.count)
            allowWrite.countDown(); assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertTrue("all authority changes finish while the reply is blocked", published.await(5, TimeUnit.SECONDS))
            assertTrue(replacementReady.await(5, TimeUnit.SECONDS))
            advanceTimeBy(30_001); runCurrent()
            assertTrue("healthy replacement session renews during the long reply", beat.await(5, TimeUnit.SECONDS))
            val recovery = async(UnconfinedTestDispatcher(testScheduler)) { runtime.request("adb.recover", JSONObject(), { true }) }
            assertTrue(approvalRequested.await(5, TimeUnit.SECONDS))
            assertTrue(broker.approve(requireNotNull(approvalId)))
            advanceTimeBy(1); runCurrent()
            assertEquals("applied", recovery.await().outcome)
            assertTrue(endpoint.effects.contains("recover"))
            assertEquals(1, endpoint.effects.count { it.startsWith("shell") })
        } finally {
            allowWrite.countDown(); allowReply.countDown(); requests.close()
            threads.forEach { it.join(1000) }
            runtimeScope.cancel()
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    @Test fun hardenedMaintenanceNeedsItsExactPhysicalApprovalAndFreshAdmission() = runTest {
        val endpoint = Endpoint()
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        var approval: String? = null
        broker.onRequested = { approval = it.id }
        var pending = 0
        var admitted = true
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, onPending, admitted ->
            io.panelassistant.android.http.awaitSessionApproval(
                io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                payload, "Panel maintenance", broker, onPending, pollMs = 1, admitted = admitted)
        })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val answer = async {
            runtime.request("adb.shell", JSONObject().put("command", "id").put("timeout_ms", 1000), { admitted }, { pending++ })
        }
        runCurrent()
        assertEquals(1, pending)
        assertTrue(endpoint.effects.isEmpty())
        assertTrue(broker.approve(requireNotNull(approval)))
        advanceTimeBy(1); runCurrent()
        assertEquals("applied", answer.await().outcome)
        assertEquals(listOf("shell id 1000"), endpoint.effects)
        // A new program cannot reuse the consumed approval, and expiry while waiting cannot run it.
        endpoint.effects.clear()
        val expired = async {
            runtime.request("adb.shell", JSONObject().put("command", "reboot").put("timeout_ms", 1000), { admitted }, { pending++ })
        }
        runCurrent(); assertEquals(2, pending)
        admitted = false
        assertTrue(broker.approve(requireNotNull(approval)))
        advanceTimeBy(1); runCurrent()
        assertEquals("expired", expired.await().code)
        assertTrue(endpoint.effects.isEmpty())
    }

    @Test fun physicalApprovalAfterTheStartDeadlineRetainsSessionAdmissionThroughTheRealCarrier() = runTest {
        val endpoint = Endpoint()
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        var approval: String? = null
        broker.onRequested = { approval = it.id }
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, pending, admitted ->
            io.panelassistant.android.http.awaitSessionApproval(
                io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                payload, "Panel maintenance", broker, pending, pollMs = 1, admitted = admitted)
        })
        runtime.session("current", true); runCurrent(); endpoint.effects.clear()
        val requests = PanelAssistantManagementRequests(backgroundScope,
            PanelAssistantManagement.of({ error("snapshot") }, { _, _ -> null }, localAdb = runtime),
            PanelAssistantSession(5, "current", listOf("management", "local_adb"), "1"), { testScheduler.currentTime })
        requests.onRequest(PanelAssistantSessionEvent.Manage("maintenance", "current", "adb.shell", null, 60_000,
            JSONObject().put("command", "id").put("timeout_ms", 1000)))
        runCurrent()
        assertEquals("pending_approval", JSONObject(requireNotNull(requests.next(1))).getString("outcome"))
        advanceTimeBy(61_000); runCurrent()
        assertTrue(endpoint.effects.none { it.startsWith("shell") })
        assertTrue(broker.approve(requireNotNull(approval)))
        advanceTimeBy(1); runCurrent()
        assertEquals("applied", JSONObject(requireNotNull(requests.next(2))).getString("outcome"))
        assertEquals(listOf("shell id 1000"), endpoint.effects.filter { it.startsWith("shell") })
        requests.close()
    }

    @Test fun nativeManagementRejectsReplaysAndBrowserOriginsBeforeThePrivilegedDoor() = runTest {
        val calls = mutableListOf<String>()
        val local = object : PanelAssistantLocalAdb {
            override fun session(token: String, granted: Boolean) = Unit
            override suspend fun request(op: String, args: JSONObject, admit: () -> Boolean, onPending: () -> Unit): PanelAssistantManageReply {
                check(admit()); calls += op
                return PanelAssistantManageReply("applied", null, JSONObject().put("exit_code", 0))
            }
        }
        val management = PanelAssistantManagement.of({ error("snapshot") }, { _, _ -> null }, localAdb = local)
        val requests = PanelAssistantManagementRequests(backgroundScope, management, PanelAssistantSession(5, "current", listOf("management", "local_adb"), "1"), { testScheduler.currentTime })
        fun event(id: String, token: String = "current", origin: PanelAssistantRelayOrigin? = null) = PanelAssistantSessionEvent.Manage(id, token, "adb.shell", null, 1000, JSONObject().put("command", "id").put("timeout_ms", 100), origin)
        requests.onRequest(event("stale", "previous"))
        requests.onRequest(event("browser", origin = PanelAssistantRelayOrigin("admin", "browser")))
        requests.onRequest(event("one")); runCurrent()
        requests.onRequest(event("one")); runCurrent()
        assertEquals(listOf("adb.shell"), calls)
        val refused = JSONObject(requireNotNull(requests.next(1)))
        assertEquals("browser", refused.getString("command_id"))
        assertEquals("refused", refused.getString("outcome"))
        assertEquals("applied", JSONObject(requireNotNull(requests.next(2))).getString("outcome"))
        assertEquals("applied", JSONObject(requireNotNull(requests.next(3))).getString("outcome"))
        requests.close()
        val ungranted = PanelAssistantManagementRequests(backgroundScope, management,
            PanelAssistantSession(5, "current", listOf("management"), "1"), { testScheduler.currentTime })
        ungranted.onRequest(event("ungranted")); runCurrent()
        assertEquals("refused", JSONObject(requireNotNull(ungranted.next(4))).getString("outcome"))
        assertEquals(listOf("adb.shell"), calls)
        ungranted.close()
    }

    private fun commandArgs(verb: String, vararg args: String) =
        JSONObject().put("verb", verb).put("args", org.json.JSONArray(args.toList()))

    private fun sha256(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun aTypedCommandRefusesAnythingOutsideItsGrammarWithoutReachingTheHelper() = runTest {
        val endpoint = Endpoint()
        var approvals = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() },
            authorize = { _, _, _ -> approvals++; io.panelassistant.android.http.DeferredSessionApproval(consume = { true }) })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val invalid = listOf(
            JSONObject(),
            JSONObject().put("verb", "PA_REPAIR"),
            JSONObject().put("args", org.json.JSONArray()),
            commandArgs("SHELL"),
            commandArgs("PA_REMOVE"),
            commandArgs("pa_identity"),
            JSONObject().put("verb", 7).put("args", org.json.JSONArray()),
            commandArgs("PA_REPAIR", "1", "0").put("timeout_ms", 1000),
            JSONObject().put("verb", "PA_REPAIR").put("args", "1 0"),
            JSONObject().put("verb", "PA_REPAIR").put("args", org.json.JSONArray().put(1).put("0")),
            commandArgs("PA_REPAIR", "1", "0", "x", "y"),
            commandArgs("PA_HANDBACK", "component", "a b/c"),
            commandArgs("PA_HANDBACK", "component", ""),
            commandArgs("PA_HANDBACK", "component", "a\nb"),
            commandArgs("PA_HANDBACK", "component", "café/x"),
            commandArgs("PA_HANDBACK", "component", "a".repeat(160)),
            commandArgs("PA_HANDBACK", "a".repeat(80), "b".repeat(80)),
        )
        invalid.forEach { args ->
            val reply = runtime.request("adb.cmd", args, { true })
            assertEquals(args.toString(), "refused", reply.outcome)
            assertEquals(args.toString(), "invalid_value", reply.code)
        }
        assertEquals(0, approvals)
        assertTrue(endpoint.effects.isEmpty())
        val longest = runtime.request("adb.cmd", commandArgs("PA_HANDBACK", "component", "a".repeat(159 - "PA_HANDBACK component ".length)), { true })
        assertEquals("applied", longest.outcome)
    }

    @Test fun anUnhardenedPanelRunsATypedCommandWithoutApprovalAndReturnsItsFields() = runTest {
        val endpoint = Endpoint()
        var approvals = 0
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() },
            authorize = { _, _, _ -> approvals++; io.panelassistant.android.http.DeferredSessionApproval("approval_denied") })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val reply = runtime.request("adb.cmd", commandArgs("PA_REPAIR", "1", "0"), { true })
        assertEquals("applied", reply.outcome)
        val result = reply.result as JSONObject
        assertTrue(result.getBoolean("ok"))
        assertEquals("NSPanel", result.getJSONObject("fields").getString("model"))
        assertEquals("1", result.getJSONObject("fields").getString("accessibility"))
        assertEquals(0, approvals)
        assertEquals(listOf("cmd PA_REPAIR 1 0"), endpoint.effects)
    }

    @Test fun aHelperRefusalCarriesItsCodeAndBusyIsARetryableFailure() = runTest {
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("pa", true); runCurrent()
        endpoint.commandReply = LocalAdbCommandReply.Refused("home-unsafe")
        val refused = runtime.request("adb.cmd", commandArgs("PA_HANDBACK", "vendor"), { true })
        assertEquals("refused", refused.outcome)
        assertEquals("failed", refused.code)
        assertEquals("home-unsafe", (refused.result as JSONObject).getString("code"))
        listOf(LocalAdbCommandReply.Busy, LocalAdbCommandReply.Refused("busy")).forEach {
            endpoint.commandReply = it
            val busy = runtime.request("adb.cmd", commandArgs("PA_HOME", "1", "repair"), { true })
            assertEquals("failed", busy.outcome)
            assertEquals("failed", busy.code)
            assertEquals("busy", (busy.result as JSONObject).getString("code"))
        }
    }

    @Test fun everyTypedVerbOnAHardenedPanelNeedsAOneUseApprovalBoundToItsVerbAndArguments() = runTest {
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        val ids = mutableListOf<String>()
        broker.onRequested = { ids += it.id }
        val payloads = mutableListOf<String>()
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, pending, admitted ->
                payloads += payload
                io.panelassistant.android.http.awaitSessionApproval(
                    io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                    payload, "Panel maintenance", broker, pending, pollMs = 1, admitted = admitted)
            })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        val first = async { runtime.request("adb.cmd", commandArgs("PA_REPAIR", "1", "0"), { true }) }
        runCurrent()
        assertTrue("nothing runs before approval", endpoint.effects.isEmpty())
        val nonce = payloads.single().split(':')[1]
        assertEquals("adb.cmd:$nonce:PA_REPAIR:${sha256("1\n0")}", payloads.single())
        assertTrue(broker.approve(ids.single()))
        advanceTimeBy(2); runCurrent()
        assertEquals("applied", first.await().outcome)
        assertEquals(listOf("cmd PA_REPAIR 1 0"), endpoint.effects)

        // The identical request again: the spent approval is gone, so it waits for a fresh one.
        val same = async { runtime.request("adb.cmd", commandArgs("PA_REPAIR", "1", "0"), { true }) }
        runCurrent(); advanceTimeBy(5); runCurrent()
        assertEquals(2, payloads.size)
        assertEquals(payloads[0], payloads[1])
        assertEquals(2, ids.size)
        assertEquals(listOf("cmd PA_REPAIR 1 0"), endpoint.effects)
        assertTrue(broker.approve(ids[1]))
        advanceTimeBy(2); runCurrent()
        assertEquals("applied", same.await().outcome)
        assertEquals(listOf("cmd PA_REPAIR 1 0", "cmd PA_REPAIR 1 0"), endpoint.effects)
        endpoint.effects.clear(); endpoint.effects += "cmd PA_REPAIR 1 0"

        // An approval nobody answers expires; the command never runs.
        val unanswered = async { runtime.request("adb.cmd", commandArgs("PA_REPAIR", "1", "0"), { true }) }
        runCurrent()
        assertEquals(3, payloads.size)
        advanceTimeBy(io.panelassistant.android.security.ApprovalBroker.DEFAULT_TTL_MS + 5); runCurrent()
        assertEquals("approval_denied", unanswered.await().code)
        // Session heartbeats run during the long wait; only commands matter here.
        assertEquals(listOf("cmd PA_REPAIR 1 0"), endpoint.effects.filter { it.startsWith("cmd ") })
        endpoint.effects.clear(); endpoint.effects += "cmd PA_REPAIR 1 0"

        // A replay with other arguments needs its own approval; the spent one cannot carry it.
        val replay = async { runtime.request("adb.cmd", commandArgs("PA_REPAIR", "0", "1"), { true }) }
        runCurrent(); advanceTimeBy(5); runCurrent()
        assertEquals(4, payloads.size)
        assertEquals("adb.cmd:$nonce:PA_REPAIR:${sha256("0\n1")}", payloads[3])
        assertEquals(listOf("cmd PA_REPAIR 1 0"), endpoint.effects)
        assertTrue(broker.deny(ids[3]))
        advanceTimeBy(2); runCurrent()
        assertEquals("approval_denied", replay.await().code)
        assertEquals(listOf("cmd PA_REPAIR 1 0"), endpoint.effects)

        // Read-only verbs are approved exactly like the rest.
        val identity = async { runtime.request("adb.cmd", commandArgs("PA_IDENTITY"), { true }) }
        runCurrent()
        assertEquals("adb.cmd:$nonce:PA_IDENTITY:${sha256("")}", payloads[4])
        assertEquals(listOf("cmd PA_REPAIR 1 0"), endpoint.effects)
        assertTrue(broker.approve(ids[4]))
        advanceTimeBy(2); runCurrent()
        assertEquals("applied", identity.await().outcome)
        assertEquals(listOf("cmd PA_REPAIR 1 0", "cmd PA_IDENTITY"), endpoint.effects)
    }

    @Test fun shellValidationIsUnchangedByTypedCommands() = runTest {
        val endpoint = Endpoint()
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { false }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() })
        runtime.session("pa", true); runCurrent(); endpoint.effects.clear()
        listOf(
            JSONObject().put("timeout_ms", 100),
            JSONObject().put("command", "").put("timeout_ms", 100),
            JSONObject().put("command", "id"),
            JSONObject().put("command", "id").put("timeout_ms", 0),
            JSONObject().put("command", "id").put("timeout_ms", 180_001),
            JSONObject().put("command", "a".repeat(65_537)).put("timeout_ms", 100),
            JSONObject().put("command", "a\u0000b").put("timeout_ms", 100),
            commandArgs("PA_IDENTITY"),
        ).forEach { assertEquals(it.toString().take(80), "invalid_value", runtime.request("adb.shell", it, { true }).code) }
        assertTrue(endpoint.effects.isEmpty())
        assertEquals("applied", runtime.request("adb.shell", JSONObject().put("command", "id").put("timeout_ms", 180_000).put("verb", "PA_IDENTITY"), { true }).outcome)
        assertEquals(listOf("shell id 180000"), endpoint.effects)
    }

    @Test fun theRootEndpointSendsTheTypedHeaderWithNoPayloadAndParsesTheBody() {
        val sent = mutableListOf<Triple<String, Int, Int>>()
        var reply = "OK 19\nmodel=X\nsu=present\n".toByteArray()
        val daemon = object : io.panelassistant.android.platform.Daemon {
            override fun available() = true
            override fun send(cmd: String): String? = null
            override fun sendLong(cmd: String, timeoutMs: Long) = io.panelassistant.android.platform.DaemonLongResult.NotSubmitted
            override fun sendBytes(cmd: String): ByteArray? = null
            override fun adbRecoveryExchange(header: String, payload: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): ByteArray? {
                if (!submit { sent += Triple(header, payload.size, timeoutMs) }) return null
                return reply
            }
        }
        val endpoint = RootLocalAdbEndpoint(daemon)
        assertEquals(LocalAdbCommandReply.Fields(mapOf("model" to "X", "su" to "present")), endpoint.command("PA_HOME", listOf("1", "repair")) { it(); true })
        assertEquals("PA_HOME 1 repair", sent.single().first)
        assertEquals(0, sent.single().second)
        assertEquals(65_000, sent.single().third)
        reply = "ERR home-unsafe\n".toByteArray()
        assertEquals(LocalAdbCommandReply.Refused("home-unsafe"), endpoint.command("PA_IDENTITY", emptyList()) { it(); true })
        assertEquals("PA_IDENTITY", sent.last().first)
        reply = "BUSY\n".toByteArray()
        assertEquals(LocalAdbCommandReply.Busy, endpoint.command("PA_ADMIN", emptyList()) { it(); true })
        assertNull(endpoint.command("PA_ADMIN", emptyList()) { false })
        listOf("OK 6\nmodel\n", "OK 16\nmodel=X\nmodel=Y\n", "OK 3\na=b", "OK 6\na=b=c\n", "OK 5\n=b\nc\n", "RESULT 0 0 0 0\n", "ERR Bad\n").forEach { bad ->
            reply = bad.toByteArray()
            assertThrows(bad, IllegalStateException::class.java) { endpoint.command("PA_ADMIN", emptyList()) { it(); true } }
        }
    }
}
