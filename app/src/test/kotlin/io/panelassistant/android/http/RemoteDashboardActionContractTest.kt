package io.panelassistant.android.http

import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteDashboardActionContractTest {
    private val own = "io.github.maxlyth.hapaneld"
    private val foreign = "io.homeassistant.companion.android.minimal"

    @Before fun clearBuiltinLatch() = BuiltinDashboard.clearRendererLatch()

    @Test fun dashboardEndpointPreservesAutomaticBuiltinAndExplicitForeignRouting() = testApplication {
        var selection = ""
        var root = FakeRootShell()
        var system = controller(FakeSystemEnv(installed = setOf(foreign), launchers = mapOf(foreign to "$foreign/.Main")), root)
        application {
            routing {
                post("/action") {
                    handleRemoteAction(call, RemoteActionRouteDependencies(
                        authorizeSensitive = { _, _, _, _ -> true },
                        admit = { call, action ->
                            assertTrue(executeRemoteDashboardAction(
                                action,
                                selection,
                                launch = { system.launchHome(it) },
                                reload = { system.reloadDashboard(it) },
                            ))
                            call.respondText("queued\n", status = HttpStatusCode.Accepted)
                        },
                    ))
                }
            }
        }

        assertAccepted("dashboard")
        assertEquals("Auto must retain built-in authority", listOf("am start -n $own/io.panelassistant.android.DashboardActivity"), root.ran)

        selection = SystemController.BUILTIN_DASHBOARD
        root = FakeRootShell()
        system = controller(FakeSystemEnv(), root)
        assertAccepted("dashboard")
        assertEquals(listOf("am start -n $own/io.panelassistant.android.DashboardActivity"), root.ran)

        selection = foreign
        root = FakeRootShell()
        system = controller(FakeSystemEnv(installed = setOf(foreign), launchers = mapOf(foreign to "$foreign/.Main")), root)
        assertAccepted("dashboard")
        assertEquals(listOf("am start -n $foreign/.Main"), root.ran)
    }

    @Test fun unavailableAndOperationSuppressedRenderersStayNoOpWithoutRecoverySubstitution() = testApplication {
        var selection = foreign
        var root = FakeRootShell()
        var system = controller(FakeSystemEnv(), root)
        application {
            routing {
                post("/action") {
                    handleRemoteAction(call, RemoteActionRouteDependencies(
                        authorizeSensitive = { _, _, _, _ -> true },
                        admit = { call, action ->
                            assertTrue(executeRemoteDashboardAction(
                                action,
                                selection,
                                launch = { system.launchHome(it) },
                                reload = { system.reloadDashboard(it) },
                            ))
                            call.respondText("queued\n", status = HttpStatusCode.Accepted)
                        },
                    ))
                }
            }
        }

        assertAccepted("dashboard")
        assertTrue("an unavailable explicit renderer must not fall back to another target", root.ran.isEmpty())

        root = FakeRootShell()
        system = controller(FakeSystemEnv(installed = setOf(foreign), launchers = mapOf(foreign to "$foreign/.Main")), root)
        CompanionDataOperationGate.acquire(foreign)!!.use {
            assertAccepted("dashboard")
            assertAccepted("reload")
        }
        assertTrue("Companion data ownership must suppress both foreground and reload effects", root.ran.isEmpty())
    }

    @Test fun hardenedSensitiveActionsPreserveLoopbackAndRequireRemoteExactApproval() = testApplication {
        val broker = ApprovalBroker()
        val admitted = mutableListOf<String>()
        var peer = "192.0.2.10"
        application {
            routing {
                post("/action") {
                    handleRemoteAction(call, RemoteActionRouteDependencies(
                        authorizeSensitive = { call, operation, payload, summary ->
                            authorizeSensitiveRequest(call, true, peer, operation, payload, summary, broker)
                        },
                        admit = { call, action ->
                            admitted += action
                            call.respondText("queued\n", status = HttpStatusCode.Accepted)
                        },
                    ))
                }
            }
        }

        val dashboard = postAction("dashboard")
        assertEquals(HttpStatusCode.Accepted, dashboard.status)
        assertEquals(listOf("dashboard"), admitted)
        assertTrue("routine Dashboard foregrounding must not open an approval", broker.pending().isEmpty())

        peer = "127.0.0.1"
        assertAccepted("reload")
        assertAccepted("reboot")
        assertEquals(listOf("dashboard", "reload", "reboot"), admitted)
        assertTrue("Hardened loopback actions must retain their established exemption", broker.pending().isEmpty())

        peer = "192.0.2.10"
        val denied = postAction("reload")
        assertEquals(HttpStatusCode.Accepted, denied.status)
        assertTrue(denied.bodyAsText().contains("approval-required"))
        assertEquals(listOf("dashboard", "reload", "reboot"), admitted)
        val pending = broker.pending().single()
        assertEquals(SensitiveOperation.DASHBOARD_RELOAD, pending.operation)
        assertTrue(broker.approve(pending.id))

        val approved = postAction("reload")
        assertEquals(HttpStatusCode.Accepted, approved.status)
        assertEquals("queued\n", approved.bodyAsText())
        assertEquals(listOf("dashboard", "reload", "reboot", "reload"), admitted)

        postAction("reload")
        assertEquals(
            "an approval is consumed by its first exact replay",
            listOf("dashboard", "reload", "reboot", "reload"),
            admitted,
        )
        assertEquals(SensitiveOperation.DASHBOARD_RELOAD, broker.pending().single().operation)
        broker.clear()

        val rebootDenied = postAction("reboot")
        assertEquals(HttpStatusCode.Accepted, rebootDenied.status)
        assertTrue(rebootDenied.bodyAsText().contains("approval-required"))
        val reboot = broker.pending().single()
        assertEquals(SensitiveOperation.DEVICE_REBOOT, reboot.operation)
        assertTrue(broker.approve(reboot.id))
        assertAccepted("reboot")
        assertEquals(listOf("dashboard", "reload", "reboot", "reload", "reboot"), admitted)
    }

    private fun controller(env: FakeSystemEnv, root: FakeRootShell) =
        SystemController(env, root, FakeDaemon(available = false), builtinForeground = { false })

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertAccepted(action: String) {
        val response = postAction(action)
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("queued\n", response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postAction(action: String) =
        client.post("/action") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("a=$action")
        }
}
