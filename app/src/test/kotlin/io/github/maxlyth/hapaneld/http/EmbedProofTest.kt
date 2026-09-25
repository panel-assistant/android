package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.security.SensitiveOperation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class EmbedProofTest {
    // Source-text reason: loads the shipped embed test vectors as input data.
    private val vectors = JSONObject(File("src/main/assets/panel_assistant_embed_v1.json").readText())
    private val proof = vectors.getJSONObject("proof")
    private val keyJson = proof.getJSONObject("key")
    private val key = EmbedProofKey(
        keyJson.getString("key_id"),
        Base64.getUrlDecoder().decode(keyJson.getString("key")),
        keyJson.getString("did"),
    )

    private fun keyring(installed: Boolean = true) = EmbedProofKeyring().also { if (installed) it.install(key) }

    private fun body(request: JSONObject): ByteArray = request.optJSONObject("body_repeat")?.let { repeat ->
        ByteArray(repeat.getInt("count")) { repeat.getString("byte")[0].code.toByte() }
    } ?: request.getString("body").toByteArray(Charsets.UTF_8)

    private fun headerValues(vector: JSONObject): List<String> = when (val raw = vector.get("header")) {
        is JSONArray -> (0 until raw.length()).map(raw::getString)
        else -> listOf(raw as String)
    }

    private fun verify(
        values: List<String>,
        request: JSONObject,
        ring: EmbedProofKeyring,
        onRead: () -> Unit = {},
    ): Result<ProvenEmbedRequest> = runBlocking {
        val bytes = body(request)
        verifyEmbedProof(values, request.getString("method"), request.getString("target"), bytes.size.toLong(), ring) {
            onRead()
            bytes.takeIf { it.size <= EmbedProof.MAX_BODY_BYTES }
        }
    }

    private fun reason(result: Result<ProvenEmbedRequest>): String? =
        (result.exceptionOrNull() as EmbedProofRefused?)?.refusal?.code

    @Test fun `the shared vectors name the header, the limits and the refusal`() {
        assertEquals(EmbedProof.HEADER, vectors.getJSONObject("headers").getString("proof"))
        assertEquals(EmbedProof.LABEL, proof.getString("label"))
        assertEquals(EmbedProof.MAX_HEADER_BYTES, proof.getInt("max_header_bytes"))
        assertEquals(EmbedProof.MAX_BODY_BYTES, proof.getInt("max_body_bytes"))
        assertEquals(EmbedProof.WINDOW_BITS, proof.getInt("window_bits"))
        val refusal = proof.getJSONObject("refusal")
        assertEquals(403, refusal.getInt("status"))
        assertEquals(EmbedProof.ERROR, refusal.getString("error"))
        assertEquals(EmbedProof.Refusal.entries.map { it.code }, strings(refusal.getJSONArray("reasons")))
    }

    @Test fun `every sensitive operation is classified, and only the table's exempt row is exempt`() {
        val operations = proof.getJSONObject("operations")
        val exempt = strings(operations.getJSONArray("exempt")).toSet()
        val required = strings(operations.getJSONArray("approval_required")).toSet()
        assertEquals(exempt, EmbedProof.EXEMPT.map { it.name }.toSet())
        assertTrue(exempt.intersect(required).isEmpty())
        assertEquals(SensitiveOperation.entries.map { it.name }.toSet(), exempt + required)
        val proven = ProvenEmbedRequest("u")
        for (operation in SensitiveOperation.entries) {
            assertEquals(operation.name, operation.name in exempt, proven.exempts(operation))
        }
    }

    @Test fun `every valid vector has its canonical text and MAC, and verifies once`() {
        val valid = proof.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val vector = valid.getJSONObject(i)
            val request = vector.getJSONObject("request")
            val header = vector.getString("header")
            val parsed = EmbedProof.parse(listOf(header))!!
            assertEquals(vector.getLong("counter"), parsed.counter)
            val canonical = EmbedProof.canonical(
                key.did, parsed.keyId, parsed.counter, parsed.userId,
                request.getString("method"), request.getString("target"), body(request),
            )
            assertEquals(vector.getString("note"), vector.getString("canonical"), canonical)
            assertEquals(vector.getString("note"), header.substringAfter(";m="), key.mac(canonical))
            val ring = keyring()
            val result = verify(listOf(header), request, ring)
            assertEquals(vector.getString("note"), keyJson.getString("user_id"), result.getOrThrow().userId)
            assertEquals("replayed", reason(verify(listOf(header), request, ring)))
        }
    }

    @Test fun `every refused vector is refused with its reason`() {
        val refused = proof.getJSONArray("refused")
        assertTrue(refused.length() > 0)
        for (i in 0 until refused.length()) {
            val vector = refused.getJSONObject(i)
            val ring = keyring(installed = vector.optString("key_state") != "none")
            vector.optJSONArray("accepted_before")?.let { counters ->
                for (c in 0 until counters.length()) assertTrue(ring.accept(counters.getLong(c)))
            }
            val result = verify(headerValues(vector), vector.getJSONObject("request"), ring)
            assertEquals(vector.getString("note"), vector.getString("reason"), reason(result))
        }
    }

    @Test fun `the replay window accepts each counter once within 256 of the highest`() {
        val ring = keyring()
        val window = proof.getJSONArray("window")
        for (i in 0 until window.length()) {
            val step = window.getJSONArray(i)
            assertEquals("step $i counter ${step.getLong(0)}", step.getString(1) == "accepted", ring.accept(step.getLong(0)))
        }
    }

    @Test fun `a jump in the highest counter leaves every skipped counter acceptable once`() {
        val ring = keyring()
        for (counter in listOf(3L, 4L, 5L, 8L)) assertTrue(ring.accept(counter))
        for (counter in listOf(6L, 7L)) assertTrue("$counter", ring.accept(counter))
        for (counter in listOf(3L, 4L, 5L, 6L, 7L, 8L)) assertFalse("$counter", ring.accept(counter))
        assertTrue(ring.accept(2L))
    }

    @Test fun `a forged proof does not move the window`() {
        val valid = proof.getJSONArray("valid").getJSONObject(0)
        val request = valid.getJSONObject("request")
        val header = valid.getString("header")
        val ring = keyring()
        val forged = header.substringBefore(";n=") + ";n=500;" + header.substringAfter(";n=").substringAfter(';')
        assertEquals("bad_mac", reason(verify(listOf(forged), request, ring)))
        // Had the forgery set the highest counter to 500, counter 1 would be outside the window.
        assertTrue(verify(listOf(header), request, ring).isSuccess)
    }

    @Test fun `the body is read only for a well-formed proof naming the live key`() {
        val request = proof.getJSONArray("valid").getJSONObject(0).getJSONObject("request")
        var reads = 0
        assertEquals("malformed", reason(verify(listOf("v1;nonsense"), request, keyring()) { reads++ }))
        val header = proof.getJSONArray("valid").getJSONObject(0).getString("header")
        assertEquals("unknown_key", reason(verify(listOf(header), request, keyring(installed = false)) { reads++ }))
        assertEquals(0, reads)
        assertTrue(verify(listOf(header), request, keyring()) { reads++ }.isSuccess)
        assertEquals(1, reads)
    }

    @Test fun `a declared body over 1 MiB is malformed before any read`() = runBlocking {
        val header = proof.getJSONArray("valid").getJSONObject(0).getString("header")
        var reads = 0
        val over = verifyEmbedProof(listOf(header), "POST", "/api/v1/config", EmbedProof.MAX_BODY_BYTES + 1L, keyring()) {
            reads++
            ByteArray(0)
        }
        assertEquals("malformed", reason(over))
        assertEquals(0, reads)
    }

    @Test fun `a replaced or cleared key verifies nothing, and an old session's clear leaves the new key`() {
        val valid = proof.getJSONArray("valid").getJSONObject(0)
        val ring = keyring()
        val other = EmbedProofKey("fedcba9876543210", ByteArray(32), key.did)
        ring.install(other)
        assertEquals("unknown_key", reason(verify(listOf(valid.getString("header")), valid.getJSONObject("request"), ring)))
        ring.clear(key.keyId)
        assertEquals(other.keyId, ring.liveKeyId())
        ring.install(key)
        ring.clear(key.keyId)
        assertNull(ring.liveKeyId())
        assertEquals("unknown_key", reason(verify(listOf(valid.getString("header")), valid.getJSONObject("request"), ring)))
    }

    @Test fun `a reinstalled key starts a fresh window`() {
        val ring = keyring()
        assertTrue(ring.accept(300))
        ring.install(key)
        // A new session's counters start again at 1, far below the old highest.
        assertTrue(ring.accept(1))
    }

    @Test fun `the key never appears in its string form`() {
        val text = key.toString()
        assertTrue(text.contains(key.keyId))
        assertFalse(text.contains(keyJson.getString("key")))
        assertFalse(text.contains(key.did))
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
}
