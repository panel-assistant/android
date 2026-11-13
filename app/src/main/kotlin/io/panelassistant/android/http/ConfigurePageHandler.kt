package io.panelassistant.android.http

import io.panelassistant.android.util.LocalAdminEndpoint
import io.panelassistant.android.util.localIpv4
import io.panelassistant.android.util.localIpv6
import io.panelassistant.android.Config
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.i18n.Strings as AppStrings

/** Composes Configure from current setup state and the existing management observations. */
internal class ConfigurePageHandler(
    private val config: Config,
    private val sensors: SensorReporter,
    private val managementObservations: ManagementObservations,
    private val setupState: SetupState,
    private val strategySelectorAllowed: () -> Boolean,
    private val pageHealth: PageHealth,
    private val powerSafetyAdvisory: (PrivilegedRouteObservation) -> PowerSafetyAdvisory,
    private val haSignInNeededForEffectiveDashboard: () -> Boolean,
) {
    /** [embedded]: rendered for the sidebar's embed link, whose document carries no panel address. */
    fun body(strings: AppStrings, embedded: Boolean = false): String =
        configureBody(
            strings, sensors.hasProximity(), configureSetupBanners(strings),
            if (embedded) null else LocalAdminEndpoint.externalUrl(localIpv4(), localIpv6(), config.httpPort),
        )

    private fun configureSetupBanners(strings: AppStrings): String {
        val management = managementObservations.snapStaleOk()
        val power = powerSafetyBanner(
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
        if (haSignInNeededForEffectiveDashboard()) {
            return power + resume + haSignInBanner(strings)
        }
        val noRenderer = pageHealth.healthFindings(pageHealth.healthInputs(), "", emptyList()).any { it.kind == HealthAudit.Kind.NO_RENDERER }
        // Only a panel past setup runs a filtered dashboard, so only this path can carry the strategy note.
        if (!noRenderer) return power + resume + strategySelectorAllowedBanner(strings)
        return power + resume + noRendererNotice(strings)
    }

    // Issue #133 follow-up. With a strategy dashboard's check allowed, cards for entities outside the
    // subscription never appear and nothing on the panel can list them, so say so where settings are
    // changed and send the reader to the Entities page, which explains what to pin. A failed read of the
    // entity store must not take the Configure page down with it.
    private fun strategySelectorAllowedBanner(strings: AppStrings): String =
        if (!runCatching(strategySelectorAllowed).getOrDefault(false)) "" else
            configureStrategyBanner(strings)
}
