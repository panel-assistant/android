package io.panelassistant.android.metrics

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.util.Cached
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Fixed feature-operation vocabulary. IDs are public API and must never contain runtime identifiers.
 *
 * [parentId] describes known inclusive nesting. A parent's elapsed time can include its children, so
 * elapsed totals across the tree are latency observations and must never be added into "cost".
 */
enum class FeatureCostOperation(
    val id: String,
    val family: String = id.substringBefore('.'),
    val parentId: String? = null,
) {
    ENTITY_SYNC("entity_learning.sync"),
    ENTITY_STATES_FETCH_PARSE("entity_learning.states_fetch_parse", parentId = "entity_learning.sync"),
    ENTITY_DASHBOARD_FETCH_PARSE("entity_learning.dashboard_fetch_parse", parentId = "entity_learning.sync"),
    ENTITY_STATIC_SCAN("entity_learning.static_scan", parentId = "entity_learning.sync"),
    ENTITY_ACCESS_PARSE("entity_learning.access_parse"),
    ENTITY_METRIC_PARSE("entity_learning.metric_parse"),
    ENTITY_BROWSER_OBSERVER("entity_learning.browser_observer"),
    ENTITY_TELEMETRY_FLUSH("entity_learning.telemetry_flush"),
    ENTITY_MEMBERSHIP_LOOKUP("entity_learning.membership_lookup", parentId = "entity_learning.telemetry_flush"),
    ENTITY_ACCESS_WRITE("entity_learning.access_write", parentId = "entity_learning.telemetry_flush"),
    ENTITY_METRIC_WRITE("entity_learning.metric_write", parentId = "entity_learning.telemetry_flush"),
    ENTITY_DB_MAINTENANCE("entity_learning.db_maintenance"),
    ENTITY_STATUS_READ("entity_learning.status_read"),
    ENTITY_LIST("entity_learning.list"),
    ENTITY_EXPORT("entity_learning.export"),
    PROFILE_CATALOG_LOAD("profiles.catalog_load"),
    PROFILE_YAML_PARSE("profiles.yaml_parse"),
    PROFILE_VALIDATE("profiles.validate"),
    PROFILE_STARTUP_RESOLVE("profiles.startup_resolve"),
    SHIZUKU_BIND("shizuku.bind"),
    SHIZUKU_CALL("shizuku.call"),
    SHIZUKU_SCREENSHOT("shizuku.screenshot"),
    SHIZUKU_INSTALL("shizuku.install"),
    KIOSK_STATE_POLL("kiosk.state_poll"),
    CAPABILITY_SNAPSHOT("capabilities.snapshot"),
    MDNS_PEER_REFRESH("mdns.peer_refresh"),
    RELAY_TOPOLOGY_DISCOVERY("hardware.relay_topology_discovery"),
    RELAY_STATE_READ("hardware.relay_state_read"),
    RELAY_HARDWARE_WRITE("hardware.relay_write"),
    RELAY_GPIO_PREPARE("hardware.relay_gpio_prepare", parentId = "hardware.relay_write"),
    RELAY_READBACK("hardware.relay_readback"),
    AUTO_BRIGHTNESS_APPLY("display.auto_brightness_apply"),
    // Retired with the app-side shell poller. Keep the shipped ID in the fixed vocabulary so
    // projections remain comparable; the helper-side fallback runs in another process.
    GPIO_PROXIMITY_POLL("sensors.gpio_proximity_poll"),
    PROXIMITY_SAMPLE("sensors.proximity_sample"),
    PROXIMITY_PERSIST_BATCH("sensors.proximity_persist_batch"),
    MQTT_TEARDOWN("mqtt.teardown"),
    MQTT_STATE_OUTBOX("mqtt.state_outbox"),
    ZIGBEE_RECONCILE("zigbee.reconcile"),
    ZIGBEE_HEALTH_SAMPLE("zigbee.health_sample"),
    NETWORK_RECONFIGURE("network.reconfigure"),
    CONFIG_LIVE_REFRESH("config.live_refresh"),
    NAVBAR_MODE_APPLY("navbar.mode_apply"),
    NAVBAR_ACTION("navbar.action"),
    MQTT_COMMAND_DISPATCH("mqtt.command_dispatch"),
    MQTT_COMMAND_QUEUE_WAIT("mqtt.command_queue_wait"),
    MQTT_DISCOVERY_REANNOUNCE("mqtt.discovery_reannounce"),
    MQTT_HEARTBEAT_ADMISSION("mqtt.heartbeat_admission"),
    MQTT_HEARTBEAT_RECOVERY("mqtt.heartbeat_recovery"),
    PERF_ROOT_DIAGNOSTICS("performance.root_diagnostics"),
    PERF_FEATURE_COST_PROJECTION("performance.feature_cost_projection"),
    PROVISIONING_PLAN("provisioning.plan"),
    PROVISIONING_OBSERVATION_REFRESH("provisioning.observation_refresh", parentId = "provisioning.plan"),
    TAME_MUTATION("vendor.tame_mutation"),
    REMOTE_INPUT("control.remote_input"),
    DASHBOARD_STORAGE_CLEAR("dashboard.storage_clear"),
    LOG_CAPTURE_BATCH("log.capture_batch"),
    LOG_SHIP_BATCH("log.ship_batch"),
}

