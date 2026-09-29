package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.control.InteractiveController
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.platform.ActivityRef
import io.github.maxlyth.hapaneld.platform.SystemEnv
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteControlFullMountTest {
    private var stopping = true
    @Test fun `full mount validates remote input and actions behind root guards and bounded readers`() {
        PaneldServerHttpFixture().use { fixture ->
            closedControls(fixture)
            testApplication {
                application { fixture.mount(this) }
                suspend fun form(path: String, body: String) = client.post("/api/v1/$path") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(body)
                }
                val coordinates = form("input", "x=NaN&y=2")
                assertEquals(HttpStatusCode.BadRequest, coordinates.status)
                assertEquals("bad-coords\n", coordinates.bodyAsText())
                val capture = form("input", "x=1&y=2&capture=nonsense")
                assertEquals(HttpStatusCode.BadRequest, capture.status)
                assertEquals("bad-capture\n", capture.bodyAsText())
                val action = form("action", "a=arbitrary-command")
                assertEquals(HttpStatusCode.BadRequest, action.status)
                assertEquals("bad-action\n", action.bodyAsText())
                val origin = client.post("/api/v1/input") { header(HttpHeaders.Origin, "http://elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, origin.status)
                assertEquals("cross-origin refused\n", origin.bodyAsText())
                val oversized = form("input", "x=" + "1".repeat(PaneldServer.MAX_SMALL_FORM_POST_BODY_BYTES.toInt()))
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                assertEquals("request too large\n", oversized.bodyAsText())
            }
        }
    }

    @Test fun `full mount refuses hardened remote taps and requires physical approval for reboot`() {
        PaneldServerHttpFixture().use { fixture ->
            closedControls(fixture)
            assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            LocalApprovalBroker.instance.clear()
            try {
                testApplication {
                    application {
                        intercept(ApplicationCallPipeline.Setup) {
                            context.mutableOriginConnectionPoint.remoteAddress = "192.168.50.20"
                        }
                        fixture.mount(this)
                    }
                    val tap = client.submitForm("/api/v1/input", Parameters.build {
                        append("x", "1"); append("y", "2"); append("capture", "1")
                    })
                    assertEquals(HttpStatusCode.Forbidden, tap.status)
                    assertEquals("remote-input-disabled", JSONObject(tap.bodyAsText()).getString("error"))
                    suspend fun reboot() = client.submitForm("/api/v1/action", Parameters.build { append("a", "reboot") })
                    val pending = reboot()
                    assertEquals(HttpStatusCode.Accepted, pending.status)
                    val body = JSONObject(pending.bodyAsText())
                    assertEquals("approval-required", body.getString("error"))
                    assertTrue(LocalApprovalBroker.instance.approve(body.getString("approval_id")))
                    val approved = reboot()
                    assertEquals(HttpStatusCode.ServiceUnavailable, approved.status)
                    assertEquals("control queue busy\n", approved.bodyAsText())
                }
            } finally {
                LocalApprovalBroker.instance.clear()
            }
        }
    }

    @Test fun `full mount distinguishes a stopped control plane from a busy live queue`() {
        PaneldServerHttpFixture().use { fixture ->
            closedControls(fixture)
            testApplication {
                application { fixture.mount(this) }
                suspend fun back() = client.submitForm("/api/v1/action", Parameters.build { append("a", "back") })
                val stopped = back()
                assertEquals(HttpStatusCode.ServiceUnavailable, stopped.status)
                assertEquals("control queue busy\n", stopped.bodyAsText())
                stopping = false
                val busy = back()
                assertEquals(HttpStatusCode.Conflict, busy.status)
                assertEquals("control queue busy\n", busy.bodyAsText())
            }
        }
    }

    private fun closedControls(fixture: PaneldServerHttpFixture) {
        val owner = RemoteControlRoutes(
            config = fixture.config,
            interactive = InteractiveController(canSu = false),
            system = SystemController(object : SystemEnv {
                override val ownPackage = "io.github.maxlyth.hapaneld"
                override fun isInstalled(pkg: String) = false
                override fun launchComponent(pkg: String): String? = null
                override fun homeActivities(): List<ActivityRef> = emptyList()
                override fun defaultHome(): ActivityRef? = null
                override fun directStart(component: String) = error("Closed control owner")
            }),
            stepVolume = { error("Closed control owner") },
            stopping = { stopping },
            cacheScreenshot = { error("Closed control owner") },
        )
        check(owner.closeAndJoin(1_000))
        field(fixture.server, "remoteControlRoutes", owner)
    }

    private fun field(server: PaneldServer, name: String, value: Any) {
        PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(server, value)
    }
}
