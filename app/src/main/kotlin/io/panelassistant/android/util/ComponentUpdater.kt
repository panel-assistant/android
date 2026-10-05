package io.panelassistant.android.util

/**
 * One typed resolve -> compare -> decide pipeline for every release-backed component (ha-paneld itself and
 * the HA Companion app). Each component resolves its newest installable release through [ReleaseCatalog] in
 * its own way (a simple newest-APK for ha-paneld; a cap-aware target for the Companion), then the SAME
 * compare-and-decide core runs here so the "is this release an update over what is installed" verdict is not
 * hand-rolled per caller.
 *
 * The version comparison itself stays single-authority in [UpdateChecker.isNewer]/[UpdateChecker.compareVersions];
 * this object only owns the pipeline shape around it. Pure + unit-tested (ComponentUpdaterTest).
 */
object ComponentUpdater {
    /** The newest installable release for a component: display [version], its [apkUrl], release-notes
     * [releaseUrl], and (when the resolver retains it) exact source [tag]. */
    data class Target(
        val version: String,
        val apkUrl: String,
        val releaseUrl: String,
        val tag: String? = null,
        val prerelease: Boolean = false,
    )

    /** The verdict of one resolve -> compare -> decide pass. */
    sealed interface Outcome {
        /** The release lookup failed (no release / network / parse error). */
        data object Unresolved : Outcome

        /** A release resolved but is not newer than what is installed (and no [force]). */
        data object UpToDate : Outcome

        /** A release resolved that should be applied — newer than installed, or [force]d. */
        data class Update(val target: Target) : Outcome
    }

    /**
     * True when [targetVersion] should be applied over [installed]: either [force] is set, or the target
     * parses as strictly newer. [installedNormalize] strips any non-version suffix (e.g. the Companion's
     * `-minimal`) from the installed version before comparison; identity by default. Malformed versions
     * compare as not-newer via [UpdateChecker.isNewer] (fail closed), preserving existing edge-case behaviour.
     */
    fun isUpdate(
        targetVersion: String,
        installed: String,
        force: Boolean = false,
        installedNormalize: (String) -> String = { it },
    ): Boolean = force || UpdateChecker.isNewer(targetVersion, installedNormalize(installed))

    /**
     * Resolve a component's newest release via [resolve], then decide whether it is an update over [installed].
     * A null [resolve] result is [Outcome.Unresolved]; a resolved-but-not-newer release is [Outcome.UpToDate];
     * a newer (or [force]d) release is [Outcome.Update]. [resolve] is invoked at most once.
     */
    inline fun resolveUpdate(
        installed: String,
        force: Boolean = false,
        noinline installedNormalize: (String) -> String = { it },
        resolve: () -> Target?,
    ): Outcome {
        val target = resolve() ?: return Outcome.Unresolved
        return if (isUpdate(target.version, installed, force, installedNormalize)) Outcome.Update(target)
        else Outcome.UpToDate
    }
}
