package io.panelassistant.android

import android.content.SharedPreferences
import com.sun.net.httpserver.HttpServer
import io.panelassistant.android.sensors.DashboardHaApiSessionProvider
import io.panelassistant.android.panelassistant.PanelAssistantHelloIdentity
import io.panelassistant.android.panelassistant.PanelAssistantTransportConnection
import io.panelassistant.android.panelassistant.PanelAssistantTransportDemand
import io.panelassistant.android.panelassistant.PanelAssistantTransportOwner
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real requests prove that an untrusted address sees no credential-bearing request. */
class HaConnectionRoutesTest {
    private val servers = mutableListOf<Endpoint>()
    private val instance = "a".repeat(32)
    private val user = "b".repeat(32)
    private val helloIdentity = PanelAssistantHelloIdentity(did = "0".repeat(64), appVersion = "1.0.0", appVersionCode = 1)
    private fun server() = Endpoint().also { servers += it }
    @After fun close() { servers.forEach { it.close() }; HaCredentialManager.forgetRefusal() }

    @Test fun oldPeerWithoutAdvertisementKeepsConfiguredCredentialRoute() {
        val ha = server()
        val (config, _) = config(ha.url)
        val result = DashboardHaApiSessionProvider(config).resolve(false)
        assertEquals("fresh", result.accessToken)
        assertEquals(ha.url, result.baseUrl)
        assertEquals(listOf("/auth/token"), ha.requests.map { it.path })
    }

    @Test fun dnsFailureRecoversRefreshAndNativeSessionWithConfiguredClientIdentity() {
        val alternate = server()
        val configured = "http://home-assistant.invalid:8123"
        val (config, values) = config(configured)
        val owner = config.haAuthSnapshot().stableOwner()
        assertTrue(learn(config, alternate.url))
        val session = DashboardHaApiSessionProvider(config).resolve(false)
        assertEquals("fresh", session.accessToken)
        assertEquals(alternate.url, session.baseUrl)
        assertEquals(owner, session.owner)
        assertEquals(configured, config.haUrl)
        assertEquals(listOf("/api/panel_assistant/instance", "/auth/token"), alternate.requests.map { it.path })
        assertNoProofCredentials(alternate)
        val form = alternate.requests.single { it.path == "/auth/token" }.body.split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("$configured/", form["client_id"])
        assertEquals("refresh", form["refresh_token"])
        val restarted = Config(preferences(values, true))
        assertEquals(alternate.url, HaConnectionRoutes.current(restarted).url)
        assertEquals(owner, HaConnectionRoutes.current(restarted).owner)
    }

    @Test fun identityOnlyAlternativeDoesNotPreventTryingNextReachableServer() {
        val broken = server().also { it.tokenStatus = 503 }
        val working = server()
        val (config, _) = config("http://unavailable.invalid:8123")
        assertTrue(learn(config, broken.url, working.url))
        assertNull(HaCredentialManager.resolve(config).session)
        val recovered = DashboardHaApiSessionProvider(config).resolve(false)
        assertEquals("fresh", recovered.accessToken)
        assertEquals(working.url, recovered.baseUrl)
        assertEquals(1, broken.requests.count { it.path == "/auth/token" })
        assertEquals(listOf("/api/panel_assistant/instance", "/auth/token"), working.requests.map { it.path })
    }

    @Test fun alternateRefusalRecoversOnConfiguredRouteWithoutAnotherSignIn() {
        val configured = server().also { it.status = 503 }
        val alternate = server().also { it.tokenStatus = 403 }
        val (config, values) = config(configured.url)
        val owner = config.haAuthSnapshot().stableOwner()
        // An already deployed peer may still advertise the cloud route to a local-only user.
        assertTrue(learn(config, alternate.url))
        val unavailable = DashboardHaApiSessionProvider(config).resolve(false)
        assertNull(unavailable.accessToken)
        assertFalse("a fallback refusal must not ask the user to sign in", unavailable.rejected)
        assertNotNull("the existing reconnect lane must retry", unavailable.transientDetail)
        assertEquals("refresh", values["ha_refresh_token"])
        assertEquals("expired", values["ha_token"])
        assertEquals(1, alternate.requests.count { it.path == "/auth/token" })

        configured.status = 200
        // No sign-in, operator retry, credential replacement or new advertisement intervenes.
        val renderer = HaCredentialManager.resolve(config)
        assertEquals("fresh", renderer.session?.accessToken)
        assertFalse(renderer.rejected)
        assertEquals(configured.url, renderer.route?.url)
        val native = DashboardHaApiSessionProvider(config).resolve(false)
        assertEquals("fresh", native.accessToken)
        assertEquals(configured.url, native.baseUrl)
        assertEquals(owner, native.owner)
        assertEquals("refresh", values["ha_refresh_token"])
        assertEquals(configured.url, config.haUrl)
        assertEquals(1, configured.requests.count { it.path == "/auth/token" })
        assertEquals("the rejected fallback yields to the configured route", 1,
            alternate.requests.count { it.path == "/auth/token" })
        assertNoProofCredentials(configured)
        assertNoProofCredentials(alternate)
    }

