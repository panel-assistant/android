package io.github.maxlyth.hapaneld.sensors

/**
 * The process-global read side of the layer-3 probe, in the [HaNetworkPathRuntime] shape.
 *
 * Identity-gated install and uninstall so a superseded service can never clear its successor's
 * monitor, and one atomic [snapshot] so no surface can assemble a reading from two moments. The
 * monitor and the clock it is read against are one [Installation], published and retired together.
 */
internal object PathProbeRuntime {
    private class Installation(val monitor: PathProbeMonitor, val nowMs: () -> Long)

    private val lock = Any()
    private var installed: Installation? = null

    fun install(next: PathProbeMonitor, nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
        synchronized(lock) { installed = Installation(next, nowMs) }
    }

    /** Clear only if [owner] is still the installed monitor. Returns whether it actually cleared. */
    fun uninstall(owner: PathProbeMonitor): Boolean = synchronized(lock) {
        if (installed?.monitor !== owner) return false
        installed = null
        true
    }

    /**
     * Null when no service owns the probe, or when the owner this read began under was replaced before
     * it finished: a retired owner's reading is never returned. The monitor is read outside the lock
     * because it serialises itself and the clock is the caller's code.
     */
    fun snapshot(): PathProbeMonitor.Snapshot? {
        val owner = synchronized(lock) { installed } ?: return null
        val snap = owner.monitor.snapshot(owner.nowMs())
        return synchronized(lock) { if (installed === owner) snap else null }
    }

    /**
     * One `/diag` line. Always present so an absent line cannot be read as health, and terse: counts,
     * percentiles and the cadence, never an address and never an individual echo.
     */
    /** The route fact every state carries, because it is useful precisely when probing is not. */
    private fun routeTokens(snap: PathProbeMonitor.Snapshot): String =
        " family=${snap.family ?: "none"} other_resolved=${snap.otherResolved ?: "unknown"}"

    fun diagnosticLine(): String {
        val snap = snapshot() ?: return "[ha-path-probe] state=unowned"
        return when (snap.availability) {
            PathProbeAvailability.UNSUPPORTED ->
                "[ha-path-probe] state=unsupported" + routeTokens(snap) +
                    " detail=this platform refuses an ICMP socket to the app"
            PathProbeAvailability.UNPROVEN ->
                "[ha-path-probe] state=unproven" + routeTokens(snap) +
                    " bursts=${snap.bursts} sent=${snap.sent} received=0 " +
                    "detail=no echo answered yet, so silence is not counted as loss"
            PathProbeAvailability.PROVEN ->
                "[ha-path-probe] state=${snap.severity?.wireValue ?: "unknown"}" + routeTokens(snap) +
                    " bursts=${snap.bursts} " +
                    "sent=${snap.sent} received=${snap.received} " +
                    "loss=${"%.1f".format(java.util.Locale.ROOT, snap.lossPercent)}% " +
                    "p50=${snap.p50Ms} p95=${snap.p95Ms} max=${snap.maxMs} jitter=${snap.jitterMs} " +
                    "dead_bursts=${snap.consecutiveDeadBursts} interval=${snap.intervalMs / 1000L}s " +
                    "last_burst_age=${snap.lastBurstAgeMs}"
        }
    }

    /** The `/api/v1/status` object, emitted unconditionally so an absent field cannot read as healthy. */
    fun statusJson(): String {
        val snap = snapshot()
        val json = org.json.JSONObject()
            .put("available", snap?.availability?.name?.lowercase() ?: "unowned")
        if (snap != null) {
            // Route facts are reported in EVERY state, including unsupported: knowing which family
            // the socket is on matters most exactly when the probe itself cannot run.
            json.put("family", snap.family ?: org.json.JSONObject.NULL)
            json.put("other_resolved", snap.otherResolved ?: org.json.JSONObject.NULL)
        }
        if (snap != null && snap.availability != PathProbeAvailability.UNSUPPORTED) {
            json.put("state", snap.severity?.wireValue ?: "unproven")
                .put("bursts", snap.bursts)
                .put("echoes_sent", snap.sent)
                .put("echoes_received", snap.received)
                .put("loss_percent", snap.lossPercent)
                .put("p50_ms", snap.p50Ms)
                .put("p95_ms", snap.p95Ms)
                .put("max_ms", snap.maxMs)
                .put("jitter_ms", snap.jitterMs)
                .put("consecutive_dead_bursts", snap.consecutiveDeadBursts)
                .put("interval_ms", snap.intervalMs)
                .put("last_burst_age_ms", snap.lastBurstAgeMs)
        }
        return json.toString()
    }
}
