package io.panelassistant.android.panelassistant

import android.util.Log
import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.sensors.HaApiSessionProvider
import io.panelassistant.android.sensors.HaAuthenticationException
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

/** The panel's synchronised voice player, which Panel Assistant streams to over Sendspin inside the session. */
internal interface PanelAssistantVoiceStreamPeer {
    /** The panel's Sendspin client id (its persisted public key), or null when the player cannot run here. */
    fun clientId(): String?

    /** The session granted the stream: connect, sending each of the player's messages to [send] in order. */
    fun open(grant: PanelAssistantVoiceStreamGrant, closed: () -> Unit = {}, send: (frame: ByteArray, text: Boolean) -> Unit)

    /** One message Panel Assistant sent; called in session order. */
    fun receive(frame: ByteArray, text: Boolean)

    /** Silence the stream playing now, and any that starts, until [unmute]. */
    fun mute()

    fun unmute()

    /** The session that opened the stream ended. */
    fun close()
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
    val routeEpoch: Long = 0L,
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

/** What the panel's local control plane reports about the transport: codes and facts, no display text. */
internal data class PanelAssistantTransportFacts(
    val phase: PanelAssistantTransportPhase,
    /** The owner's last refusal or retry code, null while none applies. */
    val refusal: String?,
) {
    /** The `panel_assistant_transport` object of `GET /api/v1/status`. */
    fun statusJson(): String =
        JSONObject().put("phase", phase.name.lowercase()).putOpt("refusal", refusal).toString()
}

internal data class PanelAssistantTransportStatus(
    val phase: PanelAssistantTransportPhase = PanelAssistantTransportPhase.STOPPED,
    /** Consecutive attempts since the last accepted `hello`. */
    val attempt: Int = 0,
    /** The code that put the owner on the slow schedule, or the last refusal on the fast one. */
    val refusal: String? = null,
    val slowRetry: Boolean = false,
    val session: PanelAssistantSession? = null,
) {
    fun liveUpdatePolicy(): PanelAssistantUpdatePolicy? =
        session?.updatePolicy.takeIf { phase == PanelAssistantTransportPhase.CONNECTED }

    /** Log form. Carries no credential, identity or session token. */
    fun describe(): String = buildString {
        append(phase.name.lowercase())
        if (attempt > 0) append(" attempt=").append(attempt)
        refusal?.let { append(" refusal=").append(it) }
        if (slowRetry) append(" slow")
        session?.let {
            append(" protocol=").append(it.protocol)
            append(" integration=").append(it.integrationVersion)
            append(" capabilities=").append(it.capabilities)
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
 * for identity or account confirmation retries every 5 seconds, so approval becomes visible promptly.
 * Every wait, slow or fast, ends early on [nudge] (the default network returned) and on a demand
 * change (the credential or identity moved).
 *
 * `unknown_command` is also what a Home Assistant restart looks like from here: Core accepts
 * WebSocket connections before custom integrations finish loading. Within [warmupWindowMs] of demand
 * starting or a session ending, this code stays on the fast schedule, so a restart costs seconds
 * rather than the slow interval.
 *
 * Credentials come from the shared [HaApiSessionProvider]; this owner holds no token cache. It sends
 * `hello`, protocol pings, the [reporter]'s `report_state` requests on a session granted `state`,
 * and the answers to the commands a session delivers, which run on the panel's own command authority
 * through [commands]. A hello refused `entry_removed` waits on the slow schedule like any terminal
 * refusal, until the panel is added again.
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
    private val reporter: PanelAssistantStateReporter? = null,
    /** Samples the running panel before this accepted session starts its full state report. */
    private val observeForHello: suspend () -> Boolean,
    /** Null offers neither commands nor approval. */
    private val commands: PanelAssistantCommandSink? = null,
    /** Null offers no `management`, so Panel Assistant reads and writes this panel over HTTP. */
    private val management: PanelAssistantManagement? = null,
    private val approvalTtlMs: Long = io.panelassistant.android.security.ApprovalBroker.DEFAULT_TTL_MS,
    /** Evidence that a Panel Assistant answers this panel: an accepted hello, or an enrollment refusal only it sends. */
    private val onConnected: () -> Unit = {},
    private val onLifecycleNotice: (io.panelassistant.android.sensors.HaLifecycleNotice) -> Unit = {},
    private val onLifecycleAuthenticated: () -> Unit = {},
    private val onLifecycleDisconnected: () -> Unit = {},
    private val onLifecycleRetired: () -> Unit = {},
    /** The Assist satellite; null, or one reporting no configuration, offers no `voice`. */
    private val voice: PanelAssistantVoice? = null,
    /** The synchronised voice stream; null, or one whose player has no client id, offers no `voice_stream_session`. */
    private val voiceStream: PanelAssistantVoiceStream? = null,
    /** Fresh interface addresses for each hello; independent of demand identity. */
    private val addresses: () -> List<String> = { emptyList() },
    private val onConnection: (io.panelassistant.android.sensors.HaApiSession, PanelAssistantSession) -> Boolean = { _, _ -> true },
    private val checkPreferred: suspend () -> Boolean = { false },
    private val onTransportFailure: (io.panelassistant.android.HaConnectionRoute) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
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
     * Held for a generation's whole run, teardown included. The state reporter has no session identity, so
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
            onLifecycleRetired()
            // Replacement cancels this session immediately, even while its socket teardown keeps the
            // next generation behind the run mutex. No retired hello may authorize an update meanwhile.
            status = PanelAssistantTransportStatus(
                phase = if (next == null) PanelAssistantTransportPhase.STOPPED else PanelAssistantTransportPhase.CONNECTING,
            )
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
        if (status.phase == PanelAssistantTransportPhase.CONNECTED && status.session != null) {
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

    /** This owner's current phase and refusal. */
    fun facts(): PanelAssistantTransportFacts {
        val current = status
        return PanelAssistantTransportFacts(current.phase, current.refusal)
    }

    override fun close() {
        restart.get()?.acknowledged?.complete(false)
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation.incrementAndGet()
            onLifecycleRetired()
            job?.cancel()
            job = null
            demand = null
        }
        status = PanelAssistantTransportStatus()
    }

    /** Serialize reporting with demand retirement, so an old session cannot overwrite its successor. */
    private fun lifecycleCurrent(run: Long, report: () -> Unit) = synchronized(lock) {
        if (generation.get() == run && !stopped) report()
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
            var attemptedRoute: io.panelassistant.android.HaConnectionRoute? = null
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
                        attemptedRoute = session.route
                        val opened = connector.connect(session.baseUrl, token)
                        connection = opened
                        publish(run, PanelAssistantTransportStatus(PanelAssistantTransportPhase.HANDSHAKING, attempt))
                        val streamClientId = voiceStream?.clientId()
                        val offered = PanelAssistantTransportProtocol.CAPABILITIES.filter { capability ->
                            when (capability) {
                                PanelAssistantTransportProtocol.CAPABILITY_STATE -> reporter != null
                                PanelAssistantTransportProtocol.CAPABILITY_VOICE -> voice?.offered() == true
                                PanelAssistantTransportProtocol.CAPABILITY_MEDIA,
                                PanelAssistantTransportProtocol.CAPABILITY_UNKNOWN_STATE,
                                -> reporter != null
                                PanelAssistantTransportProtocol.CAPABILITY_VOICE_STREAM_SESSION -> streamClientId != null
                                // The sidebar relay, its subscriptions and the bundle ride the management requests.
                                PanelAssistantTransportProtocol.CAPABILITY_MANAGEMENT,
                                PanelAssistantTransportProtocol.CAPABILITY_UI_SUBSCRIBE,
                                PanelAssistantTransportProtocol.CAPABILITY_UI_BUNDLE,
                                PanelAssistantTransportProtocol.CAPABILITY_REST_RETIRED,
                                -> management != null
                                else -> commands != null
                            }
                        }
                        val offer = reporter?.offer()
                        val described = offer?.descriptors.orEmpty()
                        when (val outcome = handshake(opened, demand.identity, offered, described, offer?.unsupported.orEmpty(), streamClientId)) {
                            is PanelAssistantHelloOutcome.Accepted -> {
                                if (generation.get() != run || !onConnection(session, outcome.session)) {
                                    throw PanelAssistantProtocolException("Home Assistant connection owner changed")
                                }
                                outcome.session.lifecycle?.let { notice -> lifecycleCurrent(run) { onLifecycleNotice(notice) } }
                                lifecycleCurrent(run, onLifecycleAuthenticated)
                                onConnected()
                                attempt = 0
                                authRefreshed = false
                                publish(run, PanelAssistantTransportStatus(
                                    PanelAssistantTransportPhase.CONNECTED,
                                    session = outcome.session,
                                ))
                                hadSession = true
                                val reporting = reporter?.takeIf {
                                    PanelAssistantTransportProtocol.CAPABILITY_STATE in outcome.session.capabilities
                                }
                                // Commands run only on a session that granted them.
                                val commanding = commands?.takeIf {
                                    PanelAssistantTransportProtocol.CAPABILITY_COMMANDS in outcome.session.capabilities
                                }?.let { sink ->
                                    PanelAssistantCommandProcessor(
                                        sink = sink,
                                        session = outcome.session,
                                        channels = described,
                                        monotonicMillis = monotonicMillis,
                                        approvalTtlMs = approvalTtlMs,
                                        baseUrl = session.baseUrl,
                                        log = log,
                                    )
                                }
                                val speaking = voice?.takeIf {
                                    PanelAssistantTransportProtocol.CAPABILITY_VOICE in outcome.session.capabilities
                                }
                                val streamGrant = outcome.session.voiceStream
                                val streaming = voiceStream?.takeIf { streamGrant != null }
                                val reason = try {
                                    if (reporting != null && !observeForHello()) {
                                        attemptedRoute = null // a local owner change, not the route's fault
                                        throw PanelAssistantProtocolException("panel state owner changed during hello")
                                    }
                                    reporting?.open(
                                        described,
                                        PanelAssistantTransportProtocol.CAPABILITY_UNKNOWN_STATE in outcome.session.capabilities,
                                    )
                                    speaking?.open(opened, outcome.session.token, session.baseUrl)
                                    // Start the handshake only once initial sampling can no longer block its carrier.
                                    if (streaming != null && streamGrant != null) streaming.open(outcome.session.token, streamGrant)
                                    holdSession(run, opened, outcome.session, reporting, commanding, speaking, streaming)
                                } finally {
                                    publish(run, PanelAssistantTransportStatus(PanelAssistantTransportPhase.WAITING, attempt))
                                    speaking?.close()
                                    streaming?.close()
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
                                lifecycleCurrent(run, onLifecycleAuthenticated)
                                // A panel awaiting its owner's confirmation is managed by Panel Assistant, not missing it.
                                if (outcome.code in ENROLLMENT_REFUSALS) onConnected()
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
                lifecycleCurrent(run, onLifecycleDisconnected)
                attemptedRoute?.let(onTransportFailure)
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
                lifecycleCurrent(run, onLifecycleDisconnected)
                attemptedRoute?.let(onTransportFailure)
                // Network, TLS, frame bound, closed socket, malformed reply or liveness: never parks.
                Retry.Fast(REFUSAL_TRANSPORT).also {
                    log("native transport attempt failed: ${failure.javaClass.simpleName}")
                }
            } finally {
                // A known-ended session cannot authorize updates while bounded socket cleanup waits.
                // This also covers an accepted hello whose setup failed before holdSession began.
                publish(run, PanelAssistantTransportStatus(PanelAssistantTransportPhase.WAITING, attempt))
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
                    if (retry.refusal in ENROLLMENT_REFUSALS) {
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
            // A nudge that arrived while connected, or before Home Assistant answered with a reason to
            // wait slowly, describes a network that is already in use.
            if (hadSession || retry is Retry.Slow) nudges.tryReceive()
            withTimeoutOrNull(delayMs) { nudges.receive() }
        }
    }

    private fun refusalRetry(code: String, warm: Boolean): Retry = when (code) {
        in ENROLLMENT_REFUSALS -> Retry.Fast(code)
        PanelAssistantTransportProtocol.CODE_UNKNOWN_COMMAND -> if (warm) Retry.Fast(code) else Retry.Slow(code)
        else -> Retry.Slow(code)
    }

    private suspend fun handshake(
        connection: PanelAssistantTransportConnection,
        identity: PanelAssistantHelloIdentity,
        offered: List<String>,
        described: List<PanelAssistantChannelDescriptor>,
        unsupported: List<String>,
        voiceStreamClientId: String?,
    ): PanelAssistantHelloOutcome {
        val currentAddresses = try {
            addresses()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        connection.send(PanelAssistantTransportProtocol.hello(
            HELLO_ID, identity, offered, described, unsupported, currentAddresses, voiceStreamClientId,
        ))
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
        run: Long,
        connection: PanelAssistantTransportConnection,
        session: PanelAssistantSession,
        reporting: PanelAssistantStateReporter?,
        commanding: PanelAssistantCommandProcessor?,
        speaking: PanelAssistantVoice?,
        streaming: PanelAssistantVoiceStream?,
    ): String = coroutineScope {
        val frames = Channel<String>(Channel.UNLIMITED)
        val managing = management?.takeIf {
            PanelAssistantTransportProtocol.CAPABILITY_MANAGEMENT in session.capabilities
        // Parented on the owner, not this session scope: a snapshot build that cannot be interrupted must
        // never hold the session's teardown, and with it the reconnect, until it finishes.
        }?.let { PanelAssistantManagementRequests(scope, it, session, monotonicMillis) }
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
            sessionLoop(run, connection, session, reporting, commanding, managing, frames, speaking, streaming)
        } finally {
            managing?.close()
            reader.cancel()
        }
    }

    private suspend fun sessionLoop(
        run: Long,
        connection: PanelAssistantTransportConnection,
        session: PanelAssistantSession,
        reporting: PanelAssistantStateReporter?,
        commanding: PanelAssistantCommandProcessor?,
        managing: PanelAssistantManagementRequests?,
        frames: Channel<String>,
        speaking: PanelAssistantVoice?,
        streaming: PanelAssistantVoiceStream?,
    ): String {
        var nextMessageId = HELLO_ID + 1
        // Ids of command_result requests awaiting their result, kept apart from report_state results.
        val answering = HashSet<Long>()
        val restartAnswers = HashMap<Long, CompletableDeferred<Boolean>>()
        var sentRestart: RestartRequest? = null
        var nextPingAt = monotonicMillis() + pingIntervalMs
        var pongDeadline: Long? = null
        if (reporting != null) log("native transport state reporting started")
        while (true) {
            val now = monotonicMillis()
            val notice = restart.get()
            if (notice != null && now >= notice.expiresAtMs) notice.acknowledged.complete(false)
            if (notice != null && notice !== sentRestart && !notice.acknowledged.isCompleted) {
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
                if (checkPreferred()) return "preferred_route"
                connection.send(PanelAssistantTransportProtocol.ping(nextMessageId++))
                pongDeadline = monotonicMillis() + pongTimeoutMs
                nextPingAt = monotonicMillis() + pingIntervalMs
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
            val managed = managing?.next(nextMessageId)
            if (managed != null) {
                connection.send(managed)
                answering += nextMessageId++
                continue
            }
            val spoken = speaking?.next(nextMessageId) ?: streaming?.next(nextMessageId)
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
                managing?.wake?.onReceive { null }
                restartWake.onReceive { null }
                speaking?.wake?.onReceive { null }
                streaming?.wake?.onReceive { null }
                onTimeout((due - now).coerceAtLeast(0L)) { null }
            } ?: continue
            pongDeadline = null
            val frame = parse(text)
            if (streaming != null && streaming.onFrame(frame, HELLO_ID)) continue
            if (speaking != null && speaking.onFrame(frame, HELLO_ID)) continue
            when (val event = PanelAssistantTransportProtocol.sessionEvent(frame, HELLO_ID)) {
                is PanelAssistantSessionEvent.Lifecycle -> lifecycleCurrent(run) { onLifecycleNotice(event.notice) }
                is PanelAssistantSessionEvent.Closed -> return event.reason
                // A command the sidebar relays runs as its browser's user, through the same door as a `/ui` frame.
                is PanelAssistantSessionEvent.Command -> if (event.origin != null) {
                    managing?.onRelayed(event) ?: log("native transport ignored a relayed command: management not granted")
                } else {
                    commanding?.onCommand(event) ?: log("native transport ignored a command: commands not offered")
                }
                is PanelAssistantSessionEvent.Manage ->
                    managing?.onRequest(event) ?: log("native transport ignored a management request: not granted")
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
        /** Refusals that only a Panel Assistant sends, each resolvable at any moment by its owner's confirmation. */
        private val ENROLLMENT_REFUSALS = setOf(
            PanelAssistantTransportProtocol.CODE_PANEL_USER_MISMATCH,
            PanelAssistantTransportProtocol.CODE_UNKNOWN_PANEL,
        )
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
