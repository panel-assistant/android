package io.panelassistant.android.panelassistant

import io.panelassistant.android.sensors.HaLifecycleNotice
import io.panelassistant.android.sensors.HaLifecyclePhase
import io.panelassistant.android.sensors.HaLifecycleReason
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
    val capabilities: List<String>,
    val integrationVersion: String,
    val connection: io.panelassistant.android.HaConnectionAdvertisement? = null,
    val lifecycle: HaLifecycleNotice? = null,
    val updatePolicy: PanelAssistantUpdatePolicy? = null,
    /** Panel Assistant's voice stream pairing, present exactly when the session granted `voice_stream_session`. */
    val voiceStream: PanelAssistantVoiceStreamGrant? = null,
    /**
     * The Panel Assistant config entry this panel belongs to, which names its device
     * `(panel_assistant, <entry_id>)`. Optional: an integration older than 0.9.0 omits it, and a malformed
     * value is treated as absent, so the panel falls back to proving the entry through the entity registry.
     */
    val entryId: String? = null,
    /** Approximate visible-state total; older integrations omit this advisory field. */
    val expectedEntityCount: Int? = null,
) {
    /** The session token is a bearer for this session's requests; keep it out of logs. */
    override fun toString(): String =
        "PanelAssistantSession(protocol=$protocol, capabilities=$capabilities, integrationVersion=$integrationVersion)"
}

/**
 * Panel Assistant's voice stream for this panel (`hello` result field `voice_stream_session`): the Sendspin
 * server's public key the panel must find over the session, and the long-term pairing key the two share.
 * The key is a secret: never logged.
 */
internal class PanelAssistantVoiceStreamGrant(val serverId: String, psk: ByteArray) {
    private val psk = psk.copyOf()

    fun psk(): ByteArray = psk.copyOf()

    override fun toString(): String = "PanelAssistantVoiceStreamGrant(serverId=$serverId)"
}

internal sealed interface PanelAssistantHelloOutcome {
    data class Accepted(val session: PanelAssistantSession) : PanelAssistantHelloOutcome

    /** An error result; [code] is Core's or the integration's closed code, never display text. */
    data class Refused(val code: String) : PanelAssistantHelloOutcome
}

/**
 * The browser a sidebar relay request came from (protocol section 20.2): the Home Assistant [user] Panel Assistant
 * checked, and the relay [connection] that carries that browser's requests. Either is null when the event's
 * `origin` was present but malformed, which no relayed request may run with.
 */
internal data class PanelAssistantRelayOrigin(val user: String?, val connection: String?) {
    val valid: Boolean get() = user != null && connection != null
}

/** An event delivered on the `hello` subscription. */
internal sealed interface PanelAssistantSessionEvent {
    data class Closed(val reason: String) : PanelAssistantSessionEvent

