package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.sameOriginDashboardRoute
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.util.DashboardPath

/** What an archive's settings would restore to, and why any of them cannot. */
internal data class RestoreSettingsDecision(
    val accepted: Map<String, String>,
    val errors: List<String>,
)

/**
 * A stored value from an older archive, in the form the current validator can read.
 *
 * `home_dashboard` had no validator before this release, so an archive can hold a whole address. The
 * path canonicalizer refuses a URL scheme, so handing it one returns null and falls back to the
 * original, which then fails validation — and because a restore is all or nothing, that single
 * historical value takes the entire archive with it, precisely when its owner needs it. This panel's
 * own origin is stripped first, exactly as the live store does on upgrade. A route naming a different
 * server is left alone and still refused, rather than silently retargeted at someone else's dashboard.
 */
internal fun restorableSettingValue(key: String, value: String, configuredOrigin: String?): String =
    when (key) {
        "home_dashboard" -> {
            val candidate = sameOriginDashboardRoute(value, configuredOrigin) ?: value
            if (DashboardPath.followsAccountDefault(candidate)) ""
            else DashboardPath.canonical(candidate, preserveRoute = true) ?: value
        }
        else -> value
    }

/**
 * Configuration adjustments for a migration-mode restore. The bridge made the successor a kiosk
 * companion of itself so its return loop would leave the successor in the foreground; carried into
 * the successor that entry would exempt the legacy package, and the successor itself, from its own
 * kiosk lock. Every other value is restored exactly as written.
 */
internal fun migrationRestoreConfig(values: Map<String, String>): Map<String, String> {
    val restored = LinkedHashMap(values)
    // A setting that names this app's own package is a sentinel, not a foreign app: the launcher
    // selection "Panel admin" and the built-in renderer are both stored as the writer's package name.
    // Left as written they would name the legacy package, which is about to be removed.
    MIGRATION_OWN_PACKAGE_SETTINGS.forEach { key ->
        if (restored[key] == io.github.maxlyth.hapaneld.AppIdentity.LEGACY) {
            restored[key] = io.github.maxlyth.hapaneld.AppIdentity.SUCCESSOR
        }
    }
    restored["kiosk_companion_packages"]?.let { companions ->
        restored["kiosk_companion_packages"] = io.github.maxlyth.hapaneld.parseKioskCompanionPackages(companions)
            .filterNot(io.github.maxlyth.hapaneld.AppIdentity::isPanelApp)
            .joinToString(",")
    }
    return if (restored == values) values else restored
}

internal val MIGRATION_OWN_PACKAGE_SETTINGS: Set<String> = setOf("launcher_package", "dashboard_package")

/** Whether a migration-mode restore wrote back everything its receipt carried. */
internal fun migrationRestoreComplete(rawPreferencesApplied: Boolean, carriedRows: Int, restoredRows: Int): Boolean =
    rawPreferencesApplied && restoredRows == carriedRows

/** The restore plan's per-setting decision, separated from the transport so it can be asserted. */
internal fun planRestoreSettings(
    migrated: Map<String, String>,
    configuredOrigin: String?,
): RestoreSettingsDecision {
    val accepted = LinkedHashMap<String, String>()
    val errors = ArrayList<String>()
    for ((key, value) in migrated) {
        val spec = SettingsRegistry.spec(key)
        val exposedSpec = SettingsRegistry.parseExposure(key)
        when {
            exposedSpec != null -> {
                val normalized = SettingValue.parseBool(value)?.toString()
                if (normalized == null) errors += "$key: expected a boolean" else accepted[key] = normalized
            }
            spec == null -> errors += "$key: unknown setting"
            spec.readOnly || spec.transient -> errors += "$key: setting cannot be restored"
            else -> when (
                val validated = SettingValue.validate(spec, restorableSettingValue(key, value, configuredOrigin))
            ) {
                is Validation.Ok -> accepted[key] = validated.normalized
                is Validation.Bad -> errors += "$key: ${validated.reason}"
            }
        }
    }
    return RestoreSettingsDecision(accepted, errors)
}
