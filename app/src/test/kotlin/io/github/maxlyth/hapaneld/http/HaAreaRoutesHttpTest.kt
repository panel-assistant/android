package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import sun.misc.Unsafe

class HaAreaRoutesHttpTest {
    @Test fun `an unconfigured panel reports an unqueried registry through the production mount`() = withArea { fixture ->
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

    @Test fun `an unavailable HA registry never clears a requested local area`() = withArea { fixture ->
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

    @Test fun `the area endpoint retains production host admission`() = withArea { fixture ->
        testApplication {
            application { fixture.mount(this) }
            val response = client.get("/api/v1/config/ha-area") {
                header(HttpHeaders.Host, "elsewhere.example")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("host not allowed\n", response.bodyAsText())
        }
    }

    private fun withArea(block: (PaneldServerHttpFixture) -> Unit) {
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
                field("haArea", HaAreaRuntime(fixture.config, learning, scope, mutationLock) { false })
                block(fixture)
            }
        } finally {
            scope.cancel()
        }
    }
}
