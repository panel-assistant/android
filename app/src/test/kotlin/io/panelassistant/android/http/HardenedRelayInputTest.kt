package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.Config
import io.panelassistant.android.ManageReply
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.panelassistant.PanelAssistantManagement
import io.panelassistant.android.panelassistant.PanelAssistantManagementRequests
import io.panelassistant.android.panelassistant.PanelAssistantSession
import io.panelassistant.android.panelassistant.PanelAssistantSessionEvent
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.security.LocalApprovalBroker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who may inject a tap in Hardened mode, through the callers a panel really has: the Panel Assistant session
 * that relays a Home Assistant administrator's request, the page socket of software on the panel, and HTTP.
 * Every case reads the taps the input backend received, not only the answer.
 */
class HardenedRelayInputTest {
    private companion object {
        const val ADMINISTRATOR = "0123456789abcdef0123456789abcdef"
        const val SESSION = "relay-session"
        const val LAN_PEER = "192.168.50.20"
    }

    @Test fun `a relayed administrator's tap is refused in Hardened mode and never injected`() = relayedTapIsRefused(capture = false)

    @Test fun `a relayed administrator's tap and capture is refused in Hardened mode and never injected`() = relayedTapIsRefused(capture = true)

    private fun relayedTapIsRefused(capture: Boolean) = rig(hardened = true) {
        val answer = relayed("input", tapArgs(11, 22, capture))
        settle()
        assertEquals(emptyList(), taps, answer.toString())
        assertEquals("refused", answer.getString("outcome"), answer.toString())
        assertEquals("refused_hardened", answer.getString("code"), answer.toString())
        assertEquals(403, answer.getInt("status"), answer.toString())
        assertEquals("remote-input-disabled", answer.getJSONObject("result").getString("error"))
    }

    @Test fun `a relayed administrator's tap is injected in Relaxed mode`() = rig(hardened = false) {
        val plain = relayed("input", tapArgs(11, 22, capture = false))
        assertEquals("applied", plain.getString("outcome"), plain.toString())
        settle()
        assertEquals(listOf(11 to 22), taps)
        // The combined tap and capture answers once its screenshot is taken, so the tap has been injected by then.
        relayed("input", tapArgs(33, 44, capture = true))
        assertEquals(listOf(11 to 22, 33 to 44), taps)
    }

    @Test fun `software on the panel still taps in Hardened mode over HTTP and its page socket`() = rig(hardened = true) {
        val plain = http(11, 22, capture = false)
        assertEquals(HttpStatusCode.Accepted, plain.status, plain.bodyAsText())
        settle()
        assertEquals(listOf(11 to 22), taps)
        val captured = http(33, 44, capture = true)
        assertEquals(HttpStatusCode.OK, captured.status, captured.bodyAsText())
        assertEquals(listOf(11 to 22, 33 to 44), taps)
        val socket = socket("input", tapArgs(55, 66, capture = false))
        assertEquals("applied", socket.getString("outcome"), socket.toString())
        settle()
        assertEquals(listOf(11 to 22, 33 to 44, 55 to 66), taps)
    }

    @Test fun `a LAN peer's tap is refused in Hardened mode on either door`() = rig(hardened = true, lanPeer = true) {
        for (capture in listOf(false, true)) {
            val direct = http(11, 22, capture)
            assertEquals(HttpStatusCode.Forbidden, direct.status)
            assertEquals("remote-input-disabled", JSONObject(direct.bodyAsText()).getString("error"))
            val managed = managed("input", tapArgs(11, 22, capture), CommandOrigin.peer(LAN_PEER))
            assertEquals("refused_hardened", managed.code, managed.result.toString())
            assertEquals(403, managed.status)
        }
        settle()
        assertEquals(emptyList(), taps)
    }

