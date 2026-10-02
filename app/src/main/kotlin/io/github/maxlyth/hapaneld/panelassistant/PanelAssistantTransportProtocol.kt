package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** What this panel says about itself in `panel_assistant/hello`. */
internal data class PanelAssistantHelloIdentity(
    /** Lowercase hex discovery identity; null until an installation identity is durable. */
    val did: String?,
    val appVersion: String,
    val appVersionCode: Int,
)

/** Session-only authority for admitting panel-app candidates. Never persisted while PA is offline. */
internal data class PanelAssistantUpdatePolicy(val protocolMin: Int, val protocolMax: Int, val prerelease: Boolean)

/** The integration's accepted reply: what this session may use and whom it talks to. */
internal data class PanelAssistantSession(
    val protocol: Int,
    val token: String,
    val authority: String,
    val capabilities: List<String>,
    val integrationVersion: String,
    /**
     * The integration's claim on this panel's MQTT discovery entities (`withdraw`) or its release of
     * them (`announce`). Present exactly when the session granted `mqtt_withdraw`; null otherwise, whatever
     * the reply carried, because only the grant makes the field binding.
     */
    val mqttDiscovery: String? = null,
    /** The sidebar proof key, present exactly when the session granted `embed_proof`. Never logged. */
    val embed: PanelAssistantEmbedGrant? = null,
    val connection: io.github.maxlyth.hapaneld.HaConnectionAdvertisement? = null,
    val updatePolicy: PanelAssistantUpdatePolicy? = null,
) {
    /** The session token is a bearer for this session's requests, and the embed key a secret; keep both out of logs. */
    override fun toString(): String =
        "PanelAssistantSession(protocol=$protocol, authority=$authority, capabilities=$capabilities, " +
            "integrationVersion=$integrationVersion, mqttDiscovery=$mqttDiscovery)"
}

/** The key Panel Assistant issued for proving sidebar requests (`hello` result field `embed`). */
internal class PanelAssistantEmbedGrant(val keyId: String, key: ByteArray) {
    private val key = key.copyOf()

    fun key(): ByteArray = key.copyOf()

    override fun toString(): String = "PanelAssistantEmbedGrant(keyId=$keyId)"
}

internal sealed interface PanelAssistantHelloOutcome {
    data class Accepted(val session: PanelAssistantSession) : PanelAssistantHelloOutcome

    /** An error result; [code] is Core's or the integration's closed code, never display text. */
    data class Refused(val code: String) : PanelAssistantHelloOutcome
}

/** An event delivered on the `hello` subscription. */
internal sealed interface PanelAssistantSessionEvent {
    data class Closed(val reason: String) : PanelAssistantSessionEvent

    /**
     * A command for [channel]. [session] is the token the integration sent it for, compared with the live
     * session before anything runs; [value] is the typed wire value, JSON null for a button press.
     * [deadlineMs] is null when the event carried a malformed or out-of-range deadline.
     */
    data class Command(
        val commandId: String,
        val session: String?,
        val channel: String?,
        val value: Any?,
        val deadlineMs: Long?,
    ) : PanelAssistantSessionEvent

    /** A command event with no usable `command_id`: there is nothing to answer it with. */
    data object MalformedCommand : PanelAssistantSessionEvent

    /** Any other kind, which this build does not act on. */
    data class Ignored(val kind: String) : PanelAssistantSessionEvent
}

/** The result answering one `report_state` request, correlated by its parsed message id. */
internal sealed interface PanelAssistantReportResult {
    val id: Long

    /** Every observation in the batch was applied, except those [rejected] by channel with a code. */
    data class Acknowledged(override val id: Long, val rejected: Map<String, String>) : PanelAssistantReportResult

    data class Failed(override val id: Long, val code: String) : PanelAssistantReportResult
}

/**
 * Wire vocabulary for the native transport's handshake, bound by the protocol specification's
 * sections 5 and 6. Pure: every function here is a translation between JSON text and typed values,
 * so the owner's lifecycle can be tested without a socket.
 */
