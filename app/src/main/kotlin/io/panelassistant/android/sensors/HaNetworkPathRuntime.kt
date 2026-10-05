package io.panelassistant.android.sensors

/**
 * What the socket owner reports to the network-path monitor. One method per fact the transport can
 * state; the monitor decides what each means for the path.
 */
internal interface HaNetworkPathObserver {
    /**
     * The shared socket's own state. Only [HaSocketState.LIVE] starts a measurement and only
     * [HaSocketState.STOPPED] ends one; demand alone never does either.
     */
    fun onSocketState(state: HaSocketState)

    /** A probe came back; [rttMs] is send-to-decode on the owner's monotonic clock. */
    fun onRoundTrip(rttMs: Long)

    /** A probe went unanswered for the whole pong timeout; the socket is about to be torn down. */
    fun onProbeTimeout()

    /** A connection attempt (or a live connection) ended for the reason [kind]. */
    fun onConnectionFailure(kind: HaPathFailureKind)
}

/**
 * The service-owned coordinator between the socket owner's facts and the pure [HaNetworkPath].
 *
 * Serialises the state machine, stamps every fact with the injected monotonic clock, and pokes
 * consumers only when the verdict changes. [haRestarting] is a second attribution signal read
 * OUTSIDE the lock: while Home Assistant has announced a shutdown or start, a probe that goes
 * unanswered is the server's doing, not the path's. It is inert on a non-admin account with no MQTT
 * birth/will, which is why the exception-class rule in the owner stays the primary attribution.
 */
internal class HaNetworkPathMonitor(
    private val nowMs: () -> Long,
    private val haRestarting: () -> Boolean = { false },
    private val onChanged: () -> Unit = {},
    private val path: HaNetworkPath = HaNetworkPath(),
) : HaNetworkPathObserver {
    private val lock = Any()
    private var lastReported: Triple<Boolean, Boolean, HaNetworkPathSeverity>? = null

    override fun onSocketState(state: HaSocketState) = mutate { path.onSocketState(state) }

    /**
     * Hand the layer-3 verdict to the classifier, serialised like every other observation and poking
     * the surfaces when it changes what they would render.
     */
    fun onPathProbeVerdict(severity: HaNetworkPathSeverity?, cause: PathProbeCause) =
        mutate { path.onPathProbeVerdict(severity, cause) }

    override fun onRoundTrip(rttMs: Long) {
        val now = nowMs()
        mutate { path.onRoundTrip(now, rttMs) }
    }

    /** A probe that went unanswered with no other frame in the whole timeout: the path lost it. */
    override fun onProbeTimeout() = onConnectionFailure(HaPathFailureKind.NETWORK)

    override fun onConnectionFailure(kind: HaPathFailureKind) {
        val now = nowMs()
        // Foreign read outside the lock (the lifecycle holder documents the lock-order cycle).
        val attributed = if (kind == HaPathFailureKind.NETWORK && haRestarting()) HaPathFailureKind.SERVER else kind
        mutate { path.onFailure(now, attributed) }
    }

    /**
     * One atomic tuple at the injected clock's now.
     *
     * A read can CHANGE the verdict: recovery is samples ageing out of the window, which is time
     * passing rather than an event, so a panel whose last probe was the one that has just aged out
     * would otherwise keep a stale native chip on screen while the polled web surfaces — which
     * re-render from each read — had already recovered. Detecting the change here and poking makes
     * every reader, including the ten-second `/health` poll, correct the push-driven surfaces too.
     * The poke is fired OUTSIDE the lock and is idempotent: whatever it wakes reads this same
     * snapshot, finds the key unchanged and pokes nothing further.
     */
    fun snapshot(): HaNetworkPath.Snapshot {
        val now = nowMs()
        val (snapshot, changed) = synchronized(lock) {
            val taken = path.snapshot(now)
            val key = taken.reportableKey
            val moved = key != lastReported
            lastReported = key
            taken to moved
        }
        if (changed) onChanged()
        return snapshot
    }

    private inline fun mutate(block: () -> Unit) {
        val now = nowMs()
        val changed = synchronized(lock) {
            block()
            val key = path.snapshot(now).reportableKey
            val changed = key != lastReported
            lastReported = key
            changed
        }
        // Outside the lock: the poke reaches a renderer that reads this monitor back.
        if (changed) onChanged()
    }
}

/**
 * Process-local read side of the Home Assistant network-path classification, for surfaces that POLL
 * (the `:8888` pages, `/diag`, `/api/v1/status`) and the native chip that is poked.
 *
 * Same shape and rules as [HaLifecycleRuntime]: the service installs a source, everything else reads
 * ONE atomic [snapshot], and an uninstalled holder answers null so no surface can render a monitor
 * that no service owns. Ownership is identity-checked because service lifetimes overlap.
 */
internal object HaNetworkPathRuntime {
    private val lock = Any()
    @Volatile private var source: HaNetworkPathMonitor? = null

    fun install(next: HaNetworkPathMonitor) {
        synchronized(lock) { source = next }
    }

    /** Clear only if [expected] still owns the holder; returns whether consumers must be re-poked. */
    fun uninstall(expected: HaNetworkPathMonitor): Boolean = synchronized(lock) {
        if (source !== expected) return false
        source = null
        true
    }

    /** The one state read every surface uses. Null when no service owns the monitor. */
    fun snapshot(): HaNetworkPath.Snapshot? {
        val owner = synchronized(lock) { source } ?: return null
        val snap = owner.snapshot()
        return synchronized(lock) { if (source === owner) snap else null }
    }

    fun statusText(): String? = HaNetworkPathPresentation.statusText(snapshot())
    fun healthToken(): String = HaNetworkPathPresentation.healthToken(snapshot())
    fun diagnosticLine(): String = HaNetworkPathPresentation.diagnosticLine(snapshot())
    fun statusJson(): String = HaNetworkPathPresentation.statusJson(snapshot())
}
