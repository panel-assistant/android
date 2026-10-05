package io.panelassistant.android.http

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.Assert.assertEquals
import org.junit.Test

class SmallFormPostBodyTest {
    @Test fun `small form reader rejects declared and chunked total-body overflow`() = testApplication {
        application {
            routing {
                post("/form") {
                    val parameters = receiveBoundedFormParameters(call, TEST_LIMIT) ?: return@post
                    call.respondText(parameters["value"].orEmpty())
                }
            }
        }
        val oversized = "value=" + "x".repeat(TEST_LIMIT.toInt())

        val declared = client.post("/form") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        assertEquals("request too large\n", declared.bodyAsText())

        val chunked = client.post("/form") { setBody(chunkedForm(oversized.toByteArray())) }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertEquals("request too large\n", chunked.bodyAsText())
    }

    @Test fun `small form reader preserves percent and plus decoding`() = testApplication {
        application {
            routing {
                post("/form") {
                    val parameters = receiveBoundedFormParameters(call, TEST_LIMIT) ?: return@post
                    call.respondText("${parameters["value"]}|${parameters["mode"]}")
                }
            }
        }
        val response = client.post("/form") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("value=Sample+Panel&mode=one%2Ftwo")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Sample Panel|one/two", response.bodyAsText())
    }

    private fun chunkedForm(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.FormUrlEncoded
        override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
    }

    private companion object { const val TEST_LIMIT = 1_024L }
}
