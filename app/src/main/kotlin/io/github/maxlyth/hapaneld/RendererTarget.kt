package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.DashboardPath

/**
 * The dashboard renderer a configured selection resolves to. Shared by the resolver's policies so the
 * built-in-versus-foreign distinction has one compiler-visible home instead of being re-derived at each
 * call site.
 */
internal sealed interface RendererTarget {
    /** ha-paneld's own built-in WebView renderer ([DashboardActivity]). */
    data object Builtin : RendererTarget

    /** A foreign dashboard app (an HA Companion variant, or a configured third-party renderer). */
    data class Foreign(val packageName: String) : RendererTarget
}

/**
 * The single home for the dashboard renderer-target rules. It keeps three concerns deliberately
 * distinct and does **not** decide the *actually active* renderer — that stays probe-derived in the
 * `dashboardState` / built-in-foreground machinery:
 *
 *  - [resolveControlPackage] — what `SystemController` acts on. An explicit selection is retained even
 *    when unavailable; Auto (blank) selects the built-in renderer.
 *  - [resolveLaunchable] — what the Main/Admin UI can open *now*: built-in needs configured HA
 *    readiness, a foreign target needs a launch intent, and an unavailable explicit choice never falls
 *    back silently.
 *  - [attributionOf] — which package a smoothness sample is charged to, derived from the resolved
 *    target (built-in maps to the real ha-paneld package). It is not foreground evidence.
 *
 * The known Companion package set is kept here only for reclaiming a HOME assignment ha-paneld made for
 * an older Auto policy or an explicit Companion choice. It is never an automatic dashboard selection.
 */
internal object RendererResolver {
    /** Sentinel `dashboard_package` value selecting the built-in renderer. */
    const val BUILTIN = "builtin"

    /** Companion targets whose ha-paneld-managed HOME assignment may be safely replaced. */
    val LEGACY_COMPANION_PACKAGES: List<String> = listOf(
        CompanionInstaller.MINIMAL_PKG,
        CompanionInstaller.FULL_PKG,
    )

    /** Dashboard renderers ha-paneld may have set as HOME and can safely reclaim. */
    val LEGACY_COMPANION_PACKAGE_SET: Set<String> = LEGACY_COMPANION_PACKAGES.toSet()

    /** An explicit per-panel return route only for the two Companion renderers. */
    fun companionHomeRoute(pkg: String, homeDashboard: String): String? =
        homeDashboard.takeIf {
            pkg in LEGACY_COMPANION_PACKAGE_SET &&
                DashboardPath.canonical(it, preserveRoute = true) == it
        }

    /** Built-in selection: Auto (blank), the [BUILTIN] sentinel, or the own-package alias tolerated as a
     *  self-force-stop-safe alias. Shared by control and status projections. */
    fun isBuiltinSelection(pkg: String, ownPackage: String): Boolean =
        pkg.isBlank() || pkg == BUILTIN || pkg == ownPackage

    /**
     * CONTROL resolution — the package string `SystemController` acts on. The [BUILTIN] sentinel and a
     * structurally valid explicit package pass through unchanged (downstream special-cases built-in); an
     * invalid non-blank value resolves to none (`""`); blank Auto selects the built-in renderer sentinel.
     */
    fun resolveControlPackage(configuredPackage: String, isInstalled: (String) -> Boolean): String {
        if (configuredPackage.isNotBlank()) {
            return configuredPackage.takeIf(AndroidInput::isDashboardTarget).orEmpty()
        }
        return BUILTIN
    }

    /**
     * LAUNCHABLE resolution — what the Main/Admin UI can open now, without silently overriding an
     * explicit selection. Built-in requires [builtinReady]; a foreign target requires a launch intent
     * ([isLaunchable]); blank Auto selects the built-in renderer when ready. Only the literal [BUILTIN]
     * sentinel is treated as built-in here (the own-package alias is a control-side concern).
     */
    fun resolveLaunchable(
        configuredPackage: String,
        builtinReady: Boolean,
        isLaunchable: (String) -> Boolean,
    ): RendererTarget? = when {
        configuredPackage == BUILTIN -> RendererTarget.Builtin.takeIf { builtinReady }
        configuredPackage.isNotBlank() ->
            configuredPackage.takeIf(isLaunchable)?.let(RendererTarget::Foreign)
        else -> RendererTarget.Builtin.takeIf { builtinReady }
    }

    /**
     * REPORTED resolution — an already-resolved control package as the stable token API clients read
     * when they must not re-derive the built-in rule for themselves. The installer's dashboard-seed gate
     * is the first such client, and it cannot derive the answer: a blank `dashboard_package` selects the
     * built-in renderer, which no amount of reading the stored value tells you, and a gate that guessed
     * either way would refuse a panel that is using the built-in renderer or seed one that is not.
     *
     * A real package passes through, and so does the [BUILTIN] sentinel — it satisfies the package
     * grammar, which is what the test named for it pins. Anything else reports as `""`: an internal
     * marker such as `SystemController`'s invalid-dashboard sentinel is not a renderer anyone can act
     * on, and publishing it would read as a foreign package. That distinction is the point — "this
     * panel runs something else" and "this panel cannot resolve what it runs" are different facts, and
     * a caller that cannot tell them apart cannot refuse with an honest reason.
     */
    fun reportedRenderer(resolvedPackage: String): String =
        resolvedPackage.takeIf(AndroidInput::isPackage).orEmpty()

    /**
     * The resolved control target as a [RendererTarget]: the [BUILTIN] sentinel → [RendererTarget.Builtin],
     * none (`""`) → `null`, any other resolved package → [RendererTarget.Foreign]. The service resolves
     * this once, off the sampling path, and hands the immutable value to diagnostics.
     */
    fun resolveControlTarget(configuredPackage: String, isInstalled: (String) -> Boolean): RendererTarget? =
        when (val ctrl = resolveControlPackage(configuredPackage, isInstalled)) {
            BUILTIN -> RendererTarget.Builtin
            "" -> null
            else -> RendererTarget.Foreign(ctrl)
        }

    /** Smoothness-attribution package for an already-resolved target: built-in → [ownPackage], a
     *  foreign target → its package, none → `""`. This is not foreground evidence. */
    fun attributionOf(target: RendererTarget?, ownPackage: String): String = when (target) {
        RendererTarget.Builtin -> ownPackage
        is RendererTarget.Foreign -> target.packageName
        null -> ""
    }
}
