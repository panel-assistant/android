package io.panelassistant.android

import android.util.Log
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.ApprovalBroker
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Whether a named operation may change state. A read may be repeated; a command keeps the duplicate rule. */
internal enum class ManageKind { READ, COMMAND }

/**
 * One answer to a named operation (protocol section 20.2): its [outcome] and refusal [code], and the body the
 * HTTP route returns for the same request as [result] — a [JSONObject] or [JSONArray] for a JSON body, a
 * [String] for a text body, a blob reference object for binary, or null for an empty body. [status],
 * [contentType] and [headers] are what HTTP would have sent, kept so a door can reproduce them.
 */
internal data class ManageReply(
    val outcome: String,
    val code: String?,
    val result: Any? = null,
    val status: Int = 200,
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        fun refused(code: String) = ManageReply(PanelAssistantTransportProtocol.OUTCOME_REFUSED, code, status = 400)
        fun failed(code: String = PanelAssistantCommandProcessor.CODE_FAILED) =
            ManageReply(PanelAssistantTransportProtocol.OUTCOME_FAILED, code, status = 500)
    }
}

/**
 * Binary bodies and uploads (protocol section 20.5), supplied by the door that carries the request. [put]
 * stores bytes a reply carries and returns the reference that stands in for them; [take] returns the bytes a
 * request's reference names, or null when it names nothing usable.
 */
internal interface ManageBlobs {
    suspend fun put(bytes: ByteArray, type: String): JSONObject
    /** The uploaded body [ref] names. A door whose request runs again once approved serves that run the same body. */
    suspend fun take(ref: JSONObject): java.io.InputStream?
}

/** One named request as its handler sees it. */
internal class ManageRequest(
    val op: String,
    val args: JSONObject,
    val origin: CommandOrigin,
    val blobs: ManageBlobs?,
    val approval: ManageApproval? = null,
)

/** Approval registration and consumption share the origin-close boundary. Started work is released. */
internal class ManageApproval(
    private val lock: Any,
    private val broker: () -> ApprovalBroker,
    val owner: String,
    private val holders: MutableSet<ManageApproval>,
) {
    private var id: String? = null
    private var closed = false
    private var admitted = false

    fun request(action: () -> Pair<ApprovalBroker.Decision, String>): Pair<ApprovalBroker.Decision, String> = synchronized(lock) {
        check(!closed) { "request origin ended" }
        action().also { (decision, approvalId) ->
            id = approvalId
            if (decision == ApprovalBroker.Decision.APPROVED) admitted = true
        }
    }

    fun close() = synchronized(lock) {
        if (!admitted) {
            closed = true
            id?.let { approvalId ->
                if (holders.none { it !== this && !it.closed && it.id == approvalId }) broker().deny(approvalId)
            }
        }
    }

    fun release() = synchronized(lock) { holders.remove(this); Unit }
}

internal fun interface ManageHandler {
    suspend fun handle(request: ManageRequest): ManageReply
}

/**
 * The dispatcher entries a door calls (protocol section 20.2): named operations, channel commands and
 * subscriptions, each with the origin the door sets. [PanelCommandDispatcher] is the production one; a door
 * holds no handlers of its own.
 */
internal interface ManageDoor {
    fun manage(
        commandId: String,
        op: String,
        args: JSONObject,
        origin: CommandOrigin,
        blobs: ManageBlobs? = null,
        onPending: () -> Unit = {},
        done: (ManageReply) -> Unit,
    )
    fun submit(
        command: io.panelassistant.android.panelassistant.PanelAssistantCommand,
        origin: CommandOrigin,
        done: (io.panelassistant.android.panelassistant.PanelAssistantCommandResult) -> Unit,
    )
    /** [args] reach every read of the family, as a one-off read's do (a visible wizard's `presence`). */
    fun subscribe(family: String, origin: CommandOrigin, args: JSONObject = JSONObject(), sink: ManageSink): ManageSubscription?
    fun closeOrigin(origin: CommandOrigin)

