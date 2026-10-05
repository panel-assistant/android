package io.panelassistant.android.platform

/**
 * Touch-to-wake seam. When ha-paneld powers the screen off (bl_power / brightness-0) the device stays
 * interactive and the dashboard stays foreground — so ha-paneld can't see the wake tap directly. A real
 * [WakeTap] watches for any tap while the screen is dark and re-lights it, guaranteeing every screen-off
 * is locally wakeable (a screen-off with no wake path is what made a panel look bricked — see the
 * never-blank robustness work). Seamed so [io.panelassistant.android.control.ScreenController]'s
 * never-blank logic stays unit-testable without WindowManager.
 */
interface WakeTap {
    /**
     * Whether a guaranteed touch-wake can be armed right now (the overlay permission is held). When this
     * is false, ScreenController degrades a screen-off to a visible dim rather than risk an unwakeable
     * dark panel — there would be no local way back.
     */
    fun canArm(): Boolean

    /**
     * Start watching; [onTap] fires on the first tap while armed (may run on any thread). Returns only
     * after the watcher is installed, and returns false when installation cannot be confirmed. A caller
     * must not make the display dark unless this returns true.
     */
    fun arm(onTap: () -> Unit): Boolean

    /** Stop watching. Safe to call when not armed. */
    fun disarm()
}

/** No-op [WakeTap]: never arms. The default, so a ScreenController built without a real tap degrades
 *  safely (dim-instead-of-dark) rather than silently stranding the panel. */
object NoWakeTap : WakeTap {
    override fun canArm() = false
    override fun arm(onTap: () -> Unit) = false
    override fun disarm() {}
}
