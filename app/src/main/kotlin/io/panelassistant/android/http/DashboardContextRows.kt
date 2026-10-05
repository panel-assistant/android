package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.logship.LOG_SHIP_STATUS_OFF

internal fun contextRowsHtml(
    keys: List<String>,
    reportingQuirk: String?,
    strings: AppStrings,
    valueFor: (String) -> String?,
): String {
    val rows = keys.mapNotNull { key ->
        val current = valueFor(key)
        // Log shipping earns a live row only while it is on; when it is off the Behaviour card's
        // "Ship logs" already says so, and a permanent "off" here is noise.
        current?.takeUnless { key == "Log shipping" && it == LOG_SHIP_STATUS_OFF }?.let { value ->
            val label = factLabel(key, strings)
            val cellId = when (key) {
                "HA lifecycle" -> " id=\"halifecell\""
                "HA network path" -> " id=\"hanetcell\""
                else -> ""
            }
            "<tr><th>${esc(label)}</th><td$cellId>${esc(localizedRuntimeValue(key, value, strings))}</td></tr>"
        }
    }.toMutableList()
    reportingQuirk?.let {
        rows += "<tr><th>${esc(factLabel("System WebView reporting", strings))}</th><td>${esc(it)}</td></tr>"
    }
    return rows.joinToString("\n")
}
