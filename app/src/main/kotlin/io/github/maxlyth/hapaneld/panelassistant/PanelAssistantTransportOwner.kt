package io.github.maxlyth.hapaneld.panelassistant

import android.util.Log
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** One authenticated socket to Home Assistant, already past `auth_ok`. */
internal interface PanelAssistantTransportConnection {
    suspend fun send(text: String)

    /**
     * The next text frame, or null when [timeoutMs] passes first. A timeout must not consume a frame:
     * losing a `session_closed` event at a timeout boundary would leave a session-less socket that
     * still answers pings, and nothing would ever reconnect it.
     */
    suspend fun receive(timeoutMs: Long): String?

    suspend fun close()

    /** One binary frame: a voice turn's handler byte and its PCM. Safe beside [send] from another coroutine. */
    suspend fun sendBinary(bytes: ByteArray): Unit = throw UnsupportedOperationException("binary frames")
}

internal fun interface PanelAssistantTransportConnector {
    /** Opens and authenticates; throws [HaAuthenticationException] when Home Assistant rejects the token. */
    suspend fun connect(baseUrl: String, accessToken: String): PanelAssistantTransportConnection
}

/** Everything a session is opened for. Equal demand never disturbs a live session. */
internal data class PanelAssistantTransportDemand(
    /**
     * The credential's stable identity, deliberately not the access token: an access-token refresh by
     * any consumer leaves this equal, so a healthy session is never recycled because a token rolled.
     */
    val credential: HaAuthOwner,
    val identity: PanelAssistantHelloIdentity,
)

/**
 * Demand for the transport, or null when there is nothing to connect with: no Home Assistant address,
 * no credential, or no panel identity for `hello` to carry.
 */
internal fun panelAssistantTransportDemand(
    credential: HaAuthOwner,
    accessTokenPresent: Boolean,
    identity: PanelAssistantHelloIdentity,
): PanelAssistantTransportDemand? {
    if (credential.url.isBlank()) return null
    if (credential.refreshToken.isBlank() && !accessTokenPresent) return null
    if (identity.did == null) return null
    return PanelAssistantTransportDemand(credential, identity)
}

internal enum class PanelAssistantTransportPhase { STOPPED, CONNECTING, HANDSHAKING, CONNECTED, WAITING }

/**
 * What the panel's local control plane reports about the transport: codes and facts, no display text.
 * Migration scaffolding for moving entities off MQTT; it is deleted with MQTT.
 */
internal data class PanelAssistantTransportFacts(
    /** The persisted authority: `mqtt`, `shadow`, `native`, or empty before any session. */
    val authority: String,
    /** The persisted MQTT discovery value: `withdraw`, `announce`, or empty before any session. */
    val mqttDiscovery: String,
    val phase: PanelAssistantTransportPhase,
    /** The owner's last refusal or retry code, null while none applies. */
    val refusal: String?,
)

internal data class PanelAssistantTransportStatus(
    val phase: PanelAssistantTransportPhase = PanelAssistantTransportPhase.STOPPED,
    /** Consecutive attempts since the last accepted `hello`. */
    val attempt: Int = 0,
    /** The code that put the owner on the slow schedule, or the last refusal on the fast one. */
    val refusal: String? = null,
    val slowRetry: Boolean = false,
    val session: PanelAssistantSession? = null,
) {
    /** Log form. Carries no credential, identity or session token. */
    fun describe(): String = buildString {
        append(phase.name.lowercase())
        if (attempt > 0) append(" attempt=").append(attempt)
        refusal?.let { append(" refusal=").append(it) }
        if (slowRetry) append(" slow")
        session?.let {
            append(" protocol=").append(it.protocol)
            append(" authority=").append(it.authority)
            append(" integration=").append(it.integrationVersion)
            append(" capabilities=").append(it.capabilities)
            it.mqttDiscovery?.let { claim -> append(" mqtt_discovery=").append(claim) }
        }
    }
}

