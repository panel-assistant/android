package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Bounded, process-local dashboard diagnostics. The browser sends one fixed-shape aggregate every five
 * seconds; no event payload, entity id, DOM target, or script URL crosses the bridge.
 */
object DashboardTelemetry {
    /** The likely-cause code for a measured degraded path; one name shared with its state owner. */
    private const val NETWORK_PATH_CAUSE = io.panelassistant.android.sensors.HaNetworkPathPresentation.LIKELY_CAUSE
    private const val MAX_BUCKETS = 48
    private const val INTERACTION_BINS = 10
    private val lock = Any()
    private val buckets = ArrayDeque<Bucket>(MAX_BUCKETS)
    private var generation = 0L
    private var installed = false
    private var latestSampleAtElapsedMs = 0L
    @Volatile private var historySink: DashboardPerformanceHistorySink? = null

    internal fun setHistorySink(sink: DashboardPerformanceHistorySink?) {
        historySink = sink
    }

    fun reset(installed: Boolean = false) = synchronized(lock) {
        generation++
        this.installed = installed
        buckets.clear()
        latestSampleAtElapsedMs = 0L
    }

    fun installed() = synchronized(lock) {
        if (!installed) {
            generation++
            installed = true
            buckets.clear()
        }
    }

    fun record(
        batch: EntityFilterProtocol.TrafficBatch,
        observedAtElapsedMs: Long = monotonicElapsedMs(),
    ) {
        val accepted = synchronized(lock) {
            if (!installed || batch.sampleMs <= 0L) false else {
                if (buckets.size == MAX_BUCKETS) buckets.removeFirst()
                buckets.addLast(Bucket(batch))
                latestSampleAtElapsedMs = observedAtElapsedMs.coerceAtLeast(0L)
                true
            }
        }
        if (accepted) historySink?.record(batch)
    }

