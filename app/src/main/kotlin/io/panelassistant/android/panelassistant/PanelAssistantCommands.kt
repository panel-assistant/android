package io.panelassistant.android.panelassistant

import android.util.Log
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** What running one native command came to, as the panel's command authority reports it. */
internal sealed interface PanelAssistantCommandResult {
    data object Applied : PanelAssistantCommandResult

    /** A newer command for the same channel replaced this one before it ran. */
    data object Superseded : PanelAssistantCommandResult

    data class Refused(val code: String) : PanelAssistantCommandResult

    data class Failed(val code: String) : PanelAssistantCommandResult

    /** The command needs a physical approval on this panel first; [approvalId] names the broker record. */
    data class ApprovalPending(val approvalId: String) : PanelAssistantCommandResult
}

internal enum class PanelAssistantApprovalState { PENDING, APPROVED, ABSENT }

/**
 * One command admitted by the session, in the payload form the panel's existing command handlers take.
 * [admit] runs on the command worker immediately before the handler: null lets the command run, anything
 * else is its outcome instead (a deadline that elapsed while it was queued, or a session that has ended).
 */
internal class PanelAssistantCommand(
    val channel: String,
    val payload: String,
    val admit: () -> PanelAssistantCommandResult?,
)

/** The panel's ordered command authority and approval broker, as the native transport reaches them. */
internal interface PanelAssistantCommandSink {
    /** Queue [command] beside commands from every other transport; [done] is called at most once, on any thread. */
    fun submit(command: PanelAssistantCommand, done: (PanelAssistantCommandResult) -> Unit)

    fun approvalState(approvalId: String): PanelAssistantApprovalState

    /** Remove a pending approval nobody can use any more, so it cannot be approved by mistake. */
    fun withdrawApproval(approvalId: String)
}

/** Typed wire values back to the payloads the command handlers already parse. */
internal object PanelAssistantCommandTranslation {
    /** Platforms that take commands. Sensors, updates, events and images are read-only. */
    val COMMANDABLE_PLATFORMS: Set<String> = setOf("switch", "camera", "light", "number", "select", "text", "button", "media_player")

    private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val CONTROL = Regex("[\\x00-\\x1f\\x7f]")
    private const val MAX_TEXT_CHARS = 255

    /**
     * The handler payload for [value], or null when the value does not fit the descriptor. [baseUrl] is the
     * address this session reached Home Assistant at; a media URL given as a Home Assistant path resolves
     * against it, as a voice announcement's does.
     */
    fun payload(descriptor: PanelAssistantChannelDescriptor, value: Any?, baseUrl: String? = null): String? = when (descriptor.kind) {
        PanelAssistantValueKind.BOOLEAN -> (value as? Boolean)?.let(::onOff)
        PanelAssistantValueKind.NUMBER -> number(descriptor, value)
        PanelAssistantValueKind.OPTION -> (value as? String)?.let(descriptor::label)
        PanelAssistantValueKind.TEXT -> (value as? String)
            ?.takeIf { it.length <= MAX_TEXT_CHARS && !CONTROL.containsMatchIn(it) }
        PanelAssistantValueKind.LIGHT -> light(descriptor, value)
        PanelAssistantValueKind.UPDATE -> null
        PanelAssistantValueKind.MEDIA -> media(value, baseUrl)
        PanelAssistantValueKind.BUTTON -> if (value == null || value == JSONObject.NULL) "PRESS" else null
    }

    private fun media(value: Any?, baseUrl: String?): String? {
        val json = value as? JSONObject ?: return null
        val path = (json.opt("url") as? String)?.takeIf { it.startsWith("/") && !it.startsWith("//") }
        val resolved = if (path != null && baseUrl != null) {
            JSONObject(json.toString()).put("url", PanelAssistantVoice.resolveUrl(baseUrl, path))
        } else json
        return resolved.takeIf { io.panelassistant.android.media.PanelMediaCommand.parse(it) != null }?.toString()
    }

