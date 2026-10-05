package io.panelassistant.android

import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * Typed, bounded representation of the Home Assistant frontend external bus.
 *
 * The V2 envelope supplies one bounded bus object, and this remains its sole inner parser. Unknown message types are
 * retained as harmless values for forward compatibility; malformed or oversized input is rejected
 * without a reply. Outbound callers choose a typed command and cannot supply JavaScript.
 */
internal object ExternalBusProtocol {
    const val MAX_MESSAGE_CHARS = 16 * 1024
    private const val MAX_ERROR_CODE_CHARS = 128
    private const val MAX_ERROR_MESSAGE_CHARS = 512

    internal sealed interface Incoming {
        data class ConfigGet(val id: Int) : Incoming
        data object ConfigScreenShow : Incoming
        data class ConnectionStatus(val event: ConnectionEvent) : Incoming
        data object FrontendLoaded : Incoming
        data object ThemeUpdate : Incoming
        data class Result(
            val id: Int,
            val success: Boolean,
            val error: CommandError?,
        ) : Incoming
        data class Unknown(val type: String?) : Incoming
        data class Malformed(val reason: String) : Incoming
    }

    internal enum class ConnectionEvent(val wireValue: String) {
        CONNECTED("connected"),
        DISCONNECTED("disconnected"),
        AUTH_INVALID("auth-invalid"),
    }

    internal data class CommandError(val code: String?, val message: String?)

    fun parse(raw: String): Incoming {
        if (raw.length > MAX_MESSAGE_CHARS) return Incoming.Malformed("oversized")
        if (!nestingWithinLimit(raw)) return Incoming.Malformed("too-deep")
        val message = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Incoming.Malformed("invalid-json")
        val type = message.opt("type") as? String
            ?: return Incoming.Malformed("missing-type")
        return when (type) {
            "config/get" -> strictId(message)?.let(Incoming::ConfigGet)
                ?: Incoming.Malformed("invalid-id")
            "config_screen/show" -> Incoming.ConfigScreenShow
            "connection-status" -> {
                val event = message.optJSONObject("payload")?.opt("event") as? String
                    ?: message.opt("event") as? String
                ConnectionEvent.entries.firstOrNull { it.wireValue == event }
                    ?.let(Incoming::ConnectionStatus)
                    ?: Incoming.Unknown(type)
            }
            "frontend/loaded" -> Incoming.FrontendLoaded
            "theme-update" -> Incoming.ThemeUpdate
            "result" -> {
                val id = strictId(message) ?: return Incoming.Malformed("invalid-id")
                val success = message.opt("success") as? Boolean
                    ?: return Incoming.Malformed("invalid-success")
                val error = if (success) null else message.optJSONObject("error")?.let {
                    CommandError(
                        code = boundedText(it.opt("code") as? String, MAX_ERROR_CODE_CHARS),
                        message = boundedText(it.opt("message") as? String, MAX_ERROR_MESSAGE_CHARS),
                    )
                }
                Incoming.Result(id, success, error)
            }
            else -> Incoming.Unknown(type)
        }
    }

    fun configResult(id: Int, appVersion: String): String {
        val result = JSONObject()
            .put("hasSettingsScreen", true)
            .put("canWriteTag", false)
            .put("hasExoPlayer", false)
            .put("canCommissionMatter", false)
            .put("canImportThreadCredentials", false)
            .put("hasAssist", false)
            .put("hasBarCodeScanner", 0)
            .put("canSetupImprov", false)
            .put("downloadFileSupported", false)
            .put("hasEntityAddTo", false)
            .put("hasAssistSettings", false)
            .put("appVersion", appVersion)
        return script(
            JSONObject()
                .put("id", id)
                .put("type", "result")
                .put("success", true)
                .put("result", result),
        )
    }

