package io.panelassistant.android.http

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.server.request.receiveStream
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageFamily
import io.panelassistant.android.ManageKind
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageTable
import io.panelassistant.android.panelassistant.PanelAssistantBlobs
import io.panelassistant.android.panelassistant.PanelAssistantManagement
import io.panelassistant.android.panelassistant.PanelAssistantManagementRequests
import io.panelassistant.android.panelassistant.PanelAssistantSession
import io.panelassistant.android.panelassistant.PanelAssistantSessionEvent
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.sensors.HaApiSessionProvider
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sidebar relay (protocol section 20.2, Epic E slice 6): `manage` and `command` events Panel Assistant relays
 * with a browser's `origin`, parsed by the session's own parser and answered by the session's management requests,
 * against a stand-in for Panel Assistant's blob view on a real loopback socket.
 */
internal class SidebarRelayTest : io.panelassistant.android.testsupport.PanelRuntimeRig() {
    private lateinit var homeAssistant: HttpServer
    private val received = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val served = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val downloads = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    @Before fun startHomeAssistant() {
        homeAssistant = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(PanelAssistantBlobs.PATH) { exchange ->
                val token = exchange.requestURI.path.removePrefix(PanelAssistantBlobs.PATH)
                when (exchange.requestMethod) {
                    "PUT" -> {
                        val bytes = exchange.requestBody.readBytes()
                        received[token] = bytes
                        val receipt = """{"size":${bytes.size},"sha256":"${sha256(bytes)}"}""".toByteArray()
                        exchange.sendResponseHeaders(200, receipt.size.toLong())
                        exchange.responseBody.write(receipt)
                    }
                    "GET" -> {
                        downloads.getOrPut(token) { AtomicInteger() }.incrementAndGet()
                        // One use, as Panel Assistant's blob view claims a download once and discards it.
                        val bytes = served.remove(token)
                        if (bytes == null) exchange.sendResponseHeaders(404, -1) else {
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        }
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
                exchange.close()
            }
            start()
        }
    }

    @After fun stop() {
        homeAssistant.stop(0)
        LocalApprovalBroker.instance.clear()
    }

    private val auth = HaApiSessionProvider { HaApiSession("http://127.0.0.1:${homeAssistant.address.port}", "panel-token") }

    /** One session's management requests, fed events as Home Assistant sends them and drained as the session loop does. */
    private inner class Session(
        door: () -> io.panelassistant.android.ManageDoor?,
        serve: suspend (String, JSONObject, () -> Unit) -> io.panelassistant.android.panelassistant.PanelAssistantManageReply? = { _, _, _ -> null },
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = PanelAssistantManagementRequests(
            scope,
            PanelAssistantManagement.of({ error("no snapshot") }, { _, _ -> error("no settings") }, door, PanelAssistantBlobs(auth), serve),
            PanelAssistantSession(5, TOKEN, listOf("management", "ui_subscribe", "ui_bundle"), "1.0.0"),
            { System.nanoTime() / 1_000_000 },
        )
        val sent = CopyOnWriteArrayList<JSONObject>()
        private var id = 2L

        fun deliver(event: JSONObject) {
            val frame = JSONObject().put("id", 1).put("type", "event").put("event", event.put("session", TOKEN))
            when (val parsed = PanelAssistantTransportProtocol.sessionEvent(frame, 1L)) {
                is PanelAssistantSessionEvent.Manage -> requests.onRequest(parsed)
                is PanelAssistantSessionEvent.Command -> requests.onRelayed(parsed)
                else -> error("not a request: $parsed")
            }
        }

        fun drain() {
            while (true) sent += JSONObject(requests.next(id++) ?: return)
        }

        fun await(what: String, predicate: (JSONObject) -> Boolean): JSONObject {
            val until = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < until) {
                drain()
                sent.firstOrNull(predicate)?.let { return it }
                Thread.sleep(10)
            }
            throw AssertionError("no $what in $sent")
        }

        fun answer(commandId: String) = await("final answer to $commandId") {
            it.optString("type") == PanelAssistantTransportProtocol.COMMAND_COMMAND_RESULT &&
                it.optString("command_id") == commandId && !it.optBoolean("interim")
        }

        override fun close() {
            requests.close()
            scope.cancel()
        }
    }

