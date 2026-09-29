package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

/** Configure tab — schema-driven, save-together settings only. */
internal fun configureBody(strings: AppStrings, proximityLearningEnabled: Boolean, setup: String): String {
    val proximityMount = if (proximityLearningEnabled) """<div id="proximity-learning-mount" hidden></div>""" else ""
    val proximityScript = if (proximityLearningEnabled) """<script src="assets/proximity-learning.js"></script>""" else ""
    return """
<!-- Basic/Advanced tab bar hidden until every setting is assigned a Basic/Advanced tier; with it hidden
     the form shows ALL settings (configure.js defaults `advanced=true`), so nothing is lost. The tier
     machinery (SettingSpec.tier + cfgTab) stays in place — restore the bar once tiers are curated. -->
<div class="cfg-tabs" style="display:none"><button id="tab-basic" onclick="cfgTab(false)">${esc(strings.get("configure.tab.basic"))}</button><button id="tab-adv" class="on" onclick="cfgTab(true)">${esc(strings.get("configure.tab.advanced"))}</button></div>
$setup
<div id="cfg-status" class="muted" style="margin-bottom:10px">${esc(strings.get("configure.status.loading"))}</div>
<div id="cfg-all-cards">
<div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1" data-card-size-proximity="${if (proximityLearningEnabled) "1" else "0"}"></div>
$proximityMount</div>
<div id="savebar" class="savebar" role="region" aria-label="${esc(strings.get("configure.unsaved.label"))}" hidden><button id="savebtn" type="button" disabled onclick="cfgSave()">${esc(strings.get("configure.action.save"))}</button><span id="cfg-msg" class="muted" role="status" aria-live="polite" aria-atomic="true"></span></div>
<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="assets/configure.js"></script>
$proximityScript"""
}
