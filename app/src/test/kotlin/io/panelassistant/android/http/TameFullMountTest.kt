package io.panelassistant.android.http

import android.content.ContextWrapper
import android.content.pm.PackageManager
import io.panelassistant.android.Config
import io.panelassistant.android.control.TameController
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.panelassistant.PanelAssistantManagement
import io.panelassistant.android.panelassistant.PanelAssistantManagementRequests
import io.panelassistant.android.panelassistant.PanelAssistantSession
import io.panelassistant.android.panelassistant.PanelAssistantSessionEvent
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.sensors.HaApiSessionProvider
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Test
import sun.misc.Unsafe
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TameFullMountTest {
    @Test fun `suggestions render at request time and reject untrusted reads before observation`() {
        PaneldServerHttpFixture().use { fixture ->
            var observations = 0
            prepare(fixture) { observations++ }
            testApplication {
                application { fixture.mount(this) }
                for ((header, value) in listOf(
                    HttpHeaders.Origin to "http://elsewhere.example",
                    "Sec-Fetch-Site" to "cross-site",
                    HttpHeaders.Host to "elsewhere.example",
                )) {
                    val rejected = client.get("/api/v1/tame/suggest") { header(header, value) }
                    assertEquals(HttpStatusCode.Forbidden, rejected.status)
                    assertEquals(0, observations)
                }
                // Source-text reason: bundled locale data is runtime input, not a source-shape assertion.
                val catalogue = CatalogueLoader { File("src/main/assets", it).readText() }
                for (locale in listOf("en", "de")) {
                    val before = observations
                    val response = client.get("/api/v1/tame/suggest?lang=$locale")
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("text/html; charset=UTF-8", response.headers[HttpHeaders.ContentType])
                    assertEquals("DENY", response.headers["X-Frame-Options"])
                    assertEquals(
                        "<p class=\"note\">${esc(catalogue.strings(locale).get("install.tame.suggest.none_found"))}</p>",
                        response.bodyAsText(),
                    )
                    assertTrue(observations > before, "Each admitted request must observe packages anew")
                }
            }
        }
    }

    @Test fun `hand back and record routes retain real approval before package ownership access`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
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
                    for (path in listOf("hand-back-home", "tame/record")) {
                        val pending = client.post("/api/v1/$path") {
                            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                            setBody("pkg=com.vendor.keep")
                        }
                        assertEquals(HttpStatusCode.Accepted, pending.status)
                        assertEquals("approval-required", JSONObject(pending.bodyAsText()).getString("error"))
                        LocalApprovalBroker.instance.clear()
                    }
                    assertEquals(emptyList(), fixture.config.tameVendorPackages)
                }
            } finally {
                LocalApprovalBroker.instance.clear()
            }
        }
    }

    @Test fun `ownership routes fail closed for unknown package observations`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
            testApplication {
                application { fixture.mount(this) }
                suspend fun record(pkg: String) = client.post("/api/v1/tame/record") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody("pkg=$pkg")
                }
                val invalid = record("not-a-package")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertEquals("bad-package", JSONObject(invalid.bodyAsText()).getString("error"))
                val unknown = record("com.vendor.unknown")
                assertEquals(HttpStatusCode.ServiceUnavailable, unknown.status)
                assertEquals("unknown", JSONObject(unknown.bodyAsText()).getString("result"))
                val handBack = client.post("/api/v1/hand-back-home")
                assertEquals(HttpStatusCode.Conflict, handBack.status)
                assertEquals("package-state-unknown", JSONObject(handBack.bodyAsText()).getString("error"))
                assertEquals(emptyList(), fixture.config.tameVendorPackages)
            }
        }
    }

    @Test fun `full mount rejects invalid or unknown packages and enforces root admission`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
            testApplication {
                application { fixture.mount(this) }
                suspend fun post(body: String) = client.post("/api/v1/tame") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Accept, "application/json")
                    setBody(body)
                }
                val invalid = post("pkg=not-a-package")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertEquals("invalid or protected package\n", invalid.bodyAsText())
                val unknown = post("pkg=com.vendor.unknown")
                assertEquals(HttpStatusCode.BadRequest, unknown.status)
                assertEquals("invalid or protected package\n", unknown.bodyAsText())
                val origin = client.post("/api/v1/tame") {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, origin.status)
                val oversized = post("pkg=" + "x".repeat(PaneldServer.MAX_SMALL_FORM_POST_BODY_BYTES.toInt()))
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            }
        }
    }

    @Test fun `full mount requires physical approval before enabling a vendor package`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
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
                    val pending = client.post("/api/v1/tame") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        setBody("action=untame&pkg=com.vendor.keep")
                    }
                    assertEquals(HttpStatusCode.Accepted, pending.status)
                    assertEquals("approval-required", JSONObject(pending.bodyAsText()).getString("error"))
                    assertEquals(emptyList(), fixture.config.tameVendorPackages)
                }
            } finally {
                LocalApprovalBroker.instance.clear()
            }
        }
    }

    @Test fun `repeated recommended requests retain durable selections and both response formats`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
            fixture.config.setTameVendorPackages("com.vendor.keep")
            testApplication {
                application { fixture.mount(this) }
                suspend fun recommended(accept: String) = client.post("/api/v1/tame") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    header(HttpHeaders.Accept, accept)
                    setBody("action=recommended")
                }
                val json = recommended("application/json")
                assertEquals(HttpStatusCode.OK, json.status)
                val body = JSONObject(json.bodyAsText())
                assertEquals("started", body.getString("status"))
                assertEquals("install#cfg-tame", body.getString("return_to"))
                assertEquals(listOf("com.vendor.keep"), fixture.config.tameVendorPackages)
                val html = recommended("text/html")
                assertEquals(HttpStatusCode.OK, html.status)
                assertTrue(html.bodyAsText().contains("2;url=install#cfg-tame"))
                assertEquals(listOf("com.vendor.keep"), fixture.config.tameVendorPackages)
            }
        }
    }

    /**
     * Removal from a panel whose ADB is closed asks for the hand back over Panel Assistant's session, so the
     * request has to pass the session parser and the dispatcher's command rule and reach the real route; the
     * route's refusal body is what Panel Assistant names the problem from.
     */
    @Test fun `Panel Assistant's hand back request over its session reaches the route and returns its answer`() {
        PaneldServerHttpFixture().use { fixture ->
            prepare(fixture)
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val auth = HaApiSessionProvider { error("Hand back moves no blobs") }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val requests = PanelAssistantManagementRequests(
                    scope,
                    PanelAssistantManagement.of(
                        { error("no snapshot") }, { _, _ -> error("no settings") }, { fixture.door },
                        serve = { op, args, onPending -> fixture.server.managementRequest(op, args, onPending, auth) },
                    ),
                    PanelAssistantSession(5, SESSION, listOf("management", "local_adb"), "1.0.0"),
                    { System.nanoTime() / 1_000_000 },
                )
                var messageId = 2L
                // As Panel Assistant's local_adb.async_hand_back_home sends it: the op, empty args and a deadline.
                fun deliver(commandId: String, op: String): PanelAssistantSessionEvent.Manage {
                    val frame = JSONObject().put("id", 1).put("type", "event").put(
                        "event",
                        JSONObject().put("kind", "manage").put("command_id", commandId).put("session", SESSION)
                            .put("op", op).put("args", JSONObject()).put("deadline_ms", 30_000),
                    )
                    val event = PanelAssistantTransportProtocol.sessionEvent(frame, 1L) as PanelAssistantSessionEvent.Manage
                    requests.onRequest(event)
                    return event
                }
                suspend fun answer(commandId: String): JSONObject {
                    val until = System.nanoTime() + 15_000_000_000L
                    while (System.nanoTime() < until) {
                        while (true) {
                            val sent = JSONObject(requests.next(messageId++) ?: break)
                            if (sent.optString("command_id") == commandId && !sent.optBoolean("interim")) return sent
                        }
                        delay(10)
                    }
                    throw AssertionError("no answer to $commandId")
                }
                try {
                    // The name is Panel Assistant's wire contract, so it is written out rather than read from the app.
                    deliver("h1", "hand_back_home")
                    val handed = answer("h1")
                    // The fixture cannot read the package inventory, so the real controller refuses with
                    // package-state-unknown; only the route itself writes that body.
                    assertEquals("package-state-unknown", handed.optJSONObject("result")?.optString("error"))
                    assertEquals(listOf("refused", "not_commandable"), listOf(handed.optString("outcome"), handed.optString("code")))

                    // The path's own spelling is not an operation name: the parser empties it, so nothing runs.
                    assertEquals("", deliver("h2", "hand-back-home").op)
                    val hyphenated = answer("h2")
                    assertEquals(listOf("refused", "unknown_command"), listOf(hyphenated.optString("outcome"), hyphenated.optString("code")))

                    // Hand back is the one command Panel Assistant may send this way; any other stays refused unrun.
                    deliver("h3", "tame.record")
                    val other = answer("h3")
                    assertEquals(listOf("refused", "not_commandable"), listOf(other.optString("outcome"), other.optString("code")))
                    assertEquals(null, other.opt("result"))
                    assertEquals(emptyList(), fixture.config.tameVendorPackages)
                } finally {
                    requests.close()
                    scope.cancel()
                }
            }
        }
    }

    private fun prepare(fixture: PaneldServerHttpFixture, onObservation: () -> Unit = {}) {
        // Source-text reason: load the shipped locale data as runtime input, not a code-shape assertion.
        field(fixture.server, "catalogueLoader\$delegate", lazyOf(CatalogueLoader {
            File("src/main/assets", it).readText()
        }))
        // Android package inventory is unavailable in this JVM fixture. The real controller must
        // classify it as unknown and refuse taming, rather than assuming an unobserved app is safe.
        val tame = unsafe.allocateInstance(TameController::class.java) as TameController
        field(tame, "context", object : ContextWrapper(null) {
            override fun getPackageName(): String = "io.github.maxlyth.hapaneld"
            override fun getPackageManager(): PackageManager {
                onObservation()
                throw UnsupportedOperationException("Package inventory unavailable in JVM fixture")
            }
        })
        field(fixture.server, "tame", tame)
        field(fixture.server, "tameProfileCandidates", emptyList<Any>())
        val authority = TameReconcileAuthority(
            readDesired = { fixture.config.tameVendorPackages.toSet() },
            reconcile = { _, _ -> error("Closed tame authority must not actuate packages") },
            stopping = { true },
        )
        check(authority.closeAndJoin(1_000))
        field(fixture.server, "tameReconciliation", authority)
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private companion object {
        const val SESSION = "session-1"
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
