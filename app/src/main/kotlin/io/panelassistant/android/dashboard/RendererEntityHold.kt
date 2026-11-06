package io.panelassistant.android.dashboard

/**
 * What the built-in renderer's entity hold is doing, for the whole process: the one record the hold
 * screen, its time limit and the status routes share, so what Panel Assistant reads cannot disagree with
 * what the panel shows.
 *
 * No entity hold may keep a panel without its dashboard indefinitely (maintainer, 2026-10-28). A hold is
 * the right first answer while the panel learns which entities its dashboard uses, but every kind of it
 * has been seen stuck on hardware: a first scan that never answered, a scan Home Assistant refused, a
 * filter the web viewer could not install, a flagged rule nobody came to decide, and a bring-up race that
 * stopped the only thing that would have released it. So once a hold has been on screen for [BOUND_MS]
 * the renderer is released to the dashboard unfiltered, which is what the panel ran before entity
 * filtering existed, while learning keeps retrying behind it and Panel Assistant reports why. When the
 * cause clears, the dashboard is rebuilt filtered.
 *
 * Process-scoped and never persisted, like [AutoScopeVerification]: a recreated activity keeps the clock
 * already running rather than starting another full wait, and a new process starts afresh.
 */
internal object RendererEntityHold {
    /**
     * How long any entity hold may stand before the dashboard is shown unfiltered. Panel Assistant gives
     * an update 240 s from the start of its wait to see the new build rendered, and the install, the
     * process restart and the dashboard's own first render all come out of that; on the bench X2i an
     * update took 37 s from install to rendered. Ninety seconds of hold leaves room for a slower panel and
     * an unfiltered first load, and is the point at which the hold already stopped presenting progress.
     */
    const val BOUND_MS = 90_000L

    /** One hold: its kind's wire name, whether it has been released, since when, and what it failed on. */
    data class Snapshot(val kind: String, val released: Boolean, val sinceMs: Long, val key: String = "")

    @Volatile
    private var current: Snapshot? = null

    /** The hold on screen, or the release standing in for it; null when the renderer is not held. */
    fun snapshot(): Snapshot? = current

    /** The release in force, or null while the renderer is held or free. */
    val released: Snapshot? get() = current?.takeIf { it.released }

    /**
     * A hold screen of [kind] is being shown at [nowMs]. Returns when the hold started, which a redraw,
     * a change of kind or a recreated activity never moves.
     */
    @Synchronized
    fun holding(kind: String, nowMs: Long): Long {
        // A hold still unreleased long past its limit was left by an activity that went away while holding
        // (the renderer switched, the task killed); no timer was running for it, so this is a new hold.
        val since = current?.takeIf { it.released || nowMs - it.sinceMs < 2 * BOUND_MS }?.sinceMs ?: nowMs
        current = Snapshot(kind, released = false, sinceMs = since)
        return since
    }

    /** The bound passed: show the dashboard unfiltered. [key] names what a filter hold failed on. */
    @Synchronized
    fun release(kind: String, nowMs: Long, key: String) {
        current = Snapshot(kind, released = true, sinceMs = current?.sinceMs ?: nowMs, key = key)
    }

    /** The renderer was admitted without a release, or the released cause cleared. */
    @Synchronized
    fun ended() {
        current = null
    }
}
