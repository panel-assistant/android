package io.github.maxlyth.hapaneld.control

import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.RendererResolver
import io.github.maxlyth.hapaneld.platform.ActivityRef
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.SystemEnv
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.HelperClient

/** Foreground/liveness state of the dashboard app, as seen by the app watchdog. */
enum class AppState { FG, BG, DEAD, UNKNOWN }

/** Fresh HOME/foreground evidence requested by an installer after a package replacement. */
data class HomeUiProof(val state: String, val reason: String, val evidence: String)

/**
 * Panel-level actions: reload the dashboard, bring a launcher / the dashboard to the foreground,
 * reboot.
 *
 * **Why these go through root, not `context.startActivity`:** ha-paneld is a foreground *service*,
 * and Android 10+ (API 29) blocks background activity starts from a service — so a direct
 * `startActivity` silently no-ops on every panel except the API 27 NSPanelPro. Launching via the
 * root helper daemon or `su` (`am start` / `monkey`) runs from a shell/root domain that BAL doesn't
 * restrict. We resolve the target *component* in-app (just a PackageManager query, always allowed)
 * and hand it to the privileged launcher. Pre-BAL panels fall back to a direct start.
 *
 * Collaborators are seamed — package/activity queries via [SystemEnv], privilege via [RootShell] /
 * [Daemon] — so the launcher-selection, default-home, and dashboard-state logic is unit-testable
 * without a device.
 */
