package io.github.maxlyth.hapaneld.sensors

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.github.maxlyth.hapaneld.util.HaWebSocketClients
import io.github.maxlyth.hapaneld.util.closeBody
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

internal data class HaPresenceRequest(
    val enabled: Boolean,
    val deviceUid: String,
    val panelId: String,
    val controllerEpoch: Long = 0L,
    /** The panel's locally configured area name; when set it names the room presence sources come from. */
    val preferredAreaName: String = "",
    /** The Panel Assistant discovery id, which proves this panel's `panel_assistant` device; null without one. */
    val discoveryId: String? = null,
)

internal enum class HaPresencePhase {
    DISABLED,
    AUTHENTICATING,
    DISCOVERING,
    LEARNING,
    CONNECTING,
    SYNCHRONIZING,
    LIVE,
    NO_AREA,
    NO_CREDIBLE_SOURCES,
    NO_INCLUDED_SOURCES,
    AUTH_FAILED,
    DISCOVERY_FAILED,
    RECONNECTING,
    STOPPED,
}

/**
 * One immutable manager output. Consumers retain the feed-generation/marker-sequence cursor across
 * manager refreshes: a manager-generation change must not replay an old marker from the same feed.
 * A new feed generation clears its marker while the owner's sequence remains global, so a later
 * non-null marker is new buffered/live activity rather than hydration state.
 */
@ConsistentCopyVisibility
internal data class HaPresenceAggregate private constructor(
    val phase: HaPresencePhase,
    val detail: String,
    val reconnectAttempt: Int,
    val controllerEpoch: Long,
    val managerGeneration: Long,
    val feedGeneration: Long,
    val feedRevision: Long,
    val finalStates: Map<String, HaPresenceValue>,
    val hydrated: Boolean,
    val selectedEntityIds: Set<String>,
    val discoveredEntityIds: Set<String>,
    val areaName: String,
    val areaKey: String,
    val learnedLeaseMs: Long,
    val activityMarker: HaPresenceActivityMarker?,
) {
    fun admits(snapshot: HaPresenceFeedSnapshot, epoch: Long, generation: Long): Boolean =
        controllerEpoch == epoch && managerGeneration == generation &&
            snapshot.sourceIds == selectedEntityIds && snapshot.revision >= 0L &&
            (snapshot.generation > feedGeneration ||
                snapshot.generation == feedGeneration &&
                (snapshot.revision > feedRevision || snapshot.revision == feedRevision && !hydrated))

    companion object {
        operator fun invoke(
            phase: HaPresencePhase = HaPresencePhase.DISABLED,
            detail: String = "",
            reconnectAttempt: Int = 0,
            controllerEpoch: Long = 0L,
            managerGeneration: Long = 0L,
            feedGeneration: Long = NO_PRESENCE_FEED_VERSION,
            feedRevision: Long = NO_PRESENCE_FEED_VERSION,
            finalStates: Map<String, HaPresenceValue> = emptyMap(),
            hydrated: Boolean = false,
            selectedEntityIds: Set<String> = emptySet(),
            discoveredEntityIds: Set<String> = selectedEntityIds,
            areaName: String = "",
            areaKey: String = "",
            learnedLeaseMs: Long = MIN_AUTO_SLEEP_LEASE_MS,
            activityMarker: HaPresenceActivityMarker? = null,
        ) = HaPresenceAggregate(
            phase, detail, reconnectAttempt, controllerEpoch, managerGeneration,
            feedGeneration, feedRevision,
            Collections.unmodifiableMap(LinkedHashMap(finalStates)), hydrated,
            Collections.unmodifiableSet(LinkedHashSet(selectedEntityIds)),
            Collections.unmodifiableSet(LinkedHashSet(discoveredEntityIds)), areaName, areaKey,
            learnedLeaseMs, activityMarker,
        )
    }
}

private const val NO_PRESENCE_FEED_VERSION = -1L

private data class HaPresenceResolution(
    val selectedEntityIds: Set<String>,
    val discoveredEntityIds: Set<String>,
    val sourceLabels: Map<String, String>,
    val learnedLeaseMs: Long,
    val areaId: String,
    val areaName: String,
    val areaKey: String,
    val sourceKeys: Map<String, String>,
    val scope: String,
    val controllerEpoch: Long,
)

internal enum class HaPresenceSourceUpdate { UPDATED, STALE, COMMIT_FAILED, UNAVAILABLE }

internal interface HaPresenceExclusions {
    val scope: String
    fun excluded(areaId: String): Set<String>
    /** Null means [expectedScope] is no longer the active HA installation. */
    fun setIncluded(expectedScope: String, areaId: String, entityId: String, included: Boolean): Boolean?
}

private class ConfigHaPresenceExclusions(private val config: Config) : HaPresenceExclusions {
    override val scope: String get() = config.autoSleepExclusionScope()
    override fun excluded(areaId: String) = config.autoSleepExcludedEntityIds(areaId)
    override fun setIncluded(expectedScope: String, areaId: String, entityId: String, included: Boolean) =
        config.setAutoSleepSourceIncludedIfScope(expectedScope, areaId, entityId, included)
}

