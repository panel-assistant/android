package io.panelassistant.android.http

import io.panelassistant.android.security.SensitiveOperation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.PipelineCall
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The key Panel Assistant issued in this panel's live `panel_assistant/hello` session. It is held in memory
 * only and never persisted, logged, exported or reported; [toString] names the key id alone.
 */
internal class EmbedProofKey(val keyId: String, key: ByteArray, val did: String) {
    private val key = key.copyOf()

    fun mac(text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    override fun toString(): String = "EmbedProofKey(keyId=$keyId)"
}

/** A proxied request whose proof verified: made by Home Assistant administrator [userId] via Panel Assistant. */
internal data class ProvenEmbedRequest(val userId: String) {
    fun exempts(operation: SensitiveOperation): Boolean = operation in EmbedProof.EXEMPT

    companion object {
        val ATTRIBUTE = AttributeKey<ProvenEmbedRequest>("PanelAssistantProvenRequest")
    }
}

/**
 * `X-Panel-Assistant-Proof`: Panel Assistant's proof that a proxied request comes from a Home Assistant
 * administrator's authenticated sidebar session. A valid proof exempts only the operations in [EXEMPT] from
 * on-panel approval; it changes no other guard. A request that carries the header and fails verification is
 * refused, never treated as a request without it.
 *
 * ```
 * proof = "v1;k=" 16LOWERHEX ";n=" counter ";u=" 32LOWERHEX ";m=" 43base64url
 * mac   = base64url(HMAC-SHA256(key, label LF did LF key_id LF counter LF user_id LF METHOD LF target LF digest))
 * ```
 *
 * `counter` is decimal without leading zeros, 1 to 2^63−1; `target` is the request target as on the request
 * line; `digest` is the lowercase hex SHA-256 of the body, or `-` when the body is empty. Replay protection is
 * clock-free: the highest accepted counter and a 256-bit window below it, per key.
 */
internal object EmbedProof {
    const val HEADER = "X-Panel-Assistant-Proof"
    const val LABEL = "panel-assistant-embed-proof-v1"
    /** The grammar bounds a proof to 124 bytes; the shared vectors state this ceiling. */
    const val MAX_HEADER_BYTES = 128
    const val MAX_BODY_BYTES = 1024 * 1024
    const val WINDOW_BITS = 256
    const val ERROR = "embed-proof-rejected"

    val EXEMPT: Set<SensitiveOperation> = setOf(
        SensitiveOperation.DISPLAY_CONFIGURATION,
        SensitiveOperation.POWER_CONFIGURATION,
        SensitiveOperation.POWER_SAFETY_ACKNOWLEDGEMENT,
        SensitiveOperation.PROFILE_ACTIVATE,
        SensitiveOperation.CAMERA_ENABLE,
        SensitiveOperation.REMOTE_MEDIA,
        SensitiveOperation.PACKAGE_TAME,
        SensitiveOperation.DASHBOARD_RELOAD,
    )

    private val GRAMMAR =
        Regex("^v1;k=([0-9a-f]{16});n=([1-9][0-9]{0,18});u=([0-9a-f]{32});m=([A-Za-z0-9_-]{43})$")

    enum class Refusal(val code: String) {
        MALFORMED("malformed"),
        UNKNOWN_KEY("unknown_key"),
        BAD_MAC("bad_mac"),
        REPLAYED("replayed"),
    }

    data class Parsed(val keyId: String, val counter: Long, val userId: String, val mac: String)

    /** Parse the header's line values; null for anything but exactly one line that meets the grammar. */
    fun parse(values: List<String>): Parsed? {
        val raw = values.singleOrNull() ?: return null
        val match = GRAMMAR.matchEntire(raw) ?: return null
        val (keyId, counterText, userId, mac) = match.destructured
        val counter = counterText.toLongOrNull() ?: return null
        return Parsed(keyId, counter, userId, mac)
    }

    fun canonical(did: String, keyId: String, counter: Long, userId: String, method: String, target: String, body: ByteArray): String =
        listOf(LABEL, did, keyId, counter.toString(), userId, method, target, bodyDigest(body)).joinToString("\n")

    fun bodyDigest(body: ByteArray): String = if (body.isEmpty()) "-" else sha256Hex(body)
}

/**
 * The one live proof key and its replay window. A new session's key replaces the old one and starts a fresh
 * window; ending that session clears it. Thread-safe.
 */
internal class EmbedProofKeyring {
    private var key: EmbedProofKey? = null
    private var highest = 0L
    // Bit i records that counter (highest − i) was accepted.
    private val window = LongArray(EmbedProof.WINDOW_BITS / Long.SIZE_BITS)

    @Synchronized
    fun install(next: EmbedProofKey) {
        key = next
        highest = 0L
        window.fill(0L)
    }

    /** Clear [keyId]'s key, if it is still the live one; a later session may already have replaced it. */
    @Synchronized
    fun clear(keyId: String) {
        if (key?.keyId == keyId) {
            key = null
            highest = 0L
            window.fill(0L)
        }
    }

    @Synchronized
    fun liveKeyId(): String? = key?.keyId