class SystemController(
    private val env: SystemEnv,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    // Foreground state of the built-in renderer (our own activity) — seamed so the `builtin` dashboard
    // path stays unit-testable without loading an Android Activity. Defaults to the live process flag.
    private val builtinForeground: () -> Boolean = { BuiltinDashboard.foreground },
    // This is the real Log.w sink by default; the focused controller test observes the same emitted line.
    private val homeWarning: (String) -> Unit = { Log.w(TAG, it) },
    private val beforeReboot: () -> Unit = {},
    private val homeDashboard: () -> String = { "" },
    private val onCompanionHome: (String, String) -> Unit = { _, _ -> },
) {

    // Native descriptor checks run on every live transport wake. Reuse one bounded observation of the
    // executable routes; the production su probe stays isolated from the persistent control shell.
    private val privilegedRouteAvailable = Cached(60_000L) {
        daemon.available() || if (root === Su) Su.availableCachedIsolated() else root.available()
    }

    // Drift checks run repeatedly. Retain only the currently missing target so a recovered alias can
    // report again if it becomes unavailable later, without turning a steady state into log noise.
    private var missingHomeTarget: String? = null

    private enum class PrivilegedStartResult { STARTED, BLOCKED, FAILED }

    /** Launch an activity [component] ("pkg/cls") via the privileged path. BUSY is an explicit helper
     * safety boundary: never bypass it through su or an in-process background activity start. */
    private fun privilegedStart(component: String): PrivilegedStartResult {
        if (!AndroidInput.isComponent(component)) return PrivilegedStartResult.FAILED
        return when (daemon.send("START $component")) {
            "OK" -> PrivilegedStartResult.STARTED
            "BUSY" -> PrivilegedStartResult.BLOCKED
            else -> if (root.run("am start -n $component")) {
                PrivilegedStartResult.STARTED
            } else {
                PrivilegedStartResult.FAILED
            }
        }
    }

    /** The single foreground-launch mechanism behind the builtin/launcher/admin/home paths: start
     * [component] via [privilegedStart] and, only when that fails outright, fall back to a direct
     * (pre-BAL) start — then log the resolved target under [label]. A BLOCKED (helper BUSY) result
     * deliberately does NOT fall back: the daemon owns that safety boundary. */
    private fun launchComponent(component: String, label: String): Boolean {
        val started = when (privilegedStart(component)) {
            PrivilegedStartResult.STARTED -> true
            PrivilegedStartResult.BLOCKED -> false
            PrivilegedStartResult.FAILED -> env.directStart(component)
        }
        if (started) Log.i(TAG, "$label -> $component")
        return started
    }

    /** The helper opens our own foreground Activity; that Activity alone sends the Companion deep link. */
    fun launchCompanionHomeReturn(): Boolean =
        launchComponent(AppIdentity.component(env.ownPackage, ".CompanionHomeReturnActivity"), "Companion home return")

    /**
     * Start the other panel-app identity's launcher activity during the application-id migration.
     * Privileged routes only: a second package is never started from this app's own background.
     */
    fun launchPanelApp(applicationId: String): Boolean {
        if (!AppIdentity.isPanelApp(applicationId) || applicationId == env.ownPackage) return false
        return privilegedStart(AppIdentity.component(applicationId, ".MainActivity")) ==
            PrivilegedStartResult.STARTED
    }

    /** Open the local instruction surface; visibility is acknowledged separately by its session. */
    fun launchProximityWizard(): Boolean {
        val component = AppIdentity.component(env.ownPackage, ".ProximityWizardActivity")
        return when (privilegedStart(component)) {
            PrivilegedStartResult.STARTED -> true
            PrivilegedStartResult.BLOCKED -> false
            PrivilegedStartResult.FAILED -> runCatching { env.directStart(component) }.getOrDefault(false)
        }
    }

    /** True when [pkg] selects ha-paneld's own built-in WebView renderer rather than a foreign app.
     *  Our own package name is treated as the sentinel too: some callers resolve the renderer to a real
     *  package (e.g. for perf attribution), and letting it fall through to the foreign-app paths would
     *  `am force-stop` ha-paneld itself — killing the service, MQTT and the web UI. */
    private fun isBuiltin(pkg: String) = isBuiltinSelection(pkg, env.ownPackage)

    private fun isSystemFallbackHome(home: ActivityRef?): Boolean =
        home?.let { it.pkg == "com.android.settings" && it.cls.endsWith("FallbackHome") } == true

    /** Renderer-kind query for recovery policy routing. Resolution stays here so the watchdog cannot
     *  drift from launch/state handling for blank or own-package aliases. */
    internal fun isBuiltinDashboardTarget(dashboardPkg: String): Boolean =
        isBuiltin(resolveDashboard(dashboardPkg))

    /** Bring the built-in DashboardActivity up (or, if already running, to the foreground — a singleTask
     *  relaunch reaches onNewIntent, which only reloads when [BuiltinDashboard.requestExplicitReload] was set).
     *  Refuses while the renderer is crash-latched so the kiosk/watchdog return loops can't churn a
     *  crash-looping WebView; an explicit reload clears the latch first and always proceeds. */
    private fun startBuiltin(): Boolean {
        if (BuiltinDashboard.rendererLatched(SystemClock.elapsedRealtime())) {
            Log.w(TAG, "builtin renderer crash-latched — refusing automatic relaunch (explicit reload clears it)")
            return false
        }
        return launchComponent(AppIdentity.component(env.ownPackage, ".DashboardActivity"), "builtin dashboard")
    }

    /** Configured dashboard package, or the automatic built-in renderer for a blank selection. The
     * sentinel [BUILTIN_DASHBOARD] passes through unchanged. A corrupt non-blank stored value remains
     * invalid rather than being mistaken for Auto and crossing a privileged launch boundary. */
    fun resolveDashboard(pkg: String): String = when {
        pkg.isNotBlank() && !AndroidInput.isDashboardTarget(pkg) -> INVALID_DASHBOARD
        else -> RendererResolver.resolveControlPackage(pkg, env::isInstalled)
    }

    /** Force-stop the dashboard and relaunch it. [reason], when given, is announced on the panel first
     *  so a deliberate reset can never be mistaken for a crash. */
    fun reloadDashboard(dashboardPkg: String, reason: String = ""): Boolean {
        val pkg = resolveDashboard(dashboardPkg)
        if (CompanionDataOperationGate.blocks(pkg)) {
            Log.i(TAG, "dashboard reload suppressed while Companion data operation owns $pkg")
            return false
        }
        // Built-in renderer: an explicit reload clears any crash latch (deliberate retry consent), flags
        // the relaunch as reload-intent, and reaches onNewIntent → fresh page load.
        if (isBuiltin(pkg)) {
            BuiltinDashboard.navPath = null
            BuiltinDashboard.requestExplicitReload(reason)
            val started = startBuiltin()
            if (!started) BuiltinDashboard.consumeSupersededReload()
            return started
        }
        if (!AndroidInput.isPackage(pkg)) { Log.w(TAG, "reload: invalid or missing dashboard package"); return false }
        val daemonReply = daemon.send("RELOAD $pkg")
        if (daemonReply == "BUSY") {
            Log.i(TAG, "dashboard reload refused while helper owns Companion data")
            return false
        }
        val route = ShortOperationRouter.effect(
            EffectAttempt(PrivilegeRoute.DAEMON) { daemonReply == "OK" },
            EffectAttempt(PrivilegeRoute.SU) {
                if (!root.run("am force-stop $pkg")) return@EffectAttempt false
                val comp = env.launchComponent(pkg)
                if (comp == null) return@EffectAttempt root.run("monkey -p $pkg 1")
                when (privilegedStart(comp)) {
                    PrivilegedStartResult.STARTED -> true
                    PrivilegedStartResult.BLOCKED -> false
                    PrivilegedStartResult.FAILED -> root.run("monkey -p $pkg 1")
                }
            },
        )
        when (route) {
            PrivilegeRoute.DAEMON -> Log.i(TAG, "reload via daemon ($pkg)")
            PrivilegeRoute.SU -> Log.i(TAG, "reload via su fallback ($pkg)")
            else -> Log.w(TAG, "reload: helper and su both failed")
        }
        if (route != null) returnToCompanionHome(pkg)
        return route != null
    }

    /** A built-in reload uses this app; an external renderer needs one of the existing privileged routes. */
    fun canReloadDashboard(dashboardPkg: String): Boolean {
        val pkg = resolveDashboard(dashboardPkg)
        return isBuiltin(pkg) || AndroidInput.isPackage(pkg) && privilegedRouteAvailable.get()
    }

    /** The same helper and root routes [reboot] will try. */
    fun canReboot(): Boolean = privilegedRouteAvailable.get()

    /**
     * Bring a launcher (home screen) to the foreground — for panels with no physical home button.
     * [configuredPkg] forces a package; either panel-app identity opens our admin launcher. Blank
     * selects a real HOME launcher, excluding both panel apps and Settings. When nothing resolves (kiosk panels often have no
     * dedicated launcher app — the Companion registers HOME but is the dashboard, and the vendor
     * pseudo-launcher may be tamed/absent), falls back to our own admin launcher so the Launcher
     * key always lands somewhere; a stale configured package degrades the same way.
     */
    fun launchLauncher(configuredPkg: String) {
        val ri = pickLauncher(configuredPkg)
        if (ri == null) {
            Log.i(TAG, "launcher: none resolvable — opening the admin launcher")
            launchAdminLauncher()
            return
        }
        launchComponent(ri.component, "launcher")
    }

    /** The launcher package [launchLauncher] would land on for [configuredPkg] (query-only) — lets
     *  the config UI show what a blank ("auto") launcher_package actually resolved to. */
    fun resolvedLauncher(configuredPkg: String): String? = pickLauncher(configuredPkg)?.pkg

    private fun pickLauncher(configuredPkg: String): io.github.maxlyth.hapaneld.platform.ActivityRef? {
        // Our package exposes more than one HOME activity. PackageManager ordering is not a contract,
        // so either panel-app identity must never accidentally resolve to DashboardActivity.
        // It means the on-demand panel-admin app drawer, regardless of the order returned below.
        if (isAdminLauncherSelection(configuredPkg) || AppIdentity.isPanelApp(configuredPkg)) return adminLauncherActivity()
        val all = env.homeActivities()
        val default = env.defaultHome()?.pkg
        // Apps that register CATEGORY_HOME but are NOT an app-drawer launcher we'd want to land on:
        // either panel app, Settings, the HA Companion (a kiosk dashboard, which registers as HOME), and known
        // vendor kiosk pseudo-launchers (e.g. eWeLink's control panel on Sonoff panels) — arriving at
        // those obstructs the dashboard instead of giving the user an app drawer.
        val notALauncher = { p: String ->
            p == env.ownPackage || AppIdentity.isPanelApp(p) || p == "com.android.settings" ||
                p in RendererResolver.LEGACY_COMPANION_PACKAGE_SET ||
                p in VENDOR_PSEUDO_LAUNCHERS
        }
        return when {
            configuredPkg.isNotBlank() -> all.firstOrNull { it.pkg == configuredPkg }
            // Prefer the actual default home when it's a real launcher (e.g. the vendor launcher) — the old
            // code always skipped the default and grabbed the first alternate, which on kiosk panels is the
            // HA Companion (registers as HOME) → opened the dashboard instead of a launcher (the bug).
            default != null && !notALauncher(default) -> all.firstOrNull { it.pkg == default }
            // Default IS a kiosk/dashboard (or us): fall back to any other real launcher.
            else -> all.firstOrNull { !notALauncher(it.pkg) && it.pkg != default }
        }
    }

    /**
     * Open ha-paneld's own on-demand admin launcher (an app drawer for panel admin). The default for
     * the navbar Launcher button — replaces landing on the vendor pseudo-launcher. Reached by explicit
     * component, so it works even when no other launcher is installed.
     */
    fun launchAdminLauncher() {
        launchComponent(adminLauncherActivity().component, "admin launcher")
    }

    /** True when Launcher app explicitly selects ha-paneld's panel-admin app drawer. */
    fun isAdminLauncherSelection(configuredPkg: String): Boolean = configuredPkg == env.ownPackage

    private fun adminLauncherActivity() =
        io.github.maxlyth.hapaneld.platform.ActivityRef(
            env.ownPackage,
            AppIdentity.className(env.ownPackage, ".AdminLauncherActivity"),
        )

    /** Whether ha-paneld currently owns Android HOME. Android 14's HOME role is package-granular when
     * one package has multiple HOME activities and may resolve DashboardActivity even after accepting
     * AdminLauncherActivity. DashboardActivity routes real HOME intents to Panel admin when explicitly
     * selected, so package ownership is the durable enforcement condition across platform versions. */
    fun isAdminLauncherHome(): Boolean {
        val current = env.defaultHome() ?: return false
        return current.pkg == env.ownPackage
    }

    /** Force ha-paneld's admin launcher to be Android HOME. Unlike [ensureDashboardHome], this is an
     * explicit override policy: it deliberately reclaims HOME from vendor and third-party launchers.
     * The command is issued only on drift, making this safe for an immediate save, startup, and a
     * periodic vendor-firmware drift check. Returns true when HOME was already correct or the repair
     * command was accepted. */
    fun ensureAdminLauncherHome(): Boolean = ensureAdminLauncherHomeWithRoute(null)

    internal fun ensureAdminLauncherHome(admittedRoute: PrivilegeRoute): Boolean =
        ensureAdminLauncherHomeWithRoute(admittedRoute)

    private fun ensureAdminLauncherHomeWithRoute(admittedRoute: PrivilegeRoute?): Boolean {
        if (isAdminLauncherHome()) return true
        val comp = adminLauncherActivity().component
        Log.i(TAG, "ensureHome(admin): default home was '${env.defaultHome()?.component}' -> $comp")
        return setHomeActivity(comp, admittedRoute)
    }

    /** Apply the HOME policy associated with Launcher app. Explicit ha-paneld selection force-enforces
     * [AdminLauncherActivity]; every other value restores the existing automatic dashboard policy.
     * Returns true only for the explicit policy, allowing the service to arm/cancel its drift check. */
    fun applyLauncherHomePolicy(
        configuredLauncherPkg: String,
        dashboardPkg: String,
        builtinReady: Boolean = true,
    ): Boolean {
        if (isAdminLauncherSelection(configuredLauncherPkg)) {
            ensureAdminLauncherHome()
            return true
        }
        ensureDashboardHome(dashboardPkg, builtinReady)
        return false
    }

    /**
     * Set the default HOME (launcher) to [component] ("pkg/cls"). Daemon SETHOME, else su.
     *
     * `internal` rather than private because handing the role AWAY — to a re-enabled vendor launcher, so
     * ha-paneld can be removed without stranding the panel — goes through the same single chokepoint as
     * every reclaim. A second `set-home-activity` call site elsewhere would be a second definition of the
     * same privileged transition.
     */
    internal fun setHomeActivity(
        component: String,
        admittedRoute: PrivilegeRoute? = null,
    ): Boolean {
        if (!AndroidInput.isComponent(component)) return false
        // The ordered DAEMON→SU route definitions live once; the admitted-route path runs exactly the
        // one admitted attempt (never falling through to another transport), while the automatic path
        // tries them in order via the shared router.
        val attempts = arrayOf(
            EffectAttempt(PrivilegeRoute.DAEMON) { daemon.send("SETHOME $component") == "OK" },
            EffectAttempt(PrivilegeRoute.SU) { root.run("cmd package set-home-activity $component") },
        )
        if (admittedRoute != null) {
            return attempts.firstOrNull { it.route == admittedRoute }?.execute() ?: false
        }
        return ShortOperationRouter.effect(*attempts) != null
    }

    /**
     * Keep the selected foreign dashboard app as the default home.
     *
     * Our [AdminLauncherActivity] declares `CATEGORY_HOME` so it's a selectable / last-resort launcher.
     * The side effect: Android **clears the default-home association** whenever a package adds or changes
     * a HOME activity (i.e. every ha-paneld install/update) — after which pressing Home pops a chooser
     * instead of booting straight to the dashboard. So on boot we re-assert the dashboard app as the
     * default home when home is unowned (the system resolver or Settings FallbackHome), owned by *us*,
     * or still assigned to a supported Companion renderer ha-paneld previously selected. A deliberate
     * third-party launcher set as home is left alone. If the dashboard app isn't installed we do
     * nothing, leaving our admin launcher as the genuine last-resort home.
     */
    fun ensureDashboardHome(dashboardPkg: String, builtinReady: Boolean = true) {
        val target = resolveDashboard(dashboardPkg)
        // Only claim HOME for the built-in renderer once it's actually renderable (an HA URL is set) —
        // else DashboardActivity would be the home yet immediately hand off, churning HOME needlessly.
        if (isBuiltin(target)) { missingHomeTarget = null; if (builtinReady) ensureBuiltinHome(); return }
        if (target.isBlank()) { missingHomeTarget = null; Log.i(TAG, "ensureHome: no dashboard app installed; leaving home as-is"); return }
        val currentHome = env.defaultHome()
        val current = currentHome?.pkg
        if (current == target) { missingHomeTarget = null; return }     // already correct
        // Respect a real third-party launcher the user chose. A known Companion HOME is one ha-paneld
        // may previously have assigned, so switching between installed renderer variants must reclaim it.
        if (current != null && current != "android" && current != env.ownPackage &&
            current !in KNOWN_RENDERER_HOMES && !isSystemFallbackHome(currentHome)) {
            missingHomeTarget = null
            return
        }
        val comp = env.homeActivities().firstOrNull { it.pkg == target }?.component
        if (comp == null) {
            if (missingHomeTarget != target) homeWarning("ensureHome: $target has no HOME activity")
            missingHomeTarget = target
            return
        }
        missingHomeTarget = null
        Log.i(TAG, "ensureHome: default home was '$current' -> $comp")
        setHomeActivity(comp)
    }

    /** Make our built-in DashboardActivity the default home (parity with the Companion path): so the
     *  panel boots to it, the Home key returns to it, and it self-heals as a home app. Reclaims from an
     *  unowned ("android") resolver, Settings FallbackHome, ourselves, or a known dashboard renderer
     *  that ha-paneld itself set as home (the Companion — [ensureDashboardHome] made it the default
     *  on every existing panel, so switching to the built-in renderer must be able to take HOME back
     *  from it). A genuinely
     *  third-party launcher the user chose is still left alone. */
    private fun ensureBuiltinHome() {
        val comp = env.homeActivities().firstOrNull { it.pkg == env.ownPackage && it.cls.endsWith("DashboardActivity") }?.component
        if (comp == null) { Log.w(TAG, "ensureHome(builtin): DashboardActivity has no HOME activity"); return }
        val current = env.defaultHome()
        if (current?.component == comp) return                           // already correct
        val curPkg = current?.pkg
        if (curPkg != null && curPkg != "android" && curPkg != env.ownPackage &&
            curPkg !in KNOWN_RENDERER_HOMES && !isSystemFallbackHome(current)) return
        Log.i(TAG, "ensureHome(builtin): default home was '${current?.component}' -> $comp")
        setHomeActivity(comp)
    }

    /** Bring the dashboard (or the default home app) to the foreground. */
    fun launchHome(dashboardPkg: String) {
        val pkg = resolveDashboard(dashboardPkg)
        if (CompanionDataOperationGate.blocks(pkg)) {
            Log.i(TAG, "home launch suppressed while Companion data operation owns $pkg")
            return
        }
        if (isBuiltin(pkg)) { startBuiltin(); return }
        val comp = if (pkg.isNotBlank()) env.launchComponent(pkg) else env.defaultHome()?.component
        if (comp == null) { Log.w(TAG, "home: no target resolved"); return }
        if (launchComponent(comp, "home")) returnToCompanionHome(pkg)
    }

    private fun returnToCompanionHome(pkg: String) {
        RendererResolver.companionHomeRoute(pkg, homeDashboard())?.let { onCompanionHome(pkg, it) }
    }

    /**
     * Report the dashboard app's state for the watchdog: [AppState.FG] (alive + focused), [AppState.BG]
     * (alive but not focused), [AppState.DEAD] (no process), or [AppState.UNKNOWN] (can't tell — no
     * root and no daemon, or no dashboard installed). Prefers the daemon's `APPSTATE` verb; falls back
     * to `su` (`pidof` for liveness, `dumpsys window` focus for foreground). `pidof … ; true` keeps the
     * shell exit 0 so a *blank* reply means "dead" while a *null* reply means su itself was unavailable.
     */
    fun dashboardState(dashboardPkg: String): AppState {
        val pkg = resolveDashboard(dashboardPkg)
        // Built-in renderer lives in our always-running process: a direct lifecycle flag gives FG
        // (resumed) / BG (paused or not yet created) with no root/daemon probe — BG lets the kiosk/
        // watchdog return-loops recreate it via launchHome. Crash-latched (renderer crash-looping,
        // relaunch budget spent) reports DEAD: the kiosk loop ignores DEAD, while the watchdog bypasses
        // its foreign-app crash budget and projects this built-in latch directly into health.
        if (isBuiltin(pkg)) return when {
            BuiltinDashboard.rendererLatched(SystemClock.elapsedRealtime()) -> AppState.DEAD
            builtinForeground() -> AppState.FG
            else -> AppState.BG
        }
        if (!AndroidInput.isPackage(pkg)) return AppState.UNKNOWN
        return ShortOperationRouter.value(
            ValueAttempt(PrivilegeRoute.DAEMON) {
                when (daemon.send("APPSTATE $pkg")) {
                    "FG" -> AppState.FG
                    "BG" -> AppState.BG
                    "DEAD" -> AppState.DEAD
                    else -> null
                }
            },
            ValueAttempt(PrivilegeRoute.SU) {
                val pid = root.runOutput("pidof $pkg 2>/dev/null; true") ?: return@ValueAttempt null
                if (pid.isBlank()) return@ValueAttempt AppState.DEAD
                val focus = root.runOutput("dumpsys window 2>/dev/null | grep mCurrentFocus")
                    ?: return@ValueAttempt null
                if (focus.contains("$pkg/")) AppState.FG else AppState.BG
            }
        )?.value ?: AppState.UNKNOWN
    }

    /** A healthy service is not proof that the panel left Android's HOME chooser. */
    fun homeUiProof(dashboardPkg: String, adminUiVisible: Boolean): HomeUiProof {
        val home = env.defaultHome()
        if (home?.pkg == "android" || home?.cls?.endsWith("ResolverActivity") == true) {
            return HomeUiProof("blocked", "home_resolver", "home_resolve")
        }
        if (isSystemFallbackHome(home)) {
            return HomeUiProof("blocked", "home_fallback", "home_resolve")
        }
        if (adminUiVisible) return HomeUiProof("setup", "admin_foreground", "admin_lifecycle")
        if (home == null) return HomeUiProof("unknown", "home_unresolved", "home_resolve")
        val target = resolveDashboard(dashboardPkg)
        val builtin = isBuiltin(target)
        val expectedHome = if (builtin) env.ownPackage else target
        // Existing HOME policy preserves a deliberate foreign launcher, but reclaims our own
        // launcher or an old Companion renderer when the configured dashboard has changed.
        if (home.pkg != expectedHome && (home.pkg == env.ownPackage || home.pkg in KNOWN_RENDERER_HOMES)) {
            return HomeUiProof("blocked", "home_mismatch", "home_resolve")
        }
        val evidence = if (builtin) "builtin_lifecycle" else "dashboard_appstate"
        return when (dashboardState(dashboardPkg)) {
            AppState.FG -> HomeUiProof("ready", "dashboard_foreground", evidence)
            AppState.BG, AppState.DEAD -> HomeUiProof("blocked", "dashboard_background", evidence)
            AppState.UNKNOWN -> HomeUiProof("unknown", "dashboard_unobserved", evidence)
        }
    }

    /**
     * The package currently holding window focus, or null when it cannot be established.
     *
     * Only the `su` route exists here on purpose. The helper protocol has no foreground verb, and
     * adding one would mean shipping a new helper for a question asked rarely — see
     * [shouldKioskReturnToDashboard], which puts this question only at the instant something else is
     * already in front. A panel whose app cannot reach a privileged shell therefore answers null,
     * and null is not exempt: the lock keeps enforcing rather than silently switching itself off.
     *
     * Known consequence, recorded rather than hidden: the built-in renderer reports FG/BG from its
     * own lifecycle with no privilege at all, so on a panel running the built-in renderer where the
     * app cannot reach `su`, the return loop still works while this probe cannot answer, and a
     * configured companion is pulled back anyway. Note that reaching a root shell over adb does not
     * prove the app can — the two run as different uids, and a `su` binary restricted to the shell
     * group refuses an ordinary app at exec. Test the app's own path before assuming the exemption
     * is available on a given panel.
     */
    fun foregroundPackage(): String? = ShortOperationRouter.value(
        ValueAttempt(PrivilegeRoute.SU) {
            val focus = root.runOutput("dumpsys window 2>/dev/null | grep mCurrentFocus")
                ?: return@ValueAttempt null
            parseForegroundPackage(focus)
        }
    )?.value

    /**
     * Reboot the panel.
     *
     * `REBOOT AWAIT` answers only when the reboot demonstrably did not happen. A panel that goes down
     * closes the socket on its way, which arrives as [DaemonLongResult.Indeterminate]; an explicit
     * `ERR` is the one reply that proves the panel is still up, and it is the only thing that makes
     * the root route worth trying. That matters because the root route is not a duplicate of what the
     * daemon just tried: it runs from the app's own environment rather than the daemon's sanitized
     * one, and `svc power reboot` has been reported working in the first and silently doing nothing
     * in the second. A helper predating AWAIT ignores the argument and answers `OK` at once, which is
     * accepted as the best evidence that helper can give — so an older helper behaves exactly as
     * before rather than losing its reboot.
     */
    fun reboot(): Boolean {
        beforeReboot()
        val route = ShortOperationRouter.effect(
            EffectAttempt(PrivilegeRoute.DAEMON) {
                when (val outcome = daemon.sendLong("REBOOT AWAIT", REBOOT_AWAIT_TIMEOUT_MS)) {
                    is DaemonLongResult.Reply -> outcome.value == "OK"
                    DaemonLongResult.Indeterminate -> true
                    DaemonLongResult.NotSubmitted -> false
                }
            },
            EffectAttempt(PrivilegeRoute.SU) { root.fireAndForget("reboot") },
        )
        when (route) {
            PrivilegeRoute.DAEMON -> Log.i(TAG, "reboot via daemon")
            PrivilegeRoute.SU -> Log.i(TAG, "reboot via su fallback")
            else -> Log.w(TAG, "reboot: helper and su both unavailable, or neither could reboot the panel")
        }
        return route != null
    }

    companion object {
        private const val TAG = "ha-paneld/system"

        /** Must exceed the helper's whole bounded escalation, or a still-escalating daemon would be
         *  mistaken for one that already went down. */
        private const val REBOOT_AWAIT_TIMEOUT_MS = 15_000L

        /** Sentinel `dashboard_package` value selecting ha-paneld's own built-in WebView renderer
         *  ([io.github.maxlyth.hapaneld.DashboardActivity]) instead of a foreign dashboard app.
         *  Aliases [RendererResolver.BUILTIN] — the one home for the sentinel. */
        const val BUILTIN_DASHBOARD = RendererResolver.BUILTIN
        private const val INVALID_DASHBOARD = "<invalid-dashboard>"

        /** Canonical pure renderer-kind predicate shared by control and status projections. */
        internal fun isBuiltinSelection(pkg: String, ownPackage: String): Boolean =
            RendererResolver.isBuiltinSelection(pkg, ownPackage)

        /** Dashboard renderers ha-paneld itself may have set as the default home ([ensureDashboardHome]).
         *  A later supported-renderer or built-in selection may reclaim HOME from these; anything else as
         *  home is a deliberate third-party launcher and is left alone. The package identities live in
         *  [RendererResolver], sourced from [io.github.maxlyth.hapaneld.util.CompanionInstaller]. */
        internal val KNOWN_RENDERER_HOMES = RendererResolver.LEGACY_COMPANION_PACKAGE_SET +
            // The legacy identity of this app: a successor must be able to take HOME from a bridge that
            // retired without managing to hand it over.
            AppIdentity.LEGACY

        // Vendor kiosk apps that register CATEGORY_HOME but aren't real launchers — the navbar Launcher
        // button must never land on them (they obstruct the dashboard). eWeLink's control panel on
        // Sonoff/NSPanel Pro, and Shelly's Stargate on Wall Displays, whose screens offer no way on to
        // Android Settings; the admin launcher lists either as an app tile instead.
        private val VENDOR_PSEUDO_LAUNCHERS = setOf("com.eWeLinkControlPanel", "cloud.shelly.stargate")
    }
}