    data class Lifecycle(val notice: HaLifecycleNotice) : PanelAssistantSessionEvent

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
        /** Present when Panel Assistant relays this command from the sidebar; absent for its own. */
        val origin: PanelAssistantRelayOrigin? = null,
    ) : PanelAssistantSessionEvent

    /**
     * A management request on a session that granted `management`: [op] `snapshot`, `settings`, or a name
     * per REST path (protocol section 20.3). Its [session], [commandId] and [deadlineMs] mean what a
     * command's do; [settings] is null when absent or malformed; [args] is the request's arguments, null
     * when absent or not an object.
     */
    data class Manage(
        val commandId: String,
        val session: String?,
        val op: String,
        val settings: Map<String, String>?,
        val deadlineMs: Long?,
        val args: JSONObject? = null,
        /** Present when Panel Assistant relays this request from the sidebar; absent for its own. */
        val origin: PanelAssistantRelayOrigin? = null,
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
    // Protocol 5 is the 1.0 protocol. Panel Assistant offers and installs a build only when its range
    // overlaps this one, so a Panel Assistant serving protocol 4 at most (0.9.0 and earlier) never
    // installs this build.
    const val PROTOCOL_MIN = 5
    const val PROTOCOL_MAX = 5
    const val COMMAND_HELLO = "panel_assistant/hello"
    const val COMMAND_REPORT_STATE = "panel_assistant/report_state"
    const val COMMAND_COMMAND_RESULT = "panel_assistant/command_result"
    const val COMMAND_RESTART_NOTICE = "panel_assistant/restart_notice"
    const val COMMAND_REPORT_EVENT = "panel_assistant/report_event"

    const val CAPABILITY_STATE = "state"
    const val CAPABILITY_COMMANDS = "commands"
    const val CAPABILITY_APPROVAL = "approval"

    /** The panel is an Assist satellite on this session ([PanelAssistantVoice]). */
    const val CAPABILITY_VOICE = "voice"

    /** The integration renders the `media` channel. */
    const val CAPABILITY_MEDIA = "media"

    /**
     * The panel plays voice from Panel Assistant's Sendspin stream, in step with the other panels, carried
     * inside this session. Offered with the panel's client id in the hello's `voice_stream_session` object;
     * granting it makes the reply's `voice_stream_session` grant required.
     */
    const val CAPABILITY_VOICE_STREAM_SESSION = "voice_stream_session"

    /**
     * Panel Assistant reads this panel's health and status, and writes its settings, on the session
     * ([PanelAssistantManagement]) rather than by opening a connection back to it.
     */
    const val CAPABILITY_MANAGEMENT = "management"
    /**
     * Protocol section 20.4: the panel sends a relayed subscription's updates as `panel_assistant/ui_update`,
     * which a Panel Assistant granting it registers.
     */
    const val CAPABILITY_UI_SUBSCRIBE = "ui_subscribe"

    /** Protocol section 20.5: Panel Assistant keeps this panel's bundle, which the `bundle` op uploads. */
    const val CAPABILITY_UI_BUNDLE = "ui_bundle"

    /**
     * The panel can serve only its stub (protocol section 20.7). A Panel Assistant grants it only once it makes no
     * REST call to this panel, and the grant closes the REST routes ([io.panelassistant.android.Config.uiRoutesClosed]).
     */
    const val CAPABILITY_REST_RETIRED = "rest_retired"

    /**
     * The integration accepts observation state `unknown` with a `reason` (section 7). Without the grant the panel
     * sends a sensor with no reading as unavailable, since an older integration refuses the whole `report_state`.
     */
    const val CAPABILITY_UNKNOWN_STATE = "unknown_state"
    const val COMMAND_UI_UPDATE = "panel_assistant/ui_update"
    const val MANAGE_BUNDLE = "bundle"
    const val MANAGE_SNAPSHOT = "snapshot"
    const val MANAGE_SETTINGS = "settings"
    /** Read the Area from Home Assistant again now; it carries no value, the panel keeps its own rule. */
    const val MANAGE_HA_AREA_REFRESH = "ha_area.refresh"

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
    /** A sensor with no reading, sent with its `reason` code only to a session granting [CAPABILITY_UNKNOWN_STATE]. */
    const val STATE_UNKNOWN = "unknown"

    const val CODE_UNKNOWN_COMMAND = "unknown_command"
    const val CODE_UNKNOWN_PANEL = "unknown_panel"
    const val CODE_PROTOCOL_UNSUPPORTED = "protocol_unsupported"
    const val CODE_PANEL_USER_MISMATCH = "panel_user_mismatch"
    const val CODE_PANEL_IDENTITY_UNAVAILABLE = "panel_identity_unavailable"
    const val CODE_INVALID_FORMAT = "invalid_format"
    const val CODE_SESSION_UNKNOWN = "session_unknown"

    /** The entry that held this panel was removed; the panel retries slowly until it is added again. */
    const val CODE_ENTRY_REMOVED = "entry_removed"

    const val REASON_SUPERSEDED = "superseded"

    /**
     * Capabilities a fully wired build serves. Advertising a capability it cannot serve would leave the
     * integration waiting.
     */
    val CAPABILITIES: List<String> =
        listOf(
            CAPABILITY_STATE,
            CAPABILITY_COMMANDS,
            CAPABILITY_APPROVAL,
            CAPABILITY_VOICE,
            CAPABILITY_MEDIA,
            CAPABILITY_VOICE_STREAM_SESSION,
            CAPABILITY_MANAGEMENT,
            CAPABILITY_UI_SUBSCRIBE,
            CAPABILITY_UI_BUNDLE,
            CAPABILITY_REST_RETIRED,
            CAPABILITY_UNKNOWN_STATE,
        )

    /**
     * The handshake contract this build implements, in canonical form. The specification's shared
     * contract file does not exist yet; until it does, the digest the panel sends covers exactly this
     * text, so a change to the handshake vocabulary changes the digest the integration records.
     */
    internal const val CANONICAL_CONTRACT: String =
        """{"protocol":{"min":5,"max":5},"commands":["panel_assistant/hello","panel_assistant/report_state","panel_assistant/command_result","panel_assistant/restart_notice","panel_assistant/voice_configuration","panel_assistant/voice_run","panel_assistant/voice_played","panel_assistant/voice_stream_frame","panel_assistant/voice_stream_stop","panel_assistant/ui_update"],"capabilities":["state","commands","approval","voice","media","voice_stream_session","management","ui_subscribe","ui_bundle","rest_retired","unknown_state"]}"""

    val CONTRACT_DIGEST: String = MessageDigest.getInstance("SHA-256")
        .digest(CANONICAL_CONTRACT.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    /** A management op: a code, or a REST path's name with `.` separators (section 20.3). */
    private val OP = Regex("^[a-z][a-z0-9_.]{0,63}$")
    private val COMMAND_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$")
    private val ENTRY_ID = Regex("^[0-9A-Za-z_-]{1,64}$")
    private val KEY_43 = Regex("^[A-Za-z0-9_-]{43}$")
    private const val MAX_SESSION_TOKEN_CHARS = 64
    private const val MAX_CAPABILITIES = 16

    fun hello(
        id: Long,
        identity: PanelAssistantHelloIdentity,
        capabilities: List<String> = emptyList(),
        channels: List<PanelAssistantChannelDescriptor> = emptyList(),
        unsupported: List<String> = emptyList(),
        addresses: List<String> = emptyList(),
        /** The panel's Sendspin client id, sent exactly when [capabilities] offers `voice_stream_session`. */
        voiceStreamClientId: String? = null,
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
        .apply {
            if (voiceStreamClientId != null && CAPABILITY_VOICE_STREAM_SESSION in capabilities) {
                put("voice_stream_session", JSONObject().put("client_id", voiceStreamClientId))
            }
        }
        .toString()

    fun reportState(id: Long, session: String, sync: String, observations: JSONArray): String = JSONObject()
        .put("id", id)
        .put("type", COMMAND_REPORT_STATE)
        .put("session", session)
        .put("sync", sync)
        .put("observations", observations)
        .toString()

    /** One transient event; Home Assistant counts each [eventId] once per session, so ids only grow. */
    fun reportEvent(id: Long, session: String, channel: String, eventId: Long, eventType: String): String =
        JSONObject().put("id", id).put("type", COMMAND_REPORT_EVENT).put("session", session)
            .put("channel", channel).put("event_id", eventId).put("event_type", eventType).toString()

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

    /** The one final (or `pending_approval` interim) answer to a delivered command or management request. */
    fun commandResult(
        id: Long,
        session: String,
        commandId: String,
        outcome: String,
        code: String?,
        result: Any? = null,
        /** A relayed request's answer also carries what the `/ui` frame carries: the route's status, headers, interim. */
        extra: JSONObject? = null,
    ): String =
        JSONObject()
            .put("id", id)
            .put("type", COMMAND_COMMAND_RESULT)
            .put("session", session)
            .put("command_id", commandId)
            .put("outcome", outcome)
            .apply { if (code != null) put("code", code) }
            .apply { if (result != null) put("result", result) }
            .apply { extra?.keys()?.forEach { key -> put(key, extra.get(key)) } }
            .toString()

    /** One relayed subscription's update (protocol section 20.4): the `/ui` frame's update with `type` and `session`. */
    fun uiUpdate(id: Long, session: String, update: JSONObject): String =
        JSONObject(update.toString()).put("id", id).put("type", COMMAND_UI_UPDATE).put("session", session).toString()

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
        val voiceStream = if (CAPABILITY_VOICE_STREAM_SESSION in capabilities) {
            voiceStreamGrant(result.optJSONObject("voice_stream_session"))
                ?: throw PanelAssistantProtocolException("hello result grants voice_stream_session without a usable grant")
        } else {
            null
        }
        return PanelAssistantHelloOutcome.Accepted(
            PanelAssistantSession(protocol, token, capabilities, integrationVersion,
                io.panelassistant.android.HaConnectionAdvertisement.parse(result.optJSONObject("connection")),
                lifecycleNotice(result.optJSONObject("lifecycle")),
                updatePolicy(result, protocol),
                voiceStream,
                (result.opt("entry_id") as? String)?.takeIf(ENTRY_ID::matches),
                (result.opt("expected_entity_count") as? Int)?.takeIf { it > 0 }),
        )
    }

    private fun voiceStreamGrant(grant: JSONObject?): PanelAssistantVoiceStreamGrant? {
        val serverId = (grant?.opt("server_id") as? String)?.takeIf(KEY_43::matches) ?: return null
        val psk = (grant.opt("psk") as? String)?.takeIf(KEY_43::matches) ?: return null
        // Forty-three base64url characters are exactly 32 bytes, as a Sendspin key and PSK are.
        val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(psk) }.getOrNull() ?: return null
        if (runCatching { java.util.Base64.getUrlDecoder().decode(serverId) }.isFailure) return null
        return PanelAssistantVoiceStreamGrant(serverId, bytes)
    }

    /** Interpret an event on the `hello` subscription [helloId]; null for any other frame. */
    fun sessionEvent(frame: JSONObject, helloId: Long): PanelAssistantSessionEvent? {
        if (frame.optString("type") != "event" || messageId(frame) != helloId) return null
        val event = frame.optJSONObject("event") ?: return PanelAssistantSessionEvent.Ignored("")
        val kind = (event.opt("kind") as? String)?.takeIf(CODE::matches).orEmpty()
        if (kind == "command") return command(event)
        if (kind == "manage") return manage(event)
        if (kind == "lifecycle") return lifecycleNotice(event)?.let { PanelAssistantSessionEvent.Lifecycle(it) }
            ?: PanelAssistantSessionEvent.Ignored(kind)
        if (kind != "session_closed") return PanelAssistantSessionEvent.Ignored(kind)
        val reason = (event.opt("reason") as? String)?.takeIf(CODE::matches).orEmpty()
        return PanelAssistantSessionEvent.Closed(reason)
    }

    /** Optional additive evidence: a malformed or future notice never rejects an otherwise valid session. */
    private fun lifecycleNotice(body: JSONObject?): HaLifecycleNotice? {
        if (body == null) return null
        val phase = when (body.opt("phase")) {
            "shutting_down" -> HaLifecyclePhase.SHUTTING_DOWN
            "starting" -> HaLifecyclePhase.STARTING
            "ready" -> HaLifecyclePhase.READY
            else -> return null
        }
        val reason = when (body.opt("reason")) {
            "restart" -> HaLifecycleReason.RESTART
            "host_reboot" -> HaLifecycleReason.HOST_REBOOT
            "core_update" -> HaLifecycleReason.CORE_UPDATE
            "unknown" -> HaLifecycleReason.UNKNOWN
            else -> return null
        }
        fun duration(key: String): Long? = when (val raw = body.opt(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        }
        val elapsed = duration("elapsed_ms")
        val expected = duration("expected_ms")
        if (!body.has("elapsed_ms") || !body.has("expected_ms")) return null
        if ((body.opt("elapsed_ms") !== JSONObject.NULL && elapsed == null) ||
            (body.opt("expected_ms") !== JSONObject.NULL && expected == null) ||
            (elapsed != null && elapsed < 0) || (expected != null && expected <= 0)) return null
        return HaLifecycleNotice(phase, reason, elapsed, expected)
    }

    private fun deadline(event: JSONObject): Long? = when (val raw = event.opt("deadline_ms")) {
        null -> DEFAULT_DEADLINE_MS
        is Int -> raw.toLong().takeIf { it in 1..MAX_DEADLINE_MS }
        is Long -> raw.takeIf { it in 1..MAX_DEADLINE_MS }
        else -> null
    }

    private fun command(event: JSONObject): PanelAssistantSessionEvent {
        val commandId = (event.opt("command_id") as? String)?.takeIf(COMMAND_ID::matches)
            ?: return PanelAssistantSessionEvent.MalformedCommand
        return PanelAssistantSessionEvent.Command(
            commandId = commandId,
            session = event.opt("session") as? String,
            channel = (event.opt("channel") as? String)?.takeIf(CODE::matches),
            value = if (event.has("value")) event.opt("value") else null,
            deadlineMs = deadline(event),
            origin = relayOrigin(event),
        )
    }

    private fun relayOrigin(event: JSONObject): PanelAssistantRelayOrigin? {
        if (!event.has("origin")) return null
        val origin = event.optJSONObject("origin")
        return PanelAssistantRelayOrigin(
            (origin?.opt("user") as? String)?.takeIf(COMMAND_ID::matches),
            (origin?.opt("connection") as? String)?.takeIf(COMMAND_ID::matches),
        )
    }

    private fun manage(event: JSONObject): PanelAssistantSessionEvent {
        val commandId = (event.opt("command_id") as? String)?.takeIf(COMMAND_ID::matches)
            ?: return PanelAssistantSessionEvent.MalformedCommand
        val settings = event.optJSONObject("settings")?.let { json ->
            json.keys().asSequence().associateWith { key -> json.opt(key) as? String ?: return@let null }
        }
        return PanelAssistantSessionEvent.Manage(
            commandId = commandId,
            session = event.opt("session") as? String,
            op = (event.opt("op") as? String)?.takeIf(OP::matches).orEmpty(),
            settings = settings,
            deadlineMs = deadline(event),
            args = event.optJSONObject("args"),
            origin = relayOrigin(event),
        )
    }
}

internal class PanelAssistantProtocolException(message: String) : RuntimeException(message)
