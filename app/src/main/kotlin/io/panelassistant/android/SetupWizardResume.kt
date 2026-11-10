package io.panelassistant.android

/** The panel's own setup wizard, as [MainActivity] opens it before setup first completes. */
internal const val SETUP_WIZARD_PATH = "/setup"

/**
 * Whether the panel's own setup wizard should come back on its own when the dashboard is held on its setup
 * question ([DashboardEntityGate.showWaitingForEntityFilterAnswer]).
 *
 * Two things bring the dashboard over a wizard that still has questions to ask: the on-panel Home Assistant
 * sign-in, which the dashboard shows itself, and every settings change the wizard makes that relaunches the
 * built-in renderer (the sign-in's credential, "Use this dashboard"'s home). Before this, the panel then
 * stopped on "One setup question is waiting" and needed a Set up tap, sometimes two, to carry on (fresh
 * installs, 2026-11-09). Armed by those two moments, cleared once the wizard is back on screen, and cleared
 * for good when the person leaves the wizard themselves, so Back from it reaches the question screen and its
 * Skip rather than the wizard again.
 *
 * Process-wide and main-thread only: the activities that arm and read it all run on the main thread.
 */
internal object SetupWizardResume {
    private var armed = false

    /** The on-panel sign-in finished, or the wizard was covered without the person leaving it. */
    fun arm() { armed = true }

    /** The wizard is on screen again, or the person left it, or setup no longer needs it. */
    fun clear() { armed = false }

    fun armed(): Boolean = armed
}