/**
 * Pull the focused package out of a `dumpsys window | grep mCurrentFocus` reply.
 *
 * The line reads `mCurrentFocus=Window{<hash> u0 <package>/<activity>}` when an activity holds focus.
 * Several shapes must return null rather than a wrong answer, because a wrong package here would
 * exempt the wrong app from the kiosk lock: `mCurrentFocus=null` between transitions, a bare
 * `mCurrentFocus=`, and system windows whose name carries no `package/activity` pair at all
 * (`NavigationBar0`, `StatusBar`). Anything not recognised with confidence is null, which the caller
 * treats as "keep enforcing".
 *
 * Every one of those shapes is rejected by the single `contains('/')` test. Earlier drafts also
 * guarded the empty and `null` values and the empty brace body explicitly; a mutation run showed
 * removing them changed no result on any of them, because a value with no `{` yields an empty body
 * and an empty body has no component. They were deleted rather than kept as reassurance, so every
 * remaining line here carries weight and a future edit to the `/` test cannot be masked by a guard
 * that looks protective but never fires.
 */
internal fun parseForegroundPackage(raw: String): String? {
    val line = raw.lineSequence().firstOrNull { it.contains("mCurrentFocus=") } ?: return null
    val inner = line.substringAfter("mCurrentFocus=").substringAfter('{', "").substringBefore('}')
    val component = inner.trim().split(' ').lastOrNull()?.takeIf { it.contains('/') } ?: return null
    return component.substringBefore('/').takeIf { it.isNotEmpty() }
}
