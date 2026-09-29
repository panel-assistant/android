package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.PanelStatus
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.control.ZigbeeHealthState
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.Json

internal fun panelBrowserTitle(
    friendlyName: String,
    section: String? = null,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
): String {
    val panel = friendlyName.trim().ifBlank { "ha-paneld" }
    val suffix = section?.trim().orEmpty()
    val title = if (suffix.isBlank()) panel else "$panel · $suffix"
    return if ('-' in versionName) "$versionCode · $title" else title
}

/** User-facing remediation for the renderer-specific recovery authority. */
internal fun dashboardRecoveryWarning(state: PanelStatus.DashboardRecoveryState): String? = when (state) {
    PanelStatus.DashboardRecoveryState.NONE -> null
    PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER ->
        "⛔ <b>Built-in renderer stopped retrying</b> after repeated WebView failures. " +
            "Update or repair System WebView, then use Reload dashboard from the panel navbar or Dashboard tab."
    PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER ->
        "⛔ <b>Dashboard app is crash-looping</b> — the watchdog stopped relaunching it to avoid a restart storm. " +
            "Reinstall or downgrade the dashboard/Companion app (see <a href=\"install\">updates</a>), or reboot the panel."
}

/** Same-order nullable overlay for `/status`; invalid cardinality omits the whole additive field. */
internal fun installWarningPresentationsJson(
    warnings: List<String>,
    presentations: List<InstallPresentation?>,
): String? {
    if (warnings.size != presentations.size || warnings.size > 11) return null
    return presentations.joinToString(separator = ",", prefix = "[", postfix = "]") { it?.json() ?: "null" }
}

/** Render one trusted Dashboard control without accepting pre-quoted HTML attribute fragments. */
internal fun dashboardControlButtonHtml(
    action: String,
    labelHtml: String,
    disabledReason: String?,
    style: String = "",
): String {
    require(action.matches(Regex("[a-z_]+"))) { "invalid Dashboard control action" }
    fun attr(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val styleAttr = style.takeIf(String::isNotBlank)?.let { " style=\"${attr(it)}\"" }.orEmpty()
    val titleAttr = disabledReason?.let { " title=\"${attr(it)}\"" }.orEmpty()
    val disabledAttr = if (disabledReason != null) " disabled" else ""
    return "<button class=\"pbtn\"$styleAttr$titleAttr onclick=\"act('$action')\"$disabledAttr>$labelHtml</button>"
}

internal fun installFormWantsHtml(accept: String?): Boolean =
    accept?.contains("text/html", ignoreCase = true) == true

/** JSON projected into browser pages. Install, Entities and shared Runtime controls need per-key
 * provenance to distinguish a genuine translation from an English compatibility fallback. */
internal fun browserI18nPayload(strings: AppStrings, prefixes: Set<String>): String {
    val resolved = strings.resolved(prefixes)
    val entries = resolved.entries.joinToString(",") { (key, localized) ->
        "${Json.str(key)}:${Json.str(localized.text)}"
    }
    val provenance = if (
        "entities." in prefixes || "install." in prefixes || "runtime." in prefixes
    ) {
        val languages = resolved.entries.joinToString(",") { (key, localized) ->
            "${Json.str(key)}:${Json.str(localized.language)}"
        }
        ",\"languages\":{$languages}"
    } else ""
    return "{\"locale\":${Json.str(strings.requestedLocale)},\"strings\":{$entries}$provenance}"
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}

internal fun zigbeeWarningText(snapshot: ZigbeeHealthSnapshot, configuredOn: Boolean): String? = when {
    snapshot.state == ZigbeeHealthState.CONTAINED ->
        "⛔ <b>Zigbee gateway runaway was contained</b> — the router switch was turned OFF after sustained unjoined high CPU or repeated restarts."
    snapshot.state == ZigbeeHealthState.CONTAINMENT_FAILED ->
        "⛔ <b>Zigbee gateway containment was incomplete</b> — the respawner was stopped where possible and surviving work was demoted. Review diagnostics before retrying."
    snapshot.state == ZigbeeHealthState.RUNAWAY ->
        "⛔ <b>Zigbee gateway is runaway</b> — automatic containment is in progress."
    snapshot.state == ZigbeeHealthState.DEGRADED_HIGH_CPU ->
        "⚠ <b>Joined Zigbee gateway has sustained high CPU</b> — it remains running because joined routers are warn-only."
    snapshot.state == ZigbeeHealthState.DEGRADED_UNJOINED && configuredOn ->
        "⚠ <b>Zigbee router is enabled but not joined</b> — repeated join retries can consume substantial CPU. " +
            "Join this panel to your Zigbee coordinator or turn the Zigbee router switch OFF. " +
            "<a href=\"configure#cfg-zigbee_join\">Resolve Zigbee setup →</a>"
    snapshot.recursiveWatchdogAssignment ->
        "⚠ <b>Legacy Zigbee watchdog defect detected</b> — the exact recursive LD_LIBRARY_PATH assignment is present. ha-paneld will not edit the vendor script automatically."
    else -> null
}