    fun json(
        builtinActive: Boolean,
        filterActive: Boolean,
        entityCount: Int,
        reloads24h: Int,
        systemCpuPct: Int? = null,
        rendererMainPct: Double? = null,
        topEntities: JSONArray = JSONArray(),
        nowElapsedMs: Long = monotonicElapsedMs(),
        /**
         * The measured Home Assistant network-path verdict (`warning` / `severe`), or null when the
         * path is healthy or not measured. Read from the one state owner by the caller so this
         * classifier stays pure.
         */
        networkPath: String? = null,
    ): String = synchronized(lock) {
        val values = buckets.toList()
        val latest = values.lastOrNull()
        val updateRates = values.map { it.rate(it.updates) }
        val payloadRates = values.map { it.rate(it.payloadBytes) }
        val occupancy = values.map { it.microsPerSecond(it.stateTaskMicros) }
        val blocked = values.map { it.microsPerSecond(it.blockingMicros) }
        val interactionBins = LongArray(INTERACTION_BINS)
        values.forEach { bucket ->
            bucket.interactionBins.forEachIndexed { index, count ->
                interactionBins[index] = saturatedAdd(interactionBins[index], count)
            }
        }
        val interactionCount = interactionBins.fold(0L, ::saturatedAdd)
        val slowest = values.maxByOrNull { it.interactionMaxMicros }
        val interactionP50 = histogramPercentile(interactionBins, 0.50)
        val interactionP95 = histogramPercentile(interactionBins, 0.95)
        val mode = when {
            builtinActive && installed -> "builtin_direct"
            builtinActive -> "builtin_unavailable"
            else -> "foreign_proxy"
        }
        val cause = classify(
            interactionP95 = interactionP95,
            updateP95 = percentile(updateRates, 0.95),
            payloadP95 = percentile(payloadRates, 0.95),
            occupancyP95 = percentile(occupancy, 0.95),
            blockedP95 = percentile(blocked, 0.95),
            slowest = slowest,
            reloads24h = reloads24h,
            systemCpuPct = systemCpuPct,
            rendererMainPct = rendererMainPct.takeIf { !builtinActive },
            networkPath = networkPath,
        )
        JSONObject()
            .put("schema", 1)
            .put("mode", mode)
            .put("generation", generation)
            .put("windowMs", values.sumOf { it.sampleMs })
            .put("sampleCount", values.size)
            .put(
                "latestSampleAgeMs",
                if (latestSampleAtElapsedMs <= 0L) -1L
                else (nowElapsedMs - latestSampleAtElapsedMs).coerceAtLeast(0L),
            )
            .put("interaction", JSONObject()
                .put("count", interactionCount)
                .put("percentilesApproximate", true)
                .put("p50Ms", interactionP50)
                .put("p95Ms", interactionP95)
                .put("worstMs", microsToMs(slowest?.interactionMaxMicros ?: 0L))
                .put("inputDelayMs", microsToMs(slowest?.inputDelayMicros ?: 0L))
                .put("processingMs", microsToMs(slowest?.interactionProcessingMicros ?: 0L))
                .put("presentationMs", microsToMs(slowest?.presentationMicros ?: 0L)))
            .put("stateStream", JSONObject()
                .put("framesPerSec", latest?.rate(latest.frames) ?: 0.0)
                .put("updatesPerSec", latest?.rate(latest.updates) ?: 0.0)
                .put("updatesP95PerSec", percentile(updateRates, 0.95))
                .put("payloadBytesPerSec", latest?.rate(latest.payloadBytes) ?: 0.0)
                .put("payloadP95BytesPerSec", percentile(payloadRates, 0.95))
                .put("hydrationUpdates", values.sumOf { it.hydrationUpdates })
                .put("mainThreadMsPerSec", latest?.microsPerSecond(latest.stateTaskMicros) ?: 0.0)
                .put("mainThreadP95MsPerSec", percentile(occupancy, 0.95))
                .put("longestStateTaskMs", microsToMs(values.maxOfOrNull { it.stateTaskMaxMicros } ?: 0L))
                .put("observerMicros", values.sumOf { it.observerMicros })
                .put("droppedFrames", values.sumOf { it.droppedFrames }))
            .put("blocking", JSONObject()
                .put("blockedMsPerSec", latest?.microsPerSecond(latest.blockingMicros) ?: 0.0)
                .put("blockedP95MsPerSec", percentile(blocked, 0.95))
                .put("longestFrameMs", microsToMs(values.maxOfOrNull { it.loafMaxMicros } ?: 0L))
                .put("scriptMsPerSec", latest?.microsPerSecond(latest.scriptMicros) ?: 0.0)
                .put("renderMsPerSec", latest?.microsPerSecond(latest.renderMicros) ?: 0.0)
                .put("longAnimationFrames", values.sumOf { it.loafCount })
                .put("longTasks", values.sumOf { it.longTaskCount }))
            .put("filter", JSONObject().put("active", filterActive).put("entityCount", entityCount))
            .put("topEntities", topEntities)
            .put("likelyCause", cause.first)
            .put("confidence", cause.second)
            .put("history", JSONObject()
                .put("interactionMs", JSONArray(values.map { microsToMs(it.interactionMaxMicros) }))
                .put("updatesPerSec", JSONArray(updateRates))
                .put("payloadBytesPerSec", JSONArray(payloadRates))
                .put("mainThreadMsPerSec", JSONArray(occupancy))
                .put("blockedMsPerSec", JSONArray(blocked)))
            .toString()
    }