enum class FeatureCostOutcome { SUCCESS, FAILURE, CANCELLED, REJECTED }

/** Primitive clock/identity seam: unlike `() -> Long`, invocation does not box the returned value. */
internal fun interface FeatureCostLongSource {
    fun read(): Long
}

/**
 * Constant-space, fixed-key feature-cost counters. Recording is event-driven: there is no sampler,
 * persistence, disk write, network export, dynamic label, or background thread.
 */
class FeatureCostRegistry internal constructor(
    private val wallNanos: FeatureCostLongSource = FeatureCostLongSource(System::nanoTime),
    private val threadCpuNanos: FeatureCostLongSource = FeatureCostLongSource {
        runCatching { android.os.Debug.threadCpuTimeNanos() }.getOrDefault(-1L)
    },
    private val threadId: FeatureCostLongSource = FeatureCostLongSource { Thread.currentThread().id },
    private val enabled: Boolean = true,
) {
    /** Lets call sites skip instrumentation-only work-size scans in the compiled-out comparison arm. */
    internal val recordingEnabled: Boolean get() = enabled

    private val startedWallNanos = if (enabled) wallNanos.read() else 0L
    private val generation = if (enabled) NEXT_GENERATION.incrementAndGet() else 0L
    // Disabled paired builds deliberately retain no per-operation state. This is nullable rather than
    // an empty map so construction cannot allocate an array/map proportional to the vocabulary.
    private val records: Array<MutableRecord>? =
        if (enabled) Array(FeatureCostOperation.entries.size) { MutableRecord() } else null
    @Volatile
    private var epoch = if (enabled) captureEpoch(1L, startedWallNanos) else EpochState.DISABLED

    fun span(operation: FeatureCostOperation): Span {
        if (!enabled) return Span.NOOP
        admit(operation)
        return Span(
            registry = this,
            operation = operation,
            startWallNanos = wallNanos.read(),
            startThreadCpuNanos = threadCpuNanos.read(),
            startThreadId = threadId.read(),
        )
    }

    /**
     * Start an allocation-free, same-scope latency measurement.
     *
     * The returned primitive must be passed exactly once to [finishSynchronous], normally from a
     * `finally` block. Use [span] instead when ownership crosses callbacks or cancellation scopes:
     * object spans are idempotent, while this hot-path API intentionally pays no allocation for an
     * exactly-once guard and does not sample thread CPU.
     */
    fun beginSynchronous(operation: FeatureCostOperation): Long {
        if (!enabled) return DISABLED_START
        admit(operation)
        return wallNanos.read()
    }

    /**
     * Finish a measurement returned by [beginSynchronous]. Elapsed time is inclusive operation
     * latency; nested parent/child totals overlap and are not additive resource consumption.
     */
    fun finishSynchronous(
        operation: FeatureCostOperation,
        startedWallNanos: Long,
        outcome: FeatureCostOutcome = FeatureCostOutcome.SUCCESS,
        workUnits: Long = 0L,
        workBytes: Long = 0L,
    ) {
        if (!enabled) return
        finish(
            operation = operation,
            startWallNanos = startedWallNanos,
            startThreadCpuNanos = -1L,
            startThreadId = -1L,
            outcome = outcome,
            workUnits = workUnits,
            workBytes = workBytes,
        )
    }

    fun recordDropped(operation: FeatureCostOperation, count: Long = 1L) {
        if (!enabled) return
        saturatingAdd(record(operation).dropped, count.coerceAtLeast(0L))
    }

    fun recordCoalesced(operation: FeatureCostOperation, count: Long = 1L) {
        if (!enabled) return
        saturatingAdd(record(operation).coalesced, count.coerceAtLeast(0L))
    }

    fun setBacklog(operation: FeatureCostOperation, current: Int) {
        if (!enabled) return
        val record = record(operation)
        val value = current.coerceAtLeast(0)
        record.backlog.set(value)
        updateMax(record.peakBacklog, value.toLong())
    }

    /** Import one bounded aggregate measured in another runtime (currently the dashboard browser). */
    fun recordExternal(
        operation: FeatureCostOperation,
        externalExecutionNanos: Long,
        events: Long = 0L,
        inputChars: Long = 0L,
        workUnits: Long = 0L,
        workBytes: Long = 0L,
    ) {
        if (!enabled) return
        val record = record(operation)
        record.calls.incrementAndGet()
        record.succeeded.incrementAndGet()
        saturatingAdd(record.externalEvents, events.coerceAtLeast(0L))
        saturatingAdd(record.externalInputChars, inputChars.coerceAtLeast(0L))
        saturatingAdd(record.externalExecutionNanosTotal, externalExecutionNanos.coerceAtLeast(0L))
        record.externalExecutionSamples.incrementAndGet()
        saturatingAdd(record.workUnits, workUnits.coerceAtLeast(0L))
        saturatingAdd(record.workBytes, workBytes.coerceAtLeast(0L))
    }

    /**
     * Begin a new bounded comparison epoch without discarding process-lifetime startup evidence.
     * Baselines are fixed-cardinality snapshots; concurrent completions can land on either side of the
     * boundary, but counters never go negative and no active span changes ownership.
     */
    fun beginEpoch() {
        if (!enabled) return
        val previous = epoch
        epoch = captureEpoch(previous.generation + 1L, wallNanos.read())
    }

    /** Record already-measured synchronous projection overhead without allocating a span. */
    internal fun recordSynchronousElapsed(
        operation: FeatureCostOperation,
        elapsedNanos: Long,
        workBytes: Long = 0L,
    ) {
        if (!enabled) return
        val record = record(operation)
        val elapsed = elapsedNanos.coerceAtLeast(0L)
        record.calls.incrementAndGet()
        record.succeeded.incrementAndGet()
        saturatingAdd(record.wallNanosTotal, elapsed)
        updateMax(record.wallNanosMax, elapsed)
        record.wallHistogram.incrementAndGet(histogramIndex(elapsed))
        saturatingAdd(record.workBytes, workBytes.coerceAtLeast(0L))
    }

    internal fun finish(
        operation: FeatureCostOperation,
        startWallNanos: Long,
        startThreadCpuNanos: Long,
        startThreadId: Long,
        outcome: FeatureCostOutcome,
        workUnits: Long,
        workBytes: Long,
    ) {
        val record = record(operation)
        record.inFlight.decrementAndGet()
        when (outcome) {
            FeatureCostOutcome.SUCCESS -> record.succeeded.incrementAndGet()
            FeatureCostOutcome.FAILURE -> record.failed.incrementAndGet()
            FeatureCostOutcome.CANCELLED -> record.cancelled.incrementAndGet()
            FeatureCostOutcome.REJECTED -> record.rejected.incrementAndGet()
        }
        val elapsed = forwardDelta(wallNanos.read(), startWallNanos)
        saturatingAdd(record.wallNanosTotal, elapsed)
        updateMax(record.wallNanosMax, elapsed)
        record.wallHistogram.incrementAndGet(histogramIndex(elapsed))

        if (startThreadCpuNanos >= 0L) {
            val endCpu = threadCpuNanos.read()
            if (endCpu >= startThreadCpuNanos && threadId.read() == startThreadId) {
                val cpu = endCpu - startThreadCpuNanos
                saturatingAdd(record.threadCpuNanosTotal, cpu)
                updateMax(record.threadCpuNanosMax, cpu)
                record.threadCpuSamples.incrementAndGet()
            }
        }
        saturatingAdd(record.workUnits, workUnits.coerceAtLeast(0L))
        saturatingAdd(record.workBytes, workBytes.coerceAtLeast(0L))
    }

    fun json(): String {
        if (!enabled) return DISABLED_JSON
        val epochSnapshot = epoch
        val operations = JSONArray()
        FeatureCostOperation.entries.forEach { operation ->
            val r = record(operation)
            val baseline = epochSnapshot.records[operation.ordinal]
            val epochRecord = JSONObject()
                .put("calls", delta(r.calls.get(), baseline.calls))
                .put("succeeded", delta(r.succeeded.get(), baseline.succeeded))
                .put("failed", delta(r.failed.get(), baseline.failed))
                .put("cancelled", delta(r.cancelled.get(), baseline.cancelled))
                .put("rejected", delta(r.rejected.get(), baseline.rejected))
                .put("dropped", delta(r.dropped.get(), baseline.dropped))
                .put("coalesced", delta(r.coalesced.get(), baseline.coalesced))
                .put("wall_ns_total", delta(r.wallNanosTotal.get(), baseline.wallNanosTotal))
                .put("thread_cpu_ns_total", delta(r.threadCpuNanosTotal.get(), baseline.threadCpuNanosTotal))
                .put("thread_cpu_samples", delta(r.threadCpuSamples.get(), baseline.threadCpuSamples))
                .put("external_events", delta(r.externalEvents.get(), baseline.externalEvents))
                .put("external_input_chars", delta(r.externalInputChars.get(), baseline.externalInputChars))
                .put("external_execution_ns_total", delta(r.externalExecutionNanosTotal.get(), baseline.externalExecutionNanosTotal))
                .put("external_execution_samples", delta(r.externalExecutionSamples.get(), baseline.externalExecutionSamples))
                .put("work_units", delta(r.workUnits.get(), baseline.workUnits))
                .put("work_bytes", delta(r.workBytes.get(), baseline.workBytes))
                .put("wall_histogram", JSONArray((0 until HISTOGRAM_UPPER_NANOS.size + 1).map {
                    delta(r.wallHistogram.get(it), baseline.wallHistogram[it])
                }))
            operations.put(JSONObject()
                .put("id", operation.id)
                .put("family", operation.family)
                .put("parent_id", operation.parentId ?: JSONObject.NULL)
                .put("calls", r.calls.get())
                .put("succeeded", r.succeeded.get())
                .put("failed", r.failed.get())
                .put("cancelled", r.cancelled.get())
                .put("rejected", r.rejected.get())
                .put("dropped", r.dropped.get())
                .put("coalesced", r.coalesced.get())
                .put("in_flight", r.inFlight.get())
                .put("peak_in_flight", r.peakInFlight.get())
                .put("backlog", r.backlog.get())
                .put("peak_backlog", r.peakBacklog.get())
                .put("wall_ns_total", r.wallNanosTotal.get())
                .put("wall_ns_max", r.wallNanosMax.get())
                .put("thread_cpu_ns_total", r.threadCpuNanosTotal.get())
                .put("thread_cpu_ns_max", r.threadCpuNanosMax.get())
                .put("thread_cpu_samples", r.threadCpuSamples.get())
                .put("external_events", r.externalEvents.get())
                .put("external_input_chars", r.externalInputChars.get())
                .put("external_execution_ns_total", r.externalExecutionNanosTotal.get())
                .put("external_execution_samples", r.externalExecutionSamples.get())
                .put("work_units", r.workUnits.get())
                .put("work_bytes", r.workBytes.get())
                .put("wall_histogram", JSONArray((0 until HISTOGRAM_UPPER_NANOS.size + 1).map {
                    r.wallHistogram.get(it)
                }))
                .put("epoch", epochRecord))
        }
        val now = wallNanos.read()
        return JSONObject()
            .put("schema", 2)
            .put("enabled", true)
            .put("generation", generation)
            .put("since_elapsed_ns", forwardDelta(now, startedWallNanos))
            .put("epoch_generation", epochSnapshot.generation)
            .put("epoch_elapsed_ns", forwardDelta(now, epochSnapshot.startedWallNanos))
            .put("metric_semantics", JSONObject()
                .put("wall_ns", "inclusive_elapsed_latency_not_additive")
                .put("thread_cpu_ns", "same_thread_cpu_subset")
                .put("external_execution_ns", "external_runtime_execution_aggregate_not_latency")
                .put("work", "operation_specific_resource_volume"))
            .put("wall_histogram_upper_ns", JSONArray(HISTOGRAM_UPPER_NANOS))
            .put("operations", operations)
            .toString()
    }

    private fun admit(operation: FeatureCostOperation) {
        val record = record(operation)
        val inFlight = record.inFlight.incrementAndGet()
        updateMax(record.peakInFlight, inFlight.toLong())
        record.calls.incrementAndGet()
    }

    private fun record(operation: FeatureCostOperation): MutableRecord =
        requireNotNull(records)[operation.ordinal]

    class Span internal constructor(
        private val registry: FeatureCostRegistry?,
        private val operation: FeatureCostOperation,
        private val startWallNanos: Long,
        private val startThreadCpuNanos: Long,
        private val startThreadId: Long,
        private val noop: Boolean = false,
    ) : AutoCloseable {
        private val closed = AtomicInteger()
        private var outcome = FeatureCostOutcome.SUCCESS
        private var workUnits = 0L
        private var workBytes = 0L

        fun work(units: Long = 0L, bytes: Long = 0L): Span = apply {
            if (noop) return@apply
            workUnits = units.coerceAtLeast(0L)
            workBytes = bytes.coerceAtLeast(0L)
        }

        fun outcome(value: FeatureCostOutcome): Span = apply { if (!noop) outcome = value }

        override fun close() {
            if (noop) return
            if (!closed.compareAndSet(0, 1)) return
            requireNotNull(registry).finish(
                operation, startWallNanos, startThreadCpuNanos, startThreadId,
                outcome, workUnits, workBytes,
            )
        }

        companion object {
            internal val NOOP = Span(null, FeatureCostOperation.ENTITY_ACCESS_PARSE, 0L, -1L, -1L, noop = true)
        }
    }

    private class MutableRecord {
        val calls = AtomicLong()
        val succeeded = AtomicLong()
        val failed = AtomicLong()
        val cancelled = AtomicLong()
        val rejected = AtomicLong()
        val dropped = AtomicLong()
        val coalesced = AtomicLong()
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicLong()
        val backlog = AtomicInteger()
        val peakBacklog = AtomicLong()
        val wallNanosTotal = AtomicLong()
        val wallNanosMax = AtomicLong()
        val threadCpuNanosTotal = AtomicLong()
        val threadCpuNanosMax = AtomicLong()
        val threadCpuSamples = AtomicLong()
        val externalEvents = AtomicLong()
        val externalInputChars = AtomicLong()
        val externalExecutionNanosTotal = AtomicLong()
        val externalExecutionSamples = AtomicLong()
        val workUnits = AtomicLong()
        val workBytes = AtomicLong()
        val wallHistogram = AtomicLongArray(HISTOGRAM_UPPER_NANOS.size + 1)
    }

    private fun histogramIndex(elapsedNanos: Long): Int {
        var low = 0
        var high = HISTOGRAM_UPPER_NANOS.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (elapsedNanos <= HISTOGRAM_UPPER_NANOS[mid]) high = mid else low = mid + 1
        }
        return low
    }

    private fun saturatingAdd(target: AtomicLong, delta: Long) {
        if (delta <= 0L) return
        while (true) {
            val current = target.get()
            val next = if (current > Long.MAX_VALUE - delta) Long.MAX_VALUE else current + delta
            if (target.compareAndSet(current, next)) return
        }
    }

    private fun updateMax(target: AtomicLong, candidate: Long) {
        var current = target.get()
        while (candidate > current && !target.compareAndSet(current, candidate)) current = target.get()
    }

    private fun forwardDelta(now: Long, then: Long): Long =
        if (now >= then) now - then else 0L

    private fun delta(current: Long, baseline: Long): Long =
        if (current >= baseline) current - baseline else 0L

    private fun captureEpoch(generation: Long, startedWallNanos: Long): EpochState = EpochState(
        generation = generation,
        startedWallNanos = startedWallNanos,
        records = FeatureCostOperation.entries.map { operation ->
            val r = record(operation)
            BaselineRecord(
                calls = r.calls.get(),
                succeeded = r.succeeded.get(),
                failed = r.failed.get(),
                cancelled = r.cancelled.get(),
                rejected = r.rejected.get(),
                dropped = r.dropped.get(),
                coalesced = r.coalesced.get(),
                wallNanosTotal = r.wallNanosTotal.get(),
                threadCpuNanosTotal = r.threadCpuNanosTotal.get(),
                threadCpuSamples = r.threadCpuSamples.get(),
                externalEvents = r.externalEvents.get(),
                externalInputChars = r.externalInputChars.get(),
                externalExecutionNanosTotal = r.externalExecutionNanosTotal.get(),
                externalExecutionSamples = r.externalExecutionSamples.get(),
                workUnits = r.workUnits.get(),
                workBytes = r.workBytes.get(),
                wallHistogram = LongArray(HISTOGRAM_UPPER_NANOS.size + 1) { r.wallHistogram.get(it) },
            )
        }.toTypedArray(),
    )

    private data class EpochState(
        val generation: Long,
        val startedWallNanos: Long,
        val records: Array<BaselineRecord>,
    ) {
        companion object {
            val DISABLED = EpochState(0L, 0L, emptyArray())
        }
    }

    private data class BaselineRecord(
        val calls: Long,
        val succeeded: Long,
        val failed: Long,
        val cancelled: Long,
        val rejected: Long,
        val dropped: Long,
        val coalesced: Long,
        val wallNanosTotal: Long,
        val threadCpuNanosTotal: Long,
        val threadCpuSamples: Long,
        val externalEvents: Long,
        val externalInputChars: Long,
        val externalExecutionNanosTotal: Long,
        val externalExecutionSamples: Long,
        val workUnits: Long,
        val workBytes: Long,
        val wallHistogram: LongArray,
    )

    companion object {
        private const val DISABLED_START = 0L
        private const val DISABLED_JSON = """{"schema":2,"enabled":false}"""
        private val NEXT_GENERATION = AtomicLong()
        internal val HISTOGRAM_UPPER_NANOS = longArrayOf(
            100_000L, 250_000L, 500_000L,
            1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L, 25_000_000L,
            50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L,
            1_000_000_000L, 2_500_000_000L, 5_000_000_000L, 10_000_000_000L,
            30_000_000_000L, 120_000_000_000L,
        )
    }
}

/** Process-local registry. Deliberately reset only by process death so startup work remains visible. */
object FeatureCosts {
    val registry = FeatureCostRegistry(enabled = BuildConfig.FEATURE_COSTS_ENABLED)
    private const val PROJECTION_CACHE_NANOS = 1_000_000_000L
    private val projectionCache = Cached(PROJECTION_CACHE_NANOS, System::nanoTime) {
        val started = System.nanoTime()
        registry.json().also { projection ->
            registry.recordSynchronousElapsed(
                FeatureCostOperation.PERF_FEATURE_COST_PROJECTION,
                System.nanoTime() - started,
                projection.length.toLong(),
            )
        }
    }

    fun beginEpoch() {
        registry.beginEpoch()
        projectionCache.invalidate()
    }

    fun json(): String {
        if (!registry.recordingEnabled) return registry.json()
        return projectionCache.get()
    }
}