    /**
     * Run [action] once when this door's dispatcher stops (a runtime generation is replaced): a socket opened
     * against it closes, so its page reconnects to the new one. Runs at once when it has already stopped.
     * Closing the returned handle forgets [action].
     */
    fun onStopped(action: () -> Unit): AutoCloseable
}

/** A sensitive operation from Panel Assistant is waiting for [approvalId] on the panel's own screen. */
internal class ManageApprovalPending(val approvalId: String) : RuntimeException("approval pending")

/** The captures the `logs` family follows: the same ones, under the same checks, the Server-Sent Events stream reads. */
internal fun interface ManageLogSources {
    /** The capture [source] (`app`, `system`, `webview`) names, or null when the stream would refuse it now. */
    suspend fun select(source: String): ManageLogSource?
}

/** One capture's live log lines. */
internal interface ManageLogSource {
    /** The lines a new viewer is shown first; null when the source cannot be followed now. */
    fun backlog(): List<String>?
    /** Null when the capture admits no further viewer now. */
    fun follow(onLine: (String) -> Unit): AutoCloseable?
}

/**
 * The named operations this panel serves, each registered once by the route that also mounts it over HTTP.
 * Registering a name again replaces it, so remounting the routes cannot leave two definitions.
 */
internal class ManageTable {
    /** The HTTP route that serves the same operation: its method, path, and the arguments it fixes. */
    data class Route(val method: String, val path: String, val fixedArgs: Map<String, String> = emptyMap())

    data class Entry(val name: String, val kind: ManageKind, val handler: ManageHandler, val route: Route? = null)

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile var logSources: ManageLogSources? = null

    fun register(name: String, kind: ManageKind, route: Route? = null, handler: ManageHandler) {
        require(NAME.matches(name)) { "operation name $name" }
        entries[name] = Entry(name, kind, handler, route)
    }

    operator fun get(name: String): Entry? = entries[name]

    fun names(): Set<String> = entries.keys.toSortedSet()

    companion object {
        val NAME = Regex("^[a-z][a-z0-9_.]{0,63}$")
    }
}

/** A subscription family (protocol section 20.4): the reads whose bodies make its value, and its floor. */
internal data class ManageFamily(val name: String, val floorMs: Long, val reads: List<String>, val append: Boolean = false)

internal object ManageFamilies {
    val DEFAULT: Map<String, ManageFamily> = listOf(
        ManageFamily("perf", 2_000, listOf("perf")),
        ManageFamily("sensors", 2_000, listOf("sensors")),
        ManageFamily("camera", 2_000, listOf("camera.status")),
        ManageFamily("health", 5_000, listOf("health")),
        ManageFamily("config", 500, listOf("config.values")),
        ManageFamily("entities", 5_000, listOf("dashboard.entities", "dashboard.entities.sync", "dashboard.entities.issues")),
        ManageFamily("proximity", 1_000, listOf("proximity")),
        ManageFamily("microphone", 2_000, listOf("voice.microphone")),
        ManageFamily("radio", 5_000, listOf("radio")),
        ManageFamily("install", 2_000, listOf("install.status", "install.apk.pending")),
        ManageFamily("setup", 2_000, listOf("setup", "ha.oauth.status")),
        ManageFamily("logs", 1_000, emptyList(), append = true),
    ).associateBy(ManageFamily::name)
}

/**
 * Receives a subscription's updates: the family's [value] when [known], or null and not known when the family
 * cannot be read now (protocol section 20.4's `unavailable`). [delivered] is called once the update has left,
 * which frees the slot.
 */
internal fun interface ManageSink {
    fun update(value: Any?, known: Boolean, delivered: () -> Unit)
}

internal interface ManageSubscription : AutoCloseable {
    val family: String
}

