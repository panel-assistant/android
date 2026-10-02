package io.github.maxlyth.hapaneld.sensors

/**
 * Process-local read side of the Home Assistant lifecycle state, for surfaces that POLL rather than
 * subscribe — the `:8888` pages and the panel facts.
 *
 * It exists because the back-online notice retires on read rather than on a timer: a push-only bridge
 * would leave a poller showing "back online" indefinitely. Reading here always re-evaluates.
 *
 * Follows the same shape as the other process-global runtime holders (`StorageHealthRuntime`,
 * `BuiltinDashboard`): the service installs a source, everything else reads it, and an uninstalled
 * source answers with the honest default rather than failing.
 *
 * Ownership is identity-checked because service lifetimes OVERLAP: Android may construct a successor
 * service while a predecessor's teardown is still timing out, and an unconditional clear would let the
 * predecessor erase the successor's installation. Every mutation therefore names the coordinator it
 * belongs to, and a mutation whose coordinator no longer owns this holder is a no-op. [snapshot] is the
 * only state read, so a consumer cannot pair fields from two different owners or two different moments.
 */
internal object HaLifecycleRuntime {
    private val lock = Any()
    @Volatile private var source: HaLifecycleCoordinator? = null
    @Volatile private var socketWatching = false
    @Volatile private var nativeWatching = false

    /**
     * The MQTT half of watching is DERIVED from the bridge's own serialized connection state, never
     * copied. A copied flag duplicates truth the bridge already maintains under a serialized owner and
     * can race it — set-after-check, a stale announcement claiming a fresh generation, an old session's
     * disconnect clearing a newer claim. A pull cannot: whoever asks reads the current truth, and a
     * superseded session has nothing to write.
     */
    @Volatile private var mqttConnected: (() -> Boolean)? = null

    /**
     * True while ANY source can report, so surfaces omit the row entirely otherwise. Either source alone
     * is enough: the MQTT one is what keeps this useful on a non-administrator account, where Home
     * Assistant refuses the WebSocket subscription outright.
     */
    val watching: Boolean get() {
        // Captured under the lock as one pair so a half-torn-down owner cannot answer with its
        // predecessor's other half; the bridge read is invoked OUTSIDE it, because calling foreign
        // code under a lock is how lock-order cycles start.
        val (socket, mqtt) = synchronized(lock) { (socketWatching || nativeWatching) to mqttConnected }
        return socket || (mqtt?.invoke() ?: false)
    }

    /**
     * Install [next] as the owner together with its MQTT read, in ONE atomic step so no reader can pair
     * one owner's coordinator with another owner's bridge.
     */
    fun install(next: HaLifecycleCoordinator) {
        synchronized(lock) {
            source = next
            socketWatching = false
            nativeWatching = false
            mqttConnected = null
            mqttLease = null
        }
    }

    /**
     * Clear the installation, but only if [expected] still owns it. Returns whether anything was
     * cleared, so the caller knows whether consumers need to be told the state they rendered is gone.
     * A predecessor whose teardown lost the race to a successor's install clears nothing.
     */
    fun uninstall(expected: HaLifecycleCoordinator): Boolean = synchronized(lock) {
        if (source !== expected) return false
        source = null
        socketWatching = false
        nativeWatching = false
        mqttConnected = null
        mqttLease = null
        true
    }

    /**
     * Record whether the socket route is watched — ignored unless [owner] still owns this holder.
     * Returns whether the answer to "is anything reportable here" changed, so the caller can retire
     * what consumers are rendering. Switching the last route off does not merely stop new
     * observations: it makes every existing one unrenderable (see [snapshot]), which is why the
     * caller must poke consumers rather than leave a card describing a watch that no longer exists.
     */
    fun setWatching(owner: HaLifecycleCoordinator, next: Boolean): Boolean = synchronized(lock) {
        if (source !== owner) return false
        val changed = socketWatching != next
        socketWatching = next
        changed
    }

    fun setNativeWatching(owner: HaLifecycleCoordinator, next: Boolean): Boolean = synchronized(lock) {
        if (source !== owner) return false
        val changed = nativeWatching != next
        nativeWatching = next
        changed
    }

    /**
     * One broker generation's right to report. A bridge is REPLACED on reconfigure while the service
     * and its coordinator stay the same, so owner identity alone cannot separate the old channel from
     * the new one — the lease can, because each bridge gets exactly one and holds it for its whole life.
     */
    class MqttLease internal constructor()

