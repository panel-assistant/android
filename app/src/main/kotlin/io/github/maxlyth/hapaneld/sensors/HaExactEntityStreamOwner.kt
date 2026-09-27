package io.github.maxlyth.hapaneld.sensors

import android.util.Log
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.ktor.client.HttpClient
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.github.maxlyth.hapaneld.util.HaWebSocketClients
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal enum class HaExactEntityConsumer { AMBIENT_LUX }

internal enum class HaExactEntityStreamPhase {
    DISABLED,
    AUTHENTICATING,
    CONNECTING,
    SUBSCRIBING,
    SYNCHRONIZING,
    LIVE,
    AUTH_FAILED,
    RECONNECTING,
    STOPPED,
}

internal data class HaExactEntityStreamStatus(
    val consumer: HaExactEntityConsumer = HaExactEntityConsumer.AMBIENT_LUX,
    val entityId: String? = null,
    val phase: HaExactEntityStreamPhase = HaExactEntityStreamPhase.DISABLED,
    val detail: String = "",
    val reconnectAttempt: Int = 0,
)

internal sealed interface HaExactEntityUpdate {
    val entityId: String
    val initial: Boolean

    data class State(
        override val entityId: String,
        val json: JSONObject,
        override val initial: Boolean,
    ) : HaExactEntityUpdate

    data class Missing(
        override val entityId: String,
        override val initial: Boolean,
    ) : HaExactEntityUpdate
}

internal interface HaExactEntityStreamObserver {
    fun onStatus(status: HaExactEntityStreamStatus)
    fun onUpdate(update: HaExactEntityUpdate)
}

/** Retained even when intermediate snapshots are coalesced, so an ON -> OFF edge is not erased. */
internal data class HaPresenceActivityMarker(
    val sequence: Long,
    val entityId: String,
    val receivedAtMonotonicMs: Long,
)

internal data class HaPresenceFeedSnapshot(
    val generation: Long = 0L,
    val revision: Long = 0L,
    val sourceIds: Set<String> = emptySet(),
    val states: Map<String, HaPresenceValue> = emptyMap(),
    val hydrated: Boolean = false,
    val phase: HaExactEntityStreamPhase = HaExactEntityStreamPhase.DISABLED,
    val detail: String = "",
    val reconnectAttempt: Int = 0,
    val lastOnActivity: HaPresenceActivityMarker? = null,
)

internal fun interface HaPresenceFeedObserver {
    fun onSnapshot(snapshot: HaPresenceFeedSnapshot)
}

internal sealed interface HaExactSocketMessage {
    data class State(val entityId: String, val json: JSONObject) : HaExactSocketMessage {
        constructor(json: JSONObject) : this(json.optString("entity_id"), json)
    }
    data class Missing(val entityId: String) : HaExactSocketMessage

    /**
     * A pong, stamped where the frame was DECODED so the round trip excludes the channel hop and the
     * consumer coroutine's scheduling on a loaded panel. `-1` means the transport did not stamp it
     * and the owner falls back to its own clock at the moment of matching.
     */
    data class Pong(val id: Int, val receivedAtMs: Long = -1L) : HaExactSocketMessage
    data object RegistryChanged : HaExactSocketMessage

    /**
     * One Home Assistant lifecycle event. The type is resolved from the subscription id we chose, not
     * from the frame body, so no event payload is ever read or forwarded.
     */
    data class Lifecycle(val event: HaLifecycleEvent) : HaExactSocketMessage

    /**
     * Home Assistant refused a lifecycle subscription. Deliberately a message rather than an exception:
     * these types are absent from the non-admin allowlist, and a throw here would tear down the shared
     * stream that ambient light and automatic sleep depend on.
     */
    data object LifecycleRejected : HaExactSocketMessage

    /** Home Assistant rejected the one lifecycle route whose outcome gates LIVE recovery. */
    data object LifecycleStartedRejected : HaExactSocketMessage

    /** Home Assistant accepted a lifecycle subscription; this session will hear the startup events. */
    data object LifecycleEstablished : HaExactSocketMessage

    data object Other : HaExactSocketMessage
}

/** What the lifecycle consumer is told, without exposing the socket itself. */
internal sealed interface HaLifecycleSignal {
    data class Event(val event: HaLifecycleEvent) : HaLifecycleSignal

    data object Rejected : HaLifecycleSignal

    /** This session holds a live lifecycle subscription, so a startup will announce itself. */
    data object Established : HaLifecycleSignal
    /** The link or watch was replaced; the predecessor's claims no longer describe this endpoint. */
    data object Retired : HaLifecycleSignal
    data class Transport(val phase: HaExactEntityStreamPhase) : HaLifecycleSignal
}

internal fun interface HaLifecycleObserver {
    fun onSignal(signal: HaLifecycleSignal)
}

/**
 * The Home Assistant WebSocket command ids for one connection.
 *
 * Extracted from the transport so the arithmetic is assertable without a socket. It has to be exact:
 * ping replies are correlated by subtracting [pingIdOffset], so a ping id that collides with any
 * subscription id would make a subscription's own reply look like a pong and silently defeat the
 * liveness check.
 *
 * Pure — unit-tested in `HaSubscriptionIdsTest`.
 */
internal data class HaSubscriptionIds(
    val entityBatchIds: List<Int>,
    val registryIds: Set<Int>,
    val lifecycleIds: Map<Int, HaLifecycleEvent>,
    val pingIdOffset: Int,
) {
    val allSubscriptionIds: Set<Int> get() = entityBatchIds.toSet() + registryIds + lifecycleIds.keys
}

internal fun haSubscriptionIds(
    entityBatchCount: Int,
    watchRegistry: Boolean,
    watchLifecycle: Boolean,
    registryEventCount: Int,
): HaSubscriptionIds {
    require(entityBatchCount >= 0 && registryEventCount >= 0)
    val entityIds = (0 until entityBatchCount).map { HA_FIRST_SUBSCRIPTION_ID + it }
    val registryIds = if (!watchRegistry) emptySet() else
        (0 until registryEventCount).map { entityBatchCount + it + 1 }.toSet()
    val lifecycleIds = if (!watchLifecycle) emptyMap() else
        HaLifecycleEvent.entries.associateBy { entityBatchCount + registryIds.size + it.rank }
    return HaSubscriptionIds(
        entityBatchIds = entityIds,
        registryIds = registryIds,
        lifecycleIds = lifecycleIds,
        pingIdOffset = entityBatchCount + registryIds.size + lifecycleIds.size,
    )
}

private const val HA_FIRST_SUBSCRIPTION_ID = 1

/** Where an inbound `event` frame belongs, decided from its subscription id alone. */
internal sealed interface HaEventRoute {
    data object Registry : HaEventRoute
    data class Lifecycle(val event: HaLifecycleEvent) : HaEventRoute
    data object Entities : HaEventRoute
}

internal fun haEventRoute(
    id: Int,
    registryIds: Set<Int>,
    lifecycleIds: Map<Int, HaLifecycleEvent>,
): HaEventRoute = when {
    id in registryIds -> HaEventRoute.Registry
    lifecycleIds.containsKey(id) -> HaEventRoute.Lifecycle(lifecycleIds.getValue(id))
    else -> HaEventRoute.Entities
}

/** What a failed `result` frame means for the shared stream. */
internal sealed interface HaResultOutcome {
    data object Ignored : HaResultOutcome

    /**
     * Home Assistant ACCEPTED a lifecycle subscription, so this session will be told when the server
     * starts. That is the only thing separating "we will hear `homeassistant_started`" from "nothing
     * will ever tell us", and the recovery announcement depends on knowing which.
     */
    data object LifecycleEstablished : HaResultOutcome
    data object LifecycleStartedRejected : HaResultOutcome
    data object LifecycleRejected : HaResultOutcome
    data class Fatal(val message: String) : HaResultOutcome
}

/**
 * Classify a `result` frame.
 *
 * This is a pure function precisely so the non-fatal branch is provable. The lifecycle event types are
 * absent from Home Assistant's non-admin subscribe allowlist, so a refusal is routine — and treating it
 * as fatal would park the shared stream after three attempts and take ambient light and automatic sleep
 * down with it on every non-admin panel.
 *
 * Unit-tested in `HaSocketFrameRoutingTest`.
 */