    // A button press carries no value, as the page and Panel Assistant send it.
    private fun command(id: String, channel: String, value: Any? = null) =
        JSONObject().put("kind", "command").put("command_id", id).put("channel", channel)
            .apply { if (value != null) put("value", value) }
            .put("origin", JSONObject().put("user", USER).put("connection", "relay-1"))

    private fun manage(id: String, op: String, args: JSONObject = JSONObject(), relayed: Boolean = true) =
        JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args)
            .apply { if (relayed) put("origin", JSONObject().put("user", USER).put("connection", "relay-1")) }

    @Test fun aRelayedBrowserSignInUsesThePanelsOwnAddressAndKeepsCallbackOwnership() {
        PaneldServerHttpFixture().use { fixture ->
            val panelOrigin = requireNotNull(io.panelassistant.android.util.LocalAdminEndpoint.externalUrl(
                io.panelassistant.android.util.localIpv4(), io.panelassistant.android.util.localIpv6(), fixture.config.httpPort,
            )).removeSuffix("/")
            val exchanges = CopyOnWriteArrayList<String>()
            PaneldServer::class.java.getDeclaredField("haOAuth").apply { isAccessible = true }.set(fixture.server, HaOAuthRuntime(
                fixture.config,
                // Source-text reason: exercise the callback's packaged translations through the runtime catalogue reader.
                { io.panelassistant.android.i18n.CatalogueLoader { name -> java.io.File("src/main/assets", name).readText() } },
                { _, code, client ->
                    exchanges += "$code|$client"
                    io.panelassistant.android.util.HaLink.AuthorizationCodeExchange.Rejected
                },
                AutoBrightnessHttpApi.UNAVAILABLE,
                { _, _, _ -> error("Rejected exchange must not commit") },
                { error("Rejected exchange must not evaluate setup") },
            ))
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val dispatcher = rig(manageTable = fixture.manageTable)
                try {
                    Session({ dispatcher.commands }).use { session ->
                        fun start(id: String): Url {
                            session.deliver(manage(id, "ha.oauth.start", JSONObject()
                                .put("ha_url", "http://ha.example:8123")
                                .put("return_surface", "configure").put("ui_locale", "en")
                                .put("Host", "attacker.example:8888").put("panel_origin", "http://attacker.example:8888")))
                            val answer = session.answer(id)
                            assertEquals(answer.toString(), "applied", answer.getString("outcome"))
                            return Url(answer.getJSONObject("result").getString("authorization_url"))
                        }
                        val superseded = start("oauth-old")
                        val current = start("oauth-current")
                        assertEquals("$panelOrigin/", current.parameters["client_id"])
                        assertEquals("$panelOrigin$HA_OAUTH_CALLBACK_PATH", current.parameters["redirect_uri"])
                        val panelHost = panelOrigin.removePrefix("http://")
                        suspend fun callback(url: Url, host: String = panelHost) = client.get(
                            "$HA_OAUTH_CALLBACK_PATH?state=${url.parameters["state"]}&code=fixture-code",
                        ) { header(HttpHeaders.Host, host) }
                        assertTrue(callback(superseded).bodyAsText().contains("expired or was already used"))
                        assertTrue(callback(current, "wrong-panel.local:8888").bodyAsText().contains("different panel address"))
                        assertTrue(callback(current).bodyAsText().contains("expired or was already used"))
                        assertTrue(exchanges.isEmpty())
                        val usable = start("oauth-usable")
                        assertTrue(callback(usable).bodyAsText().contains("did not accept this sign-in"))
                        assertTrue(callback(usable).bodyAsText().contains("expired or was already used"))
                        assertEquals(listOf("fixture-code|$panelOrigin/"), exchanges.toList())
                        val direct = client.submitForm("/api/v1/ha/oauth/start", Parameters.build {
                            append("ha_url", "http://ha.example:8123")
                        }) { header(HttpHeaders.Host, "direct-panel.local:9999") }
                        assertEquals("http://direct-panel.local:9999/", Url(JSONObject(direct.bodyAsText()).getString("authorization_url")).parameters["client_id"])
                        val invalidHost = client.submitForm("/api/v1/ha/oauth/start", Parameters.build {
                            append("ha_url", "http://ha.example:8123")
                        }) { header(HttpHeaders.Host, "user@attacker.example:8888") }
                        assertTrue(invalidHost.status.value in 400..499)
                    }
                } finally { dispatcher.close() }
            }
        }
    }

    @Test fun aRelayedCommandRunsAsItsUserWhileTheSameEventWithoutAnOriginIsRefused() {
        PaneldServerHttpFixture().use { fixture ->
            val origins = CopyOnWriteArrayList<CommandOrigin>()
            fixture.manageTable.register("probe.write", ManageKind.COMMAND) { request ->
                origins += request.origin
                ManageReply("applied", null, JSONObject().put("written", true))
            }
            Session({ fixture.door }) { op, args, onPending -> fixture.server.managementRequest(op, args, onPending, auth) }.use { session ->
                session.deliver(manage("w1", "probe.write"))
                session.deliver(manage("w2", "probe.write", relayed = false))

                val relayed = session.answer("w1")
                assertEquals(listOf(TOKEN, "applied"), listOf(relayed.getString("session"), relayed.getString("outcome")))
                assertTrue(relayed.getJSONObject("result").getBoolean("written"))
                val own = session.answer("w2")
                assertEquals(listOf("refused", "not_commandable"), listOf(own.getString("outcome"), own.getString("code")))
                assertEquals(listOf("user:$USER"), origins.map { it.approvalKey })
            }
        }
    }

    @Test fun aRelayedLongOperationWithATwoMinuteDeadlineRuns() {
        // The page gives backup, restore, config import and APK install two minutes (deadline_ms 120000).
        PaneldServerHttpFixture().use { fixture ->
            fixture.manageTable.register("probe.long", ManageKind.COMMAND) { ManageReply("applied", null, JSONObject()) }
            Session({ fixture.door }) { op, args, onPending -> fixture.server.managementRequest(op, args, onPending, auth) }.use { session ->
                session.deliver(manage("l1", "probe.long").put("deadline_ms", 120_000))
                assertEquals("applied", session.answer("l1").getString("outcome"))
            }
        }
    }

    @Test fun aMalformedOriginNeverRunsAndNeverAsPanelAssistant() {
        val ran = AtomicInteger()
        val table = ManageTable().apply { register("probe.write", ManageKind.COMMAND) { ran.incrementAndGet(); ManageReply("applied", null) } }
        Session({ tableDoor(table) }) { _, _, _ -> ran.incrementAndGet(); null }.use { session ->
            session.deliver(manage("m1", "probe.write").put("origin", JSONObject().put("user", "x y")))
            val answer = session.answer("m1")
            assertEquals(listOf("refused", "invalid_format"), listOf(answer.getString("outcome"), answer.getString("code")))
            assertEquals(0, ran.get())
        }
    }

    @Test fun aRelayConnectionEndsWithItsDispatcherGenerationAndItsNextRequestReachesTheLiveOne() {
        val generations = mutableListOf<String>()
        val table = ManageTable()
        fun generation(name: String) = io.panelassistant.android.ManageOperations(table, { LocalApprovalBroker.instance }).also {
            generations += name
        }
        table.register("probe.read", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("generation", generations.last())) }
        val first = generation("first")
        var door = tableDoor(table, operations = first)
        Session({ door }).use { session ->
            session.deliver(manage("r1", "probe.read"))
            assertEquals("first", session.answer("r1").getJSONObject("result").getString("generation"))

            // A runtime replacement stops the first dispatcher and starts another.
            first.stop()
            door = tableDoor(table, operations = generation("second"))
            session.deliver(manage("r2", "probe.read"))
            val answer = session.answer("r2")
            assertEquals("applied", answer.getString("outcome"))
            assertEquals("second", answer.getJSONObject("result").getString("generation"))
        }
    }

    @Test fun inHardenedModeARelayedExemptOperationRunsAndANonExemptOneAnswersInterimPendingApproval() = testApplication {
        val table = ManageTable()
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops {
                        for ((path, operation) in listOf("exempt" to SensitiveOperation.DISPLAY_CONFIGURATION, "guarded" to SensitiveOperation.BACKUP_EXPORT)) {
                            post("/probe/$path") {
                                if (!authorizeSensitiveRequest(call, true, operation, path, path, LocalApprovalBroker.instance)) return@post
                                call.respondText("""{"ok":true}""", ContentType.Application.Json)
                            }
                        }
                    }
                }
            }
        }
        startApplication()
        Session({ tableDoor(table, approvalPollMs = 20) }).use { session ->
            session.deliver(manage("e1", "probe.exempt"))
            assertEquals("applied", session.answer("e1").getString("outcome"))

            session.deliver(manage("g1", "probe.guarded"))
            val interim = session.await("interim answer") { it.optString("command_id") == "g1" }
            assertEquals(listOf("pending_approval", "true"), listOf(interim.getString("outcome"), interim.get("interim").toString()))
            val pending = LocalApprovalBroker.instance.pending().single()
            assertEquals(listOf(SensitiveOperation.BACKUP_EXPORT, "user:$USER"), listOf(pending.operation, pending.peer))
            assertTrue(LocalApprovalBroker.instance.approve(pending.id))
            assertEquals("applied", session.answer("g1").getString("outcome"))
        }
    }

    @Test fun inHardenedModeARelayedReloadCommandRunsAndARelayedRebootAnswersInterimPendingApprovalBoundToItsUser() {
        val rig = rig(deviceSecure = false, configure = { assertTrue(it.setSecurityMode(io.panelassistant.android.Config.SecurityMode.HARDENED)) })
        try {
            Session({ rig.commands }).use { session ->
                session.deliver(command("c1", "reload"))
                assertEquals("applied", session.answer("c1").getString("outcome"))

                session.deliver(command("c2", "reboot"))
                val interim = session.await("interim answer") { it.optString("command_id") == "c2" }
                assertEquals(listOf("pending_approval", "true"), listOf(interim.getString("outcome"), interim.get("interim").toString()))
                val pending = LocalApprovalBroker.instance.pending().single()
                assertEquals(listOf(SensitiveOperation.DEVICE_REBOOT, "user:$USER"), listOf(pending.operation, pending.peer))
            }
        } finally {
            rig.close()
        }
    }

    @Test fun aRelayedSubscriptionSendsUiUpdatesAndStopsOnUnsubscribeAndWithTheSession() {
        val reads = AtomicInteger()
        val table = ManageTable().apply { register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) } }
        val door = tableDoor(table, families = mapOf("probe" to ManageFamily("probe", 50, listOf("probe"))))
        fun settled(): Int {
            val before = reads.get()
            Thread.sleep(300)
            return reads.get() - before
        }
        Session({ door }).use { session ->
            session.deliver(manage("s1", "subscribe", JSONObject().put("family", "probe")))
            assertEquals("s1", session.answer("s1").getJSONObject("result").getString("subscription"))
            val update = session.await("update") { it.optString("type") == PanelAssistantTransportProtocol.COMMAND_UI_UPDATE }
            assertEquals(listOf(TOKEN, "s1"), listOf(update.getString("session"), update.getString("subscription")))
            val observation = update.getJSONArray("observations").getJSONObject(0)
            assertEquals(listOf("probe", "known"), listOf(observation.getString("channel"), observation.getString("state")))
            assertTrue(observation.getJSONObject("value").getInt("v") >= 1)

            session.deliver(manage("u1", "unsubscribe", JSONObject().put("subscription", "s1")))
            assertEquals("applied", session.answer("u1").getString("outcome"))
            assertTrue("unsubscribed: reads stop", settled() <= 1)

            session.deliver(manage("s2", "subscribe", JSONObject().put("family", "probe")))
            session.await("second update") { it.optString("subscription") == "s2" && it.has("observations") }
            assertTrue("subscribed again: reads run", settled() > 1)
            session.close()
            assertTrue("the session ended: reads stop", settled() <= 1)
        }
    }

    // A page the sidebar opens subscribes before the page it replaced has unsubscribed, so its subscription joins
    // the live one; its first value must not wait for the family's next read (a floor of up to five seconds).
    @Test fun aSubscriptionJoiningALiveOneGetsTheCurrentValueAtOnce() {
        val value = AtomicInteger(1)
        val table = ManageTable().apply { register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", value.get())) } }
        val door = tableDoor(table, families = mapOf("probe" to ManageFamily("probe", 60_000, listOf("probe"))))
        fun updates(session: Session, subscription: String) = session.sent.filter { it.optString("subscription") == subscription && it.has("observations") }
        Session({ door }).use { session ->
            session.deliver(manage("s1", "subscribe", JSONObject().put("family", "probe")))
            session.await("first page's value") { it.optString("subscription") == "s1" && it.has("observations") }
            value.set(2)

            val joined = System.nanoTime()
            session.deliver(manage("s2", "subscribe", JSONObject().put("family", "probe")))
            val update = session.await("next page's value") { it.optString("subscription") == "s2" && it.has("observations") }
            assertTrue("the joining page waited for the next read", System.nanoTime() - joined < 5_000_000_000L)
            val observation = update.getJSONArray("observations").getJSONObject(0)
            assertEquals(listOf("known", 2), listOf(observation.getString("state"), observation.getJSONObject("value").getInt("v")))
            Thread.sleep(300)
            session.drain()
            assertTrue(
                "neither page is told the family went unavailable",
                (updates(session, "s1") + updates(session, "s2")).all { it.getJSONArray("observations").getJSONObject(0).getString("state") == "known" },
            )
        }
    }

    // The joining page restarts the family's read; a read it interrupts is not an unavailable family.
    @Test fun aSubscriptionJoiningDuringARunningReadNeverReportsTheFamilyUnavailable() {
        val reads = AtomicInteger()
        val firstRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        val table = ManageTable().apply {
            register("probe", ManageKind.READ) {
                if (reads.incrementAndGet() == 1) firstRead.await()
                ManageReply("applied", null, JSONObject().put("v", reads.get()))
            }
        }
        val door = tableDoor(table, families = mapOf("probe" to ManageFamily("probe", 60_000, listOf("probe"))))
        Session({ door }).use { session ->
            session.deliver(manage("s1", "subscribe", JSONObject().put("family", "probe")))
            session.answer("s1")
            val until = System.nanoTime() + 5_000_000_000L
            while (reads.get() == 0 && System.nanoTime() < until) Thread.sleep(10)
            session.deliver(manage("s2", "subscribe", JSONObject().put("family", "probe")))
            session.await("joining page's value") { it.optString("subscription") == "s2" && it.has("observations") }
            Thread.sleep(300)
            session.drain()
            val states = session.sent.filter { it.has("observations") }.map { it.getJSONArray("observations").getJSONObject(0).getString("state") }
            assertTrue("no page is told the family went unavailable: $states", states.isNotEmpty() && states.all { it == "known" })
        }
    }

    // Pages replaced quickly join one shared subscription three deep before any read restarts; once every page
    // has left, no reader may be left polling the panel for nobody.
    @Test fun subscriptionsJoiningInQuickSuccessionLeaveNoReaderOnceAllHaveLeft() {
        val reads = AtomicInteger()
        val table = ManageTable().apply { register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) } }
        val door = tableDoor(table, families = mapOf("probe" to ManageFamily("probe", 20, listOf("probe"))))
        Session({ door }).use { session ->
            repeat(20) { round ->
                val ids = (1..3).map { "s$round-$it" }
                ids.forEach { session.deliver(manage(it, "subscribe", JSONObject().put("family", "probe"))) }
                ids.forEach { session.answer(it) }
                ids.forEach { session.deliver(manage("u$it", "unsubscribe", JSONObject().put("subscription", it))) }
                ids.forEach { session.answer("u$it") }
                Thread.sleep(100)
                val settled = reads.get()
                Thread.sleep(200)
                assertEquals("round $round: every page left, yet the panel is still being read", settled, reads.get())
            }
        }
    }

    @Test fun aLargeRelayedResultMovesAsABlobThroughItsTokenAndAnUploadedBodyIsFetchedAndChecked() = testApplication {
        val table = ManageTable()
        val large = "x".repeat(300 * 1024)
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops {
                        get("/probe/large") { call.respondText(JSONObject().put("text", large).toString(), ContentType.Application.Json) }
                        get("/probe/small") { call.respondText("""{"small":true}""", ContentType.Application.Json) }
                        get("/probe/text") { call.respondText(large, ContentType.Text.Plain) }
                        post("/probe/echo") {
                            val body = call.receiveStream().use { it.readBytes() }
                            call.respondText(JSONObject().put("size", body.size).put("text", String(body)).toString(), ContentType.Application.Json)
                        }
                    }
                }
            }
        }
        startApplication()
        Session({ tableDoor(table) }).use { session ->
            session.deliver(manage("l1", "probe.large", JSONObject().put("blob_token", "relay-token-000001")))
            session.deliver(manage("l2", "probe.small", JSONObject().put("blob_token", "relay-token-000002")))

            val blob = session.answer("l1").getJSONObject("result").getJSONObject("blob")
            val uploaded = requireNotNull(received["relay-token-000001"]) { "the answer went up through its token" }
            assertEquals(large, JSONObject(String(uploaded)).getString("text"))
            assertEquals(listOf(PanelAssistantBlobs.PATH + "relay-token-000001", uploaded.size.toLong(), sha256(uploaded)), listOf(blob.getString("url"), blob.getLong("size"), blob.getString("sha256")))
            assertTrue("a small answer rides the frame", session.answer("l2").getJSONObject("result").getBoolean("small"))
            assertFalse("an unused token is left for Panel Assistant to discard", received.containsKey("relay-token-000002"))
            // A large text answer is typed as Panel Assistant serves it, a bare media type, so the page reads it as text.
            session.deliver(manage("t1", "probe.text", JSONObject().put("blob_token", "relay-token-000003")))
            assertEquals("text/plain", session.answer("t1").getJSONObject("result").getJSONObject("blob").getString("type"))
            assertEquals(large, String(requireNotNull(received["relay-token-000003"])))

            val body = "uploaded by the reader".toByteArray()
            fun reference(sha: String) = JSONObject().put("url", PanelAssistantBlobs.PATH + "reader-token-00001").put("size", body.size)
                .put("sha256", sha).put("type", "text/plain")
            served["reader-token-00001"] = body
            session.deliver(manage("p1", "probe.echo", JSONObject().put("blob", reference(sha256(body)))))
            assertEquals("uploaded by the reader", session.answer("p1").getJSONObject("result").getString("text"))
            served["reader-token-00001"] = body
            session.deliver(manage("p2", "probe.echo", JSONObject().put("blob", reference("0".repeat(64)))))
            assertFalse("a mismatched body is never used", session.answer("p2").getString("outcome") == "applied")
        }
    }

    /** Send [op] with a body the reader uploaded, from a relayed user on a Hardened panel, approve it on the panel, and give the final answer. */
    private fun relayedApprovedBodyRequest(fixture: PaneldServerHttpFixture, op: String, body: ByteArray, token: String, repeated: Boolean = false): JSONObject {
        served[token] = body
        val blob = JSONObject().put("url", PanelAssistantBlobs.PATH + token).put("size", body.size)
            .put("sha256", sha256(body)).put("type", "application/octet-stream")
        fun kept() = java.io.File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("relay") && f.name.endsWith(".blob") }.orEmpty().toSet()
        val before = kept()
        return Session({ fixture.door }).use { session ->
            session.deliver(manage("a1", op, JSONObject().put("blob", blob).put("blob_token", "answer-token-00001")))
            val interim = session.await("interim answer") { it.optString("command_id") == "a1" }
            assertEquals(interim.toString(), "pending_approval", interim.getString("outcome"))
            // Panel Assistant sending the same request again while it waits joins the held run.
            if (repeated) session.deliver(manage("a1", op, JSONObject().put("blob", blob).put("blob_token", "answer-token-00002")))
            val pending = LocalApprovalBroker.instance.pending().single()
            assertEquals("user:$USER", pending.peer)
            assertTrue(LocalApprovalBroker.instance.approve(pending.id))
            session.answer("a1").also { assertEquals("the body is deleted with the final answer", emptySet<java.io.File>(), kept() - before) }
        }
    }

    @Test fun aRelayedRestoreOnAHardenedPanelRunsAfterItsApprovalHavingReadItsOneUseBlobOnce() {
        withFullReadServer { config, fixture ->
            assertTrue(config.setSecurityMode(io.panelassistant.android.Config.SecurityMode.HARDENED))
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val backup = """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Restored from the sidebar"}}"""
                val reply = relayedApprovedBodyRequest(fixture, "restore", backup.toByteArray(), "reader-token-restore1", repeated = true)

                assertEquals(reply.toString(), "applied", reply.getString("outcome"))
                assertEquals(1, downloads["reader-token-restore1"]?.get())
                val until = System.nanoTime() + 15_000_000_000L
                while (config.friendlyName != "Restored from the sidebar" && System.nanoTime() < until) Thread.sleep(20)
                assertEquals("Restored from the sidebar", config.friendlyName)
            }
        }
    }

    // Seen on a Hardened panel: the restore waited for an approval only Settings listed, so nobody saw it and it expired.
    @Test fun aRelayedRestoreOnAHardenedPanelRaisesItsApprovalOnThePanelsScreenOnceAndApprovingItThereRunsIt() {
        val screen = CopyOnWriteArrayList<String>()
        val broker = LocalApprovalBroker.instance
        io.panelassistant.android.ApprovalPrompt(
            broker,
            wake = { screen += "wake" },
            launch = { screen += "prompt: " + broker.pending().joinToString { "${it.operation} for ${it.peer}" }; true },
            post = { it() },
        ).use {
            withFullReadServer { config, fixture ->
                assertTrue(config.setSecurityMode(io.panelassistant.android.Config.SecurityMode.HARDENED))
                testApplication {
                    application { fixture.mount(this) }
                    startApplication()
                    val backup = """{"kind":"ha-paneld-backup","schema":15,"config":{"friendly_name":"Approved on the panel"}}"""
                    val reply = relayedApprovedBodyRequest(fixture, "restore", backup.toByteArray(), "reader-token-restore2", repeated = true)

                    assertEquals(reply.toString(), "applied", reply.getString("outcome"))
                    val until = System.nanoTime() + 15_000_000_000L
                    while (config.friendlyName != "Approved on the panel" && System.nanoTime() < until) Thread.sleep(20)
                    assertEquals("Approved on the panel", config.friendlyName)
                }
            }
        }
        assertEquals(
            "the panel wakes and shows the restore's approval once, though the request arrived twice",
            listOf("wake", "prompt: ${SensitiveOperation.BACKUP_RESTORE} for user:$USER"),
            screen,
        )
    }

    @Test fun aRelayedConfigImportOverTheFrameLimitOnAHardenedPanelAppliesAfterItsApprovalHavingReadItsOneUseBlobOnce() {
        withFullReadServer { config, fixture ->
            assertTrue(config.setSecurityMode(io.panelassistant.android.Config.SecurityMode.HARDENED))
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                // Over the 256 KiB a frame carries, so the sidebar sends it as a blob.
                val bundle = """{"kind":"ha-paneld-config","schema":1,"values":{"friendly_name":"Imported from the sidebar"}}""" + " ".repeat(300 * 1024)
                val reply = relayedApprovedBodyRequest(fixture, "config.import", bundle.toByteArray(), "reader-token-import01")

                assertEquals(reply.toString(), "applied", reply.getString("outcome"))
                assertEquals(1, downloads["reader-token-import01"]?.get())
                assertEquals("Imported from the sidebar", config.friendlyName)
            }
        }
    }

    @Test fun theBundleUploadsOneZipOfExactlyTheFilesTheStubServesKeyedByTheHealthBuild() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val reply = requireNotNull(kotlinx.coroutines.runBlocking {
                    fixture.server.managementRequest(
                        PanelAssistantTransportProtocol.MANAGE_BUNDLE, JSONObject().put("build", "old").put("blob_token", "bundle-token-0001"), {}, auth,
                    )
                })
                assertEquals("applied", reply.outcome)
                val result = reply.result as JSONObject
                val health = client.get("/health").bodyAsText()
                val build = requireNotNull(Regex("build=(\\S+)").find(health)) { health }.groupValues[1]
                assertEquals("keyed by the health line's build token", build, result.getString("build"))
                val zip = requireNotNull(received["bundle-token-0001"])
                assertEquals(listOf(zip.size.toLong(), sha256(zip)), result.getJSONObject("blob").let { listOf(it.getLong("size"), it.getString("sha256")) })

                val entries = linkedMapOf<String, ByteArray>()
                ZipInputStream(zip.inputStream()).use { input ->
                    while (true) entries[(input.nextEntry ?: break).name] = input.readBytes()
                }
                // Every file a rendered page or a bundled script asks for is in the bundle, so the sidebar frame
                // needs nothing from the panel but its session.
                val wanted = sortedSetOf<String>()
                val reference = Regex("(?:src|href)=\"(assets/[^\"?#]+|info\\.js|info\\.css|icon\\.svg|favicon\\.svg)\"")
                for (page in listOf("/", "/configure", "/setup", "/profiles", "/install", "/logs", "/entities")) {
                    reference.findAll(client.get(page).bodyAsText()).forEach { wanted += it.groupValues[1] }
                }
                val scripted = Regex("\"(assets/[A-Za-z0-9_./-]+\\.(?:js|css|svg))\"")
                entries.filterKeys { it.endsWith(".js") }.values.forEach { bytes -> scripted.findAll(String(bytes)).forEach { wanted += it.groupValues[1] } }
                assertTrue("pages reference their files: $wanted", wanted.size > 10)
                assertTrue("pages ask for ${wanted - entries.keys}", entries.keys.containsAll(wanted))
                assertTrue("a page script is bundled", "assets/transport.js" in entries.keys)
                // Only page files: a slow panel must be able to upload it (2.9 MB zipped with everything, 2026-10-15).
                assertEquals(emptyList<String>(), entries.keys.filter { it.substringAfterLast('.') !in setOf("js", "css", "svg") })
                assertTrue("bundle is ${zip.size} bytes", zip.size < 1024 * 1024)
                // Each entry is the file the stub serves at that path, relative to the page base.
                for ((path, bytes) in entries) {
                    val response = client.get("/$path")
                    assertEquals(path, HttpStatusCode.OK, response.status)
                    if (path.substringAfterLast('.') in setOf("js", "css", "svg", "json", "html")) {
                        assertTrue(path, bytes.contentEquals(response.bodyAsBytes()))
                    }
                }
            }
        }
    }

    @Test fun theBundleOfAnInstalledApkIsExactlyItsAssetEntries() {
        val apk = java.io.File.createTempFile("panel", ".apk").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
            for ((name, text) in listOf("classes.dex" to "dex", "res/raw/a.txt" to "res", "assets/" to "", "assets/info.js" to "js", "assets/i18n/en.json" to "{}")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        val assets = UiBundleAssets.ofApk { apk.path }
        assertEquals(listOf("i18n/en.json", "info.js"), assets.names().sorted())
        assertEquals("{}", String(assets.bytes("i18n/en.json")))
        assertTrue(runCatching { assets.bytes("classes.dex") }.isFailure)
    }

    private companion object {
        const val TOKEN = "relay-session"
        const val USER = "0123456789abcdef0123456789abcdef"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
