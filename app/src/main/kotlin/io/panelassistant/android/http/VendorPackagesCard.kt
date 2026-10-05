package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.control.TameController
import io.panelassistant.android.util.Json

internal fun localizedTameGroupTitle(title: String, strings: AppStrings): String = when (title) {
    "Recommended for this panel" -> strings.get("install.tame.group.recommended")
    "Other apps" -> strings.get("install.tame.group.other")
    "Using the most CPU" -> strings.get("install.tame.group.cpu")
    else -> title
}

internal fun localizedTameGroupHint(hint: String, strings: AppStrings): String = when (hint) {
    "Known intrusive firmware apps for your hardware — safe first picks." ->
        strings.get("install.tame.group.recommended_hint")
    "Apps on this panel that aren't part of core Android." -> strings.get("install.tame.group.other_hint")
    "Top CPU users right now. Core/system ones are shown for context but can't be disabled; only tame a vendor app you recognise." ->
        strings.get("install.tame.group.cpu_hint")
    else -> hint
}

/** One Vendor-packages row: label + package id, an optional state badge, and the single action button.
 *  Shared by the card and the picker. [showState] is false on the card — every row there is already
 *  tamed (disabled), so the column is redundant and just crowds the layout. */
internal fun tameRowHtml(
    c: TameController.Candidate,
    showState: Boolean = true,
    disabled: Boolean = false,
    strings: AppStrings,
): String {
    val tamed = c.blocked || c.disabled
    val state = if (!showState) "" else when {
        !c.installed -> """<span style="width:80px;text-align:right;font-size:.85em;color:var(--dim)">${esc(strings.get("install.shared.not_installed"))}</span>"""
        c.disabled -> """<span style="width:80px;text-align:right;font-size:.85em;color:#d9a528">${esc(strings.get("install.tame.state.disabled"))}</span>"""
        else -> """<span style="width:80px;text-align:right;font-size:.85em;color:#3fb950">${esc(strings.get("install.tame.state.active"))}</span>"""
    }
    val action = if (tamed) "untame" else "tame"
    val label = strings.get(if (tamed) "install.tame.action.reenable" else "install.tame.action.tame")
    val btn = if (tamed) "" else "background:#7a2e2e;border-color:#7a2e2e"
    // Tags (authored or heuristic: core/vendor/user/overlay) after the label; note below the package id.
    val tags = c.tags.joinToString("") { tag ->
        val localized = when (tag.lowercase(java.util.Locale.ROOT)) {
            "core" -> strings.get("install.tame.tag.core")
            "vendor" -> strings.get("install.tame.tag.vendor")
            "user" -> strings.get("install.tame.tag.user")
            "overlay" -> strings.get("install.tame.tag.overlay")
            else -> tag
        }
        """<span class="vtag">${esc(localized)}</span>"""
    }
    // A "recommended" badge marks the profile's defaultTame picks (safe first picks / the "Tame all
    // recommended" set) while they're still active.
    val recBadge = if (c.recommended && !tamed)
        """<span class="vtag rec">${esc(strings.get("install.tame.badge.recommended"))}</span>""" else ""
    val note = if (c.note.isNotBlank())
        """<br><small style="color:#9aa">${esc(c.note)}</small>""" else ""
    // A non-removable package (core Android / dashboard / ourselves) is shown for context with a muted
    // "protected" label where the action button would be — no way to disable it.
    val control = if (!c.removable)
        """<span style="font-size:.8em;color:#777;white-space:nowrap">${esc(strings.get("install.tame.state.protected"))}</span>"""
    else
        """<form method="post" action="${localizedHref("api/v1/tame", strings)}" style="margin:0"><input type="hidden" name="pkg" value="${esc(c.pkg)}"><input type="hidden" name="action" value="$action"><button type="submit"${hardenedApprovalA11yAttrs(strings = strings)} style="$btn;white-space:nowrap"${if (disabled) " disabled" else ""}>${esc(label)}</button></form>"""
    return """  <div style="display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #222">
   <span style="flex:1;min-width:0;overflow:hidden">${esc(c.label)}$recBadge$tags<br><small style="color:#888">${esc(c.pkg)}</small>$note</span>
   $state
   $control
  </div>"""
}

/**
 * Standalone "Vendor packages" card. Taming intrusive firmware apps is a distinct, deploy-time concept
 * — not part of basic configuration — so it gets its own card with **per-package action buttons**, not
 * a checkbox list behind a shared Save (which made "did it apply?" and "how do I remove one?" unclear).
 * Each row acts immediately via `POST /tame`: an active app offers **Tame**, a tamed/disabled one offers
 * **Re-enable**. A free-text box tames any package by name. Hidden where no privileged path exists (taming
 * needs root or the helper daemon). Critical / HA / own packages are never listed.
 */
