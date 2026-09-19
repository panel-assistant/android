package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.config.TamePackagePolicy
import io.github.maxlyth.hapaneld.device.TameCandidate
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.persistence.commitWithDurableVisibility
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * **Taming intrusive vendor packages.** A firmware update can leave a vendor app that relaunches on
 * boot and even draws a floating widget over the dashboard (seen on the NSPanel Pro 120P after a Sonoff
 * update: `com.eWeLinkControlPanel`). This controller, driven by the opt-in
 * [io.github.maxlyth.hapaneld.Config.tameVendorPackages] blocklist, neutralises such a package by:
 *
 *  1. **force-stop** — kill it now (`am force-stop`),
 *  2. **disable-user** — stop it relaunching on boot (`pm disable-user --user 0`, reversible), and
 *  3. **overlay deny** — strip its floating-window permission (`appops set … SYSTEM_ALERT_WINDOW deny`).
 *
 * Everything is **privileged** (root), so it routes through the helper daemon (`STOP`/`DISABLE`/`OVERLAY`)
 * — which covers sandbox-walled panels — and falls back to `su` where the app can reach it. Everything is
 * **reversible** (rollback re-enables + restores the exact prior overlay app-op), and **critical system
 * packages are never touched**: a brick-guard here AND in the daemon refuses the system UI, Settings,
 * telephony, the framework, and ha-paneld itself. Only installed packages are acted on. The feature is
 * **off by default** — the blocklist is empty unless the user deliberately populates it.
 */
