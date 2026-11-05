package io.panelassistant.android.http

import io.panelassistant.android.PanelTokens
import io.panelassistant.android.Config
import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.PanelStatus
import io.panelassistant.android.dashboardRecoveryPresentation
import io.panelassistant.android.camera.CameraPresentation
import io.panelassistant.android.platform.PanelPermissionRepair
import android.os.Build
import io.panelassistant.android.control.CompanionDb
import io.panelassistant.android.control.HardwareSerial
import io.panelassistant.android.control.NetworkMacs
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.util.UpdateChecker
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.Json.str as jsonStr
import io.panelassistant.android.util.PanelAssistantDevice
import io.panelassistant.android.panelassistant.PanelAssistantTransportFacts
import io.panelassistant.android.panelassistant.PanelAssistantTransportPhase
import io.panelassistant.android.RendererAdmissionPresentation
import io.panelassistant.android.sensors.HaNetworkPathRuntime
import io.panelassistant.android.sensors.PathProbeRuntime
import io.panelassistant.android.control.zigbeeHealthPresentation
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
    permissions: () -> Map<PanelPermissionRepair.Grant, PanelPermissionRepair.State>,
    homeProof: (() -> String)? = null,
    /** The `proximity` component, or null to omit it on a panel with no proximity source. */
    proximity: () -> String? = { null },
    /** The native transport's phase and refusal; null when no transport owner runs. */
    panelAssistantTransport: PanelAssistantTransportFacts? = null,
    /** The capability rows' wire text is English, from the same catalogue the dashboard reads. */
    english: AppStrings,
    /** The panel profile id, read only to link a WebView-too-old warning to that model's update steps. */
    profileId: () -> String,
    webView: (() -> String)? = null,
): String {
    // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version). Same finding
    // set as the dashboard banner + Install tab (HealthAudit); the audit lists ALL available updates
    // (not the ignore-filtered view — Ignore only silences the dashboard banner). Plus two warnings not
    // modelled by HealthAudit: renderer recovery suppression and a Companion with a blank internal_url.
    val currentUpdates = health.updates
    val findings = health.findings
    val warns = mutableListOf<String>()
    val severities = mutableListOf<NoticeSeverity>()
    val warningPresentations = mutableListOf<InstallPresentation?>()
    fun addWarning(severity: NoticeSeverity, warning: String?, presentation: InstallPresentation?) {
        if (warning == null) return
        warns += stripNoticeGlyph(warning)
        severities += severity
        warningPresentations += presentation
    }
    fun addNotice(spec: NoticeSpec?, presentation: InstallPresentation?) {
        if (spec != null) addWarning(spec.severity, spec.wire(), presentation)
    }
    val recoveryState = health.recovery()
    addNotice(recoveryNotice(recoveryState, english), dashboardRecoveryPresentation(recoveryState))
    // Same companion internal-URL decision as the dashboard/Install banner (CompanionDb.warning); this
    // surface presents it as bare JSON strings (no Ignore/repair buttons), so the copy stays distinct.
    when (val w = CompanionDb.warning(config.dashboardPackage, companion, management.privilege.directSuReady)) {
        is CompanionDb.Warning.NeedsRepair -> addWarning(
            NoticeSeverity.ACTION_NEEDED,
            "<b>Home Assistant Companion has no internal URL</b> (${w.affected} server${if (w.affected == 1) "" else "s"}) — " +
                "the dashboard can fail with \"Missing 'Host' header\". Repair it on the Install tab.",
            InstallPresentation.create("status-companion-url-missing", mapOf("count" to w.affected.toString())),
        )
        CompanionDb.Warning.ProbeFailed -> addWarning(
            NoticeSeverity.WARNING,
            "<b>Home Assistant Companion settings could not be inspected</b> — " +
                "Panel Assistant will retain any last-known result and retry automatically.",
            InstallPresentation("status-companion-probe-failed"),
        )
        null -> {}
    }
    radio?.let { z ->
        val configuredOn = config.zigbeeRouterConfigured && config.zigbeeRouterEnabled
        addNotice(zigbeeNotice(z, configuredOn, english), zigbeeHealthPresentation(z, configuredOn))
    }
    addNotice(storageNotice(storage, english), storage.warningPresentation)
    addNotice(
        powerSafetyNotice(powerAdvisory, inlineRepair = false, strings = english),
        PowerSafetyPresentation.warningPresentation(powerAdvisory),
    )
    val mdns = runCatching(health.mdns).getOrNull()
    addWarning(NoticeSeverity.WARNING, mdns?.first, mdns?.second)
    val rollback = health.rollback()
    findings.forEach { finding ->
        addWarning(
            statusWarningSeverity(finding),
            statusWarning(finding, profileId),
            HealthAudit.presentation(
                finding,
                targetChromium = PanelHealth.MIN_CHROMIUM,
                fromSchema = rollback?.first,
                toSchema = rollback?.second,
                updateComponent = finding.update?.component,
            ),
        )
    }
    val capColor = mapOf(
        "ok" to PanelTokens.Page.CAPABILITY_OK,
        "degraded" to PanelTokens.Page.CAPABILITY_DEGRADED,
        "none" to PanelTokens.Page.CAPABILITY_NONE,
    )
    // Stale-while-revalidate keeps status polling fast while ensuring a status-only client still
    // admits one background refresh instead of preserving an old capability view indefinitely.
    val caps = management.capabilityRows.joinToString(",") { c ->
        "{\"name\":${jsonStr(c.name(english))},\"note\":${jsonStr(c.note(english))},\"color\":${jsonStr(capColor[c.status] ?: PanelTokens.Page.CAPABILITY_UNKNOWN)}}"
    }
    val zigbee = radio?.let {
        JSONObject(it.attributesJson()).put("state", it.state.wireValue).toString()
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
    val homeProofJson = homeProof?.let { "\"home_ui\":${it()}," }.orEmpty()
    val proximityJson = runCatching(proximity).getOrNull()?.let { "\"proximity\":$it," }.orEmpty()
    val presentationOverlay = installWarningPresentationsJson(warns, warningPresentations)
        ?.let { "\"warning_presentations\":$it," }
        .orEmpty()
    return "{\"warnings\":[${warns.joinToString(",") { jsonStr(it) }}]," +
        "\"warning_severities\":[${severities.joinToString(",") { jsonStr(it.wire) }}]," + presentationOverlay +
        "\"capabilities\":[$caps],${installCapabilityStatusJson(management.privilege, management.confirmedSelfUpdate)}," +
        storageProof +
        homeProofJson +
        // Additive, presentation-only, and read from state the panel already holds.
        "\"panel_assistant_device\":${
            PanelAssistantDevice.json(
                config.friendlyName,
                config.manufacturer,
                config.model,
                config.haArea,
            )
        }," +
        "\"panel_assistant_hardware\":${
            PanelAssistantDevice.hardwareJson(
                Build.DISPLAY,
                Build.VERSION.RELEASE,
                HardwareSerial.shared.read(management.privilege.directSuReady),
                config.androidId,
                NetworkMacs.shared.read(),
            )
        }," +
        "\"zigbee_gateway\":$zigbee,\"storage_health\":${storage.statusJson()}," +
        // `ha_network` follows the same unconditional rule: idle with measuring=false when no
        // socket is held, never absent.
        "\"ha_network\":${HaNetworkPathRuntime.statusJson()}," +
            "\"ha_path_probe\":${PathProbeRuntime.statusJson()}," +
        // Unconditional too, so a panel Panel Assistant refuses says why somewhere a reader can see.
        "\"panel_assistant_transport\":${
            (panelAssistantTransport ?: PanelAssistantTransportFacts(PanelAssistantTransportPhase.STOPPED, null)).statusJson()
        }," +
        (webView?.let { "\"webview\":${it()}," } ?: "") +
        "\"renderer\":${renderer().statusJson()}," +
        "\"camera\":${camera().statusJson()}," +
        proximityJson +
        "\"permissions\":${PanelPermissionRepair.statusJson(runCatching(permissions).getOrDefault(emptyMap()))}," +
        "\"power_safety\":${PowerSafetyPresentation.json(powerAdvisory)}}"
}

