package io.panelassistant.android.sensors

import android.util.Log
import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.dashboard.EntityFilterProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class HaAmbientSampleOrigin { REST_INITIAL, WEBSOCKET }

data class HaAmbientLuxSample(
    val entityId: String,
    val lux: Double,
    val observedAtEpochMs: Long,
    val receivedAtEpochMs: Long,
    val origin: HaAmbientSampleOrigin,
)

data class HaAmbientLuxCandidate(
    val entityId: String,
    val friendlyName: String,
    val unit: String,
    val currentLux: Double?,
    val available: Boolean,
    val lastUpdatedEpochMs: Long?,
)

data class HaAmbientCandidateProjection(
    val items: List<HaAmbientLuxCandidate> = emptyList(),
    val refreshedAtEpochMs: Long = 0L,
    val error: String = "",
)

internal sealed interface HaAmbientSourceValidation {
    data class Ready(val authOwner: HaAuthOwner) : HaAmbientSourceValidation
    data class Rejected(val statusCode: Int, val error: String, val message: String) : HaAmbientSourceValidation
}

enum class HaAmbientSourcePhase {
    DISABLED,
    CONNECTING,
    AUTHENTICATING,
    SUBSCRIBING,
    SYNCHRONIZING,
    LIVE,
    SOURCE_MISSING,
    SOURCE_UNAVAILABLE,
    RECONNECTING,
    AUTH_FAILED,
    STOPPED,
}

data class HaAmbientSourceStatus(
    val entityId: String? = null,
    val phase: HaAmbientSourcePhase = HaAmbientSourcePhase.DISABLED,
    val detail: String = "",
    val lastSampleAtEpochMs: Long? = null,
    val reconnectAttempt: Int = 0,
)

/**
 * Adapts one exact Home Assistant entity stream into ambient-light samples while retaining bounded
 * candidate, validation and history reads. Socket/reconnect/liveness ownership lives exclusively in
 * [streamOwner]; lifecycle methods remain non-blocking and safe to call from Android's main thread.
 */
