package io.panelassistant.android.panelassistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

@OptIn(ExperimentalCoroutinesApi::class)
class LocalAdbRuntimeTest {
    @After fun clearEffectiveOwnership() {
        io.panelassistant.android.control.ManagedNetworkAdb.managed = false
        io.panelassistant.android.control.ManagedNetworkAdb.recoveryHold = false
    }
    private class Endpoint : LocalAdbEndpoint {
        var status: LocalAdbState? = LocalAdbState(false, false)
        val effects = mutableListOf<String>()
        override fun state() = status
        override fun arm(nonce: String): Boolean { effects += "arm"; status = LocalAdbState(true, false); return true }
        override fun beat(nonce: String): Boolean { effects += "beat"; return true }
        override fun recover(nonce: String): Boolean { effects += "recover"; status = LocalAdbState(false, true); return true }
        override fun shell(command: ByteArray, timeoutMs: Int): JSONObject {
            effects += "shell ${String(command)} $timeoutMs"
            return JSONObject().put("stdout_b64", "b2s=").put("exit_code", 7).put("timed_out", false).put("truncated", false)
        }
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

    @Test fun hardenedMaintenanceNeedsItsExactPhysicalApprovalAndFreshAdmission() = runTest {
        val endpoint = Endpoint()
        val broker = io.panelassistant.android.security.ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        var approval: String? = null
        broker.onRequested = { approval = it.id }
        var pending = 0
        var admitted = true
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, onPending ->
            io.panelassistant.android.http.authorizeSessionRequest(true,
                io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                payload, "Panel maintenance", broker, onPending, pollMs = 1)
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
        val runtime = LocalAdbRuntime(backgroundScope, endpoint, { true }, { JSONObject() }, { false }, StandardTestDispatcher(testScheduler), { it() }, authorize = { payload, pending ->
            io.panelassistant.android.http.authorizeSessionRequest(true,
                io.panelassistant.android.security.SensitiveOperation.LOCAL_ADB_MAINTENANCE,
                payload, "Panel maintenance", broker, pending, pollMs = 1)
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
}
