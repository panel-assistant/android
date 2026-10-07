package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.MigrationNotice
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.Strings as AppStrings

// ---- tabbed multi-page shell ----
/** Shared page chrome; service observations remain supplied by the HTTP server. */
internal class PageShell(
    private val config: Config,
    private val catalogueLoader: CatalogueLoader,
    private val setupNeedsUser: () -> Boolean,
    private val buildToken: () -> String,
    private val renderConfigConcurrencyHash: () -> String,
) {
    private fun hardenedApprovalDescription(strings: AppStrings): String =
        """<span id="hardened-approval-description" class="sr-only">${esc(strings.get("configure.hardened.action_approval"))}</span>""" +
            """<span id="hardened-approval-conditional-description" class="sr-only">${esc(strings.get("configure.hardened.setting_approval"))}</span>""" +
            """<span id="hardened-approval-section-description" class="sr-only">${esc(strings.get("shell.hardened.section"))}</span>""" +
            """<span id="hardened-approval-section-conditional-description" class="sr-only">${esc(strings.get("shell.hardened.section_conditional"))}</span>"""

    private fun hardenedApprovalKey(top: Boolean = false, strings: AppStrings): String =
        """<p class="hardened-approval-key${if (top) " top" else ""}">${esc(strings.get("shell.hardened.key"))}</p>"""

    /** The shared tab bar; [active] highlights the current page. */
    private fun navBar(
        active: String,
        strings: AppStrings,
        preserveExplicitEnglish: Boolean = false,
        hiddenTabs: Set<String> = emptySet(),
    ): String {
        // Hiding is presentation for Panel Assistant's sidebar, never access control: the route still answers.
        fun tab(id: String, href: String, label: String): String = if (id in hiddenTabs) "" else
            """<a href="${setupHref(href, strings, preserveExplicitEnglish)}"${if (id == active) " class=\"active\"" else ""}>${esc(label)}</a>"""
        // The guided setup tab exists only while the journey is unfinished, then disappears — a healthy
        // panel's navigation is exactly what it was before the wizard existed. Placed first because on an
        // unfinished panel it IS the primary destination (the QR points at it).
        val setup = if (setupNeedsUser()) {
            tab("setup", "setup", strings.get("shell.nav.setup"))
        } else ""
        return "<div class=\"nav\">" +
            setup +
            tab("dashboard", "./", strings.get("shell.nav.dashboard")) +
            tab("configure", "configure", strings.get("shell.nav.configure")) +
            tab("profiles", "profiles", strings.get("shell.nav.profile")) +
            tab("entities", "entities", strings.get("shell.nav.entities")) +
            tab("install", "install", strings.get("shell.nav.install")) +
            tab("logs", "logs", strings.get("shell.nav.logs")) +
            (if ("api" in hiddenTabs) "" else """<a href="${setupHref("api", strings, preserveExplicitEnglish)}">API</a>""") +
            "</div>"
    }

    /**
     * The one page shell shared by every :8888 surface. The tabbed pages (page()) and the dashboard
     * (infoHtml()) render byte-identical chrome — doctype, theme-pin script, header, nav bar and the
     * buildwatch reload bar — through this single builder; only the per-surface deltas are passed in:
     * the body data-attributes, the header right-hand controls, the body markup itself, and any extra
     * scripts loaded ahead of the shared switcher/buildwatch pair.
     */
    fun pageShell(
        active: String,
        sectionTitle: String? = null,
        bodyAttrs: String,
        rightControls: String,
        body: String,
        extraScripts: String = "",
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
        translationPrefixes: Set<String> = setOf("shell."),
        preserveExplicitEnglish: Boolean = false,
        embed: EmbedMode? = null,
    ): String {
        // Capture panel identity once so title, switcher metadata and visible name cannot disagree if a
        // concurrent config save replaces the live identity while this response is being rendered.
        val rawPanelId = config.panelId
        val rawFriendlyName = config.friendlyName
        val panelId = esc(rawPanelId)
        val friendlyName = esc(rawFriendlyName)
        val title = esc(panelBrowserTitle(rawFriendlyName, sectionTitle))
        // Embedded in Panel Assistant's sidebar, Home Assistant owns the top menu: the header and the mDNS
        // switcher (whose links leave the proxy) are omitted, and the tab bar and everything below it stay.
        // Only validated enum values reach the markup.
        val themeAttr = embed?.theme?.let { """ data-theme="$it"""" }.orEmpty()
        val embedAttr = if (embed != null) " data-embedded" else ""
        // Embedded keeps only the panel identity install.js names downloads from, as escaped attributes.
        val header = if (embed != null) """<span id="pswitch" hidden data-self-id="$panelId" data-self-name="$friendlyName"></span>""" else """<div class="hdr"><button id="navburger" class="navburger pbtn" aria-label="${esc(strings.get("shell.menu.label"))}">☰</button><h1><img src="icon.svg" class="logo" alt=""><span class="brand">ha-paneld</span> <small id="pswitch" data-self-id="$panelId" data-self-name="$friendlyName"><span class="sep">·</span>$friendlyName</small></h1>
 <span style="display:flex;gap:10px;align-items:center">$rightControls</span></div>
"""
        val switcher = if (embed != null) "" else """<!-- Load switcher.js immediately after the header it measures so responsive collapse finishes before page
     content is parsed and publishes the final header height without causing a post-paint card-wall shift. -->
<script src="assets/switcher.js"></script>
"""
        val migrationNotice = """<div id="migrationbar" class="setup"${if (config.migrationNoticeVisible()) "" else " style=\"display:none\""}>⚠ <b>${esc(strings.get("shell.migration.title"))}</b> ${esc(strings.get("shell.migration.body"))} <a href="${MigrationNotice.URL}" target="_blank" rel="noopener">${esc(MigrationNotice.URL)}</a> <button id="migration-dismiss" class="pbtn" type="button">${esc(strings.get("shell.migration.dismiss"))}</button></div>"""
        return """<!doctype html><html lang="${esc(strings.requestedLocale)}"$themeAttr><head><base href="/"><meta charset="utf-8">
<script>/* ?theme=light|dark pins the UI theme for testing (else the browser preference rules) */
(function(){var m=location.search.match(/[?&]theme=(dark|light)\b/);if(m)document.documentElement.setAttribute("data-theme",m[1])})();</script>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<link rel="icon" type="image/svg+xml" href="favicon.svg">
<link rel="stylesheet" href="info.css">
<script id="ha-i18n" type="application/json">${browserI18nPayload(strings, translationPrefixes)}</script>
<script src="assets/i18n.js"></script></head><body $bodyAttrs$embedAttr><div class="wrap">
<div class="topbar">$header${navBar(active, strings, preserveExplicitEnglish, embed?.hiddenTabs.orEmpty())}</div>
$switcher<div id="halifebar" class="setup" style="display:none"></div>
<div id="hanetbar" class="setup" style="display:none"></div>
$migrationNotice
<div id="verbar" class="setup" style="display:none">⟳ ${esc(strings.get("shell.new_version.installed"))} — <a href="#" onclick="location.reload();return false">${esc(strings.get("shell.action.reload"))}</a> ${esc(strings.get("shell.new_version.refresh_suffix"))}</div>
$body
$extraScripts<script src="assets/power-safety.js"></script>
<script src="assets/buildwatch.js"></script>
</div></body></html>"""
    }

    /** Shared page shell (header + tab bar + body) for the non-dashboard tabs. */
    fun page(
        active: String,
        title: String,
        body: String,
        strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
        embed: EmbedMode? = null,
    ): String {
        val haLink = if (config.haLinkUrl.isNotBlank())
            """<a class="pbtn" href="${esc(config.haLinkUrl)}" target="_blank" rel="noopener">${esc(strings.get("shell.open_in_ha"))}</a>""" else ""
        val approvalKey = if (active in setOf("configure", "install")) {
            hardenedApprovalKey(top = active == "install", strings = strings)
        } else {
            ""
        }
        val approvalKeyBefore = approvalKey.takeIf { active == "install" }.orEmpty()
        val approvalKeyAfter = approvalKey.takeIf { active != "install" }.orEmpty()
        // While setup is unfinished, Configure carries a `commissioning` body class: a first-time user on
        // the full settings wall may not know a Save button exists at all, so an unsaved change there gets
        // a throb (see info.css). Pure function of journey state — no dismissal memory, so it can never
        // stick on, and a finished panel's Configure is byte-identical to before the wizard existed.
        // Urgency treatment only while a person actually owes an action; a configured panel whose render proof
        // is merely being re-earned after a restart must not get a throbbing Save button.
        val commissioning = active == "configure" && setupNeedsUser()
        return pageShell(
            active = active,
            sectionTitle = title,
            bodyAttrs = (if (commissioning) """class="commissioning" """ else "") +
                """data-build="${buildToken()}" data-cfg="${renderConfigConcurrencyHash()}"""",
            rightControls = "$haLink${ghLink(strings)}",
            body = """${hardenedApprovalDescription(strings)}
$approvalKeyBefore
$body
$approvalKeyAfter""",
            strings = strings,
            translationPrefixes = setOf("shell.", "$active.", "runtime."),
            embed = embed,
        )
    }

}
/** The GitHub-repository icon link shown in the header of every :8888 surface. */
internal fun ghLink(strings: AppStrings): String =
    """<a class="gh" href="$REPO_URL" target="_blank" rel="noopener" title="${esc(strings.get("shell.github.title"))}" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a>"""


internal const val REPO_URL = "https://github.com/panel-assistant/android"
// GitHub mark (official, CC0 simple-icons) + Material "open in new" glyph — icon links in the UI.
internal const val GH_ICON = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
