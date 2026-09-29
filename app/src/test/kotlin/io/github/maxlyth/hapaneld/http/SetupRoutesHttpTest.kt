package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.GuidedSetupPresence
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.platform.ActivityRef
import io.github.maxlyth.hapaneld.platform.SystemEnv
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class SetupRoutesHttpTest {
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

    private fun withSetup(block: (PaneldServerHttpFixture) -> Unit) {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setDashboardPackage("com.example.dashboard")
            fixture.config.setFriendlyName("Contract panel")
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
            field("mqttState", { "" })
            field("lastHaDiscovery", DiscoveryResult())
            val context = PaneldServer::class.java.getDeclaredField("appContext").run {
                isAccessible = true
                get(fixture.server) as android.content.Context
            }
            field("setupState", SetupState(
                fixture.config, system, learning, profile, context, { "" }, { 0 },
                { DiscoveryResult() }, { false }, { false },
                { system.resolveDashboard(fixture.config.dashboardPackage) == SystemController.BUILTIN_DASHBOARD },
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
                io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator(
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