internal fun presenceOpaqueKey(scope: String, kind: String, areaId: String, entityId: String = ""): String {
    val bytes = "$scope\u0000$kind\u0000$areaId\u0000$entityId".toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal data class HaPresenceRegistrySnapshot(
    val devices: JSONObject,
    val areas: JSONObject,
    val entities: JSONObject,
    val states: JSONArray,
    /** [HaPanelDeviceMatcher.readProbe]'s response; null when no Panel Assistant device was probed. */
    val panelAssistantProbe: JSONObject? = null,
)

internal data class HaPanelAreaRegistrySnapshot(
    val devices: JSONObject,
    val areas: JSONObject,
    val panelAssistantProbe: JSONObject? = null,
)

internal enum class HaPanelAreaPrerequisitePhase { ASSIGNED, UNASSIGNED, AUTH_FAILED, UNAVAILABLE }

internal data class HaPanelAreaPrerequisite(
    val phase: HaPanelAreaPrerequisitePhase,
    val areaName: String = "",
    val detail: String = "",
    val authOwner: io.github.maxlyth.hapaneld.HaAuthOwner? = null,
) {
    val eligible: Boolean get() = phase == HaPanelAreaPrerequisitePhase.ASSIGNED
}

internal interface HaPresenceTransport {
    suspend fun registry(baseUrl: String, accessToken: String): HaPresenceRegistrySnapshot
    suspend fun panelAreaRegistry(baseUrl: String, accessToken: String): HaPanelAreaRegistrySnapshot {
        val snapshot = registry(baseUrl, accessToken)
        return HaPanelAreaRegistrySnapshot(snapshot.devices, snapshot.areas, snapshot.panelAssistantProbe)
    }
    suspend fun history(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        startEpochMs: Long,
        endEpochMs: Long,
    ): JSONArray
}

internal data class HaPresenceSelectedHistory(
    val sourceIds: Set<String>,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val transitions: Map<String, List<HaPresenceTransition>>,
    val sourceLabels: Map<String, String> = sourceIds.associateWith { it },
    val discoveredSourceIds: Set<String> = sourceIds,
    val excludedSourceIds: Set<String> = emptySet(),
    val areaName: String = "",
    val areaKey: String = "",
    val sourceKeys: Map<String, String> = discoveredSourceIds.associateWith { it },
)

/**
 * Owns bounded one-shot Area/registry/history discovery. Selected-source streaming is delegated to
 * [streamOwner], the service's sole persistent HA socket/auth/reconnect/hydration authority.
 * [offerAggregate] runs in generation order under the manager lock and therefore must be a
 * constant-time, non-blocking offer (normally `Channel.trySend`). `false` means the consumer closed;
 * the manager logs and does not introduce a queue, worker or retry lifecycle of its own.
 */
internal class HaPresenceSourceManager(
    private val scope: CoroutineScope,
    private val auth: HaApiSessionProvider,
    private val transport: HaPresenceTransport,
    private val streamOwner: HaExactEntityStreamOwner,
    private val offerAggregate: (HaPresenceAggregate) -> Boolean,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val exclusions: HaPresenceExclusions = object : HaPresenceExclusions {
        override val scope = "test"
        override fun excluded(areaId: String) = emptySet<String>()
        override fun setIncluded(expectedScope: String, areaId: String, entityId: String, included: Boolean) = false
    },
) : AutoCloseable {
    private class DiscoveryFailure(
        val code: String,
        cause: Throwable,
        val areaName: String = "",
    ) : Exception(code, cause)

    private class NoCredibleSources(val detail: String, val areaName: String) : Exception(detail)

    constructor(
        scope: CoroutineScope,
        config: Config,
        streamOwner: HaExactEntityStreamOwner,
        offerAggregate: (HaPresenceAggregate) -> Boolean,
    ) : this(
        scope,
        DashboardHaApiSessionProvider(config),
        KtorHaPresenceTransport(
            socketFamilyPolicy = { MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily) },
        ),
        streamOwner,
        offerAggregate,
        exclusions = ConfigHaPresenceExclusions(config),
    )

    private val generation = AtomicLong()
    private val lock = Any()
    @Volatile private var request = disabledRequest()
    @Volatile private var aggregate = HaPresenceAggregate()
    @Volatile private var selection: HaPresenceResolution? = null
    @Volatile private var discoveryJob: Job? = null
    @Volatile private var closed = false
    private val feedObserver = HaPresenceFeedObserver(::acceptFeed)

    init {
        streamOwner.bindPresence(feedObserver)
        streamOwner.bindRegistryChanges(::registryChanged)
    }

    private var registryRefreshJob: Job? = null

    /**
     * Consecutive failed discoveries, and the window after the latest one in which a registry change may
     * not start another. Home Assistant emits registry events for the whole installation, so a panel whose
     * discovery fails deterministically would otherwise retry on every one. The change is delayed, never
     * dropped: it may be the one that fixes the failure, such as an Area being assigned. Explicit
     * configuration and refresh are not gated. Both fields are guarded by [lock].
     */
    private var discoveryFailures = 0
    private var failureBackoff: Job? = null

    private fun registryChanged() {
        synchronized(lock) {
            if (closed || !request.enabled) return
            registryRefreshJob?.cancel()
            val backoff = failureBackoff
            registryRefreshJob = scope.launch(workerDispatcher) {
                backoff?.join()
                kotlinx.coroutines.delay(REGISTRY_REFRESH_COALESCE_MS)
                runCatching(::refresh).onFailure {
                    Log.w(TAG, "presence registry refresh admission failed: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    suspend fun prerequisite(
        deviceUid: String,
        panelId: String,
        preferredAreaName: String = "",
        discoveryId: String? = null,
    ): HaPanelAreaPrerequisite {
        return try {
            suspend fun resolveAndRead(force: Boolean): Pair<HaApiSession, HaPanelAreaRegistrySnapshot> {
                val session = resolveSession(force)
                val snapshot = withContext(workerDispatcher) {
                    transport.panelAreaRegistry(session.baseUrl, checkNotNull(session.accessToken))
                }
                return session to snapshot
            }
            val (session, snapshot) = try {
                resolveAndRead(force = false)
            } catch (rejected: HaAuthenticationException) {
                resolveAndRead(force = true)
            }
            val area = HaPresenceProtocol.projectPanelArea(
                snapshot.devices,
                snapshot.areas,
                deviceUid,
                panelId,
                HaPanelDeviceMatcher.panelAssistantEntryIds(snapshot.panelAssistantProbe, discoveryId),
                preferredAreaName,
            )
            HaPanelAreaPrerequisite(
                HaPanelAreaPrerequisitePhase.ASSIGNED,
                areaName = area.name,
                authOwner = session.owner,
            )
        } catch (rejected: HaAuthenticationException) {
            HaPanelAreaPrerequisite(
                HaPanelAreaPrerequisitePhase.AUTH_FAILED,
                detail = "Home Assistant rejected the panel sign-in",
            )
        } catch (error: Exception) {
            val unassigned = error.message?.contains("has no Area", ignoreCase = true) == true
            HaPanelAreaPrerequisite(
                if (unassigned) HaPanelAreaPrerequisitePhase.UNASSIGNED
                else HaPanelAreaPrerequisitePhase.UNAVAILABLE,
                detail = if (unassigned) "Assign this panel to a Home Assistant Area before enabling Auto sleep."
                else "Home Assistant Area information is unavailable",
            )
        }
    }

    fun latestAggregate(): HaPresenceAggregate = aggregate

    /** One bounded, on-demand read of the exact Area sources currently selected by discovery. */
    suspend fun selectedHistory(startEpochMs: Long, endEpochMs: Long): HaPresenceSelectedHistory {
        require(startEpochMs >= 0L && endEpochMs > startEpochMs) { "Invalid presence history range" }
        data class Demand(
            val generation: Long,
            val request: HaPresenceRequest,
            val sourceIds: Set<String>,
            val discoveredSourceIds: Set<String>,
            val sourceLabels: Map<String, String>,
            val areaName: String,
            val areaKey: String,
            val sourceKeys: Map<String, String>,
        )
        val demand = synchronized(lock) {
            check(!closed) { "presence source manager is closed" }
            val currentSelection = selection
            val ids = currentSelection?.selectedEntityIds?.toSortedSet().orEmpty()
            val discovered = currentSelection?.discoveredEntityIds?.toSortedSet().orEmpty()
            check(request.enabled && discovered.isNotEmpty()) { "selected Area sources are unavailable" }
            Demand(generation.get(), request, ids, discovered, currentSelection?.sourceLabels.orEmpty(),
                currentSelection?.areaName.orEmpty(), currentSelection?.areaKey.orEmpty(),
                currentSelection?.sourceKeys.orEmpty())
        }
        suspend fun read(force: Boolean): Map<String, List<HaPresenceTransition>> {
            val session = resolveSession(force)
            return loadHistory(session, demand.discoveredSourceIds, startEpochMs, endEpochMs)
        }
        val transitions = try {
            read(force = false)
        } catch (rejected: HaAuthenticationException) {
            read(force = true)
        }
        synchronized(lock) {
            check(!closed && generation.get() == demand.generation && request == demand.request &&
                selection?.selectedEntityIds == demand.sourceIds &&
                selection?.discoveredEntityIds == demand.discoveredSourceIds
            ) { "selected Area sources changed during history retrieval" }
        }
        return HaPresenceSelectedHistory(
            demand.sourceIds, startEpochMs, endEpochMs, transitions, demand.sourceLabels,
            demand.discoveredSourceIds, demand.discoveredSourceIds - demand.sourceIds,
            demand.areaName, demand.areaKey, demand.sourceKeys,
        )
    }

    fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean): HaPresenceSourceUpdate {
        val target = synchronized(lock) {
            if (closed || !request.enabled) return HaPresenceSourceUpdate.UNAVAILABLE
            val current = selection ?: return HaPresenceSourceUpdate.UNAVAILABLE
            if (current.controllerEpoch != request.controllerEpoch) return HaPresenceSourceUpdate.STALE
            if (areaKey != current.areaKey) return HaPresenceSourceUpdate.STALE
            val entityId = current.sourceKeys.entries.firstOrNull { it.value == sourceKey }?.key
                ?: return HaPresenceSourceUpdate.STALE
            Triple(current.scope, current.areaId, entityId)
        }
        val committed = runCatching {
            exclusions.setIncluded(target.first, target.second, target.third, included)
        }.getOrElse { return HaPresenceSourceUpdate.COMMIT_FAILED }
        when (committed) {
            null -> return HaPresenceSourceUpdate.STALE
            false -> return HaPresenceSourceUpdate.COMMIT_FAILED
            true -> Unit
        }
        val shouldRefresh = synchronized(lock) { !closed && request.enabled }
        if (shouldRefresh) runCatching(::refresh)
        return HaPresenceSourceUpdate.UPDATED
    }

    fun configure(next: HaPresenceRequest) = configure(next, force = false)

    /** Re-runs bounded discovery/history without creating an autonomous retry lifecycle. */
    fun refresh() = configure(request, force = true)

    private fun configure(next: HaPresenceRequest, force: Boolean) {
        val normalized = next
        var run = 0L
        var kept: HaPresenceResolution? = null
        var pendingDiscovery: Job? = null
        synchronized(lock) {
            check(!closed) { "presence source manager is closed" }
            if (!force && normalized == request &&
                (discoveryJob?.isActive == true || selection != null)
            ) return
            // A refresh of the unchanged request keeps the selection, generation and live feed while
            // discovery revalidates it; [discover] publishes only an outcome that differs.
            kept = selection?.takeIf { force && normalized == request }
            if (!force && normalized != request) clearFailuresLocked()
            request = normalized
            discoveryJob?.cancel()
            discoveryJob = null
            if (kept == null) {
                run = generation.incrementAndGet()
                selection = null
            } else run = generation.get()
            if (normalized.enabled) {
                val revalidating = kept
                pendingDiscovery = scope.launch(start = CoroutineStart.LAZY) { discover(run, normalized, revalidating) }
                discoveryJob = pendingDiscovery
            }
        }
        if (!normalized.enabled) {
            reconcileStreamSources()
            publish(run, HaPresenceAggregate(HaPresencePhase.DISABLED))
        } else {
            if (kept == null) publish(run, HaPresenceAggregate(HaPresencePhase.AUTHENTICATING))
            pendingDiscovery?.start()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val run = generation.incrementAndGet()
            discoveryJob?.cancel()
            registryRefreshJob?.cancel()
            registryRefreshJob = null
            clearFailuresLocked()
            discoveryJob = null
            selection = null
            emitLocked(HaPresenceAggregate(
                controllerEpoch = request.controllerEpoch,
                managerGeneration = run,
                phase = HaPresencePhase.STOPPED,
            ))
        }
        streamOwner.replacePresenceDemand(emptySet(), watchRegistry = false)
        streamOwner.unbindRegistryChanges()
        streamOwner.unbindPresence(feedObserver)
    }

    /**
     * With [kept], this revalidates a live selection silently: an unchanged result only replaces the
     * stored selection (labels, lease), while a changed selection or any failure first takes a new
     * generation and then publishes exactly as a fresh discovery would.
     */
    private suspend fun discover(initialRun: Long, requested: HaPresenceRequest, kept: HaPresenceResolution?) {
        var run = initialRun
        val job = currentCoroutineContext()[Job]
        fun diverged(): Boolean = kept == null || synchronized(lock) {
            if (!current(run, requested) || discoveryJob !== job) return@synchronized false
            selection = null
            run = generation.incrementAndGet()
            true
        }
        try {
            var session: HaApiSession
            val found = try {
                session = resolveSession(force = false)
                bootstrap(run, requested, session, quiet = kept != null)
            } catch (rejected: HaAuthenticationException) {
                session = resolveSession(force = true)
                bootstrap(run, requested, session, quiet = kept != null)
            }
            synchronized(lock) { if (current(run, requested)) clearFailuresLocked() }
            if (kept != null && found.selectedEntityIds == kept.selectedEntityIds &&
                found.discoveredEntityIds == kept.discoveredEntityIds && found.areaKey == kept.areaKey
            ) {
                synchronized(lock) { if (current(run, requested) && discoveryJob === job) selection = found }
                return
            }
            if (!diverged()) return
            synchronized(lock) {
                if (!current(run, requested)) return
                selection = found
            }
            if (found.selectedEntityIds.isEmpty()) {
                reconcileStreamSources()
                publish(run, HaPresenceAggregate(
                    HaPresencePhase.NO_INCLUDED_SOURCES,
                    "All credible activity sources are excluded",
                ))
                return
            }
            publish(run, HaPresenceAggregate(HaPresencePhase.CONNECTING))
            reconcileStreamSources()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (none: NoCredibleSources) {
            synchronized(lock) { if (current(run, requested)) clearFailuresLocked() }
            if (!diverged()) return
            reconcileStreamSources()
            publish(run, HaPresenceAggregate(HaPresencePhase.NO_CREDIBLE_SOURCES, none.detail, areaName = none.areaName))
        } catch (rejected: HaAuthenticationException) {
            if (!diverged()) return
            noteFailure(run, requested)
            reconcileStreamSources()
            publish(run, HaPresenceAggregate(
                HaPresencePhase.AUTH_FAILED,
                "Home Assistant rejected the panel sign-in",
            ))
        } catch (error: Exception) {
            if (!diverged()) return
            val backoffMs = noteFailure(run, requested)
            reconcileStreamSources()
            val failure = error as? DiscoveryFailure
            val code = failure?.code ?: "discovery_failed"
            val cause = failure?.cause ?: error
            val detail = cause.message.orEmpty().filterNot(Char::isISOControl).take(MAX_DETAIL_CHARS)
            Log.w(TAG, "presence discovery failed code=$code cause=${cause.javaClass.simpleName} " +
                "detail=\"$detail\" registry_backoff_s=${backoffMs / 1_000L}")
            val phase = if (code == "no_area") HaPresencePhase.NO_AREA
                else HaPresencePhase.DISCOVERY_FAILED
            publish(run, HaPresenceAggregate(phase, code, areaName = failure?.areaName.orEmpty()))
        }
    }

    private suspend fun bootstrap(
        run: Long,
        requested: HaPresenceRequest,
        session: HaApiSession,
        quiet: Boolean,
    ): HaPresenceResolution {
        if (!quiet) publish(run, HaPresenceAggregate(HaPresencePhase.DISCOVERING))
        val snapshot = try {
            withContext(workerDispatcher) {
                transport.registry(session.baseUrl, checkNotNull(session.accessToken))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is HaAuthenticationException) throw error
            throw DiscoveryFailure("registry_transport", error)
        }
        val panelAssistantEntryIds =
            HaPanelDeviceMatcher.panelAssistantEntryIds(snapshot.panelAssistantProbe, requested.discoveryId)
        // Preserve the independently resolvable panel Area in a projection failure. This keeps the
        // status coherent with the prerequisite endpoint and lets clients distinguish a terminal
        // failure from a stale result for a previously selected Area.
        val projectedAreaName = runCatching {
            HaPresenceProtocol.projectPanelArea(
                snapshot.devices,
                snapshot.areas,
                requested.deviceUid,
                requested.panelId,
                panelAssistantEntryIds,
                requested.preferredAreaName,
            ).name
        }.getOrDefault("")
        val area = try {
            HaPresenceProtocol.projectArea(
                snapshot.devices,
                snapshot.areas,
                snapshot.entities,
                snapshot.states,
                requested.deviceUid,
                requested.panelId,
                panelAssistantEntryIds,
                requested.preferredAreaName,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val code = if (error.message?.contains("no Area", ignoreCase = true) == true) {
                "no_area"
            } else "registry_projection"
            throw DiscoveryFailure(code, error, projectedAreaName)
        }
        val assertingCandidates = area.candidates.filter {
            it.authority == HaPresenceAuthority.ASSERT_PRESENCE
        }
        Log.i(TAG, "presence authority candidates=${area.candidates.size} " +
            "asserting=${assertingCandidates.size} supporting=${area.candidates.size - assertingCandidates.size}")
        if (assertingCandidates.isEmpty()) {
            throw NoCredibleSources("no_device_source", area.panelAreaName)
        }
        val historyIds = assertingCandidates.mapTo(linkedSetOf()) { it.entityId }
        if (!quiet) publish(run, HaPresenceAggregate(HaPresencePhase.LEARNING))
        val transitions = try {
            val end = epochMillis() / MINUTE_MS * MINUTE_MS
            loadHistory(session, historyIds, end - HISTORY_WINDOW_MS, end)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DiscoveryFailure) {
            throw DiscoveryFailure(error.code, error.cause ?: error, area.panelAreaName)
        } catch (error: Exception) {
            throw DiscoveryFailure(historyFailureCode(error), error, area.panelAreaName)
        }
        val candidates = assertingCandidates.map { candidate ->
            candidate.copy(evidence = HaPresenceProtocol.evidence(transitions[candidate.entityId].orEmpty()))
        }
        val credible = candidates.asSequence()
            .filter { it.value != HaPresenceValue.UNAVAILABLE && it.evidence.autoEligible }
            .map(HaPresenceCandidate::entityId)
            .toCollection(linkedSetOf())
        if (credible.isEmpty()) {
            throw NoCredibleSources("insufficient_history", area.panelAreaName)
        }
        val excluded = exclusions.excluded(area.panelAreaId)
        val selected = credible.filterTo(linkedSetOf()) { it !in excluded }
        val learnedLease = selected.maxOfOrNull { id ->
            candidates.firstOrNull { it.entityId == id }?.evidence?.suggestedLeaseMs
                ?: HaPresenceProtocol.evidence(transitions[id].orEmpty()).suggestedLeaseMs
        }?.coerceIn(MIN_AUTO_SLEEP_LEASE_MS, MAX_AUTO_SLEEP_LEASE_MS) ?: MIN_AUTO_SLEEP_LEASE_MS
        val labels = candidates.asSequence()
            .filter { it.entityId in credible }
            .associateTo(linkedMapOf()) { it.entityId to it.friendlyName }
        val scope = exclusions.scope
        val areaKey = presenceOpaqueKey(scope, "area", area.panelAreaId)
        val keys = credible.associateWithTo(linkedMapOf()) {
            presenceOpaqueKey(scope, "source", area.panelAreaId, it)
        }
        return HaPresenceResolution(
            selected, credible, labels, learnedLease, area.panelAreaId, area.panelAreaName,
            areaKey, keys, scope, requested.controllerEpoch,
        )
    }

    /** Single synchronous ingress used by the bound shared-owner observer and deterministic races. */
    internal fun acceptFeed(snapshot: HaPresenceFeedSnapshot) {
        synchronized(lock) {
            if (closed || !request.enabled) return
            val currentSelection = selection ?: return
            val current = aggregate
            val run = generation.get()
            if (!current.admits(snapshot, request.controllerEpoch, run)) return
            val ids = currentSelection.selectedEntityIds.toSortedSet()
            val next = HaPresenceAggregate(
                controllerEpoch = request.controllerEpoch,
                managerGeneration = run,
                feedGeneration = snapshot.generation,
                feedRevision = snapshot.revision,
                phase = snapshot.phase.toPresencePhase(),
                detail = snapshot.detail.take(MAX_DETAIL_CHARS),
                finalStates = ids.associateWith { snapshot.states[it] ?: HaPresenceValue.UNAVAILABLE },
                hydrated = snapshot.hydrated,
                selectedEntityIds = ids,
                discoveredEntityIds = currentSelection.discoveredEntityIds,
                areaName = currentSelection.areaName,
                areaKey = currentSelection.areaKey,
                learnedLeaseMs = currentSelection.learnedLeaseMs,
                activityMarker = snapshot.lastOnActivity?.takeIf { it.entityId in ids },
                reconnectAttempt = snapshot.reconnectAttempt,
            )
            emitLocked(next)
        }
    }

    private fun HaExactEntityStreamPhase.toPresencePhase(): HaPresencePhase = when (this) {
        HaExactEntityStreamPhase.DISABLED -> HaPresencePhase.DISABLED
        HaExactEntityStreamPhase.AUTHENTICATING -> HaPresencePhase.AUTHENTICATING
        HaExactEntityStreamPhase.CONNECTING, HaExactEntityStreamPhase.SUBSCRIBING -> HaPresencePhase.CONNECTING
        HaExactEntityStreamPhase.SYNCHRONIZING -> HaPresencePhase.SYNCHRONIZING
        HaExactEntityStreamPhase.LIVE -> HaPresencePhase.LIVE
        HaExactEntityStreamPhase.AUTH_FAILED -> HaPresencePhase.AUTH_FAILED
        HaExactEntityStreamPhase.RECONNECTING -> HaPresencePhase.RECONNECTING
        HaExactEntityStreamPhase.STOPPED -> HaPresencePhase.STOPPED
    }

    private suspend fun loadHistory(
        session: HaApiSession,
        entityIds: Set<String>,
        startEpochMs: Long,
        endEpochMs: Long,
    ): Map<String, List<HaPresenceTransition>> {
        if (entityIds.isEmpty()) return emptyMap()
        val combined = entityIds.associateWithTo(linkedMapOf()) { mutableListOf<HaPresenceTransition>() }
        val batches = presenceHistoryBatches(entityIds)
        var retainedRows = 0
        var cursor = startEpochMs
        while (cursor < endEpochMs) {
            val chunkEnd = minOf(endEpochMs, cursor + HISTORY_CHUNK_MS)
            var chunkRows = 0
            batches.forEach { batch ->
                val response = try {
                    withContext(workerDispatcher) {
                        transport.history(session.baseUrl, checkNotNull(session.accessToken), batch, cursor, chunkEnd)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (error is HaAuthenticationException) throw error
                    throw DiscoveryFailure("history_transport", error)
                }
                for (index in 0 until response.length()) {
                    chunkRows += response.optJSONArray(index)?.length() ?: 0
                    if (chunkRows > MAX_HISTORY_ROWS_PER_CHUNK) {
                        throw HaPresenceHistoryLimitException("Home Assistant presence history exceeds its chunk row bound")
                    }
                }
                val parsed = try {
                    HaPresenceProtocol.parseHistory(response, batch, cursor, chunkEnd)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw DiscoveryFailure(historyFailureCode(error), error)
                }
                parsed.forEach { (id, rows) ->
                    retainedRows += rows.size
                    if (retainedRows > MAX_HISTORY_ROWS) {
                        throw HaPresenceHistoryLimitException("Home Assistant presence history exceeds its row bound")
                    }
                    combined.getValue(id).addAll(rows)
                }
            }
            cursor = chunkEnd
        }
        return combined.mapValues { (_, rows) ->
            rows.sortedBy(HaPresenceTransition::atEpochMs).fold(mutableListOf()) { out, row ->
                if (out.lastOrNull()?.atEpochMs == row.atEpochMs) out[out.lastIndex] = row
                else if (out.lastOrNull()?.value != row.value) out += row
                out
            }
        }
    }

    private suspend fun resolveSession(force: Boolean): HaApiSession = withContext(workerDispatcher) {
        val session = auth.resolve(force)
        when {
            session.rejected -> throw HaAuthenticationException("Home Assistant rejected the configured refresh credentials")
            session.baseUrl.isBlank() -> throw HaProtocolException("Home Assistant URL is not configured")
            session.accessToken.isNullOrBlank() -> throw HaAuthenticationException("Home Assistant access token is unavailable")
            session.owner == null -> throw HaProtocolException("Home Assistant credentials changed during presence setup")
            else -> session
        }
    }

    private fun publish(run: Long, next: HaPresenceAggregate) {
        synchronized(lock) {
            if (!current(run, request)) return
            val currentSelection = selection
            val ids = currentSelection?.selectedEntityIds?.toSortedSet().orEmpty()
            val discovered = currentSelection?.discoveredEntityIds?.toSortedSet().orEmpty()
            val newManagerRun = aggregate.managerGeneration != run
            val candidate = HaPresenceAggregate(
                controllerEpoch = request.controllerEpoch,
                managerGeneration = run,
                feedGeneration = aggregate.feedGeneration,
                feedRevision = aggregate.feedRevision,
                phase = next.phase,
                detail = next.detail.take(MAX_DETAIL_CHARS),
                finalStates = if (!newManagerRun && ids == aggregate.selectedEntityIds) aggregate.finalStates
                    else ids.associateWith { HaPresenceValue.UNAVAILABLE },
                hydrated = !newManagerRun && ids.isNotEmpty() &&
                    ids == aggregate.selectedEntityIds && aggregate.hydrated,
                selectedEntityIds = ids,
                discoveredEntityIds = discovered,
                // A no-source phase has no selection, but must still name the room discovery searched.
                areaName = currentSelection?.areaName.orEmpty().ifEmpty { next.areaName },
                areaKey = currentSelection?.areaKey.orEmpty(),
                learnedLeaseMs = currentSelection?.learnedLeaseMs ?: MIN_AUTO_SLEEP_LEASE_MS,
                activityMarker = aggregate.activityMarker?.takeIf { !newManagerRun && it.entityId in ids },
                reconnectAttempt = next.reconnectAttempt,
            )
            emitLocked(candidate)
        }
    }

    /** Caller holds [lock], making callback order and terminal delivery generation-atomic. */
    private fun emitLocked(next: HaPresenceAggregate) {
        if (next == aggregate) return
        aggregate = next
        safeCallback {
            if (!offerAggregate(next)) Log.w(TAG, "HA presence aggregate consumer is closed")
        }
    }

    private fun current(run: Long, expected: HaPresenceRequest): Boolean =
        !closed && generation.get() == run && request == expected

    /** Counts a failed discovery and opens its registry-change backoff window; returns the window. */
    private fun noteFailure(run: Long, expected: HaPresenceRequest): Long = synchronized(lock) {
        if (!current(run, expected)) return 0L
        discoveryFailures++
        val backoffMs = registryBackoffMs(discoveryFailures)
        failureBackoff?.cancel()
        failureBackoff = scope.launch(workerDispatcher) { kotlinx.coroutines.delay(backoffMs) }
        backoffMs
    }

    private fun clearFailuresLocked() {
        discoveryFailures = 0
        failureBackoff?.cancel()
        failureBackoff = null
    }

    /** Applies only the latest admitted demand without holding the manager lock across owner calls. */
    private fun reconcileStreamSources() {
        while (true) {
            val (stamp, desired) = synchronized(lock) {
                generation.get() to if (!closed && request.enabled) {
                    selection?.selectedEntityIds.orEmpty()
                } else emptySet()
            }
            streamOwner.replacePresenceDemand(desired, watchRegistry = !closed && request.enabled)
            if (generation.get() == stamp) return
        }
    }

    private inline fun safeCallback(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "HA presence callback failed: ${it.javaClass.simpleName}") }
    }

    private fun historyFailureCode(error: Exception): String =
        if (error is HaPresenceHistoryLimitException) "history_limit" else "history_parse"

    private companion object {
        const val TAG = "HaPresence"
        const val MAX_HISTORY_ROWS = 100_000
        const val MAX_HISTORY_ROWS_PER_CHUNK = 20_000
        const val MAX_DETAIL_CHARS = 240
        const val MINUTE_MS = 60_000L
        const val HISTORY_WINDOW_MS = 7L * 24L * 60L * MINUTE_MS
        const val HISTORY_CHUNK_MS = 12L * 60L * MINUTE_MS
        const val REGISTRY_REFRESH_COALESCE_MS = 2_000L
        const val REGISTRY_BACKOFF_BASE_MS = 60_000L
        const val REGISTRY_BACKOFF_MAX_MS = 30L * MINUTE_MS

        /** One minute after the first failure, doubling to a 30-minute ceiling. */
        fun registryBackoffMs(failures: Int): Long =
            (REGISTRY_BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(REGISTRY_BACKOFF_MAX_MS)

        fun disabledRequest() = HaPresenceRequest(
            false, "", "",
        )
    }
}

/** Bounded one-shot registry/history transport; it has no retry, subscription or liveness loop. */
internal class KtorHaPresenceTransport(
    private val socketFamilyPolicy: () -> MqttAddressFamilyPolicy = { MqttAddressFamilyPolicy.AUTOMATIC },
) : HaPresenceTransport {
    override suspend fun registry(baseUrl: String, accessToken: String): HaPresenceRegistrySnapshot {
        var devices: JSONObject? = null
        var areas: JSONObject? = null
        var entities: JSONObject? = null
        var probe: JSONObject? = null
        withCommandSocket(baseUrl, accessToken) { request ->
            devices = request(JSONObject().put("type", "config/device_registry/list"))
            areas = request(JSONObject().put("type", "config/area_registry/list"))
            entities = request(JSONObject().put("type", "config/entity_registry/list_for_display"))
            probe = HaPanelDeviceMatcher.readProbe(request, devices, entities)
        }
        return HaPresenceRegistrySnapshot(
            checkNotNull(devices),
            checkNotNull(areas),
            checkNotNull(entities),
            states(baseUrl, accessToken),
            probe,
        )
    }

    override suspend fun panelAreaRegistry(baseUrl: String, accessToken: String): HaPanelAreaRegistrySnapshot {
        var devices: JSONObject? = null
        var areas: JSONObject? = null
        var probe: JSONObject? = null
        withCommandSocket(baseUrl, accessToken) { request ->
            devices = request(JSONObject().put("type", "config/device_registry/list"))
            areas = request(JSONObject().put("type", "config/area_registry/list"))
            probe = HaPanelDeviceMatcher.readProbe(request, devices)
        }
        return HaPanelAreaRegistrySnapshot(checkNotNull(devices), checkNotNull(areas), probe)
    }

    override suspend fun history(
        baseUrl: String,
        accessToken: String,
        entityIds: Set<String>,
        startEpochMs: Long,
        endEpochMs: Long,
    ): JSONArray {
        val entities = URLEncoder.encode(entityIds.sorted().joinToString(","), Charsets.UTF_8.name())
        val end = URLEncoder.encode(Instant.ofEpochMilli(endEpochMs).toString(), Charsets.UTF_8.name())
        val path = "/api/history/period/${Instant.ofEpochMilli(startEpochMs)}?end_time=$end&filter_entity_id=$entities" +
            "&minimal_response&no_attributes&significant_changes_only=0"
        return JSONArray(checkNotNull(restGet(baseUrl, accessToken, path, MAX_HISTORY_BYTES, HISTORY_TIMEOUT_MS)))
    }

    private suspend fun states(baseUrl: String, accessToken: String): JSONArray =
        JSONArray(checkNotNull(restGet(baseUrl, accessToken, "/api/states", MAX_STATES_BYTES)))

    private suspend fun withCommandSocket(
        baseUrl: String,
        accessToken: String,
        block: suspend (suspend (JSONObject) -> JSONObject) -> Unit,
    ) {
        val policy = socketFamilyPolicy()
        val client = HaWebSocketClients.client(preferIpv4 = policy.initialPreferIpv4, ipv4Only = policy.ipv4Only)
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(CONNECT_TIMEOUT_MS) {
                HaWebSocketClients.open(client, EntityFilterProtocol.upstreamWebSocketUrl(baseUrl), MAX_WS_FRAME_BYTES)
            }
            socket = active
            authenticate(active, accessToken)
            var id = 0
            suspend fun request(command: JSONObject): JSONObject = withTimeout(REQUEST_TIMEOUT_MS) {
                val current = ++id
                active.send(Frame.Text(command.put("id", current).toString()))
                repeat(MAX_RESPONSE_FRAMES) {
                    val frame = active.incoming.receive() as? Frame.Text ?: return@repeat
                    val response = JSONObject(frame.readText())
                    if (response.optInt("id") == current && response.optString("type") == "result") {
                        if (!response.optBoolean("success")) {
                            throw HaProtocolException(response.optJSONObject("error")?.optString("message")
                                ?.take(MAX_ERROR_CHARS).orEmpty().ifBlank {
                                    "Home Assistant rejected a registry command"
                                })
                        }
                        return@withTimeout response
                    }
                }
                throw HaProtocolException("Home Assistant registry response exceeded its frame bound")
            }
            block(::request)
        } finally {
            runCatching { socket?.close() }
            client.close()
        }
    }

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

    private suspend fun restGet(
        baseUrl: String,
        accessToken: String,
        path: String,
        maxBytes: Long,
        readTimeoutMs: Int = HTTP_TIMEOUT_MS,
    ): String? = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trim().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            this.readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) connection.closeBody()
            when (code) {
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                    throw HaAuthenticationException("Home Assistant rejected the REST access token")
                !in 200..299 -> throw HaProtocolException("Home Assistant REST request failed (HTTP $code)")
                else -> connection.inputStream.use { String(BoundedStreams.readBytes(it, maxBytes), Charsets.UTF_8) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_FRAMES = 32
        const val MAX_ERROR_CHARS = 240
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_WS_FRAME_BYTES = 4L * 1024L * 1024L
        const val AUTH_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val HTTP_TIMEOUT_MS = 15_000
        const val HISTORY_TIMEOUT_MS = 20_000
        const val MAX_STATES_BYTES = 64L * 1024L * 1024L
        const val MAX_HISTORY_BYTES = 4L * 1024L * 1024L
    }
}