/** Wire projection of the system controller\'s HOME observation. */
internal fun homeUiProofJson(state: String, reason: String, evidence: String): String =
    "{\"state\":${jsonStr(state)},\"reason\":${jsonStr(reason)},\"evidence\":${jsonStr(evidence)}}"

/** A health finding as a one-line HTML warning for GET /api/v1/status (no Ignore button; updates keep
 *  a direct download link — this is the machine-readable audit, not the dashboard banner). */
private fun statusWarningSeverity(f: HealthAudit.Finding): NoticeSeverity = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD, HealthAudit.Kind.SCHEMA_ROLLED_BACK -> NoticeSeverity.ACTION_NEEDED
    HealthAudit.Kind.NO_RENDERER -> NoticeSeverity.NOTE
    HealthAudit.Kind.UPDATE -> NoticeSeverity.UPDATE
}

private fun statusWarning(f: HealthAudit.Finding, profileId: () -> String): String = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD ->
        "⚠ <b>System WebView is too old</b> (${esc(f.detail)}) — the Home Assistant dashboard may render blank. " +
            "<a href=\"${webViewUpdateSteps(profileId())}\" target=\"_blank\" rel=\"noopener\">How &amp; why to update</a> (target Chromium ${PanelHealth.MIN_CHROMIUM}+)."
    HealthAudit.Kind.NO_RENDERER ->
        "ℹ <b>Next: choose a dashboard renderer.</b> Select Panel Assistant's built-in renderer, install the Home Assistant Companion app, or configure another dashboard package."
    HealthAudit.Kind.UPDATE -> f.update!!.let { u ->
        "⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available (installed ${esc(u.currentVersion)}) — " +
            "<a href=\"${esc(u.releaseUrl)}\" target=\"_blank\" rel=\"noopener\">download</a>"
    }
    HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
        "⚠ <b>Newer database preserved after a version downgrade</b> (${esc(f.detail)}) — this build opened a " +
            "fresh state store because its schema is older; some settings may have reset. The previous database " +
            "is preserved on the panel for recovery. Check Configure or restore a backup."
}
