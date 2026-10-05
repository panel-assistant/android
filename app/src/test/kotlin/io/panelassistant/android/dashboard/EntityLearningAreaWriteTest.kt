package io.panelassistant.android.dashboard

import android.content.SharedPreferences
import io.panelassistant.android.Config
import io.panelassistant.android.http.HaAreaProtocol
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

class EntityLearningAreaWriteTest {
    @Test fun blankRepairPreservesAnInterveningRealAreaAndClearsTheStaleLiteral() = runBlocking {
        val currentArea = AtomicReference("bad-area")
        val updates = ConcurrentLinkedQueue<JSONObject>()
        val commands = ConcurrentLinkedQueue<String>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/api/websocket") {
                    send(Frame.Text("""{"type":"auth_required"}"""))
                    assertEquals("auth", JSONObject((incoming.receive() as Frame.Text).readText()).getString("type"))
                    send(Frame.Text("""{"type":"auth_ok"}"""))
                    for (frame in incoming) {
                        val command = JSONObject((frame as Frame.Text).readText())
                        commands.add(command.getString("type"))
                        val result: Any = when (command.getString("type")) {
                            "auth/current_user" -> JSONObject().put("is_admin", true)
                            "config/auth/list" -> JSONArray()
                            "config/area_registry/list" -> JSONArray()
                                .put(JSONObject().put("area_id", "bad-area").put("name", "null"))
                                .put(JSONObject().put("area_id", "study").put("name", "Study"))
                            "config/device_registry/list" -> JSONArray().put(
                                JSONObject().put("id", "panel-device")
                                    .put("identifiers", JSONArray().put(JSONArray().put("mqtt").put("ha-paneld-uid-test")))
                                    .put("area_id", currentArea.get()),
                            )
                            "config/device_registry/update" -> {
                                updates.add(command)
                                currentArea.set(if (command.isNull("area_id")) "" else command.getString("area_id"))
                                JSONObject().put("id", "panel-device")
                            }
                            else -> error("unexpected HA command: ${command.getString("type")}")
                        }
                        send(Frame.Text(JSONObject().put("id", command.getInt("id"))
                            .put("type", "result").put("success", true).put("result", result).toString()))
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val prefs = Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "contains" -> false
                    "getLong" -> args!![1]
                    "getString" -> when (args?.get(0)) {
                        "ha_url" -> "http://127.0.0.1:$port"
                        "ha_token" -> "test-token"
                        else -> ""
                    }
                    "getAll" -> mapOf("ha_url" to "http://127.0.0.1:$port", "ha_token" to "test-token")
                    else -> error("unexpected preference read: ${method.name}")
                }
            } as SharedPreferences
            val config = Config(prefs)
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
                .get(null) as Unsafe
            val manager = unsafe.allocateInstance(EntityLearningManager::class.java) as EntityLearningManager
            EntityLearningManager::class.java.getDeclaredField("config").apply { isAccessible = true }
                .set(manager, config)

            val stale = manager.haAreaCatalog("test", "panel")
            assertTrue(stale.queried)
            assertTrue(HaAreaProtocol.hasLiteralNullAssignment(stale.device, stale.areas))
            val readsBeforeRepair = commands.count { it == "config/device_registry/list" }

            currentArea.set("study") // Another HA owner moves the device after the catalog read.
            assertFalse(manager.applyRequestedArea("test", "panel", "", stale.ownerKey))
            assertEquals(readsBeforeRepair + 1, commands.count { it == "config/device_registry/list" })
            assertEquals("study", currentArea.get())
            assertTrue(updates.isEmpty())

            currentArea.set("bad-area")
            assertTrue(manager.applyRequestedArea("test", "panel", "", stale.ownerKey))
            assertEquals(readsBeforeRepair + 2, commands.count { it == "config/device_registry/list" })
            assertEquals("", currentArea.get())
            assertEquals(1, updates.size)
            assertTrue(updates.single().isNull("area_id"))
        } finally {
            server.stop(100, 1_000)
        }
    }
}