class TameController(
    private val context: Context,
    // Invoked right after [reassertTame] disables a package that WAS the current default home (a vendor kiosk
    // like eWeLink left as home). The caller re-asserts the dashboard as the default home AND foregrounds
    // it — otherwise disabling the vendor home strands the panel on ha-paneld's admin launcher until the
    // 300s "dashboard backgrounded" watchdog eventually returns to it (a ~5-minute blank-to-dashboard gap
    // seen on boot). Default no-op so plain construction (tests / non-boot use) is unaffected.
    private val onDefaultHomeDisabled: () -> Unit = {},
) {
    private val state = AppState.preferences(context, "controller-state", STATE_PREFS)
    private val desiredStateReconciler = TameDesiredStateReconciler(
        readOwned = ::readOwnedMarkers,
        observePackages = ::observePackages,
        reassert = ::reassertTame,
        restore = ::restoreOwned,
        clearAbsent = ::clearAbsentMarker,
    )

    /** Reconcile desired state against the existing write-ahead overlay restoration records. */
    internal fun reconcileBlocklist(packages: Set<String>): TameReconcileResult =
        desiredStateReconciler.reconcile(packages)

    /** Installed, safe recommended selections; mutation remains owned by [reconcileBlocklist]. */
    fun recommendedSelections(profileCandidates: List<TameCandidate>): List<String> {
        val recommended = profileCandidates.asSequence()
            .filter(TameCandidate::defaultTame)
            .map(TameCandidate::pkg)
            .distinct()
            .sorted()
            .toList()
        val observed = observePackages(recommended.toSet())
        return recommended.filter {
            observed[it] == TamePackageObservation(TamePackagePresence.PRESENT, TamePackageSafety.SAFE)
        }
    }

    /** One row in the Vendor-packages UI: a package, its label + current state, whether it's tamed, and
     *  (for profile-curated packages) the author's [tags] and [note] explaining what it is. */
    data class Candidate(
        val pkg: String,
        val label: String,
        val installed: Boolean,
        val disabled: Boolean,
        val blocked: Boolean,
        val tags: List<String> = emptyList(),
        val note: String = "",
        // false for packages that must not be disabled (core Android, the dashboard, ourselves) — shown for
        // context (e.g. a heavy core process) but with no Tame button.
        val removable: Boolean = true,
        // A profile `defaultTame` pick — badged as "recommended" and covered by "Tame all recommended".
        val recommended: Boolean = false,
    )

    // Heuristic tags for a DISCOVERED package (one with no profile-authored metadata), so a non-engineer
    // can tell core Android apart from firmware/vendor apps and user installs at a glance.
    private fun autoTags(pkg: String, ai: ApplicationInfo?): List<String> {
        val t = mutableListOf<String>()
        when {
            isCoreAosp(pkg) -> t += "core"
            ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 -> t += "vendor"   // firmware-baked
            else -> t += "user"
        }
        if (hasOverlay(pkg)) t += "overlay"
        return t
    }

    /**
     * The Vendor-packages CARD list: the currently-tamed [blocked] packages, each annotated with its
     * profile [profileMeta] (tags + note) when one exists, so a tamed entry still explains what it is.
     * Installed (incl. disabled) first, then by label.
     */
    fun cardCandidates(blocked: List<String>, profileMeta: List<TameCandidate>): List<Candidate> {
        val blockedSet = blocked.toSet()
        val apps = installedApps()
        val meta = profileMeta.associateBy { it.pkg }
        return blocked.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { toCandidate(it, apps[it], blockedSet, meta[it]) }
            .sortedWith(compareByDescending<Candidate> { it.installed }.thenBy { it.label.lowercase() })
            .toList()
    }

    /** Every profile candidate with its current state (installed/disabled/tags), for the /diag report —
     *  includes not-installed entries (a maintainer wants to see the full known set vs what's present). */
    fun profileReport(profileCandidates: List<TameCandidate>): List<Candidate> {
        val apps = installedApps()
        return profileCandidates.map { toCandidate(it.pkg, apps[it.pkg], emptySet(), it) }
    }

    /** A labelled section of the picker pop-up. */
    data class Group(val title: String, val hint: String, val items: List<Candidate>)

    /**
     * The picker pop-up content, grouped for a non-expert: **(1) Recommended** — the panel profile's
     * known-bad [profileCandidates]; **(2) Other apps** — third-party apps that aren't core Android (the
     * launcher/overlay heuristic); **(3) Using the most CPU** — the current top processes ([resourceNames],
     * full cmdlines) mapped to packages. A package appears in only the first group it qualifies for.
     * [exclude] (the already-tamed blocklist shown in the card), the launcher, the IME, and the untouchables
     * (critical / HA / ourselves) are never offered. Empty groups are dropped.
     */
    fun suggestionGroups(profileCandidates: List<TameCandidate>, exclude: Set<String>, resourceNames: List<String>): List<Group> {
        val meta = profileCandidates.associateBy { it.pkg }
        // Home launchers we must NOT offer to disable — but ONLY the protected ones (the Companion
        // dashboard, or every home when ha-paneld isn't a fallback home). A vendor kiosk that just
        // registers HOME (eWeLink) IS offered, since ha-paneld's admin launcher keeps the panel homed.
        // Plus the active IME.
        val homes = homeLaunchers()
        val ownIsHome = context.packageName in homes
        val protectedHomes = if (ownIsHome) homes.filter { it == context.packageName || it in HA_PACKAGES }.toSet() else homes
        val ime = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }.getOrNull()
        val nav = protectedHomes + setOfNotNull(ime)   // protected launchers + IME — never offer to disable
        val apps = installedApps()
        val seen = exclude.toMutableSet()
        fun take(src: List<String>, extra: (String) -> Boolean = { true }): List<Candidate> {
            val picks = src.asSequence().map { it.trim() }
                // installed-only: a non-engineer should only ever see apps actually on THIS firmware, so a
                // profile recommendation absent from this build is silently dropped (no "not installed" rows).
                .filter { it.isNotEmpty() && it !in seen && it !in nav && !isUntouchable(it) && it in apps &&
                    (apps[it]?.let { ai -> (ai.flags and ApplicationInfo.FLAG_PERSISTENT) == 0 } ?: true) && extra(it) }
                .distinct().toList()
            seen += picks
            return picks.map { toCandidate(it, apps[it], exclude, meta[it]) }
                .sortedWith(compareByDescending<Candidate> { it.installed }.thenBy { it.label.lowercase() })
        }
        val recommended = take(profileCandidates.map { it.pkg })
        val others = take(enumerate())
        // "Using the most CPU" keeps the protected heavy hitters (the dashboard WebView, HA, core Android)
        // visible — marked non-removable — so the user sees WHERE the CPU goes, not a misleading "nothing
        // here". CPU order is preserved (most-active first); only a tameable vendor process gets a button.
        val resourcePkgs = resourceNames.map { it.substringBefore(':').trim() }.filter { it.contains('.') }
        val heavy = resourcePkgs.asSequence()
            .filter { it.isNotEmpty() && it !in seen && it !in nav && it in apps }
            .distinct().toList()
            .map { pkg ->
                val persistent = apps[pkg]?.let { (it.flags and ApplicationInfo.FLAG_PERSISTENT) != 0 } ?: false
                val protectedPkg = isUntouchable(pkg) || isCoreAosp(pkg) || persistent
                toCandidate(pkg, apps[pkg], exclude, meta[pkg]).copy(removable = !protectedPkg)
            }
        seen += heavy.map { it.pkg }
        // All three sections are returned (even when empty) so the picker always shows its structure —
        // the empty-state is informative ("no recommendations / nothing heavy right now").
        return listOf(
            Group("Recommended for this panel", "Known intrusive firmware apps for your hardware — safe first picks.", recommended),
            Group("Other apps", "Apps on this panel that aren't part of core Android.", others),
            Group("Using the most CPU", "Top CPU users right now. Core/system ones are shown for context but can't be disabled; only tame a vendor app you recognise.", heavy),
        )
    }

    // All installed apps (enabled, disabled, disabled-until-used) keyed by package — the RELIABLE presence
    // source. Per-package getApplicationInfo() is not: it returns null for a disabled PRIVILEGED system app
    // (e.g. a tamed `com.smatek.*`) even though the app is installed, which would wrongly hide it. A single
    // getInstalledApplications() snapshot lists them all, so resolve presence/label from this map instead.
    private fun installedAppsOrNull(): Map<String, ApplicationInfo>? = try {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        context.packageManager.getInstalledApplications(flags).associateBy { it.packageName }
            .takeIf { TameStatePolicy.packageInventoryCredible(it.keys, context.packageName) }
            .also {
                if (it == null) Log.w(TAG, "installed-package snapshot omitted ha-paneld; treating it as unknown")
            }
    } catch (error: Throwable) {
        Log.w(TAG, "could not observe installed packages", error)
        null
    }

    /** UI callers may render an empty snapshot; mutation callers use [observePackages] and retain UNKNOWN. */
    private fun installedApps(): Map<String, ApplicationInfo> = installedAppsOrNull().orEmpty()

    private fun toCandidate(pkg: String, ai: ApplicationInfo?, blocked: Set<String>, meta: TameCandidate? = null): Candidate {
        val pm = context.packageManager
        val label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?.takeIf { it != pkg } ?: pkg
        val disabled = ai != null && runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull()
            ?.let { it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER } ?: false
        // Profile-authored tags win; otherwise derive them from heuristics so discovered packages still
        // carry "core / vendor / user / overlay" context.
        return Candidate(pkg, label, installed = ai != null, disabled = disabled, blocked = pkg in blocked,
            tags = meta?.tags ?: autoTags(pkg, ai), note = meta?.note ?: "", recommended = meta?.defaultTame == true)
    }

    // Discovery: a package is a candidate if it (a) has a launcher activity or holds the overlay
    // permission — an app "with a face" or one that can draw over the dashboard — AND (b) is not in the
    // core-AOSP namespace. The namespace gate is the load-bearing filter: vendor bloat lives in vendor
    // namespaces (com.eWeLink*, com.rockchip*, com.smatek*, org.fdroid, …), never com.android.* — whereas
    // many core platform packages (telecom, the settings/telephony providers, …) DO hold the overlay
    // permission, and taming one of those would brick the panel. So com.android.* / com.google.android.*
    // are excluded outright; a headless vendor service won't appear either, but the free-text box covers
    // both rare cases. The current home launcher and IME are excluded so the picker can't suggest
    // disabling the very things the user navigates with.
    private fun enumerate(): List<String> {
        val pm = context.packageManager
        val launchers = runCatching {
            // MATCH_DISABLED_COMPONENTS so an already-disabled vendor app's launcher still resolves —
            // otherwise the picker can't surface it for re-enabling (its activity is hidden by default).
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val home = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        }.getOrNull()
        val ime = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }.getOrNull()
        val skip = setOfNotNull(home, ime)
        return runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .map { it.packageName }
            .filter { pkg ->
                !isUntouchable(pkg) && !isCoreAosp(pkg) && pkg !in skip &&
                    (pkg in launchers || hasOverlay(pkg))
            }
    }

    // Core AOSP / GMS namespaces — excluded from discovery (see [enumerate]). Vendor bloat is never here.
    private fun isCoreAosp(pkg: String): Boolean =
        pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.")

    private fun hasOverlay(pkg: String): Boolean = runCatching {
        context.packageManager.checkPermission("android.permission.SYSTEM_ALERT_WINDOW", pkg) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun isUntouchable(pkg: String): Boolean =
        isCritical(pkg) || pkg == context.packageName || pkg in HA_PACKAGES

    /**
     * The brick-guard: packages that must NEVER be disabled. Beyond the hardcoded AOSP-name set
     * ([isUntouchable]), this catches vendor-RENAMED criticals by ROLE so a user can't brick the panel by
     * typing one into the free-text box: any FLAG_PERSISTENT system service (e.g. a vendor SystemUI such as
     * `com.smartos.xinch.systemui` on the TPA10 — the name-based guard wouldn't catch it), protected home
     * launchers, and the current IME. Failed role/package observation is UNKNOWN and therefore fail-closed.
     */
    fun isProtected(pkg: String): Boolean =
        observePackages(setOf(pkg))[pkg]?.safety != TamePackageSafety.SAFE

    /** Every HOME-registered package (incl. disabled); UI callers degrade to an empty observation. */
    private fun homeLaunchers(): Set<String> = homeLaunchersOrNull().orEmpty()

    private fun homeLaunchersOrNull(): Set<String>? = try {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).map { it.activityInfo.packageName }.toSet()
    } catch (error: Throwable) {
        Log.w(TAG, "could not observe home launchers", error)
        null
    }

    /** The package that currently owns the default home role, or null. */
    private fun currentDefaultHome(): String? = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
    }.getOrNull()

    // ── Handing the home screen back ───────────────────────────────────────────────────────────────

    /** The write-ahead ownership snapshot, for the hand-back decision. */
    internal fun ownedMarkerSnapshot(): TameOwnedMarkers = readOwnedMarkers()

    /**
     * Presence plus the *reason* a package is disabled.
     *
     * `COMPONENT_ENABLED_STATE_DISABLED_USER` is what `pm disable-user` produces, so it is what ha-paneld
     * and the provisioner leave behind; `COMPONENT_ENABLED_STATE_DISABLED` is typically a package the
     * firmware shipped switched off. Collapsing the two into one "disabled" Boolean — which is what
     * [toCandidate] does for the UI, where it does not matter — would let hand-back enable vendor apps that
     * were never ha-paneld's to touch. An unreadable state stays null and refuses.
     */
    internal fun handBackPackageStates(packages: Set<String>): Map<String, HandBackHomePolicy.PackageState> {
        val apps = installedAppsOrNull() ?: throw IllegalStateException("installed-package inventory unavailable")
        val pm = context.packageManager
        return packages.associateWith { pkg ->
            val present = pkg in apps
            val disabledByUser = if (!present) null else runCatching {
                pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }.getOrNull()
            HandBackHomePolicy.PackageState(present, disabledByUser)
        }
    }

    /**
     * Every package declaring `CATEGORY_HOME`, including disabled ones, with the component to hand the role
     * to. Null when the query failed — a hand-back must never read that as "no launcher exists".
     */
    internal fun handBackHomeCandidates(): List<HandBackHomePolicy.HomeCandidate>? = try {
        val pm = context.packageManager
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).map { resolved ->
            val pkg = resolved.activityInfo.packageName
            val enabled = runCatching {
                pm.getApplicationEnabledSetting(pkg)
            }.getOrNull().let {
                it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            HandBackHomePolicy.HomeCandidate(pkg, "$pkg/${resolved.activityInfo.name}", enabled)
        }.distinctBy(HandBackHomePolicy.HomeCandidate::pkg)
    } catch (error: Throwable) {
        Log.w(TAG, "could not enumerate home launchers for hand-back", error)
        null
    }

    /** Roll every ownership marker back, exactly as removing a package from the desired set would. */
    internal fun restoreEveryOwnedPackage() {
        reconcileBlocklist(emptySet())
    }

    /**
     * Adopt and re-enable a package the panel's profile authorises but holds no marker for.
     *
     * No overlay app-op is restored, because none was ever captured: ha-paneld did not perform this disable
     * and inventing a prior mode would be worse than leaving the current one alone.
     */
    internal fun adoptAndEnable(pkg: String): Boolean = reenable(pkg)

    /**
     * Claim ownership of a package something outside the app disabled, so hand-back can reverse it.
     *
     * The guard is the whole value: a marker is only written when Android reports the package
     * `COMPONENT_ENABLED_STATE_DISABLED_USER`, which is what `pm disable-user` leaves behind. A package the
     * firmware shipped switched off reads as `COMPONENT_ENABLED_STATE_DISABLED` and is refused, because
     * claiming it would let a later hand-back enable something ha-paneld never disabled.
     *
     * The overlay app-op is captured now, exactly as [reassertTame] captures it before its own mutation. The
     * host only ran `pm disable-user` and never touched app-ops, so what is observed here is still the
     * pre-tame mode. Adding the package to the desired blocklist is the CALLER's job — this writes the
     * ownership record only, and an owned package outside the desired set is what the reconciler treats as
     * "restore this".
     */
    internal fun recordExternallyTamed(pkg: String): HandBackHomePolicy.RecordOutcome {
        // The same brick-guard the tame path uses. Without it a caller could claim ownership of a critical
        // package -- including this app's OTHER application id during the migration -- and an ownership
        // marker is exactly what puts a package into the hand-back set and the replacement-home ranking.
        if (isCritical(pkg) || pkg == context.packageName) return HandBackHomePolicy.RecordOutcome.NOT_DISABLED
        val observed = runCatching { handBackPackageStates(setOf(pkg))[pkg] }.getOrNull()
            ?: return HandBackHomePolicy.RecordOutcome.UNKNOWN
        if (!observed.present) return HandBackHomePolicy.RecordOutcome.UNKNOWN
        when (observed.disabledByUser) {
            null -> return HandBackHomePolicy.RecordOutcome.UNKNOWN
            false -> return HandBackHomePolicy.RecordOutcome.NOT_DISABLED
            true -> Unit
        }
        val key = TameStatePolicy.markerKey(pkg)
        // Idempotent: a provisioning run that is repeated, or that reports the same package twice, must not
        // overwrite the captured mode with the post-tame one.
        if (runCatching { state.contains(key) }.getOrDefault(false)) {
            return HandBackHomePolicy.RecordOutcome.RECORDED
        }
        val mode = observeOverlayMode(pkg)?.takeIf(TameStatePolicy::validOverlayMode)
            ?: return HandBackHomePolicy.RecordOutcome.UNKNOWN
        val persisted = runCatching {
            state.commitWithDurableVisibility { putString(key, mode) }
        }.getOrDefault(false)
        return if (persisted) {
            HandBackHomePolicy.RecordOutcome.RECORDED
        } else {
            HandBackHomePolicy.RecordOutcome.FAILED
        }
    }
    /** Observe which package currently owns the home role, for the hand-back readback. */
    internal fun observeDefaultHome(): String? = currentDefaultHome()

    /** Write-ahead-own [pkg], then force-stop + disable boot-relaunch + deny its overlay permission. */
    private fun reassertTame(pkg: String): Boolean {
        val key = TameStatePolicy.markerKey(pkg)
        val marker = try {
            val exists = state.contains(key)
            exists to if (exists) state.getString(key, null) else null
        } catch (error: Throwable) {
            Log.w(TAG, "could not read tame ownership marker for $pkg", error)
            return false
        }
        return TameStatePolicy.reassertOwnership(
            markerExists = marker.first,
            markerMode = marker.second,
            captureMode = { observeOverlayMode(pkg) },
            persistMarker = { mode ->
                state.commitWithDurableVisibility { putString(key, mode) }
            },
            // Marker capture is deliberately before this lambda: SETHOME is the first possible external
            // mutation and must never occur without a durable public-v0.9.4-compatible ownership record.
            mutate = { applyTameActions(pkg) },
        )
    }

    private fun applyTameActions(pkg: String): Boolean {
        // If we're disabling the CURRENT default home (e.g. a vendor kiosk like eWeLink left as home),
        // hand the home role to ha-paneld's admin launcher FIRST so Android doesn't pop a home chooser
        // or strand the panel. The dashboard app is re-asserted as home by ensureDashboardHome later.
        val wasDefaultHome = pkg == currentDefaultHome()
        if (wasDefaultHome) {
            val comp = AppIdentity.component(context.packageName, ".AdminLauncherActivity")
            val set = privileged("SETHOME $comp", "cmd package set-home-activity $comp")
            val observed = currentDefaultHome()
            if (!TameStatePolicy.homeHandoffSecured(set, observed, context.packageName)) {
                Log.w(TAG, "tame $pkg: replacement home was not confirmed; leaving current home enabled")
                return false
            }
            Log.i(TAG, "tame $pkg: was default home → confirmed ha-paneld admin launcher as home first")
        }
        val stopped = privileged("STOP $pkg", "am force-stop $pkg")
        val disabled = privileged("DISABLE $pkg", "pm disable-user --user 0 $pkg")
        val overlay = privileged("OVERLAY $pkg deny", "appops set $pkg SYSTEM_ALERT_WINDOW deny")
        val ok = stopped && disabled && overlay
        Log.i(TAG, "tame $pkg: stop=$stopped disable=$disabled overlay=$overlay")
        // We just removed the vendor home and parked the home role on our admin launcher; hand it on to
        // the real dashboard now (set-as-home + foreground) instead of waiting for the 300s watchdog.
        if (wasDefaultHome && disabled) {
            Log.i(TAG, "tame $pkg: default home disabled → re-asserting dashboard home + foreground")
            runCatching { onDefaultHomeDisabled() }
        }
        return ok
    }

    /** Re-enable an owned package and restore its exact saved app-op before removing ownership. */
    private fun restoreOwned(marker: TameOwnedMarker): Boolean {
        val restored = TameStatePolicy.restoreOwnership(
            markerMode = marker.overlayMode,
            enable = { privileged("ENABLE ${marker.pkg}", "pm enable ${marker.pkg}") },
            restoreOverlay = { mode ->
                privileged(
                    "OVERLAY ${marker.pkg} $mode",
                    "appops set ${marker.pkg} SYSTEM_ALERT_WINDOW $mode",
                )
            },
            removeMarker = {
                state.commitWithDurableVisibility {
                    remove(TameStatePolicy.markerKey(marker.pkg))
                }
            },
        )
        Log.i(TAG, "untame ${marker.pkg}: restored=$restored mode=${marker.overlayMode}")
        return restored
    }

    /**
     * Re-enable a package which may have been disabled outside ha-paneld's own tame blocklist. Firmware
     * images often ship diagnostic/vendor applications disabled already; they have no ownership marker to
     * restore, but the Vendor packages picker still needs its Re-enable action to be real and reversible.
     * The caller has already completed the same privileged-operation authorization as an owned untame.
     */
    fun reenable(pkg: String): Boolean =
        privileged("ENABLE $pkg", "pm enable $pkg")

    /** Exact pre-mutation app-op observation; query failure refuses mutation rather than inventing state. */
    private fun observeOverlayMode(pkg: String): String? {
        val helperMode = HelperClient.send("OVERLAY $pkg")?.let(TameStatePolicy::overlayMode)
        return helperMode ?: run {
            Su.runOutput("appops get $pkg SYSTEM_ALERT_WINDOW 2>/dev/null")
                ?.let(TameStatePolicy::overlayMode)
        }
    }

    /** Run a privileged op via the daemon (trusting only an explicit OK), falling back to su. */
    private fun privileged(daemonCmd: String, suCmd: String): Boolean =
        TameStatePolicy.privileged(
            daemonCmd = daemonCmd,
            suCmd = suCmd,
            send = HelperClient::send,
            runSu = Su::run,
        )

    private fun readOwnedMarkers(): TameOwnedMarkers = try {
        TameStatePolicy.parseOwnedMarkers(state.all).also {
            when (it) {
                is TameOwnedMarkers.Overflow ->
                    Log.e(TAG, "tame ownership marker bound exceeded (${it.count}); refusing mutation")
                is TameOwnedMarkers.Invalid ->
                    Log.e(TAG, "tame ownership marker snapshot is malformed (${it.prefixedCount}); refusing mutation")
                is TameOwnedMarkers.Ready,
                TameOwnedMarkers.Unavailable -> Unit
            }
        }
    } catch (error: Throwable) {
        Log.w(TAG, "could not enumerate tame ownership markers", error)
        TameOwnedMarkers.Unavailable
    }

    private fun observePackages(packages: Set<String>): Map<String, TamePackageObservation> {
        val apps = installedAppsOrNull()
            ?: return packages.associateWith {
                TamePackageObservation(TamePackagePresence.UNKNOWN, TamePackageSafety.UNKNOWN)
            }
        val homes = homeLaunchersOrNull()
        var rolesKnown = homes != null
        val ime = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        } catch (error: Throwable) {
            Log.w(TAG, "could not observe current input method", error)
            rolesKnown = false
            null
        }
        return packages.associateWith { pkg ->
            val presence = if (pkg in apps) TamePackagePresence.PRESENT else TamePackagePresence.ABSENT
            val safety = when {
                pkg.isBlank() || isUntouchable(pkg) -> TamePackageSafety.PROTECTED
                apps[pkg]?.let { (it.flags and ApplicationInfo.FLAG_PERSISTENT) != 0 } == true ->
                    TamePackageSafety.PROTECTED
                !rolesKnown -> TamePackageSafety.UNKNOWN
                pkg in requireNotNull(homes) && (pkg in HA_PACKAGES || context.packageName !in homes) ->
                    TamePackageSafety.PROTECTED
                pkg == ime -> TamePackageSafety.PROTECTED
                else -> TamePackageSafety.SAFE
            }
            TamePackageObservation(presence, safety)
        }
    }

    /** Positive absence is the only no-actuation path allowed to discard ownership. */
    private fun clearAbsentMarker(marker: TameOwnedMarker): Boolean =
        state.commitWithDurableVisibility { remove(TameStatePolicy.markerKey(marker.pkg)) }

    companion object {
        private const val TAG = "ha-paneld/tame"
        private const val STATE_PREFS = "ha-paneld-controller-state"

        // The HA Companion dashboard apps — never offered as tame candidates (this is an HA project; the
        // dashboard is the whole point). Excluded from enumeration/suggestions, not the brick-guard.
        private val HA_PACKAGES = CompanionInstaller.SUPPORTED_PACKAGES

        fun isCritical(pkg: String): Boolean = TamePackagePolicy.isCritical(pkg)
    }
}