internal fun haResultOutcome(
    json: JSONObject,
    lifecycleIds: Map<Int, HaLifecycleEvent>,
    maxErrorChars: Int,
): HaResultOutcome {
    val lifecycleEvent = lifecycleIds[json.optInt("id")]
    return when {
        // Only acceptance of STARTED promises the exact event recovery waits for. The other four
        // subscriptions are independently authorized and cannot stand in for it.
        json.optBoolean("success") && lifecycleEvent == HaLifecycleEvent.STARTED ->
            HaResultOutcome.LifecycleEstablished
        json.optBoolean("success") -> HaResultOutcome.Ignored
        lifecycleEvent == HaLifecycleEvent.STARTED -> HaResultOutcome.LifecycleStartedRejected
        lifecycleEvent != null -> HaResultOutcome.LifecycleRejected
        else -> HaResultOutcome.Fatal(
            json.optJSONObject("error")?.optString("message")?.take(maxErrorChars)
                ?.takeIf(String::isNotBlank)
                ?: "Home Assistant rejected the entity subscription",
        )
    }
}

internal interface HaExactEntityConnection {
    suspend fun receive(): HaExactSocketMessage
    suspend fun ping(id: Int)
    suspend fun close()
}

internal interface HaExactEntityStreamTransport {
    suspend fun subscribe(baseUrl: String, accessToken: String, entityIds: Set<String>): HaExactEntityConnection
    suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        watchRegistry: Boolean,
    ): HaExactEntityConnection = subscribe(baseUrl, accessToken, entityIds)
    suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        watchRegistry: Boolean,
        watchLifecycle: Boolean,
    ): HaExactEntityConnection = subscribe(baseUrl, accessToken, entityIds, watchRegistry)
    suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject?
}

private class HaExactEntitySetupException(message: String) : RuntimeException(message)

/**
 * A probe went unanswered for the whole pong timeout. A protocol failure for the reconnect policy
 * (unchanged), but distinguished so the network-path report is made once, where it was detected,
 * and not again as a server failure in the generic protocol catch.
 */
internal class HaStreamLivenessException : HaProtocolException("Home Assistant stream liveness check timed out")

/**
 * Attribute one failed connection attempt to the path or to the server, from the exception chain.
 *
 * Pure so every branch is assertable. The rule: an I/O failure that means "nothing answered" is the
 * path (a connect that timed out, a host that could not be resolved, no route); an I/O failure that
 * means "something answered and said no" is the server (connection refused: the host is reachable
 * and the port is closed, which is a Home Assistant that is down or restarting, not a lost link).
 * A closed channel is the peer or our own teardown closing an established socket, so it is the
 * server's doing. Everything that is not I/O at all (an HTTP upgrade error, a bad frame, a JSON
 * failure) came over a working path and is the server's as well.
 *
 * Order matters: Ktor's `ConnectTimeoutException` EXTENDS `java.net.ConnectException` (verified in
 * ktor-client-core-jvm 3.5.2), so the timeout must be tested before its refused-connection parent.
 */
internal fun haPathFailureKind(error: Throwable): HaPathFailureKind {
    for (cause in generateSequence(error) { it.cause }) {
        when (cause) {
            is HaAuthenticationException -> return HaPathFailureKind.AUTH
            is io.ktor.client.network.sockets.ConnectTimeoutException,
            is java.net.UnknownHostException,
            is java.net.NoRouteToHostException,
            is java.net.SocketTimeoutException,
            -> return HaPathFailureKind.NETWORK
            is java.net.ConnectException -> return HaPathFailureKind.SERVER
            // ENETUNREACH / EHOSTUNREACH arrive as a plain SocketException on Android and Linux
            // ("Network is unreachable", "Host is unreachable"): no path at all. Every other
            // SocketException (reset, broken pipe) is a peer that was there and went away.
            is java.net.SocketException ->
                if (cause.message?.contains("unreachable", ignoreCase = true) == true) return HaPathFailureKind.NETWORK
        }
    }
    return HaPathFailureKind.SERVER
}

/**
 * The service-owned lifecycle authority for the bounded union of exact Home Assistant entities.
 *
 * Ambient light and automatic sleep share one authenticated exact-entity union, hydration pass,
 * reconnect policy and liveness timer. Empty demand cancels the generation and owns no socket,
 * coroutine or timer. Outbound requests are byte-batched rather than entity-count limited;
 * oversized inbound frames remain a safe transport failure.
 */
