package io.github.maxlyth.hapaneld.panelassistant

import android.util.Log
import io.github.maxlyth.hapaneld.mqtt.StateConverger
import io.github.maxlyth.hapaneld.mqtt.StateSink
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native state reporting (protocol sections 8 and 10): every accepted observation is reported over the
 * native transport independently of MQTT delivery, so the integration can compare values and freshness.
 *
 * This keeps its own per-channel state: the latest
 * observation, whether it still needs sending, the request carrying it, and the revision the integration
 * acknowledged or rejected. Channel state is keyed by wire channel; the two attribute channels are folded
 * into their parents.
 *
 * Two sides share it. [bind] hands the live MQTT bridge a [StateSink] whose invocation only records the
 * observation and wakes the session: it never translates, blocks or throws into the convergence pump.
 * Everything else is called by the transport owner's single session coroutine, which also owns every send,
 * so message ids stay strictly increasing in send order.
 */
internal class PanelAssistantShadowReporter(
    private val describe: (String) -> PanelAssistantChannelDescriptor? = PanelAssistantChannelCatalog::describe,
    private val retryDelayMs: Long = 5_000L,
    private val resultTimeoutMs: Long = 15_000L,
    private val log: (String) -> Unit = { message -> Log.i(PanelAssistantTransportOwner.TAG, message) },
) {
    /** Conflated wake-up for the session coroutine; a spare wake-up costs one loop iteration. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    private val lock = Any()
    private var generation = 0L
    private var source: () -> PanelAssistantChannelShape = { PanelAssistantChannelShape(emptyList()) }
    private val entries = HashMap<String, Entry>()
    private val outstanding = LinkedHashMap<Long, Batch>()
    private var phase = Phase.IDLE
    private var described: Set<String> = emptySet()
    private var retryPhaseAt = 0L

    /** Totals for this process, for status and tests. */
    @Volatile var untranslatable = 0L
        private set

    @Volatile var rejections = 0L
        private set

    private class Entry {
        var observation: StateConverger.Observation.Reportable? = null
        var attributes: StateConverger.Observation.Reportable? = null
        /** Advances whenever the observation or its attributes change; a repeat is a refresh. */
        var revision = 0L
        var dirty = false
        var inFlight = false
        var retryAt = 0L
        var acknowledged: Long? = null
        var rejected: Long? = null
        /** The revision last found untranslatable, so a refresh of it is neither counted nor logged. */
        var skipped: Long? = null
    }

    private class Batch(val sync: String, val channels: Map<String, Long>, val deadline: Long)

    private enum class Phase { IDLE, FULL_BEGIN, BEGIN_SENT, FULL_END, END_SENT, DELTA }

    /** [bindShape] for a bridge that states nothing unsupported. */
    fun bind(channels: () -> Collection<String>): StateSink = bindShape { PanelAssistantChannelShape(channels()) }

    /** Points reporting at a new bridge generation; observations from any earlier generation are ignored. */
    fun bindShape(shape: () -> PanelAssistantChannelShape): StateSink {
        val bound = synchronized(lock) {
            source = shape
            ++generation
        }
        wake.trySend(Unit)
        return { channel, observation, _ -> record(bound, channel, observation) }
    }

    /**
     * The only work on the convergence pump: constant-time bookkeeping under a lock no caller holds for
     * translation or I/O. Nothing here throws into observation delivery.
     */
    private fun record(bound: Long, channel: String, observation: StateConverger.Observation.Reportable) {
        synchronized(lock) {
            // A retired bridge generation can still deliver a late observation; it never overwrites the live one.
            if (bound != generation) return
            val parent = PanelAssistantChannelCatalog.FOLDED[channel]
            val wire = parent ?: PanelAssistantChannelCatalog.wireChannel(channel) ?: return
            val entry = entries.getOrPut(wire) { Entry() }
            val changed = if (parent != null) {
                if (entry.attributes == observation) return
                entry.attributes = observation
                // The parent is re-reported with its new attributes once it has an observation.
                if (entry.observation == null) return
                true
            } else {
                (entry.observation != observation).also { entry.observation = observation }
            }
            // A new value is eligible at once; a repeat keeps any retry delay a failure set.
            if (changed) {
                entry.revision++
                entry.retryAt = 0L
            }
            entry.dirty = true
        }
        wake.trySend(Unit)
    }

    /** The wire channels the bound bridge currently serves and this build can describe. */
    private fun describable(): Map<String, PanelAssistantChannelDescriptor> =
        describable(synchronized(lock) { source }.invoke().served)

    private fun describable(keys: Collection<String>): Map<String, PanelAssistantChannelDescriptor> =
        keys.mapNotNull(PanelAssistantChannelCatalog::wireChannel).distinct().sorted()
            .mapNotNull { wire -> describe(wire)?.let { wire to it } }
            .toMap()

    /** Descriptors for a `hello`; [open] later receives the same set. */
    fun descriptors(): List<PanelAssistantChannelDescriptor> = describable().values.toList()

    /**
     * The `hello` offer, read from one shape: the descriptors, and the describable wire channels the bridge
     * states it cannot serve. A channel in neither list is merely omitted, which changes nothing.
     */
    fun offer(): PanelAssistantHelloOffer {
        val shape = synchronized(lock) { source }.invoke()
        val descriptors = describable(shape.served)
        return PanelAssistantHelloOffer(descriptors.values.toList(), describable(shape.unsupported).keys.toList())
    }

    /** Start reporting on an accepted shadow session described by [channels]. */
    fun open(channels: Collection<PanelAssistantChannelDescriptor>) = synchronized(lock) {
        described = channels.mapTo(HashSet()) { it.channel }
        outstanding.clear()
        entries.values.forEach {
            it.inFlight = false
            it.dirty = true
            it.retryAt = 0L
            it.acknowledged = null
            it.rejected = null
        }
        phase = Phase.FULL_BEGIN
        retryPhaseAt = 0L
    }

    /** Stop reporting; late results for this session are ignored. */
    fun close() = synchronized(lock) {
        phase = Phase.IDLE
        outstanding.clear()
        entries.values.forEach { it.inFlight = false }
    }

    /** True once the integration has acknowledged this session's `full_end`, so every native entity is available. */
    fun fullSyncComplete(): Boolean = synchronized(lock) { phase == Phase.DELTA }

    /** True when the bridge now serves a different channel set than the session described. */
    fun descriptorsChanged(): Boolean {
        val current = describable().keys
        return synchronized(lock) { phase != Phase.IDLE && current != described }
    }

    private class Candidate(
        val wire: String,
        val descriptor: PanelAssistantChannelDescriptor,
        val observation: StateConverger.Observation.Reportable,
        val attributes: StateConverger.Observation.Reportable?,
        val revision: Long,
        val refresh: Boolean,
    )

    /**
     * The next `report_state` to send as message [id], or null when nothing may be sent now. The caller
     * sends the returned text immediately, before allocating any other id. Translation runs outside the
     * lock the sink takes, so a large batch never holds up the convergence pump.
     */
    fun next(id: Long, session: String, now: Long): String? {
        val descriptors = describable()
        val (sync, candidates) = synchronized(lock) { plan(now, descriptors) } ?: return null
        val translated = candidates.map { candidate ->
            candidate to PanelAssistantValueTranslation.translate(candidate.descriptor, candidate.observation)
        }
        return synchronized(lock) { commit(id, session, now, sync, translated) }
    }

    private fun plan(
        now: Long,
        descriptors: Map<String, PanelAssistantChannelDescriptor>,
    ): Pair<String, List<Candidate>>? {
        if (outstanding.size >= MAX_OUTSTANDING) return null
        val sync = when (phase) {
            Phase.FULL_BEGIN -> PanelAssistantTransportProtocol.SYNC_FULL_BEGIN
            Phase.FULL_END -> PanelAssistantTransportProtocol.SYNC_FULL_END
            Phase.DELTA -> PanelAssistantTransportProtocol.SYNC_DELTA
            Phase.IDLE, Phase.BEGIN_SENT, Phase.END_SENT -> return null
        }
        if (phase != Phase.DELTA && now < retryPhaseAt) return null
        val candidates = mutableListOf<Candidate>()
        if (phase == Phase.FULL_END) return sync to candidates
        for ((wire, entry) in entries.entries.sortedBy { it.key }) {
            if (phase == Phase.DELTA && candidates.size >= MAX_DELTA) break
            if (!entry.dirty || entry.inFlight || now < entry.retryAt || wire !in described) continue
            val descriptor = descriptors[wire] ?: continue
            val observation = entry.observation ?: continue
            if (entry.rejected == entry.revision || entry.skipped == entry.revision) {
                entry.dirty = false
                continue
            }
            candidates += Candidate(wire, descriptor, observation, entry.attributes, entry.revision, entry.acknowledged == entry.revision)
        }
        if (phase == Phase.DELTA && candidates.isEmpty()) return null
        return sync to candidates
    }

    private fun commit(
        id: Long,
        session: String,
        now: Long,
        sync: String,
        translated: List<Pair<Candidate, PanelAssistantWireValue?>>,
    ): String? {
        val observations = JSONArray()
        val carried = LinkedHashMap<String, Long>()
        for ((candidate, value) in translated) {
            val entry = entries[candidate.wire] ?: continue
            // A newer value recorded meanwhile keeps the channel dirty for the next request.
            if (entry.revision == candidate.revision) entry.dirty = false
            if (value == null) {
                entry.skipped = candidate.revision
                untranslatable++
                log("native transport shadow skipped untranslatable observation channel=${candidate.wire}")
                continue
            }
            observations.put(observationJson(candidate, value))
            carried[candidate.wire] = candidate.revision
            entry.inFlight = true
        }
        if (sync == PanelAssistantTransportProtocol.SYNC_DELTA && carried.isEmpty()) return null
        outstanding[id] = Batch(sync, carried, now + resultTimeoutMs)
        phase = when (phase) {
            Phase.FULL_BEGIN -> Phase.BEGIN_SENT
            Phase.FULL_END -> Phase.END_SENT
            else -> phase
        }
        if (sync != PanelAssistantTransportProtocol.SYNC_DELTA) {
            log("native transport shadow report_state $sync id=$id observations=${carried.size}")
        }
        return PanelAssistantTransportProtocol.reportState(id, session, sync, observations)
    }

    private fun observationJson(candidate: Candidate, value: PanelAssistantWireValue): JSONObject {
        val json = JSONObject().put("channel", candidate.wire)
        when (value) {
            PanelAssistantWireValue.Unavailable -> json.put("state", PanelAssistantTransportProtocol.STATE_UNAVAILABLE)
            is PanelAssistantWireValue.Known -> {
                json.put("state", PanelAssistantTransportProtocol.STATE_KNOWN).put("value", value.value)
                PanelAssistantValueTranslation.attributes(candidate.attributes)?.let { json.put("attributes", it) }
                if (candidate.refresh) json.put("refresh", true)
            }
        }
        return json
    }

    /** Apply the result answering request [id]; a result for no outstanding request is ignored. */
    fun onResult(result: PanelAssistantReportResult, now: Long) = synchronized(lock) {
        val batch = outstanding.remove(result.id) ?: return@synchronized
        when (result) {
            is PanelAssistantReportResult.Acknowledged -> {
                for ((wire, revision) in batch.channels) {
                    val entry = entries[wire] ?: continue
                    entry.inFlight = false
                    val code = result.rejected[wire]
                    if (code != null) {
                        entry.rejected = revision
                        rejections++
                        log("native transport shadow observation rejected channel=$wire code=$code")
                    } else {
                        entry.acknowledged = revision
                    }
                }
                phase = when {
                    batch.sync == PanelAssistantTransportProtocol.SYNC_FULL_BEGIN && phase == Phase.BEGIN_SENT -> Phase.FULL_END
                    batch.sync == PanelAssistantTransportProtocol.SYNC_FULL_END && phase == Phase.END_SENT -> {
                        log("native transport shadow full sync complete channels=${described.size}")
                        Phase.DELTA
                    }
                    else -> phase
                }
            }
            is PanelAssistantReportResult.Failed -> failLocked(batch, now, "error ${result.code}", result.id)
        }
    }

    /** Expire requests unanswered past their deadline, as transient failures. */
    fun expire(now: Long) = synchronized(lock) {
        val expired = outstanding.entries.filter { now >= it.value.deadline }
        for ((id, batch) in expired) {
            outstanding.remove(id)
            failLocked(batch, now, "timed out", id)
        }
    }

    private fun failLocked(batch: Batch, now: Long, why: String, id: Long) {
        log("native transport shadow report_state id=$id ${batch.sync} failed: $why")
        for (wire in batch.channels.keys) {
            val entry = entries[wire] ?: continue
            entry.inFlight = false
            entry.dirty = true
            entry.retryAt = now + retryDelayMs
        }
        when {
            batch.sync == PanelAssistantTransportProtocol.SYNC_FULL_BEGIN && phase == Phase.BEGIN_SENT -> {
                phase = Phase.FULL_BEGIN
                retryPhaseAt = now + retryDelayMs
            }
            batch.sync == PanelAssistantTransportProtocol.SYNC_FULL_END && phase == Phase.END_SENT -> {
                phase = Phase.FULL_END
                retryPhaseAt = now + retryDelayMs
            }
        }
    }

    /**
     * The earliest future time something becomes due without a new observation or frame, or null. Only
     * future times count: work already due but blocked waits for the result that unblocks it.
     */
    fun nextDeadline(now: Long): Long? = synchronized(lock) {
        val deadlines = outstanding.values.map { it.deadline } +
            entries.values.filter { it.dirty && !it.inFlight }.map { it.retryAt } +
            listOfNotNull(retryPhaseAt.takeIf { phase == Phase.FULL_BEGIN || phase == Phase.FULL_END })
        deadlines.filter { it > now }.minOrNull()
    }

    companion object {
        /** Protocol section 8: at most four outstanding `report_state` requests. */
        const val MAX_OUTSTANDING = 4
        private const val MAX_DELTA = 64
    }
}

/**
 * The bridge's converger channels, split by whether the panel can fill them. [served] is described;
 * [unsupported] holds only channels the panel has settled it cannot fill, never one it is still unsure of.
 */
internal data class PanelAssistantChannelShape(
    val served: Collection<String>,
    val unsupported: Collection<String> = emptyList(),
)

/** What a `hello` offers: descriptors, and the wire channels stated unsupported. */
internal data class PanelAssistantHelloOffer(
    val descriptors: List<PanelAssistantChannelDescriptor>,
    val unsupported: List<String>,
)