/**
 * The single entry every door calls for named operations and subscriptions, owned by one command
 * dispatcher generation. Reads and commands run on this owner's own workers, never on the ordered command
 * worker, so a long command (a backup, an APK fetch) cannot hold up channel commands; a handler that changes
 * a setting enters the ordered worker itself, through the settings path.
 */
internal class ManageOperations(
    private val table: ManageTable,
    private val approvals: () -> ApprovalBroker,
    private val families: Map<String, ManageFamily> = ManageFamilies.DEFAULT,
    private val approvalPollMs: Long = 1_000,
    private val approvalTtlMs: Long = ApprovalBroker.DEFAULT_TTL_MS,
    private val maxRememberedPerOrigin: Int = 64,
    private val maxSubscriptionsPerOrigin: Int = 8,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private class Recorded(var answer: ManageReply?, val waiters: MutableList<(ManageReply) -> Unit>)

    private val lock = Any()
    private val approvalHolders = mutableSetOf<ManageApproval>()
    /** Command answers by origin approval key, then command ID, oldest first; a running command has none. */
    private val remembered = HashMap<String, LinkedHashMap<String, Recorded>>()
    private val subscriptions = HashMap<String, LinkedHashMap<String, FamilySubscription>>()

    /**
     * Run [op] with [args] for [origin]; [done] receives exactly one final answer. An unknown name is
     * refused. A command whose [commandId] this origin already used answers that command's answer without
     * running again. A sensitive command from Panel Assistant calls [onPending] once, waits for its approval
     * on the panel, then answers its final outcome.
     */
    fun manage(
        commandId: String,
        op: String,
        args: JSONObject,
        origin: CommandOrigin,
        blobs: ManageBlobs? = null,
        onPending: () -> Unit = {},
        done: (ManageReply) -> Unit,
    ) {
        val entry = table[op]
        if (entry == null || !job.isActive) {
            return done(ManageReply.refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_COMMAND))
        }
        if (entry.kind == ManageKind.READ) {
            val request = trackedRequest(op, args, origin, blobs)
            // Started even if this owner stops first, so a stop answers it as failed rather than dropping it.
            scope.launch(start = CoroutineStart.ATOMIC) {
                val answer = try {
                    run(entry, request, onPending)
                } catch (cancelled: CancellationException) {
                    done(ManageReply.failed())
                    throw cancelled
                }
                done(answer)
            }
            return
        }
        val record = synchronized(lock) {
            val byId = remembered.getOrPut(origin.approvalKey) { LinkedHashMap() }
            val previous = byId[commandId]
            if (previous != null) {
                previous.answer?.let { answer -> return@synchronized { done(answer) } }
                previous.waiters += done
                return
            }
            byId[commandId] = Recorded(null, mutableListOf(done))
            while (byId.size > maxRememberedPerOrigin) {
                val oldest = byId.entries.firstOrNull { it.value.answer != null } ?: break
                byId.remove(oldest.key)
            }
            null
        }
        if (record != null) return record()
        val request = trackedRequest(op, args, origin, blobs)
        scope.launch(start = CoroutineStart.ATOMIC) {
            val answer = try {
                run(entry, request, onPending)
            } catch (cancelled: CancellationException) {
                // This owner stopped mid-command: everyone waiting hears it failed, never nothing.
                val waiters = synchronized(lock) {
                    remembered[origin.approvalKey]?.get(commandId)?.let { recorded ->
                        recorded.waiters.toList().also { recorded.waiters.clear() }
                    }.orEmpty()
                }
                waiters.forEach { it(ManageReply.failed()) }
                throw cancelled
            }
            val waiters = synchronized(lock) {
                val recorded = remembered[origin.approvalKey]?.get(commandId) ?: return@synchronized emptyList()
                recorded.answer = answer
                recorded.waiters.toList().also { recorded.waiters.clear() }
            }
            waiters.forEach { it(answer) }
        }
    }

    private fun trackedRequest(op: String, args: JSONObject, origin: CommandOrigin, blobs: ManageBlobs?): ManageRequest {
        val approval = synchronized(lock) {
            ManageApproval(lock, approvals, origin.subscriptionOwner, approvalHolders).also { approvalHolders += it }
        }
        return ManageRequest(op, args, origin, blobs, approval)
    }

    private suspend fun run(entry: ManageTable.Entry, request: ManageRequest, onPending: () -> Unit): ManageReply = try {
        runActive(entry, request, onPending)
    } finally {
        request.approval?.release()
    }

    private suspend fun runActive(entry: ManageTable.Entry, request: ManageRequest, onPending: () -> Unit): ManageReply {
        var pendingSent = false
        while (true) {
            val approvalId = try {
                return entry.handler.handle(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (pending: ManageApprovalPending) {
                pending.approvalId
            } catch (e: Exception) {
                Log.w(TAG, "operation ${request.op} failed", e)
                return ManageReply.failed()
            }
            // Asked again after an approval was consumed: its record expired between the approval and
            // the run. One interim answer per request, so this one is final.
            if (pendingSent) {
                return ManageReply.refused(PanelAssistantCommandProcessor.CODE_APPROVAL_TIMEOUT)
            }
            pendingSent = true
            // Logged so a held command is visible in the panel's log: nothing else records it until it ends.
            Log.i(TAG, "${request.op} for ${request.origin.approvalKey} waits for approval $approvalId on the panel")
            onPending()
            val heldAt = System.nanoTime()
            while (true) {
                delay(approvalPollMs)
                when (approvals().state(approvalId)) {
                    ApprovalBroker.State.PENDING -> continue
                    ApprovalBroker.State.APPROVED -> break
                    // The broker keeps no denied state: gone before its lifetime ran out was denied.
                    ApprovalBroker.State.ABSENT -> {
                        val code = if ((System.nanoTime() - heldAt) / 1_000_000 >= approvalTtlMs) {
                            PanelAssistantCommandProcessor.CODE_APPROVAL_TIMEOUT
                        } else {
                            PanelAssistantCommandProcessor.CODE_APPROVAL_DENIED
                        }
                        Log.i(TAG, "${request.op} approval $approvalId ended: $code")
                        return ManageReply.refused(code)
                    }
                }
            }
        }
    }

    /**
     * Follow [family] for [origin]. The first update carries the current value; later ones follow changes,
     * never more often than the family's floor, with at most one update outstanding (a newer value replaces
     * an unsent one). One owner ([CommandOrigin.subscriptionOwner]: the socket, or the origin) subscribing twice
     * to a family shares one subscription, whose updates go to every subscriber. Null when the family is unknown
     * or the owner already holds the most it may. [args] are passed to every read of the family; a shared
     * subscription reads with the newest.
     */
    fun subscribe(family: String, origin: CommandOrigin, args: JSONObject = JSONObject(), sink: ManageSink): ManageSubscription? {
        val definition = families[family] ?: return null
        if (!job.isActive) return null
        synchronized(lock) {
            val held = subscriptions.getOrPut(origin.subscriptionOwner) { LinkedHashMap() }
            held[family]?.let { shared -> return shared.attach(sink, args) }
            if (held.size >= maxSubscriptionsPerOrigin) return null
            val created = FamilySubscription(definition, origin, args)
            held[family] = created
            val handle = created.attach(sink, args)
            created.start()
            return handle
        }
    }

    private val stopActions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** See [ManageDoor.onStopped]. */
    fun onStopped(action: () -> Unit): AutoCloseable {
        stopActions += action
        if (!job.isActive && stopActions.remove(action)) action()
        return AutoCloseable { stopActions.remove(action) }
    }

    /** End every subscription [origin]'s owner holds (that socket only, when it has one), as a door does when its connection closes. */
    fun closeOrigin(origin: CommandOrigin) {
        val held = synchronized(lock) {
            approvalHolders.filter { it.owner == origin.subscriptionOwner }.forEach { it.close() }
            subscriptions.remove(origin.subscriptionOwner)?.values?.toList().orEmpty()
        }
        held.forEach { it.stop() }
    }

    fun stop() {
        synchronized(lock) { approvalHolders.toList().forEach { it.close() } }
        job.cancel()
        // Commands still running are answered here or by their own cancellation, whichever takes them first.
        val unanswered = synchronized(lock) {
            subscriptions.clear()
            remembered.values.flatMap { byId ->
                byId.values.filter { it.answer == null }.flatMap { recorded -> recorded.waiters.toList().also { recorded.waiters.clear() } }
            }.also { remembered.clear() }
        }
        unanswered.forEach { runCatching { it(ManageReply.failed()) } }
        // Each door opened against this owner closes, so its page reconnects to the generation that replaces it.
        stopActions.toList().forEach { action -> if (stopActions.remove(action)) runCatching(action) }
    }

    private suspend fun readFamily(definition: ManageFamily, origin: CommandOrigin, args: JSONObject): Any? {
        val values = definition.reads.map { name ->
            val entry = table[name] ?: return null
            val reply = run(entry, ManageRequest(name, JSONObject(args.toString()), origin, null)) {}
            if (reply.outcome != PanelAssistantTransportProtocol.OUTCOME_APPLIED) return null
            name to reply.result
        }
        // A family made of one read carries that read's body; one made of several carries each by name.
        return values.singleOrNull()?.second ?: JSONObject().apply { values.forEach { (name, body) -> put(name, body) } }
    }

    private inner class FamilySubscription(
        private val definition: ManageFamily,
        private val origin: CommandOrigin,
        @Volatile private var args: JSONObject,
    ) {
        private var worker: Job? = null
        /** One per subscribe call, oldest first; guarded by the owner's lock. The newest one's sink receives. */
        private val handles = ArrayList<Handle>()

        /** One subscriber of the shared worker; closing the last one ends the worker. */
        inner class Handle(val handleSink: ManageSink, val handleArgs: JSONObject) : ManageSubscription {
            override val family: String get() = definition.name
            override fun close() = detach(this)
        }

        /** Add a subscriber, under the owner's lock; the newest receives the current value next. */
        fun attach(newSink: ManageSink, newArgs: JSONObject): Handle {
            val handle = Handle(newSink, newArgs)
            handles += handle
            replaceArgs(newArgs)
            // A new subscriber gets the current value now, not after the family's floor: a fresh read, or for a log
            // viewer its own source's backlog, as a new stream does.
            if (worker != null) {
                synchronized(slot) { unsent = null }
                // The old worker stops first, so a log capture at its viewer limit admits this follower.
                start(after = worker)
            }
            return handle
        }

        private fun detach(handle: Handle) {
            val last = synchronized(lock) {
                if (!handles.remove(handle)) return
                if (handles.isEmpty()) {
                    val held = subscriptions[origin.subscriptionOwner]
                    if (held?.get(definition.name) === this) held.remove(definition.name)
                    true
                } else {
                    // A late unsubscribe of an older id leaves the newer subscriber in charge.
                    replaceArgs(handles.last().handleArgs)
                    false
                }
            }
            if (last) stop()
        }
        private val slot = Any()
        private var outstanding = false
        private var unsent: Update? = null
        /** The last value sent or queued, compared by its encoding ([UNAVAILABLE] when it could not be read); null before the first. */
        private var last: String? = null

        fun start(after: Job? = null) {
            // The predecessor is cancelled here, not inside the replacement: a replacement cancelled before it
            // runs (a third subscriber came first) would otherwise never cancel it, leaving a reader nobody owns.
            after?.cancel()
            worker = scope.launch {
                after?.join()
                if (definition.append) followLogs() else poll()
            }
        }

        fun replaceArgs(newArgs: JSONObject) {
            args = newArgs
            synchronized(slot) { last?.let { last = null } }
        }

        private suspend fun poll() {
            while (currentCoroutineContext().isActive) {
                // A read that fails, throws or answers nothing makes the family unavailable; the next success is
                // then a change even when it repeats the value from before the failure.
                // A worker stopped mid-read (a new subscriber restarts it) leaves quietly, never as unavailable.
                val value = try {
                    readFamily(definition, origin, args)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val encoded = if (value == null) UNAVAILABLE else encode(value)
                val changed = synchronized(slot) { (encoded != last).also { if (it) last = encoded } }
                if (changed) offer(Update(value, value != null), append = false)
                delay(definition.floorMs)
            }
        }

        /** Follows the capture `args.source` names (`app` when absent); the stream's refusals are `unavailable`. */
        private suspend fun followLogs() {
            val source = table.logSources?.select(args.optString("source").ifEmpty { "app" })
            val backlog = source?.backlog()
            val arrived = ArrayDeque<String>()
            val follower = if (backlog == null) null else source.follow { line ->
                synchronized(arrived) {
                    arrived.addLast(line)
                    while (arrived.size > MAX_LOG_LINES) arrived.removeFirst()
                }
            }
            if (follower == null) {
                offer(Update(null, false), append = true)
                return
            }
            try {
                offer(Update(JSONArray(backlog), true), append = true)
                while (currentCoroutineContext().isActive) {
                    delay(definition.floorMs)
                    val lines = synchronized(arrived) { arrived.toList().also { arrived.clear() } }
                    if (lines.isNotEmpty()) offer(Update(JSONArray(lines), true), append = true)
                }
            } finally {
                runCatching { follower.close() }
            }
        }

        private fun offer(update: Update, append: Boolean) {
            val send = synchronized(slot) {
                if (outstanding) {
                    // Latest wins for a replacing family; an append family keeps every line, oldest first.
                    val queued = unsent
                    unsent = if (append && queued != null && queued.known && update.known) {
                        Update(concat(queued.value as JSONArray, update.value as JSONArray), true)
                    } else update
                    false
                } else {
                    outstanding = true
                    true
                }
            }
            if (send) deliver(update)
        }

        /** Sends [update] to every live subscriber; the slot frees once each has sent it. A read that finishes after the last close sends nothing. */
        private fun deliver(update: Update) {
            val sinks = synchronized(lock) { handles.map { it.handleSink } }
            if (sinks.isEmpty()) {
                synchronized(slot) { outstanding = false; unsent = null }
                return
            }
            val remaining = java.util.concurrent.atomic.AtomicInteger(sinks.size)
            sinks.forEach { target ->
                val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
                target.update(update.value, update.known) {
                    if (!delivered.compareAndSet(false, true) || remaining.decrementAndGet() > 0) return@update
                    val next = synchronized(slot) {
                        unsent.also { if (it == null) outstanding = false else unsent = null }
                    }
                    if (next != null && worker?.isActive == true) deliver(next)
                }
            }
        }

        fun stop() {
            worker?.cancel()
        }

    }

    /** One update a subscription sends: a value, or null when [known] is false. */
    private class Update(val value: Any?, val known: Boolean)

    private companion object {
        const val TAG = "ha-paneld/manage"
        const val MAX_LOG_LINES = 512
        /** [FamilySubscription]'s record of an unreadable family; no [encode] output is empty. */
        const val UNAVAILABLE = ""

        fun encode(value: Any?): String = when (value) {
            is JSONObject, is JSONArray -> value.toString()
            null -> "null"
            else -> JSONObject().put("v", value).toString()
        }

        fun concat(first: JSONArray, second: JSONArray): JSONArray = JSONArray().apply {
            for (i in 0 until first.length()) put(first.get(i))
            for (i in 0 until second.length()) put(second.get(i))
        }
    }
}
