package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.PanelStatus
import io.github.maxlyth.hapaneld.dashboardRecoveryPresentation
import io.github.maxlyth.hapaneld.camera.CameraPresentation
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisory
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.Json.str as jsonStr
import io.github.maxlyth.hapaneld.util.PanelAssistantDevice
import io.github.maxlyth.hapaneld.RendererAdmissionPresentation
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime
import io.github.maxlyth.hapaneld.sensors.PathProbeRuntime
import io.github.maxlyth.hapaneld.control.zigbeeHealthPresentation
import org.json.JSONObject

/** Health inputs and live observations read at their original positions in the status response. */
internal data class StatusHealth(
    val updates: List<UpdateChecker.UpdateInfo>,
    val findings: List<HealthAudit.Finding>,
    val recovery: () -> PanelStatus.DashboardRecoveryState,
    val mdns: () -> Pair<String?, InstallPresentation?>,
    val rollback: () -> Pair<Int, Int>?,
)

internal fun managementStatusJson(
    config: Config,
    management: ManagementSnapshot,
    companion: CompanionDb.ServerObservation,
    powerAdvisory: PowerSafetyAdvisory,
    radio: ZigbeeHealthSnapshot?,
    storage: HealthAudit.StoragePresentation,
    health: StatusHealth,
    renderer: () -> RendererAdmissionPresentation,
    camera: () -> CameraPresentation,
    databaseObservationNonce: String?,
): String {
    // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version). Same finding
    // set as the dashboard banner + Install tab (HealthAudit); the audit lists ALL available updates
    // (not the ignore-filtered view — Ignore only silences the dashboard banner). Plus two warnings not
    // modelled by HealthAudit: renderer recovery suppression and a Companion with a blank internal_url.
    val currentUpdates = health.updates
    val findings = health.findings
    val warns = mutableListOf<String>()
    val warningPresentations = mutableListOf<InstallPresentation?>()
    fun addWarning(warning: String?, presentation: InstallPresentation?) {
        if (warning == null) return
        warns += warning
        warningPresentations += presentation
    }
    val recoveryState = health.recovery()
    addWarning(dashboardRecoveryWarning(recoveryState), dashboardRecoveryPresentation(recoveryState))
    // Same companion internal-URL decision as the dashboard/Install banner (CompanionDb.warning); this
    // surface presents it as bare JSON strings (no Ignore/repair buttons), so the copy stays distinct.
    when (val w = CompanionDb.warning(config.dashboardPackage, companion, management.privilege.directSuReady)) {
        is CompanionDb.Warning.NeedsRepair -> addWarning(
            "⚠ <b>Home Assistant Companion has no internal URL</b> (${w.affected} server${if (w.affected == 1) "" else "s"}) — " +
                "the dashboard can fail with \"Missing 'Host' header\". Repair it on the Install tab.",
            InstallPresentation.create("status-companion-url-missing", mapOf("count" to w.affected.toString())),
        )
        CompanionDb.Warning.ProbeFailed -> addWarning(
            "⚠ <b>Home Assistant Companion settings could not be inspected</b> — " +
                "ha-paneld will retain any last-known result and retry automatically.",
            InstallPresentation("status-companion-probe-failed"),
        )
        null -> {}
    }
    radio?.let { z ->
        addWarning(
            zigbeeWarningText(z, configuredOn = config.zigbeeRouterConfigured && config.zigbeeRouterEnabled),
            zigbeeHealthPresentation(z, config.zigbeeRouterConfigured && config.zigbeeRouterEnabled),
        )
    }
    addWarning(storage.warningHtml(), storage.warningPresentation)
    addWarning(
        PowerSafetyPresentation.statusWarningHtml(powerAdvisory),
        PowerSafetyPresentation.warningPresentation(powerAdvisory),
    )
    val mdns = runCatching(health.mdns).getOrNull()
    addWarning(mdns?.first, mdns?.second)
    val rollback = health.rollback()
    findings.forEach { finding ->
        addWarning(
            statusWarning(finding),
            HealthAudit.presentation(
                finding,
                targetChromium = PanelHealth.MIN_CHROMIUM,
                fromSchema = rollback?.first,
                toSchema = rollback?.second,
                updateComponent = finding.update?.component,
            ),
        )
    }
    val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
    // Stale-while-revalidate keeps status polling fast while ensuring a status-only client still
    // admits one background refresh instead of preserving an old capability view indefinitely.
    val caps = management.capabilityRows.joinToString(",") { c ->
        "{\"name\":${jsonStr(c.name)},\"note\":${jsonStr(c.note)},\"color\":${jsonStr(capColor[c.status] ?: "#888")}}"
    }
    val zigbee = radio?.let {
        JSONObject(it.mqttAttributes()).put("state", it.state.wireValue).toString()
    } ?: "null"
    // `renderer` is emitted UNCONDITIONALLY, including for an external or unconfigured renderer,
    // because a consumer must never have to infer applicability from an absent field. A fleet
    // check that reads a missing object as "nothing to worry about" restates the very failure
    // this object exists to expose: a blank panel that every check still reports as green.
    // `camera` follows the exact same rule for a board with no camera at all — CameraPresentation
    // .absent() is emitted rather than the field being omitted.
    val storageProof = databaseObservationNonce?.let {
        "\"database_observation_nonce\":${jsonStr(it)},"
    }.orEmpty()
    val presentationOverlay = installWarningPresentationsJson(warns, warningPresentations)
        ?.let { "\"warning_presentations\":$it," }
        .orEmpty()
    return "{\"warnings\":[${warns.joinToString(",") { jsonStr(it) }}]," + presentationOverlay +
        "\"capabilities\":[$caps],${installCapabilityStatusJson(management.privilege)}," +
        storageProof +
        "\"panel_assistant_update\":${UpdateChecker.panelAssistantUpdateJson(currentUpdates)}," +
        // Additive, presentation-only, and read from state the panel already holds.
        "\"panel_assistant_device\":${
            PanelAssistantDevice.json(
                config.friendlyName,
                config.manufacturer,
                config.model,
                config.haArea,
            )
        }," +
        "\"zigbee_gateway\":$zigbee,\"storage_health\":${storage.statusJson()}," +
        // `ha_network` follows the same unconditional rule: idle with measuring=false when no
        // socket is held, never absent.
        "\"ha_network\":${HaNetworkPathRuntime.statusJson()}," +
            "\"ha_path_probe\":${PathProbeRuntime.statusJson()}," +
        "\"renderer\":${renderer().statusJson()}," +
        "\"camera\":${camera().statusJson()}," +
        "\"power_safety\":${PowerSafetyPresentation.json(powerAdvisory)}}"
}

