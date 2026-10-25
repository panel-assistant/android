package io.panelassistant.android.http

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.DiscoveryResult
import io.panelassistant.android.GuidedSetupPresence
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.dashboard.EntityLearningManager
import io.panelassistant.android.dashboardEntityLearningEnabled
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.panelassistant.android.setHaConnection
import io.panelassistant.android.setHaSetupHandover
import io.panelassistant.android.setupEntityFilterAnswered
import io.panelassistant.android.setupEverCompleted
import io.panelassistant.android.setupHomeDashboardChosen
import io.panelassistant.android.setupIdentityConfirmed
import io.panelassistant.android.setupRenderAttestation
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class SetupRoutesHttpTest {
    @Test fun `setup reports a session estimate arriving during the scan and its actual completed total`() = withSetup { fixture ->
        val learning = PaneldServer::class.java.getDeclaredField("entityLearning").run {
            isAccessible = true
            get(fixture.server) as EntityLearningManager
        }
        // Keep the fixture runnable on the prior app so this endpoint test proves the missing behavior.
        fun field(name: String, value: Any) = EntityLearningManager::class.java.declaredFields
            .firstOrNull { it.name == name }?.run {
                isAccessible = true
                set(learning, value)
            }
        var expected: Int? = null
        field("expectedEntityTotal", { expected })
        val generation = java.util.concurrent.atomic.AtomicLong(0)
        field("effectGeneration", generation)
        field("scanCounting", true)
        field("scanCountedRows", 1_234)
        testApplication {
            application { fixture.mount(this) }
            suspend fun state() = JSONObject(client.get("/api/v1/setup").bodyAsText())
                .getJSONObject("entity_filter").getJSONObject("learning")
            assertEquals(-1, state().optInt("expected_total", -1))
            expected = 2_000
            assertEquals(2_000, state().optInt("expected_total", -1))
            field("scanCountedRows", 2_100)
            assertEquals("the measured count may exceed its approximate estimate", 2_100, state().getInt("scanned"))
            field("scanCounting", false)
            assertEquals(2_100, state().getInt("catalog"))
            assertEquals(-1, state().getInt("scanned"))
            field("scanCountedRows", 0)
            assertEquals("an empty completed response is an actual zero", 0, state().getInt("catalog"))
            generation.incrementAndGet()
            assertEquals("a completed count belongs to its original target", -1, state().getInt("catalog"))
        }
    }

    @Test fun `a configured builtin panel waiting for render proof needs no setup input`() = withSetup { fixture ->
        fixture.config.setDashboardPackage(SystemController.BUILTIN_DASHBOARD)
        fixture.config.setHaConnection("http://ha.example", "fixture-token")
        fixture.config.setupIdentityConfirmed = true
        fixture.config.setupHomeDashboardChosen = true
        fixture.config.setupEntityFilterAnswered = true
        fixture.config.setupEverCompleted = true
        BuiltinDashboard.resetRendererPerf()
        try {
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/setup")
                assertEquals(HttpStatusCode.OK, response.status)
                val state = JSONObject(response.bodyAsText())
                assertFalse(state.getBoolean("complete"))
                assertEquals("render_proof", state.getString("next"))
                assertFalse(state.getBoolean("needs_user"))
            }
        } finally {
            BuiltinDashboard.resetRendererPerf()
        }
    }

    @Test fun `a configured panel with a missing credential still needs setup input`() = withSetup { fixture ->
        fixture.config.setDashboardPackage(SystemController.BUILTIN_DASHBOARD)
        fixture.config.setHaConnection("http://ha.example", "")
        fixture.config.setupIdentityConfirmed = true
        fixture.config.setupHomeDashboardChosen = true
        fixture.config.setupEntityFilterAnswered = true
        fixture.config.setupEverCompleted = true
        testApplication {
            application { fixture.mount(this) }
            val response = client.get("/api/v1/setup")
            assertEquals(HttpStatusCode.OK, response.status)
            val state = JSONObject(response.bodyAsText())
            assertFalse(state.getBoolean("complete"))
            assertEquals("ha_credentials", state.getString("next"))
            assertTrue(state.getBoolean("needs_user"))
        }
    }

    @Test fun `only the wizard heartbeat marks setup as actively walked`() = withSetup { fixture ->
        try {
            testApplication {
                application { fixture.mount(this) }
                GuidedSetupPresence.noteHeartbeat(Long.MIN_VALUE)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/setup").status)
                assertFalse(GuidedSetupPresence.activelyWalked(0L))
                val response = client.get("/api/v1/setup") {
                    header(SETUP_PRESENCE_HEADER, SETUP_PRESENCE_ACTIVE)
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(GuidedSetupPresence.activelyWalked(0L))
            }
        } finally {
            GuidedSetupPresence.noteHeartbeat(Long.MIN_VALUE)
        }
    }

    @Test fun `over the socket a visible wizard's setup reads keep it actively walked`() = withSetup { fixture ->
        try {
            testApplication {
                application { fixture.mount(this) }
                val ws = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }
                ws.webSocket("/ui") {
                    suspend fun answer(id: String): JSONObject {
                        while (true) {
                            val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                            if (frame.optString("command_id") == id) return frame
                        }
                    }
                    // A one-off read carries presence as an argument, where HTTP carries the header.
                    GuidedSetupPresence.noteHeartbeat(Long.MIN_VALUE)
                    send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "r1").put("op", "setup")
                        .put("args", JSONObject().put("presence", "active")).toString()))
                    assertEquals("applied", answer("r1").getString("outcome"))
                    assertTrue("a one-off read with presence", GuidedSetupPresence.activelyWalked(0L))

                    // The wizard's subscription renews it on every read of the family, not only the first.
                    send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "s1").put("op", "subscribe")
                        .put("args", JSONObject().put("family", "setup").put("presence", "active")).toString()))
                    assertEquals("applied", answer("s1").getString("outcome"))
                    // Its first read has landed once the first update arrives; then clear the heartbeat and
                    // wait for the family's next read (its floor is two seconds) to renew it.
                    withTimeout(5_000) { while (!JSONObject((incoming.receive() as Frame.Text).readText()).has("subscription")) Unit }
                    GuidedSetupPresence.noteHeartbeat(Long.MIN_VALUE)
                    val deadline = System.nanoTime() + 6_000_000_000L
                    while (!GuidedSetupPresence.activelyWalked(0L) && System.nanoTime() < deadline) Thread.sleep(100)
                    assertTrue("a later read of the subscription renewed it", GuidedSetupPresence.activelyWalked(0L))
                }
            }
        } finally {
            GuidedSetupPresence.noteHeartbeat(Long.MIN_VALUE)
        }
    }

    @Test fun `setup polling exposes stored choices without answering them`() = withSetup { fixture ->
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.get("/api/v1/setup")
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers["Cache-Control"])
                val state = JSONObject(response.bodyAsText())
                assertEquals("contract-panel", state.getJSONObject("panel").getString("id"))
                assertEquals(false, state.getJSONObject("home_dashboard").getBoolean("answered"))
                assertEquals(false, state.getJSONObject("entity_filter").getBoolean("answered"))
                assertTrue(state.getJSONObject("entity_filter").getBoolean("counting"))
                assertTrue(state.getJSONObject("handover").getBoolean("supported"))
                assertFalse(state.has("config_hash"))
            }
        }
        assertFalse(fixture.config.setupIdentityConfirmed)
        assertFalse(fixture.config.setupHomeDashboardChosen)
        assertFalse(fixture.config.setupEntityFilterAnswered)
    }

    @Test fun `identity acceptance persists without changing the panel name`() = withSetup { fixture ->
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.post("/api/v1/setup/identity")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("{\"ok\":true}", response.bodyAsText())
                assertTrue(fixture.config.setupIdentityConfirmed)
                assertEquals("contract-panel", fixture.config.panelId)
            }
        }
    }

    @Test fun `accepting the default dashboard records an answer without enabling the filter`() = withSetup { fixture ->
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.post("/api/v1/setup/home-dashboard")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("{\"ok\":true}", response.bodyAsText())
                assertTrue(fixture.config.setupHomeDashboardChosen)
                assertEquals("", fixture.config.homeDashboard)
                assertFalse(fixture.config.dashboardEntityLearningEnabled)
            }
        }
    }

    @Test fun `declining filtering records the answer for a foreign renderer without launching it`() = withSetup { fixture ->
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.post("/api/v1/setup/entity-filter")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("{\"ok\":true}", response.bodyAsText())
                assertTrue(fixture.config.setupEntityFilterAnswered)
                assertFalse(fixture.config.dashboardEntityLearningEnabled)
            }
        }
    }

    @Test fun `render attestation completes the journey only for its current endpoint`() = withSetup { fixture ->
        fixture.config.setHaConnection("http://initial.example", "fixture-token")
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.post("/api/v1/setup/attest")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("{\"ok\":true}", response.bodyAsText())
                assertTrue(fixture.config.setupEverCompleted)
                assertTrue(fixture.config.setupRenderAttestation.isNotBlank())
                assertTrue(JSONObject(client.get("/api/v1/setup").bodyAsText()).getBoolean("complete"))
            }
            fixture.config.setHaConnection("http://changed.example", "fixture-token")
            val state = JSONObject(client.get("/api/v1/setup").bodyAsText())
            assertFalse(state.getBoolean("complete"))
            assertTrue(state.getBoolean("repair"))
            val steps = state.getJSONArray("steps")
            val proof = (0 until steps.length()).map { steps.getJSONObject(it) }
                .single { it.getString("stage") == "render_proof" }
            assertEquals("blocked", proof.getString("status"))
        }
    }

    @Test fun `a panel Panel Assistant handed over goes from identity straight to the render proof`() = withSetup { fixture ->
        fixture.config.setHaSetupHandover(true)
        fixture.config.setHaConnection("http://ha.example", "fixture-token")
        testApplication {
            application { fixture.mount(this) }
            client.post("/api/v1/setup/identity")
            val state = JSONObject(client.get("/api/v1/setup").bodyAsText())
            assertEquals("render_proof", state.getString("next"))
            client.post("/api/v1/setup/attest")
            assertTrue(JSONObject(client.get("/api/v1/setup").bodyAsText()).getBoolean("complete"))
        }
    }

    private fun withSetup(block: (PaneldServerHttpFixture) -> Unit) {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setDashboardPackage("com.example.dashboard")
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
            val system = SystemController(object : SystemEnv {
                override val ownPackage = "io.github.maxlyth.hapaneld"
                override fun isInstalled(pkg: String) = pkg == "com.example.dashboard"
                override fun launchComponent(pkg: String): String? = error("Unexpected launch")
                override fun homeActivities(): List<ActivityRef> = emptyList()
                override fun defaultHome(): ActivityRef? = null
                override fun directStart(component: String) = error("Unexpected launch")
            })
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null) as Unsafe
            }
            val learning = unsafe.allocateInstance(EntityLearningManager::class.java) as EntityLearningManager
            EntityLearningManager::class.java.getDeclaredField("config").apply { isAccessible = true }
                .set(learning, fixture.config)
            val profile = Proxy.newProxyInstance(
                DeviceProfile::class.java.classLoader,
                arrayOf(DeviceProfile::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getSoc", "getRecommendedWebView" -> null
                    else -> error("Unexpected profile read: ${method.name}")
                }
            } as DeviceProfile
            fun field(name: String, value: Any) {
                PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .set(fixture.server, value)
            }
            field("system", system)
            field("entityLearning", learning)
            field("profile", profile)
            field("lastHaDiscovery", DiscoveryResult())
            val context = PaneldServer::class.java.getDeclaredField("appContext").run {
                isAccessible = true
                get(fixture.server) as android.content.Context
            }
            field("setupState", SetupState(
                fixture.config, system, learning, profile, context, { 0 },
                { DiscoveryResult() }, { false },
                { system.resolveDashboard(fixture.config.dashboardPackage) == SystemController.BUILTIN_DASHBOARD },
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
                io.panelassistant.android.util.RendererPreparationCoordinator(
                    builtinPackage = "builtin",
                    state = { error("Unexpected renderer preparation") },
                    borrow = { null },
                    persist = { error("Unexpected renderer persistence") },
                ),
            ))
            block(fixture)
        }
    }
}
