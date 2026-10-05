package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

internal fun hardenedApprovalA11yAttrs(
    conditional: Boolean = false,
    strings: AppStrings,
): String {
    val description = if (conditional) "hardened-approval-conditional-description" else "hardened-approval-description"
    val title = strings.get(
        if (conditional) "configure.hardened.setting_approval" else "configure.hardened.action_approval",
    )
    return """ aria-describedby="$description" title="${esc(title)}""""
}

internal fun hardenedApprovalAttrs(
    conditional: Boolean = false,
    strings: AppStrings,
): String =
    """ data-hardened-approval${if (conditional) "=\"conditional\"" else ""}${hardenedApprovalA11yAttrs(conditional, strings)}"""

internal fun hardenedApprovalCardTitle(
    title: String,
    badge: String = "",
    conditional: Boolean = false,
    strings: AppStrings,
): String {
    val description = if (conditional) "hardened-approval-section-conditional-description" else "hardened-approval-section-description"
    val explanation = strings.get(
        if (conditional) "shell.hardened.section_conditional" else "shell.hardened.section",
    )
    val marker = if (conditional) "=\"conditional\"" else ""
    return """<h2 data-hardened-approval$marker aria-describedby="$description" title="${esc(explanation)}">$title$badge</h2>"""
}
