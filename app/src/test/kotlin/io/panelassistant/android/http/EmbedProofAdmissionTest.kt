package io.panelassistant.android.http

import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The proof as the request intercept applies it, in front of routes that authorize through the shared
 * sensitive-request decision exactly as the server's do.
 */
class EmbedProofAdmissionTest {
    private val key = EmbedProofKey(KEY_ID, ByteArray(32) { it.toByte() }, DID)
    private var counter = 0L

    private fun proof(method: String, target: String, body: String, n: Long = ++counter, signer: EmbedProofKey = key) =
        "v1;k=${signer.keyId};n=$n;u=$USER;m=" +
            signer.mac(EmbedProof.canonical(DID, signer.keyId, n, USER, method, target, body.toByteArray()))

    private class Rig {
        val ring = EmbedProofKeyring()
        val broker = ApprovalBroker({ 1_000L }, SecureRandom())
        val audit = mutableListOf<String>()
        val handled = mutableListOf<String>()
        var hardened = true
        var peer = REMOTE_PEER
    }

    private fun rig(block: suspend ApplicationTestBuilder.(Rig) -> Unit) = testApplication {
        val rig = Rig().also { it.ring.install(key) }
        application {
            intercept(ApplicationCallPipeline.Plugins) {
                if (!call.admitEmbedProof(rig.ring)) return@intercept finish()
            }
            routing {
                // An exempt operation whose handler reads its form body after the intercept did.
                post("/api/v1/config") {
                    val parameters = call.receiveParameters()
                    val payload = parameters.entries().joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }
                    if (!authorizeSensitiveRequest(
                            call, rig.hardened, rig.peer, SensitiveOperation.POWER_CONFIGURATION, payload,
                            "power", rig.broker, rig.audit::add,
                        )
                    ) return@post
                    rig.handled += "config:$payload"
                    call.respondText("applied")
                }
                // An operation that still needs approval, reading a raw stream.
                post("/api/v1/backup") {
                    val bytes = call.receiveStream().readBytes().decodeToString()
                    if (!authorizeSensitiveRequest(
                            call, rig.hardened, rig.peer, SensitiveOperation.BACKUP_EXPORT, bytes,
                            "backup", rig.broker, rig.audit::add,
                        )
                    ) return@post
                    rig.handled += "backup:$bytes"
                    call.respondText("exported")
                }
                get("/api/v1/status") { rig.handled += "status"; call.respondText("ok") }
            }
        }
        block(rig)
    }

    private suspend fun ApplicationTestBuilder.postForm(
        path: String,
        body: String,
        proofHeader: String?,
        extra: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.post(path) {
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        proofHeader?.let { header(EmbedProof.HEADER, it) }
        setBody(body)
        extra()
    }

    private suspend fun HttpResponse.reason(): String {
        assertEquals(HttpStatusCode.Forbidden, status)
        val json = JSONObject(bodyAsText())
        assertEquals(false, json.getBoolean("ok"))
        assertEquals("embed-proof-rejected", json.getString("error"))
        return json.getString("reason")
    }

    @Test fun `without a proof an exempt change still waits for approval, exactly as today`() = rig { rig ->
        val response = postForm("/api/v1/config", BODY, null)
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("approval-required", JSONObject(response.bodyAsText()).getString("error"))
        assertTrue(rig.handled.isEmpty())
        assertTrue(rig.audit.isEmpty())
        assertEquals(1, rig.broker.pending().size)
    }

    @Test fun `a valid proof applies an exempt change without approval, with its body intact and an audit line`() = rig { rig ->
        val response = postForm("/api/v1/config", BODY, proof("POST", "/api/v1/config", BODY))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("config:touch_sound=false"), rig.handled)
        assertEquals(
            listOf("approved by Home Assistant administrator $USER via Panel Assistant: POWER_CONFIGURATION"),
            rig.audit,
        )
        assertTrue(rig.broker.pending().isEmpty())
    }

    @Test fun `a valid proof on an operation that still needs approval answers 202`() = rig { rig ->
        val response = postForm("/api/v1/backup", "full", proof("POST", "/api/v1/backup", "full"))
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("approval-required", JSONObject(response.bodyAsText()).getString("error"))
        assertTrue(rig.handled.isEmpty())
        assertTrue(rig.audit.isEmpty())
        assertEquals(listOf(SensitiveOperation.BACKUP_EXPORT), rig.broker.pending().map { it.operation })
    }

    @Test fun `a forged proof is refused bad_mac and reaches no handler`() = rig { rig ->
        val forger = EmbedProofKey(KEY_ID, ByteArray(32) { 9 }, DID)
        assertEquals("bad_mac", postForm("/api/v1/config", BODY, proof("POST", "/api/v1/config", BODY, signer = forger)).reason())
        assertEquals("bad_mac", postForm("/api/v1/config", "touch_sound=true", proof("POST", "/api/v1/config", BODY)).reason())
        assertTrue(rig.handled.isEmpty())
        assertTrue(rig.broker.pending().isEmpty())
    }

    @Test fun `a replayed proof is refused replayed`() = rig { rig ->
        val header = proof("POST", "/api/v1/config", BODY)
        assertEquals(HttpStatusCode.OK, postForm("/api/v1/config", BODY, header).status)
        assertEquals("replayed", postForm("/api/v1/config", BODY, header).reason())
        assertEquals(1, rig.handled.size)
    }

    @Test fun `a proof under another key, or with no live key, is refused unknown_key`() = rig { rig ->
        val other = EmbedProofKey("fedcba9876543210", ByteArray(32) { it.toByte() }, DID)
        assertEquals("unknown_key", postForm("/api/v1/config", BODY, proof("POST", "/api/v1/config", BODY, signer = other)).reason())
        rig.ring.clear(KEY_ID)
        assertEquals("unknown_key", postForm("/api/v1/config", BODY, proof("POST", "/api/v1/config", BODY)).reason())
        assertTrue(rig.handled.isEmpty())
    }

    @Test fun `malformed proofs are refused malformed, including a repeated header line`() = rig { rig ->
        assertEquals("malformed", postForm("/api/v1/config", BODY, "v1;k=$KEY_ID").reason())
        assertEquals("malformed", postForm("/api/v1/config", BODY, "").reason())
        val good = proof("POST", "/api/v1/config", BODY)
        assertEquals("malformed", postForm("/api/v1/config", BODY, good) { header(EmbedProof.HEADER, good) }.reason())
        assertEquals("malformed", client.get("/api/v1/status") { header(EmbedProof.HEADER, "nope") }.reason())
        assertTrue(rig.handled.isEmpty())
    }

    @Test fun `an over-size body is refused malformed, declared or streamed`() = rig { rig ->
        val big = "a".repeat(EmbedProof.MAX_BODY_BYTES + 1)
        assertEquals("malformed", postForm("/api/v1/backup", big, proof("POST", "/api/v1/backup", big)).reason())
        val chunked = client.post("/api/v1/backup") {
            header(EmbedProof.HEADER, proof("POST", "/api/v1/backup", big))
            setBody(object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(big.toByteArray())
            })
        }
        assertEquals("malformed", chunked.reason())
        // Exactly 1 MiB is within the limit.
        val limit = "a".repeat(EmbedProof.MAX_BODY_BYTES)
        assertEquals(HttpStatusCode.Accepted, postForm("/api/v1/backup", limit, proof("POST", "/api/v1/backup", limit)).status)
        assertTrue(rig.handled.isEmpty())
    }

    @Test fun `a proof over the request target and an empty body verifies`() = rig { rig ->
        val target = "/api/v1/status?x=a%2Fb+c&y=%E2%9C%93"
        val response = client.get(target) { header(EmbedProof.HEADER, proof("GET", target, "")) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("status"), rig.handled)
    }

    @Test fun `a proof changes nothing in Relaxed mode or for a loopback peer`() = rig { rig ->
        rig.hardened = false
        assertEquals(HttpStatusCode.OK, postForm("/api/v1/config", BODY, proof("POST", "/api/v1/config", BODY)).status)
        rig.hardened = true
        rig.peer = "127.0.0.1"
        assertEquals(HttpStatusCode.OK, postForm("/api/v1/config", BODY, proof("POST", "/api/v1/config", BODY)).status)
        assertEquals(2, rig.handled.size)
        assertTrue(rig.audit.isEmpty())
    }

    @Test fun `administrator proof exempts sensitive configuration but installation still requires approval`() {
        val proven = ProvenEmbedRequest(USER)
        assertTrue(proven.exempts(SensitiveOperation.POWER_CONFIGURATION))
        assertTrue(proven.exempts(SensitiveOperation.PACKAGE_TAME))
        assertFalse(proven.exempts(SensitiveOperation.APK_INSTALL))
    }

    private companion object {
        const val REMOTE_PEER = "192.0.2.10"
        const val KEY_ID = "0123456789abcdef"
        const val USER = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        const val DID = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"
        const val BODY = "touch_sound=false"
    }
}