    private fun onOff(on: Boolean): String = if (on) "ON" else "OFF"

    private fun number(descriptor: PanelAssistantChannelDescriptor, value: Any?): String? {
        val number = (value as? Number)?.takeIf { it.toDouble().isFinite() } ?: return null
        val double = number.toDouble()
        descriptor.min?.let { if (double < it.toDouble()) return null }
        descriptor.max?.let { if (double > it.toDouble()) return null }
        val long = number.toLong()
        return if (long.toDouble() == double) long.toString() else double.toString()
    }

    private fun light(descriptor: PanelAssistantChannelDescriptor, value: Any?): String? {
        val json = value as? JSONObject ?: return null
        val on = json.opt("on") as? Boolean ?: return null
        // Button LEDs take a bare ON/OFF; every other light takes Home Assistant's JSON light schema.
        if (descriptor.family == "button_led") return onOff(on)
        val payload = JSONObject().put("state", onOff(on))
        if (json.has("brightness")) payload.put("brightness", byte(json.opt("brightness")) ?: return null)
        json.opt("color")?.let { raw ->
            val color = raw as? JSONObject ?: return null
            val rgb = JSONObject()
            for (channel in listOf("r", "g", "b")) rgb.put(channel, byte(color.opt(channel)) ?: return null)
            payload.put("color", rgb)
        }
        if (json.has("effect")) {
            val effect = (json.opt("effect") as? String)?.takeIf(CODE::matches)
                ?.takeIf { descriptor.options?.contains(it) == true }
                ?: return null
            payload.put("effect", effect)
        }
        return payload.toString()
    }

    private fun byte(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 0..255 }?.toInt()
        else -> null
    }?.takeIf { it in 0..255 }
}

/**
 * One session's commands: admission, idempotent retry, the approval hold and the answers to send.
 *
 * [onCommand], [pollApprovals], [next] and [close] are called only by the transport owner's session
 * coroutine, which is the only sender on the socket. A command's completion arrives from the command worker
 * on its own thread, so every state change happens under [lock] and wakes the session through [wake].
 */
