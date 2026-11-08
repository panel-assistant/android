package io.panelassistant.android.panelassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The `/health` line and the `/api/v1/status` body, exactly as HTTP serves them. */
internal data class PanelAssistantManagementSnapshot(val health: String, val status: String)

/** What the panel serves when Panel Assistant manages it over its own session. */
internal interface PanelAssistantManagement {
    val localAdb: PanelAssistantLocalAdb? get() = null

    suspend fun snapshot(): PanelAssistantManagementSnapshot

    /**
     * Apply [settings] through the panel's own settings store; null when applied, else an outcome code.
     * [admit] is asked once the store's locks are held, just before the commit: a request whose session ended
     * or whose deadline passed while it queued commits nothing.
     */
    suspend fun applySettings(settings: Map<String, String>, admit: () -> Boolean): String?

    /**
     * Run the request named [op] with [args], a name per REST path (protocol section 20.3); null when this
     * build serves no such name. [onPending] sends the interim `pending_approval` answer while a sensitive
     * request waits for its approval on the panel.
     */
    suspend fun request(op: String, args: JSONObject, onPending: () -> Unit): PanelAssistantManageReply?

    /** The dispatcher a sidebar-relayed request enters, as a `/ui` frame does (protocol section 20.2); null while none runs. */
    fun door(): io.panelassistant.android.ManageDoor? = null

    /** Where a relayed request's bodies move (section 20.5); null moves none. */
    val blobs: PanelAssistantBlobs? get() = null

    companion object {
        fun of(
            read: suspend () -> PanelAssistantManagementSnapshot,
            write: suspend (Map<String, String>, () -> Boolean) -> String?,
            door: () -> io.panelassistant.android.ManageDoor? = { null },
            blobs: PanelAssistantBlobs? = null,
            localAdb: PanelAssistantLocalAdb? = null,
            serve: suspend (String, JSONObject, () -> Unit) -> PanelAssistantManageReply? = { _, _, _ -> null },
        ): PanelAssistantManagement = object : PanelAssistantManagement {
            override val localAdb = localAdb
            override suspend fun snapshot() = read()

            override suspend fun applySettings(settings: Map<String, String>, admit: () -> Boolean) = write(settings, admit)

            override suspend fun request(op: String, args: JSONObject, onPending: () -> Unit) = serve(op, args, onPending)

            override fun door() = door()

            override val blobs: PanelAssistantBlobs? = blobs
        }
    }
}

/** A named request's final answer: its outcome, the refusal code, and the result body. */
/** [result] is the body a route returns (protocol section 20.2): a JSON object or array, a string for text, or null. */
internal data class PanelAssistantManageReply(val outcome: String, val code: String?, val result: Any?)

/**
 * The settings Panel Assistant may write over the session, and the order they are checked in.
 *
 * The list is a security boundary. The session reaches the settings store without an HTTP request, so
 * none of the HTTP admission that binds a sensitive change to an approval on the panel applies here.
 * Every key on it must be one that admission never holds for approval; adding a key that it does
 * reopens that question.
 */
internal object PanelAssistantManagedSettings {
    // voice_enabled: enabling the panel's Assist satellite entity in Home Assistant turns voice on.
    // friendly_name: renaming the panel's device in Home Assistant renames the panel.
    val KEYS: Set<String> = setOf("voice_enabled", "voice_wake_words", "friendly_name")

    /**
     * [validate] is the Configure page's own admission, returning the normalized values or null when it
     * refuses them; [commit] is the store's accepted-settings commit, returning whether it applied, and asks
     * [admit] at its own commit boundary.
     */
    suspend fun apply(
        settings: Map<String, String>,
        admit: () -> Boolean,
        validate: (Map<String, String>) -> Map<String, String>?,
        commit: suspend (Map<String, String>, () -> Boolean) -> Boolean,
    ): String? {
        if (settings.isEmpty() || !KEYS.containsAll(settings.keys)) return PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE
        val accepted = validate(settings) ?: return PanelAssistantCommandProcessor.CODE_INVALID_VALUE
        return when {
            commit(accepted, admit) -> null
            !admit() -> PanelAssistantCommandProcessor.CODE_EXPIRED
            else -> PanelAssistantCommandProcessor.CODE_FAILED
        }
    }
}