    /** The key for [keyId] when it is the live one, else null. */
    @Synchronized
    fun key(keyId: String): EmbedProofKey? = key?.takeIf { it.keyId == keyId }

    /** Accept [counter] once. Called only after the MAC passed, so a forged proof never moves the window. */
    @Synchronized
    fun accept(counter: Long): Boolean {
        if (counter > highest) {
            shift(counter - highest)
            highest = counter
            setBit(0)
            return true
        }
        val age = highest - counter
        if (age >= EmbedProof.WINDOW_BITS || bit(age.toInt())) return false
        setBit(age.toInt())
        return true
    }

    private fun shift(by: Long) {
        if (by >= EmbedProof.WINDOW_BITS) {
            window.fill(0L)
            return
        }
        for (i in EmbedProof.WINDOW_BITS - 1 downTo by.toInt()) {
            if (bit(i - by.toInt())) setBit(i) else clearBit(i)
        }
        for (i in 0 until by.toInt()) clearBit(i)
    }

    private fun bit(i: Int) = window[i / Long.SIZE_BITS] and (1L shl (i % Long.SIZE_BITS)) != 0L
    private fun setBit(i: Int) { window[i / Long.SIZE_BITS] = window[i / Long.SIZE_BITS] or (1L shl (i % Long.SIZE_BITS)) }
    private fun clearBit(i: Int) { window[i / Long.SIZE_BITS] = window[i / Long.SIZE_BITS] and (1L shl (i % Long.SIZE_BITS)).inv() }
}

internal object PanelAssistantEmbedKeys {
    val instance = EmbedProofKeyring()
}

/**
 * Verify a proof in the order malformed, unknown key, bad MAC, replayed. [body] is read only once the header
 * is well formed and names the live key, and returns null when the body is over the limit.
 */
internal suspend fun verifyEmbedProof(
    values: List<String>,
    method: String,
    target: String,
    declaredLength: Long?,
    keyring: EmbedProofKeyring,
    body: suspend () -> ByteArray?,
): Result<ProvenEmbedRequest> {
    fun refuse(refusal: EmbedProof.Refusal) = Result.failure<ProvenEmbedRequest>(EmbedProofRefused(refusal))
    val parsed = EmbedProof.parse(values) ?: return refuse(EmbedProof.Refusal.MALFORMED)
    if (declaredLength != null && declaredLength > EmbedProof.MAX_BODY_BYTES) return refuse(EmbedProof.Refusal.MALFORMED)
    val key = keyring.key(parsed.keyId) ?: return refuse(EmbedProof.Refusal.UNKNOWN_KEY)
    val bytes = body() ?: return refuse(EmbedProof.Refusal.MALFORMED)
    val expected = key.mac(
        EmbedProof.canonical(key.did, parsed.keyId, parsed.counter, parsed.userId, method, target, bytes),
    )
    if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), parsed.mac.toByteArray(Charsets.US_ASCII))) {
        return refuse(EmbedProof.Refusal.BAD_MAC)
    }
    if (!keyring.accept(parsed.counter)) return refuse(EmbedProof.Refusal.REPLAYED)
    return Result.success(ProvenEmbedRequest(parsed.userId))
}

internal class EmbedProofRefused(val refusal: EmbedProof.Refusal) : RuntimeException(refusal.code)

/**
 * Verify the request's proof, when it carries one, before any handler runs. Returns false after answering 403
 * `embed-proof-rejected` with its reason. A verified body is handed back to the handlers unchanged. A request
 * without the header is untouched and returns true.
 */
internal suspend fun PipelineCall.admitEmbedProof(keyring: EmbedProofKeyring): Boolean {
    val values = request.headers.getAll(EmbedProof.HEADER) ?: return true
    var read: ByteArray? = null
    val result = verifyEmbedProof(
        values = values,
        method = request.httpMethod.value,
        target = request.uri,
        declaredLength = request.headers[HttpHeaders.ContentLength]?.let { it.toLongOrNull() ?: Long.MAX_VALUE },
        keyring = keyring,
    ) {
        request.receiveChannel().readRemaining(EmbedProof.MAX_BODY_BYTES + 1L).readByteArray()
            .also { read = it }
            .takeIf { it.size <= EmbedProof.MAX_BODY_BYTES }
    }
    read?.let { bytes ->
        // The body was consumed here; every later receive reads the same bytes.
        request.pipeline.intercept(ApplicationReceivePipeline.Before) { proceedWith(ByteReadChannel(bytes)) }
    }
    val proven = result.getOrNull() ?: run {
        val refusal = (result.exceptionOrNull() as EmbedProofRefused).refusal
        respondText(
            "{\"ok\":false,\"error\":\"${EmbedProof.ERROR}\",\"reason\":\"${refusal.code}\"}",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return false
    }
    attributes.put(ProvenEmbedRequest.ATTRIBUTE, proven)
    return true
}

/** The request's verified proof, set once by the request intercept; null for every request without one. */
internal fun ApplicationCall.provenEmbedRequest(): ProvenEmbedRequest? = attributes.getOrNull(ProvenEmbedRequest.ATTRIBUTE)