    fun navigate(id: Int, path: String): String {
        val normalized = "/${normalizeDashboardTarget(path)}"
        return script(
            JSONObject()
                .put("id", id)
                .put("type", "command")
                .put("command", "navigate")
                .put(
                    "payload",
                    JSONObject().put("path", normalized).put("options", JSONObject().put("replace", true)),
                ),
        )
    }

    fun setKioskMode(id: Int, enabled: Boolean): String = script(
        JSONObject()
            .put("id", id)
            .put("type", "command")
            .put("command", "kiosk_mode/set")
            .put("payload", JSONObject().put("enable", enabled)),
    )

    private fun script(message: JSONObject): String = "externalBus($message);"

    private fun boundedText(value: String?, maxChars: Int): String? = value
        ?.take(maxChars)
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")

    /** Cheap pre-parse ceiling so a tiny but pathologically deep payload never reaches recursion. */
    private fun nestingWithinLimit(raw: String, maxDepth: Int = 32): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        raw.forEach { char ->
            if (escaped) {
                escaped = false
            } else if (quoted && char == '\\') {
                escaped = true
            } else if (char == '"') {
                quoted = !quoted
            } else if (!quoted && (char == '{' || char == '[')) {
                if (++depth > maxDepth) return false
            } else if (!quoted && (char == '}' || char == ']')) {
                depth--
                if (depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }

    private fun strictId(message: JSONObject): Int? {
        val value = message.opt("id") as? Number ?: return null
        val long = when (value) {
            is Byte, is Short, is Int, is Long -> value.toLong()
            is Float, is Double -> value.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
            else -> null
        } ?: return null
        return long.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    }
}

/** One renderer document's bounded command/result authority. No worker and no polling loop. */
internal class ExternalBusController(
    val pendingLimit: Int = 8,
    val commandTimeoutMs: Long = 5_000L,
    val kioskRetryDelayMs: Long = 1_000L,
) {
    init {
        require(pendingLimit > 0)
        require(commandTimeoutMs > 0)
        require(kioskRetryDelayMs >= 0)
    }

    data class Session(val rendererGeneration: Long, val documentEpoch: Long)

    sealed interface CommandKind {
        data class Navigate(val path: String) : CommandKind
        data class KioskMode(val enabled: Boolean) : CommandKind
    }

    data class Outbound(
        val session: Session,
        val id: Int,
        val kind: CommandKind,
        val script: String,
        val evictedIds: List<Int> = emptyList(),
    )

    data class Completion(
        val matched: Boolean,
        val id: Int? = null,
        val kind: CommandKind? = null,
        val success: Boolean = false,
        val error: ExternalBusProtocol.CommandError? = null,
        val followUp: Outbound? = null,
        val retryKiosk: Boolean = false,
    )

    private data class Pending(val command: Outbound)

    private var current: Session? = null
    private var nextEpoch = 0L
    private var nextId = 0
    private val pending = LinkedHashMap<Int, Pending>()
    private var connected = false
    private var frontendLoaded = false
    private var desiredKiosk = false
    private var appliedKiosk = false
    private var kioskAttempts = 0
    private var kioskRetryWaiting = false

    @Synchronized
    fun beginDocument(rendererGeneration: Long, kioskEnabled: Boolean): Session {
        val session = Session(rendererGeneration, ++nextEpoch)
        current = session
        pending.clear()
        connected = false
        frontendLoaded = false
        desiredKiosk = kioskEnabled
        appliedKiosk = false
        kioskAttempts = 0
        kioskRetryWaiting = false
        return session
    }

    @Synchronized
    fun owns(session: Session): Boolean = current == session

    @Synchronized
    fun invalidate() {
        current = null
        pending.clear()
        connected = false
        frontendLoaded = false
        kioskRetryWaiting = false
    }

    @Synchronized
    fun onConnection(session: Session, isConnected: Boolean): Outbound? {
        if (!owns(session)) return null
        connected = isConnected
        return if (isConnected) maybeIssueKiosk(session, allowRetry = true) else null
    }

    @Synchronized
    fun onFrontendLoaded(session: Session): Outbound? {
        if (!owns(session)) return null
        frontendLoaded = true
        return maybeIssueKiosk(session, allowRetry = true)
    }

    @Synchronized
    fun updateKioskPreference(session: Session, enabled: Boolean): Outbound? {
        if (!owns(session) || desiredKiosk == enabled) return null
        desiredKiosk = enabled
        kioskAttempts = 0
        kioskRetryWaiting = false
        return maybeIssueKiosk(session, allowRetry = true)
    }

    @Synchronized
    fun navigate(session: Session, path: String): Outbound? {
        if (!owns(session)) return null
        return issue(session, CommandKind.Navigate(path))
    }

    @Synchronized
    fun onResult(session: Session, result: ExternalBusProtocol.Incoming.Result): Completion {
        if (!owns(session)) return Completion(matched = false)
        val command = pending.remove(result.id)?.command ?: return Completion(matched = false)
        val kind = command.kind
        if (kind !is CommandKind.KioskMode) {
            return Completion(true, result.id, kind, result.success, result.error)
        }

        if (result.success) appliedKiosk = kind.enabled
        if (kind.enabled != desiredKiosk) {
            val followUp = maybeIssueKiosk(session, allowRetry = true)
            return Completion(true, result.id, kind, result.success, result.error, followUp = followUp)
        }
        if (result.success) {
            kioskRetryWaiting = false
            return Completion(true, result.id, kind, success = true)
        }
        kioskRetryWaiting = kioskAttempts < MAX_KIOSK_ATTEMPTS
        return Completion(
            matched = true,
            id = result.id,
            kind = kind,
            success = false,
            error = result.error,
            retryKiosk = kioskRetryWaiting,
        )
    }

    @Synchronized
    fun onTimeout(session: Session, id: Int): Completion {
        if (!owns(session)) return Completion(matched = false)
        val command = pending.remove(id)?.command ?: return Completion(matched = false)
        val kind = command.kind
        val retry = kind is CommandKind.KioskMode && kind.enabled == desiredKiosk &&
            kioskAttempts < MAX_KIOSK_ATTEMPTS
        kioskRetryWaiting = retry
        return Completion(true, id, kind, success = false, retryKiosk = retry)
    }

    @Synchronized
    fun retryKiosk(session: Session): Outbound? {
        if (!owns(session) || !kioskRetryWaiting) return null
        if (!connected || !frontendLoaded) return null
        kioskRetryWaiting = false
        return maybeIssueKiosk(session, allowRetry = true)
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    private fun maybeIssueKiosk(session: Session, allowRetry: Boolean): Outbound? {
        if (!connected || !frontendLoaded || appliedKiosk == desiredKiosk) return null
        if (pending.values.any { it.command.kind is CommandKind.KioskMode }) return null
        if (kioskRetryWaiting && !allowRetry) return null
        if (kioskAttempts >= MAX_KIOSK_ATTEMPTS) return null
        kioskRetryWaiting = false
        kioskAttempts++
        return issue(session, CommandKind.KioskMode(desiredKiosk))
    }

    private fun issue(session: Session, kind: CommandKind): Outbound? {
        val evicted = ArrayList<Int>(1)
        while (pending.size >= pendingLimit) {
            val evictable = pending.entries.firstOrNull { it.value.command.kind is CommandKind.Navigate }
                ?: return null
            pending.remove(evictable.key)
            evicted += evictable.key
        }
        val id = nextCommandId()
        val script = when (kind) {
            is CommandKind.Navigate -> ExternalBusProtocol.navigate(id, kind.path)
            is CommandKind.KioskMode -> ExternalBusProtocol.setKioskMode(id, kind.enabled)
        }
        return Outbound(session, id, kind, script, evicted).also { pending[id] = Pending(it) }
    }

    private fun nextCommandId(): Int {
        do {
            nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
        } while (nextId in pending)
        return nextId
    }

    private companion object {
        const val MAX_KIOSK_ATTEMPTS = 2
    }
}
