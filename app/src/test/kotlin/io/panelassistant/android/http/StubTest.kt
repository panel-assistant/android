package io.panelassistant.android.http

import io.panelassistant.android.testsupport.scratchDirectory

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.Config
import io.panelassistant.android.ManageFamily
import io.panelassistant.android.ManageKind
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageTable
import io.panelassistant.android.navbarMode
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantHelloOutcome
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The `:8888` stub of protocol section 20.7 through the production root and its real dispatcher: the closed
 * list and its 410s (section 20.9 check 6), the gate of section 20.8, the `/ui` door's origins, blobs, and
 * hardened mode's loopback-only listener (check 3).
 */
internal class StubTest : PanelRuntimeRig() {
    private val rigs = mutableListOf<Rig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
        LocalApprovalBroker.instance.clear()
    }

    private fun managedRig(table: ManageTable, families: Map<String, ManageFamily> = emptyMap(), configure: (Config) -> Unit = {}): Rig =
        rig(manageTable = table, manageFamilies = families, approvalPollMs = 20, deviceSecure = false, configure = configure).also { it.start(); rigs += it }

    @Test fun withTheRoutesClosedOnlyTheClosedListServesAndEveryOtherPathIsCountedGone() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.values[Config.UI_ROUTES_CLOSED_PREF] = true
            fixture.useWarmDiagnostics("Panel diagnostics\n", stopping = true)
            testApplication {
                var root: RoutingNode? = null
                application { fixture.mount(this); root = plugin(RoutingRoot) }
                startApplication()
                val gone = client.get("/api/v1/radio")
                assertEquals(HttpStatusCode.Gone, gone.status)
                assertEquals("gone", JSONObject(gone.bodyAsText()).getString("error"))
                val diag = client.get("/diag").bodyAsText()
                assertTrue(diag, diag.contains("GET /api/v1/radio 1\n"))

                val registered = registeredRoutes(requireNotNull(root))
                assertEquals("every closed endpoint is mounted", emptySet<Pair<HttpMethod, String>>(), StubEndpoints.ENDPOINTS - registered)

                val serving = registered.filter { (method, template) -> probe(method, template) != HttpStatusCode.Gone }.toSet()

                assertEquals(StubEndpoints.ENDPOINTS, serving)
                // Closing the routes keeps panel sign-in: the WebView's start and Home Assistant's redirect back.
                for (path in listOf("/api/v1/ha/oauth/panel-start", "/api/v1/ha/oauth/callback")) {
                    assertTrue(path, client.get(path).status != HttpStatusCode.Gone)
                }
            }
        }
    }

    @Test fun withTheRoutesOpenRestAnswersAsBeforeAndOnlyTheStepOneRetirementsAreGone() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useWarmDiagnostics("Panel diagnostics\n", stopping = true)
            testApplication {
                application { fixture.mount(this) }
                val radio = client.get("/api/v1/radio")
                assertEquals(HttpStatusCode.OK, radio.status)
                assertEquals("{\"present\":false,\"status\":\"none\"}", radio.bodyAsText())
                for (path in listOf("/test", "/api/v1/sensortrace", "/api/v1/proximity/teach", "/api/v1//sensortrace", "/api/v1/%73ensortrace")) {
                    assertEquals(path, HttpStatusCode.Gone, client.get(path).status)
                }
                // Counted as routing reads the path: the three spellings of sensortrace are one path.
                assertTrue(client.get("/api/v1/diag").bodyAsText().contains("GET /api/v1/sensortrace 3\n"))
            }
        }
    }

    @Test fun aPageNamesTheSocketOnlyWhenItsPeerMayOpenIt() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableConfigurePage(false)
            var peer = "192.168.50.20"
            testApplication {
                application {
                    intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                        context.mutableOriginConnectionPoint.remoteAddress = peer
                    }
                    fixture.mount(this)
                }
                suspend fun socketNamed(): Boolean {
                    val page = client.get("/configure") { header(HttpHeaders.Cookie, "wiz_escape=1") }
                    val html = page.bodyAsText()
                    assertEquals(html.take(300), HttpStatusCode.OK, page.status)
                    return html.contains("data-ui-socket=\"ui\"")
                }

                suspend fun hardenedNamed(): Boolean = Regex("<body[^>]*data-hardened").containsMatchIn(
                    client.get("/configure") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText(),
                )

                assertTrue("relaxed: a LAN page uses the socket", socketNamed())
                assertFalse("relaxed: the page is not marked Hardened", hardenedNamed())
                assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
                assertFalse("hardened: a LAN page stays on HTTP, which it may use", socketNamed())
                // Its scripts say at once to approve a held request on the panel only when they know the mode.
                assertTrue("hardened: the page says so, for its approval notices", hardenedNamed())
                peer = "127.0.0.1"
                assertTrue("hardened: the panel's own loopback page uses the socket", socketNamed())
            }
        }
    }

    @Test fun aRelayedPageTakesItsPresentationFromArgsEmbedWhileTheQueryNeverEmbedsIt() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableConfigurePage(false)
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val queried = client.get("/configure?embed=true&theme=dark&hide=install&lang=de") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }.bodyAsText()
                assertFalse("the query cannot switch the page into embedded mode", queried.contains("data-embedded"))
                assertFalse(queried.contains("data-theme=\"dark\""))
                assertTrue("the query's language still applies", queried.contains("<html lang=\"de\""))

                // page.configure as the sidebar relay asks for it.
                fun relayed(args: JSONObject): String {
                    val answer = LinkedBlockingQueue<ManageReply>()
                    fixture.door.manage(
                        java.util.UUID.randomUUID().toString(), "page.configure", args,
                        CommandOrigin.user("0123456789abcdef0123456789abcdef").withConnection("relay-1"),
                    ) { answer.put(it) }
                    val reply = requireNotNull(answer.poll(20, TimeUnit.SECONDS))
                    assertEquals("applied", reply.outcome)
                    return reply.result as String
                }
                fun tabs(page: String) = Regex("<a href=\"([a-z./]*)[^\"]*\"").findAll(
                    requireNotNull(Regex("<div class=\"nav\">(.*?)</div>").find(page)).groupValues[1],
                ).map { it.groupValues[1] }.toList()
                val plain = relayed(JSONObject())
                assertTrue("an ordinary page links its install and logs tabs", tabs(plain).containsAll(listOf("install", "logs")))
                assertFalse("only an embedded page uses the relay", Regex("<body[^>]*data-ui-relay").containsMatchIn(plain))
                val embedded = relayed(JSONObject().put("embed", "v=1;theme=dark;hide=install,logs").put("lang", "de"))
                assertTrue(Regex("<body[^>]*data-ui-relay").containsMatchIn(embedded))
                assertFalse("an embedded page never opens the stub's socket", Regex("<body[^>]*data-ui-socket").containsMatchIn(embedded))
                assertTrue(embedded.contains("data-theme=\"dark\""))
                assertEquals("the named tabs are hidden, the rest stay", tabs(plain) - listOf("install", "logs"), tabs(embedded))
                assertTrue("the explicit language still applies", embedded.contains("<html lang=\"de\""))
            }
        }
    }

    @Test fun grantingUiSubscribeAndUiBundleWithoutRestRetiredLeavesRestOpen() {
        PaneldServerHttpFixture().use { fixture ->
            val relayOnly = hello(PanelAssistantTransportProtocol.CAPABILITY_UI_SUBSCRIBE, PanelAssistantTransportProtocol.CAPABILITY_UI_BUNDLE, "management")
            assertTrue(fixture.config.recordPanelAssistantGrant(relayOnly.capabilities))
            assertFalse(fixture.config.uiRoutesClosed)
            testApplication {
                application { fixture.mount(this) }
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/radio").status)
            }
        }
    }

    @Test fun theGatePersistsAcrossARestartAndALaterHelloWithoutRestRetiredReopensIt() {
        val closing = hello(PanelAssistantTransportProtocol.CAPABILITY_REST_RETIRED, "management")
        val persisted = PaneldServerHttpFixture().use { first ->
            assertTrue(first.config.recordPanelAssistantGrant(closing.capabilities))
            first.values.toMap()
        }
        PaneldServerHttpFixture().use { restarted ->
            restarted.values.putAll(persisted)
            testApplication {
                application { restarted.mount(this) }
                assertEquals(HttpStatusCode.Gone, client.get("/api/v1/radio").status)

                assertTrue(restarted.config.recordPanelAssistantGrant(hello(PanelAssistantTransportProtocol.CAPABILITY_UI_SUBSCRIBE, PanelAssistantTransportProtocol.CAPABILITY_UI_BUNDLE).capabilities))

                assertEquals(HttpStatusCode.OK, client.get("/api/v1/radio").status)
            }
        }
    }

    @Test fun theSameRequestThroughTheSocketAndThroughPanelAssistantReachesTheOneEntryWithItsOwnOrigin() = testApplication {
        val table = ManageTable()
        val origins = CopyOnWriteArrayList<CommandOrigin>()
        table.register("probe.origin", ManageKind.READ) { request ->
            origins += request.origin
            ManageReply("applied", null, JSONObject().put("ok", true))
        }
        val rig = managedRig(table)
        stub { rig.commands }

        val reply = socket { exchange(JSONObject().put("kind", "manage").put("command_id", "r1").put("op", "probe.origin").put("origin", JSONObject().put("user", "x"))) }
        val session = LinkedBlockingQueue<ManageReply>()
        rig.commands.manage("r1", "probe.origin", JSONObject(), CommandOrigin.panelAssistant()) { session.put(it) }

        assertEquals("applied", reply.getString("outcome"))
        assertEquals("applied", session.poll(10, TimeUnit.SECONDS)?.outcome)
        assertEquals(2, origins.size)
        assertTrue("the socket's origin is its peer, not the frame's: ${origins[0].approvalKey}", origins[0].approvalKey.startsWith("peer:"))
        assertEquals(CommandOrigin.PANEL_ASSISTANT, origins[1].approvalKey)
    }

    @Test fun aSessionManageEventForAnyOtherNameReachesTheOneTableWithPanelAssistantsOrigin() {
        PaneldServerHttpFixture().use { fixture ->
            val origins = CopyOnWriteArrayList<CommandOrigin>()
            fixture.manageTable.register("probe.origin", ManageKind.READ) { request ->
                origins += request.origin
                ManageReply("applied", null, JSONArray().put("listed"))
            }
            val noSession = io.panelassistant.android.sensors.HaApiSessionProvider { error("no Home Assistant session in this test") }

            val (served, unknown) = kotlinx.coroutines.runBlocking {
                fixture.server.managementRequest("probe.origin", JSONObject(), {}, noSession) to
                    fixture.server.managementRequest("no.such.operation", JSONObject(), {}, noSession)
            }

            assertEquals("applied", served?.outcome)
            assertEquals("""["listed"]""", served?.result.toString())
            assertEquals(listOf(CommandOrigin.PANEL_ASSISTANT), origins.map { it.approvalKey })
            assertEquals("an unknown name is still the session's own refusal", null, unknown)
        }
    }

    @Test fun aStoppingDispatcherAnswersItsRequestsInFlightAsFailed() {
        val table = ManageTable()
        table.register("probe.hold", ManageKind.READ) { kotlinx.coroutines.awaitCancellation() }
        table.register("probe.held_command", ManageKind.COMMAND) { kotlinx.coroutines.awaitCancellation() }
        val rig = managedRig(table)
        val answers = LinkedBlockingQueue<ManageReply>()
        rig.commands.manage("h1", "probe.hold", JSONObject(), CommandOrigin.peer("192.0.2.4")) { answers.put(it) }
        rig.commands.manage("c1", "probe.held_command", JSONObject(), CommandOrigin.peer("192.0.2.4")) { answers.put(it) }
        Thread.sleep(200)

        rig.close()
        rigs.remove(rig)

        val settled = listOf(answers.poll(5, TimeUnit.SECONDS), answers.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf("failed", "failed"), settled.map { it?.outcome })
    }

    @Test fun aRuntimeReplacementClosesTheSocketSettlesItsRequestAndTheReconnectionServes() = testApplication {
        val table = ManageTable()
        table.register("probe.hold", ManageKind.READ) { kotlinx.coroutines.awaitCancellation() }
        table.register("probe.origin", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("ok", true)) }
        val before = managedRig(table)
        val after = managedRig(table)
        var current = before.commands
        stub { current }
        val ws = createClient { install(ClientWebSockets) }

        val old = ws.webSocketSession("/ui")
        // One answered request first: the socket is then bound to the generation running now.
        assertEquals("applied", old.exchange(JSONObject().put("kind", "manage").put("command_id", "o1").put("op", "probe.origin")).getString("outcome"))
        old.send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "h1").put("op", "probe.hold").toString()))
        Thread.sleep(200)
        // A rename replaces the runtime generation: the service switches to the new dispatcher and stops the old one.
        current = after.commands
        before.close()
        rigs.remove(before)

        // The old socket closes (the page's transport then fails what it was waiting for and reconnects); an
        // answer that still leaves before the close says the request failed.
        val frames = mutableListOf<JSONObject>()
        val closed = withTimeout(5_000) {
            runCatching { while (true) frames += JSONObject((old.incoming.receive() as Frame.Text).readText()) }.exceptionOrNull()
        }
        val reconnected = socket { exchange(JSONObject().put("kind", "manage").put("command_id", "r1").put("op", "probe.origin")) }

        assertTrue("the old socket closed", closed != null)
        assertTrue("nothing it answered claims success: $frames", frames.all { it.optString("outcome") == "failed" })
        assertEquals("applied", reconnected.getString("outcome"))
    }

    @Test fun panelAssistantMayReadButNeverCommandThroughTheFallThrough() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.values["friendly_name"] = "Contract panel"
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val noSession = io.panelassistant.android.sensors.HaApiSessionProvider { error("no Home Assistant session in this test") }
                val before = fixture.config.friendlyName
                val bundle = io.panelassistant.android.config.ConfigBundle.fromValues(mapOf("friendly_name" to "Written by Panel Assistant")).serialize()

                val (imported, read) = kotlinx.coroutines.runBlocking {
                    fixture.server.managementRequest("config.import", JSONObject().put("body", bundle), {}, noSession) to
                        fixture.server.managementRequest("radio", JSONObject(), {}, noSession)
                }

                assertEquals(listOf("refused", "not_commandable"), listOf(imported?.outcome, imported?.code))
                assertEquals("its settings stay as they were", before, fixture.config.friendlyName)
                assertEquals("applied", read?.outcome)
            }
        }
    }

    @Test fun aSocketSubscriptionSendsObservationFramesAndEndsWithTheSocket() = testApplication {
        val table = ManageTable()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        table.register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) }
        val rig = managedRig(table, families = mapOf("probe" to ManageFamily("probe", 100, listOf("probe"))))
        stub { rig.commands }

        val update = socket {
            val applied = exchange(JSONObject().put("kind", "manage").put("command_id", "s1").put("op", "subscribe").put("args", JSONObject().put("family", "probe")))
            assertEquals("s1", applied.getJSONObject("result").getString("subscription"))
            JSONObject((incoming.receive() as Frame.Text).readText())
        }

        assertEquals("s1", update.getString("subscription"))
        val observation = update.getJSONArray("observations").getJSONObject(0)
        assertEquals("probe", observation.getString("channel"))
        assertEquals("known", observation.getString("state"))
        assertEquals(1, observation.getJSONObject("value").getInt("v"))
        val afterClose = reads.get()
        Thread.sleep(400)
        assertTrue("the closed socket's subscription stopped reading", reads.get() <= afterClose + 1)
    }

    @Test fun aFailingReadIsUnavailableOverTheSocketAndAnIdenticalRecoveryIsKnownAgain() = testApplication {
        val table = ManageTable()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        table.register("probe", ManageKind.READ) {
            when (reads.incrementAndGet()) {
                1, 2 -> ManageReply("applied", null, JSONObject().put("v", 1))
                3 -> ManageReply("refused", "hardware_unavailable", JSONObject().put("error", "busy"), status = 503)
                4 -> error("read threw")
                else -> ManageReply("applied", null, JSONObject().put("v", 1))
            }
        }
        val rig = managedRig(table, families = mapOf("probe" to ManageFamily("probe", 100, listOf("probe"))))
        stub { rig.commands }

        val observations = socket {
            val applied = exchange(JSONObject().put("kind", "manage").put("command_id", "s1").put("op", "subscribe").put("args", JSONObject().put("family", "probe")))
            assertEquals("applied", applied.getString("outcome"))
            val seen = mutableListOf<String>()
            withTimeout(5_000) {
                while (seen.size < 3) {
                    val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                    if (!frame.has("subscription")) continue
                    val observation = frame.getJSONArray("observations").getJSONObject(0)
                    seen += observation.getString("state") + " " + observation.get("value")
                }
            }
            // Later identical reads send nothing more.
            val extra = withTimeoutOrNull(500) { incoming.receive() }
            assertNull("an unchanged recovered value sends nothing", extra)
            seen
        }

        assertEquals(listOf("""known {"v":1}""", "unavailable null", """known {"v":1}"""), observations)
    }

    @Test fun twoSubscriptionsOfOneFamilyOnOneSocketBothReceiveUpdates() = testApplication {
        val table = ManageTable()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        table.register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) }
        val rig = managedRig(table, families = mapOf("probe" to ManageFamily("probe", 100, listOf("probe"))))
        stub { rig.commands }

        val seen = socket {
            fun subscribe(id: String) = JSONObject().put("kind", "manage").put("command_id", id).put("op", "subscribe").put("args", JSONObject().put("family", "probe"))
            assertEquals("applied", exchange(subscribe("a")).getString("outcome"))
            assertEquals("applied", exchange(subscribe("b")).getString("outcome"))
            val seen = mutableSetOf<String>()
            withTimeout(5_000) {
                while (seen.size < 2) {
                    val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                    if (frame.has("subscription") && frame.getJSONArray("observations").getJSONObject(0).getJSONObject("value").getInt("v") > 1) {
                        seen += frame.getString("subscription")
                    }
                }
            }
            seen
        }

        assertEquals(setOf("a", "b"), seen)
    }

    @Test fun aLateUnsubscribeOfAnOlderIdLeavesTheNewerSubscriptionRunning() = testApplication {
        val table = ManageTable()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        table.register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) }
        val rig = managedRig(table, families = mapOf("probe" to ManageFamily("probe", 100, listOf("probe"))))
        stub { rig.commands }

        val updates = socket {
            fun subscribe(id: String) = JSONObject().put("kind", "manage").put("command_id", id).put("op", "subscribe").put("args", JSONObject().put("family", "probe"))
            assertEquals("applied", exchange(subscribe("a")).getString("outcome"))
            assertEquals("applied", exchange(subscribe("b")).getString("outcome"))
            // The page re-subscribed (a radio join poll) and only now unsubscribes its old id.
            assertEquals("applied", exchange(JSONObject().put("kind", "manage").put("command_id", "u").put("op", "unsubscribe").put("args", JSONObject().put("subscription", "a"))).getString("outcome"))
            val seen = mutableListOf<String>()
            withTimeout(5_000) {
                while (seen.size < 2) {
                    val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                    if (frame.has("subscription")) seen += frame.getString("subscription")
                }
            }
            seen
        }

        assertEquals(listOf("b", "b"), updates)
    }

    @Test fun twoSocketsFromOneAddressKeepAndCloseTheirOwnSubscriptions() = testApplication {
        val table = ManageTable()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        table.register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) }
        val rig = managedRig(table, families = mapOf("probe" to ManageFamily("probe", 100, listOf("probe"))))
        stub { rig.commands }
        val ws = createClient { install(ClientWebSockets) }
        val first = ws.webSocketSession("/ui")
        val second = ws.webSocketSession("/ui")
        suspend fun DefaultClientWebSocketSession.subscribe(id: String) {
            assertEquals("applied", exchange(JSONObject().put("kind", "manage").put("command_id", id).put("op", "subscribe").put("args", JSONObject().put("family", "probe"))).getString("outcome"))
        }
        suspend fun DefaultClientWebSocketSession.nextUpdate(): JSONObject = withTimeout(5_000) {
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.has("subscription")) return@withTimeout frame
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

        first.subscribe("a")
        first.nextUpdate()
        second.subscribe("b")
        second.nextUpdate()
        first.close()

        // The other tab's subscription is its own: it keeps receiving after the first socket closes.
        assertEquals("b", second.nextUpdate().getString("subscription"))
        assertEquals("b", second.nextUpdate().getString("subscription"))
        second.close()
    }

    @Test fun anUploadedBlobIsTakenOnceAndOnlyWithItsOwnHash() = testApplication {
        val table = ManageTable()
        table.register("probe.upload", ManageKind.COMMAND) { request ->
            val bytes = request.blobs?.take(request.args.getJSONObject("blob"))?.use { it.readBytes() }
            if (bytes == null) ManageReply.refused("invalid_value") else ManageReply("applied", null, JSONObject().put("size", bytes.size))
        }
        val rig = managedRig(table)
        stub { rig.commands }
        val payload = ByteArray(300_000) { (it % 251).toByte() }

        val (first, again, mismatched) = socket {
            suspend fun upload(): String {
                val url = exchange(JSONObject().put("kind", "manage").put("command_id", "u${System.nanoTime()}").put("op", "blob.upload"))
                    .getJSONObject("result").getString("url")
                val put = client.put(url) { setBody(payload) }
                assertEquals(HttpStatusCode.OK, put.status)
                return url
            }
            suspend fun use(id: String, url: String, sha256: String) = exchange(
                JSONObject().put("kind", "manage").put("command_id", id).put("op", "probe.upload").put(
                    "args", JSONObject().put("blob", JSONObject().put("url", url).put("size", payload.size).put("sha256", sha256)),
                ),
            )
            val url = upload()
            val first = use("c1", url, sha256(payload))
            val again = use("c2", url, sha256(payload))
            val mismatched = use("c3", upload(), sha256(payload + 1))
            Triple(first, again, mismatched)
        }

        assertEquals("applied", first.getString("outcome"))
        assertEquals(payload.size, first.getJSONObject("result").getInt("size"))
        assertEquals("refused", again.getString("outcome"))
        assertEquals("refused", mismatched.getString("outcome"))
    }

    @Test fun anUploadIsRefusedBeforeItsBodyWhenItsTokenOrDeclaredSizeDoNotAdmitIt() = testApplication {
        val rig = managedRig(ManageTable())
        stub { rig.commands }
        val tenBytes = ByteArray(10) { it.toByte() }

        val unknown = client.put("/blob/no-such-token") { setBody(ByteArray(1_000_000)) }
        val (tooLarge, fits) = socket {
            fun upload(id: String) = JSONObject().put("kind", "manage").put("command_id", id).put("op", "blob.upload")
                .put("args", JSONObject().put("size", tenBytes.size).put("type", "application/octet-stream"))
            val first = exchange(upload("u1")).getJSONObject("result")
            assertEquals(10, first.getInt("max_size"))
            val second = exchange(upload("u2")).getJSONObject("result").getString("url")
            client.put(first.getString("url")) { setBody(ByteArray(20)) }.status to client.put(second) { setBody(tenBytes) }.status
        }

        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("more than the declared size is refused", HttpStatusCode.PayloadTooLarge, tooLarge)
        assertEquals(HttpStatusCode.OK, fits)
    }

    @Test fun anUploadUpToTheHttpLimitIsWrittenToAFileAndAnOversizeDeclarationIsRefused() = testApplication {
        val table = ManageTable()
        val dir = scratchDirectory("ui-blobs").toFile()
        val seen = java.util.concurrent.atomic.AtomicReference<List<String>>()
        table.register("probe.upload", ManageKind.COMMAND) { request ->
            seen.set(dir.list().orEmpty().toList())
            val bytes = request.blobs?.take(request.args.getJSONObject("blob"))?.use { it.readBytes() }
            if (bytes == null) ManageReply.refused("invalid_value") else ManageReply("applied", null, JSONObject().put("size", bytes.size))
        }
        val rig = managedRig(table)
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                stubRoutes({ rig.commands }, StubBlobs(dir), health = { "health\n" }, diag = { "diag\n" })
            }
        }
        val payload = ByteArray(1_000) { (it % 251).toByte() }
        val apkSized = PaneldServer.MAX_APK_UPLOAD_BYTES

        val (large, oversize, used) = socket {
            fun upload(id: String, size: Long) = JSONObject().put("kind", "manage").put("command_id", id).put("op", "blob.upload")
                .put("args", JSONObject().put("size", size))
            val large = exchange(upload("u1", apkSized))
            val oversize = exchange(upload("u2", apkSized + 1))
            val url = exchange(upload("u3", payload.size.toLong())).getJSONObject("result").getString("url")
            val reference = JSONObject(client.put(url) { setBody(payload) }.bodyAsText())
            val used = exchange(JSONObject().put("kind", "manage").put("command_id", "c1").put("op", "probe.upload").put("args", JSONObject().put("blob", reference)))
            Triple(large, oversize, used)
        }

        assertEquals("an APK-sized upload is admitted at its size", apkSized, large.getJSONObject("result").getLong("max_size"))
        assertEquals("refused", oversize.getString("outcome"))
        assertEquals("invalid_value", oversize.getString("code"))
        assertEquals("applied", used.getString("outcome"))
        assertEquals(payload.size, used.getJSONObject("result").getInt("size"))
        assertEquals("the body waited in a file", 1, seen.get().size)
        assertEquals("the file is gone once read", 0, dir.list().orEmpty().size)
        dir.deleteRecursively()
    }

    @Test fun aBlobAReplyCarriesIsReadOnce() = testApplication {
        val table = ManageTable()
        val bytes = ByteArray(1024) { it.toByte() }
        table.register("probe.download", ManageKind.READ) { request ->
            ManageReply("applied", null, JSONObject().put("blob", requireNotNull(request.blobs).put(bytes, "application/octet-stream")))
        }
        val rig = managedRig(table)
        stub { rig.commands }

        val reference = socket { exchange(JSONObject().put("kind", "manage").put("command_id", "d1").put("op", "probe.download")) }
            .getJSONObject("result").getJSONObject("blob")

        assertEquals(sha256(bytes), reference.getString("sha256"))
        val read = client.get(reference.getString("url"))
        assertEquals(HttpStatusCode.OK, read.status)
        assertArrayEquals(bytes, read.bodyAsBytes())
        assertEquals(HttpStatusCode.NotFound, client.get(reference.getString("url")).status)
    }

    @Test fun theDoorCarriesTheXHeadersARouteSetAsTheHttpTransportReadsThem() = testApplication {
        val table = ManageTable()
        table.register("probe.headers", ManageKind.READ) {
            ManageReply("applied", null, JSONObject(), headers = listOf(
                "X-ha-paneld-Screenshot-Id" to "abc", "Content-Disposition" to "attachment", "X-Frame-Options" to "DENY",
            ))
        }
        val rig = managedRig(table)
        stub { rig.commands }

        val reply = socket { exchange(JSONObject().put("kind", "manage").put("command_id", "x1").put("op", "probe.headers")) }

        assertEquals("abc", reply.getJSONObject("headers").getString("x-ha-paneld-screenshot-id"))
        assertEquals("attachment", reply.getJSONObject("headers").getString("content-disposition"))
        assertFalse(reply.getJSONObject("headers").has("x-frame-options"))
    }

    @Test fun aRouteStatusReadsAsTheOutcomeThePageTransportGivesItOverHttp() = testApplication {
        val table = ManageTable()
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops { get("/status-probe") { call.respondText("{}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.fromValue(call.parameters["status"]!!.toInt())) } }
                }
            }
        }
        startApplication()
        val rig = managedRig(table)
        val expected = mapOf(
            200 to ("applied" to null), 403 to ("refused" to "refused_hardened"), 404 to ("refused" to "unknown_command"),
            405 to ("refused" to "unknown_command"), 410 to ("refused" to "unknown_command"), 409 to ("refused" to "not_commandable"),
            423 to ("refused" to "not_commandable"), 429 to ("refused" to "not_commandable"), 400 to ("refused" to "invalid_value"),
            413 to ("refused" to "invalid_value"), 503 to ("failed" to "hardware_unavailable"), 500 to ("failed" to "failed"),
            302 to ("failed" to "failed"),
        )

        val actual = expected.keys.associateWith { status ->
            val answer = LinkedBlockingQueue<ManageReply>()
            rig.commands.manage("s$status", "status_probe", JSONObject().put("status", status), CommandOrigin.peer("192.0.2.1")) { answer.put(it) }
            requireNotNull(answer.poll(10, TimeUnit.SECONDS)).let { it.outcome to it.code }
        }

        assertEquals(expected, actual)
    }

    @Test fun aRouteAnsweringItsApprovalChallengeSettlesTheRequest() = testApplication {
        val table = ManageTable()
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops { post("/held") { call.respondText("""{"error":"approval-required","approval_id":"a1"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.Accepted) } }
                }
                stubRoutes({ rigs.last().commands }, StubBlobs(), health = { "health\n" }, diag = { "diag\n" })
            }
        }
        startApplication()
        managedRig(table)

        val reply = socket { exchange(JSONObject().put("kind", "manage").put("command_id", "h1").put("op", "held")) }

        // Final, as the same route's HTTP 202 is: the page is never left waiting for an answer that will not come.
        assertEquals("pending_approval", reply.getString("outcome"))
        assertFalse(reply.optBoolean("interim"))
    }

    @Test fun aPageCommandReachesItsHandlerAsTheValuePanelAssistantWouldSend() = testApplication {
        val rig = rig(navbarAcknowledges = true, deviceSecure = false).also { it.start(); rigs += it }
        stub { rig.commands }

        val outcomes = socket {
            listOf("relay1" to true, "screen" to JSONObject().put("on", false), "navbar" to "swipe_reveal").map { (channel, value) ->
                exchange(JSONObject().put("kind", "command").put("command_id", channel).put("channel", channel).put("value", value))
                    .getString("outcome")
            }
        }

        assertEquals(listOf("applied", "applied", "applied"), outcomes)
        assertTrue(rig.sysfs.writes.toString(), "$RELAY_BASE/relay1=1" in rig.sysfs.writes)
        assertTrue("the screen was put to sleep", rig.screen.isIntendedOff())
        assertEquals("Swipe reveal", rig.config.navbarMode)
    }

    @Test fun aHeldChannelCommandAnswersInterimThenItsOutcomeOnceApproved() = testApplication {
        val rig = managedRig(ManageTable()) { it.setSecurityMode(Config.SecurityMode.HARDENED) }
        // A LAN peer's sensitive command is held for approval (this stub is mounted without the door's own
        // hardened check, so the dispatcher's approval rule is what runs).
        stub(peer = "192.168.50.20") { rig.commands }

        val frames = socket {
            send(Frame.Text(JSONObject().put("kind", "command").put("command_id", "c1").put("channel", "reboot").toString()))
            val held = JSONObject((incoming.receive() as Frame.Text).readText())
            assertTrue(LocalApprovalBroker.instance.approve(held.getJSONObject("result").getString("approval_id")))
            held to withTimeout(10_000) { JSONObject((incoming.receive() as Frame.Text).readText()) }
        }

        assertEquals("pending_approval", frames.first.getString("outcome"))
        assertTrue(frames.first.getBoolean("interim"))
        assertEquals("c1", frames.second.getString("command_id"))
        assertEquals("applied", frames.second.getString("outcome"))
    }

    @Test fun turningHardenedModeOnClosesALanSocketAtOnce() {
        val lan = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLinkLocalAddress && io.panelassistant.android.util.isLocalSource(it.hostAddress) }
        assumeTrue("needs a private-range LAN IPv4 address", lan != null)
        val table = ManageTable()
        table.register("probe.origin", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("ok", true)) }
        val rig = managedRig(table)
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = PaneldListener(scope, { port }, { listenerOffLan(rig.config) }) {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                stubRoutes({ rig.commands }, StubBlobs(), health = { "health\n" }, diag = { "diag\n" }, hardened = { rig.config.hardenedSecurityEnabled })
            }
        }
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) { install(ClientWebSockets) }
        try {
            listener.start(closeIngress = {})
            val closed = kotlinx.coroutines.runBlocking {
                http.webSocket("ws://${lan!!.hostAddress}:$port/ui") {
                    assertEquals("applied", exchange(JSONObject().put("kind", "manage").put("command_id", "l1").put("op", "probe.origin")).getString("outcome"))
                    assertTrue(rig.config.setSecurityMode(Config.SecurityMode.HARDENED))
                    withTimeout(3_000) { runCatching { while (true) incoming.receive() }.exceptionOrNull() }
                }
            }
            assertTrue("the open LAN socket was closed", closed != null)
        } finally {
            http.close()
            listener.stop()
            scope.cancel()
        }
    }

    @Test fun aCrossSitePageCannotOpenTheSocket() = testApplication {
        val rig = managedRig(ManageTable())
        stub { rig.commands }
        val ws = createClient { install(ClientWebSockets) }

        var answered: Frame? = null
        runCatching {
            ws.webSocket("/ui", request = { headers.append("Origin", "http://elsewhere.example") }) {
                send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "x").put("op", "health").toString()))
                answered = incoming.receive()
            }
        }

        assertTrue("a cross-site upgrade gets no answer: $answered", answered !is Frame.Text)
    }

    @Test fun inHardenedModeAPanelAssistantSensitiveCommandWaitsForApprovalWhileALoopbackPeerIsExempt() {
        val rig = managedRig(ManageTable()) { it.setSecurityMode(Config.SecurityMode.HARDENED) }

        assertTrue(submit(rig, "reboot", CommandOrigin.panelAssistant()) is PanelAssistantCommandResult.ApprovalPending)
        assertTrue(submit(rig, "reboot", CommandOrigin.peer("192.0.2.7")) is PanelAssistantCommandResult.ApprovalPending)
        assertEquals(PanelAssistantCommandResult.Applied, submit(rig, "reboot", CommandOrigin.peer("127.0.0.1")))
    }

    @Test fun inHardenedModeTheLanKeepsRestUntilTheRoutesCloseButNeverReachesTheDoorOrBlobs() {
        val lan = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLinkLocalAddress && io.panelassistant.android.util.isLocalSource(it.hostAddress) }
        assumeTrue("needs a private-range LAN IPv4 address", lan != null)
        val lanHost = lan!!.hostAddress
        val table = ManageTable()
        table.register("probe.origin", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("ok", true)) }
        val rig = managedRig(table) { it.setSecurityMode(Config.SecurityMode.HARDENED) }
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = PaneldListener(scope, { port }, { listenerOffLan(rig.config) }) {
            paneldRoot({ emptySet() }, { false }, { "/setup" }, uiRoutesClosed = { rig.config.uiRoutesClosed }) {
                stubRoutes({ rig.commands }, StubBlobs(), health = { "health\n" }, diag = { "diag\n" }, hardened = { rig.config.hardenedSecurityEnabled })
                route("/api/v1") { discoveryRoutes({ """{"peers":[]}""" }, { """{"apps":[]}""" }) }
            }
        }
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) { install(ClientWebSockets); expectSuccess = false }
        try {
            listener.start(closeIngress = {})
            kotlinx.coroutines.runBlocking {
                // Routes open: the LAN keeps REST, health and diag as today.
                val peers = http.get("http://$lanHost:$port/api/v1/peers")
                assertEquals(peers.bodyAsText(), HttpStatusCode.OK, peers.status)
                assertEquals(HttpStatusCode.OK, http.get("http://$lanHost:$port/health").status)
                assertEquals(HttpStatusCode.OK, http.get("http://$lanHost:$port/diag").status)
                // ...but never the door or blobs, which loopback still reaches.
                assertEquals(HttpStatusCode.Forbidden, http.get("http://$lanHost:$port/blob/x").status)
                assertEquals(HttpStatusCode.NotFound, http.get("http://127.0.0.1:$port/blob/x").status)
                // An admitted socket would answer this frame at once; a refused upgrade never opens.
                val lanDoor = runCatching {
                    withTimeout(10_000) {
                        http.webSocket("ws://$lanHost:$port/ui") {
                            exchange(JSONObject().put("kind", "manage").put("command_id", "lan").put("op", "probe.origin"))
                        }
                    }
                }
                assertTrue("a LAN peer cannot open the door in hardened mode", lanDoor.isFailure)
                var reply: JSONObject? = null
                http.webSocket("ws://127.0.0.1:$port/ui") {
                    reply = exchange(JSONObject().put("kind", "manage").put("command_id", "h1").put("op", "probe.origin"))
                }
                assertEquals("applied", reply?.getString("outcome"))
            }
            // Every spelling of the door and blob paths that routing resolves is refused to a LAN peer as well.
            for (path in listOf("//ui", "/%75i")) {
                assertEquals(path, 403, rawStatus(lan, port, path, upgrade = true))
            }
            assertEquals(101, rawStatus(InetAddress.getByName("127.0.0.1"), port, "/%75i", upgrade = true))
            assertEquals(403, rawStatus(lan, port, "/%62lob/x", upgrade = false))
            assertTrue("routes open: the LAN connects", connects(lan, port))

            assertTrue(rig.config.recordPanelAssistantGrant(listOf(PanelAssistantTransportProtocol.CAPABILITY_REST_RETIRED)))
            assertTrue(listener.rebind())

            assertFalse("routes closed in hardened mode: no LAN connection", connects(lan, port))
            kotlinx.coroutines.runBlocking {
                assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/health").status)
            }
        } finally {
            http.close()
            listener.stop()
            scope.cancel()
        }
    }

    @Test fun aRebindThatCannotBindKeepsTryingUntilThePortIsFree() {
        val lan = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && !it.isLinkLocalAddress }
        assumeTrue("needs a non-loopback IPv4 address", lan != null)
        val port = ServerSocket(0).use { it.localPort }
        var offLan = true
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = PaneldListener(scope, { port }, { offLan }, onBindFailure = { failures.incrementAndGet() }, retryInitialMs = 100, retryMaxMs = 400) {
            routing { get("/") { call.respondText("ok") } }
        }
        try {
            listener.start(closeIngress = {})
            // Something else holds the port on the LAN address, so moving from loopback to every address fails.
            val holder = ServerSocket().apply { bind(InetSocketAddress(lan, port)) }
            offLan = false
            var rebound: Boolean? = null
            val rebinding = kotlin.concurrent.thread { rebound = listener.rebind() }
            Thread.sleep(1_500)
            assertTrue("the failed binds were reported", failures.get() >= 1)
            assertFalse("unbound while the port is held", listener.bound())
            holder.close()
            rebinding.join(15_000)

            assertEquals(true, rebound)
            assertTrue(listener.bound())
            assertTrue("bound again on every address", connects(lan!!, port))
            assertTrue(connects(InetAddress.getByName("127.0.0.1"), port))
        } finally {
            listener.stop()
            scope.cancel()
        }
    }

    // ---- helpers ----

    private fun ApplicationTestBuilder.stub(peer: String? = null, door: () -> io.panelassistant.android.ManageDoor?) {
        application {
            if (peer != null) intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                context.mutableOriginConnectionPoint.remoteAddress = peer
            }
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                stubRoutes(door, StubBlobs(), health = { "health\n" }, diag = { "diag\n" })
            }
        }
    }

    private suspend fun <T> ApplicationTestBuilder.socket(block: suspend DefaultClientWebSocketSession.() -> T): T {
        val ws = createClient { install(ClientWebSockets) }
        var result: Result<T>? = null
        ws.webSocket("/ui") { result = runCatching { block() } }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.exchange(request: JSONObject): JSONObject {
        send(Frame.Text(request.toString()))
        return withTimeout(10_000) {
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.optString("command_id") == request.getString("command_id") && !frame.optBoolean("interim")) {
                    return@withTimeout frame
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun registeredRoutes(root: RoutingNode): Set<Pair<HttpMethod, String>> {
        return root.getAllRoutes().mapNotNull { leaf ->
            val method = generateSequence(leaf) { it.parent }.firstNotNullOfOrNull { (it.selector as? HttpMethodRouteSelector)?.method }
                ?: return@mapNotNull null
            val segments = generateSequence(leaf) { it.parent }.toList().asReversed().mapNotNull { node ->
                when (val selector = node.selector) {
                    is PathSegmentConstantRouteSelector -> selector.value
                    is PathSegmentParameterRouteSelector -> "{${selector.name}}"
                    is PathSegmentTailcardRouteSelector -> "{${selector.name}...}"
                    else -> null
                }
            }
            method to "/" + segments.joinToString("/")
        }.toSet()
    }

    private suspend fun ApplicationTestBuilder.probe(method: HttpMethod, template: String): HttpStatusCode {
        val path = template.replace(Regex("\\{[^}]*\\}"), "x")
        return client.request(path) { this.method = method }.status
    }

    private fun hello(vararg capabilities: String) = (PanelAssistantTransportProtocol.helloOutcome(
        JSONObject().put("id", 1).put("type", "result").put("success", true).put(
            "result",
            JSONObject().put("protocol", 5).put("session", "opaque").put("capabilities", JSONArray(capabilities.toList()))
                .put("integration", JSONObject().put("version", "1.0.0")),
        ),
        1L,
        offered = PanelAssistantTransportProtocol.CAPABILITIES,
    ) as PanelAssistantHelloOutcome.Accepted).session

    private fun submit(rig: Rig, channel: String, origin: CommandOrigin): PanelAssistantCommandResult {
        val answer = LinkedBlockingQueue<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand(channel, CommandValue.Press, admit = { null }), origin) { answer.put(it) }
        return requireNotNull(answer.poll(20, TimeUnit.SECONDS)) { "$channel answered" }
    }

    /** The status a raw HTTP/1.1 GET of [path] answers, sent byte for byte so no client normalizes the path. */
    private fun rawStatus(address: InetAddress, port: Int, path: String, upgrade: Boolean): Int = Socket().use { socket ->
        socket.connect(InetSocketAddress(address, port), 2_000)
        socket.soTimeout = 5_000
        val headers = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(address.hostAddress).append(':').append(port).append("\r\n")
            if (upgrade) append("Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); flush() }
        socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().split(' ')[1].toInt()
    }

    private fun connects(address: InetAddress, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(address, port), 2_000) }
    }.isSuccess

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
