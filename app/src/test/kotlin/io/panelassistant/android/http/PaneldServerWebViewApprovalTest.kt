package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.HaApiSessionProvider
import io.panelassistant.android.util.InstallOutcome
import io.panelassistant.android.util.WebViewApk
import io.panelassistant.android.util.WebViewObservation
import io.panelassistant.android.util.WebViewUpdate
import io.panelassistant.android.util.WebViewUpdateIo
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaneldServerWebViewApprovalTest {
    @Test fun nativeProviderInstallWaitsForExactApprovalBeforeEnteringTheOwner() = runTest {
        val broker = LocalApprovalBroker.instance
        broker.clear()
        try {
            PaneldServerHttpFixture().use { fixture ->
                fixture.config.setSecurityMode(Config.SecurityMode.HARDENED)
                var observations = 0
                val io = object : WebViewUpdateIo {
                    override val directory = fixture.directory
                    override fun observe(): WebViewObservation {
                        observations++
                        return WebViewObservation("generic", 27, emptySet(), "process", 1, 0,
                            null, null, null, emptySet(), null, null, null, "none")
                    }
                    override fun readReceipt(): JSONObject? = null
                    override fun writeReceipt(receipt: JSONObject): Unit = error("Unadmitted install wrote a receipt")
                    override fun deleteReceipt(): Unit = error("Unadmitted install deleted a receipt")
                    override fun inspect(file: File): WebViewApk? = error("Unadmitted install inspected an APK")
                    override fun download(spec: io.panelassistant.android.device.WebViewSpec, destination: File): Boolean =
                        error("Unadmitted install downloaded an APK")
                    override fun installedMatches(spec: io.panelassistant.android.device.WebViewSpec): Boolean = false
                    override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome =
                        error("Unadmitted install submitted a package")
                }
                // Reuse the complete server fixture; only its platform owner IO is replaced.
                PaneldServer::class.java.getDeclaredField("webViewUpdate\$delegate").apply {
                    isAccessible = true
                    set(fixture.server, lazyOf(WebViewUpdate(this@runTest, { null }, io)))
                }
                val auth = HaApiSessionProvider { error("Provider admission does not fetch session blobs") }
                val request = JSONObject().put("request_id", "owner-one").put("artifact_sha256", "a".repeat(64))
                var pending = 0
                val refused = async { fixture.server.managementRequest("webview.install", request, { pending++ }, auth) }
                runCurrent()
                assertFalse("Native request must wait for local approval", refused.isCompleted)
                assertEquals(1, pending)
                assertEquals(0, observations)
                val denied = broker.pending().single()
                assertEquals(SensitiveOperation.APK_INSTALL, denied.operation)
                assertEquals("Update System WebView", denied.summary)
                assertEquals(PANEL_ASSISTANT_APPROVAL_PEER, denied.peer)
                assertTrue(broker.deny(denied.id))
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(PanelAssistantTransportProtocol.OUTCOME_REFUSED, refused.await()!!.outcome)
                assertEquals("approval_denied", refused.await()!!.code)
                assertNull(refused.await()!!.result)
                assertEquals(0, observations)

                val approved = async { fixture.server.managementRequest("webview.install", request, { pending++ }, auth) }
                runCurrent()
                assertEquals(2, pending)
                val approval = broker.pending().single()
                assertTrue(broker.approve(approval.id))
                advanceTimeBy(1_000)
                runCurrent()
                val answer = approved.await()!!
                assertEquals(PanelAssistantTransportProtocol.OUTCOME_APPLIED, answer.outcome)
                assertNull(answer.code)
                assertEquals("no_candidate", (answer.result as JSONObject).getString("reason"))
                assertEquals("The approved request reached the real provider owner", 1, observations)
                assertEquals(ApprovalBroker.State.ABSENT, broker.state(approval.id))

                // A consumed approval cannot authorize another native dispatch, even with identical bytes.
                val replay = async { fixture.server.managementRequest("webview.install", request, { pending++ }, auth) }
                runCurrent()
                assertFalse(replay.isCompleted)
                assertEquals(3, pending)
                assertEquals(1, observations)
                replay.cancel()
                runCurrent()
                assertTrue(broker.pending().isEmpty())

                // An approved request cannot authorize changed native request bytes before its own poll consumes it.
                for (changedField in listOf("request_id", "artifact_sha256")) {
                    val exact = JSONObject().put("request_id", "binding-$changedField").put("artifact_sha256", "a".repeat(64))
                    val beforeObservations = observations
                    val original = async { fixture.server.managementRequest("webview.install", exact, { pending++ }, auth) }
                    runCurrent()
                    assertTrue(broker.approve(broker.pending().single().id))
                    val different = JSONObject(exact.toString()).put(changedField,
                        if (changedField == "request_id") "different-owner" else "b".repeat(64))
                    val changed = async { fixture.server.managementRequest("webview.install", different, { pending++ }, auth) }
                    runCurrent()
                    assertFalse("$changedField must remain bound to the approved request", changed.isCompleted)
                    assertEquals(beforeObservations, observations)
                    assertEquals("A distinct changed request needs pending approval", 1, broker.pending().size)
                    assertTrue(broker.deny(broker.pending().single().id))
                    advanceTimeBy(1_000)
                    runCurrent()
                    assertEquals(PanelAssistantTransportProtocol.OUTCOME_APPLIED, original.await()!!.outcome)
                    assertEquals("approval_denied", changed.await()!!.code)
                    assertEquals(beforeObservations + 1, observations)
                }
                assertEquals(7, pending)
                fixture.config.setSecurityMode(Config.SecurityMode.RELAXED)
                val relaxed = fixture.server.managementRequest("webview.install", request, { pending++ }, auth)!!
                assertEquals(PanelAssistantTransportProtocol.OUTCOME_APPLIED, relaxed.outcome)
                assertEquals("no_candidate", (relaxed.result as JSONObject).getString("reason"))
                assertEquals(7, pending)
                assertEquals(4, observations)
            }
        } finally {
            broker.clear()
        }
    }

    @Test fun nativeProviderRefusalsAnswerWithTransportContractCodesAndKeepTheReason() = runTest {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setSecurityMode(Config.SecurityMode.RELAXED)
            val receipt = JSONObject().put("schema", 1).put("request_id", "held").put("artifact_sha256", "a".repeat(64))
                .put("state", "installed").put("reason", JSONObject.NULL).put("process_id", "process")
            val io = object : WebViewUpdateIo {
                override val directory = fixture.directory
                override fun observe() = WebViewObservation("generic", 27, emptySet(), "process", 1, 0,
                    null, null, null, emptySet(), null, null, null, "none")
                override fun readReceipt(): JSONObject? = receipt
                override fun writeReceipt(receipt: JSONObject): Unit = error("A refused request wrote a receipt")
                override fun deleteReceipt(): Unit = error("A refused request deleted a receipt")
                override fun inspect(file: File): WebViewApk? = error("A refused request inspected an APK")
                override fun download(spec: io.panelassistant.android.device.WebViewSpec, destination: File): Boolean =
                    error("A refused request downloaded an APK")
                override fun installedMatches(spec: io.panelassistant.android.device.WebViewSpec): Boolean = false
                override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome =
                    error("A refused request submitted a package")
            }
            PaneldServer::class.java.getDeclaredField("webViewUpdate\$delegate").apply {
                isAccessible = true
                set(fixture.server, lazyOf(WebViewUpdate(this@runTest, { null }, io)))
            }
            val auth = HaApiSessionProvider { error("Provider install does not fetch session blobs") }
            // Panel Assistant's schema accepts only contract outcome codes; anything else is dropped and times out.
            val invalid = fixture.server.managementRequest("webview.install", JSONObject(), {}, auth)!!
            assertEquals(PanelAssistantTransportProtocol.OUTCOME_REFUSED, invalid.outcome)
            assertEquals("invalid_value", invalid.code)
            assertEquals("invalid_request", (invalid.result as JSONObject).getString("reason"))

            val conflict = fixture.server.managementRequest(
                "webview.install", JSONObject().put("request_id", "held").put("artifact_sha256", "b".repeat(64)), {}, auth,
            )!!
            assertEquals(PanelAssistantTransportProtocol.OUTCOME_REFUSED, conflict.outcome)
            assertEquals("not_commandable", conflict.code)
            assertEquals("request_conflict", (conflict.result as JSONObject).getString("reason"))
        }
    }
}