internal object PanelAssistantTransportProtocol {
    const val PROTOCOL_MIN = 3
    const val PROTOCOL_MAX = 3
    const val COMMAND_HELLO = "panel_assistant/hello"
    const val COMMAND_REPORT_STATE = "panel_assistant/report_state"
    const val COMMAND_COMMAND_RESULT = "panel_assistant/command_result"
    const val COMMAND_RESTART_NOTICE = "panel_assistant/restart_notice"

    const val AUTHORITY_MQTT = "mqtt"
    const val AUTHORITY_SHADOW = "shadow"
    const val AUTHORITY_NATIVE = "native"
    val AUTHORITIES: Set<String> = setOf(AUTHORITY_MQTT, AUTHORITY_SHADOW, AUTHORITY_NATIVE)

    /** The panel's MQTT discovery entities are removed from Home Assistant and stay removed. */
    const val MQTT_DISCOVERY_WITHDRAW = "withdraw"
    /** The panel announces its MQTT discovery entities as it always has. */
    const val MQTT_DISCOVERY_ANNOUNCE = "announce"
    val MQTT_DISCOVERY_VALUES: Set<String> = setOf(MQTT_DISCOVERY_WITHDRAW, MQTT_DISCOVERY_ANNOUNCE)

    const val CAPABILITY_STATE = "state"
    const val CAPABILITY_COMMANDS = "commands"
    const val CAPABILITY_APPROVAL = "approval"

    /**
     * Offered in every hello: this panel can withdraw its MQTT discovery entities when the integration
     * claims them. Granting it makes the reply's `mqtt_discovery` required and binding. Migration
     * scaffolding for moving entities off MQTT; it is deleted with MQTT.
     */
    const val CAPABILITY_MQTT_WITHDRAW = "mqtt_withdraw"

    /**
     * This panel verifies Panel Assistant's sidebar request proofs. Granting it makes the reply's `embed` key
     * required; the key lives in memory for the session only.
     */
    const val CAPABILITY_EMBED_PROOF = "embed_proof"

    /** The panel is an Assist satellite on this session ([PanelAssistantVoice]). */
    const val CAPABILITY_VOICE = "voice"

    const val OUTCOME_APPLIED = "applied"
    const val OUTCOME_SUPERSEDED = "superseded"
    const val OUTCOME_PENDING_APPROVAL = "pending_approval"
    const val OUTCOME_REFUSED = "refused"
    const val OUTCOME_FAILED = "failed"

    const val DEFAULT_DEADLINE_MS = 10_000L
    const val MAX_DEADLINE_MS = 60_000L

    const val SYNC_FULL_BEGIN = "full_begin"
    const val SYNC_DELTA = "delta"
    const val SYNC_FULL_END = "full_end"
    const val STATE_KNOWN = "known"
    const val STATE_UNAVAILABLE = "unavailable"

    const val CODE_UNKNOWN_COMMAND = "unknown_command"
    const val CODE_UNKNOWN_PANEL = "unknown_panel"
    const val CODE_PROTOCOL_UNSUPPORTED = "protocol_unsupported"
    const val CODE_PANEL_USER_MISMATCH = "panel_user_mismatch"
    const val CODE_PANEL_IDENTITY_UNAVAILABLE = "panel_identity_unavailable"
    const val CODE_INVALID_FORMAT = "invalid_format"
    const val CODE_SESSION_UNKNOWN = "session_unknown"

    /**
     * The entry that held this panel was removed, so nothing holds its entities any more and the panel
     * hands them back to MQTT. Migration scaffolding; it is deleted with MQTT.
     */
    const val CODE_ENTRY_REMOVED = "entry_removed"

    const val REASON_SUPERSEDED = "superseded"

    /**
     * Capabilities a fully wired build serves: state reporting, commands with panel-side approval, and the
     * MQTT discovery withdrawal. It reports no events, and advertising a capability it cannot serve would
     * leave the integration waiting.
     */
    val CAPABILITIES: List<String> =
        listOf(
            CAPABILITY_STATE,
            CAPABILITY_COMMANDS,
            CAPABILITY_APPROVAL,
            CAPABILITY_MQTT_WITHDRAW,
            CAPABILITY_EMBED_PROOF,
            CAPABILITY_VOICE,
        )