    @Test fun `Panel Assistant's own origin never taps in Hardened mode`() = rig(hardened = true) {
        for (capture in listOf(false, true)) {
            val answer = managed("input", tapArgs(11, 22, capture), CommandOrigin.panelAssistant())
            assertEquals("refused_hardened", answer.code, answer.result.toString())
            assertEquals(403, answer.status)
        }
        settle()
        assertEquals(emptyList(), taps)
    }

    @Test fun `a LAN peer's tap is injected in Relaxed mode`() = rig(hardened = false, lanPeer = true) {
        val direct = http(11, 22, capture = false)
        assertEquals(HttpStatusCode.Accepted, direct.status, direct.bodyAsText())
        settle()
        assertEquals(listOf(11 to 22), taps)
    }

    @Test fun `a relayed administrator still changes power safety and vendor packages in Hardened mode without approval`() =
        withFullReadServer { config, fixture ->
            assertTrue(config.applyBatch { config.setKeepAwake(true) })
            assertTrue(config.setSecurityMode(Config.SecurityMode.HARDENED))
            LocalApprovalBroker.instance.clear()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val requests = relaySession(fixture, scope)
            try {
                testApplication {
                    application { fixture.mount(this) }
                    startApplication()
                    val power = relayed(requests, "settings", JSONObject().put("keep_awake", "false"))
                    assertEquals("applied", power.getString("outcome"), power.toString())
                    assertEquals(false, config.keepAwake)
                    val packages = relayed(requests, "settings", JSONObject().put("tame_vendor_packages", "com.example.vendor"))
                    assertEquals("applied", packages.getString("outcome"), packages.toString())
                    assertEquals(listOf("com.example.vendor"), config.tameVendorPackages)
                    assertTrue(config.applyBatch { config.setKeepAwake(true) })
                    val both = relayed(
                        requests, "settings",
                        JSONObject().put("keep_awake", "false").put("tame_vendor_packages", "com.example.other"),
                    )
                    assertEquals("applied", both.getString("outcome"), both.toString())
                    assertEquals(false, config.keepAwake)
                    assertEquals(listOf("com.example.other"), config.tameVendorPackages)
                    assertTrue(LocalApprovalBroker.instance.pending().isEmpty())
                }
            } finally {
                requests.close()
                scope.cancel()
                LocalApprovalBroker.instance.clear()
            }
        }