/**
 * The panel's long-lived native-transport socket to Panel Assistant (protocol specification section 3).
 *
 * It owns connection, reconnect and backoff: Home Assistant cannot dial a panel, so nothing else ever
 * retries. Unlike the exact-entity stream owner it never parks. Network and protocol failures retry
 * on full-jitter exponential backoff from [backoffBaseMs] to [backoffMaxMs], and the attempt counter
 * resets only on an accepted `hello`. Two conditions move to the fixed [slowRetryMs] schedule instead:
 * a token still rejected after one forced refresh, and terminal `hello` refusals. A panel waiting
 * for account confirmation retries every 5 seconds, so approval becomes visible promptly.
 * Every wait, slow or fast, ends early on [nudge] (the default network returned) and on a demand
 * change (the credential or identity moved).
 *
 * `unknown_command` and `unknown_panel` are also what a Home Assistant restart looks like from here:
 * Core accepts WebSocket connections before custom integrations finish loading, and an entry reload
 * briefly has no loaded entry. Within [warmupWindowMs] of demand starting or a session ending, those
 * two codes stay on the fast schedule, so a restart or reload costs seconds rather than the slow
 * interval.
 *
 * Credentials come from the shared [HaApiSessionProvider]; this owner holds no token cache. It sends
 * `hello`, protocol pings, the [shadow] reporter's `report_state` requests on a session granted `state`
 * under the `shadow` or `native` authority, and the answers to the commands a session delivers, which run
 * on the panel's own command authority through [commands]. Every accepted authority is handed to
 * [onAuthority] before the session is used, so the panel keeps enforcing it while no session is open.
 * No events.
 *
 * The reply's claim on the panel's MQTT discovery entities is resolved against the [mqttDiscovery] the
 * panel persisted ([PanelAssistantTransportProtocol.mqttDiscovery]) and handed to [onMqttDiscovery] only
 * when it changes. A release, or a withdrawal on a session that reports no state, is handed over at once.
 * A withdrawal on a reporting session waits for the integration to acknowledge the session's full sync,
 * so the native entities are available before the MQTT ones are removed; a session that ends first hands
 * nothing over, and the next accepted hello resolves the claim again. A hello refused `entry_removed` hands
 * the panel back to MQTT through [releaseToMqtt], which the local control plane also calls.
 */
