package io.panelassistant.android.http

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.panelassistant.android.dashboard.EntityFilterProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bounded reader used by entity routes, exercised with real request bodies. */
class PaneldServerEntityAdminRoutesTest {
    private val routePolicies = linkedMapOf(
        "/activate" to true,
        "/policy" to false,
        "/override" to false,
        "/overrides" to false,
        "/issues" to false,
        "/reset" to true,
    )

    @Test fun `all six routes reject declared and chunked overflow before JSON parsing`() = testApplication {
        application { routing { installEntityReaderRoutes() } }
        val oversizedInvalid = "not-json" + "x".repeat(MAX_ENTITY_ADMIN_BODY_BYTES.toInt())

        routePolicies.keys.forEach { path ->
            val declared = client.post(path) { setBody(oversizedInvalid) }
            assertEquals(path, HttpStatusCode.PayloadTooLarge, declared.status)
            assertEquals("request too large\n", declared.bodyAsText())

            val chunked = client.post(path) { setBody(chunked(oversizedInvalid.toByteArray())) }
            assertEquals(path, HttpStatusCode.PayloadTooLarge, chunked.status)
            assertEquals("request too large\n", chunked.bodyAsText())
        }
    }

    @Test fun `valid invalid and route-specific blank JSON semantics are preserved`() = testApplication {
        application { routing { installEntityReaderRoutes() } }

        routePolicies.forEach { (path, allowBlank) ->
            val valid = client.post(path) { setBody("""{"confirm":true}""") }
            assertEquals(path, HttpStatusCode.OK, valid.status)
            assertTrue(path, JSONObject(valid.bodyAsText()).getBoolean("confirm"))

            val invalid = client.post(path) { setBody("not-json") }
            assertEquals(path, HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid JSON\n", invalid.bodyAsText())

            val blank = client.post(path) { setBody("   \n") }
            assertEquals(path, if (allowBlank) HttpStatusCode.OK else HttpStatusCode.BadRequest, blank.status)
            if (allowBlank) assertEquals(0, JSONObject(blank.bodyAsText()).length())
            else assertEquals("invalid JSON\n", blank.bodyAsText())
        }
    }

    @Test fun `blank explicit manual list parses to an explicit empty id list`() {
        val update = EntityFilterProtocol.parseUpdate("""{"mode":"manual","entity_ids":[" "]}""")
        assertTrue(update.entityIds != null)
        assertTrue(update.entityIds!!.isEmpty())
    }

    private fun io.ktor.server.routing.Route.installEntityReaderRoutes() {
        routePolicies.forEach { (path, allowBlank) ->
            post(path) {
                val parsed = receiveEntityAdminJson(call, allowBlank) ?: return@post
                call.respondText(parsed.toString(), ContentType.Application.Json)
            }
        }
    }

    private fun chunked(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json
        override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
    }

}
