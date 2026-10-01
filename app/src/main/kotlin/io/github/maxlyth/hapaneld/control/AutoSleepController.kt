package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import io.github.maxlyth.hapaneld.AutoSleepActivitySnapshot
import io.github.maxlyth.hapaneld.sensors.HaPresenceAggregate
import io.github.maxlyth.hapaneld.sensors.HaPanelAreaPrerequisite
import io.github.maxlyth.hapaneld.sensors.HaPanelAreaPrerequisitePhase
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.github.maxlyth.hapaneld.sensors.HaPresenceHistoryLimitException
import io.github.maxlyth.hapaneld.sensors.HaPresencePhase
import io.github.maxlyth.hapaneld.sensors.HaPresenceRequest
import io.github.maxlyth.hapaneld.sensors.HaPresenceSelectedHistory
import io.github.maxlyth.hapaneld.sensors.HaPresenceSourceManager
import io.github.maxlyth.hapaneld.sensors.HaPresenceSourceUpdate
import io.github.maxlyth.hapaneld.sensors.HaPresenceValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class AutoSleepManagerHandle(
    val configure: (HaPresenceRequest) -> Unit,
    val close: () -> Unit,
    val refresh: () -> Unit = {},
    val history: suspend (Long, Long) -> HaPresenceSelectedHistory = { _, _ ->
        error("selected Area source history is unavailable")
    },
    val setSourceIncluded: (String, String, Boolean) -> HaPresenceSourceUpdate = { _, _, _ ->
        HaPresenceSourceUpdate.UNAVAILABLE
    },
    val prerequisite: suspend (String, String, String, String?) -> HaPanelAreaPrerequisite = { _, _, _, _ ->
        HaPanelAreaPrerequisite(
            HaPanelAreaPrerequisitePhase.UNAVAILABLE,
            detail = "Auto-sleep Area discovery is unavailable",
        )
    },
)

internal data class AutoSleepRuntimeConfig(
    val enabled: Boolean,
    val deviceUid: String,
    val panelId: String,
    val haUrl: String,
    /** Locally configured area name (`ha_area`); presence sources come from here when it is set. */
    val haArea: String = "",
    val source: String = "home_assistant",
    val touchDelaySeconds: Int = 30,
    /** Panel Assistant discovery id; it proves which `panel_assistant` device is this panel. */
    val discoveryId: String? = null,
)

internal interface AutoSleepLearning {
    fun learnedLease(partition: String, baseLeaseMs: Long): AutoSleepLearnedLease
    fun recordGap(partition: String, evidence: AutoSleepLocalEvidence, gapMs: Long)
    fun recordCorrection(partition: String, floorMs: Long)
    fun flush()
}

private class StoredAutoSleepLearning(context: Context) : AutoSleepLearning {
    private val store = AutoSleepLearningStore(context)
    override fun learnedLease(partition: String, baseLeaseMs: Long) = store.learnedLease(partition, baseLeaseMs)
    override fun recordGap(partition: String, evidence: AutoSleepLocalEvidence, gapMs: Long) =
        store.recordGap(partition, evidence, gapMs)
    override fun recordCorrection(partition: String, floorMs: Long) = store.recordCorrection(partition, floorMs)
    override fun flush() = store.flush()
}

