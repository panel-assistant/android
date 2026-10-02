package io.github.maxlyth.hapaneld.sensors

/**
 * Turns the shared Home Assistant socket's signals into [HaLifecycle] observations and pushes the
 * rendered state at one listener.
 *
 * This is the only place that decides which transport phases count as "proven running" and which count
 * as "gone", so that judgement is testable in isolation. Pure apart from the injected clock — it owns no
 * socket, thread or timer, and is unit-tested in `HaLifecycleCoordinatorTest`.
 */
internal class HaLifecycleCoordinator(
    private val lifecycle: HaLifecycle = HaLifecycle(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onChanged: () -> Unit = {},
) : HaLifecycleObserver {
    private val lock = Any()
    private var published = HaLifecycle.Snapshot(HaLifecycleState.NORMAL, null, false, 0L, 0L)

    override fun onSignal(signal: HaLifecycleSignal) {
        val now = nowMs()
        when (signal) {
            is HaLifecycleSignal.Event -> lifecycle.onEvent(signal.event, HaLifecycleSource.SOCKET, now)
            HaLifecycleSignal.Rejected -> lifecycle.onSubscriptionRejected()
            HaLifecycleSignal.Established -> lifecycle.onSubscriptionEstablished()
            HaLifecycleSignal.Retired -> return onSocketWatchStopped()
            is HaLifecycleSignal.Transport -> when (signal.phase) {
                // Reaching LIVE means a fresh authenticated socket completed its subscriptions — so by
                // now this session's lifecycle subscription has already been accepted or refused, and
                // the machine can tell "a startup will announce itself" from "nothing ever will".
                HaExactEntityStreamPhase.LIVE -> lifecycle.onAuthenticatedRunning(now)
                // The socket is gone. Whether that is an outage or a LAN blip is the machine's call, not
                // ours — we only report that the connection ended.
                HaExactEntityStreamPhase.RECONNECTING,
                HaExactEntityStreamPhase.AUTH_FAILED,
                HaExactEntityStreamPhase.STOPPED,
                HaExactEntityStreamPhase.DISABLED,
                -> lifecycle.onDisconnected(now)
                // A new session is issuing its own subscriptions, so a refusal recorded against its
                // predecessor stops describing anyone. This matters when a session is REPLACED rather
                // than dropped — a demand or credential change cancels the old socket without any
                // disconnect phase, and only this signal separates the two sessions.
                HaExactEntityStreamPhase.SUBSCRIBING -> lifecycle.onRefusalBasisEnded()
                // Mid-handshake phases are steps of one attempt, not outcomes; treating them as a
                // disconnect would relabel every ordinary reconnect as a fault.
                HaExactEntityStreamPhase.AUTHENTICATING,
                HaExactEntityStreamPhase.CONNECTING,
                HaExactEntityStreamPhase.SYNCHRONIZING,
                -> Unit
            }
        }
        publish(now)
    }

    fun onNativeNotice(notice: HaLifecycleNotice) {
        val now = nowMs()
        lifecycle.onNativeNotice(notice, now)
        publish(now)
    }

    fun onNativeAuthenticated() {
        val now = nowMs()
        lifecycle.onAuthenticatedRunning(now)
        publish(now)
    }

    fun onNativeDisconnected() {
        val now = nowMs()
        lifecycle.onDisconnected(now, HaLifecycleSource.NATIVE)
        publish(now)
    }

    fun onNativeRetired() {
        val now = nowMs()
        lifecycle.onSourceRetired(HaLifecycleSource.NATIVE, now)
        publish(now)
    }

    /**
     * A Home Assistant birth/will observation from the MQTT broker. The second, privilege-free source:
     * it needs no WebSocket subscription and therefore works on the non-administrator accounts panels
     * actually sign in with.
     */
    fun onMqttStatus(event: HaLifecycleEvent) {
        val now = nowMs()
        lifecycle.onEvent(event, HaLifecycleSource.MQTT, now)
        publish(now)
    }

    /** The panel's own broker connection ended; an MQTT-sourced outage does not survive its channel. */
    fun onMqttChannelLost() {
        val now = nowMs()
        lifecycle.onMqttChannelLost(now)
        publish(now)
    }

    /**
     * The ONLY read. One atomic rendered tuple — state, source, refusal, revision, remaining recovery
     * lifetime — so no consumer can pair a state from one moment with a source from another. Piecewise
     * accessors are deliberately absent: their existence is what makes a torn read writable. Reading is
     * also what retires an expired back-online notice.
     */
    fun snapshot(): HaLifecycle.Snapshot = lifecycle.snapshot(nowMs())

    /**
     * The socket route stopped being watched, so a refusal recorded against it no longer describes a
     * session anyone is listening to. Without this, disabling and later re-enabling the watch under a
     * new user would show the OLD user's refusal until the new session's first answer.
     */
    fun onSocketWatchStopped() {
        val now = nowMs()
        lifecycle.onRefusalBasisEnded()
        // The socket route is gone, so a socket-sourced claim has no clearer left: `homeassistant_started`
        // and a fresh authenticated LIVE both arrive on the connection that just stopped being watched.
        // Retire it rather than hide it, or re-enabling the watch later resurfaces the old era's outage.
        lifecycle.onSourceRetired(HaLifecycleSource.SOCKET, now)
        publish(now)
    }

    /** The panel's broker session was REPLACED, so an MQTT-sourced claim loses the channel it was heard on. */
    fun onMqttChannelRetired() {
        val now = nowMs()
        lifecycle.onSourceRetired(HaLifecycleSource.MQTT, now)
        publish(now)
    }

    /**
     * [published] is a delivery de-duplicator, NOT a second copy of the state — nothing reads it as
     * truth, and the notification carries NO payload. Consumers read the canonical state themselves,
     * so an out-of-order poke is harmless: whoever runs last still reads the current value.
     *
     * Two rules make it sound under concurrent MQTT and socket callbacks. The snapshot is captured by
     * the machine under ONE lock acquisition — reading state, source and refusal separately allowed a
     * torn tuple that never existed. And the dedup key REFUSES TO GO BACKWARDS by revision — without
     * that, an older snapshot could win the lock last, overwrite a newer key, and a later real
     * transition back to the newer value would then be suppressed as a duplicate.
     */
    private fun publish(now: Long) {
        val changed = synchronized(lock) {
            val next = lifecycle.snapshot(now)
            if (!lifecyclePublishDecision(next, published)) return@synchronized false
            published = next
            true
        }
        if (changed) onChanged()
    }
}

/**
 * Whether [candidate] may replace [published] as the deduplication key. Pure — unit-tested in
 * `HaLifecycleCoordinatorTest` — because the two clauses each prevent a distinct suppression defect:
 * never move backwards to an older revision, and never renotify for a rendering-identical snapshot.
 */
internal fun lifecyclePublishDecision(
    candidate: HaLifecycle.Snapshot,
    published: HaLifecycle.Snapshot,
): Boolean = candidate.revision > published.revision &&
    (candidate.state != published.state ||
        candidate.source != published.source ||
        candidate.refused != published.refused ||
        candidate.reason != published.reason || candidate.expectedMs != published.expectedMs ||
        candidate.elapsedMs != published.elapsedMs)