    /**
     * The handshake contract this build implements, in canonical form. The specification's shared
     * contract file does not exist yet; until it does, the digest the panel sends covers exactly this
     * text, so a change to the handshake vocabulary changes the digest the integration records.
     */
    internal const val CANONICAL_CONTRACT: String =
        """{"protocol":{"min":3,"max":3},"commands":["panel_assistant/hello","panel_assistant/report_state","panel_assistant/command_result","panel_assistant/restart_notice","panel_assistant/voice_configuration","panel_assistant/voice_run","panel_assistant/voice_played"],"capabilities":["state","commands","approval","mqtt_withdraw","embed_proof","voice"]}"""

    val CONTRACT_DIGEST: String = MessageDigest.getInstance("SHA-256")
        .digest(CANONICAL_CONTRACT.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val COMMAND_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$")
    private val EMBED_KEY_ID = Regex("^[0-9a-f]{16}$")
    private val EMBED_KEY = Regex("^[A-Za-z0-9_-]{43}$")
    private const val MAX_SESSION_TOKEN_CHARS = 64
    private const val MAX_CAPABILITIES = 16

    fun hello(
        id: Long,
        identity: PanelAssistantHelloIdentity,
        capabilities: List<String> = emptyList(),
        channels: List<PanelAssistantChannelDescriptor> = emptyList(),
        unsupported: List<String> = emptyList(),
        addresses: List<String> = emptyList(),
    ): String = JSONObject()
        .put("id", id)
        .put("type", COMMAND_HELLO)
        .put("protocol", JSONObject().put("min", PROTOCOL_MIN).put("max", PROTOCOL_MAX))
        .put("did", identity.did ?: JSONObject.NULL)
        .put(
            "app",
            JSONObject().put("version", identity.appVersion).put("version_code", identity.appVersionCode),
        )
        .put("contract_digest", CONTRACT_DIGEST)
        .put("capabilities", JSONArray(capabilities))
        .put("channels", JSONArray(channels.map { it.toJson() }))
        .apply { if (addresses.isNotEmpty()) put("addresses", JSONArray(addresses)) }
        // An explicit statement that these channels cannot be served here, so the integration removes their
        // entities; a channel merely absent from both lists changes nothing. Omitted when empty.
        .apply { if (unsupported.isNotEmpty()) put("unsupported", JSONArray(unsupported)) }
        .toString()

    fun reportState(id: Long, session: String, sync: String, observations: JSONArray): String = JSONObject()
        .put("id", id)
        .put("type", COMMAND_REPORT_STATE)
        .put("session", session)
        .put("sync", sync)
        .put("observations", observations)
        .toString()

    fun restartNotice(id: Long, session: String, scope: String, reason: String, expectedBackMs: Long): String =
        JSONObject().put("id", id).put("type", COMMAND_RESTART_NOTICE).put("session", session)
            .put("scope", scope).put("reason", reason).put("expected_back_ms", expectedBackMs).toString()

    /**
     * Interpret a result frame as a `report_state` answer; null for any frame that is not a result. The
     * caller decides whether its id names an outstanding request. A per-observation rejection whose
     * channel or code is malformed is dropped: it cannot name a channel this panel sent.
     */
    fun reportResult(frame: JSONObject): PanelAssistantReportResult? {
        if (frame.optString("type") != "result") return null
        val id = messageId(frame) ?: return null
        if (frame.opt("success") != true) {
            val code = frame.optJSONObject("error")?.opt("code") as? String
            return PanelAssistantReportResult.Failed(id, code?.takeIf(CODE::matches) ?: CODE_INVALID_FORMAT)
        }
        val rejected = LinkedHashMap<String, String>()
        val list = frame.optJSONObject("result")?.optJSONArray("rejected")
        if (list != null) {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val channel = (item.opt("channel") as? String)?.takeIf(CODE::matches) ?: continue
                rejected[channel] = (item.opt("code") as? String)?.takeIf(CODE::matches) ?: CODE_INVALID_FORMAT
            }
        }
        return PanelAssistantReportResult.Acknowledged(id, rejected)
    }