/** Android-free policy kept explicit so destructive home/app-op transitions have deterministic tests. */
internal object TameStatePolicy {
    const val MARKER_PREFIX = "tame_overlay."
    const val MAX_MARKERS = 256
    private val MODES = setOf("allow", "deny", "ignore", "default", "foreground")
    private val APP_OP = Regex("""SYSTEM_ALERT_WINDOW(?:\s*\([^)]*\))?\s*:\s*(allow|deny|ignore|default|foreground)\b""")

    fun validOverlayMode(mode: String): Boolean = mode in MODES

    fun markerKey(pkg: String): String = MARKER_PREFIX + pkg

    /** Every prefixed record participates in the bound; any malformed record rejects the whole snapshot. */
    fun parseOwnedMarkers(values: Map<String, *>): TameOwnedMarkers {
        val owned = LinkedHashMap<String, TameOwnedMarker>()
        var prefixedCount = 0
        var invalid = false
        for ((key, value) in values) {
            if (!key.startsWith(MARKER_PREFIX)) continue
            prefixedCount++
            if (prefixedCount > MAX_MARKERS) return TameOwnedMarkers.Overflow(prefixedCount)
            val pkg = key.removePrefix(MARKER_PREFIX)
            val mode = value as? String
            if (pkg.length > MAX_PACKAGE_CHARS || !AndroidInput.isPackage(pkg) ||
                mode == null || !validOverlayMode(mode)
            ) {
                invalid = true
                continue
            }
            owned[pkg] = TameOwnedMarker(pkg, mode)
        }
        return if (invalid) TameOwnedMarkers.Invalid(prefixedCount) else TameOwnedMarkers.Ready(owned)
    }