internal class PanelAssistantCommandProcessor(
    private val sink: PanelAssistantCommandSink,
    private val session: PanelAssistantSession,
    channels: Collection<PanelAssistantChannelDescriptor>,
    private val monotonicMillis: () -> Long,
    private val approvalTtlMs: Long,
    private val approvalPollMs: Long = 1_000L,
    /** The address this session reached Home Assistant at, for media URLs given as paths. */
    private val baseUrl: String? = null,
    private val maxRemembered: Int = 256,
    private val log: (String) -> Unit = { message -> Log.i(PanelAssistantTransportOwner.TAG, message) },
) {
    /** Conflated wake-up for the session coroutine; a spare wake-up costs one loop iteration. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    private val described = channels.associateBy { it.channel }
    private val accepting = session.authority == PanelAssistantTransportProtocol.AUTHORITY_NATIVE &&
        PanelAssistantTransportProtocol.CAPABILITY_COMMANDS in session.capabilities

    private class Request(val channel: String, val payload: String, val deadlineMs: Long)

    private sealed interface State {
        /** Queued or running; [attempt] tells a stale completion from the current run. */
        class Running(val request: Request, val attempt: Int, val approved: Boolean) : State

        class Held(val request: Request, val approvalId: String, val heldAt: Long, val attempt: Int) : State

        class Done(val outcome: String, val code: String?) : State
    }

    private class Answer(val commandId: String, val outcome: String, val code: String?)

    private val lock = Any()
    private val commands = LinkedHashMap<String, State>()
    private val outbox = ArrayDeque<Answer>()
    private var closed = false

    fun onCommand(event: PanelAssistantSessionEvent.Command) {
        val commandId = event.commandId
        synchronized(lock) {
            if (closed) return
            // A command addressed to another session is the reconnect replay of an old one. Nothing may run
            // it, and there is no session left to answer.
            if (event.session != session.token) {
                log("native transport ignored a command for another session")
                return
            }
            when (val known = commands[commandId]) {
                is State.Done -> {
                    outbox.addLast(Answer(commandId, known.outcome, known.code))
                    wake.trySend(Unit)
                    return
                }
                null -> Unit
                // Still running or held: its one final answer is still to come.
                else -> return
            }
        }
        val refusal = when {
            !accepting -> CODE_AUTHORITY_MISMATCH
            event.channel == null || described[event.channel] == null -> CODE_UNKNOWN_CHANNEL
            event.deadlineMs == null -> CODE_INVALID_VALUE
            described.getValue(event.channel).platform !in PanelAssistantCommandTranslation.COMMANDABLE_PLATFORMS ->
                CODE_NOT_COMMANDABLE
            else -> null
        }
        if (refusal != null) {
            synchronized(lock) { finishLocked(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, refusal) }
            return
        }
        val descriptor = described.getValue(requireNotNull(event.channel))
        val payload = PanelAssistantCommandTranslation.payload(descriptor, event.value, baseUrl)
        if (payload == null) {
            synchronized(lock) {
                finishLocked(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, CODE_INVALID_VALUE)
            }
            return
        }
        val request = Request(descriptor.channel, payload, requireNotNull(event.deadlineMs))
        synchronized(lock) {
            if (closed) return
            // A newer command for a channel replaces one still waiting for its approval, exactly as the
            // dispatcher replaces a queued one.
            commands.entries.filter { (it.value as? State.Held)?.request?.channel == request.channel }.forEach {
                val held = it.value as State.Held
                sink.withdrawApproval(held.approvalId)
                finishLocked(it.key, PanelAssistantTransportProtocol.OUTCOME_SUPERSEDED, null)
            }
            commands[commandId] = State.Running(request, attempt = 1, approved = false)
            forgetOldLocked()
        }
        submit(commandId, request, attempt = 1)
    }

    private fun submit(commandId: String, request: Request, attempt: Int) {
        val deadlineAt = monotonicMillis() + request.deadlineMs
        sink.submit(
            PanelAssistantCommand(
                channel = request.channel,
                payload = request.payload,
                admit = {
                    when {
                        synchronized(lock) { closed } -> PanelAssistantCommandResult.Failed(CODE_FAILED)
                        monotonicMillis() > deadlineAt -> PanelAssistantCommandResult.Refused(CODE_EXPIRED)
                        else -> null
                    }
                },
            ),
        ) { result -> onDone(commandId, attempt, result) }
    }

    private fun onDone(commandId: String, attempt: Int, result: PanelAssistantCommandResult) {
        synchronized(lock) {
            val running = commands[commandId] as? State.Running
            if (closed || running == null || running.attempt != attempt) {
                // Nobody holds this approval any more, so it must not stay on the panel's review list.
                if (result is PanelAssistantCommandResult.ApprovalPending) sink.withdrawApproval(result.approvalId)
                return
            }
            when (result) {
                PanelAssistantCommandResult.Applied ->
                    finishLocked(commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, null)
                PanelAssistantCommandResult.Superseded ->
                    finishLocked(commandId, PanelAssistantTransportProtocol.OUTCOME_SUPERSEDED, null)
                is PanelAssistantCommandResult.Refused ->
                    finishLocked(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, result.code)
                is PanelAssistantCommandResult.Failed ->
                    finishLocked(commandId, PanelAssistantTransportProtocol.OUTCOME_FAILED, result.code)
                is PanelAssistantCommandResult.ApprovalPending -> if (running.approved) {
                    // The approval was consumed and asked for again: its record expired between the
                    // approval and the run. One interim answer per command, so this one is final.
                    sink.withdrawApproval(result.approvalId)
                    finishLocked(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, CODE_APPROVAL_TIMEOUT)
                } else {
                    commands[commandId] = State.Held(running.request, result.approvalId, monotonicMillis(), attempt)
                    outbox.addLast(Answer(commandId, PanelAssistantTransportProtocol.OUTCOME_PENDING_APPROVAL, null))
                    wake.trySend(Unit)
                }
            }
        }
    }

    /** Run every held command whose approval was granted, and answer those denied or expired. */
    fun pollApprovals(now: Long) {
        val approved = mutableListOf<Pair<String, State.Running>>()
        synchronized(lock) {
            if (closed) return
            for ((commandId, state) in commands.entries.toList()) {
                val held = state as? State.Held ?: continue
                when (sink.approvalState(held.approvalId)) {
                    PanelAssistantApprovalState.PENDING -> Unit
                    PanelAssistantApprovalState.APPROVED -> {
                        val running = State.Running(held.request, held.attempt + 1, approved = true)
                        commands[commandId] = running
                        approved += commandId to running
                    }
                    // The broker keeps no denied state: a record gone before its lifetime ran out was
                    // denied (or displaced by newer approvals), one gone after it expired.
                    PanelAssistantApprovalState.ABSENT -> finishLocked(
                        commandId,
                        PanelAssistantTransportProtocol.OUTCOME_REFUSED,
                        if (now - held.heldAt >= approvalTtlMs) CODE_APPROVAL_TIMEOUT else CODE_APPROVAL_DENIED,
                    )
                }
            }
        }
        approved.forEach { (commandId, running) -> submit(commandId, running.request, running.attempt) }
    }

    /** When the session should next poll approvals, or null with nothing held. */
    fun nextDeadline(now: Long): Long? = synchronized(lock) {
        if (commands.values.any { it is State.Held }) now + approvalPollMs else null
    }

    /** The next answer to send as message [id], or null. */
    fun next(id: Long): String? {
        val answer = synchronized(lock) { outbox.removeFirstOrNull() } ?: return null
        return PanelAssistantTransportProtocol.commandResult(id, session.token, answer.commandId, answer.outcome, answer.code)
    }

    /**
     * End the session's commands. A held command is dropped and its approval withdrawn, never run on a later
     * session: a command outliving its session is exactly the replay this transport rejects.
     */
    fun close() {
        val withdrawn = synchronized(lock) {
            if (closed) return
            closed = true
            outbox.clear()
            commands.values.mapNotNull { (it as? State.Held)?.approvalId }.also { commands.clear() }
        }
        withdrawn.forEach(sink::withdrawApproval)
    }

    private fun finishLocked(commandId: String, outcome: String, code: String?) {
        commands[commandId] = State.Done(outcome, code)
        outbox.addLast(Answer(commandId, outcome, code))
        forgetOldLocked()
        wake.trySend(Unit)
    }

    /** Keep the newest [maxRemembered] answers for retries; running and held commands are never forgotten. */
    private fun forgetOldLocked() {
        if (commands.size <= maxRemembered) return
        val iterator = commands.entries.iterator()
        while (commands.size > maxRemembered && iterator.hasNext()) {
            if (iterator.next().value is State.Done) iterator.remove()
        }
    }

    companion object {
        const val CODE_UNKNOWN_CHANNEL = "unknown_channel"
        const val CODE_INVALID_VALUE = "invalid_value"
        const val CODE_NOT_COMMANDABLE = "not_commandable"
        const val CODE_AUTHORITY_MISMATCH = "authority_mismatch"
        const val CODE_EXPIRED = "expired"
        const val CODE_APPROVAL_DENIED = "approval_denied"
        const val CODE_APPROVAL_TIMEOUT = "approval_timeout"
        const val CODE_REFUSED_HARDENED = "refused_hardened"
        const val CODE_HARDWARE_UNAVAILABLE = "hardware_unavailable"
        const val CODE_FAILED = "failed"
    }
}
