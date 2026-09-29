package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.DisplaySizingObservation

/** Display-sizing card (density + text scale). Empty when su isn't reachable (no control). */
internal fun displayCardHtml(
    typedShellReady: Boolean,
    sizing: DisplaySizingObservation,
    strings: AppStrings,
    recommendedDensity: Int?,
    recommendedFontScale: Float?,
): String {
    // The POST primes densityCache, so peek preserves immediate post-write values without turning
    // Install rendering into another privileged probe. Cold startup already populated the snapshot.
    val (curOverride, base, fs) = sizing
    // Shown even without root (density can't be READ without it either) so a no-root user sees the
    // feature — but greyed, with a lock banner, and every control disabled. `dis` toggles all of it.
    val locked = !typedShellReady
    // Prefill: the active override if one is set, else the profile's HA-optimised recommendation
    // (so a fresh panel offers the right value to Apply rather than the factory base), else the base.
    val cur = curOverride?.takeIf { it != base } ?: recommendedDensity ?: base ?: DensityController.MIN_DPI
    val densityHint = recommendedDensity?.let { formattedString(strings, "install.display.profile_recommendation", "value" to it.toString()) }
        ?: formattedString(strings, "install.display.firmware_default", "value" to (base?.toString() ?: "?"))
    val resetTitle = base?.let { formattedString(strings, "install.display.reset_default_with_dpi", "value" to it.toString()) }
        ?: strings.get("install.display.reset_default")
    val dis = if (locked) " disabled" else ""
    val rec = if (!locked && (recommendedDensity != null || recommendedFontScale != null))
        """ <button type="submit" name="action" value="rec"${hardenedApprovalA11yAttrs(strings = strings)} formnovalidate>${esc(strings.get("install.display.ha_optimised"))}</button>""" else ""
    val lock = if (locked) privilegedLockBanner(strings.get("install.display.root_required"), strings) else ""
    val badge = """<span class="cardbadge exp">${esc(strings.get("install.display.badge.experimental"))}</span>"""
    val title = if (!locked) hardenedApprovalCardTitle(esc(strings.get("install.display.title")), badge, strings = strings) else "<h2>${esc(strings.get("install.display.title"))}$badge</h2>"
    return """<div class="card" id="cfg-display" data-layout-key="display-sizing">$title
$lock<p class="note">${esc(strings.get("install.display.description"))}</p>
<form method="post" action="${localizedHref("api/v1/display/density", strings)}" class="${if (locked) "locked" else ""}" style="display:flex;flex-direction:column;gap:10px">
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>${esc(strings.get("install.display.logical_density"))} <small style="color:#888">· ${esc(densityHint)}</small></span>
  <input name="density" type="number" min="${DensityController.MIN_DPI}" max="${DensityController.MAX_DPI}" value="$cur" style="width:96px"$dis>
 </label>
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>${esc(strings.get("install.display.text_size"))} <small style="color:#888">· ${esc(strings.get("install.display.default_scale"))}</small></span>
  <input name="font" type="number" step="0.05" min="${DensityController.MIN_FONT}" max="${DensityController.MAX_FONT}" value="$fs" style="width:96px"$dis>
 </label>
 <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:2px">
  <button type="submit"${hardenedApprovalA11yAttrs(strings = strings)}$dis>${esc(strings.get("install.display.apply"))}</button>$rec
  <button type="submit" name="action" value="reset" aria-describedby="hardened-approval-description" formnovalidate title="${esc(resetTitle)} · ${esc(strings.get("configure.hardened.action_approval"))}"$dis>${esc(strings.get("install.display.reset"))}</button>
 </div>
</form></div>"""
}