/** One bounded owner joining immutable HA aggregates, local evidence and physical screen epochs. */
internal class AutoSleepController private constructor(
    private val scope: CoroutineScope,
    private val screen: ScreenController,
    private val configuration: () -> AutoSleepRuntimeConfig,
    private val learning: AutoSleepLearning,
    private val subscribeToTouches: ((() -> Unit) -> PanelTouchObserver.Subscription?),
    private val onScreenChanged: (Boolean) -> Unit,
    private val onProjectionChanged: () -> Unit,
    private val onNoArea: (Long) -> Unit,
    private val elapsedRealtime: () -> Long,
    private val epochMillis: () -> Long,
    private val workerDispatcher: CoroutineDispatcher,
    sourceManagerFactory: ((HaPresenceAggregate) -> Boolean) -> AutoSleepManagerHandle,
) {
    private enum class Slot {
        CONFIGURE, AGGREGATE, TOUCH, TAP_WAKE, PROXIMITY, SCREEN_WAKE, DEADLINE, REDISCOVER, TERMINAL,
    }
    private enum class Admission { OPEN, CLOSING, CLOSED }
    private data class Entry(val sequence: Long, val slot: Slot, val value: Any?)
    private data class Configuration(val epoch: Long, val value: AutoSleepRuntimeConfig)
    private data class Touch(val atMs: Long, val expectedOffGeneration: Long?)
    private data class TapWake(val atMs: Long, val epoch: AutomaticOffEpoch)
    private data class Proximity(val atMs: Long, val near: Boolean?)
    private data class ScreenWake(val atMs: Long)
    private data class Deadline(val atMs: Long, val token: Long)
    private data class Rediscover(val epoch: Long, val token: Long)
    private data class HistoryFlightKey(val hours: Int, val projectionRevision: Long)

    constructor(
        context: Context,
        scope: CoroutineScope,
        config: Config,
        screen: ScreenController,
        onScreenChanged: (Boolean) -> Unit,
        onProjectionChanged: () -> Unit = {},
        onNoArea: (Long) -> Unit = {},
        elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
        epochMillis: () -> Long = System::currentTimeMillis,
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
        sourceManagerFactory: (((HaPresenceAggregate) -> Boolean) -> HaPresenceSourceManager),
    ) : this(
        scope = scope,
        screen = screen,
        configuration = {
            AutoSleepRuntimeConfig(
                config.autoSleep, config.deviceUid, config.panelId, config.haUrl, config.haArea, config.autoSleepSource,
                config.autoSleepTouchDelaySeconds, panelAssistantDiscoveryId(config.deviceUid),
            )
        },
        learning = StoredAutoSleepLearning(context),
        subscribeToTouches = { callback -> PanelTouchObserver.shared(context).subscribeWithActivityFallback(callback) },
        onScreenChanged = onScreenChanged,
        onProjectionChanged = onProjectionChanged,
        onNoArea = onNoArea,
        elapsedRealtime = elapsedRealtime,
        epochMillis = epochMillis,
        workerDispatcher = workerDispatcher,
        sourceManagerFactory = { offer ->
            sourceManagerFactory(offer).let { manager ->
                AutoSleepManagerHandle(
                    manager::configure, manager::close, manager::refresh, manager::selectedHistory,
                    manager::setSourceIncluded,
                    manager::prerequisite,
                )
            }
        },
    )

    internal constructor(
        scope: CoroutineScope,
        screen: ScreenController,
        configuration: () -> AutoSleepRuntimeConfig,
        learning: AutoSleepLearning,
        subscribeToTouches: ((() -> Unit) -> PanelTouchObserver.Subscription?) = { null },
        onScreenChanged: (Boolean) -> Unit = {},
        onProjectionChanged: () -> Unit = {},
        onNoArea: (Long) -> Unit = {},
        elapsedRealtime: () -> Long,
        epochMillis: () -> Long = System::currentTimeMillis,
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
        sourceManagerFactory: ((HaPresenceAggregate) -> Boolean) -> AutoSleepManagerHandle,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit,
    ) : this(
        scope, screen, configuration, learning, subscribeToTouches, onScreenChanged, onProjectionChanged, onNoArea,
        elapsedRealtime, epochMillis, workerDispatcher, sourceManagerFactory,
    )

    private val ingressLock = Any()
    private var sequence = 0L
    private val controllerEpoch = AtomicLong()
    private val slots = arrayOfNulls<Entry>(Slot.entries.size)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val manager = sourceManagerFactory(::offerAggregate)
    private val worker = scope.launch(workerDispatcher) { runOwner() }
    private val historyFlightLock = Any()
    private val historyFlights = mutableMapOf<HistoryFlightKey, CompletableDeferred<String>>()
    private val historyReadMutex = Mutex()
    private val projectionRevision = AtomicLong()

    @Volatile private var admission = Admission.OPEN
    private var configured = AutoSleepRuntimeConfig(false, "", "", "", "")
    @Volatile private var status = Status()
    private var aggregate = HaPresenceAggregate()
    private var decision: AutoSleepDecision? = null
    private var touchSubscription: PanelTouchObserver.Subscription? = null
    private var policy: AutoSleepPolicyState? = null
    private var partition = ""
    private var acceptedManagerGeneration = -1L
    private var automaticEpoch: AutomaticOffEpoch? = null
    /** An off adopted from a replaced process; Home Assistant reconnecting is not a reason to wake it. */
    private var inheritedEpoch: AutomaticOffEpoch? = null
    /** Set once by [start]; consumed by the first configuration the owner accepts, whichever epoch it is. */
    private val adoptionPending = AtomicBoolean(false)
    /** Startup holds the main thread, so the first touch-wake attach can time out; a dark panel gets two more tries. */
    private var adoptionRetriesLeft = 2
    private var provedTapGeneration: Long? = null
    private var manualSuppression = false
    private var suppressionSawAllOff = false
    private var rearmOffGeneration: Long? = null
    private var lastHaActivityAtMs: Long? = null
    private var lastProximityNear: Boolean? = null
    private var localRevision = 0L
    private val usesPanelPresence: Boolean get() = configured.source == "panel"
    private val usesTouchInactivity: Boolean get() = configured.source == "touch"
    private var deadlineToken = 0L
    private var deadlineJob: Job? = null
    private var rediscoveryToken = 0L
    private var rediscoveryJob: Job? = null
    private var noAreaFailOffRequested = false
    /** Set by [wakeOwnedScreen] so the wake it causes is not booked as a touch. */
    private var ownWakePending = false

    fun start(): Boolean {
        adoptionPending.set(true)
        return configureLatest()
    }
    fun refresh(): Boolean = configureLatest()
    fun isCurrentConfigurationEpoch(expectedEpoch: Long): Boolean =
        admission == Admission.OPEN && controllerEpoch.get() == expectedEpoch

    fun statusJson(): String {
        val current = status
        return JSONObject()
            .put("available", current.available)
            .put("enabled", current.enabled)
            .put("source", current.source)
            .put("phase", current.phase)
            .put("reason", current.reason)
            .put("learned_lease_ms", current.learnedLeaseMs ?: JSONObject.NULL)
            .put("source_count", current.sourceCount.coerceAtLeast(0))
            .put("discovered_source_count", current.discoveredSourceCount.coerceAtLeast(0))
            .put("area_name", current.areaName)
            .put("manual_suppression", current.manualSuppression)
            .put("detail", current.detail)
            .toString()
    }

    fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean): HaPresenceSourceUpdate =
        manager.setSourceIncluded(areaKey, sourceKey, included)

    suspend fun prerequisite(): HaPanelAreaPrerequisite {
        val current = configuration()
        return manager.prerequisite(current.deviceUid, current.panelId, current.haArea, current.discoveryId)
    }

    /** Counterfactual history using today's policy/lease and HA Area-source history only. */
    suspend fun historyJson(hours: Int): String {
        require(hours in 1..48) { "hours must be between 1 and 48" }
        val key = HistoryFlightKey(hours, status.projectionRevision)
        var leader = false
        val flight = synchronized(historyFlightLock) {
            historyFlights[key] ?: CompletableDeferred<String>().also {
                historyFlights[key] = it
                leader = true
            }
        }
        if (!leader) return flight.await()
        try {
            val result = historyReadMutex.withLock { calculateHistoryJson(hours) }
            flight.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            flight.completeExceptionally(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            flight.completeExceptionally(error)
            throw error
        } finally {
            synchronized(historyFlightLock) { historyFlights.remove(key, flight) }
        }
    }

    private suspend fun calculateHistoryJson(hours: Int): String {
        val endEpochMs = epochMillis().coerceAtLeast(1L)
        val windowStartEpochMs = (endEpochMs - hours * HOUR_MS).coerceAtLeast(0L)
        val historyStartEpochMs = (windowStartEpochMs - HISTORY_WARMUP_MS).coerceAtLeast(0L)
        val current = status
        val leaseMs = current.replayLeaseMs
        if (current.source != "home_assistant") {
            return JSONObject(unavailableHistoryJson(
                hours, windowStartEpochMs, endEpochMs, leaseMs, "local_history_unavailable",
            )).put("source_scope", if (current.source == "touch") "touch_inactivity" else "panel_proximity")
                .put("area_sources_only", false).toString()
        }
        if (!current.enabled || leaseMs == null) {
            return unavailableHistoryJson(hours, windowStartEpochMs, endEpochMs, null, "runtime_unavailable")
        }
        return try {
            val historyReadStartedMs = elapsedRealtime()
            val history = manager.history(historyStartEpochMs, endEpochMs)
            val historyFetchMs = (elapsedRealtime() - historyReadStartedMs).coerceAtLeast(0L)
            val replayStartedMs = elapsedRealtime()
            val trace = AutoSleepReplay.run(
                sourceIds = history.sourceIds,
                events = autoSleepHistoryEvents(history),
                fromMs = historyStartEpochMs,
                untilMs = endEpochMs,
                config = AutoSleepPolicyConfig(learnedLeaseMs = leaseMs),
            )
            val segments = AutoSleepReplay.minuteSegments(trace, windowStartEpochMs, endEpochMs)
            val visibleMs = endEpochMs - windowStartEpochMs
            val requiredBucketMs = if (history.discoveredSourceIds.isEmpty()) REPLAY_BUCKET_MS else
                (visibleMs * history.discoveredSourceIds.size + MAX_SOURCE_CELLS - 1) / MAX_SOURCE_CELLS
            val sourceBucketMs = maxOf(
                REPLAY_BUCKET_MS,
                (requiredBucketMs + REPLAY_BUCKET_MS - 1) / REPLAY_BUCKET_MS * REPLAY_BUCKET_MS,
            )
            val sourceSegments = AutoSleepReplay.bucketSourceSegments(
                history, windowStartEpochMs, endEpochMs, sourceBucketMs,
            )
            val displayLabels = uniqueSourceLabels(history)
            val replayMs = (elapsedRealtime() - replayStartedMs).coerceAtLeast(0L)
            JSONObject()
                .put("available", true)
                .put("hours", hours)
                .put("bucket_ms", REPLAY_BUCKET_MS)
                .put("source_bucket_ms", sourceBucketMs)
                .put("window_start_epoch_ms", windowStartEpochMs)
                .put("window_end_epoch_ms", endEpochMs)
                .put("warmup_ms", windowStartEpochMs - historyStartEpochMs)
                .put("learned_lease_ms", leaseMs)
                .put("source_scope", "selected_area_sources")
                .put("area_sources_only", true)
                .put("source_count", history.sourceIds.size)
                .put("discovered_source_count", history.discoveredSourceIds.size)
                .put("area_name", history.areaName)
                .put("area_key", history.areaKey)
                .put("exclusions", org.json.JSONArray(HISTORY_EXCLUSIONS))
                .put("segments", org.json.JSONArray().apply {
                    segments.forEach { segment ->
                        put(JSONObject()
                            .put("start_epoch_ms", segment.startEpochMs)
                            .put("end_epoch_ms", segment.endEpochMs)
                            .put("output", segment.output.name.lowercase(Locale.ROOT))
                            .put("reason", segment.reason.name.lowercase(Locale.ROOT)))
                    }
                })
                .put("source_lanes", org.json.JSONArray().apply {
                    sourceSegments.forEach { (sourceId, laneSegments) ->
                        put(JSONObject()
                            .put("source_key", history.sourceKeys.getValue(sourceId))
                            .put("label", displayLabels.getValue(sourceId))
                            .put("included", sourceId !in history.excludedSourceIds)
                            .put("segments", org.json.JSONArray().apply {
                                laneSegments.forEach { segment ->
                                    put(JSONObject()
                                        .put("start_epoch_ms", segment.startEpochMs)
                                        .put("end_epoch_ms", segment.endEpochMs)
                                        .put("state", segment.value.name.lowercase(Locale.ROOT)))
                                }
                            }))
                    }
                })
                .put("diagnostics", JSONObject()
                    .put("history_rows", history.transitions.values.sumOf { it.size })
                    .put("source_segments", sourceSegments.values.sumOf { it.size })
                    .put("history_fetch_ms", historyFetchMs)
                    .put("replay_ms", replayMs))
                .toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val detail = historyFailureDetail(error)
            Log.w(TAG, "auto-sleep history replay unavailable code=$detail type=${error.javaClass.simpleName}")
            unavailableHistoryJson(hours, windowStartEpochMs, endEpochMs, leaseMs, detail)
        }
    }

    private fun historyFailureDetail(error: Exception): String = when {
        error is HaPresenceHistoryLimitException -> "history_limit"
        error is HaAuthenticationException -> "history_auth"
        error.message in setOf("history_transport", "history_parse", "history_limit") -> error.message!!
        error.message?.contains("sources changed", ignoreCase = true) == true -> "sources_changed"
        error.message?.contains("sources are unavailable", ignoreCase = true) == true -> "runtime_unavailable"
        else -> "history_unavailable"
    }

    private fun unavailableHistoryJson(
        hours: Int,
        startEpochMs: Long,
        endEpochMs: Long,
        learnedLeaseMs: Long?,
        detail: String,
    ) =
        JSONObject()
            .put("available", false)
            .put("hours", hours)
            .put("bucket_ms", REPLAY_BUCKET_MS)
            .put("source_bucket_ms", REPLAY_BUCKET_MS)
            .put("window_start_epoch_ms", startEpochMs)
            .put("window_end_epoch_ms", endEpochMs)
            .put("warmup_ms", HISTORY_WARMUP_MS)
            .put("learned_lease_ms", learnedLeaseMs ?: JSONObject.NULL)
            .put("source_scope", "selected_area_sources")
            .put("area_sources_only", true)
            .put("source_count", 0)
            .put("exclusions", org.json.JSONArray(HISTORY_EXCLUSIONS))
            .put("segments", org.json.JSONArray())
            .put("source_lanes", org.json.JSONArray())
            .put("detail", detail)
            .toString()

    private fun uniqueSourceLabels(history: HaPresenceSelectedHistory): Map<String, String> {
        val used = mutableMapOf<String, Int>()
        return history.discoveredSourceIds.toSortedSet().associateWithTo(linkedMapOf()) { sourceId ->
            val base = history.sourceLabels[sourceId]?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(sourceId, ignoreCase = true) }
                ?.take(MAX_SOURCE_LABEL_CHARS) ?: "Activity source"
            val duplicate = used.merge(base.lowercase(Locale.ROOT), 1, Int::plus) ?: 1
            if (duplicate == 1) base else "$base ($duplicate)"
        }
    }

    internal fun activitySnapshot(): AutoSleepActivitySnapshot {
        val current = status
        return AutoSleepActivitySnapshot(
            holdingAwake = decision?.output == AutoSleepOutput.HOLD_AWAKE,
            policyHealthy = current.available,
            reason = current.reason,
            learnedDelay = current.learnedLeaseMs?.let {
                if (current.source == "touch") "${it / 1_000}s" else "${it / 60_000}m"
            } ?: "unknown",
            sourceCount = current.sourceCount,
            phase = current.phase,
            manualSuppression = current.manualSuppression,
        )
    }

    /** Feed only the learned, qualified near edge; raw proximity never enters this controller. */
    fun noteProximityState(near: Boolean?): Boolean =
        admit(Slot.PROXIMITY, Proximity(elapsedRealtime(), near))

    fun noteScreenWoken(): Boolean = admit(Slot.SCREEN_WAKE, ScreenWake(elapsedRealtime()))

    /** Exact proof emitted only by the screen epoch that a physical tap actually woke. */
    fun noteTapWake(epoch: AutomaticOffEpoch): Boolean =
        admit(Slot.TAP_WAKE, TapWake(elapsedRealtime(), epoch))

    internal fun advanceToForTest(atMs: Long, token: Long = deadlineToken): Boolean =
        admit(Slot.DEADLINE, Deadline(atMs, token))

    internal fun deadlineTokenForTest(): Long = deadlineToken

    /** One owner drain split at its dequeue, so a test can land ingress between taking and handling. */
    internal fun drainForTest(afterTake: () -> Unit) {
        val batch = takeBatch()
        afterTake()
        batch.forEach(::handle)
    }

    internal fun feedPositionForTest(): AutoSleepFeedPosition? = policy?.feed

    internal fun noteTouchForTest(atMs: Long, expectedOffGeneration: Long?): Boolean =
        admit(Slot.TOUCH, Touch(atMs, expectedOffGeneration))

    fun closeAndJoin(timeoutMs: Long): Boolean {
        val first = synchronized(ingressLock) {
            if (admission != Admission.OPEN) false else {
                admission = Admission.CLOSING
                true
            }
        }
        if (first) {
            deadlineJob?.cancel()
            rediscoveryJob?.cancel()
            touchSubscription?.close()
            touchSubscription = null
            runCatching(manager.close)
                .onFailure { Log.w(TAG, "presence manager close failed", it) }
            synchronized(ingressLock) {
                putLocked(Slot.TERMINAL, Unit)
                wake.trySend(Unit)
            }
        }
        val joined = runBlocking {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { worker.join(); true } ?: false
        }
        if (!joined) worker.cancel()
        return joined
    }

    private fun configureLatest(): Boolean = synchronized(ingressLock) {
        if (admission != Admission.OPEN) return false
        val next = Configuration(controllerEpoch.incrementAndGet(), configuration())
        putLocked(Slot.CONFIGURE, next)
        wake.trySend(Unit)
        true
    }

    /** Constant-time latest-state admission; false has the single meaning "controller is closing". */
    private fun offerAggregate(next: HaPresenceAggregate): Boolean = synchronized(ingressLock) {
        if (admission != Admission.OPEN) return false
        val prior = slots[Slot.AGGREGATE.ordinal]?.value as? HaPresenceAggregate
        if (prior == null || next.positionAtLeast(prior)) putLocked(Slot.AGGREGATE, next)
        wake.trySend(Unit)
        true
    }

    private fun admit(slot: Slot, value: Any?): Boolean = synchronized(ingressLock) {
        if (admission != Admission.OPEN) return false
        putLocked(slot, value)
        wake.trySend(Unit)
        true
    }

    private fun putLocked(slot: Slot, value: Any?) {
        slots[slot.ordinal] = Entry(++sequence, slot, value)
    }

    private fun takeBatch(): List<Entry> = synchronized(ingressLock) {
        slots.mapNotNull { it }.also { slots.fill(null) }.sortedBy(Entry::sequence)
    }

    private suspend fun runOwner() {
        try {
            var terminal = false
            while (!terminal) {
                wake.receiveCatching().getOrNull() ?: break
                do {
                    val batch = takeBatch()
                    batch.forEach { entry ->
                        if (entry.slot == Slot.TERMINAL) terminal = true else try {
                            handle(entry)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.e(TAG, "auto-sleep ${entry.slot.name.lowercase()} failed", error)
                        }
                    }
                } while (!terminal && synchronized(ingressLock) { slots.any { it != null } })
            }
        } finally {
            withContext(NonCancellable) {
                runCatching(learning::flush).onFailure { Log.w(TAG, "auto-sleep learning flush failed", it) }
                synchronized(ingressLock) { admission = Admission.CLOSED }
                wake.close()
            }
        }
    }

    private fun handle(entry: Entry) {
        when (entry.slot) {
            Slot.CONFIGURE -> configure(entry.value as Configuration)
            Slot.AGGREGATE -> applyAggregate(entry.value as HaPresenceAggregate)
            Slot.TOUCH -> applyTouch(entry.value as Touch)
            Slot.TAP_WAKE -> applyTapWake(entry.value as TapWake)
            Slot.PROXIMITY -> applyProximity(entry.value as Proximity)
            // A tap callback carrying the exact automatic epoch may be queued behind this generic wake
            // notification. Never let generic reconciliation consume that proof before it is reduced.
            Slot.SCREEN_WAKE -> {
                val reason = if (ownWakePending) AutoSleepReason.SOURCE_LOSS_WAKE else AutoSleepReason.TOUCH_ACTIVITY
                ownWakePending = false
                reduce(AutoSleepEvent.ScreenWoken((entry.value as ScreenWake).atMs, reason), actuate = false)
            }
            Slot.DEADLINE -> (entry.value as Deadline).let { deadline ->
                if (deadline.token == deadlineToken) reduce(AutoSleepEvent.TimeAdvanced(deadline.atMs))
            }
            Slot.REDISCOVER -> (entry.value as Rediscover).let { rediscover ->
                if (configured.enabled && configured.source == "home_assistant" && rediscover.epoch == controllerEpoch.get() &&
                    rediscover.token == rediscoveryToken
                ) {
                    manager.refresh()
                    scheduleRediscovery(rediscover.epoch, rediscover.token)
                }
            }
            Slot.TERMINAL -> Unit
        }
    }

    private fun configure(next: Configuration) {
        if (next.epoch != controllerEpoch.get()) return
        deadlineJob?.cancel()
        rediscoveryJob?.cancel()
        rediscoveryToken++
        deadlineToken++
        val sourceChanged = configured.source != next.value.source
        val touchDelayChanged = next.value.source == "touch" &&
            configured.touchDelaySeconds != next.value.touchDelaySeconds
        configured = next.value
        if (sourceChanged) {
            wakeOwnedScreen()
            policy = null
            decision = null
            lastProximityNear = null
            lastHaActivityAtMs = null
        }
        if (touchDelayChanged) {
            policy = null
            decision = null
        }
        acceptedManagerGeneration = -1L
        if (!next.value.enabled) {
            wakeOwnedScreen()
            policy = null
            decision = null
            aggregate = HaPresenceAggregate(controllerEpoch = next.epoch, phase = HaPresencePhase.DISABLED)
            automaticEpoch = null
            manualSuppression = false
            suppressionSawAllOff = false
            rearmOffGeneration = null
            touchSubscription?.close()
            touchSubscription = null
            noAreaFailOffRequested = false
            publishProjection()
        } else if (touchSubscription == null) {
            noAreaFailOffRequested = false
            touchSubscription = subscribeToTouches {
                admit(Slot.TOUCH, Touch(elapsedRealtime(), screen.currentOffGeneration()))
            }
        }
        if (adoptionPending.getAndSet(false) && automaticEpoch == null) {
            val adopted = screen.adoptInheritedDark(automatic = next.value.enabled)
            if (screen.isIntendedOff()) {
                automaticEpoch = adopted
                inheritedEpoch = adopted
                // A retried adoption can land after presence went live and granted its startup lease;
                // drop that policy so the next feed rebuilds it starting asleep.
                if (adopted != null && next.value.source == "home_assistant") {
                    policy = null
                    decision = null
                }
                onScreenChanged(false)
            } else if (adoptionRetriesLeft > 0 && screen.looksDark()) {
                adoptionRetriesLeft--
                scope.launch(workerDispatcher) {
                    delay(ADOPTION_RETRY_MS)
                    adoptionPending.set(true)
                    configureLatest()
                }
            }
        }
        manager.configure(HaPresenceRequest(
            enabled = next.value.enabled && next.value.source == "home_assistant",
            deviceUid = next.value.deviceUid,
            panelId = next.value.panelId,
            controllerEpoch = next.epoch,
            preferredAreaName = next.value.haArea,
            discoveryId = next.value.discoveryId,
        ))
        if (next.value.enabled && usesTouchInactivity) {
            partition = "touch_inactivity"
            if (policy == null) {
                policy = AutoSleepPolicyReducer.initial(
                    setOf(LOCAL_SOURCE), AutoSleepPolicyConfig(
                        fixedLeaseMs = next.value.touchDelaySeconds.coerceIn(5, 86_400) * 1_000L,
                        qualifiedProximityExtensionMs = 0L,
                    ),
                )
                aggregate = HaPresenceAggregate(
                    phase = HaPresencePhase.LIVE,
                    controllerEpoch = next.epoch,
                    selectedEntityIds = setOf(LOCAL_SOURCE),
                )
                reduce(AutoSleepEvent.SourcesHydrated(
                    eventTime(), mapOf(LOCAL_SOURCE to AutoSleepSourceState.OFF),
                    AutoSleepFeedPosition(next.epoch, ++localRevision),
                ), actuate = !screen.isIntendedOff())
            } else {
                decision?.let { acceptDecision(it, shouldActuate = false) }
            }
        } else if (next.value.enabled && usesPanelPresence) {
            partition = "panel_proximity|v1"
            if (policy == null) {
                val learned = learning.learnedLease(partition, MIN_AUTO_SLEEP_LEASE_MS)
                policy = AutoSleepPolicyReducer.initial(
                    setOf(LOCAL_SOURCE), AutoSleepPolicyConfig(learnedLeaseMs = learned.leaseMs),
                )
            }
            applyPanelPresence(null, eventTime())
        } else if (next.value.enabled) scheduleRediscovery(next.epoch, rediscoveryToken)
    }

    private fun scheduleRediscovery(epoch: Long, token: Long) {
        rediscoveryJob?.cancel()
        rediscoveryJob = scope.launch(workerDispatcher) {
            delay(REDISCOVERY_INTERVAL_MS)
            admit(Slot.REDISCOVER, Rediscover(epoch, token))
        }
    }

    private fun applyAggregate(next: HaPresenceAggregate) {
        if (configured.source != "home_assistant") return
        if (next.controllerEpoch != controllerEpoch.get()) return
        if (next.managerGeneration < acceptedManagerGeneration) return
        val currentFeed = policy?.feed
        if (next.hydrated && currentFeed != null && next.selectedEntityIds == policy?.sources?.keys &&
            (next.feedGeneration < currentFeed.generation ||
                next.feedGeneration == currentFeed.generation && next.feedRevision < currentFeed.revision)
        ) return
        if (!configured.enabled || next.phase != HaPresencePhase.LIVE || !next.hydrated ||
            next.selectedEntityIds.isEmpty() || next.feedGeneration < 0L || next.feedRevision < 0L
        ) {
            acceptedManagerGeneration = next.managerGeneration
            aggregate = next
            deadlineJob?.cancel()
            deadlineToken++
            // The stream reports DISABLED before it subscribes, so only a resolved missing Area releases an
            // adopted off here; turning auto-sleep off goes through configure(), which always releases it.
            wakeOwnedScreen(transient = next.phase != HaPresencePhase.NO_AREA)
            publishProjection()
            if (configured.enabled && next.phase == HaPresencePhase.NO_AREA && !noAreaFailOffRequested) {
                noAreaFailOffRequested = true
                runCatching { onNoArea(next.controllerEpoch) }.onFailure {
                    Log.e(TAG, "auto-sleep Area-removal fail-off request failed", it)
                }
            }
            return
        }
        val states = next.selectedEntityIds.associateWith { id ->
            when (next.finalStates[id] ?: HaPresenceValue.UNAVAILABLE) {
                HaPresenceValue.ON -> AutoSleepSourceState.ON
                HaPresenceValue.OFF -> AutoSleepSourceState.OFF
                HaPresenceValue.UNAVAILABLE -> AutoSleepSourceState.UNAVAILABLE
            }
        }
        val previous = policy
        val sourceChanged = previous == null || previous.sources.keys != states.keys
        val marker = next.activityMarker?.let {
            AutoSleepActivityMarker(it.sequence, it.receivedAtMonotonicMs)
        }
        if (!sourceChanged && currentFeed?.generation == next.feedGeneration &&
            currentFeed.revision == next.feedRevision &&
            (previous.sources != states || previous.activityMarker != marker)
        ) return
        acceptedManagerGeneration = next.managerGeneration
        aggregate = next
        val startsAsleep = inheritedEpoch != null && inheritedEpoch == automaticEpoch && screen.isIntendedOff()
        inheritedEpoch = null
        if (sourceChanged) {
            partition = buildString {
                append(configured.haUrl.trim().lowercase(Locale.ROOT).trimEnd('/'))
                append('|')
                append(next.selectedEntityIds.sorted().joinToString(","))
                append("|v1")
            }
            val learned = learning.learnedLease(partition, next.learnedLeaseMs)
            policy = AutoSleepPolicyReducer.initial(
                states.keys, AutoSleepPolicyConfig(learnedLeaseMs = learned.leaseMs, startsAsleep = startsAsleep),
            )
            decision = null
            manualSuppression = false
            suppressionSawAllOff = false
            rearmOffGeneration = null
        } else {
            val learned = learning.learnedLease(partition, next.learnedLeaseMs)
            reduce(AutoSleepEvent.LearnedLeaseChanged(eventTime(), learned.leaseMs), actuate = false)
        }
        val beforeMarker = policy?.activityMarker
        val atMs = maxOf(eventTime(), marker?.atMs ?: 0L)
        val transition = reduce(AutoSleepEvent.SourcesHydrated(
            atMs = atMs,
            states = states,
            feed = AutoSleepFeedPosition(next.feedGeneration, next.feedRevision),
            activityMarker = marker,
        )) ?: return
        val markerAdvanced = marker != null && marker != beforeMarker
        if (markerAdvanced) {
            lastHaActivityAtMs = marker.atMs
            if (manualSuppression && suppressionSawAllOff) {
                manualSuppression = false
                suppressionSawAllOff = false
            }
            if (automaticEpoch == null && screen.isIntendedOff()) {
                rearmOffGeneration = screen.currentOffGeneration()
                actuate(transition)
            }
        }
        if (manualSuppression && states.values.all { it == AutoSleepSourceState.OFF }) {
            suppressionSawAllOff = true
        }
    }

    private fun applyTouch(touch: Touch) {
        val expected = touch.expectedOffGeneration
        if (expected == null) {
            applyLocalEvidence(AutoSleepLocalEvidence.TOUCH, touch.atMs, false)
            return
        }
        if (provedTapGeneration == expected) return
        val epoch = automaticEpoch
        if (epoch?.generation != expected) {
            applyLocalEvidence(AutoSleepLocalEvidence.TOUCH, touch.atMs, false)
            return
        }
        when (screen.wakeAutomaticallyIfOwned(epoch) { configured.enabled && admission == Admission.OPEN }) {
            WakeOutcome.WOKEN -> {
                automaticEpoch = null
                provedTapGeneration = expected
                onScreenChanged(true)
                applyLocalEvidence(AutoSleepLocalEvidence.TOUCH, touch.atMs, true)
            }
            // The overlay callback or a generic wake event now owns the exact proof. Reducing this as a
            // non-causal touch would erase the correction evidence before that proof reaches the actor.
            WakeOutcome.ALREADY_ON, WakeOutcome.STALE_GENERATION -> Unit
            WakeOutcome.ACTUATION_FAILED -> Unit
        }
    }

    private fun applyTapWake(tap: TapWake) {
        if (provedTapGeneration == tap.epoch.generation) return
        if (automaticEpoch != tap.epoch) return
        automaticEpoch = null
        provedTapGeneration = tap.epoch.generation
        applyLocalEvidence(AutoSleepLocalEvidence.TOUCH, tap.atMs, true)
    }

    private fun applyProximity(next: Proximity) {
        val previous = lastProximityNear
        lastProximityNear = next.near
        if (usesTouchInactivity) return
        if (usesPanelPresence) {
            if (configured.enabled) applyPanelPresence(next.near, next.atMs)
            return
        }
        if (next.near == true && previous != true) {
            // Presence may extend an already visible interaction, never override a dark screen.
            // Keep the check and reduction with the screen transition monitor: a queued approach
            // must not race a manual/automatic OFF and turn its lease extension into a wake.
            synchronized(screen) {
                if (screen.isIntendedOff() || !screen.isOn() || screen.observedLit() != true) return
                applyLocalEvidence(AutoSleepLocalEvidence.PROXIMITY, next.atMs, false)
            }
        }
    }

    /** Local presence owns an independent source; it never borrows HA availability or history. */
    private fun applyPanelPresence(near: Boolean?, atMs: Long) {
        aggregate = HaPresenceAggregate(
            phase = HaPresencePhase.LIVE,
            controllerEpoch = controllerEpoch.get(),
            selectedEntityIds = setOf(LOCAL_SOURCE),
        )
        val state = when (near) {
            true -> AutoSleepSourceState.ON
            false -> AutoSleepSourceState.OFF
            null -> AutoSleepSourceState.UNAVAILABLE
        }
        // Hydration can hold a visible screen, but is never a wake gesture. Source loss restores only
        // the screen epoch this controller put to sleep so touch remains a guaranteed fallback.
        reduce(AutoSleepEvent.SourcesHydrated(
            eventTime(atMs), mapOf(LOCAL_SOURCE to state),
            AutoSleepFeedPosition(controllerEpoch.get(), ++localRevision),
        ), actuate = false)
        if (near == null) wakeOwnedScreen(transient = true)
        else decision?.let(::actuate)
    }

    private fun applyLocalEvidence(kind: AutoSleepLocalEvidence, atMs: Long, causalTapWake: Boolean) {
        val active = policy ?: return
        if (usesTouchInactivity) {
            if (kind == AutoSleepLocalEvidence.TOUCH) reduce(AutoSleepEvent.Touch(eventTime(atMs), causalTapWake))
            return
        }
        val last = lastHaActivityAtMs
        if (last != null && active.sources.values.none { it == AutoSleepSourceState.ON }) {
            learning.recordGap(partition, kind, atMs - last)
        }
        val learned = learning.learnedLease(partition, aggregate.learnedLeaseMs)
        reduce(AutoSleepEvent.LearnedLeaseChanged(eventTime(atMs), learned.leaseMs), actuate = false)
        val before = decision?.effectiveLeaseMs ?: learned.leaseMs
        val event = when (kind) {
            AutoSleepLocalEvidence.TOUCH -> AutoSleepEvent.Touch(eventTime(atMs), causalTapWake)
            AutoSleepLocalEvidence.PROXIMITY -> AutoSleepEvent.QualifiedProximity(eventTime(atMs))
        }
        val after = reduce(event) ?: return
        if (causalTapWake && after.effectiveLeaseMs > before) {
            learning.recordCorrection(partition, after.effectiveLeaseMs)
        }
    }

    private fun reduce(event: AutoSleepEvent, actuate: Boolean = true): AutoSleepDecision? {
        val current = policy ?: return null
        if (event.atMs < (current.lastEventAtMs ?: 0L)) {
            Log.d(TAG, "clamped late auto-sleep ${event.javaClass.simpleName} event")
        }
        return runCatching { AutoSleepPolicyReducer.reduce(current, event) }
            .onFailure { Log.w(TAG, "rejected auto-sleep event ${event.javaClass.simpleName}", it) }
            .getOrNull()?.let { transition ->
                policy = transition.state
                acceptDecision(transition.decision, actuate)
                transition.decision
            }
    }

    private fun acceptDecision(next: AutoSleepDecision, shouldActuate: Boolean) {
        decision = next
        deadlineJob?.cancel()
        val token = ++deadlineToken
        next.nextDeadlineMs?.let { deadline ->
            deadlineJob = scope.launch(workerDispatcher) {
                delay((deadline - elapsedRealtime()).coerceAtLeast(1L))
                admit(Slot.DEADLINE, Deadline(maxOf(deadline, elapsedRealtime()), token))
            }
        }
        if (shouldActuate) actuate(next)
        publishProjection()
    }

    private fun actuate(next: AutoSleepDecision) {
        if (admission != Admission.OPEN || !configured.enabled || aggregate.phase != HaPresencePhase.LIVE) return
        when {
            next.output == AutoSleepOutput.ALLOW_SLEEP && !screen.isIntendedOff() && !manualSuppression -> {
                automaticEpoch = screen.sleepAutomatically()
                automaticEpoch?.let {
                    reduce(AutoSleepEvent.AutomaticSleepRecorded(eventTime()), actuate = false)
                    onScreenChanged(false)
                }
            }
            next.output != AutoSleepOutput.ALLOW_SLEEP && screen.isIntendedOff() && !usesPanelPresence -> {
                val epoch = automaticEpoch
                if (epoch == null) {
                    val generation = rearmOffGeneration
                    if (generation != null) {
                        val result = screen.wakeIfStillDark(generation) {
                            configured.enabled && aggregate.phase == HaPresencePhase.LIVE
                        }
                        rearmOffGeneration = null
                        manualSuppression = result != WakeOutcome.WOKEN && screen.isIntendedOff()
                        suppressionSawAllOff = false
                        if (result == WakeOutcome.WOKEN) onScreenChanged(true)
                    } else {
                        manualSuppression = true
                    }
                } else when (screen.wakeAutomaticallyIfOwned(epoch) {
                    configured.enabled && !manualSuppression && admission == Admission.OPEN
                }) {
                    WakeOutcome.WOKEN -> {
                        automaticEpoch = null
                        rearmOffGeneration = null
                        onScreenChanged(true)
                    }
                    WakeOutcome.STALE_GENERATION -> {
                        automaticEpoch = null
                        manualSuppression = screen.isIntendedOff()
                    }
                    WakeOutcome.ALREADY_ON -> {
                        automaticEpoch = null
                        manualSuppression = false
                    }
                    WakeOutcome.ACTUATION_FAILED -> Unit
                }
            }
            !screen.isIntendedOff() -> {
                automaticEpoch = null
                manualSuppression = false
                suppressionSawAllOff = false
                rearmOffGeneration = null
            }
        }
    }

    private fun wakeOwnedScreen(transient: Boolean = false) {
        val epoch = automaticEpoch ?: return
        if (transient && epoch == inheritedEpoch) return
        if (admission == Admission.OPEN && screen.wakeAutomaticallyIfOwned(epoch) == WakeOutcome.WOKEN) {
            ownWakePending = true
            onScreenChanged(true)
        }
        automaticEpoch = null
    }

    private fun publishProjection() {
        val current = decision
        val revision = projectionRevision.incrementAndGet()
        status = Status(
            available = configured.enabled && aggregate.phase == HaPresencePhase.LIVE && current != null &&
                current.output != AutoSleepOutput.INHIBITED,
            enabled = configured.enabled,
            source = configured.source,
            reason = if (aggregate.phase == HaPresencePhase.LIVE) {
                current?.reason?.name?.lowercase(Locale.ROOT) ?: "live"
            } else aggregate.phase.name.lowercase(Locale.ROOT),
            learnedLeaseMs = current?.effectiveLeaseMs,
            replayLeaseMs = policy?.let { it.fixedLeaseMs ?: maxOf(it.learnedLeaseMs, it.correctionFloorMs) }
                ?: aggregate.learnedLeaseMs.takeIf { aggregate.phase == HaPresencePhase.NO_INCLUDED_SOURCES },
            sourceCount = aggregate.selectedEntityIds.size,
            discoveredSourceCount = aggregate.discoveredEntityIds.size,
            areaName = aggregate.areaName,
            phase = if (usesPanelPresence && configured.enabled && current?.output == AutoSleepOutput.INHIBITED)
                "source_unavailable" else aggregate.phase.name.lowercase(Locale.ROOT),
            manualSuppression = manualSuppression,
            detail = if (usesPanelPresence && configured.enabled && current?.output == AutoSleepOutput.INHIBITED)
                "Calibrate panel proximity or wait for a trustworthy presence reading." else diagnosticDetail(aggregate),
            projectionRevision = revision,
        )
        runCatching(onProjectionChanged).onFailure {
            Log.w(TAG, "auto-sleep projection callback failed", it)
        }
    }

    private fun diagnosticDetail(next: HaPresenceAggregate): String = when (next.phase) {
        HaPresencePhase.DISCOVERY_FAILED,
        HaPresencePhase.NO_AREA,
        HaPresencePhase.NO_CREDIBLE_SOURCES,
        HaPresencePhase.NO_INCLUDED_SOURCES,
        HaPresencePhase.AUTH_FAILED,
        -> next.detail.take(64).ifBlank { next.phase.name.lowercase(Locale.ROOT) }
        else -> ""
    }

    private fun eventTime(candidate: Long = elapsedRealtime()): Long =
        maxOf(candidate, policy?.lastEventAtMs ?: 0L)

    private fun HaPresenceAggregate.positionAtLeast(other: HaPresenceAggregate): Boolean = when {
        controllerEpoch != other.controllerEpoch -> controllerEpoch > other.controllerEpoch
        managerGeneration != other.managerGeneration -> managerGeneration > other.managerGeneration
        feedGeneration != other.feedGeneration -> feedGeneration > other.feedGeneration
        else -> feedRevision >= other.feedRevision
    }

    private companion object {
        const val TAG = "AutoSleep"
        const val LOCAL_SOURCE = "panel_proximity"
        const val REDISCOVERY_INTERVAL_MS = 24L * 60L * 60_000L
        const val HOUR_MS = 60L * 60_000L
        const val HISTORY_WARMUP_MS = HOUR_MS
        const val MAX_SOURCE_LABEL_CHARS = 96
        const val MAX_SOURCE_CELLS = 12_000L
        val HISTORY_EXCLUSIONS = listOf(
            "past_touch",
            "panel_proximity",
            "manual_override_or_suppression",
            "screen_wake",
            "historical_learning_changes",
        )
    }

    private data class Status(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val source: String = "home_assistant",
        val phase: String = "disabled",
        val reason: String = "disabled",
        val learnedLeaseMs: Long? = null,
        val replayLeaseMs: Long? = null,
        val sourceCount: Int = 0,
        val discoveredSourceCount: Int = 0,
        val areaName: String = "",
        val manualSuppression: Boolean = false,
        val detail: String = "",
        val projectionRevision: Long = 0L,
    )
}

/** Gap between startup adoption attempts; two retries still land before the never-blank guard's 15 s first check. */
internal const val ADOPTION_RETRY_MS = 4_000L