/** A health finding as a one-line HTML warning for GET /api/v1/status (no Ignore button; updates keep
 *  a direct download link — this is the machine-readable audit, not the dashboard banner). */
private fun statusWarning(f: HealthAudit.Finding): String = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD ->
        "⚠ <b>System WebView is too old</b> (${esc(f.detail)}) — the Home Assistant dashboard may render blank. " +
            "<a href=\"$WEBVIEW_DOC\" target=\"_blank\" rel=\"noopener\">How &amp; why to update</a> (target Chromium ${PanelHealth.MIN_CHROMIUM}+)."
    HealthAudit.Kind.NO_RENDERER ->
        "ℹ <b>MQTT is configured. Next: choose a dashboard renderer.</b> Select ha-paneld's built-in renderer, install the Home Assistant Companion app, or configure another dashboard package."
    HealthAudit.Kind.UPDATE -> f.update!!.let { u ->
        "⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available (installed ${esc(u.currentVersion)}) — " +
            "<a href=\"${esc(u.releaseUrl)}\" target=\"_blank\" rel=\"noopener\">download</a>"
    }
    HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
        "⚠ <b>Newer database preserved after a version downgrade</b> (${esc(f.detail)}) — this build opened a " +
            "fresh state store because its schema is older; some settings may have reset. The previous database " +
            "is preserved on the panel for recovery. Check Configure or restore a backup."
}
