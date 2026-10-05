package io.panelassistant.android.sensors

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
    @Volatile private var nativeWatching = false

    val watching: Boolean get() = nativeWatching

    /** Install the current service's native lifecycle coordinator. */
    fun install(next: HaLifecycleCoordinator) {
        synchronized(lock) {
            source = next
            nativeWatching = false
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
        nativeWatching = false
        true
    }

    fun setNativeWatching(owner: HaLifecycleCoordinator, next: Boolean): Boolean = synchronized(lock) {
        if (source !== owner) return false
        val changed = nativeWatching != next
        nativeWatching = next
        changed
    }

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
        val (owner, watched) = synchronized(lock) { source to nativeWatching }
        if (owner == null || !watched) return null
        val snapshot = owner.snapshot()
        // Validate AFTER: ownership can change while the reads above run, and answering with a
        // superseded owner's state is the same defect as writing through one.
        return synchronized(lock) { if (source === owner && nativeWatching) snapshot else null }
    }

    /**
     * One short line for the panel's own status surfaces, or null when there is nothing to say. Never
     * includes an event payload or a Home Assistant error string.
     */
    fun statusText(): String? {
        // No separate `watching` test: an unreportable holder answers null from [snapshot] itself, so
        // the row cannot describe a watch that has been switched off.
        val snap = snapshot() ?: return null
        return HaLifecycleMessage.text(snap) ?: "watching"
    }
}
