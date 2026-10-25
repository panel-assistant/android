package io.panelassistant.android.http

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.panelassistant.android.*
import io.panelassistant.android.panelassistant.*
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.sensors.HaApiSessionProvider
import io.panelassistant.android.util.AppInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The registered URL source and Cancel, carried by the production sidebar session relay. */
class RegisteredApkFetchCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancelRetiresTheWaitingFetchAndItsOldApproval() = probe(cancel = true)
    @Test fun uncancelledFetchKeepsItsRequestAndOriginGuardsAndApprovesOnce() = probe(cancel = false)
    @Test fun cancelRetiresAnApprovalReplayEvenWhenHardenedModeIsTurnedOff() = probe(cancel = true, replay = true)
    @Test fun cancellingOneConnectionKeepsTheOtherConnectionsSharedApprovalUsable() = probe(cancel = true, shared = true)
    @Test fun registeredCancelStillStopsAnAdmittedTransfer() = probe(cancel = true, transfer = true)

    private fun probe(cancel: Boolean, replay: Boolean = false, shared: Boolean = false, transfer: Boolean = false) = testApplication {
        val table = ManageTable()
        val broker = ApprovalBroker(monotonicMs = { System.nanoTime() / 1_000_000 })
        val pending = PendingUploadStore().apply { open() }
        val fetches = AtomicInteger()
        val inspections = AtomicInteger()
        val authorizations = AtomicInteger()
        val hardened = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val routes = ControlPlaneRouteDependencies(
            playAudio = { error("unused") }, installComponent = { _, _, _ -> error("never install") },
            installedComponentVersion = { null }, buildBackup = { _, _ -> error("unused") }, backupFileStem = { "unused" },
            apkUpload = ApkUploadRouteDependencies(
                enabled = { true }, rootAvailable = { true }, pending = pending,
                createStagingFile = { temporary.newFile("fetch-${System.nanoTime()}.apk") },
                inspect = { inspections.incrementAndGet(); UploadedApkIdentity("example.app", "1", null) },
                startInstall = { _, _ -> error("fetch must never install") },
                fetch = { _, file, _, _ ->
                    fetches.incrementAndGet()
                    if (transfer) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    // Even a transfer that completes while Cancel arrives cannot publish a token.
                    file.writeText("test APK")
                    AppInstaller.DownloadResult.Succeeded
                },
            ),
            authorize = { call, operation, payload, summary ->
                if (authorizations.incrementAndGet() == 2 && replay) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                authorizeSensitiveRequest(call, hardened.get(), operation, payload, summary, broker)
            },
        )
        application { routing { serveOperations(table); controlPlaneRoutes(routes) } }
        startApplication()
        assertNotNull(table["install.apk.from_url"])
        assertNotNull(table["install.apk.fetch.cancel"])
        val operations = ManageOperations(table, { broker }, approvalPollMs = 5)
        val door = object : ManageDoor {
            override fun manage(commandId: String, op: String, args: JSONObject, origin: CommandOrigin, blobs: ManageBlobs?, onPending: () -> Unit, done: (ManageReply) -> Unit) =
                operations.manage(commandId, op, args, origin, blobs, onPending, done)
            override fun submit(command: PanelAssistantCommand, origin: CommandOrigin, done: (PanelAssistantCommandResult) -> Unit) = error("not a channel command")
            override fun subscribe(family: String, origin: CommandOrigin, args: JSONObject, sink: ManageSink) = operations.subscribe(family, origin, args, sink)
            override fun closeOrigin(origin: CommandOrigin) = operations.closeOrigin(origin)
            override fun onStopped(action: () -> Unit) = operations.onStopped(action)
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val requests = PanelAssistantManagementRequests(
            scope,
            PanelAssistantManagement.of({ error("unused") }, { _, _ -> error("unused") }, { door },
                PanelAssistantBlobs(HaApiSessionProvider { HaApiSession("http://127.0.0.1", "test-token") })),
            PanelAssistantSession(5, "test-session", listOf("management", "ui_subscribe", "ui_bundle"), "1.0.0"),
            { System.nanoTime() / 1_000_000 },
        )
        fun send(id: String, op: String, args: JSONObject, user: String = "administrator", connection: String = "sidebar") {
            val request = JSONObject().put("session", "test-session").put("kind", "manage").put("command_id", id)
                .put("op", op).put("args", args).put("origin", JSONObject().put("user", user).put("connection", connection))
            val frame = JSONObject().put("id", 1).put("type", "event").put("event", request)
            requests.onRequest(PanelAssistantTransportProtocol.sessionEvent(frame, 1L) as PanelAssistantSessionEvent.Manage)
        }
        val received = mutableListOf<JSONObject>()
        fun answer(id: String): JSONObject {
            val until = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < until) {
                val found = received.indexOfFirst { it.optString("command_id") == id }
                if (found >= 0) return received.removeAt(found)
                requests.next(2L)?.let { received += JSONObject(it) }
                Thread.sleep(2)
            }
            error("no reply for $id; received $received")
        }
        fun cancelRequest(id: String, request: String, user: String = "administrator", connection: String = "sidebar"): JSONObject {
            send(id, "install.apk.fetch.cancel", JSONObject().put("request", request), user, connection)
            return answer(id).getJSONObject("result")
        }
        fun fetch(id: String, request: String) = send(id, "install.apk.from_url",
            JSONObject().put("url", "https://example.test/app.apk").put("request", request))
        try {
            fetch("fetch", "owned-fetch")
            assertEquals("pending_approval", answer("fetch").optString("outcome"))
            val card = broker.pending().single()
            assertEquals("user:administrator", card.peer)
            assertEquals(0, fetches.get())
            assertFalse(cancelRequest("wrong-request", "other-fetch").getBoolean("cancelled"))
            assertFalse(cancelRequest("wrong-user", "owned-fetch", user = "other").getBoolean("cancelled"))
            assertFalse(cancelRequest("wrong-connection", "owned-fetch", connection = "other-tab").getBoolean("cancelled"))
            assertEquals(ApprovalBroker.State.PENDING, broker.state(card.id))
            if (shared) {
                // Exact managed arguments share a broker card across this principal's connections.
                send("shared", "install.apk.from_url", JSONObject().put("url", "https://example.test/app.apk")
                    .put("request", "owned-fetch"), connection = "other-tab")
                assertEquals("pending_approval", answer("shared").optString("outcome"))
                assertEquals(1, broker.pending().size)
            }
            if (replay || transfer) {
                assertTrue(broker.approve(card.id))
                assertTrue("replay/transfer reached", entered.await(10, TimeUnit.SECONDS))
                if (replay) hardened.set(false)
            }
            if (cancel) {
                assertTrue("Cancel must accept the exact waiting or transferring request",
                    cancelRequest("cancel", "owned-fetch").getBoolean("cancelled"))
                if (!shared) assertFalse("old card must be unusable", broker.approve(card.id))
                release.countDown()
                val final = answer("fetch")
                assertEquals(final.toString(), "refused", final.optString("outcome"))
                val result = final.getJSONObject("result")
                assertEquals("cancelled", result.getString("error"))
                assertEquals("owned-fetch", result.getString("request"))
                assertFalse(result.has("token"))
                assertEquals(if (transfer) 1 else 0, fetches.get())
                assertNull(pending.pendingSummary())
                if (shared) {
                    assertTrue("the other request still owns the shared card", broker.approve(card.id))
                    assertEquals("applied", answer("shared").optString("outcome"))
                    assertEquals(1, fetches.get())
                    assertNotNull(pending.pendingSummary())
                } else if (!transfer) assertEquals(0, inspections.get())
            } else {
                assertTrue(broker.approve(card.id))
                val final = answer("fetch")
                assertEquals(final.toString(), "applied", final.optString("outcome"))
                assertNotNull(pending.peek(final.getJSONObject("result").getString("token")))
                // The real relay replays the original command's answer without fetching twice.
                fetch("fetch", "owned-fetch")
                assertEquals("applied", answer("fetch").optString("outcome"))
                assertEquals(1, fetches.get())
                assertEquals(1, inspections.get())
            }
        } finally { release.countDown(); requests.close(); scope.cancel(); operations.stop(); pending.close() }
    }
}