    private fun classify(
        interactionP95: Double,
        updateP95: Double,
        payloadP95: Double,
        occupancyP95: Double,
        blockedP95: Double,
        slowest: Bucket?,
        reloads24h: Int,
        systemCpuPct: Int?,
        rendererMainPct: Double?,
        networkPath: String? = null,
    ): Pair<String, String> {
        // A measured lossy path to Home Assistant is named BEFORE any in-panel burden: every
        // server-backed action waits on that path, so entity filtering, card JavaScript and panel CPU
        // are not to be blamed while the round trip itself is the bottleneck.
        when (networkPath) {
            "severe" -> return NETWORK_PATH_CAUSE to "high"
            "warning" -> return NETWORK_PATH_CAUSE to "medium"
        }
        if (interactionP95 >= 200.0 && occupancyP95 >= 100.0 &&
            (updateP95 >= 50.0 || payloadP95 >= 64.0 * 1024.0)
        ) return "state_stream" to if (occupancyP95 >= 250.0) "high" else "medium"
        if (interactionP95 >= 200.0 && slowest != null) {
            if (slowest.presentationMicros > slowest.inputDelayMicros + slowest.interactionProcessingMicros &&
                slowest.renderMicros >= slowest.scriptMicros
            ) return "rendering_or_media" to "medium"
            if (slowest.interactionProcessingMicros >= slowest.presentationMicros &&
                slowest.interactionProcessingMicros >= slowest.inputDelayMicros
            ) return "dashboard_script" to "medium"
        }
        if ((systemCpuPct ?: 0) >= 85 && blockedP95 < 100.0 && occupancyP95 < 100.0) {
            return "system_contention" to "medium"
        }
        if ((rendererMainPct ?: 0.0) >= 85.0 && interactionP95 <= 0.0) {
            return "dashboard_or_state_proxy" to "low"
        }
        // A reload is retained for diagnosis, but it is historical evidence spanning 24 hours. Let
        // decisive evidence from the current live window identify the active burden first.
        if (reloads24h > 0) {
            return "memory_or_renderer_instability" to if (reloads24h > 2) "high" else "medium"
        }
        return "no_clear_dominant_cause" to if (interactionP95 > 0.0 && interactionP95 < 200.0) "medium" else "low"
    }

    private fun histogramPercentile(bins: LongArray, fraction: Double): Double {
        val total = bins.sum()
        if (total <= 0L) return 0.0
        val target = ceil(total * fraction).toLong().coerceAtLeast(1L)
        var seen = 0L
        bins.forEachIndexed { index, count ->
            seen += count
            if (seen >= target) return INTERACTION_UPPER_MS[index]
        }
        return INTERACTION_UPPER_MS.last()
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    private fun microsToMs(value: Long): Double = trafficMicrosToMs(value)

    private fun monotonicElapsedMs(): Long = System.nanoTime() / 1_000_000L

    private data class Bucket(
        val sampleMs: Long,
        val frames: Long,
        val payloadBytes: Long,
        val updates: Long,
        val hydrationUpdates: Long,
        val observerMicros: Long,
        val droppedFrames: Long,
        val stateTaskMicros: Long,
        val stateTaskMaxMicros: Long,
        val interactionBins: LongArray,
        val interactionMaxMicros: Long,
        val inputDelayMicros: Long,
        val interactionProcessingMicros: Long,
        val presentationMicros: Long,
        val loafCount: Long,
        val blockingMicros: Long,
        val loafMaxMicros: Long,
        val scriptMicros: Long,
        val renderMicros: Long,
        val longTaskCount: Long,
    ) {
        constructor(batch: EntityFilterProtocol.TrafficBatch) : this(
            batch.sampleMs, batch.frames, batch.payloadBytes, batch.entityUpdates,
            batch.hydrationUpdates, batch.observerMicros, batch.droppedFrames,
            batch.stateTaskMicros, batch.stateTaskMaxMicros, batch.interactionBins.copyOf(),
            batch.interactionMaxMicros, batch.inputDelayMicros, batch.interactionProcessingMicros,
            batch.presentationMicros, batch.loafCount, batch.blockingMicros, batch.loafMaxMicros,
            batch.scriptMicros, batch.renderMicros, batch.longTaskCount,
        )

        fun rate(value: Long): Double = trafficRatePerSec(value, sampleMs)
        fun microsPerSecond(value: Long): Double = trafficMicrosPerSecond(value, sampleMs)
    }

    private val INTERACTION_UPPER_MS = doubleArrayOf(16.0, 50.0, 100.0, 200.0, 300.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)
}
