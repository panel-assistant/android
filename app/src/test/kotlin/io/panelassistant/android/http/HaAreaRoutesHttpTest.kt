package io.panelassistant.android.http

import io.panelassistant.android.dashboard.EntityLearningManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.HaConnectionAdvertisement
import io.panelassistant.android.HaConnectionRoutes
import io.panelassistant.android.sensors.HaApiSessionProvider
import io.panelassistant.android.setHaConnection
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HaAreaRoutesHttpTest {
    @Test fun `an unconfigured panel reports an unqueried registry through the production mount`() = withArea { fixture, _ ->
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.get("/api/v1/config/ha-area")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                val expected = JSONObject(
                    "{\"areas\":[],\"device\":{\"found\":false,\"area_id\":\"\",\"area_name\":\"\"}," +
                        "\"admin\":false,\"queried\":false,\"requested\":\"\",\"ha_username\":\"\"}",
                )
                val actual = JSONObject(response.bodyAsText())
                assertEquals(expected.toString(), actual.toString())
            }
        }
    }

    @Test fun `an unavailable HA registry never clears a requested local area`() = withArea { fixture, _ ->
        fixture.config.commitHaArea("Chosen area", userOverride = true)
        fixture.config.setHaConnection("http://127.0.0.1:9", "")
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.get("/api/v1/config/ha-area")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = JSONObject(response.bodyAsText())
                assertEquals(false, body.getBoolean("queried"))
                assertEquals("Chosen area", body.getString("requested"))
                assertEquals(false, body.getJSONObject("device").getBoolean("found"))
            }
        }
        assertEquals("Chosen area", fixture.config.haArea)
        assertEquals(true, fixture.config.haAreaUserOverride)
    }

    @Test fun `the area endpoint retains production host admission`() = withArea { fixture, _ ->
        testApplication {
            application { fixture.mount(this) }
            val response = client.get("/api/v1/config/ha-area") {
                header(HttpHeaders.Host, "elsewhere.example")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("host not allowed\n", response.bodyAsText())
        }
    }

    @Test fun `Panel Assistant's request adopts the area and refreshes Auto sleep once`() = withArea { fixture, refreshed ->
        val deviceArea = AtomicReference("")
        val ha = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                // The learned connection proves its instance before a token leaves.
                routeGet("/api/panel_assistant/instance") { call.respondText("""{"instance_id":"${"c".repeat(32)}"}""") }
                webSocket("/api/websocket") {
                    send(Frame.Text("""{"type":"auth_required"}"""))
                    incoming.receive()
                    send(Frame.Text("""{"type":"auth_ok"}"""))
                    for (frame in incoming) {
                        val command = JSONObject((frame as Frame.Text).readText())
                        val result: Any = when (command.getString("type")) {
                            "auth/current_user" -> JSONObject().put("is_admin", false)
                            "config/area_registry/list" -> JSONArray()
                                .put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen"))
                            "config/device_registry/list" -> JSONArray().put(
                                JSONObject().put("id", "panel-device")
                                    .put("identifiers", JSONArray().put(JSONArray().put("panel_assistant").put("entry-test")))
                                    .put("area_id", deviceArea.get()),
                            )
                            else -> error("unexpected HA command: ${command.getString("type")}")
                        }
                        send(Frame.Text(JSONObject().put("id", command.getInt("id")).put("type", "result")
                            .put("success", true).put("result", result).toString()))
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = runBlocking { ha.engine.resolvedConnectors() }.single().port
            fixture.config.setHaConnection("http://127.0.0.1:$port", "test-token")
            assertTrue(HaConnectionRoutes.learn(
                fixture.config, HaConnectionRoutes.current(fixture.config),
                HaConnectionAdvertisement("c".repeat(32), "d".repeat(32), emptyList()), "entry-test",
            ))
            assertEquals("", fixture.config.haArea)
            val noSession = HaApiSessionProvider { error("no Home Assistant session in this test") }

            deviceArea.set("kitchen") // Someone moves the panel's device in Home Assistant; Panel Assistant asks.
            val reply = runBlocking { fixture.server.managementRequest("ha_area.refresh", JSONObject(), {}, noSession) }

            assertEquals("applied", reply?.outcome)
            assertEquals("Kitchen", fixture.config.haArea)
            assertEquals(listOf("Kitchen"), refreshed)
            assertEquals("Home Assistant's value is adopted, never recorded as a person's choice", false, fixture.config.haAreaUserOverride)
            val repeated = runBlocking { fixture.server.managementRequest("ha_area.refresh", JSONObject(), {}, noSession) }
            assertEquals("applied", repeated?.outcome)
            assertEquals(listOf("Kitchen"), refreshed)
        } finally {
            ha.stop(100, 1_000)
        }
    }

    @Test fun `a request to read the area again that cannot reach Home Assistant answers failed`() = withArea { fixture, refreshed ->
        fixture.config.setHaConnection("http://127.0.0.1:9", "test-token")
        val noSession = HaApiSessionProvider { error("no Home Assistant session in this test") }

        val reply = runBlocking { fixture.server.managementRequest("ha_area.refresh", JSONObject(), {}, noSession) }

        assertEquals("failed", reply?.outcome)
        assertEquals("", fixture.config.haArea)
        assertTrue(refreshed.isEmpty())
    }

    private fun withArea(block: (PaneldServerHttpFixture, List<String>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            PaneldServerHttpFixture().use { fixture ->
                // Match the existing mount fixture: these paths need Config, not Android catalog storage.
                val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
                    isAccessible = true
                    get(null) as Unsafe
                }
                val learning = unsafe.allocateInstance(EntityLearningManager::class.java) as EntityLearningManager
                EntityLearningManager::class.java.getDeclaredField("config").apply { isAccessible = true }
                    .set(learning, fixture.config)
                fun field(name: String, value: Any) {
                    PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }
                        .set(fixture.server, value)
                }
                field("entityLearning", learning)
                field("scope", scope)
                val mutationLock = Any()
                field("directConfigMutationLock", mutationLock)
                val refreshed = mutableListOf<String>()
                field("haArea", HaAreaRuntime(
                    fixture.config, scope, mutationLock, onAreaAdopted = { refreshed += fixture.config.haArea },
                ) { false })
                block(fixture, refreshed)
            }
        } finally {
            scope.cancel()
        }
    }
}
