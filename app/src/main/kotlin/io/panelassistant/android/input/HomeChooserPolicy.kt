package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.RendererResolver

/** The active window as the chooser answer reads it; [alwaysCentre] is null without an Always button. */
internal data class ChooserWindow(
    val packageName: CharSequence?,
    val title: CharSequence?,
    val alwaysEnabled: Boolean?,
    val alwaysCentre: Pair<Int, Int>?,
    val rows: List<ChooserRow> = emptyList(),
    val windowId: Int? = null,
)

internal data class ChooserRow(val appLabel: String, val activityLabel: String, val centre: Pair<Int, Int>)
internal data class ChooserHome(val activity: ActivityRef, val appLabel: String, val activityLabel: String)

/** Resolve the configured own HOME from package metadata, never an English chooser label or list order. */
internal fun configuredChooserHome(
    ownPackage: String, launcher: String, dashboard: String, builtinReady: Boolean, homes: List<ChooserHome>,
): ChooserHome? {
    val cls = when {
        launcher == ownPackage -> "$ownPackage.AdminLauncherActivity"
        builtinReady && RendererResolver.isBuiltinSelection(dashboard, ownPackage) -> "$ownPackage.DashboardActivity"
        else -> return null
    }
    val target = homes.singleOrNull { it.activity == ActivityRef(ownPackage, cls) } ?: return null
    // Android exposes labels, not components, in its tree. Refuse a label collision with any candidate.
    return target.takeIf { it.appLabel.isNotBlank() && it.activityLabel.isNotBlank() &&
        homes.count { home -> home.appLabel == it.appLabel && home.activityLabel == it.activityLabel } == 1 }
}

/** Minimal native tree seam shared by the accessibility adapter and recorded-tree tests. */
internal data class ChooserNode(
    val id: String?, val text: String?, val enabled: Boolean, val centre: Pair<Int, Int>?,
    val children: List<ChooserNode> = emptyList(),
) {
    fun all(): Sequence<ChooserNode> = sequence { yield(this@ChooserNode); children.forEach { yieldAll(it.all()) } }
}

internal fun readChooserWindow(packageName: CharSequence?, windowId: Int, root: ChooserNode): ChooserWindow {
    val nodes = root.all().toList()
    val always = nodes.filter { it.id == HomeChooserPolicy.ALWAYS_ID }.singleOrNull()
    val title = nodes.filter { it.id == HomeChooserPolicy.TITLE_ID }.singleOrNull()
    val list = nodes.filter { it.id == "android:id/resolver_list" }.singleOrNull()
    val rows = list?.children.orEmpty().mapNotNull { row ->
        val labels = row.all().toList()
        val app = labels.filter { it.id == "android:id/text1" }.singleOrNull()?.text
        val activity = labels.filter { it.id == "android:id/text2" }.singleOrNull()?.text
        if (!row.enabled || app == null || activity == null || row.centre == null) null
        else ChooserRow(app, activity, row.centre)
    }
    return ChooserWindow(packageName, title?.text, always?.enabled, always?.centre, rows, windowId)
}

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
 * A chooser it showed can be read before it is answerable, with Always disabled or no title yet, as
 * while its list loads (a Meta Portal, 2026-10-28: read 1.1 s after HOME was pressed, dismissed, and
 * HOME left on the chooser after the update). So it is looked at again every [LOOK_MS], at most [LOOKS] times per connection, and
 * dismissed only if it is still not answerable then.
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
    private val intendedHome: () -> ChooserHome? = { null },
) {
    private var shown = false
    private var acted = false
    private var retries = 0
    private var taps = 0
    private var looks = 0
    private var leftToInstaller = false
    private var selections = 0
    private var selected: Pair<ChooserHome, ChooserWindow>? = null

    fun connected() {
        shown = false
        acted = false
        retries = 0
        taps = 0
        looks = 0
        leftToInstaller = false
        selections = 0
        selected = null
    }

    fun answer() {
        val home = defaultHome()
        val window = activeWindow()
        if (acted && home?.pkg == "android" && window?.packageName != "android" && retries < RETRIES) {
            retries++
            shown = false
        }
        acted = false
        if (answerList(home, window)) return
        val step = HomeChooserPolicy.next(
            home, window?.packageName, window?.title, window?.alwaysEnabled, appName, shown, setupEverCompleted(),
        )
        when (step) {
            HomeChooserPolicy.Step.NONE -> Unit
            HomeChooserPolicy.Step.SHOW_CHOOSER -> {
                acted = showChooser()
                shown = acted
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

    /** A list is answered only after our own HOME press and our own successful, freshly verified selection. */
    private fun answerList(home: ActivityRef?, window: ChooserWindow?): Boolean {
        if (home?.pkg != "android" || window?.packageName != "android" || window.rows.isEmpty() ||
            window.title?.contains(appName) == true) {
            selected = null
            return false
        }
        val target = intendedHome()
        val row = window.rows.singleOrNull { it.appLabel == target?.appLabel && it.activityLabel == target?.activityLabel }
        if (setupEverCompleted() != true || target == null || row == null || window.alwaysEnabled == null) {
            selected = null
            log("HOME chooser list has no authorised unique configured activity; leaving it unanswered")
            return true
        }
        if (!shown) {
            // The list may already be in front at bind. Press HOME ourselves before trusting its selection.
            if (retries < RETRIES) {
                retries++
                acted = showChooser()
                shown = acted
                log("HOME is unowned; showing the configured HOME chooser=$acted")
                if (acted) answerAgainIn(VERIFY_MS)
            }
            return true
        }
        val previous = selected
        if (previous != null && previous.first == target && previous.second.rows == window.rows &&
            previous.second.windowId == window.windowId && window.alwaysEnabled == true) {
            if (taps < TAPS) {
                taps++
                val centre = window.alwaysCentre
                acted = centre != null && tap(centre.first, centre.second)
                log("HOME chooser selected ${target.activity.component}; tapped Always=$acted")
            }
        } else {
            selected = null
            // An already enabled list may contain somebody else's selection. Never confirm it.
            if (window.alwaysEnabled == false && selections < TAPS) {
                selections++
                acted = tap(row.centre.first, row.centre.second)
                if (acted) selected = target to window
                log("HOME chooser selecting ${target.activity.component}=$acted")
            } else log("HOME chooser list selection is unverified or exhausted; leaving it unanswered")
        }
        if (acted) answerAgainIn(VERIFY_MS)
        return true
    }

    private fun describe(window: ChooserWindow?): String = window?.let {
        val always = when (it.alwaysEnabled) { null -> "absent"; true -> "enabled"; false -> "disabled" }
        "window=${it.packageName} title=${it.title?.let { t -> "'$t'" }} always=$always"
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
