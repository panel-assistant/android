package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.PanelStatus
import io.github.maxlyth.hapaneld.control.DisplaySizingObservation
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisory
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.TameCandidate
import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.CompanionInstaller

/** Samples the existing installation observations and composes the software-management page. */
internal class InstallPageHandler(
    private val config: Config,
    private val appContext: Context,
    private val profile: DeviceProfile,
    private val managementObservations: ManagementObservations,
    private val pageHealth: PageHealth,
    private val tame: TameController,
    private val tameProfileCandidates: List<TameCandidate>,
    private val recommendedDensity: Int?,
    private val recommendedFontScale: Float?,
    private val catalogueLoader: () -> CatalogueLoader,
    private val radioStatus: () -> ZigbeeHealthSnapshot?,
    private val dashboardRecoveryState: () -> PanelStatus.DashboardRecoveryState,
    private val powerSafetyAdvisory: (PrivilegedRouteObservation) -> PowerSafetyAdvisory,
) {
    /** Install tab — software-management hub: setup warnings, managed component versions, radio firmware,
     *  on-demand health audit, and config backup. (The Capabilities card lives on the Dashboard.) */
    fun body(strings: AppStrings): String {
        val management = managementObservations.snapStaleOk()
        val companion = managementObservations.companionServersStaleOk()
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version).
        val h = pageHealth.healthInputs()
        val wv = h.webView
        val root = management.privilege.rootControlReady
        val installer = management.privilege.typedShellControlReady
        val su = management.privilege.directSuReady
        val displaySizing = managementObservations.densityCache.peek() ?: DisplaySizingObservation(
            current = management.densityCur,
            base = management.densityBase,
            fontScale = management.fontScale,
        )
        val companionHelper = managementObservations.companionHelperCache.get()
        // Same finding set as the dashboard banner (HealthAudit). Update findings are surfaced by the
        // Managed-components card below, so the top warnings show only the render-blocking states.
        val problems = pageHealth.healthFindings(h, wv.display, emptyList())
        // A missing dashboard app can be self-healed by installing the minimal HA Companion over root — a
        // Play-managed full Companion would already count as a renderer, so NO_RENDERER + root ⇒ safe.
        val canInstallCompanion = installer
        // Two warnings not modelled by HealthAudit (crash-looping dashboard, Companion blank internal_url)
        // — shared with the dashboard banner. Here (Install tab, install.js loaded) they get inline buttons.
        val powerAdvisory = powerSafetyAdvisory(management.privilege)
        val extra = localizedPowerSafetyBanner(
            powerAdvisory,
            inlineRepair = true,
            strings = strings,
        ) +
            adHocWarnings(
                config, catalogueLoader(), management.privilege.directSuReady, management.densityBase,
                radioStatus, dashboardRecoveryState,
                companion, inlineRepair = true, strings = strings,
            )
        val warnings = extra + problems.joinToString("") { installWarning(it, canInstallCompanion, strings) }
        val allGood = if (h.brokerConfigured && problems.isEmpty() && extra.isEmpty() && !powerAdvisory.assessment.warning) """<div class="card" data-layout-key="ready"><p class="note">✓ ${esc(strings.get("install.ready"))}</p></div>""" else ""
        val compPkg = CompanionInstaller.installedPkg(appContext)
        val compCur = compPkg?.let { AppInstaller.installedVersion(appContext, it) }?.takeIf { it.isNotBlank() }
        return installPageBody(
            strings = strings,
            warnings = warnings,
            allGood = allGood,
            components = componentsCardHtml(installer, strings, compPkg, compCur),
            apk = apkCardHtml(root, strings, config),
            uninstall = uninstallCardHtml(su, strings),
            vendor = tameCardHtml(root, strings) { tame.cardCandidates(config.tameVendorPackages, tameProfileCandidates) },
            display = displayCardHtml(management.privilege.typedShellControlReady, displaySizing, strings, recommendedDensity, recommendedFontScale),
            backup = backupCardHtml(companionHelper, CompanionInstaller.installedPkg(appContext) != null, strings),
        )
    }
}
