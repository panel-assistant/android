package io.panelassistant.android.dashboard

/** Process-wide counters surfaced through `/api/v1/perf` and the filter status endpoint. */
object EntityFilterTelemetry {
    class Lease internal constructor(internal val id: Long)

    private var nextLease = 0L
    private var owner = 0L
    private var active = false
    private var configuredCount = 0
    private var configuredHash = ""
    private var lastError = ""
    private var modifiedSubscriptions = 0L
    private var failures = 0L
    private var directFallbacks = 0L
    private var trafficInstalled = false
    private var trafficCounters = TrafficCounters()

    /** Saturating cumulative traffic counters surfaced in the filter status snapshot. */
    private data class TrafficCounters(
        val batches: Long = 0,
        val sampleMs: Long = 0,
        val frames: Long = 0,
        val payloadBytes: Long = 0,
        val entityUpdates: Long = 0,
        val observerMicros: Long = 0,
        val droppedFrames: Long = 0,
    ) {
        fun add(batch: EntityFilterProtocol.TrafficBatch) = TrafficCounters(
            batches = saturatedAdd(batches, 1),
            sampleMs = saturatedAdd(sampleMs, batch.sampleMs),
            frames = saturatedAdd(frames, batch.frames),
            payloadBytes = saturatedAdd(payloadBytes, batch.payloadBytes),
            entityUpdates = saturatedAdd(entityUpdates, batch.entityUpdates),
            observerMicros = saturatedAdd(observerMicros, batch.observerMicros),
            droppedFrames = saturatedAdd(droppedFrames, batch.droppedFrames),
        )
    }

    @Synchronized fun started(entityIds: List<String>, hash: String): Lease {
        val lease = Lease(++nextLease)
        owner = lease.id
        active = true
        configuredCount = entityIds.size
        configuredHash = hash
        lastError = ""
        modifiedSubscriptions = 0; failures = 0; directFallbacks = 0
        resetTraffic()
        return lease
    }

    /** Establish a new inactive owner, superseding callbacks from any previous renderer activity. */
    @Synchronized fun stopped(): Lease {
        val lease = Lease(++nextLease)
        owner = lease.id
        reset()
        return lease
    }

    /** Release only the activity that still owns the public runtime snapshot. */
    @Synchronized fun stop(lease: Lease) {
        if (owner != lease.id) return
        owner = 0L
        reset()
    }

    private fun reset() {
        active = false
        configuredCount = 0
        configuredHash = ""
        lastError = ""
        modifiedSubscriptions = 0
        failures = 0
        directFallbacks = 0
        resetTraffic()
    }

    private fun resetTraffic() {
        DashboardTelemetry.reset()
        trafficInstalled = false
        trafficCounters = TrafficCounters()
    }

    @Synchronized fun isActive(lease: Lease): Boolean = owner == lease.id && active
    @Synchronized fun dashboardFilterState(): Pair<Boolean, Int> = active to configuredCount
    @Synchronized fun subscriptionModified(lease: Lease) {
        if (owner == lease.id) modifiedSubscriptions++
    }
    @Synchronized fun failed(lease: Lease, kind: String) {
        if (owner == lease.id) { failures++; lastError = kind }
    }
    /** Interception could not be established and the renderer was safely held before opening HA.
     *  Preserve the configured set and error for diagnostics without claiming either an active filter
     *  or an unfiltered direct fallback. */
    @Synchronized fun held(lease: Lease, kind: String) {
        if (owner == lease.id) {
            active = false
            failures++
            lastError = kind
        }
    }
    @Synchronized fun directFallback(lease: Lease) {
        if (owner == lease.id) { directFallbacks++; active = false }
    }
    @Synchronized fun trafficObserverInstalled(lease: Lease) {
        if (owner == lease.id) {
            trafficInstalled = true
            DashboardTelemetry.installed()
        }
    }
    @Synchronized fun traffic(lease: Lease, batch: EntityFilterProtocol.TrafficBatch) {
        if (owner != lease.id || !trafficInstalled) return
        trafficCounters = trafficCounters.add(batch)
        DashboardTelemetry.record(batch)
    }

    @Synchronized fun json(): String {
        val t = trafficCounters
        return "{" +
            "\"active\":$active," +
            "\"mode\":\"native_socket\"," +
            "\"entityCount\":$configuredCount," +
            "\"filterHash\":\"$configuredHash\"," +
            "\"modifiedSubscriptions\":$modifiedSubscriptions," +
            "\"failures\":$failures,\"directFallbacks\":$directFallbacks," +
            "\"lastError\":\"$lastError\"," +
            "\"traffic\":{\"installed\":$trafficInstalled,\"batches\":${t.batches}," +
            "\"sampleMs\":${t.sampleMs},\"frames\":${t.frames}," +
            "\"frameChars\":${t.payloadBytes},\"payloadBytes\":${t.payloadBytes}," +
            "\"entityUpdates\":${t.entityUpdates}," +
            "\"processingMicros\":${t.observerMicros},\"observerMicros\":${t.observerMicros}," +
            "\"droppedFrames\":${t.droppedFrames}}}"
    }
}
