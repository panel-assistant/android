package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef

/** The active window as the chooser answer reads it; [alwaysCentre] is null without an Always button. */
internal data class ChooserWindow(
    val packageName: CharSequence?,
    val title: CharSequence?,
    val alwaysEnabled: Boolean?,
    val alwaysCentre: Pair<Int, Int>?,
)

/**
 * One accessibility connection's answers to the HOME chooser, by [HomeChooserPolicy.next].
 *
 * The app's own start-up can bring the dashboard over the chooser after this service has shown it,
 * either before the chooser is answered or while the tap on Always is on its way, which then lands on
 * the dashboard (a Meta Portal, 2026-10-15). So [VERIFY_MS] after showing or tapping it looks again,
 * and while HOME is still unowned and the chooser is not in front it shows the chooser once more, at
 * most [RETRIES] times per connection. Always is tapped at most [TAPS] times per connection, so a
 * chooser whose Always never takes is not pressed forever.
 *
 * A chooser it showed can be read before its list has loaded, with Always disabled and no title (a Meta
 * Portal, 2026-10-28: read 1.1 s after HOME was pressed, dismissed, and HOME left on the chooser after
 * the update). So it is looked at again every [LOOK_MS], at most [LOOKS] times per show, and dismissed
 * only if it is still not answerable then.
 */
internal class HomeChooserAnswer(
    private val appName: String,
    private val defaultHome: () -> ActivityRef?,
    private val setupEverCompleted: () -> Boolean?,
    private val activeWindow: () -> ChooserWindow?,
    private val showChooser: () -> Boolean,
    private val dismiss: () -> Boolean,
    private val tap: (Int, Int) -> Boolean,
    private val answerAgainIn: (Long) -> Unit,
    private val log: (String) -> Unit,
) {
    private var shown = false
    private var acted = false
    private var retries = 0
    private var taps = 0
    private var looks = 0
    private var leftToInstaller = false

    fun connected() {
        shown = false
        acted = false
        retries = 0
        taps = 0
        looks = 0
        leftToInstaller = false
    }

    fun answer() {
        val home = defaultHome()
        val window = activeWindow()
        if (acted && home?.pkg == "android" && window?.packageName != "android" && retries < RETRIES) {
            retries++
            shown = false
        }
        acted = false
        val step = HomeChooserPolicy.next(
            home, window?.packageName, window?.title, window?.alwaysEnabled, appName, shown, setupEverCompleted(),
        )
        when (step) {
            HomeChooserPolicy.Step.NONE -> Unit
            HomeChooserPolicy.Step.SHOW_CHOOSER -> {
                shown = true
                looks = 0
                acted = showChooser()
                log("HOME is unowned; showing the chooser=$acted")
            }
            HomeChooserPolicy.Step.LEAVE_TO_INSTALLER -> if (!leftToInstaller) {
                leftToInstaller = true
                log("HOME is unowned before first setup; leaving it to the installer")
            }
            HomeChooserPolicy.Step.TAP_ALWAYS -> if (taps < TAPS) {
                taps++
                val centre = window?.alwaysCentre
                acted = centre != null && tap(centre.first, centre.second)
                log("HOME chooser offers this app; tapped Always=$acted")
            }
            HomeChooserPolicy.Step.LOOK_AGAIN -> if (looks < LOOKS) {
                looks++
                // Like a show: a dashboard that covers the chooser meanwhile is answered by showing it again.
                acted = true
                log("HOME chooser this service showed is not answerable yet (${describe(window)}); look $looks of $LOOKS")
            } else {
                log("HOME chooser this service showed never became answerable (${describe(window)}); dismissed=${dismiss()}")
            }
            HomeChooserPolicy.Step.DISMISS ->
                log("HOME chooser this service showed is not answered here (${describe(window)}); dismissed=${dismiss()}")
        }
        // Once HOME is owned the chooser this service showed has been answered or dismissed, so a later
        // resolver ("Open with", say) on this connection is not taken for it.
        if (home?.pkg != "android") shown = false
        if (acted && step == HomeChooserPolicy.Step.LOOK_AGAIN) {
            answerAgainIn(LOOK_MS)
        } else if (acted && (retries < RETRIES || taps < TAPS)) {
            answerAgainIn(VERIFY_MS)
        }
    }

    private fun describe(window: ChooserWindow?): String = window?.let {
        val always = when (it.alwaysEnabled) { null -> "absent"; true -> "enabled"; false -> "disabled" }
        "window=${it.packageName} title='${it.title}' always=$always"
    } ?: "no window"

    companion object {
        const val VERIFY_MS = 2000L
        const val RETRIES = 2
        const val TAPS = 3
        const val LOOK_MS = 750L
        const val LOOKS = 4
    }
}

