package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.device.profile.ProfileLink
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

internal fun dashboardProfileNote(links: List<ProfileLink>, strings: AppStrings): String {
    val profileReferences = links.joinToString(" · ") { link ->
        val host = runCatching { java.net.URI(link.url).host }.getOrNull().orEmpty()
        val destination = host.takeIf { it.isNotBlank() }
            ?.let { """ · <bdi class="profile-reference-host" dir="ltr">${esc(it)}</bdi>""" }
            .orEmpty()
        """<a href="${esc(link.url)}" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer"><bdi class="profile-reference-label" dir="auto">${esc(link.label)}</bdi>$destination</a>"""
    }.takeIf { it.isNotBlank() }?.let { """<br><span class="profile-reference-links">$it</span>""" }.orEmpty()
    return """<p class="note">${esc(strings.get("dashboard.profile_note.prefix"))} <a href="$DEVICE_PROFILES_DOC" target="_blank" rel="noopener" style="color:#9cf">${esc(strings.get("dashboard.profile_note.link"))}</a>.$profileReferences</p>"""
}

internal fun dashboardScreenshotCard(
    cold: Boolean,
    canCapture: Boolean,
    cachedShot: String?,
    aspectRatio: () -> String,
    strings: AppStrings,
): String {
    val shotTitle = """<h2>${esc(strings.get("dashboard.card.screenshot"))} <small>· ${esc(strings.get("dashboard.card.live_panel"))}</small><a class="card-title-action" href="#" onclick="refreshScreenshot(this.closest('.card'));return false" title="${esc(strings.get("dashboard.screenshot.capture_title"))}">↻ ${esc(strings.get("dashboard.action.refresh"))}</a></h2>"""
    val shotInner = { src: String? ->
        val source = src?.let { """src="${esc(it)}"""" } ?: ""
        """<a class="shot" href="api/v1/screenshot.png" target="_blank" rel="noopener" title="${esc(strings.get("dashboard.screenshot.open_full_size"))}" data-error-label="${esc(strings.get("dashboard.screenshot.unavailable"))}" style="aspect-ratio:${aspectRatio()}"><img $source alt="${esc(strings.get("dashboard.screenshot.alt"))}" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')"></a>"""
    }
    return when {
        cold && cachedShot != null ->
            """<div class="card" id="shotcard" data-layout-key="screenshot" data-capture-ok="0">$shotTitle${shotInner(cachedShot)}</div>"""
        cold ->
            """<div class="card" id="shotcard" data-layout-key="screenshot" data-capture-ok="0" style="display:none">$shotTitle${shotInner(null)}</div>"""
        canCapture ->
            """<div class="card" id="shotcard" data-layout-key="screenshot" data-capture-ok="1">$shotTitle${shotInner(cachedShot)}</div>"""
        else -> ""
    }
}

internal fun dashboardCameraCard(present: Boolean, strings: AppStrings): String {
    // The camera card is a live measurement surface, so the server renders the shell and nothing
    // else: rows written here would be a reading from page-render time that the card could not
    // retract, which is the defect the lifecycle banner was deleted for. The poll owns every row.
    // A board whose profile declares no camera gets no card rather than an empty one — the same
    // rule the Camera row in Runtime diagnostics already follows.
    return if (!present) "" else
        """<div class="card" data-layout-key="camera-stream"><h2>${esc(strings.get("dashboard.camera.title"))} <small id="camhdr"></small></h2>
<table id="camtbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.reading"))}</td></tr></table>
<p class="note">${esc(strings.get("dashboard.camera.note"))} ${esc(strings.get("dashboard.camera.settings_on"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("dashboard.camera.configure_link"))}</a>.</p></div>"""
}

internal fun dashboardHeaderControls(config: Config, strings: AppStrings): String {
    val infoHaLink = if (config.haLinkUrl.isNotBlank())
        """<a class="pbtn" href="${esc(config.haLinkUrl)}" target="_blank" rel="noopener" title="${esc(strings.get("dashboard.open_in_ha.title"))}">${esc(strings.get("shell.open_in_ha"))}</a>""" else ""
    val revealBtn = """<button id="revbtn" class="pbtn" onclick="toggleReveal()" title="${esc(strings.get("dashboard.reveal.title"))}">${esc(strings.get("dashboard.action.reveal"))}</button>"""
    return "$infoHaLink$revealBtn ${ghLink(strings)}"
}

internal fun dashboardBody(
    config: Config,
    strings: AppStrings,
    profNote: String,
    shotCard: String,
    cameraCard: String,
    banners: String,
    controls: String,
    rowHtml: (String) -> String?,
): String {
    val placeholder = """<tr><td style="color:#888">${esc(strings.get("dashboard.status.reading"))}</td></tr>"""
    // One facts/value card: cold → placeholder rows (hydration fills or hides); warm → rows, and
    // an EMPTY card is omitted exactly as before.
    fun tcard(id: String, title: String, rows: String?, pre: String = "", post: String = ""): String = when {
        rows == null -> """<div class="card" data-layout-key="$id"><h2>${esc(title)}</h2>$pre<table id="$id">$placeholder</table>$post</div>"""
        rows.isBlank() -> ""
        else -> """<div class="card" data-layout-key="$id"><h2>${esc(title)}</h2>$pre<table id="$id">$rows</table>$post</div>"""
    }
    val capNote = """<p class="note"><a href="api/v1/diag" target="_blank" style="color:#9cf">⭳ ${esc(strings.get("dashboard.diagnostics_dump.link"))}</a> — ${esc(strings.get("dashboard.diagnostics_dump.explanation"))}</p>"""
    return """<div id="bannerzone">${banners}</div>
<div class="cards" id="dashboard-cards" data-card-size-page="dashboard" data-card-size-epoch="1" data-card-size-restore="1">
<div class="card" data-layout-key="controls"><h2>${esc(strings.get("dashboard.card.controls"))} <small>· ${esc(strings.get("dashboard.card.software_nav_bar"))}</small></h2>
<div id="ctlzone">${controls}</div></div>
${tcard("infotbl", strings.get("dashboard.card.panel_information"), rowHtml("infotbl"))}
$shotCard
${tcard("nettbl", strings.get("dashboard.card.networking"), rowHtml("nettbl"), post = """<p class="note">${esc(strings.get("dashboard.networking.warning_guidance"))}</p>""")}
${tcard("proftbl", strings.get("dashboard.card.profile"), rowHtml("proftbl"), post = profNote)}
${tcard("contexttbl", strings.get("dashboard.card.runtime_diagnostics"), rowHtml("contexttbl"))}
${tcard("captbl", strings.get("dashboard.card.capabilities"), rowHtml("captbl"), post = capNote)}
<div class="card" data-layout-key="responsiveness"><h2>${esc(strings.get("dashboard.card.responsiveness"))} <small id="smhdr"></small></h2>
<canvas id="respchart" width="600" height="150" style="height:150px"></canvas>
<div class="leg"><span style="color:#d04a3b">▬</span> ${esc(strings.get("dashboard.chart.interaction_latency"))}&nbsp;&nbsp;<span style="color:#4a9eff">▬</span> ${esc(strings.get("dashboard.chart.state_updates"))}&nbsp;&nbsp;<span style="color:#f5a623">▬</span> ${esc(strings.get("dashboard.chart.main_thread_blocking"))} · ~4 min</div>
<table id="smtbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.measuring"))}</td></tr></table></div>
<div class="card" data-layout-key="ha-state-stream"><h2>${esc(strings.get("dashboard.card.ha_state_stream"))} <small>· ${esc(strings.get("dashboard.card.builtin_renderer"))}</small></h2>
<table id="streamtbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.waiting_state_traffic"))}</td></tr></table>
<table class="dt" id="noisyentities"><tr><td style="color:#888">${esc(strings.get("dashboard.status.waiting_entity_contributors"))}</td></tr></table>
<p class="note">${esc(strings.get("dashboard.ha_stream.note"))} <a href="${localizedHref("entities", strings)}">${esc(strings.get("dashboard.ha_stream.open_diagnostics"))}</a>.</p></div>
<div class="card" data-layout-key="sensors"><h2>${esc(strings.get("dashboard.card.sensors"))} <small id="sensage"></small></h2>
<table id="senstbl"><tr><td style="color:#888">${esc(strings.get("dashboard.status.reading"))}</td></tr></table>
<p class="note">${esc(strings.get("dashboard.sensors.note"))}</p></div>
$cameraCard
<div class="card" data-layout-key="performance"><h2>${esc(strings.get("dashboard.card.performance"))} <small id="perfage"></small></h2>
<div style="color:#666;font-size:.78rem;margin-bottom:8px">${esc(strings.get("dashboard.performance.samples_note"))}</div>
<canvas id="perfchart" width="600" height="96" style="height:96px"></canvas>
<div class="leg"><span style="color:#4a9eff">■</span> CPU&nbsp;&nbsp;<span style="color:#48c774">■</span> RAM&nbsp;&nbsp;<span style="color:#f5a623">■</span> GPU (${esc(strings.get("dashboard.chart.percent_used"))}) · ~4&nbsp;min</div>
<table id="perf"><tr><td style="color:#888">${esc(strings.get("dashboard.status.sampling"))}</td></tr></table></div>
<div class="card" data-layout-key="top-processes"><h2>${esc(strings.get("dashboard.card.top_processes"))} <span class="top-process-modes" role="group" aria-label="${esc(strings.get("dashboard.processes.rank_by"))}"><button type="button" class="top-process-mode on" data-mode="cpu" aria-pressed="true" onclick="setTopMode('cpu')">CPU</button><button type="button" class="top-process-mode" data-mode="ram" aria-pressed="false" onclick="setTopMode('ram')">RAM</button></span></h2>
<table class="dt" id="topproc"><tr><td style="color:#888">${esc(strings.get("dashboard.status.top_processes"))}</td></tr></table></div>
<div class="card" data-layout-key="remote-webview"><h2>${esc(strings.get("dashboard.card.remote_webview"))} <small id="insthdr"></small></h2>
<div style="display:flex;gap:8px;margin-bottom:4px">
 <button id="inspstart" type="button" class="pbtn" onclick="inspStart()"${if (config.hardenedSecurityEnabled) " disabled title=\"${esc(strings.get("dashboard.remote_webview.hardened_unavailable"))}\"" else ""}>${esc(strings.get("dashboard.action.enable"))}</button>
 <button type="button" class="pbtn" onclick="inspStop()">${esc(strings.get("dashboard.action.stop"))}</button></div>
<p class="note" id="insthint"></p></div>
${tcard("livetbl", strings.get("dashboard.card.live_state"), rowHtml("livetbl"), pre = """<p class="note">${esc(strings.get("dashboard.live_state.note"))}</p>""")}
${tcard("behavtbl", strings.get("dashboard.card.behaviour"), rowHtml("behavtbl"))}
${tcard("disptbl", strings.get("dashboard.card.display_tuning"), rowHtml("disptbl"))}
</div>
<p class="note" style="text-align:center;margin-top:18px"><a href="${localizedHref("api", strings)}" style="color:#9cf">${esc(strings.get("dashboard.footer.api_explorer"))}</a>
 · <a href="api/v1/diag" target="_blank" style="color:#9cf">${esc(strings.get("dashboard.footer.diagnostics"))}</a> · <a href="$REPO_URL" target="_blank" rel="noopener" style="color:#9cf">GitHub</a></p>"""
}

private const val DEVICE_PROFILES_DOC = "https://panel-assistant.io/go/docs?page=architecture/device-profiles"
