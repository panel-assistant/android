package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.control.PrivilegedRouteObservation

/** The Controls-card button rows. [facts] null (cold shell) → everything disabled as "checking…";
 *  hydration swaps in the capability-gated real state. */
internal fun controlsHtml(
    facts: Map<String, String>?,
    privilege: PrivilegedRouteObservation?,
    hasRecents: Boolean,
    distinctLauncher: () -> Boolean,
    strings: AppStrings,
): String {
    // Controls buttons: render but DISABLE (not hide, not silently-broken) when the action's capability
    // is missing — back/recents accept Accessibility or Shizuku input; launcher/reboot need root.
    val a11yOk = facts?.get("Nav actions (a11y)") == "yes"
    val navigation = ControlAvailability.navigation(
        accessibilityReady = a11yOk,
        shizukuReady = privilege?.shizuku?.ready == true,
        hasRecents = hasRecents,
    )
    // Recents is only real where the firmware has an overview screen — KEYCODE_APP_SWITCH no-ops on
    // single-purpose panels, so the policy gates it on the profile rather than show a dead one.
    val rootOk = privilege?.rootControlReady == true
    val checking = facts == null
    fun pbtn(
        action: String,
        label: String,
        ok: Boolean,
        needsKey: String,
        style: String = "",
        disabledTitle: String? = null,
    ): String {
        val disabledReason = when {
            checking -> strings.get("dashboard.controls.checking_capabilities")
            !ok -> strings.get(needsKey)
            disabledTitle != null -> disabledTitle
            else -> null
        }
        return dashboardControlButtonHtml(action, label, disabledReason, style)
    }
    // "Launcher" opens the best real home-screen launcher; "Admin launcher" always opens ha-paneld's
    // own. When no separate launcher exists (e.g. the vendor kiosk is tamed), "Launcher" would just
    // fall through to the admin launcher — so DISABLE it rather than show two buttons that do the same
    // thing. resolvedLauncher() is a cheap PackageManager query (no root).
    val hasDistinctLauncher = !checking &&
        distinctLauncher()
    // Launcher / Admin launcher / Reboot need root; a disabled button's tooltip is invisible on a
    // touch panel, so add a visible note that accurately reflects the remaining navigation routes.
    val rootNote = if (!checking && !rootOk)
        """<div class="setup rootlock" style="margin:0 0 8px">🔒 ${esc(strings.get("dashboard.controls.root_required_note"))}</div>""" else ""
    return """$rootNote<div class="ctlrow">
 ${pbtn("back", "←<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.back"))}</span>", navigation.backEnabled, "dashboard.controls.input_required")}
 ${pbtn("recents", "▢<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.recents"))}</span>", navigation.recentsEnabled, "dashboard.controls.input_required")}
 ${pbtn("launcher", "⊞<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.launcher"))}</span>", rootOk, "dashboard.controls.root_required", "margin-left:auto", disabledTitle = if (hasDistinctLauncher) null else strings.get("dashboard.controls.no_separate_launcher"))}
 ${pbtn("admin_launcher", "⚙<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.admin_launcher"))}</span>", rootOk, "dashboard.controls.root_required")}
</div>
<div class="ctlrow ctlrow-secondary">
 ${pbtn("dashboard", "⌂<span class=\"lbl\"> ${esc(strings.get("dashboard.controls.dashboard"))}</span>", !checking, "dashboard.controls.unavailable")}
 ${pbtn("reload", "↻ ${esc(strings.get("dashboard.controls.reload"))}", !checking, "dashboard.controls.unavailable", "border-color:#7a6330;color:#f5cf82")}
 ${pbtn("reboot", "⟳ ${esc(strings.get("dashboard.controls.reboot"))}", rootOk, "dashboard.controls.root_required", "margin-left:auto;border-color:#7a3a2a;color:#f5a08a")}
</div>"""
}
