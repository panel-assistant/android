package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisory
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

/** Composes Configure from current setup state and the existing management observations. */
internal class ConfigurePageHandler(
    private val config: Config,
    private val sensors: SensorReporter,
    private val managementObservations: ManagementObservations,
    private val setupState: SetupState,
    private val strategySelectorAllowed: () -> Boolean,
    private val pageHealth: PageHealth,
    private val mqttState: () -> String,
    private val powerSafetyAdvisory: (PrivilegedRouteObservation) -> PowerSafetyAdvisory,
    private val haSignInNeededForEffectiveDashboard: () -> Boolean,
) {
    fun body(strings: AppStrings): String =
        configureBody(strings, sensors.hasProximity(), configureSetupBanners(strings))

    private fun configureSetupBanners(strings: AppStrings): String {
        val management = managementObservations.snapStaleOk()
        val power = localizedPowerSafetyBanner(
            powerSafetyAdvisory(management.privilege),
            inlineRepair = true,
            strings = strings,
        )
        // Someone landing on the full settings wall mid-commissioning (an old bookmark, the QR from a
        // build that pointed here) should learn the guided path exists — once setup completes this line
        // vanishes with the rest of the wizard surface.
        val resume = if (setupState.setupNeedsUser()) {
            configureResumeBanner(strings)
        } else ""
        // MQTT verification runs asynchronously after the save returns, and the Configure tab is where the
        // user actually is while it happens — but it showed nothing, so a save that was still being checked
        // looked like a save that had done nothing. SetupBanner already derives this state and is already
        // rendered on the dashboard; surfacing it here too costs nothing and keeps one authority.
        val mqtt = management.facts["MQTT"] ?: "disabled"
        SetupBanner.progress(mqtt, config.mqttBroker.isNotBlank(), setupState.dashboardSetupStepPending(), mqttState())?.let { progress ->
            return power + resume + setupProgressBanner(progress, strings)
        }
        if (haSignInNeededForEffectiveDashboard()) {
            return power + resume + configureSignInBanner(strings)
        }
        val noRenderer = pageHealth.healthFindings(pageHealth.healthInputs(), "", emptyList()).any { it.kind == HealthAudit.Kind.NO_RENDERER }
        // Only a panel past setup runs a filtered dashboard, so only this path can carry the strategy note.
        if (!noRenderer) return power + resume + strategySelectorAllowedBanner(strings)
        return power + resume + configureRendererBanner(strings)
    }

    // Issue #133 follow-up. With a strategy dashboard's check allowed, cards for entities outside the
    // subscription never appear and nothing on the panel can list them, so say so where settings are
    // changed and send the reader to the Entities page, which explains what to pin. A failed read of the
    // entity store must not take the Configure page down with it.
    private fun strategySelectorAllowedBanner(strings: AppStrings): String =
        if (!runCatching(strategySelectorAllowed).getOrDefault(false)) "" else
            configureStrategyBanner(strings)
}
