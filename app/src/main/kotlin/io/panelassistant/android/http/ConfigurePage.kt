package io.panelassistant.android.http

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.i18n.Strings as AppStrings

/** Configure tab — schema-driven settings, each change sent as it is made (configure.js). */
internal fun configureBody(strings: AppStrings, proximityLearningEnabled: Boolean, setup: String, panelUrl: String?): String {
    val proximityMount = if (proximityLearningEnabled) """<div id="proximity-learning-mount" hidden></div>""" else ""
    val proximityScript = if (proximityLearningEnabled) """<script src="assets/proximity-learning.js"></script>""" else ""
    return """
<div id="cfg-tools" class="cfg-tools" data-app-version="${esc(BuildConfig.VERSION_NAME)}"><input id="cfg-filter" class="cfg-filter" type="search" autocomplete="off" placeholder="${esc(strings.get("configure.filter.placeholder"))}" aria-label="${esc(strings.get("configure.filter.label"))}"><div class="seg" role="radiogroup" aria-label="${esc(strings.get("configure.tier.label"))}"><label><input type="radio" name="cfg-tier" id="tier-basic" value="basic" checked>${esc(strings.get("configure.tab.basic"))}</label><label><input type="radio" name="cfg-tier" id="tier-adv" value="advanced">${esc(strings.get("configure.tab.advanced"))}</label></div><label class="cfg-desc-switch"><input type="checkbox" id="cfg-desc" checked><span class="toggle" aria-hidden="true"></span>${esc(strings.get("configure.descriptions"))}</label><span id="cfg-count" class="muted cfg-count" aria-live="polite"></span></div>
$setup
<div id="cfg-status" class="muted" style="margin-bottom:10px">${esc(strings.get("configure.status.loading"))}</div>
<div id="cfg-all-cards">
<div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1" data-card-size-proximity="${if (proximityLearningEnabled) "1" else "0"}"></div>
$proximityMount</div>
<div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div>
<div id="cfg-help" class="cfg-help" data-panel-url="${esc(panelUrl.orEmpty())}" popover="manual" role="dialog" aria-labelledby="cfg-help-title"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" class="cfg-help-close" type="button" aria-label="${esc(strings.get("configure.help.close"))}">×</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more" target="_blank" rel="noopener">${esc(strings.get("configure.help.more"))}</a></div></div>
<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="assets/configure-state.js"></script>
<script src="assets/configure-view.js"></script>
<script src="assets/configure-help.js"></script>
<script src="assets/configure-controls.js"></script>
<script src="assets/configure-brightness.js"></script>
<script src="assets/configure-auto-sleep.js"></script>
<script src="assets/configure-cards.js"></script>
<script src="assets/configure-render.js"></script>
<script src="assets/configure-voice-tuning.js"></script>
<script src="assets/configure.js"></script>
$proximityScript"""
}
