package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

/** Fleet tab — placeholder (discovery hooks exist; the roster lands later). */
internal fun fleetBody(strings: AppStrings, httpPort: Int): String = """
<div class="cards"><div class="card"><h2>${esc(strings.get("fleet.title"))} <small>· ${esc(strings.get("fleet.state.coming_soon"))}</small></h2>
<p class="note">${esc(strings.get("fleet.note.roster"))}
${esc(strings.get("fleet.note.discovery_prefix"))} (<code>${esc(Config.MDNS_SERVICE_TYPE)}</code>) ${esc(strings.get("fleet.note.discovery_suffix"))}</p>
<p class="note">${esc(strings.get("fleet.note.direct"))} <code>http://&lt;its-ip&gt;:${esc(httpPort.toString())}/</code>.</p></div></div>"""