    @Volatile private var mqttLease: MqttLease? = null

    /**
     * Register [lease] as the live broker generation, replacing any predecessor. Owner-gated. A
     * replacement RETIRES the previous generation's MQTT-sourced claims: the retraction would have
     * arrived on the channel that has just been replaced, and the new channel gets no replay because
     * Home Assistant's birth is not retained. Returns whether anything changed, so the caller can poke
     * consumers.
     */
    fun installMqttLease(owner: HaLifecycleCoordinator, lease: MqttLease, mqtt: (() -> Boolean)?): Boolean {
        val previous = synchronized(lock) {
            if (source !== owner) return false
            val had = mqttLease
            mqttLease = lease
            mqttConnected = mqtt
            had
        }
        if (previous == null) return true
        // Outside the lock: retiring publishes, and publication reaches consumers.
        owner.onMqttChannelRetired()
        return true
    }

    /** Feed one birth/will observation — ignored unless [lease] is still the live broker generation. */
    fun observeMqtt(lease: MqttLease, event: HaLifecycleEvent) {
        currentForLease(lease)?.onMqttStatus(event)
    }

    /** The panel's own broker connection ended (validated, current-session) — lease-gated. */
    fun observeMqttChannelLost(lease: MqttLease) {
        currentForLease(lease)?.onMqttChannelLost()
    }

    /**
     * The coordinator this [lease] may report to, or null when the lease or its owner has been
     * superseded. A bridge outlives both the service that configured it and its own replacement, so a
     * queued callback from a predecessor's broker session would otherwise mutate whichever coordinator
     * happens to be installed when it finally runs — the same cross-generation write the socket side
     * already refuses.
     */
    private fun currentForLease(lease: MqttLease): HaLifecycleCoordinator? =
        synchronized(lock) { if (mqttLease === lease) source else null }

    /**
     * The one atomic rendered tuple, or null when nothing is reportable — either because no service
     * owns lifecycle tracking, or because no route is being watched at all. Both are the same fact to
     * a consumer: there is nothing to show. Returning a stale outage while watching is off is how a
     * native card survived its own feature being switched off and was redrawn from it on resume.
     *
     * Piecewise reads are deliberately not offered: two calls can straddle a transition — or a whole
     * ownership change — and render a combination that never existed.
     */
    fun snapshot(): HaLifecycle.Snapshot? {
        // One capture, then the foreign bridge read OUTSIDE the lock — invoking it under the lock is
        // how a lock-order cycle starts, which is the reason `watching` is written the same way. The
        // coordinator's own read is also taken outside, because a coordinator notifying consumers
        // takes its lock and then reaches a renderer that reads THIS holder; nesting the two in the
        // opposite order here would close that cycle.
        val (owner, socket, mqtt) = synchronized(lock) { Triple(source, socketWatching || nativeWatching, mqttConnected) }
        if (owner == null) return null
        if (!socket && !(mqtt?.invoke() ?: false)) return null
        val snapshot = owner.snapshot()
        // Validate AFTER: ownership can change while the reads above run, and answering with a
        // superseded owner's state is the same defect as writing through one.
        return synchronized(lock) { if (source === owner) snapshot else null }
    }

    /**
     * One short line for the panel's own status surfaces, or null when there is nothing to say. Never
     * includes an event payload or a Home Assistant error string.
     */
    fun statusText(): String? {
        // No separate `watching` test: an unreportable holder answers null from [snapshot] itself, so
        // the row cannot describe a watch that has been switched off.
        val snap = snapshot() ?: return null
        return when (snap.state) {
            HaLifecycleState.NORMAL -> idleText(snap.refused)
            HaLifecycleState.CONNECTION_LOST -> HaLifecycleMessage.text(snap) ?: idleText(snap.refused)
            else -> HaLifecycleMessage.text(snap) ?: idleText(snap.refused)
        }
    }

    /**
     * Deliberately says what is OBSERVED, never which route is covered.
     *
     * Enumerating live routes would mean acknowledgement accounting per subscription — machinery this
     * diagnostics string does not justify. A refusal is still reported, because it explains why the
     * socket route is quiet and is a single observed fact rather than a claim needing proof.
     */
    private fun idleText(refused: Boolean): String =
        if (refused) "watching; Home Assistant does not permit WebSocket lifecycle events for this user"
        else "watching"
}