    fun packageInventoryCredible(packageNames: Set<String>, ownPackage: String): Boolean =
        ownPackage in packageNames

    /** One identity-admitted helper command per action; only an explicit OK suppresses the su fallback. */
    fun privileged(
        daemonCmd: String,
        suCmd: String,
        send: (String) -> String?,
        runSu: (String) -> Boolean,
    ): Boolean = send(daemonCmd) == "OK" || runSu(suCmd)

    fun overlayMode(output: String): String? {
        val helper = output.trim().removePrefix("MODE=").takeIf { output.trim().startsWith("MODE=") }
        if (helper != null) return helper.takeIf(::validOverlayMode)
        return APP_OP.find(output)?.groupValues?.get(1)
            ?: "default".takeIf { output.contains("No operations", ignoreCase = true) }
    }

    fun homeHandoffSecured(setSucceeded: Boolean, observedHome: String?, ownPackage: String): Boolean =
        setSucceeded && observedHome == ownPackage

    /** Persist exact restoration state before the first external mutation. */
    fun reassertOwnership(
        markerExists: Boolean,
        markerMode: String?,
        captureMode: () -> String?,
        persistMarker: (String) -> Boolean,
        mutate: () -> Boolean,
    ): Boolean {
        val mode = if (markerExists) markerMode else captureMode()
        if (mode == null || !validOverlayMode(mode)) return false
        if (!markerExists && !persistMarker(mode)) return false
        return mutate()
    }

    /** Remove ownership only after both exact rollback actions have succeeded. */
    fun restoreOwnership(
        markerMode: String?,
        enable: () -> Boolean,
        restoreOverlay: (String) -> Boolean,
        removeMarker: () -> Boolean,
    ): Boolean {
        val mode = markerMode?.takeIf(::validOverlayMode) ?: return false
        val enabled = enable()
        val overlayRestored = restoreOverlay(mode)
        return enabled && overlayRestored && removeMarker()
    }

    private const val MAX_PACKAGE_CHARS = 255
}