internal class HaExactEntityStreamOwner(
    private val scope: CoroutineScope,
    private val auth: HaApiSessionProvider,
    private val transport: HaExactEntityStreamTransport,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Fixed probe cadence while LIVE, regardless of entity traffic. It used to be an idle timer that
     * pinged only after this long with no inbound frame, so a busy subscription never pinged at all
     * and the one path measurement the panel has was biased to exactly the quiet moments. The pong
     * timeout and the teardown it triggers are unchanged; only WHEN a ping goes out has changed.
     */
    private val probeIntervalMs: Long = HaNetworkPath.PROBE_INTERVAL_MS,
    private val pongTimeoutMs: Long = DEFAULT_PONG_TIMEOUT_MS,
    private val reconnectBaseMs: Long = DEFAULT_RECONNECT_BASE_MS,
    private val reconnectMaxMs: Long = DEFAULT_RECONNECT_MAX_MS,
    private val subscribeTimeoutMs: Long = DEFAULT_SUBSCRIBE_TIMEOUT_MS,
    private val hydrationTimeoutMs: Long = DEFAULT_HYDRATION_TIMEOUT_MS,
    private val closeTimeoutMs: Long = DEFAULT_CLOSE_TIMEOUT_MS,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private data class BufferedActivity(
        val count: Long,
        val observedAtEpochMs: Long?,
        val receivedAtMonotonicMs: Long,
    )

    private data class Request(
        val ambient: String?,
        val presence: Set<String>,
        val watchRegistry: Boolean = false,
        val watchLifecycle: Boolean = false,
        val haLink: HaAuthOwner? = null,
    ) {
        val union: Set<String> = buildSet {
            ambient?.let(::add)
            addAll(presence)
        }
        val active: Boolean get() = union.isNotEmpty() || watchRegistry || watchLifecycle
    }

    private sealed interface PendingCallback {
        data class AmbientStatus(
            val run: Long,
            val target: HaExactEntityStreamObserver,
            val status: HaExactEntityStreamStatus,
        ) : PendingCallback

        data class AmbientUpdate(
            val run: Long,
            val target: HaExactEntityStreamObserver,
            val update: HaExactEntityUpdate,
        ) : PendingCallback

        data class Presence(
            val run: Long,
            val target: HaPresenceFeedObserver,
            val snapshot: HaPresenceFeedSnapshot,
        ) : PendingCallback

        data class FinalAmbient(
            val target: HaExactEntityStreamObserver,
            val status: HaExactEntityStreamStatus,
        ) : PendingCallback

        data class FinalPresence(
            val target: HaPresenceFeedObserver,
            val snapshot: HaPresenceFeedSnapshot,
        ) : PendingCallback

        data class RegistryChanged(
            val run: Long,
            val target: () -> Unit,
        ) : PendingCallback

        data class Lifecycle(
            val run: Long,
            val target: HaLifecycleObserver,
            val signal: HaLifecycleSignal,
        ) : PendingCallback

        /** Ordered after an in-flight old callback, but not discarded by a later generation. */
        data class LifecycleRetired(val target: HaLifecycleObserver) : PendingCallback
    }

    private val generation = AtomicLong()
    private val lock = Any()
    private val callbackQueue = ArrayDeque<PendingCallback>()
    @Volatile private var request = Request(null, emptySet())
    @Volatile private var sourceJob: Job? = null
    @Volatile private var stopped = false
    @Volatile private var ambientObserver: HaExactEntityStreamObserver? = null
    @Volatile private var presenceObserver: HaPresenceFeedObserver? = null
    @Volatile private var registryChangeObserver: (() -> Unit)? = null
    @Volatile private var lifecycleObserver: HaLifecycleObserver? = null
    @Volatile private var networkPathObserver: HaNetworkPathObserver? = null

    /** The layer-3 echo probe, when one is bound. Nothing in the stream depends on it existing. */
    @Volatile private var pathProbe: PathProbeMonitor? = null
    private var drainingCallbacks = false
    private var presenceRevision = 0L
    private var presencePhase = HaExactEntityStreamPhase.DISABLED
    private var presenceDetail = ""
    private var presenceReconnectAttempt = 0
    private var presenceHydrated = false
    private val presenceStates = linkedMapOf<String, HaPresenceValue>()
    private val presenceObservedAt = linkedMapOf<String, Long>()
    private var activitySequence = 0L
    private var lastActivity: HaPresenceActivityMarker? = null

    fun bindAmbient(next: HaExactEntityStreamObserver) {
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(ambientObserver == null || ambientObserver === next) { "ambient exact-entity observer is already bound" }
            ambientObserver = next
        }
    }

    fun unbindAmbient(expected: HaExactEntityStreamObserver) {
        synchronized(lock) {
            if (ambientObserver === expected) ambientObserver = null
        }
    }

    fun bindPresence(next: HaPresenceFeedObserver) {
        var snapshot: HaPresenceFeedSnapshot? = null
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(presenceObserver == null || presenceObserver === next) { "presence exact-entity observer is already bound" }
            presenceObserver = next
            snapshot = presenceSnapshotLocked(generation.get())
        }
        snapshot?.let { safeCallback { next.onSnapshot(it) } }
    }

    fun unbindPresence(expected: HaPresenceFeedObserver) {
        synchronized(lock) {
            if (presenceObserver === expected) presenceObserver = null
        }
    }

    fun bindRegistryChanges(next: () -> Unit) {
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(registryChangeObserver == null || registryChangeObserver === next) {
                "registry-change observer is already bound"
            }
            registryChangeObserver = next
        }
    }

    fun unbindRegistryChanges() {
        synchronized(lock) { registryChangeObserver = null }
    }

    fun bindLifecycle(next: HaLifecycleObserver) {
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(lifecycleObserver == null || lifecycleObserver === next) {
                "lifecycle observer is already bound"
            }
            lifecycleObserver = next
        }
    }

    /**
     * Bind the network-path monitor. It is told the CURRENT demand at once so a monitor bound after
     * the socket was demanded does not sit unreportable until the next demand change.
     */
    fun bindNetworkPath(next: HaNetworkPathObserver) {
        val active = synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(networkPathObserver == null || networkPathObserver === next) {
                "network-path observer is already bound"
            }
            networkPathObserver = next
            request.active
        }
        safeCallback { next.onSocketState(if (active) HaSocketState.CONNECTING else HaSocketState.STOPPED) }
    }

    fun unbindNetworkPath() {
        synchronized(lock) { networkPathObserver = null }
    }

    /**
     * Bind the layer-3 echo probe. Optional by construction: a panel whose platform refuses an ICMP
     * socket, or a build with no probe bound at all, keeps exactly the behaviour it had before.
     */
    fun bindPathProbe(next: PathProbeMonitor) {
        val active = synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            check(pathProbe == null || pathProbe === next) { "path probe is already bound" }
            pathProbe = next
            request.active
        }
        safeCallback { next.onSocketState(if (active) HaSocketState.CONNECTING else HaSocketState.STOPPED) }
    }

    fun unbindPathProbe() {
        synchronized(lock) { pathProbe = null }
    }

    /**
     * Keeps the shared Home Assistant socket alive for lifecycle events. Unlike the entity and registry
     * demands this can be the ONLY reason a socket exists, which is deliberate: a panel that renders a
     * dashboard needs to explain a server outage even when it subscribes to no entity at all.
     */
    fun replaceLifecycleWatch(enabled: Boolean) {
        replaceRequest { it.copy(watchLifecycle = enabled) }
    }

    /** A changed Home Assistant credential owner retires the socket selected under the old link. */
    fun replaceHaLink(next: HaAuthOwner) {
        replaceRequest { it.copy(haLink = next) }
    }

    fun replaceAmbientSource(nextEntityId: String?) {
        val normalized = nextEntityId?.trim()?.takeIf(String::isNotEmpty)?.also(::validateEntityId)
        replaceRequest { it.copy(ambient = normalized) }
    }

    fun replacePresenceSources(nextEntityIds: Set<String>) {
        val normalized = nextEntityIds.mapTo(sortedSetOf()) { it.trim().lowercase(Locale.ROOT).also(::validateEntityId) }
        var drain = false
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            if (normalized == request.presence && sourceJob?.isActive == true) {
                drain = enqueuePresenceLocked(generation.get())
            }
        }
        if (drain) {
            drainCallbacks()
            return
        }
        if (normalized == request.presence && sourceJob?.isActive == true) return
        replaceRequest { it.copy(presence = normalized) }
    }

    /** Keeps the shared HA socket alive for registry events even when no entity source is selected. */
    fun replacePresenceRegistryWatch(enabled: Boolean) {
        replaceRequest { it.copy(watchRegistry = enabled) }
    }

    fun replacePresenceDemand(nextEntityIds: Set<String>, watchRegistry: Boolean) {
        val normalized = nextEntityIds.mapTo(sortedSetOf()) {
            it.trim().lowercase(Locale.ROOT).also(::validateEntityId)
        }
        val same = synchronized(lock) {
            !stopped && request.presence == normalized && request.watchRegistry == watchRegistry
        }
        if (same) {
            replacePresenceSources(normalized)
            return
        }
        replaceRequest { it.copy(presence = normalized, watchRegistry = watchRegistry) }
    }

    private fun replaceRequest(transform: (Request) -> Request) {
        var run = 0L
        var next = Request(null, emptySet())
        var demandChanged = false
        var drainLifecycle = false
        var pathObserver: HaNetworkPathObserver? = null
        synchronized(lock) {
            check(!stopped) { "exact entity stream owner is closed" }
            next = transform(request)
            if (next == request && sourceJob?.isActive == true) return
            demandChanged = next.active != request.active
            val retireLifecycle = request.watchLifecycle &&
                (!next.watchLifecycle || next.haLink != request.haLink)
            pathObserver = networkPathObserver
            sourceJob?.cancel()
            sourceJob = null
            request = next
            run = generation.incrementAndGet()
            resetPresenceLocked(next.presence)
            if (retireLifecycle) lifecycleObserver?.let {
                callbackQueue.addLast(PendingCallback.LifecycleRetired(it))
                if (!drainingCallbacks) {
                    drainingCallbacks = true
                    drainLifecycle = true
                }
            }
            if (next.active) sourceJob = scope.launch { runSource(run, next) }
        }
        if (drainLifecycle) drainCallbacks()
        // Demand on or off is what makes the path verdict reportable; a change of union or watch
        // bits with the socket still wanted is not a demand change and is not announced.
        if (demandChanged) {
            val state = if (next.active) HaSocketState.CONNECTING else HaSocketState.STOPPED
            pathObserver?.let { observer -> safeCallback { observer.onSocketState(state) } }
            // The echo probe follows the same ownership. Demand going off ends its session, which is
            // what discards a burst still in flight against the route that is going away.
            pathProbe?.let { probe -> safeCallback { probe.onSocketState(state) } }
        }
        if (next.ambient == null) publishAmbientStatus(
            run,
            HaExactEntityStreamStatus(phase = HaExactEntityStreamPhase.DISABLED),
        )
        if (next.presence.isEmpty()) publishPresenceStatus(run, HaExactEntityStreamPhase.DISABLED)
    }

    override fun close() {
        var drain = false
        var pathObserver: HaNetworkPathObserver? = null
        var probeAtClose: PathProbeMonitor? = null
        var hadDemand = false
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation.incrementAndGet()
            sourceJob?.cancel()
            sourceJob = null
            hadDemand = request.active
            pathObserver = networkPathObserver
            probeAtClose = pathProbe
            networkPathObserver = null
            request = Request(null, emptySet())
            callbackQueue.clear()
            ambientObserver?.let {
                callbackQueue.addLast(PendingCallback.FinalAmbient(
                    it,
                    HaExactEntityStreamStatus(phase = HaExactEntityStreamPhase.STOPPED),
                ))
            }
            presenceObserver?.let {
                callbackQueue.addLast(PendingCallback.FinalPresence(
                    it,
                    HaPresenceFeedSnapshot(phase = HaExactEntityStreamPhase.STOPPED),
                ))
            }
            ambientObserver = null
            presenceObserver = null
            registryChangeObserver = null
            lifecycleObserver = null
            if (!drainingCallbacks && callbackQueue.isNotEmpty()) {
                drainingCallbacks = true
                drain = true
            }
        }
        // A closed owner holds no socket, so the verdict it fed becomes unreportable with it.
        if (hadDemand) {
            pathObserver?.let { observer -> safeCallback { observer.onSocketState(HaSocketState.STOPPED) } }
            probeAtClose?.let { probe -> safeCallback { probe.onSocketState(HaSocketState.STOPPED) } }
        }
        if (drain) drainCallbacks()
    }

    private fun reportPath(kind: HaPathFailureKind) {
        networkPathObserver?.let { observer -> safeCallback { observer.onConnectionFailure(kind) } }
    }

    /**
     * Start a layer-3 burst if one is due, off this coroutine.
     *
     * The burst blocks for as long as its echoes take, so it must never sit on the socket's own
     * probe path. `runBurst` contains its own failures, and the launch adds a second boundary so a
     * diagnostic can never reach the supervisor and take the process down.
     */
    private fun dispatchPathBurst(probe: PathProbeMonitor) {
        val claim = runCatching { probe.claimBurst(monotonicMillis()) }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { probe.runBurst(claim, monotonicMillis) }
        }
    }

    /** Publish the socket's own state to the network-path monitor; it owns nothing else. */
    private fun reportSocketState(state: HaSocketState) {
        networkPathObserver?.let { observer -> safeCallback { observer.onSocketState(state) } }
        // The echo probe follows the same authenticated socket, for the same reason: there is no path
        // worth describing until one is actually held.
        pathProbe?.let { probe -> safeCallback { probe.onSocketState(state) } }
    }

    private suspend fun runSource(run: Long, expected: Request) {
        var attempt = 0
        var forceAuth = false
        var authRefreshAttempted = false
        var protocolFailures = 0
        var acceptedOwner: HaAuthOwner? = null
        var registryConnectedOnce = false
        try {
        while (scope.isActive && current(run, expected)) {
            var connection: HaExactEntityConnection? = null
            try {
                reportSocketState(HaSocketState.CONNECTING)
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.AUTHENTICATING, attempt = attempt)
                val session = resolveSession(forceAuth)
                val resolvedOwner = checkNotNull(session.owner)
                if (acceptedOwner != null && acceptedOwner != resolvedOwner) {
                    throw HaExactEntitySetupException("Home Assistant credentials changed during stream setup")
                }
                acceptedOwner = resolvedOwner
                forceAuth = false
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.CONNECTING, attempt = attempt)
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.SUBSCRIBING, attempt = attempt)
                connection = withTimeout(subscribeTimeoutMs) {
                    withContext(workerDispatcher) {
                        transport.subscribe(
                            session.baseUrl,
                            checkNotNull(session.accessToken),
                            expected.union,
                            expected.watchRegistry,
                            expected.watchLifecycle,
                        )
                    }
                }
                if (expected.watchRegistry && registryConnectedOnce) publishRegistryChanged(run)
                registryConnectedOnce = expected.watchRegistry
                publishTransportStatus(run, expected, HaExactEntityStreamPhase.SYNCHRONIZING, attempt = attempt)
                runConnected(run, expected, session, connection) {
                    attempt = 0
                    authRefreshAttempted = false
                    protocolFailures = 0
                    // Authenticated and subscribed: only now is there a Home Assistant application
                    // path to describe, so only now does a measurement start.
                    reportSocketState(HaSocketState.LIVE)
                }
                throw HaProtocolException("Home Assistant stream closed")
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                // The outer subscribe/hydration deadlines. A path that cannot be connected fails
                // FASTER than this, as a per-route connect timeout inside the transport (an
                // IOException, classified below); reaching this deadline means the host accepted the
                // connection and Home Assistant itself stalled on auth, subscribe or REST hydration,
                // which is a slow or restarting server, not a lost path.
                reportPath(HaPathFailureKind.SERVER)
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.RECONNECTING,
                    safeDetail(timeout, "Home Assistant stream timed out"), attempt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: HaAuthenticationException) {
                reportPath(HaPathFailureKind.AUTH)
                if (authRefreshAttempted) {
                    publishTransportStatus(
                        run, expected, HaExactEntityStreamPhase.AUTH_FAILED,
                        "Home Assistant rejected the panel sign-in after credential refresh", attempt,
                    )
                    return
                }
                authRefreshAttempted = true
                forceAuth = true
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.AUTH_FAILED,
                    safeDetail(rejected, "Home Assistant authentication failed"), attempt,
                )
            } catch (error: HaExactEntitySetupException) {
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.AUTH_FAILED,
                    safeDetail(error, "Home Assistant stream setup failed"), attempt,
                )
                return
            } catch (error: HaProtocolException) {
                // A liveness timeout was already reported as a network miss where it was detected;
                // every other protocol failure is Home Assistant closing, refusing or mis-answering.
                if (error !is HaStreamLivenessException) reportPath(HaPathFailureKind.SERVER)
                protocolFailures = nextAttempt(protocolFailures)
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.RECONNECTING,
                    safeDetail(error, "Home Assistant stream unavailable"), attempt,
                )
                if (protocolFailures >= MAX_PROTOCOL_ATTEMPTS) return
            } catch (error: Exception) {
                reportPath(haPathFailureKind(error))
                attempt = nextAttempt(attempt)
                publishTransportStatus(
                    run, expected, HaExactEntityStreamPhase.RECONNECTING,
                    safeDetail(error, "Home Assistant stream unavailable"), attempt,
                )
                if (!isTransient(error)) return
            } finally {
                withContext(NonCancellable + workerDispatcher) {
                    withTimeoutOrNull(closeTimeoutMs) {
                        try {
                            connection?.close()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w(TAG, "HA exact-entity connection close failed: ${error.javaClass.simpleName}")
                        }
                    }
                }
            }
            if (!current(run, expected)) return
            delay(reconnectDelay(attempt))
        }
        } finally {
            // The loop ended while this generation is still the live one, so it PARKED — a refused
            // sign-in after refresh, repeated protocol failures, a setup error or a non-transient
            // failure — and no probe will ever follow. Measurement stops, and the surfaces say
            // "not measured" rather than carrying a verdict nothing is refreshing. A SUPERSEDED
            // generation reports nothing: replaceRequest and close have already published the state
            // that replaced it, and a late report from a cancelled generation would overwrite it.
            if (current(run, expected)) reportSocketState(HaSocketState.STOPPED)
        }
    }

    private suspend fun runConnected(
        run: Long,
        expected: Request,
        session: HaApiSession,
        connection: HaExactEntityConnection,
        onLive: () -> Unit,
    ) = coroutineScope {
        val bufferedAmbient = ArrayDeque<HaExactSocketMessage>()
        val bufferedPresence = linkedMapOf<String, HaExactSocketMessage>()
        val bufferedPresenceStates = expected.presence.associateWithTo(linkedMapOf()) {
            HaPresenceValue.UNAVAILABLE
        }
        val bufferedActivities = linkedMapOf<String, BufferedActivity>()
        var startupSubscriptionResolved = !expected.watchLifecycle
        val messages = Channel<HaExactSocketMessage>(STREAM_BUFFER_CAPACITY)
        val reader = launch(workerDispatcher) {
            while (isActive) messages.send(connection.receive())
        }
        val initial = async {
            withTimeout(hydrationTimeoutMs) {
                withContext(workerDispatcher) {
                    expected.union.associateWith { id ->
                        transport.state(session.baseUrl, checkNotNull(session.accessToken), id)
                    }
                }
            }
        }
        try {
            var hydration: Map<String, JSONObject?>? = null
            while (hydration == null) {
                select<Unit> {
                    initial.onAwait { hydration = it }
                    messages.onReceive { message ->
                        if (message == HaExactSocketMessage.RegistryChanged) {
                            publishRegistryChanged(run)
                            return@onReceive
                        }
                        // Lifecycle frames do not depend on entity hydration and must not wait for it:
                        // a non-admin refusal arrives IMMEDIATELY after subscribing, and a restart can
                        // land at any moment — both were silently discarded here until hydration
                        // finished, which on a slow Home Assistant is a 20-second deaf window.
                        if (message is HaExactSocketMessage.Lifecycle) {
                            publishLifecycle(run, HaLifecycleSignal.Event(message.event))
                            return@onReceive
                        }
                        if (message == HaExactSocketMessage.LifecycleRejected) {
                            publishLifecycle(run, HaLifecycleSignal.Rejected)
                            return@onReceive
                        }
                        if (message == HaExactSocketMessage.LifecycleStartedRejected) {
                            startupSubscriptionResolved = true
                            publishLifecycle(run, HaLifecycleSignal.Rejected)
                            return@onReceive
                        }
                        // Acceptance rides the same non-hydration path as refusal: both are answers to
                        // the subscribe we just issued, and both must land before LIVE is reported.
                        if (message == HaExactSocketMessage.LifecycleEstablished) {
                            startupSubscriptionResolved = true
                            publishLifecycle(run, HaLifecycleSignal.Established)
                            return@onReceive
                        }
                        if (message is HaExactSocketMessage.State || message is HaExactSocketMessage.Missing) {
                            val entityId = when (message) {
                                is HaExactSocketMessage.State -> message.entityId
                                is HaExactSocketMessage.Missing -> message.entityId
                            }
                            if (entityId in expected.presence) {
                                val previousMessage = bufferedPresence[entityId]
                                val previousObserved = (previousMessage as? HaExactSocketMessage.State)
                                    ?.json?.let(::observedAtEpochMs)
                                val nextObserved = (message as? HaExactSocketMessage.State)
                                    ?.json?.let(::observedAtEpochMs)
                                val admitMessage = previousMessage == null || message is HaExactSocketMessage.Missing ||
                                    previousMessage is HaExactSocketMessage.Missing || nextObserved == null ||
                                    previousObserved == null || nextObserved >= previousObserved
                                if (admitMessage) {
                                    bufferedPresence[entityId] = message
                                    val value = when (message) {
                                        is HaExactSocketMessage.State ->
                                            HaPresenceProtocol.value(message.json.optString("state"))
                                        is HaExactSocketMessage.Missing -> HaPresenceValue.UNAVAILABLE
                                    }
                                    if (value == HaPresenceValue.ON &&
                                        bufferedPresenceStates[entityId] != HaPresenceValue.ON
                                    ) {
                                        val previous = bufferedActivities[entityId]
                                        val count = if (previous == null || previous.count == Long.MAX_VALUE) {
                                            previous?.count ?: 1L
                                        } else previous.count + 1L
                                        bufferedActivities[entityId] = BufferedActivity(
                                            count,
                                            nextObserved,
                                            monotonicMillis(),
                                        )
                                    }
                                    bufferedPresenceStates[entityId] = value
                                }
                            }
                            if (entityId == expected.ambient) {
                                if (bufferedAmbient.size == STREAM_BUFFER_CAPACITY) bufferedAmbient.removeFirst()
                                bufferedAmbient.addLast(message)
                            }
                        }
                    }
                }
            }
            checkNotNull(hydration).forEach { (entityId, state) ->
                deliverEntity(
                    run,
                    expected,
                    state?.let { HaExactEntityUpdate.State(entityId, it, initial = true) }
                        ?: HaExactEntityUpdate.Missing(entityId, initial = true),
                    publishPresence = false,
                )
            }
            markPresenceHydrated(run, expected)
            recordBufferedActivity(run, expected, bufferedActivities)
            bufferedAmbient.forEach { message ->
                applyMessage(
                    run, expected, message, initial = false,
                    publishPresence = false, recordActivity = false,
                )
            }
            bufferedPresence.values.forEach { message ->
                applyMessage(
                    run, expected, message, initial = false,
                    publishPresence = false, recordActivity = false,
                )
            }
            if (bufferedPresence.isNotEmpty()) publishBufferedPresence(run, expected)
            // REST hydration and WebSocket subscription replies race. Do not call the session LIVE
            // until the exact STARTED subscription has answered, or an empty lifecycle-only request can
            // recreate the premature recovery before its acceptance frame is read.
            if (!startupSubscriptionResolved) {
                withTimeout(subscribeTimeoutMs) {
                    while (!startupSubscriptionResolved) {
                        val message = messages.receive()
                        if (message == HaExactSocketMessage.LifecycleEstablished ||
                            message == HaExactSocketMessage.LifecycleStartedRejected
                        ) {
                            startupSubscriptionResolved = true
                        }
                        applyMessage(run, expected, message, initial = false)
                    }
                }
            }
            onLive()
            publishTransportStatus(run, expected, HaExactEntityStreamPhase.LIVE)

            var pingId = FIRST_PING_ID
            // Deadline-based, not idle-based: entity traffic keeps the receive returning early, so
            // the wait is recomputed against a fixed next-probe instant and a probe goes out on the
            // cadence whether or not frames arrived in between. One consumer only: `messages` is a
            // single-consumer channel and a second ticker draining it would steal entity frames.
            var nextProbeAtMs = monotonicMillis() + probeIntervalMs
            while (isActive && current(run, expected)) {
                val wait = nextProbeAtMs - monotonicMillis()
                val message = if (wait > 0L) withTimeoutOrNull(wait) { messages.receive() } else null
                if (message != null) {
                    applyMessage(run, expected, message, initial = false)
                    continue
                }
                val expectedPong = pingId++
                val sentAtMs = monotonicMillis()
                connection.ping(expectedPong)
                when (val wait = awaitPong(messages, expectedPong, run, expected)) {
                    is PongWait.Answered -> networkPathObserver?.let { observer ->
                        safeCallback { observer.onRoundTrip((wait.receivedAtMs - sentAtMs).coerceAtLeast(0L)) }
                    }
                    // Frames kept arriving while the pong did not: the socket and the path are
                    // demonstrably alive and Home Assistant is what is slow to answer. That is the
                    // server's condition, never loss, and it must not tear down the shared stream
                    // that ambient light, presence, auto-sleep and lifecycle ride — an overloaded
                    // instance would otherwise be interrupted every probe for as long as it stayed
                    // busy. The abandoned ping's late pong is ignored like any stray pong.
                    PongWait.BusyTimeout -> reportPath(HaPathFailureKind.SERVER)
                    // Silence for the whole pong timeout is the original liveness verdict: dead.
                    PongWait.SilentTimeout -> {
                        networkPathObserver?.let { observer -> safeCallback { observer.onProbeTimeout() } }
                        // Ask layer 3 NOW, and dispatch before the throw. Escalating and leaving the
                        // burst to the next loop iteration would make the answer wait on a reconnect
                        // that may never complete — during precisely the outage this exists to
                        // attribute. The burst runs on its own dispatcher and outlives the teardown;
                        // its result is discarded if the session it belongs to does not come back.
                        pathProbe?.let { probe ->
                            safeCallback { probe.onSocketSilence(monotonicMillis()) }
                            dispatchPathBurst(probe)
                        }
                        throw HaStreamLivenessException()
                    }
                }
                // Piggyback the layer-3 burst on this cadence rather than running a second loop.
                pathProbe?.let { probe -> dispatchPathBurst(probe) }
                nextProbeAtMs = monotonicMillis() + probeIntervalMs
            }
        } finally {
            reader.cancel()
            initial.cancel()
            messages.close()
        }
    }

    /** How one probe's wait ended. */
    private sealed interface PongWait {
        /** The instant the pong was decoded (the transport's stamp when given, else this clock). */
        class Answered(val receivedAtMs: Long) : PongWait

        /** No pong, but at least one other frame arrived during the wait: the socket is alive. */
        data object BusyTimeout : PongWait

        /** No pong and no other frame for the whole pong timeout: the socket is dead. */
        data object SilentTimeout : PongWait
    }

    /**
     * Drain frames until the expected pong or the pong timeout, counting every OTHER frame seen on
     * the way. A missing pong means two different things depending on that count, and only the
     * silent case is a liveness failure.
     */
    private suspend fun awaitPong(
        messages: Channel<HaExactSocketMessage>,
        expectedPong: Int,
        run: Long,
        expected: Request,
    ): PongWait {
        var inboundWhileWaiting = 0
        val answeredAtMs = withTimeoutOrNull(pongTimeoutMs) {
            while (current(run, expected)) {
                when (val message = messages.receive()) {
                    is HaExactSocketMessage.Pong -> if (message.id == expectedPong) {
                        return@withTimeoutOrNull if (message.receivedAtMs >= 0L) message.receivedAtMs else monotonicMillis()
                    } else {
                        // A LATE pong for an abandoned earlier probe. Its round trip is not
                        // attributable any more, but the frame itself is proof the socket is
                        // delivering — so it counts as inbound traffic like any other frame. Not
                        // counting it is how a server answering one probe-interval late made every
                        // subsequent wait look SILENT and tore down a demonstrably live socket.
                        inboundWhileWaiting++
                    }
                    else -> {
                        inboundWhileWaiting++
                        applyMessage(run, expected, message, initial = false)
                    }
                }
            }
            null
        }
        return when {
            answeredAtMs != null -> PongWait.Answered(answeredAtMs)
            inboundWhileWaiting > 0 -> PongWait.BusyTimeout
            else -> PongWait.SilentTimeout
        }
    }

    private fun applyMessage(
        run: Long,
        expected: Request,
        message: HaExactSocketMessage,
        initial: Boolean,
        publishPresence: Boolean = true,
        recordActivity: Boolean = true,
    ) {
        when (message) {
            is HaExactSocketMessage.State -> deliverEntity(
                run, expected, HaExactEntityUpdate.State(message.entityId, message.json, initial),
                publishPresence = publishPresence,
                recordActivity = recordActivity,
            )
            is HaExactSocketMessage.Missing -> deliverEntity(
                run, expected, HaExactEntityUpdate.Missing(message.entityId, initial),
                publishPresence = publishPresence,
                recordActivity = recordActivity,
            )
            HaExactSocketMessage.RegistryChanged -> publishRegistryChanged(run)
            is HaExactSocketMessage.Lifecycle -> publishLifecycle(run, HaLifecycleSignal.Event(message.event))
            HaExactSocketMessage.LifecycleRejected -> publishLifecycle(run, HaLifecycleSignal.Rejected)
            HaExactSocketMessage.LifecycleStartedRejected -> publishLifecycle(run, HaLifecycleSignal.Rejected)
            HaExactSocketMessage.LifecycleEstablished -> publishLifecycle(run, HaLifecycleSignal.Established)
            else -> Unit
        }
    }

    private fun publishLifecycle(run: Long, signal: HaLifecycleSignal) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || !request.watchLifecycle) return
            val target = lifecycleObserver ?: return
            // Deliberately NOT coalesced the way registry changes are: order is the whole meaning here,
            // and collapsing "stop" into "started" would erase the outage this feature exists to report.
            callbackQueue.addLast(PendingCallback.Lifecycle(run, target, signal))
            if (!drainingCallbacks) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private fun publishRegistryChanged(run: Long) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || !request.watchRegistry) return
            val target = registryChangeObserver ?: return
            callbackQueue.removeAll { it is PendingCallback.RegistryChanged && it.run == run && it.target === target }
            callbackQueue.addLast(PendingCallback.RegistryChanged(run, target))
            if (!drainingCallbacks) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private suspend fun resolveSession(force: Boolean): HaApiSession = withContext(workerDispatcher) {
        val session = auth.resolve(force)
        when {
            session.rejected -> throw HaAuthenticationException("Home Assistant rejected the configured refresh credentials")
            session.baseUrl.isBlank() -> throw HaExactEntitySetupException("Home Assistant URL is not configured")
            session.accessToken.isNullOrBlank() -> throw HaAuthenticationException("Home Assistant access credentials are unavailable")
            session.owner == null -> throw HaExactEntitySetupException("Home Assistant credentials changed during stream setup")
            else -> session
        }
    }

    private fun publishTransportStatus(
        run: Long,
        expected: Request,
        phase: HaExactEntityStreamPhase,
        detail: String = "",
        attempt: Int = 0,
    ) {
        expected.ambient?.let { entityId ->
            publishAmbientStatus(run, HaExactEntityStreamStatus(
                entityId = entityId,
                phase = phase,
                detail = detail,
                reconnectAttempt = attempt,
            ))
        }
        if (expected.presence.isNotEmpty()) publishPresenceStatus(run, phase, detail, attempt)
        // The detail string is withheld on purpose: it can carry a Home Assistant error message, and the
        // lifecycle consumer only needs to know whether the socket is proven live or gone.
        if (expected.watchLifecycle) publishLifecycle(run, HaLifecycleSignal.Transport(phase))
    }

    private fun publishAmbientStatus(run: Long, next: HaExactEntityStreamStatus) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run) return
            val target = ambientObserver ?: return
            callbackQueue.addLast(PendingCallback.AmbientStatus(run, target, next.copy(detail = next.detail.take(MAX_DETAIL_CHARS))))
            if (!drainingCallbacks) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private fun publishPresenceStatus(
        run: Long,
        phase: HaExactEntityStreamPhase,
        detail: String = "",
        attempt: Int = 0,
    ) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run) return
            presencePhase = phase
            if (phase == HaExactEntityStreamPhase.CONNECTING ||
                phase == HaExactEntityStreamPhase.SUBSCRIBING ||
                phase == HaExactEntityStreamPhase.SYNCHRONIZING
            ) presenceHydrated = false
            presenceDetail = detail.take(MAX_DETAIL_CHARS)
            presenceReconnectAttempt = attempt
            presenceRevision++
            drain = enqueuePresenceLocked(run)
        }
        if (drain) drainCallbacks()
    }

    private fun markPresenceHydrated(run: Long, expected: Request) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected || expected.presence.isEmpty()) return
            presenceHydrated = true
            presenceRevision++
            drain = enqueuePresenceLocked(run)
        }
        if (drain) drainCallbacks()
    }

    private fun deliverEntity(
        run: Long,
        expected: Request,
        update: HaExactEntityUpdate,
        publishPresence: Boolean = true,
        recordActivity: Boolean = true,
    ) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected) return
            if (update.entityId == expected.ambient) {
                ambientObserver?.let { callbackQueue.addLast(PendingCallback.AmbientUpdate(run, it, update)) }
            }
            if (update.entityId in expected.presence) {
                val nextValue = when (update) {
                    is HaExactEntityUpdate.State -> HaPresenceProtocol.value(update.json.optString("state"))
                    is HaExactEntityUpdate.Missing -> HaPresenceValue.UNAVAILABLE
                }
                val observed = (update as? HaExactEntityUpdate.State)?.json?.let(::observedAtEpochMs)
                val previousObserved = presenceObservedAt[update.entityId]
                val stale = observed != null && previousObserved != null && observed < previousObserved
                if (!stale) {
                    val previous = presenceStates[update.entityId]
                    presenceStates[update.entityId] = nextValue
                    if (observed != null) presenceObservedAt[update.entityId] = observed
                    if (recordActivity && nextValue == HaPresenceValue.ON && previous != HaPresenceValue.ON) {
                        lastActivity = HaPresenceActivityMarker(
                            ++activitySequence,
                            update.entityId,
                            monotonicMillis(),
                        )
                    }
                    if (publishPresence) {
                        presenceRevision++
                        drain = enqueuePresenceLocked(run) || drain
                    }
                }
            }
            if (!drainingCallbacks && callbackQueue.isNotEmpty()) {
                drainingCallbacks = true
                drain = true
            }
        }
        if (drain) drainCallbacks()
    }

    private fun recordBufferedActivity(
        run: Long,
        expected: Request,
        activities: Map<String, BufferedActivity>,
    ) {
        if (activities.isEmpty()) return
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected) return
            val valid = activities.filter { (entityId, activity) ->
                activity.observedAtEpochMs == null ||
                    (presenceObservedAt[entityId] ?: Long.MIN_VALUE) <= activity.observedAtEpochMs
            }
            val latest = valid.maxByOrNull { it.value.receivedAtMonotonicMs } ?: return
            val count = valid.values.fold(0L) { total, activity ->
                if (total > Long.MAX_VALUE - activity.count) Long.MAX_VALUE else total + activity.count
            }
            activitySequence = if (activitySequence > Long.MAX_VALUE - count) Long.MAX_VALUE
                else activitySequence + count
            lastActivity = HaPresenceActivityMarker(
                activitySequence,
                latest.key,
                latest.value.receivedAtMonotonicMs,
            )
        }
    }

    private fun publishBufferedPresence(run: Long, expected: Request) {
        var drain = false
        synchronized(lock) {
            if (stopped || generation.get() != run || request != expected) return
            presenceRevision++
            drain = enqueuePresenceLocked(run)
        }
        if (drain) drainCallbacks()
    }

    private fun resetPresenceLocked(sourceIds: Set<String>) {
        presenceRevision = 0L
        presencePhase = if (sourceIds.isEmpty()) HaExactEntityStreamPhase.DISABLED
            else HaExactEntityStreamPhase.AUTHENTICATING
        presenceDetail = ""
        presenceReconnectAttempt = 0
        presenceHydrated = false
        presenceStates.clear()
        sourceIds.forEach { presenceStates[it] = HaPresenceValue.UNAVAILABLE }
        presenceObservedAt.clear()
        lastActivity = null
    }

    private fun enqueuePresenceLocked(run: Long): Boolean {
        val target = presenceObserver ?: return false
        val snapshot = presenceSnapshotLocked(run)
        callbackQueue.removeAll { it is PendingCallback.Presence && it.run == run && it.target === target }
        callbackQueue.addLast(PendingCallback.Presence(run, target, snapshot))
        if (!drainingCallbacks) {
            drainingCallbacks = true
            return true
        }
        return false
    }

    private fun presenceSnapshotLocked(run: Long) = HaPresenceFeedSnapshot(
        generation = run,
        revision = presenceRevision,
        sourceIds = request.presence.toSet(),
        states = presenceStates.toMap(),
        hydrated = presenceHydrated,
        phase = presencePhase,
        detail = presenceDetail,
        reconnectAttempt = presenceReconnectAttempt,
        lastOnActivity = lastActivity,
    )

    private fun drainCallbacks() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    var accepted: PendingCallback? = null
                    while (callbackQueue.isNotEmpty() && accepted == null) {
                        val candidate = callbackQueue.removeFirst()
                        accepted = when (candidate) {
                            is PendingCallback.FinalAmbient, is PendingCallback.FinalPresence -> candidate
                            is PendingCallback.AmbientStatus -> candidate.takeIf {
                                !stopped && generation.get() == it.run && ambientObserver === it.target
                            }
                            is PendingCallback.AmbientUpdate -> candidate.takeIf {
                                !stopped && generation.get() == it.run && ambientObserver === it.target
                            }
                            is PendingCallback.Presence -> candidate.takeIf {
                                !stopped && generation.get() == it.run && presenceObserver === it.target
                            }
                            is PendingCallback.RegistryChanged -> candidate.takeIf {
                                !stopped && generation.get() == it.run && registryChangeObserver === it.target
                            }
                            is PendingCallback.Lifecycle -> candidate.takeIf {
                                !stopped && generation.get() == it.run && lifecycleObserver === it.target
                            }
                            is PendingCallback.LifecycleRetired -> candidate.takeIf {
                                !stopped && lifecycleObserver === it.target
                            }
                        }
                    }
                    if (accepted == null) drainingCallbacks = false
                    accepted
                } ?: return
                when (next) {
                    is PendingCallback.FinalAmbient -> safeCallback { next.target.onStatus(next.status) }
                    is PendingCallback.FinalPresence -> safeCallback { next.target.onSnapshot(next.snapshot) }
                    is PendingCallback.AmbientStatus -> safeCallback { next.target.onStatus(next.status) }
                    is PendingCallback.AmbientUpdate -> safeCallback { next.target.onUpdate(next.update) }
                    is PendingCallback.Presence -> safeCallback { next.target.onSnapshot(next.snapshot) }
                    is PendingCallback.RegistryChanged -> safeCallback(next.target)
                    is PendingCallback.Lifecycle -> safeCallback { next.target.onSignal(next.signal) }
                    is PendingCallback.LifecycleRetired -> safeCallback {
                        next.target.onSignal(HaLifecycleSignal.Retired)
                    }
                }
            }
        } catch (error: Error) {
            synchronized(lock) {
                callbackQueue.clear()
                drainingCallbacks = false
            }
            throw error
        }
    }

    private fun current(run: Long, expected: Request): Boolean =
        !stopped && generation.get() == run && request == expected

    private fun reconnectDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return (reconnectBaseMs * (1L shl shift)).coerceAtMost(reconnectMaxMs)
    }

    private fun nextAttempt(current: Int): Int = minOf(current, MAX_RECONNECT_ATTEMPT - 1) + 1

    private fun isTransient(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any {
            it is IOException || it is ClosedChannelException ||
                it is ClosedReceiveChannelException || it is ClosedSendChannelException
        }

    private fun safeDetail(error: Throwable, fallback: String): String =
        error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(MAX_DETAIL_CHARS)
            ?.takeIf(String::isNotBlank) ?: fallback

    private inline fun safeCallback(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.w(TAG, "HA exact-entity callback failed: ${error.javaClass.simpleName}")
        }
    }

    private fun observedAtEpochMs(state: JSONObject): Long? =
        sequenceOf(state.optString("last_updated"), state.optString("last_changed"))
            .firstOrNull(String::isNotBlank)
            ?.let(::parseHaTimestampEpochMs)

    private companion object {
        const val TAG = "HaExactEntity"
        const val MAX_DETAIL_CHARS = 240
        const val STREAM_BUFFER_CAPACITY = 256
        const val MAX_PROTOCOL_ATTEMPTS = 3
        const val MAX_RECONNECT_ATTEMPT = 1_000_000
        const val FIRST_PING_ID = 10
        const val DEFAULT_PONG_TIMEOUT_MS = 15_000L
        const val DEFAULT_RECONNECT_BASE_MS = 1_000L
        const val DEFAULT_RECONNECT_MAX_MS = 60_000L
        const val DEFAULT_SUBSCRIBE_TIMEOUT_MS = 35_000L
        const val DEFAULT_HYDRATION_TIMEOUT_MS = 20_000L
        const val DEFAULT_CLOSE_TIMEOUT_MS = 5_000L
    }
}