internal class PanelAssistantTransportOwner(
    private val scope: CoroutineScope,
    private val auth: HaApiSessionProvider,
    private val connector: PanelAssistantTransportConnector,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Returns a value in `[0, bound]`; injected so a test can pin the jitter. */
    private val jitter: (bound: Long) -> Long = { bound -> Random.nextLong(bound + 1) },
    private val backoffBaseMs: Long = 1_000L,
    private val backoffMaxMs: Long = 60_000L,
    private val slowRetryMs: Long = 15L * 60_000L,
    private val warmupWindowMs: Long = 10L * 60_000L,
    private val pingIntervalMs: Long = 30_000L,
    private val pongTimeoutMs: Long = 15_000L,
    private val helloTimeoutMs: Long = 15_000L,
    private val closeTimeoutMs: Long = 2_000L,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
    /** Null reports no state and offers no capability, exactly as the handshake-only slice did. */
    private val shadow: PanelAssistantShadowReporter? = null,
    /** Samples the running panel before this accepted session starts its full state report. */
    private val observeForHello: suspend () -> Boolean,
    /** Null offers neither commands nor approval. */
    private val commands: PanelAssistantCommandSink? = null,
    private val approvalTtlMs: Long = io.github.maxlyth.hapaneld.security.ApprovalBroker.DEFAULT_TTL_MS,
    private val onAuthority: (String) -> Unit = {},
    /** Positive handshake evidence; a local MQTT fallback is not an accepted session. */
    private val onConnected: () -> Unit = {},
    /** The persisted authority, empty before any session. */
    private val authority: () -> String = { "" },
    /** The persisted MQTT discovery value, empty before any session. */
    private val mqttDiscovery: () -> String = { "" },
    /** Persists the resolved MQTT discovery value and applies it to the bridge. */
    private val onMqttDiscovery: (String) -> Unit = {},
    /** Holds the sidebar proof key for the live session; null offers no `embed_proof`. */
    private val embedKeys: io.github.maxlyth.hapaneld.http.EmbedProofKeyring? = null,
    /** The Assist satellite; null, or one reporting no configuration, offers no `voice`. */
    private val voice: PanelAssistantVoice? = null,
) : AutoCloseable {
    private val lock = Any()
    private val releaseLock = Any()
    private val generation = AtomicLong()
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private data class RestartRequest(
        val scope: String,
        val reason: String,
        val expiresAtMs: Long,
        val acknowledged: CompletableDeferred<Boolean>,
    )
    private val restart = AtomicReference<RestartRequest?>()
    private val restartWake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Held for a generation's whole run, teardown included. The shadow reporter has no session identity, so
     * a replaced generation still closing it must finish before the next one opens it, or its late close
     * would switch reporting off for the new session. A generation cancelled while waiting never takes it.
     */
    private val sessions = Mutex()
    private var demand: PanelAssistantTransportDemand? = null
    private var job: Job? = null
    private var stopped = false

    @Volatile
    var status: PanelAssistantTransportStatus = PanelAssistantTransportStatus()
        private set

    /** Start, replace or stop the session to match [next]. An equal demand is a no-op. */
    fun replaceDemand(next: PanelAssistantTransportDemand?) {
        val run: Long
        synchronized(lock) {
            if (stopped) return
            if (next == demand && (next == null || job?.isActive == true)) return
            job?.cancel()
            job = null
            demand = next
            run = generation.incrementAndGet()
            if (next != null) {
                job = scope.launch(workerDispatcher) { sessions.withLock { runSource(run, next) } }
            }
        }
        if (next == null) publish(run, PanelAssistantTransportStatus())
    }

    /** End any pending wait now; used when the default network becomes available. */
    fun nudge() {
        nudges.trySend(Unit)
    }

    /** The same bounded notice feeds the socket and the existing HTTP health poll. */
    fun announceRestart(scope: String, reason: String, expectedBackMs: Long): CompletableDeferred<Boolean> {
        require(scope in setOf("app", "panel"))
        require(reason in setOf("update", "settings", "recovery", "reboot"))
        require(expectedBackMs in 1..300_000L)
        val answer = CompletableDeferred<Boolean>()
        val notice = RestartRequest(scope, reason, monotonicMillis() + expectedBackMs, answer)
        restart.getAndSet(notice)?.acknowledged?.complete(false)
        if (status.phase == PanelAssistantTransportPhase.CONNECTED && (status.session?.protocol ?: 0) >= 2) {
            restartWake.trySend(Unit)
        } else {
            answer.complete(false)
        }
        return answer
    }

    fun restartHealthToken(): String {
        val notice = restart.get() ?: return ""
        val remaining = notice.expiresAtMs - monotonicMillis()
        return if (remaining > 0) " pa_restarting=${notice.scope},${notice.reason},$remaining" else ""
    }

    /** The persisted authority and discovery value beside this owner's current phase and refusal. */
    fun facts(): PanelAssistantTransportFacts {
        val current = status
        return PanelAssistantTransportFacts(authority(), mqttDiscovery(), current.phase, current.refusal)
    }

    /**
     * Hand this panel's entities and commands back to MQTT: persist authority `mqtt` and discovery
     * `announce` through the same callbacks an accepted hello uses, so the bridge re-announces its discovery
     * and accepts MQTT `/set` again. Called when a hello is refused `entry_removed`, and by the panel's local
     * control plane when no integration can answer. It does not end a live session: a live integration's
     * next accepted hello still wins and may claim the panel again. Returns whether anything changed, so a
     * refusal repeated on every slow retry neither re-announces nor logs again.
     *
     * Migration scaffolding for moving entities between MQTT and the integration; it is deleted with MQTT.
     */
    fun releaseToMqtt(): Boolean = synchronized(releaseLock) {
        val authorityChanged = authority() != PanelAssistantTransportProtocol.AUTHORITY_MQTT
        val discoveryChanged = mqttDiscovery() != PanelAssistantTransportProtocol.MQTT_DISCOVERY_ANNOUNCE
        if (authorityChanged) onAuthority(PanelAssistantTransportProtocol.AUTHORITY_MQTT)
        if (discoveryChanged) onMqttDiscovery(PanelAssistantTransportProtocol.MQTT_DISCOVERY_ANNOUNCE)
        authorityChanged || discoveryChanged
    }

    override fun close() {
        restart.get()?.acknowledged?.complete(false)
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation.incrementAndGet()
            job?.cancel()
            job = null
            demand = null
        }
        status = PanelAssistantTransportStatus()
    }

    private sealed interface Retry {
        val refusal: String?

        data class Fast(override val refusal: String? = null) : Retry
        data class Slow(override val refusal: String) : Retry
    }

    private suspend fun runSource(run: Long, demand: PanelAssistantTransportDemand) {
        var attempt = 0
        var forceAuth = false
        var authRefreshed = false
        var warmUntil = monotonicMillis() + warmupWindowMs
        while (generation.get() == run) {
            var hadSession = false
            var connection: PanelAssistantTransportConnection? = null
            val retry: Retry = try {
                publish(run, PanelAssistantTransportStatus(PanelAssistantTransportPhase.CONNECTING, attempt))
                val session = auth.resolve(forceAuth)
                forceAuth = false
                val token = session.accessToken
                when {
                    token == null && session.rejected -> Retry.Slow(REFUSAL_CREDENTIAL_REJECTED)
                    token == null -> Retry.Fast(REFUSAL_CREDENTIAL_UNAVAILABLE)
                    // The credential moved while it was being resolved. The service replaces the
                    // demand for the new credential; until then this generation does not use it.
                    session.owner != demand.credential -> Retry.Fast(REFUSAL_CREDENTIAL_UNAVAILABLE)
                    else -> {
                        val opened = connector.connect(session.baseUrl, token)
                        connection = opened
                        publish(run, PanelAssistantTransportStatus(PanelAssistantTransportPhase.HANDSHAKING, attempt))
                        val offered = PanelAssistantTransportProtocol.CAPABILITIES.filter { capability ->
                            when (capability) {
                                PanelAssistantTransportProtocol.CAPABILITY_STATE -> shadow != null
                                // Offered whatever else is wired: withdrawing discovery needs neither.
                                PanelAssistantTransportProtocol.CAPABILITY_MQTT_WITHDRAW -> true
                                // Proofs are verified by the web server, whatever else the session carries.
                                PanelAssistantTransportProtocol.CAPABILITY_EMBED_PROOF -> embedKeys != null
                                PanelAssistantTransportProtocol.CAPABILITY_VOICE -> voice?.offered() == true
                                else -> commands != null
                            }
                        }
                        val offer = shadow?.offer()
                        val described = offer?.descriptors.orEmpty()
                        when (val outcome = handshake(opened, demand.identity, offered, described, offer?.unsupported.orEmpty())) {
                            is PanelAssistantHelloOutcome.Accepted -> {
                                onConnected()
                                attempt = 0
                                authRefreshed = false
                                publish(run, PanelAssistantTransportStatus(
                                    PanelAssistantTransportPhase.CONNECTED,
                                    session = outcome.session,
                                ))
                                hadSession = true
                                val authority = outcome.session.authority
                                if (authority in PanelAssistantTransportProtocol.AUTHORITIES) onAuthority(authority)
                                val discovery = PanelAssistantTransportProtocol.mqttDiscovery(
                                    outcome.session, mqttDiscovery(),
                                )
                                val discoveryChanged = discovery != mqttDiscovery()
                                // A native entity is available only while its channel is reported, so the
                                // native authority reports exactly as shadow mode does.
                                val reporting = shadow?.takeIf {
                                    (authority == PanelAssistantTransportProtocol.AUTHORITY_SHADOW ||
                                        authority == PanelAssistantTransportProtocol.AUTHORITY_NATIVE) &&
                                        PanelAssistantTransportProtocol.CAPABILITY_STATE in outcome.session.capabilities
                                }
                                val commanding = commands?.let { sink ->
                                    PanelAssistantCommandProcessor(
                                        sink = sink,
                                        session = outcome.session,
                                        channels = described,
                                        monotonicMillis = monotonicMillis,
                                        approvalTtlMs = approvalTtlMs,
                                        log = log,
                                    )
                                }
                                val withdrawAfterSync = discoveryChanged && reporting != null &&
                                    discovery == PanelAssistantTransportProtocol.MQTT_DISCOVERY_WITHDRAW
                                if (discoveryChanged && !withdrawAfterSync) onMqttDiscovery(discovery)
                                val embed = outcome.session.embed
                                val did = demand.identity.did
                                if (embed != null && did != null) {
                                    embedKeys?.install(io.github.maxlyth.hapaneld.http.EmbedProofKey(embed.keyId, embed.key(), did))
                                }
                                val speaking = voice?.takeIf {
                                    PanelAssistantTransportProtocol.CAPABILITY_VOICE in outcome.session.capabilities
                                }
                                val reason = try {
                                    if (reporting != null && !observeForHello()) {
                                        throw PanelAssistantProtocolException("panel state owner changed during hello")
                                    }
                                    reporting?.open(described)
                                    speaking?.open(opened, outcome.session.token, session.baseUrl)
                                    holdSession(opened, outcome.session, reporting, commanding, withdrawAfterSync, speaking)
                                } finally {
                                    speaking?.close()
                                    // The key belongs to this session: once it ends no proof verifies.
                                    if (embed != null) embedKeys?.clear(embed.keyId)
                                    commanding?.close()
                                    reporting?.close()
                                    // Every way an accepted session ends reopens the window: a Core
                                    // restart is a bare socket close, never a session_closed event.
                                    warmUntil = monotonicMillis() + warmupWindowMs
                                }
                                log("native transport session closed: ${reason.ifEmpty { "unspecified" }}")
                                // Another connection took this panel's session. Coming straight back
                                // would take it back, and two claimants would trade it every second.
                                if (reason == PanelAssistantTransportProtocol.REASON_SUPERSEDED) {
                                    Retry.Slow(REFUSAL_SESSION_SUPERSEDED)
                                } else {
                                    Retry.Fast(REFUSAL_SESSION_CLOSED)
                                }
                            }
                            is PanelAssistantHelloOutcome.Refused -> {
                                // The entry was removed, so nothing holds this panel's entities: hand them
                                // back to MQTT. Migration scaffolding; it is deleted with MQTT.
                                if (outcome.code == PanelAssistantTransportProtocol.CODE_ENTRY_REMOVED && releaseToMqtt()) {
                                    log("native transport entry removed: authority and discovery returned to MQTT")
                                }
                                refusalRetry(outcome.code, monotonicMillis() < warmUntil)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Ktor also throws CancellationException from a send on a socket Home Assistant has
                // just closed. Only this coroutine's own cancellation ends the owner; anything else is
                // a lost socket and retries like one.
                currentCoroutineContext().ensureActive()
                Retry.Fast(REFUSAL_TRANSPORT).also {
                    log("native transport attempt failed: ${cancelled.javaClass.simpleName}")
                }
            } catch (rejected: HaAuthenticationException) {
                if (authRefreshed) {
                    Retry.Slow(REFUSAL_AUTH_INVALID)
                } else {
                    authRefreshed = true
                    forceAuth = true
                    Retry.Fast(REFUSAL_AUTH_INVALID)
                }
            } catch (failure: Exception) {
                // Network, TLS, frame bound, closed socket, malformed reply or liveness: never parks.
                Retry.Fast(REFUSAL_TRANSPORT).also {
                    log("native transport attempt failed: ${failure.javaClass.simpleName}")
                }
            } finally {
                connection?.let { open ->
                    withContext(NonCancellable) {
                        withTimeoutOrNull(closeTimeoutMs) { runCatching { open.close() } }
                    }
                }
            }
            if (generation.get() != run) return
            val delayMs = when (retry) {
                is Retry.Fast -> {
                    attempt = nextAttempt(attempt)
                    if (retry.refusal == PanelAssistantTransportProtocol.CODE_PANEL_USER_MISMATCH) {
                        CONFIRMATION_RETRY_MS
                    } else {
                        backoffDelay(attempt)
                    }
                }
                is Retry.Slow -> {
                    attempt = nextAttempt(attempt)
                    slowRetryMs
                }
            }
            publish(run, PanelAssistantTransportStatus(
                PanelAssistantTransportPhase.WAITING,
                attempt,
                retry.refusal,
                slowRetry = retry is Retry.Slow,
            ))
            // A nudge that arrived while connected describes a network that is already in use.
            if (hadSession) nudges.tryReceive()
            withTimeoutOrNull(delayMs) { nudges.receive() }
        }
    }

    private fun refusalRetry(code: String, warm: Boolean): Retry = when (code) {
        PanelAssistantTransportProtocol.CODE_PANEL_USER_MISMATCH -> Retry.Fast(code)
        PanelAssistantTransportProtocol.CODE_UNKNOWN_COMMAND,
        PanelAssistantTransportProtocol.CODE_UNKNOWN_PANEL,
        -> if (warm) Retry.Fast(code) else Retry.Slow(code)
        else -> Retry.Slow(code)
    }

    private suspend fun handshake(
        connection: PanelAssistantTransportConnection,
        identity: PanelAssistantHelloIdentity,
        offered: List<String>,
        described: List<PanelAssistantChannelDescriptor>,
        unsupported: List<String>,
    ): PanelAssistantHelloOutcome {
        connection.send(PanelAssistantTransportProtocol.hello(HELLO_ID, identity, offered, described, unsupported))
        val deadline = monotonicMillis() + helloTimeoutMs
        while (true) {
            val remaining = deadline - monotonicMillis()
            if (remaining <= 0) throw PanelAssistantProtocolException("hello was not answered")
            val text = connection.receive(remaining)
                ?: throw PanelAssistantProtocolException("hello was not answered")
            PanelAssistantTransportProtocol.helloOutcome(parse(text), HELLO_ID, offered)?.let { return it }
        }
    }

    /**
     * Hold an accepted session until it ends. Returns the `session_closed` reason, or a local reason when
     * this panel ends it; throws when the socket fails or stops answering. Pings go out on a fixed
     * cadence, and a ping with no inbound frame of any kind within [pongTimeoutMs] means the socket is dead.
     *
     * This coroutine is the only sender on the connection, so message ids strictly increase in send order.
     * A child only receives frames; [reporting], when present, is asked for its next request whenever an
     * observation, a result or a deadline may have made one due.
     */
    private suspend fun holdSession(
        connection: PanelAssistantTransportConnection,
        session: PanelAssistantSession,
        reporting: PanelAssistantShadowReporter?,
        commanding: PanelAssistantCommandProcessor?,
        withdrawAfterSync: Boolean,
        speaking: PanelAssistantVoice?,
    ): String = coroutineScope {
        val frames = Channel<String>(Channel.UNLIMITED)
        val reader = launch {
            try {
                while (true) connection.receive(pingIntervalMs)?.let { frames.send(it) }
            } catch (failure: Exception) {
                // Ktor reports a socket Home Assistant closed as a CancellationException too; only this
                // child's own cancellation is the end of the session rather than a lost socket.
                if (failure is CancellationException && !isActive) throw failure
                frames.close(failure)
            }
        }
        try {
            sessionLoop(connection, session, reporting, commanding, frames, withdrawAfterSync, speaking)
        } finally {
            reader.cancel()
        }
    }

    private suspend fun sessionLoop(
        connection: PanelAssistantTransportConnection,
        session: PanelAssistantSession,
        reporting: PanelAssistantShadowReporter?,
        commanding: PanelAssistantCommandProcessor?,
        frames: Channel<String>,
        withdrawAfterSync: Boolean,
        speaking: PanelAssistantVoice?,
    ): String {
        var nextMessageId = HELLO_ID + 1
        // A withdrawal still owed to the bridge once this session's full sync is acknowledged.
        var withdrawPending = withdrawAfterSync
        // Ids of command_result requests awaiting their result, kept apart from report_state results.
        val answering = HashSet<Long>()
        val restartAnswers = HashMap<Long, CompletableDeferred<Boolean>>()
        var sentRestart: RestartRequest? = null
        var nextPingAt = monotonicMillis() + pingIntervalMs
        var pongDeadline: Long? = null
        if (reporting != null) log("native transport shadow reporting started")
        while (true) {
            val now = monotonicMillis()
            val notice = restart.get()
            if (notice != null && now >= notice.expiresAtMs) notice.acknowledged.complete(false)
            if (notice != null && notice !== sentRestart && !notice.acknowledged.isCompleted && session.protocol >= 2) {
                connection.send(PanelAssistantTransportProtocol.restartNotice(
                    nextMessageId, session.token, notice.scope, notice.reason, notice.expiresAtMs - now,
                ))
                restartAnswers[nextMessageId++] = notice.acknowledged
                sentRestart = notice
            }
            val awaiting = pongDeadline
            if (awaiting != null && now >= awaiting) {
                throw PanelAssistantProtocolException("Home Assistant stopped answering pings")
            }
            if (awaiting == null && now >= nextPingAt) {
                connection.send(PanelAssistantTransportProtocol.ping(nextMessageId++))
                pongDeadline = now + pongTimeoutMs
                nextPingAt = now + pingIntervalMs
                continue
            }
            if (commanding != null) {
                commanding.pollApprovals(now)
                val answer = commanding.next(nextMessageId)
                if (answer != null) {
                    connection.send(answer)
                    answering += nextMessageId++
                    continue
                }
            }
            val spoken = speaking?.next(nextMessageId)
            if (spoken != null) {
                connection.send(spoken)
                nextMessageId++
                continue
            }
            if (reporting != null) {
                reporting.expire(now)
                if (reporting.descriptorsChanged()) return REASON_DESCRIPTORS_CHANGED
                val request = reporting.next(nextMessageId, session.token, now)
                if (request != null) {
                    connection.send(request)
                    nextMessageId++
                    continue
                }
            }
            val due = listOfNotNull(awaiting ?: nextPingAt, reporting?.nextDeadline(now), commanding?.nextDeadline(now)).min()
            val text = select<String?> {
                frames.onReceive { it }
                reporting?.wake?.onReceive { null }
                commanding?.wake?.onReceive { null }
                restartWake.onReceive { null }
                speaking?.wake?.onReceive { null }
                onTimeout((due - now).coerceAtLeast(0L)) { null }
            } ?: continue
            pongDeadline = null
            val frame = parse(text)
            if (speaking != null && speaking.onFrame(frame, HELLO_ID)) continue
            when (val event = PanelAssistantTransportProtocol.sessionEvent(frame, HELLO_ID)) {
                is PanelAssistantSessionEvent.Closed -> return event.reason
                is PanelAssistantSessionEvent.Command ->
                    commanding?.onCommand(event) ?: log("native transport ignored a command: commands not offered")
                PanelAssistantSessionEvent.MalformedCommand -> log("native transport ignored a command without an id")
                is PanelAssistantSessionEvent.Ignored -> log("native transport ignored event kind ${event.kind}")
                null -> Unit
            }
            val id = PanelAssistantTransportProtocol.messageId(frame)
            if (id != null) {
                restartAnswers.remove(id)?.let { answer ->
                    answer.complete(frame.optString("type") == "result" && frame.opt("success") == true)
                    continue
                }
            }
            if (id != null && answering.remove(id)) {
                when (val result = PanelAssistantTransportProtocol.reportResult(frame)) {
                    is PanelAssistantReportResult.Failed -> {
                        if (result.code == PanelAssistantTransportProtocol.CODE_SESSION_UNKNOWN) {
                            return PanelAssistantTransportProtocol.CODE_SESSION_UNKNOWN
                        }
                        log("native transport command_result refused: ${result.code}")
                    }
                    else -> Unit
                }
                continue
            }
            if (reporting != null && id != HELLO_ID) {
                val result = PanelAssistantTransportProtocol.reportResult(frame)
                if (result is PanelAssistantReportResult.Failed &&
                    result.code == PanelAssistantTransportProtocol.CODE_SESSION_UNKNOWN
                ) {
                    return PanelAssistantTransportProtocol.CODE_SESSION_UNKNOWN
                }
                result?.let { reporting.onResult(it, monotonicMillis()) }
                if (withdrawPending && reporting.fullSyncComplete()) {
                    withdrawPending = false
                    onMqttDiscovery(PanelAssistantTransportProtocol.MQTT_DISCOVERY_WITHDRAW)
                }
            }
        }
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (malformed: JSONException) {
        throw PanelAssistantProtocolException("Home Assistant sent a malformed frame")
    }

    private fun backoffDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val ceiling = (backoffBaseMs shl shift).coerceAtMost(backoffMaxMs)
        return jitter(ceiling).coerceIn(MIN_DELAY_MS, ceiling.coerceAtLeast(MIN_DELAY_MS))
    }

    private fun nextAttempt(current: Int): Int = minOf(current, MAX_ATTEMPT - 1) + 1

    private fun publish(run: Long, next: PanelAssistantTransportStatus) {
        val changed = synchronized(lock) {
            if (generation.get() != run) return
            val previous = status
            status = next
            // Attempts cycle through connecting and handshaking; only an outcome is worth a line.
            next.phase != PanelAssistantTransportPhase.CONNECTING &&
                next.phase != PanelAssistantTransportPhase.HANDSHAKING &&
                (previous.refusal != next.refusal || previous.slowRetry != next.slowRetry ||
                    previous.session != next.session || (previous.phase == PanelAssistantTransportPhase.STOPPED) !=
                    (next.phase == PanelAssistantTransportPhase.STOPPED))
        }
        if (changed) log("native transport ${next.describe()}")
    }

    companion object {
        internal const val TAG = "PanelAssistantTransport"
        private const val HELLO_ID = 1L
        private const val MAX_ATTEMPT = 1_000
        private const val MIN_DELAY_MS = 250L
        private const val CONFIRMATION_RETRY_MS = 5_000L

        const val REFUSAL_AUTH_INVALID = "auth_invalid"
        const val REFUSAL_CREDENTIAL_REJECTED = "credential_rejected"
        const val REFUSAL_CREDENTIAL_UNAVAILABLE = "credential_unavailable"
        const val REFUSAL_SESSION_CLOSED = "session_closed"
        const val REFUSAL_SESSION_SUPERSEDED = "session_superseded"
        const val REFUSAL_TRANSPORT = "transport_failure"

        /** Local session end: the bridge's channel set moved, so the session must be described again. */
        const val REASON_DESCRIPTORS_CHANGED = "descriptors_changed"
    }
}