    private class Rig(
        private val test: ApplicationTestBuilder,
        private val fixture: PaneldServerHttpFixture,
        private val requests: PanelAssistantManagementRequests,
        private val volume: Semaphore,
        val taps: List<Pair<Int, Int>>,
    ) {
        fun tapArgs(x: Int, y: Int, capture: Boolean): JSONObject =
            JSONObject().put("x", x).put("y", y).apply { if (capture) put("capture", "1") }

        /** The production caller: a `manage` event of the Panel Assistant session that names the browser's user. */
        fun relayed(op: String, args: JSONObject): JSONObject = relayed(requests, op, args)

        /** The managed door as the stub's pages and socket enter it, with the origin the door constructed. */
        fun managed(op: String, args: JSONObject, origin: CommandOrigin): ManageReply {
            val answer = LinkedBlockingQueue<ManageReply>()
            fixture.door.manage(java.util.UUID.randomUUID().toString(), op, args, origin) { answer.put(it) }
            return checkNotNull(answer.poll(20, TimeUnit.SECONDS)) { "no answer to managed $op" }
        }

        suspend fun http(x: Int, y: Int, capture: Boolean): HttpResponse =
            test.client.submitForm("/api/v1/input", Parameters.build {
                append("x", "$x"); append("y", "$y"); if (capture) append("capture", "1")
            })

        /** One request over the page socket, from the address the test engine connects from: loopback. */
        suspend fun socket(op: String, args: JSONObject): JSONObject {
            var answer: JSONObject? = null
            test.createClient { install(WebSockets) }.webSocket("/ui", request = { header(HttpHeaders.Host, "localhost") }) {
                send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "socket-1").put("op", op).put("args", args).toString()))
                answer = withTimeout(20_000) {
                    var frame: JSONObject
                    do frame = JSONObject((incoming.receive() as Frame.Text).readText())
                    while (frame.optString("command_id") != "socket-1" || frame.optBoolean("interim"))
                    frame
                }
            }
            return checkNotNull(answer)
        }

        /**
         * Wait until every tap admitted so far has run: the control queue runs its keys in admission order, so a
         * volume step admitted now runs after any tap already queued.
         */
        suspend fun settle() {
            val step = test.client.submitForm("/api/v1/action", Parameters.build { append("a", "volup") })
            assertEquals(HttpStatusCode.Accepted, step.status, step.bodyAsText())
            assertTrue(volume.tryAcquire(20, TimeUnit.SECONDS), "the control queue never reached the volume step")
        }
    }

    private fun rig(hardened: Boolean, lanPeer: Boolean = false, block: suspend Rig.() -> Unit) {
        PaneldServerHttpFixture().use { fixture ->
            val taps = CopyOnWriteArrayList<Pair<Int, Int>>()
            val volume = Semaphore(0)
            val accessibility = object : AccessibilityActions {
                override fun back() = false
                override fun recents() = false
                override fun tap(x: Int, y: Int): Boolean { taps += x to y; return true }
                override fun canScreenshot() = true
                override fun screenshot(timeoutMs: Long) = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            }
            val owner = RemoteControlRoutes(
                fixture.config,
                InteractiveController(
                    canSu = false,
                    root = FakeRootShell(available = false, runResult = false),
                    daemon = FakeDaemon(available = false),
                    accessibility = accessibility,
                ),
                SystemController(FakeSystemEnv(), FakeRootShell(), FakeDaemon(), isDeviceSecure = { false }),
                stepVolume = { volume.release() }, stopping = { false }, cacheScreenshot = { null },
            )
            PaneldServer::class.java.getDeclaredField("remoteControlRoutes").apply { isAccessible = true }.set(fixture.server, owner)
            if (hardened) assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val requests = relaySession(fixture, scope)
            try {
                testApplication {
                    application {
                        if (lanPeer) intercept(ApplicationCallPipeline.Setup) {
                            context.mutableOriginConnectionPoint.remoteAddress = LAN_PEER
                        }
                        fixture.mount(this)
                    }
                    startApplication()
                    Rig(this, fixture, requests, volume, taps).block()
                }
            } finally {
                requests.close()
                scope.cancel()
                owner.closeAndJoin(1_000)
            }
        }
    }

    /** The session's own request handling over the fixture's production operations table. */
    private fun relaySession(fixture: PaneldServerHttpFixture, scope: CoroutineScope) = PanelAssistantManagementRequests(
        scope,
        PanelAssistantManagement.of({ error("unused") }, { _, _ -> error("unused") }, { fixture.door }),
        PanelAssistantSession(5, SESSION, listOf("management", "ui_subscribe", "ui_bundle"), "1.0.0"),
        { System.nanoTime() / 1_000_000 },
    )
}

private val relayedCommandIds = AtomicInteger()

/** Send one relayed `manage` event as Panel Assistant frames it, and return the session's first answer to it. */
private fun relayed(requests: PanelAssistantManagementRequests, op: String, args: JSONObject): JSONObject {
    val id = "relayed-${relayedCommandIds.incrementAndGet()}"
    val event = JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args)
        .put("session", "relay-session")
        .put("origin", JSONObject().put("user", "0123456789abcdef0123456789abcdef").put("connection", "relay-1"))
    val frame = JSONObject().put("id", 1).put("type", "event").put("event", event)
    requests.onRequest(PanelAssistantTransportProtocol.sessionEvent(frame, 1L) as PanelAssistantSessionEvent.Manage)
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (System.nanoTime() < until) {
        val answer = requests.next(2L)?.let(::JSONObject)
        if (answer == null) Thread.sleep(5)
        else if (answer.optString("command_id") == id) return answer
    }
    error("no answer to relayed $op")
}
