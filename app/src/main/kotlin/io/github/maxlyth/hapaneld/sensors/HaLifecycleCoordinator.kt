package io.github.maxlyth.hapaneld.sensors

/**
 * Turns Panel Assistant's lifecycle notices and connection signals into [HaLifecycle] observations
 * and pushes the rendered state at one listener.
 *
 * Pure apart from the injected clock: it owns no socket, thread or timer.
 */
internal class HaLifecycleCoordinator(
    private val lifecycle: HaLifecycle = HaLifecycle(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onChanged: () -> Unit = {},
) {
    private val lock = Any()
    private var published = HaLifecycle.Snapshot(HaLifecycleState.NORMAL, null, false, 0L, 0L)

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
        lifecycle.onDisconnected(now)
        publish(now)
    }

    fun onNativeRetired() {
        val now = nowMs()
        lifecycle.onNativeRetired(now)
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
     * [published] is a delivery de-duplicator, NOT a second copy of the state — nothing reads it as
     * truth, and the notification carries NO payload. Consumers read the canonical state themselves,
     * so an out-of-order poke is harmless: whoever runs last still reads the current value.
     *
     * Two rules make it sound under concurrent connection callbacks. The snapshot is captured by
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
        candidate.reason != published.reason || candidate.expectedMs != published.expectedMs ||
        candidate.elapsedMs != published.elapsedMs)
