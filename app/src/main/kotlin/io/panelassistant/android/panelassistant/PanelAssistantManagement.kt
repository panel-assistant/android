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
    /** [updateOwner] is the session form of HTTP's update-owner header: Home Assistant shows this panel's update. */
    suspend fun snapshot(updateOwner: Boolean): PanelAssistantManagementSnapshot

    /** Apply [settings] through the panel's own settings store; null when applied, else an outcome code. */
    suspend fun applySettings(settings: Map<String, String>): String?
}

/**
 * The settings Panel Assistant may write over the session, and the order they are checked in.
 *
 * The list is a security boundary. The session reaches the settings store without an HTTP request, so
 * none of the HTTP admission that binds a sensitive change to an approval on the panel applies here.
 * Every key on it must be one that admission never holds for approval; adding a key that it does
 * reopens that question.
 */
internal object PanelAssistantManagedSettings {
    val KEYS: Set<String> = setOf("voice_wake_words")

    /**
     * [validate] is the Configure page's own admission, returning the normalized values or null when it
     * refuses them; [commit] is the store's accepted-settings commit, returning whether it applied.
     */
    suspend fun apply(
        settings: Map<String, String>,
        validate: (Map<String, String>) -> Map<String, String>?,
        commit: suspend (Map<String, String>) -> Boolean,
    ): String? {
        if (settings.isEmpty() || !KEYS.containsAll(settings.keys)) return PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE
        val accepted = validate(settings) ?: return PanelAssistantCommandProcessor.CODE_INVALID_VALUE
        return if (commit(accepted)) null else PanelAssistantCommandProcessor.CODE_FAILED
    }
}

/**
 * One session's management requests: the session check, duplicate suppression and deadline that commands
 * use, answered with a `command_result` whose `result` carries what a snapshot read.
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

    private class Answer(val commandId: String, val outcome: String, val code: String?, val result: JSONObject?)

    private val lock = Any()
    /** Answers by command ID, kept for repeats; a running request maps to null. */
    private val answers = LinkedHashMap<String, Answer?>()
    private val outbox = ArrayDeque<Answer>()

    fun onRequest(event: PanelAssistantSessionEvent.Manage) {
        val receivedAt = monotonicMillis()
        synchronized(lock) {
            // A request for another session is a replay of an old one: nothing may run it.
            if (event.session != session.token) return
            if (answers.containsKey(event.commandId)) {
                answers[event.commandId]?.let { outbox.addLast(it); wake.trySend(Unit) }
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
                outbox.addLast(answer)
                while (answers.size > maxRemembered) {
                    val oldest = answers.entries.firstOrNull { it.value != null } ?: break
                    answers.remove(oldest.key)
                }
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun run(event: PanelAssistantSessionEvent.Manage, receivedAt: Long): Answer {
        fun refused(code: String) = Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, code, null)
        val deadline = event.deadlineMs ?: return refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
        if (monotonicMillis() - receivedAt > deadline) return refused(PanelAssistantCommandProcessor.CODE_EXPIRED)
        return when (event.op) {
            PanelAssistantTransportProtocol.MANAGE_SNAPSHOT -> {
                val snapshot = management.snapshot(event.updateOwner)
                Answer(
                    event.commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, null,
                    JSONObject().put("health", snapshot.health).put("status", snapshot.status),
                )
            }
            PanelAssistantTransportProtocol.MANAGE_SETTINGS -> {
                val settings = event.settings ?: return refused(PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
                when (val code = management.applySettings(settings)) {
                    null -> Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, null, null)
                    PanelAssistantCommandProcessor.CODE_FAILED ->
                        Answer(event.commandId, PanelAssistantTransportProtocol.OUTCOME_FAILED, code, null)
                    else -> refused(code)
                }
            }
            else -> refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_COMMAND)
        }
    }

    /** End the session's requests; one still running is cancelled and never answered. */
    fun close() {
        job.cancel()
    }

    /** The next answer to send as message [id], or null. */
    fun next(id: Long): String? {
        val answer = synchronized(lock) { outbox.removeFirstOrNull() } ?: return null
        return PanelAssistantTransportProtocol.commandResult(
            id, session.token, answer.commandId, answer.outcome, answer.code, answer.result,
        )
    }
}