internal class KtorHaExactEntityStreamTransport(
    private val rest: HaAmbientTransport = KtorHaAmbientTransport(),
    private val socketFamilyPolicy: () -> MqttAddressFamilyPolicy = { MqttAddressFamilyPolicy.AUTOMATIC },
    /**
     * Stamps each pong at decode time for the round-trip measurement. Must be the SAME clock the
     * owner sends on, which is why the service passes its `elapsedRealtime` lambda to both.
     */
    private val monotonicMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /**
     * Reports the address each route actually connected on, for the layer-3 echo probe.
     *
     * Supplied by the service alongside the probe itself. It exists so the probe measures THE PATH
     * THE DASHBOARD IS USING rather than a fresh resolution of the same hostname: the two differ
     * exactly when it matters, which is a black-holed family the connect race has already stepped
     * around. Null in every build and test that has no probe.
     */
    private val onRouteConnected: ((java.net.InetAddress, Boolean?) -> Unit)? = null,
) : HaExactEntityStreamTransport {
    override suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
    ): HaExactEntityConnection = subscribe(baseUrl, accessToken, entityIds, watchRegistry = false)

    override suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        watchRegistry: Boolean,
    ): HaExactEntityConnection =
        subscribe(baseUrl, accessToken, entityIds, watchRegistry, watchLifecycle = false)

    override suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        watchRegistry: Boolean,
        watchLifecycle: Boolean,
    ): HaExactEntityConnection = withContext(Dispatchers.IO) {
        require(entityIds.isNotEmpty() || watchRegistry || watchLifecycle)
        val policy = socketFamilyPolicy()
        val client = HaWebSocketClients.client(
            preferIpv4 = policy.initialPreferIpv4,
            ipv4Only = policy.ipv4Only,
            onRouteConnected = onRouteConnected,
        )
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(CONNECT_TIMEOUT_MS) {
                HaWebSocketClients.open(client, EntityFilterProtocol.upstreamWebSocketUrl(baseUrl), MAX_WS_FRAME_BYTES)
            }
            socket = active
            authenticate(active, accessToken)
            val batches = presenceSubscriptionBatches(entityIds)
            val ids = haSubscriptionIds(batches.size, watchRegistry, watchLifecycle, REGISTRY_EVENTS.size)
            batches.forEachIndexed { index, batch ->
                active.send(Frame.Text(JSONObject()
                    .put("id", ids.entityBatchIds[index])
                    .put("type", "subscribe_entities")
                    .put("entity_ids", JSONArray(batch.sorted()))
                    .toString()))
            }
            REGISTRY_EVENTS.forEachIndexed { index, eventType ->
                val id = ids.registryIds.elementAtOrNull(index) ?: return@forEachIndexed
                active.send(Frame.Text(JSONObject()
                    .put("id", id)
                    .put("type", "subscribe_events")
                    .put("event_type", eventType)
                    .toString()))
            }
            // Subscribed by EXACT type, never as a match-all listener: `homeassistant_close` is excluded
            // from match-all, so only an explicit subscription can observe the final shutdown stage.
            ids.lifecycleIds.forEach { (id, event) ->
                active.send(Frame.Text(JSONObject()
                    .put("id", id)
                    .put("type", "subscribe_events")
                    .put("event_type", event.wireValue)
                    .toString()))
            }
            KtorExactEntityConnection(
                client,
                active,
                HaCompressedEntityProjection(entityIds),
                ids.registryIds,
                ids.lifecycleIds,
                ids.pingIdOffset,
                monotonicMillis,
            )
        } catch (error: Exception) {
            runCatching { socket?.close() }
            client.close()
            throw error
        }
    }

    override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? =
        rest.state(baseUrl, accessToken, entityId)

    private suspend fun authenticate(socket: DefaultClientWebSocketSession, accessToken: String) {
        withTimeout(AUTH_TIMEOUT_MS) {
            val required = readJson(socket)
            if (required.optString("type") != "auth_required") {
                throw HaProtocolException("Home Assistant did not request WebSocket authentication")
            }
            socket.send(Frame.Text(JSONObject().put("type", "auth").put("access_token", accessToken).toString()))
            when (readJson(socket).optString("type")) {
                "auth_ok" -> Unit
                "auth_invalid" -> throw HaAuthenticationException("Home Assistant rejected the access token")
                else -> throw HaProtocolException("Unexpected Home Assistant authentication response")
            }
        }
    }

    private suspend fun readJson(socket: DefaultClientWebSocketSession): JSONObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame is Frame.Text) return JSONObject(frame.readText())
        }
    }

    private class KtorExactEntityConnection(
        private val client: HttpClient,
        private val socket: DefaultClientWebSocketSession,
        private val projection: HaCompressedEntityProjection,
        private val registrySubscriptionIds: Set<Int>,
        private val lifecycleSubscriptionIds: Map<Int, HaLifecycleEvent>,
        private val pingIdOffset: Int,
        private val monotonicMillis: () -> Long,
    ) : HaExactEntityConnection {
        private val pending = ArrayDeque<HaExactSocketMessage>()

        override suspend fun receive(): HaExactSocketMessage {
            while (true) {
                if (pending.isNotEmpty()) return pending.removeFirst()
                val frame = socket.incoming.receive()
                if (frame !is Frame.Text) continue
                val json = JSONObject(frame.readText())
                return when (json.optString("type")) {
                    "event" -> when (
                        val route = haEventRoute(
                            json.optInt("id"),
                            registrySubscriptionIds,
                            lifecycleSubscriptionIds,
                        )
                    ) {
                        HaEventRoute.Registry -> HaExactSocketMessage.RegistryChanged
                        // The id identifies the type, so the event body is never opened.
                        is HaEventRoute.Lifecycle -> HaExactSocketMessage.Lifecycle(route.event)
                        HaEventRoute.Entities -> {
                            pending.addAll(projection.applyAll(json.optJSONObject("event") ?: JSONObject()))
                            if (pending.isEmpty()) HaExactSocketMessage.Other else pending.removeFirst()
                        }
                    }
                    "result" -> when (
                        val outcome = haResultOutcome(json, lifecycleSubscriptionIds, MAX_ERROR_CHARS)
                    ) {
                        HaResultOutcome.Ignored -> HaExactSocketMessage.Other
                        HaResultOutcome.LifecycleEstablished -> HaExactSocketMessage.LifecycleEstablished
                        HaResultOutcome.LifecycleStartedRejected ->
                            HaExactSocketMessage.LifecycleStartedRejected
                        HaResultOutcome.LifecycleRejected -> HaExactSocketMessage.LifecycleRejected
                        is HaResultOutcome.Fatal -> throw HaProtocolException(outcome.message)
                    }
                    "pong" -> HaExactSocketMessage.Pong(
                        (json.optLong("id", -1L) - pingIdOffset.toLong()).toInt(),
                        receivedAtMs = monotonicMillis(),
                    )
                    else -> HaExactSocketMessage.Other
                }
            }
        }

        override suspend fun ping(id: Int) {
            socket.send(Frame.Text(JSONObject()
                .put("id", id.toLong() + pingIdOffset.toLong())
                .put("type", "ping")
                .toString()))
        }

        override suspend fun close() {
            try {
                socket.close()
            } finally {
                client.close()
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_ID = 1
        val REGISTRY_EVENTS = listOf(
            "device_registry_updated",
            "entity_registry_updated",
            "area_registry_updated",
        )
        const val MAX_ERROR_CHARS = 240
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_WS_FRAME_BYTES = 2L * 1024L * 1024L
        const val AUTH_TIMEOUT_MS = 15_000L
    }
}

/** Expands HA's permission-aware exact-entity diff stream into ordinary state objects. */
internal class HaCompressedEntityProjection(entityIds: Set<String>) {
    constructor(entityId: String) : this(setOf(entityId))

    private val entityIds = entityIds.toSet()
    private val states = linkedMapOf<String, JSONObject>()

    fun apply(event: JSONObject): HaExactSocketMessage =
        applyAll(event).firstOrNull() ?: HaExactSocketMessage.Other

    fun applyAll(event: JSONObject): List<HaExactSocketMessage> {
        val updates = ArrayList<HaExactSocketMessage>(entityIds.size)
        event.optJSONObject("a")?.let { addedRoot ->
            entityIds.forEach { entityId -> addedRoot.optJSONObject(entityId)?.let { added ->
                states[entityId] = JSONObject()
                    .put("entity_id", entityId)
                    .put("state", added.optString("s"))
                    .put("attributes", added.optJSONObject("a") ?: JSONObject())
                    .also { applyTimes(it, added) }
                updates += HaExactSocketMessage.State(entityId, JSONObject(checkNotNull(states[entityId]).toString()))
            } }
        }
        val removed = event.optJSONArray("r")
        if (removed != null) {
            for (index in 0 until removed.length()) {
                val entityId = removed.optString(index)
                if (entityId in entityIds) {
                    states.remove(entityId)
                    updates += HaExactSocketMessage.Missing(entityId)
                }
            }
        }
        event.optJSONObject("c")?.let { changedRoot ->
            entityIds.forEach { entityId -> changedRoot.optJSONObject(entityId)?.let { changed ->
                states[entityId]?.let { current ->
                    changed.optJSONObject("+")?.let { additions ->
                        if (additions.has("s")) current.put("state", additions.optString("s"))
                        additions.optJSONObject("a")?.let { addedAttributes ->
                            val attributes = current.optJSONObject("attributes")
                                ?: JSONObject().also { current.put("attributes", it) }
                            val keys = addedAttributes.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                attributes.put(key, addedAttributes.get(key))
                            }
                        }
                        applyTimes(current, additions)
                    }
                    changed.optJSONObject("-")?.optJSONArray("a")?.let { removedAttributes ->
                        val attributes = current.optJSONObject("attributes") ?: JSONObject()
                        for (index in 0 until removedAttributes.length()) {
                            attributes.remove(removedAttributes.optString(index))
                        }
                    }
                    updates += HaExactSocketMessage.State(entityId, JSONObject(current.toString()))
                }
            } }
        }
        return updates
    }

    private fun applyTimes(target: JSONObject, compressed: JSONObject) {
        if (compressed.has("lc")) {
            val changed = epochSeconds(compressed.optDouble("lc", Double.NaN)) ?: return
            target.put("last_changed", changed)
            target.put("last_updated", changed)
        }
        if (compressed.has("lu")) {
            epochSeconds(compressed.optDouble("lu", Double.NaN))?.let { target.put("last_updated", it) }
        }
    }

    private fun epochSeconds(value: Double): String? = value.takeIf(Double::isFinite)
        ?.let { Instant.ofEpochMilli((it * 1_000.0).toLong()).toString() }
}
