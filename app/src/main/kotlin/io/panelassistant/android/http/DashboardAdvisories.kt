package io.panelassistant.android.http

import android.content.Context
import io.panelassistant.android.Config
import io.panelassistant.android.PanelStatus
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.util.UpdateChecker
import org.json.JSONObject

/** Composes dashboard advisories at their original positions in each render. */
internal class DashboardAdvisories(
    private val config: Config,
    private val appContext: Context,
    private val sensors: SensorReporter,
    private val managementObservations: ManagementObservations,
    private val setupState: SetupState,
    private val pageHealth: PageHealth,
    private val mqttState: () -> String,
    private val haSignInNeededForEffectiveDashboard: () -> Boolean,
    private val powerSafetyAdvisory: (PrivilegedRouteObservation) -> PowerSafetyAdvisory,
    private val storageHealth: () -> StorageHealthSnapshot,
    private val catalogueLoader: () -> CatalogueLoader,
    private val radioStatus: () -> ZigbeeHealthSnapshot?,
    private val dashboardRecoveryState: () -> PanelStatus.DashboardRecoveryState,
) {
    /** The setup / health / update banners — everything above the cards. Needs the facts map (MQTT
     *  state), so on a cold start it hydrates with the rest. */
    fun render(s: ManagementSnapshot, h: PageHealth.Inputs, strings: AppStrings): String {
        val storage = HealthAudit.storage(storageHealth())
        val mqttSetupRequired = SetupBanner.mqttSetupRequired(config.panelAssistantAuthority)
        val mqtt = s.facts["MQTT"] ?: "disabled"
        // Pure decision (unit-tested in SetupBannerTest) — note a CONFIGURED broker that's merely
        // mid-(re)connect must not be reported as missing.
        val needs = SetupBanner.needs(mqtt, config.mqttBroker.isNotBlank(), config.mqttUser.isNotBlank(), mqttSetupRequired)
        val setup = if (needs.isNotEmpty())
            dashboardSetupNeedsBanner(needs, strings)
        else ""
        // Commissioning progress only while somebody is actually commissioning. `announcing` is transient but
        // recurs on every bridge reconnect — an HA restart, a broker blip, a panel waking — so on a finished
        // panel this banner kept reappearing to narrate a step that was done months ago. Reported twice from
        // deployed panels. The Configure tab keeps it unconditionally: there it is feedback for a save the user just
        // made, which is the reason it was added.
        val mqttProgress = if (!setupState.setupNeedsUser()) "" else {
            SetupBanner.progress(mqtt, config.mqttBroker.isNotBlank(), setupState.dashboardSetupStepPending(), mqttState(), mqttSetupRequired)?.let {
                setupProgressBanner(it, strings)
            }.orEmpty()
        }
        val haSetup = if (haSignInNeededForEffectiveDashboard()) haSignInBanner(strings) else ""
        val termuxBridge = if (managementObservations.termuxBridgeCache.get() == TermuxBridgeProbe.State.RUNNING) {
            dashboardPanelBridgeBanner(strings)
        } else ""
        val proximityState = JSONObject(sensors.proximityJson())
        val proximityLearning = ProximityStatusBanner.titleKey(
            enabled = config.wakeOnWave,
            present = proximityState.optBoolean("present", false),
            phase = proximityState.optString("phase"),
            health = proximityState.optString("health"),
            active = proximityState.optBoolean("sessionActive", false),
            wakeReady = proximityState.optBoolean("wakeReady", false),
        )?.let { title ->
            proximityLearningBanner(title, strings)
        }.orEmpty()
        // Panel-health + update findings: states that stop the panel rendering the dashboard as expected but
        // that the info map otherwise reports neutrally. Soft + best-effort — ha-paneld runs fine regardless.
        // The WebView verdict is from the REAL engine version (WebView UA), not the stamped package version
        // (cached, so cheap). Shared decision — see HealthAudit; updates are filtered by the per-version
        // dismissals so an "Ignore this version" click stays hidden until a newer release ticks it back.
        val findings = pageHealth.healthFindings(h, s.facts["System WebView"] ?: "", UpdateChecker.current(appContext, config.ignoredUpdates))
        // Order: storage/database safety first, then actively-broken render states, render findings
        // (WebView / renderer / updates), and finally the needs-config setup notice. On the dashboard the
        // ad-hoc warnings link to the Install tab for the fix (their one-tap buttons live there, with install.js).
        // The lifecycle banner leads: while Home Assistant is going away or coming back, that explains
        // most of what else the page is about to report.
        return localizedStorageBanner(storage, strings) + localizedPowerSafetyBanner(
            advisory = powerSafetyAdvisory(s.privilege),
            inlineRepair = true,
            strings = strings,
        ) +
            adHocWarnings(
                config, catalogueLoader(), s.privilege.directSuReady, s.densityBase,
                radioStatus, dashboardRecoveryState,
                managementObservations.companionServersForRender(), inlineRepair = false, strings = strings,
            ) +
            findings.joinToString("") { bannerFor(it, strings) } + termuxBridge + proximityLearning + haSetup + mqttProgress + setup
    }
}
