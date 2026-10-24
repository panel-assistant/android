package io.panelassistant.android.http

import android.util.Log
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.isLoopbackPeer
import io.panelassistant.android.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import kotlinx.coroutines.delay
import java.security.MessageDigest

/**
 * Canonical approval payload for a materialized HTTP request. Distinct query-name order is not
 * semantically significant, but duplicate value order is because Ktor's first-value lookup can
 * affect behavior. Group by name while retaining each value list's order, then length-frame every
 * field and collection count so no name/value grouping can share an approval accidentally.
 */
internal fun exactHttpApprovalPayload(
    method: String,
    path: String,
    parameters: List<Pair<String, String>>,
    bodyDigest: String,
): String = buildString {
    fun frame(value: String) {
        append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
    }

    val grouped = parameters.groupBy({ it.first }, { it.second }).toSortedMap()
    frame(method.uppercase())
    frame(path)
    frame(grouped.size.toString())
    grouped.forEach { (name, values) ->
        frame(name)
        frame(values.size.toString())
        values.forEach(::frame)
    }
    frame(bodyDigest)
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

/** Shared sensitive-request decision. Hardened policy is intentionally remote-only: trusted loopback
 * callers retain the established exemption while every non-loopback request remains peer-, operation-
 * and payload-bound through the one-shot approval broker. */
internal suspend fun authorizeSensitiveRequest(
    call: ApplicationCall,
    hardened: Boolean,
    peer: String,
    operation: SensitiveOperation,
    payload: String,
    summary: String,
    broker: ApprovalBroker,
): Boolean {
    if (!hardened || isLoopbackPeer(peer)) return true
    val (decision, id) = broker.request(operation, peer, payload, summary)
    if (decision == ApprovalBroker.Decision.APPROVED) return true
    call.respondText(approvalRequiredBody(id), ContentType.Application.Json, HttpStatusCode.Accepted)
    return false
}

/**
 * The sensitive operations a Home Assistant administrator's request, relayed from Panel Assistant's sidebar, may
 * perform in hardened mode without approval on the panel (embed contract section 8, answer 3b): configuration
 * and routine panel actions. Installing, backing up, restoring, exporting secrets and rebooting still need it.
 */
internal val RELAYED_ADMINISTRATOR_EXEMPT: Set<SensitiveOperation> = setOf(
    SensitiveOperation.DISPLAY_CONFIGURATION,
    SensitiveOperation.POWER_CONFIGURATION,
    SensitiveOperation.POWER_SAFETY_ACKNOWLEDGEMENT,
    SensitiveOperation.PROFILE_ACTIVATE,
    SensitiveOperation.CAMERA_ENABLE,
    SensitiveOperation.REMOTE_MEDIA,
    SensitiveOperation.PACKAGE_TAME,
    SensitiveOperation.DASHBOARD_RELOAD,
    SensitiveOperation.WAKE_WORD_IMPORT,
)

/** Whether [origin] is a Home Assistant administrator relayed by Panel Assistant and [operation] is exempt for them,
 * on the manage path and the channel-command path alike; an exemption is logged. */
internal fun relayedAdministratorExempt(origin: io.panelassistant.android.CommandOrigin, operation: SensitiveOperation): Boolean {
    if (!origin.isUser || operation !in RELAYED_ADMINISTRATOR_EXEMPT) return false
    Log.i("ha-paneld/http", "approved by Home Assistant administrator ${origin.approvalKey.removePrefix("user:")} via Panel Assistant: ${operation.name}")
    return true
}

/** The approval peer for requests Panel Assistant makes over its own session (protocol section 20.2 origin key). */
internal const val PANEL_ASSISTANT_APPROVAL_PEER = "panel_assistant"

/**
 * The session form of [authorizeSensitiveRequest]: the same policy for a request Panel Assistant makes over
 * its own session, which has no HTTP peer. Relaxed mode admits it as it admits a LAN request. Hardened mode
 * asks the same one-shot broker, bound to the session's origin key, calls [onPending] once so Panel
 * Assistant hears `pending_approval`, and waits for the physical decision. Null to go ahead, else the
 * refusal code; a cancelled wait withdraws its approval request.
 */
internal suspend fun authorizeSessionRequest(
    hardened: Boolean,
    operation: SensitiveOperation,
    payload: String,
    summary: String,
    broker: ApprovalBroker,
    onPending: () -> Unit,
    pollMs: Long = 1_000L,
): String? {
    if (!hardened) return null
    val (decision, id) = broker.request(operation, PANEL_ASSISTANT_APPROVAL_PEER, payload, summary)
    if (decision == ApprovalBroker.Decision.APPROVED) return null
    onPending()
    var settled = false
    try {
        while (true) {
            delay(pollMs)
            when (broker.state(id)) {
                ApprovalBroker.State.PENDING -> continue
                ApprovalBroker.State.APPROVED -> {
                    // The exact replay consumes the approval, as an HTTP retry does.
                    settled = true
                    return if (broker.request(operation, PANEL_ASSISTANT_APPROVAL_PEER, payload, summary).first ==
                        ApprovalBroker.Decision.APPROVED
                    ) null else PanelAssistantCommandProcessor.CODE_APPROVAL_TIMEOUT
                }
                // The broker keeps no denied state: a record gone was denied, displaced or expired.
                ApprovalBroker.State.ABSENT -> {
                    settled = true
                    return PanelAssistantCommandProcessor.CODE_APPROVAL_DENIED
                }
            }
        }
    } finally {
        if (!settled) broker.deny(id)
    }
}

internal fun Parameters.canonicalDigest(): String {
    val framed = buildString {
        fun frame(value: String) {
            append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
        }

        val fields = entries().sortedBy { it.key }
        frame(fields.size.toString())
        fields.forEach { (name, values) ->
            frame(name)
            frame(values.size.toString())
            values.forEach { value ->
                // Parameters.get(name) consumes the first submitted value, so value order is part
                // of the request's behavior and must remain part of its approval identity.
                frame(value)
            }
        }
    }
    return sha256Hex(framed.toByteArray(Charsets.UTF_8))
}

internal fun exactHttpApprovalPayload(call: ApplicationCall, bodyDigest: String): String =
    exactHttpApprovalPayload(call.op(), bodyDigest)

/** A managed request's payload frames its route's method and path with its `args` as the parameters. */
internal fun exactHttpApprovalPayload(call: OpCall, bodyDigest: String): String =
    exactHttpApprovalPayload(
        method = call.request.httpMethod.value,
        path = call.request.uri.substringBefore('?'),
        parameters = call.request.queryParameters.entries()
            .flatMap { (name, values) -> values.map { name to it } },
        bodyDigest = bodyDigest,
    )

/**
 * [authorizeSensitiveRequest] for a request on either door. Over HTTP it is that function, bound to the peer
 * address. A managed request binds its approval to its origin's approval key (protocol section 20.2), so an
 * approval earned by one origin is never consumed by another: a loopback peer is exempt as on HTTP; a
 * Home Assistant administrator relayed by Panel Assistant is exempt for exactly [RELAYED_ADMINISTRATOR_EXEMPT];
 * a Panel Assistant or relayed request waiting for its approval raises [ManageApprovalPending] so the
 * dispatcher answers an interim `pending_approval` and then its final outcome; any other answers the HTTP `202` body.
 */
internal suspend fun authorizeSensitiveRequest(
    call: OpCall,
    hardened: Boolean,
    operation: SensitiveOperation,
    payload: String,
    summary: String,
    broker: ApprovalBroker,
): Boolean {
    call.http?.let { http ->
        return authorizeSensitiveRequest(http, hardened, call.request.origin.remoteAddress, operation, payload, summary, broker)
    }
    val origin = call.origin
    if (!hardened || origin.peerAddress?.let(::isLoopbackPeer) == true) return true
    if (relayedAdministratorExempt(origin, operation)) return true
    val registration = { broker.request(operation, origin.approvalKey, payload, summary) }
    val (decision, id) = (call as? ManageOpCall)?.managed?.approval?.request(registration) ?: registration()
    if (decision == ApprovalBroker.Decision.APPROVED) return true
    if (origin.isPanelAssistant || origin.isUser) throw io.panelassistant.android.ManageApprovalPending(id)
    call.respondText(approvalRequiredBody(id), ContentType.Application.Json, HttpStatusCode.Accepted)
    return false
}

private fun approvalRequiredBody(id: String): String =
    "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}," +
        "\"message\":\"Approve this request physically on the panel, then retry it; it cannot be approved remotely.\"}"