class HaAmbientLuxSubscriber internal constructor(
    private val scope: CoroutineScope,
    private val auth: HaApiSessionProvider,
    private val transport: HaAmbientTransport,
    private val streamOwner: HaExactEntityStreamOwner,
    private val onSample: (HaAmbientLuxSample) -> Unit,
    private val onStatus: (HaAmbientSourceStatus) -> Unit = {},
    private val onCandidates: (HaAmbientCandidateProjection) -> Unit = {},
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val epochMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val candidateGeneration = AtomicLong()
    private val lock = Any()
    @Volatile private var sourceEntityId: String? = null
    @Volatile private var stopped = false
    @Volatile private var candidateJob: Job? = null
    @Volatile private var status = HaAmbientSourceStatus()
    @Volatile private var candidates = HaAmbientCandidateProjection()
    @Volatile private var sampleGate: HaLuxSampleGate? = null
    private val streamObserver = object : HaExactEntityStreamObserver {
        override fun onStatus(status: HaExactEntityStreamStatus) = acceptStreamStatus(status)
        override fun onUpdate(update: HaExactEntityUpdate) = acceptStreamUpdate(update)
    }

    init {
        streamOwner.bindAmbient(streamObserver)
    }

    fun latestStatus(): HaAmbientSourceStatus = status

    fun latestCandidates(): HaAmbientCandidateProjection = candidates
    fun candidateRefreshInFlight(): Boolean = candidateJob?.isActive == true

    /** Blank/null disables the shared exact-entity stream generation. */
    fun setSource(entityId: String?) {
        val normalized = entityId?.trim()?.takeIf(String::isNotEmpty)?.also(::validateEntityId)
        synchronized(lock) {
            check(!stopped) { "subscriber is closed" }
            if (normalized != sourceEntityId) {
                sourceEntityId = normalized
                sampleGate = normalized?.let { expected ->
                    HaLuxSampleGate(expected, epochMillis, ::emitSample)
                }
            }
            streamOwner.replaceAmbientSource(normalized)
        }
    }

    /** Refreshes the bounded illuminance-candidate projection without disturbing a live source. */
    fun refreshCandidates(force: Boolean = false) {
        synchronized(lock) {
            check(!stopped) { "subscriber is closed" }
            if (candidateJob?.isActive == true) return
            val age = epochMillis() - candidates.refreshedAtEpochMs
            val ttl = if (candidates.error.isBlank()) CANDIDATE_TTL_MS else CANDIDATE_ERROR_TTL_MS
            if (!force && candidates.refreshedAtEpochMs > 0L && age in 0 until ttl) return
            val refresh = candidateGeneration.incrementAndGet()
            candidateJob = scope.launch {
                val projection = runCatching { discoverCandidates() }.fold(
                    onSuccess = { HaAmbientCandidateProjection(it, epochMillis()) },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        HaAmbientCandidateProjection(
                            items = candidates.items,
                            refreshedAtEpochMs = epochMillis(),
                            error = safeDetail(error, "Home Assistant candidate discovery failed"),
                        )
                    },
                )
                if (!stopped && candidateGeneration.get() == refresh) {
                    candidates = projection
                    safeCallback { onCandidates(projection) }
                }
            }
        }
    }

    fun invalidateCandidates() {
        synchronized(lock) {
            candidateGeneration.incrementAndGet()
            candidateJob?.cancel()
            candidateJob = null
            candidates = HaAmbientCandidateProjection()
        }
    }

    /**
     * Read one exact entity before a configuration save commits it as the active source. This shares
     * the renderer's refresh-safe credential owner, retries one rejected access token through that
     * owner, and never disturbs the currently subscribed source.
     */
    internal suspend fun validateSource(entityId: String): HaAmbientSourceValidation {
        validateEntityId(entityId)
        return try {
            val (state, owner) = authRetryOnce { session ->
                withContext(workerDispatcher) {
                    transport.state(session.baseUrl, checkNotNull(session.accessToken), entityId)
                } to session.owner
            }
            when {
                state == null -> HaAmbientSourceValidation.Rejected(
                    422,
                    "ha-source-missing",
                    "Home Assistant cannot find that entity. Check the entity id and try again.",
                )
                !HaAmbientLuxProtocol.hasUsableLux(state, entityId) -> HaAmbientSourceValidation.Rejected(
                    422,
                    "ha-source-unavailable",
                    "The selected entity is not currently reporting a numeric light level.",
                )
                owner == null -> HaAmbientSourceValidation.Rejected(
                    409,
                    "ha-source-validation-stale",
                    "Home Assistant settings changed during the check. Try again.",
                )
                else -> HaAmbientSourceValidation.Ready(owner)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HaAuthenticationException) {
            HaAmbientSourceValidation.Rejected(
                422,
                "ha-authentication-failed",
                "Home Assistant rejected the panel sign-in. Reconnect Home Assistant and try again.",
            )
        } catch (_: Throwable) {
            HaAmbientSourceValidation.Rejected(
                503,
                "ha-source-check-failed",
                "The panel could not read that entity from Home Assistant. Check the connection and try again.",
            )
        }
    }

    /** One bounded bootstrap read; callers run this independently of the live subscription. */
    internal suspend fun loadHistory(entityId: String): HaAmbientHistorySeed {
        validateEntityId(entityId)
        val endEpochMs = epochMillis() / HISTORY_MINUTE_MS * HISTORY_MINUTE_MS
        val startEpochMs = endEpochMs - HISTORY_WINDOW_MS
        val (minutes, baseUrl, ownerOrNull) = authRetryOnce { session ->
            Triple(
                loadHistoryChunks(session, entityId, startEpochMs, endEpochMs),
                session.baseUrl.trim().trimEnd('/'),
                session.owner,
            )
        }
        val owner = ownerOrNull ?: throw HaProtocolException("Home Assistant credentials changed during history retrieval")
        return HaAmbientHistorySeed(
            entityId = entityId,
            baseUrl = baseUrl,
            authOwner = owner,
            minutes = minutes,
        )
    }

    /**
     * Home Assistant can return several megabytes for a frequently changing illuminance sensor.
     * Keep every response independently bounded while retaining the exact seven-day window.
     */
    private suspend fun loadHistoryChunks(
        session: HaApiSession,
        entityId: String,
        startEpochMs: Long,
        endEpochMs: Long,
    ): List<HaAmbientHistoryMinute> = withContext(workerDispatcher) {
        val result = ArrayList<HaAmbientHistoryMinute>((endEpochMs - startEpochMs).div(HISTORY_MINUTE_MS).toInt())
        var chunkStart = startEpochMs
        while (chunkStart < endEpochMs) {
            val chunkEnd = minOf(endEpochMs, chunkStart + HISTORY_CHUNK_MS)
            val response = transport.history(
                session.baseUrl,
                checkNotNull(session.accessToken),
                entityId,
                chunkStart,
                chunkEnd,
            )
            result += HaAmbientHistoryProtocol.parse(response, entityId, chunkStart, chunkEnd)
            if (result.size > HISTORY_MAX_MINUTES) {
                throw HaProtocolException("Home Assistant history covers too many minutes")
            }
            chunkStart = chunkEnd
        }
        result
    }

    override fun close() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            candidateGeneration.incrementAndGet()
            candidateJob?.cancel()
            candidateJob = null
            sourceEntityId = null
            sampleGate = null
            streamOwner.unbindAmbient(streamObserver)
            streamOwner.replaceAmbientSource(null)
            val final = status.copy(
                entityId = null,
                phase = HaAmbientSourcePhase.STOPPED,
                detail = "",
                lastSampleAtEpochMs = null,
                reconnectAttempt = 0,
            )
            status = final
            safeCallback { onStatus(final) }
        }
    }

    private suspend fun discoverCandidates(): List<HaAmbientLuxCandidate> {
        val states = authRetryOnce { session ->
            transport.states(session.baseUrl, checkNotNull(session.accessToken))
        }
        return HaAmbientLuxProtocol.candidates(states)
    }

    /**
     * Shared exact-entity read failure policy: resolve the refresh-safe session once, run [op], and on
     * a single rejected access token force one refreshed session through the same owner and retry. A
     * second rejection propagates so callers can classify it.
     */
    private suspend fun <T> authRetryOnce(op: suspend (HaApiSession) -> T): T {
        val session = resolveSession(force = false)
        return try {
            op(session)
        } catch (_: HaAuthenticationException) {
            op(resolveSession(force = true))
        }
    }

    private suspend fun resolveSession(force: Boolean): HaApiSession = withContext(workerDispatcher) {
        val session = auth.resolve(force)
        when {
            session.rejected -> throw HaAuthenticationException("Home Assistant rejected the configured refresh credentials")
            session.baseUrl.isBlank() -> throw HaProtocolException("Home Assistant URL is not configured")
            session.accessToken.isNullOrBlank() -> throw HaAuthenticationException("Home Assistant access credentials are unavailable")
            else -> session
        }
    }

    /**
     * The subscriber is the single availability authority for the HA lux source. A usable sample means
     * the source is LIVE, so publish that status before delivering the sample: a consumer can never
     * observe a sample before the source has been reported available (status-before-sample ordering).
     */
    private fun emitSample(sample: HaAmbientLuxSample) {
        if (stopped || sample.entityId != sourceEntityId) return
        val live = statusFor(sample.entityId, HaAmbientSourcePhase.LIVE)
            .copy(lastSampleAtEpochMs = sample.receivedAtEpochMs)
        status = live
        safeCallback { onStatus(live) }
        safeCallback { onSample(sample) }
    }

    private fun publishStatus(next: HaAmbientSourceStatus) {
        if (stopped || next.entityId != null && next.entityId != sourceEntityId) return
        val withLastSample = next.copy(
            lastSampleAtEpochMs = if (status.entityId == next.entityId) status.lastSampleAtEpochMs
            else next.lastSampleAtEpochMs,
        )
        status = withLastSample
        safeCallback { onStatus(withLastSample) }
    }

    private fun acceptStreamStatus(next: HaExactEntityStreamStatus) {

        val phase = when (next.phase) {
            HaExactEntityStreamPhase.DISABLED -> HaAmbientSourcePhase.DISABLED
            HaExactEntityStreamPhase.AUTHENTICATING -> HaAmbientSourcePhase.AUTHENTICATING
            HaExactEntityStreamPhase.CONNECTING -> HaAmbientSourcePhase.CONNECTING
            HaExactEntityStreamPhase.SUBSCRIBING -> HaAmbientSourcePhase.SUBSCRIBING
            HaExactEntityStreamPhase.SYNCHRONIZING -> {
                next.entityId?.let { expected -> sampleGate = HaLuxSampleGate(expected, epochMillis, ::emitSample) }
                HaAmbientSourcePhase.SYNCHRONIZING
            }
            HaExactEntityStreamPhase.LIVE -> {
                if (status.entityId == next.entityId &&
                    (status.phase == HaAmbientSourcePhase.LIVE ||
                        status.phase == HaAmbientSourcePhase.SOURCE_MISSING ||
                        status.phase == HaAmbientSourcePhase.SOURCE_UNAVAILABLE)
                ) return
                HaAmbientSourcePhase.LIVE
            }
            HaExactEntityStreamPhase.AUTH_FAILED -> HaAmbientSourcePhase.AUTH_FAILED
            HaExactEntityStreamPhase.RECONNECTING -> HaAmbientSourcePhase.RECONNECTING
            HaExactEntityStreamPhase.STOPPED -> HaAmbientSourcePhase.STOPPED
        }
        publishStatus(statusFor(next.entityId.orEmpty(), phase, next.detail, next.reconnectAttempt).copy(
            entityId = next.entityId,
        ))
    }

    private fun acceptStreamUpdate(update: HaExactEntityUpdate) {
        if (stopped || update.entityId != sourceEntityId) return
        when (update) {
            is HaExactEntityUpdate.State -> {
                val gate = sampleGate ?: return
                val origin = if (update.initial) HaAmbientSampleOrigin.REST_INITIAL else HaAmbientSampleOrigin.WEBSOCKET
                // An accepted sample publishes LIVE via emitSample (status-before-sample). Only an
                // update that carries no usable lux flips the source to unavailable here.
                if (!gate.accept(update.json, origin) &&
                    !HaAmbientLuxProtocol.hasUsableLux(update.json, update.entityId)
                ) {
                    publishStatus(statusFor(
                        update.entityId,
                        HaAmbientSourcePhase.SOURCE_UNAVAILABLE,
                        "Entity does not currently have a numeric lux value",
                    ))
                }
            }
            is HaExactEntityUpdate.Missing -> publishStatus(statusFor(
                update.entityId,
                HaAmbientSourcePhase.SOURCE_MISSING,
                "Entity is not currently available",
            ))
        }
    }

    private fun statusFor(
        entityId: String,
        phase: HaAmbientSourcePhase,
        detail: String = "",
        attempt: Int = 0,
    ) = HaAmbientSourceStatus(
        entityId,
        phase,
        detail.take(MAX_DETAIL_CHARS),
        status.lastSampleAtEpochMs.takeIf { status.entityId == entityId },
        attempt,
    )

    private fun safeDetail(error: Throwable, fallback: String): String =
        error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(MAX_DETAIL_CHARS)
            ?.takeIf(String::isNotBlank) ?: fallback

    private inline fun safeCallback(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "HA ambient callback failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "HaAmbientLux"
        const val MAX_DETAIL_CHARS = 240
        const val CANDIDATE_TTL_MS = 10L * 60_000L
        const val CANDIDATE_ERROR_TTL_MS = 60_000L
        const val HISTORY_MINUTE_MS = 60_000L
        const val HISTORY_WINDOW_MS = 7L * 24L * 60L * HISTORY_MINUTE_MS
        const val HISTORY_CHUNK_MS = 12L * 60L * HISTORY_MINUTE_MS
        const val HISTORY_MAX_MINUTES = (HISTORY_WINDOW_MS / HISTORY_MINUTE_MS).toInt()
    }
}