internal fun tameCardHtml(rootReady: Boolean, strings: AppStrings, candidates: () -> List<TameController.Candidate>): String {
    // Root-gated, but shown (never hidden) so a no-root user sees the feature: the profile's candidate
    // vendor apps are listed greyed with a lock banner, actions disabled. Discovery (PackageManager)
    // needs no root; the tame/re-enable ACTIONS do.
    val locked = !rootReady
    // The card shows what's currently TAMED (the blocklist); discovery lives in the Find-a-package
    // picker. So a tamed package always has a visible Re-enable here. When locked, fall back to the
    // profile's candidate list so there's something to show.
    val cands = runCatching {
        candidates()
    }.getOrDefault(emptyList())
    val rows = cands.joinToString("\n") { tameRowHtml(it, showState = false, disabled = locked, strings = strings) }
    val body = when {
        locked -> """<div class="locked">${rows.ifBlank { """<p class="note">${esc(strings.get("install.tame.locked_empty"))}</p>""" }}</div>"""
        else -> rows.ifBlank {
            """<p class="note">${esc(strings.get("install.tame.empty"))}</p>"""
        }
    }
    val dis = if (locked) " disabled" else ""
    val lock = if (locked) rootLockBanner(strings.get("install.tame.root_required"), strings) else ""
    val titleText = esc(strings.get("install.card.vendor_packages"))
    val title = if (!locked) hardenedApprovalCardTitle(titleText, conditional = true, strings = strings)
        else "<h2>$titleText</h2>"
    return """<div class="card" id="cfg-tame" data-layout-key="vendor-packages">$title
$lock<p class="note">${esc(strings.get("install.tame.description"))}</p>
$body
<div style="display:flex;flex-direction:column;gap:8px;margin-top:12px" class="${if (locked) "locked" else ""}">
 <button type="button" onclick="pkgPick()"$dis>${esc(strings.get("install.tame.find"))}</button>
 <form method="post" action="${localizedHref("api/v1/tame", strings)}" style="display:grid;grid-template-columns:1fr auto;gap:8px;margin:0">
  <label for="tame-pkg" style="grid-column:1/-1">${esc(strings.get("install.tame.package_name"))}</label>
  <input id="tame-pkg" name="pkg" autocapitalize="none" autocorrect="off" spellcheck="false" required pattern="[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*" maxlength="255" aria-describedby="tame-pkg-hint" placeholder="io.example.app" style="min-width:0"$dis oninput="updateTamePackageSubmit()">
  <input type="hidden" name="action" value="tame">
  <button id="tame-package-submit" type="submit"${hardenedApprovalA11yAttrs(strings = strings)}$dis>${esc(strings.get("install.tame.action.tame"))}</button>
  <small id="tame-pkg-hint" class="note" style="grid-column:1/-1">${esc(strings.get("install.tame.package_hint"))}</small>
 </form>
</div>
<div id="hand-back-home" style="margin-top:16px;padding-top:12px;border-top:1px solid #222">
 <h3 style="margin:0 0 4px">${esc(strings.get("install.tame.hand_back.title"))}</h3>
 <p class="note" style="margin:0 0 4px">${esc(strings.get("install.tame.hand_back.description"))}</p>
 <p class="note" style="margin:0 0 4px"><strong>${esc(strings.get("install.tame.hand_back.warning"))}</strong></p>
 <p class="note" style="margin:0 0 8px">${esc(strings.get("install.tame.hand_back.scope"))}</p>
 <button id="hand-back-home-button" type="button" onclick="handBackHome()"${hardenedApprovalA11yAttrs(strings = strings)}$dis>${esc(strings.get("install.tame.hand_back.action"))}</button>
 <p id="hand-back-home-status" class="note" role="status" aria-live="polite" style="margin:8px 0 0"></p>
</div>
<dialog id="pkgdlg" style="background:#1a1a1a;color:#eee;border:1px solid #333;border-radius:12px;max-width:520px;width:92%;padding:16px">
 <h3 data-hardened-approval="conditional" aria-describedby="hardened-approval-section-conditional-description" title="${esc(strings.get("shell.hardened.section_conditional"))}" style="margin:0 0 4px">${esc(strings.get("install.tame.dialog.title"))}</h3>
 <p class="note" style="margin:0 0 8px">${esc(strings.get("install.tame.dialog.description"))}</p>
 <div id="pkgdlgbody" style="max-height:55vh;overflow:auto">${esc(strings.get("install.shared.loading"))}</div>
 <form method="dialog" style="margin-top:12px;text-align:right"><button>${esc(strings.get("install.shared.close"))}</button></form>
</dialog>
<script>function pkgPick(){var d=document.getElementById('pkgdlg');d.showModal();
document.getElementById('pkgdlgbody').textContent=${Json.str(strings.get("install.shared.loading"))};
fetch(${Json.str(localizedHref("api/v1/tame/suggest", strings))}).then(function(r){return r.text()}).then(function(t){document.getElementById('pkgdlgbody').innerHTML=t}).catch(function(){document.getElementById('pkgdlgbody').textContent=${Json.str(strings.get("install.tame.dialog.list_failed"))};});}
function updateTamePackageSubmit(){var input=document.getElementById('tame-pkg'),button=document.getElementById('tame-package-submit');if(!input||!button)return;button.disabled=input.disabled||!input.checkValidity();}updateTamePackageSubmit();
function handBackHome(){var b=document.getElementById('hand-back-home-button'),s=document.getElementById('hand-back-home-status');if(!b||!s)return;b.disabled=true;s.textContent=${Json.str(strings.get("install.shared.loading"))};
fetch(${Json.str(localizedHref("api/v1/hand-back-home", strings))},{method:'POST'}).then(function(r){return r.json().then(function(j){return {status:r.status,body:j}})}).then(function(r){
if(r.status===200&&r.body&&r.body.home_handed_to){s.textContent=${Json.str(strings.get("install.tame.hand_back.done"))};return;}
b.disabled=false;s.textContent=${Json.str(strings.get("install.tame.hand_back.failed"))};
}).catch(function(){b.disabled=false;s.textContent=${Json.str(strings.get("install.tame.hand_back.failed"))};});}</script></div>"""
}