    /** The one final (or `pending_approval` interim) answer to a delivered command. */
    fun commandResult(id: Long, session: String, commandId: String, outcome: String, code: String?): String =
        JSONObject()
            .put("id", id)
            .put("type", COMMAND_COMMAND_RESULT)
            .put("session", session)
            .put("command_id", commandId)
            .put("outcome", outcome)
            .apply { if (code != null) put("code", code) }
            .toString()

    fun ping(id: Long): String = JSONObject().put("id", id).put("type", "ping").toString()

    /**
     * The message id a frame answers, parsed rather than matched as text: a substring test for
     * `"id":1` also matches 10 and above. Null for a frame with no integral id.
     */
    fun messageId(frame: JSONObject): Long? = when (val id = frame.opt("id")) {
        is Int -> id.toLong()
        is Long -> id
        else -> null
    }

    /**
     * Interpret the result frame answering the `hello` sent as [helloId]. Null means the frame is not
     * that result. A success result whose body breaks the contract is a protocol failure, reported
     * as an exception so the caller retries rather than holding a session it cannot describe.
     */
    private fun updatePolicy(result: JSONObject, protocol: Int): PanelAssistantUpdatePolicy? {
        if (!result.has("update_policy")) return null // An older PA can connect, but cannot admit updates.
        val policy = result.optJSONObject("update_policy")
            ?: throw PanelAssistantProtocolException("hello update policy is not an object")
        val low = policy.opt("protocolMin") as? Int
        val high = policy.opt("protocolMax") as? Int
        val prerelease = policy.opt("prerelease") as? Boolean
        if (low == null || high == null || low < 1 || high < low || protocol !in low..high || prerelease == null) {
            throw PanelAssistantProtocolException("hello update policy is malformed")
        }
        return PanelAssistantUpdatePolicy(low, high, prerelease)
    }

    fun helloOutcome(
        frame: JSONObject,
        helloId: Long,
        offered: List<String> = CAPABILITIES,
    ): PanelAssistantHelloOutcome? {
        if (frame.optString("type") != "result" || messageId(frame) != helloId) return null
        if (frame.opt("success") != true) {
            val code = frame.optJSONObject("error")?.opt("code") as? String
            return PanelAssistantHelloOutcome.Refused(code?.takeIf(CODE::matches) ?: CODE_INVALID_FORMAT)
        }
        val result = frame.optJSONObject("result") ?: throw PanelAssistantProtocolException("hello result has no body")
        val protocol = result.opt("protocol") as? Int
        if (protocol == null || protocol !in PROTOCOL_MIN..PROTOCOL_MAX) {
            throw PanelAssistantProtocolException("hello result names an unoffered protocol")
        }
        val token = result.opt("session") as? String
        if (token.isNullOrEmpty() || token.length > MAX_SESSION_TOKEN_CHARS) {
            throw PanelAssistantProtocolException("hello result has no usable session")
        }
        val authority = (result.opt("authority") as? String)?.takeIf(CODE::matches)
            ?: throw PanelAssistantProtocolException("hello result has no authority")
        val capabilityArray = result.optJSONArray("capabilities")
            ?: throw PanelAssistantProtocolException("hello result has no capabilities")
        if (capabilityArray.length() > MAX_CAPABILITIES) {
            throw PanelAssistantProtocolException("hello result lists too many capabilities")
        }
        val capabilities = (0 until capabilityArray.length()).map { index ->
            (capabilityArray.opt(index) as? String)?.takeIf(CODE::matches)
                ?: throw PanelAssistantProtocolException("hello result has a malformed capability")
        }
        if (!offered.containsAll(capabilities)) {
            throw PanelAssistantProtocolException("hello result grants a capability the panel did not offer")
        }
        val integrationVersion = (result.optJSONObject("integration")?.opt("version") as? String)
            ?.takeIf(VERSION::matches)
            ?: throw PanelAssistantProtocolException("hello result has no integration version")
        // The claim is negotiated, never inferred from a missing field. Granting mqtt_withdraw makes it
        // required, so a reply without a valid value is a protocol failure the owner retries; without the
        // grant, as from an integration that predates it, the field is ignored whatever it says.
        val mqttDiscovery = if (CAPABILITY_MQTT_WITHDRAW in capabilities) {
            (result.opt("mqtt_discovery") as? String)?.takeIf { it in MQTT_DISCOVERY_VALUES }
                ?: throw PanelAssistantProtocolException("hello result grants mqtt_withdraw without a discovery claim")
        } else {
            null
        }
        val embed = if (CAPABILITY_EMBED_PROOF in capabilities) {
            embedGrant(result.optJSONObject("embed"))
                ?: throw PanelAssistantProtocolException("hello result grants embed_proof without a usable key")
        } else {
            null
        }
        return PanelAssistantHelloOutcome.Accepted(
            PanelAssistantSession(protocol, token, authority, capabilities, integrationVersion, mqttDiscovery, embed,
                io.github.maxlyth.hapaneld.HaConnectionAdvertisement.parse(result.optJSONObject("connection")),
                updatePolicy(result, protocol)),
        )
    }