/**
 * **Answering Android's HOME chooser.** Updating the app in place makes some firmware (Meta's Portal)
 * forget the preferred HOME, and the next HOME shows "Use Panel Assistant as Home — Just once / Always".
 * A panel without root or the helper cannot set HOME again, so the accessibility service presses Always,
 * which is the setup the owner already chose (maintainer, 2026-10-12).
 *
 * The rule is Panel Assistant's ADB `home_chooser.py`, ported: while HOME is unowned, show the chooser
 * if another window covers it (only once the panel has been set up, because before that the installer
 * is claiming HOME), tap Always only when its title names this app (the app name is not translated, so
 * this matches in any language), and dismiss a chooser it showed itself that offers another app or
 * that is still in front once HOME is owned. A chooser it showed that is not answerable yet (Always
 * disabled, or no title: its list is still loading) is looked at again rather than dismissed. A chooser
 * that appeared on its own and offers another app is left alone.
 */
internal object HomeChooserPolicy {
    const val ALWAYS_ID = "android:id/button_always"
    const val TITLE_ID = "android:id/title"

    enum class Step { NONE, SHOW_CHOOSER, LEAVE_TO_INSTALLER, TAP_ALWAYS, LOOK_AGAIN, DISMISS }

    /**
     * What to do with the active window. [alwaysEnabled] is null when the window has no Always button.
     * [shown] is whether this connection already pressed HOME to show the chooser: once per connection,
     * because the app's own start-up brings the dashboard over a chooser that appeared during an update.
     * [setupEverCompleted] is false until the panel's first setup, and null while it is still being read.
     * Until then the installer is claiming HOME: on a fresh install HOME is unowned from the moment the
     * app is installed until the installer sets it, and a chooser shown in between offers the vendor
     * launcher to whoever is at the panel (Shelly X2i, 2026-10-15). Nothing is shown before that.
     */
    fun next(
        home: ActivityRef?,
        windowPackage: CharSequence?,
        title: CharSequence?,
        alwaysEnabled: Boolean?,
        appName: String,
        shown: Boolean,
        setupEverCompleted: Boolean?,
    ): Step = when {
        // Owned HOME resolves to an app; unowned HOME resolves to the chooser itself, `android/…ResolverActivity`.
        // A chooser this connection showed is not left in front once something else made HOME owned.
        home?.pkg != "android" -> if (shown && windowPackage == "android" && alwaysEnabled != null) Step.DISMISS else Step.NONE
        windowPackage != "android" -> when {
            shown -> Step.NONE
            setupEverCompleted != true -> Step.LEAVE_TO_INSTALLER
            else -> Step.SHOW_CHOOSER
        }
        alwaysEnabled == null -> Step.NONE
        alwaysEnabled && title?.contains(appName) == true -> Step.TAP_ALWAYS
        shown && (!alwaysEnabled || title.isNullOrBlank()) -> Step.LOOK_AGAIN
        shown -> Step.DISMISS
        else -> Step.NONE
    }

    /**
     * Android keeps an accessibility service's capabilities from when its connection was made, so the
     * update that adds window-content retrieval only gets it at the next bind (a reboot, or a
     * re-enable). Returns the enabled-services value without this service when it should re-enable
     * itself once to pick the capability up, or null when it already has it or is not listed.
     */
    fun rebindEntries(enabled: String?, pkg: String, serviceClass: String, capabilities: Int): String? {
        if (enabled == null || capabilities and CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0) return null
        val entries = enabled.split(':').filter { it.isNotBlank() }
        val others = entries.filterNot { entry ->
            val cls = entry.substringAfter('/', "")
            entry.substringBefore('/') == pkg && (if (cls.startsWith('.')) pkg + cls else cls) == serviceClass
        }
        return if (others.size < entries.size) others.joinToString(":") else null
    }

    /** `AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT`. */
    const val CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT = 1
}