    @Test fun failedOnlyRouteIsStillTriedOnceItAnswersAgain() {
        val ha = server()
        val (config, _) = config(ha.url)
        assertTrue(learn(config))
        // The native owner's onTransportFailure after a connect that failed (a Core restart refusing it).
        HaConnectionRoutes.failed(config, HaConnectionRoutes.current(config))
        val session = DashboardHaApiSessionProvider(config).resolve(false)
        assertEquals(listOf("/api/panel_assistant/instance", "/auth/token"), ha.requests.map { it.path })
        assertEquals("fresh", session.accessToken)
        assertEquals(ha.url, session.baseUrl)
        assertNoProofCredentials(ha)
    }

    @Test fun aDroppedSessionReconnectsOnTheSameRouteSoTheRendererKeepsItsPage() = runTest {
        val configured = server()
        val peer = server()
        val (config, _) = config(configured.url)
        assertTrue(learn(config, peer.url))
        val before = HaConnectionRoutes.current(config)
        val native = native(config, Socket(answerPings = false), Socket())
        native.owner.replaceDemand(PanelAssistantTransportDemand(before.owner, helloIdentity))
        runCurrent()
        // Home Assistant stops answering the live session: its ping times out and the session drops while the
        // configured address still answers, as on ha-integration #25.
        advanceTimeBy(45_000L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        val after = HaConnectionRoutes.current(config)
        assertEquals(listOf(configured.url, configured.url), native.connects)
        assertEquals("the reconnect keeps the route and its epoch", before, after)
        assertEquals("nothing the renderer embodies changed", emptyList<RendererRebuildCause>(), rebuildCauses(before, after))
        native.owner.close()
    }

    @Test fun aRouteThatStopsAnsweringStillMovesTheRendererToItsPeer() = runTest {
        val configured = server()
        val peer = server()
        val (config, _) = config(configured.url)
        assertTrue(learn(config, peer.url))
        val before = HaConnectionRoutes.current(config)
        val native = native(config, Socket(answerPings = false), Socket())
        native.owner.replaceDemand(PanelAssistantTransportDemand(before.owner, helloIdentity))
        runCurrent()
        configured.status = 503
        advanceTimeBy(45_000L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        val after = HaConnectionRoutes.current(config)
        assertEquals(listOf(configured.url, peer.url), native.connects)
        assertEquals(peer.url, after.url)
        assertEquals(before.epoch + 1, after.epoch)
        assertEquals(listOf(RendererRebuildCause.ROUTE, RendererRebuildCause.ENDPOINT), rebuildCauses(before, after))
        native.owner.close()
    }

    @Test fun aReusedConnectionThatStopsAnsweringDoesNotMoveTheRendererOffAWorkingRoute() {
        val configured = StallingEndpoint(instance).also { servers += it }
        val peer = server()
        val (config, values) = config(configured.url)
        values["ha_token_expiry"] = Long.MAX_VALUE
        assertTrue(learn(config, peer.url))
        val before = HaConnectionRoutes.current(config)
        assertEquals(configured.url, HaCredentialManager.resolve(config).route?.url)
        // The network silently drops the kept-alive connection, as it did every existing socket on
        // ha-integration #25, while a new connection to the same address is answered at once.
        val renderer = HaCredentialManager.resolve(config)
        val after = HaConnectionRoutes.current(config)
        assertEquals(configured.url, renderer.route?.url)
        assertEquals("the route and its epoch hold", before, after)
        assertEquals(emptyList<RendererRebuildCause>(), rebuildCauses(before, after))
        assertEquals("the stalled connection was retried on a fresh one", 2, configured.connections.get())
        assertTrue("the peer was never needed", peer.requests.isEmpty())
    }

    @Test fun reusedAccessTokenStillRequiresInstanceProof() {
        val endpoint = server()
        val (config, values) = config(endpoint.url)
        values["ha_token_expiry"] = Long.MAX_VALUE
        assertTrue(learn(config))
        endpoint.identity = "c".repeat(32)
        assertNull(DashboardHaApiSessionProvider(config).resolve(false).accessToken)
        assertEquals(listOf("/api/panel_assistant/instance"), endpoint.requests.map { it.path })
    }

    @Test fun helloEntryIsKeptForThisAccountOnlyWhileHellosNameIt() {
        val (config, _) = config(server().url)
        assertNull(HaConnectionRoutes.panelAssistantEntryId(config))
        assertTrue(HaConnectionRoutes.learn(config, HaConnectionRoutes.current(config), advertisement(), "01J00000000000000000000CCC"))
        assertEquals("01J00000000000000000000CCC", HaConnectionRoutes.panelAssistantEntryId(config))
        // A hello without the field names no entry, so none is kept.
        assertTrue(learn(config))
        assertNull(HaConnectionRoutes.panelAssistantEntryId(config))
        assertTrue(HaConnectionRoutes.learn(config, HaConnectionRoutes.current(config), advertisement(), "01J00000000000000000000CCC"))
        val spec = requireNotNull(io.panelassistant.android.config.SettingsRegistry.spec("ha_refresh_token"))
        assertTrue(config.commitRaw(spec, "replacement"))
        assertNull("another account never inherits the entry", HaConnectionRoutes.panelAssistantEntryId(config))
    }

    @Test fun rawAccountReplacementForgetsRoutesEvenWhenOriginalValueReturns() {
        val endpoint = server()
        val (config, _) = config(endpoint.url)
        assertTrue(learn(config))
        val spec = requireNotNull(io.panelassistant.android.config.SettingsRegistry.spec("ha_refresh_token"))
        assertTrue(config.commitRaw(spec, "replacement"))
        assertTrue(config.commitRaw(spec, "refresh"))
        assertEquals("", config.haConnectionRecord())
    }

    @Test fun wrongInstanceUnauthorizedAndRedirectNeverReceiveTokens() {
        for (failure in listOf("wrong-instance", "unauthorized", "redirect")) {
            val candidate = server()
            val redirected = server()
            when (failure) {
                "wrong-instance" -> candidate.identity = "c".repeat(32)
                "unauthorized" -> candidate.status = 401
                "redirect" -> { candidate.status = 302; candidate.location = redirected.url + "/api/panel_assistant/instance" }
            }
            val (config, _) = config("http://unavailable.invalid:8123")
            assertTrue(learn(config, candidate.url))
            val result = HaCredentialManager.resolve(config)
            assertNull(failure, result.session)
            assertFalse(failure, result.rejected)
            assertEquals(failure, listOf("/api/panel_assistant/instance"), candidate.requests.map { it.path })
            assertNoProofCredentials(candidate)
            assertTrue(failure, redirected.requests.isEmpty())
        }
    }

    @Test fun sameConfiguredAddressReassignedToAnotherInstanceAlsoGetsNoToken() {
        val configured = server()
        val (config, _) = config(configured.url)
        assertTrue(learn(config))
        configured.identity = "d".repeat(32)
        assertNull(HaCredentialManager.resolve(config).session)
        assertEquals(listOf("/api/panel_assistant/instance"), configured.requests.map { it.path })
        assertNoProofCredentials(configured)
    }

    @Test fun explicitServerReplacementAndReturnCannotResurrectLearnedAuthority() {
        val configured = server()
        val alternate = server()
        val (config, _) = config(configured.url)
        val original = HaConnectionRoutes.current(config)
        assertTrue(learn(config, alternate.url))
        config.setHaConnection("http://replacement.invalid:8123", null)
        config.setHaConnection(configured.url, null)
        assertEquals("", config.haConnectionRecord())
        assertFalse(HaConnectionRoutes.isCurrent(config, original))
        assertFalse(HaConnectionRoutes.learn(config, original, advertisement(alternate.url)))
        assertEquals("fresh", HaCredentialManager.resolve(config).session?.accessToken)
        assertEquals(listOf("/auth/token"), configured.requests.map { it.path })
        assertTrue(alternate.requests.isEmpty())
    }

    @Test fun authenticatedAccountAndInstanceCannotBeReboundByAnotherHello() {
        val ha = server()
        val (config, _) = config(ha.url)
        assertTrue(learn(config))
        val route = HaConnectionRoutes.current(config)
        assertFalse(HaConnectionRoutes.learn(config, route, advertisement().copy(userId = "c".repeat(32))))
        assertFalse(HaConnectionRoutes.learn(config, route, advertisement().copy(instanceId = "d".repeat(32))))
    }

    @Test fun lateRefreshAfterServerReplacementIsNeitherReturnedNorStored() {
        val alternate = server()
        val (config, values) = config("http://original.invalid:8123")
        assertTrue(learn(config, alternate.url))
        alternate.holdToken = CountDownLatch(1)
        var result: DashboardAuth.Result? = null
        val caller = Thread { result = HaCredentialManager.resolve(config) }.apply { start() }
        try {
            assertTrue(alternate.tokenArrived.await(5, TimeUnit.SECONDS))
            config.setHaConnection("http://replacement.invalid:8123", null)
            alternate.holdToken.countDown()
            caller.join(5_000)
            assertFalse(caller.isAlive)
            assertNull(result?.session)
            assertTrue(result?.notAttempted == true)
            assertEquals("expired", values["ha_token"])
        } finally { alternate.holdToken.countDown(); caller.join(5_000) }
    }

    @Test fun preferredRouteNeedsHoldSpacedProofsAndSuccessfulAuthentication() {
        val preferred = server().also { it.status = 503 }
        val alternate = server()
        val (config, _) = config(preferred.url)
        assertTrue(learn(config, alternate.url))
        val fallback = checkNotNull(HaConnectionRoutes.resolve(config))
        assertEquals(alternate.url, fallback.url)
        preferred.status = 200
        preferred.requests.clear()
        assertNull(HaConnectionRoutes.preferredCandidate(config, 0))
        assertNull(HaConnectionRoutes.preferredCandidate(config, 299_999))
        assertTrue(preferred.requests.isEmpty())
        assertNull(HaConnectionRoutes.preferredCandidate(config, 300_000))
        assertNull(HaConnectionRoutes.preferredCandidate(config, 359_999))
        assertNull(HaConnectionRoutes.preferredCandidate(config, 360_000))
        assertEquals(fallback, HaConnectionRoutes.preferredCandidate(config, 420_000))
        assertEquals(3, preferred.requests.size)
        assertFalse(HaConnectionRoutes.preferredResult(config, fallback, authenticated = false, nowMs = 420_000))
        assertEquals(fallback, HaConnectionRoutes.current(config))
        assertNull(HaConnectionRoutes.preferredCandidate(config, 719_999))
        assertNull(HaConnectionRoutes.preferredCandidate(config, 720_000))
        assertNull(HaConnectionRoutes.preferredCandidate(config, 780_000))
        assertEquals(fallback, HaConnectionRoutes.preferredCandidate(config, 840_000))
        assertTrue(HaConnectionRoutes.preferredResult(config, fallback, authenticated = true, nowMs = 840_000))
        assertEquals(preferred.url, HaConnectionRoutes.current(config).url)
        assertFalse(HaConnectionRoutes.isCurrent(config, fallback))
        assertFalse(HaConnectionRoutes.learn(config, fallback, advertisement(alternate.url)))
        assertNoProofCredentials(preferred)
    }

    @Test fun intermittentPreferredReachabilityResetsConsecutiveProofs() {
        val preferred = server().also { it.status = 503 }
        val alternate = server()
        val (config, _) = config(preferred.url)
        assertTrue(learn(config, alternate.url))
        val fallback = checkNotNull(HaConnectionRoutes.resolve(config))
        HaConnectionRoutes.preferredCandidate(config, 0)
        preferred.status = 200
        assertNull(HaConnectionRoutes.preferredCandidate(config, 300_000))
        preferred.status = 503
        assertNull(HaConnectionRoutes.preferredCandidate(config, 360_000))
        preferred.status = 200
        assertNull(HaConnectionRoutes.preferredCandidate(config, 420_000))
        assertNull(HaConnectionRoutes.preferredCandidate(config, 480_000))
        assertEquals(fallback, HaConnectionRoutes.preferredCandidate(config, 540_000))
        assertEquals(alternate.url, config.haEffectiveUrl)
    }

    @Test fun provedRendererRouteCannotUpgradeOrInheritAnotherAdvertisedOrigin() {
        val route = "http://ha.example:8123"
        assertTrue(dashboardNavigationAllowed(route, "$route/dashboard", allowHttpsUpgrade = false))
        assertFalse(dashboardNavigationAllowed(route, "https://ha.example:8123/dashboard", allowHttpsUpgrade = false))
        assertFalse(dashboardNavigationAllowed(route, "http://other.example:8123/dashboard", allowHttpsUpgrade = false))
        assertEquals(setOf(route), dashboardDocumentStartOrigins(route, allowHttpsUpgrade = false))
    }

    @Test fun learnedRevokedAccountStillRequiresSignInRatherThanRetryingConnectionProof() = kotlinx.coroutines.test.runTest {
        val endpoint = server().also { it.tokenStatus = 400 }
        val (config, _) = config(endpoint.url)
        assertTrue(learn(config))
        assertEquals(DashboardV2ProbeResult.AuthenticationFailed, DashboardV2CompatibilityProbe(config).check())
        assertEquals(1, endpoint.requests.count { it.path == "/auth/token" })
    }

    @Test fun advertisedRoutesRejectNonOriginsAndUnsupportedSchemes() {
        for (url in listOf("ftp://ha.example", "https://user:password@ha.example", "https://ha.example/path",
                "https://ha.example?token=x", "https://ha.example#fragment", "https://ha.example:0")) {
            val raw = org.json.JSONObject().put("instance_id", instance).put("user_id", user)
                .put("urls", org.json.JSONArray(listOf(url)))
            assertNull(url, HaConnectionAdvertisement.parse(raw))
        }
        assertEquals("https://ha.example", HaConnectionRoutes.normalize("https://HA.example:443/"))
    }

    /** What the dashboard compares when the route epoch moves (DashboardActivity.handleNewIntent); the entity
     *  filter's signature carries the same effective address, so it follows [RendererRebuildCause.ENDPOINT]. */
    private fun rebuildCauses(before: HaConnectionRoute, after: HaConnectionRoute) = rendererRebuildCauses(
        routeChanged = after.epoch != before.epoch,
        signedIn = false,
        embodied = RendererConfiguration(before.url, null, null, null, null),
        wanted = RendererConfiguration(after.url, null, null, null, null),
    )

    private class Native(val owner: PanelAssistantTransportOwner, val connects: List<String>)

    /** The native session owner wired to the routes as ServiceHaLinks wires it. */
    private fun TestScope.native(config: Config, vararg sockets: Socket): Native {
        val queue = ArrayDeque(sockets.toList())
        val connects = CopyOnWriteArrayList<String>()
        val owner = PanelAssistantTransportOwner(
            scope = backgroundScope,
            auth = DashboardHaApiSessionProvider(config),
            connector = { baseUrl, _ -> connects += baseUrl; queue.removeFirstOrNull() ?: throw IOException("unreachable") },
            workerDispatcher = StandardTestDispatcher(testScheduler),
            monotonicMillis = { testScheduler.currentTime },
            jitter = { bound -> bound },
            log = {},
            observeForHello = { true },
            onConnection = { session, accepted ->
                val route = session.route
                route != null && HaConnectionRoutes.isCurrent(config, route) &&
                    (accepted.connection?.let { HaConnectionRoutes.learn(config, route, it, accepted.entryId) } ?: true)
            },
            onTransportFailure = { route -> HaConnectionRoutes.failed(config, route) },
        )
        return Native(owner, connects)
    }

    /** Panel Assistant's side of one native socket: accepts the hello and, unless told not to, answers pings. */
    private class Socket(private val answerPings: Boolean = true) : PanelAssistantTransportConnection {
        private val inbound = Channel<String>(Channel.UNLIMITED)

        override suspend fun send(text: String) {
            val frame = JSONObject(text)
            val id = frame.getLong("id")
            when (frame.getString("type")) {
                "panel_assistant/hello" -> inbound.trySend(JSONObject().put("id", id).put("type", "result")
                    .put("success", true).put("result", JSONObject().put("protocol", 5).put("session", "opaque")
                        .put("capabilities", JSONArray()).put("integration", JSONObject().put("version", "1.0.0"))
                        .put("channels", JSONObject().put("accepted", 0).put("unknown", JSONArray()))).toString())
                "ping" -> if (answerPings) inbound.trySend(JSONObject().put("id", id).put("type", "pong").toString())
            }
        }

        override suspend fun receive(timeoutMs: Long): String? = select {
            inbound.onReceive { it }
            onTimeout(timeoutMs) { null }
        }

        override suspend fun close() { inbound.close() }
    }

    private fun advertisement(vararg urls: String) = HaConnectionAdvertisement(instance, user, urls.toList())
    private fun learn(config: Config, vararg urls: String) =
        HaConnectionRoutes.learn(config, HaConnectionRoutes.current(config), advertisement(*urls))
    private fun assertNoProofCredentials(endpoint: Endpoint) {
        endpoint.requests.filter { it.path == "/api/panel_assistant/instance" }.forEach {
            assertNull(it.authorization)
            assertTrue(it.cookie.isNullOrBlank())
            assertEquals("", it.body)
        }
    }
    private fun config(url: String): Pair<Config, MutableMap<String, Any>> {
        val values = ConcurrentHashMap<String, Any>(mapOf(
            "ha_url" to url, "ha_token" to "expired", "ha_refresh_token" to "refresh",
            "ha_token_expiry" to 0L, "ha_client_id" to "",
        ))
        return Config(preferences(values, true)) to values
    }

    private data class Request(val path: String, val body: String, val authorization: String?, val cookie: String?)
    /** Answers one instance proof per connection and keeps the connection open, then never answers on it again. */
    private class StallingEndpoint(private val answered: String) : Endpoint() {
        val connections = java.util.concurrent.atomic.AtomicInteger()
        private val socket = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val accepted = CopyOnWriteArrayList<java.net.Socket>()
        override val url = "http://127.0.0.1:${socket.localPort}"

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    accepted += client
                    Thread {
                        runCatching {
                            val reader = client.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            val body = """{"instance_id":"$answered"}"""
                            client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n$body").toByteArray())
                            client.getOutputStream().flush()
                            // A second request on this connection is read and never answered.
                            reader.readLine()
                        }
                    }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() {
            runCatching { socket.close() }
            accepted.forEach { runCatching { it.close() } }
            super.close()
        }
    }

    private open class Endpoint : AutoCloseable {
        @Volatile var status = 200
        @Volatile var tokenStatus = 200
        @Volatile var identity = "a".repeat(32)
        @Volatile var location = ""
        @Volatile var holdToken = CountDownLatch(0)
        val tokenArrived = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Request>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).apply {
            this.executor = this@Endpoint.executor
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests += Request(path, exchange.requestBody.bufferedReader().readText(),
                    exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Cookie"))
                val token = path == "/auth/token"
                if (token) { tokenArrived.countDown(); holdToken.await(10, TimeUnit.SECONDS) }
                val body = if (token) """{"access_token":"fresh","expires_in":1800}"""
                    else """{"instance_id":"$identity"}"""
                if (location.isNotBlank()) exchange.responseHeaders.set("Location", location)
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(if (token) tokenStatus else status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        open val url = "http://127.0.0.1:${server.address.port}"
        override fun close() { holdToken.countDown(); server.stop(0); executor.shutdownNow() }
    }

    private fun preferences(values: MutableMap<String, Any>, commits: Boolean): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> HashMap(values)
            "getString" -> values[args!![0]] as? String ?: args[1]
            "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
            "getInt" -> values[args!![0]] as? Int ?: args[1]
            "getLong" -> values[args!![0]] as? Long ?: args[1]
            "getFloat" -> values[args!![0]] as? Float ?: args[1]
            "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
            "contains" -> values.containsKey(args!![0])
            "edit" -> editor(values, commits)
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            "toString" -> "LoopbackPreferences"
            else -> error("unexpected SharedPreferences call: ${method.name}")
        }
    } as SharedPreferences

    private fun editor(values: MutableMap<String, Any>, commits: Boolean): SharedPreferences.Editor {
        val writes = LinkedHashMap<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when {
                method.name.startsWith("put") -> editor.also { writes[args!![0] as String] = args[1] }
                method.name == "remove" -> editor.also { writes[args!![0] as String] = null }
                method.name == "commit" || method.name == "apply" -> {
                    if (commits) {
                        writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    }
                    if (method.name == "commit") commits else null
                }
                method.name == "toString" -> "LoopbackEditor"
                else -> error("unexpected Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