    /**
     * What the panel's MQTT discovery does after accepting [session], given the [persisted] value from the
     * last session (empty before any). The release is unconditional: any authority but `native` announces,
     * whatever the reply says. Under `native` the rule is keyed on the `mqtt_withdraw` grant, never on
     * whether the field is present: granted, the reply's claim is taken as sent; not granted, as by an
     * integration that predates it, the persisted value is kept, because the registry entries of a panel
     * that has withdrawn belong to the integration and announcing them again would duplicate every entity.
     * A panel that never persisted anything announces. Migration scaffolding; it is deleted with MQTT.
     */
    fun mqttDiscovery(session: PanelAssistantSession, persisted: String): String {
        val claim = session.mqttDiscovery
        return when {
            session.authority != AUTHORITY_NATIVE -> MQTT_DISCOVERY_ANNOUNCE
            CAPABILITY_MQTT_WITHDRAW in session.capabilities && claim != null -> claim
            persisted in MQTT_DISCOVERY_VALUES -> persisted
            else -> MQTT_DISCOVERY_ANNOUNCE
        }
    }

    private fun embedGrant(embed: JSONObject?): PanelAssistantEmbedGrant? {
        val keyId = (embed?.opt("key_id") as? String)?.takeIf(EMBED_KEY_ID::matches) ?: return null
        val key = (embed.opt("key") as? String)?.takeIf(EMBED_KEY::matches) ?: return null
        val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(key) }.getOrNull() ?: return null
        // Forty-three base64url characters are exactly 32 bytes.
        return PanelAssistantEmbedGrant(keyId, bytes)
    }

    /** Interpret an event on the `hello` subscription [helloId]; null for any other frame. */
    fun sessionEvent(frame: JSONObject, helloId: Long): PanelAssistantSessionEvent? {
        if (frame.optString("type") != "event" || messageId(frame) != helloId) return null
        val event = frame.optJSONObject("event") ?: return PanelAssistantSessionEvent.Ignored("")
        val kind = (event.opt("kind") as? String)?.takeIf(CODE::matches).orEmpty()
        if (kind == "command") return command(event)
        if (kind != "session_closed") return PanelAssistantSessionEvent.Ignored(kind)
        val reason = (event.opt("reason") as? String)?.takeIf(CODE::matches).orEmpty()
        return PanelAssistantSessionEvent.Closed(reason)
    }

    private fun command(event: JSONObject): PanelAssistantSessionEvent {
        val commandId = (event.opt("command_id") as? String)?.takeIf(COMMAND_ID::matches)
            ?: return PanelAssistantSessionEvent.MalformedCommand
        val deadline = when (val raw = event.opt("deadline_ms")) {
            null -> DEFAULT_DEADLINE_MS
            is Int -> raw.toLong().takeIf { it in 1..MAX_DEADLINE_MS }
            is Long -> raw.takeIf { it in 1..MAX_DEADLINE_MS }
            else -> null
        }
        return PanelAssistantSessionEvent.Command(
            commandId = commandId,
            session = event.opt("session") as? String,
            channel = (event.opt("channel") as? String)?.takeIf(CODE::matches),
            value = if (event.has("value")) event.opt("value") else null,
            deadlineMs = deadline,
        )
    }
}

internal class PanelAssistantProtocolException(message: String) : RuntimeException(message)