internal object HaAmbientLuxProtocol {
    private val LUX_UNITS = setOf("lx", "lux")

    fun candidates(states: JSONArray): List<HaAmbientLuxCandidate> {
        val projected = ArrayList<HaAmbientLuxCandidate>()
        val limit = states.length().coerceAtMost(MAX_CANDIDATE_ROWS)
        for (index in 0 until limit) {
            val state = states.optJSONObject(index) ?: continue
            val entityId = state.optString("entity_id")
            if (!entityId.startsWith("sensor.") || !isValidEntityId(entityId)) continue
            val attributes = state.optJSONObject("attributes") ?: JSONObject()
            val unit = attributes.optString("unit_of_measurement").trim()
            if (!isIlluminance(state)) continue
            val lux = finiteNonNegative(state.optString("state"))
            projected += HaAmbientLuxCandidate(
                entityId = entityId,
                friendlyName = attributes.optString("friendly_name").trim().take(MAX_FRIENDLY_NAME_CHARS)
                    .ifBlank { entityId },
                unit = unit.ifBlank { "lx" }.take(MAX_UNIT_CHARS),
                currentLux = lux,
                available = lux != null,
                lastUpdatedEpochMs = timestamp(state),
            )
        }
        return projected.distinctBy(HaAmbientLuxCandidate::entityId)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, HaAmbientLuxCandidate::friendlyName)
                .thenBy(HaAmbientLuxCandidate::entityId))
    }

    fun sample(state: JSONObject, expectedEntityId: String): Pair<Double, Long>? {
        if (state.optString("entity_id") != expectedEntityId) return null
        if (!isIlluminance(state)) return null
        val lux = finiteNonNegative(state.optString("state")) ?: return null
        return lux to (timestamp(state) ?: return null)
    }

    fun hasUsableLux(state: JSONObject, expectedEntityId: String): Boolean =
        state.optString("entity_id") == expectedEntityId && isIlluminance(state) &&
            finiteNonNegative(state.optString("state")) != null

    private fun isIlluminance(state: JSONObject): Boolean {
        val attributes = state.optJSONObject("attributes") ?: return false
        val unit = attributes.optString("unit_of_measurement").trim().lowercase(Locale.ROOT)
        val deviceClass = attributes.optString("device_class").trim().lowercase(Locale.ROOT)
        return deviceClass == "illuminance" || unit in LUX_UNITS
    }

    private fun timestamp(state: JSONObject): Long? {
        val raw = state.optString("last_updated").ifBlank { state.optString("last_changed") }
        return parseHaTimestampEpochMs(raw)
    }

    private fun finiteNonNegative(raw: String): Double? =
        raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_LUX }

    private const val MAX_CANDIDATE_ROWS = 100_000
    private const val MAX_FRIENDLY_NAME_CHARS = 256
    private const val MAX_UNIT_CHARS = 32
    private const val MAX_LUX = 10_000_000.0
}

internal class HaLuxSampleGate(
    private val entityId: String,
    private val epochMillis: () -> Long,
    private val emit: (HaAmbientLuxSample) -> Unit,
) {
    private var newestObservedAt = Long.MIN_VALUE
    private var newestLux: Double? = null

    /** Timestamp ordering makes subscribe-first + REST hydration race-safe and suppresses duplicates. */
    fun accept(state: JSONObject, origin: HaAmbientSampleOrigin): Boolean {
        val (lux, observedAt) = HaAmbientLuxProtocol.sample(state, entityId) ?: return false
        if (observedAt < newestObservedAt || observedAt == newestObservedAt && lux == newestLux) return false
        newestObservedAt = max(newestObservedAt, observedAt)
        newestLux = lux
        emit(HaAmbientLuxSample(entityId, lux, observedAt, epochMillis(), origin))
        return true
    }
}

internal fun validateEntityId(entityId: String) {
    require(entityId.length <= 255 && EntityFilterProtocol.normalize(listOf(entityId)).single() == entityId) {
        "invalid Home Assistant entity_id"
    }
}

private fun isValidEntityId(entityId: String): Boolean =
    runCatching { validateEntityId(entityId) }.isSuccess