/**
 * One session's management requests: the session check, duplicate suppression and deadline that commands
 * use, answered with a `command_result` whose `result` carries what a snapshot read.
 *
 * A request or command the sidebar relays (it carries an `origin`) runs instead as that browser's Home Assistant
 * user, through the same [io.panelassistant.android.http.UiFrames] handling as a `/ui` frame, one per relay
 * connection; its answers carry the frame's `status`, `headers` and `interim`, its subscription updates leave as
 * `panel_assistant/ui_update`, and every relayed connection closes with the session.
 *
 * [onRequest] and [next] are called only by the session coroutine. Each request runs as a child of
 * [parent] until [close], which the session calls as it ends, so no request outlives its session;
 * completion arrives on another thread, under [lock].
 */
internal class PanelAssistantManagementRequests(
    parent: CoroutineScope,
    private val management: PanelAssistantManagement,
    private val session: PanelAssistantSession,
    private val monotonicMillis: () -> Long,
    private val maxRemembered: Int = 64,
) {
    val wake = Channel<Unit>(Channel.CONFLATED)
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    private class Answer(val commandId: String, val outcome: String, val code: String?, val result: Any?)

    /** One message to send: built with its message id, then [after] once it has left. */
    private class Outgoing(val after: () -> Unit = {}, val build: (Long) -> String)

    private val lock = Any()
    @Volatile private var closed = false
    /** Answers by command ID, kept for repeats; a running request maps to null. */
    private val answers = LinkedHashMap<String, Answer?>()
    private val outbox = ArrayDeque<Outgoing>()
    /** The relay connections this session carries, by user and connection. */
    private val relays = HashMap<String, Relay>()

    private fun enqueue(outgoing: Outgoing) {
        synchronized(lock) { outbox.addLast(outgoing) }
        wake.trySend(Unit)
    }

    private fun enqueue(answer: Answer) = enqueue(Outgoing { id ->
        PanelAssistantTransportProtocol.commandResult(id, session.token, answer.commandId, answer.outcome, answer.code, answer.result)
    })

    fun onRequest(event: PanelAssistantSessionEvent.Manage) {
        if (event.op.startsWith("adb.") && event.origin != null) {
            if (event.session == session.token) enqueue(Answer(event.commandId, "refused", "not_commandable", null))
            return
        }
        if (event.origin != null) {
            return relay(event.session, event.commandId, event.origin) {
                JSONObject().put("kind", "manage").put("command_id", event.commandId).put("op", event.op)
                    .put("args", event.args ?: JSONObject())
            }
        }
        val receivedAt = monotonicMillis()
        synchronized(lock) {
            // A request for another session is a replay of an old one: nothing may run it.
            if (event.session != session.token) return
            if (answers.containsKey(event.commandId)) {
                answers[event.commandId]?.let { enqueue(it) }
                return
            }
            answers[event.commandId] = null
        }
        scope.launch {
            val answer = try {
                run(event, receivedAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_FAILED, PanelAssistantCommandProcessor.CODE_FAILED, null)
            }
            synchronized(lock) {
                answers[event.commandId] = answer
                while (answers.size > maxRemembered) {
                    val oldest = answers.entries.firstOrNull { it.value != null } ?: break
                    answers.remove(oldest.key)
                }
            }
            enqueue(answer)
        }
    }

    /** A channel command the sidebar relays (it carries an `origin`). */
    fun onRelayed(event: PanelAssistantSessionEvent.Command) {
        val origin = event.origin ?: return
        relay(event.session, event.commandId, origin) {
            JSONObject().put("kind", "command").put("command_id", event.commandId).put("channel", event.channel.orEmpty())
                .apply { event.value?.let { put("value", it) } }
                .put("deadline_ms", event.deadlineMs ?: PanelAssistantTransportProtocol.DEFAULT_DEADLINE_MS)
        }
    }

    private fun relay(eventSession: String?, commandId: String, origin: PanelAssistantRelayOrigin, request: () -> JSONObject) {
        // A request for another session is a replay of an old one: nothing may run it.
        if (eventSession != session.token || closed) return
        val user = origin.user
        val connection = origin.connection
        // A malformed origin never runs, and never as Panel Assistant itself.
        if (user == null || connection == null) {
            return enqueue(Answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantTransportProtocol.CODE_INVALID_FORMAT, null))
        }
        val door = management.door()
            ?: return enqueue(Answer(commandId, PanelAssistantTransportProtocol.OUTCOME_FAILED, PanelAssistantCommandProcessor.CODE_FAILED, null))
        val key = "$user\u0000$connection"
        var created = false
        val relay = synchronized(lock) {
            if (closed) return
            relays.getOrPut(key) {
                created = true
                Relay(io.panelassistant.android.CommandOrigin.user(user).withConnection(connection), door)
            }
        }
        // The connection belongs to this dispatcher generation, as a stub socket does: when a runtime replacement
        // stops it, the connection ends and its next request opens one on the live dispatcher.
        if (created) relay.stopped = door.onStopped {
            synchronized(lock) { if (relays[key] === relay) relays.remove(key) }
            relay.frames.close()
        }
        relay.handle(request())
    }

    /** One relay connection: the `/ui` frame handling, and the upload token each request in flight carries. */
    private inner class Relay(origin: io.panelassistant.android.CommandOrigin, door: io.panelassistant.android.ManageDoor) {
        private val carriers = java.util.concurrent.ConcurrentHashMap<String, PanelAssistantRelayBlobs>()
        val frames = io.panelassistant.android.http.UiFrames(door, origin, scope) { frame, after -> leave(frame, after) }
        @Volatile var stopped: AutoCloseable? = null

        fun handle(request: JSONObject) {
            val args = request.optJSONObject("args")
            // The upload token is the transfer's, never an argument of the operation.
            val token = args?.remove("blob_token")?.let(PanelAssistantBlobs::validToken)
            // A repeat of a command still in flight joins its run, so it keeps that run's carrier and the body it holds.
            val carrier = management.blobs?.let { blobs ->
                carriers.computeIfAbsent(request.optString("command_id")) { PanelAssistantRelayBlobs(blobs, token) }
            }
            frames.handle(request, carrier)
        }

        private fun leave(frame: JSONObject, after: () -> Unit) {
            if (!frame.has("command_id")) {
                return enqueue(Outgoing(after) { id -> PanelAssistantTransportProtocol.uiUpdate(id, session.token, frame) })
            }
            val commandId = frame.getString("command_id")
            val interim = frame.optBoolean("interim")
            val carrier = if (interim) carriers[commandId] else carriers.remove(commandId)?.also { it.release() }
            val result = frame.opt("result")
            val text = when (result) {
                null -> null
                is String -> result
                else -> result.toString()
            }
            // A result over the frame limit moves as a blob through the request's own token (section 20.5).
            if (!interim && text != null && carrier?.canUpload == true && text.toByteArray(Charsets.UTF_8).size > INLINE_RESULT_BYTES) {
                scope.launch {
                    // No parameters: Panel Assistant serves only a bare media type, so a charset would turn the text into bytes.
                    val type = if (result is String) "text/plain" else "application/json"
                    val reference = runCatching { carrier.put(text.toByteArray(Charsets.UTF_8), type) }.getOrNull()
                    if (reference != null) {
                        frame.put("result", JSONObject().put("blob", reference))
                    } else {
                        frame.put("outcome", PanelAssistantTransportProtocol.OUTCOME_FAILED).put("code", PanelAssistantCommandProcessor.CODE_FAILED).remove("result")
                    }
                    answer(frame, after)
                }
                return
            }
            answer(frame, after)
        }

        private fun answer(frame: JSONObject, after: () -> Unit) = enqueue(Outgoing(after) { id ->
            val extra = JSONObject()
            for (key in listOf("status", "headers", "interim")) if (frame.has(key)) extra.put(key, frame.get(key))
            PanelAssistantTransportProtocol.commandResult(
                id, session.token, frame.getString("command_id"), frame.getString("outcome"),
                frame.opt("code") as? String, frame.opt("result"), extra.takeIf { it.length() > 0 },
            )
        })
    }

    private suspend fun run(event: PanelAssistantSessionEvent.Manage, receivedAt: Long): Answer {
        fun refused(code: String) = Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, code, null)
        val deadline = event.deadlineMs ?: return refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
        if (monotonicMillis() - receivedAt > deadline) return refused(PanelAssistantCommandProcessor.CODE_EXPIRED)
        if (event.op.startsWith("adb.")) {
            if (PanelAssistantTransportProtocol.CAPABILITY_LOCAL_ADB !in session.capabilities) return refused("not_commandable")
            if (event.op == "adb.cmd" && PanelAssistantTransportProtocol.CAPABILITY_LOCAL_ADB_COMMANDS !in session.capabilities) return refused("not_commandable")
            val channel = management.localAdb ?: return refused("not_commandable")
            var waitingForApproval = false
            val reply = channel.request(event.op, event.args ?: JSONObject(),
                { !closed && monotonicMillis() - receivedAt <= deadline + if (waitingForApproval) 600_000L else 0L },
                {
                    waitingForApproval = true
                    enqueue(Answer(event.commandId, "pending_approval", null, null))
                },
            )
            return Answer(event.commandId, reply.outcome, reply.code, reply.result)
        }
        return when (event.op) {
            PanelAssistantTransportProtocol.MANAGE_SNAPSHOT -> {
                val snapshot = management.snapshot()
                Answer(
                    event.commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, null,
                    JSONObject().put("health", snapshot.health).put("status", snapshot.status),
                )
            }
            PanelAssistantTransportProtocol.MANAGE_SETTINGS -> {
                val settings = event.settings ?: return refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
                val admit = { !closed && monotonicMillis() - receivedAt <= deadline }
                when (val code = management.applySettings(settings, admit)) {
                    null -> Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, null, null)
                    PanelAssistantCommandProcessor.CODE_FAILED ->
                        Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_FAILED, code, null)
                    else -> refused(code)
                }
            }
            else -> {
                val reply = management.request(event.op, event.args ?: JSONObject()) {
                    enqueue(Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_PENDING_APPROVAL, null, null))
                } ?: return refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_COMMAND)
                Answer(event.commandId, reply.outcome, reply.code, reply.result)
            }
        }
    }

    /** End the session's requests; one still running is cancelled and never answered. */
    fun close() {
        val ending = io.panelassistant.android.control.RemoteDebugSecurityTransitionGate.withLock {
            synchronized(lock) {
                closed = true
                relays.values.toList().also { relays.clear() }
            }
        }
        // Every relayed connection ends with the session: its subscriptions stop and its commands fail.
        ending.forEach { relay ->
            relay.stopped?.close()
            relay.frames.close()
        }
        job.cancel()
    }

    /** The next message to send as message [id], or null. */
    fun next(id: Long): String? {
        val outgoing = synchronized(lock) { outbox.removeFirstOrNull() } ?: return null
        return outgoing.build(id).also { outgoing.after() }
    }

    private companion object {
        /** Section 20.5: a relayed result larger than this moves as a blob. */
        const val INLINE_RESULT_BYTES = 256 * 1024
    }
}
