package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

internal fun proximityLearningBanner(title: String, strings: AppStrings): String =
    """<div class="setup">👋 <b>${esc(strings.get(title))}</b>. """ +
        """${esc(strings.get("dashboard.banner.proximity_learning.touch_available"))} <a href="${localizedHref("configure#cfg-proximity-learning", strings)}">${esc(strings.get("dashboard.banner.proximity_learning.action"))}</a>.</div>"""

internal fun configureResumeBanner(strings: AppStrings): String =
    """<div class="setup info">${esc(strings.get("configure.setup.question"))} <a href="${localizedHref("setup", strings)}"><b>${esc(strings.get("configure.setup.link"))}</b></a> ${esc(strings.get("configure.setup.explanation"))}</div>"""

internal fun setupProgressBanner(progress: String, strings: AppStrings): String =
    """<div class="setup">⟳ ${esc(localizedSetupProgress(progress, strings))}</div>"""

internal fun configureSignInBanner(strings: AppStrings): String =
    """<div class="setup">🏠 <b>${esc(strings.get("configure.setup.ha_signin.title"))}</b> ${esc(strings.get("configure.setup.ha_signin.body"))}</div>"""

internal fun configureRendererBanner(strings: AppStrings): String =
    """<div class="setup">ℹ <b>${esc(strings.get("configure.setup.renderer.title"))}</b> ${esc(strings.get("configure.setup.renderer.body"))} <small>${esc(strings.get("configure.setup.renderer.note"))}</small></div>"""

internal fun configureStrategyBanner(strings: AppStrings): String =
    """<div class="setup info">ℹ <b>${esc(strings.get("configure.setup.strategy_allowed.title"))}</b> ${esc(strings.get("configure.setup.strategy_allowed.body"))} <a href="${localizedHref("entities", strings)}">${esc(strings.get("configure.setup.strategy_allowed.link"))}</a>.</div>"""

internal fun dashboardSetupNeedsBanner(needs: List<String>, strings: AppStrings): String =
    """<div class="setup">⚠ ${esc(strings.get("dashboard.banner.setup_needs.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(localizedSetupNeeds(needs, strings))}</a> ${esc(strings.get("dashboard.banner.setup_needs.suffix"))}</div>"""

internal fun dashboardPanelBridgeBanner(strings: AppStrings): String =
    """<div class="setup">⚠ ${esc(strings.get("dashboard.banner.panel_bridge_running"))}</div>"""
