package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

internal fun entitiesBody(strings: AppStrings, enabled: Boolean): String = if (!enabled) {
    val disabled = entityOwnedMarkup(
        strings.get("entities.disabled.body"),
        linkedMapOf(
            "{setting}" to "<b>${esc(strings.get("settings.dashboard_entity_learning.label"))}</b>",
            "{configure}" to "<b>${esc(strings.get("shell.nav.configure"))}</b>",
            "{dashboard}" to "<b>${esc(strings.get("configure.group.dashboard"))}</b>",
        ),
    )
    """<div class="cards"><div class="card"><h2>${esc(strings.get("entities.disabled.title"))} <small>· ${esc(strings.get("entities.disabled.badge"))}</small></h2>
        <p>$disabled</p></div></div>"""
} else """
        <div class="cards entity-cards">
          <div class="card"><h2>${esc(strings.get("entities.filter.title"))}</h2>
            <div id="entity-status">${esc(strings.get("entities.filter.loading"))}</div>
            <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px">
              <button class="pbtn" id="entity-sync">${esc(strings.get("entities.filter.scan"))}</button>
              <button class="pbtn" id="entity-activate" disabled>${esc(strings.get("entities.filter.checking"))}</button>
              <button class="pbtn" id="entity-reset" type="button">${esc(strings.get("entities.filter.reset"))}</button>
              <a class="pbtn" href="api/v1/dashboard/entities/export">${esc(strings.get("entities.filter.export"))}</a>
            </div>
            <div id="entity-action-result" class="entity-action-result muted" role="status" aria-live="polite"></div>
            <fieldset class="entity-policy"><legend>${esc(strings.get("entities.policy.legend"))}</legend>
              <label><input type="checkbox" id="entity-auto-static"> ${esc(strings.get("entities.policy.static"))}</label>
              <label><input type="checkbox" id="entity-auto-runtime"> ${entityOwnedMarkup(strings.get("entities.policy.runtime"), linkedMapOf("hass.states" to "<code>hass.states</code>"))}</label>
              <p class="muted">${esc(strings.get("entities.policy.note"))}</p>
            </fieldset>
          </div>
          <div class="entity-search-row">
            <label class="sr-only" for="entity-search">${esc(strings.get("entities.search.label"))}</label>
            <input id="entity-search" type="search" autocomplete="off" placeholder="${esc(strings.get("entities.search.placeholder"))}" aria-describedby="entity-search-status">
            <div id="entity-search-status" class="entity-search-status muted" role="status" aria-live="polite"></div>
          </div>
          <div class="card entity-issues" id="entity-issues"><h2>${esc(strings.get("entities.issues.title"))}</h2>
            <div id="entity-issues-summary" class="muted" role="status" aria-live="polite">${esc(strings.get("entities.issues.checking"))}</div>
            <div id="entity-issues-list" class="entity-issues-list"></div>
            <section id="entity-dynamic" class="entity-dynamic" hidden>
              <h3>${esc(strings.get("entities.dynamic.title"))}</h3>
              <p class="muted">${entityOwnedMarkup(strings.get("entities.dynamic.body"), linkedMapOf("{{ ... }}" to "<code>{{ ... }}</code>", "{% ... %}" to "<code>{% ... %}</code>"))}</p>
              <div id="entity-dynamic-list" class="entity-dynamic-list"></div>
            </section>
            <button class="pbtn" id="entity-issues-rescan" type="button">${esc(strings.get("entities.issues.rescan"))}</button>
          </div>
          ${entityTableHtml("current", "entities.table.current", "subscribed", strings)}
          ${entityTableHtml("suggested", "entities.table.suggested", "candidate", strings)}
          ${entityTableHtml("review", "entities.table.review", "review", strings)}
        </div>
        <script src="assets/entities.js"></script>
    """.trimIndent()

private fun entityTableHtml(
    id: String,
    keyPrefix: String,
    filter: String,
    strings: AppStrings,
): String {
    val keys = when (keyPrefix) {
        "entities.table.current" -> Triple(
            "entities.table.current.title",
            "entities.table.current.short",
            "entities.table.current.note",
        )
        "entities.table.suggested" -> Triple(
            "entities.table.suggested.title",
            "entities.table.suggested.short",
            "entities.table.suggested.note",
        )
        "entities.table.review" -> Triple(
            "entities.table.review.title",
            "entities.table.review.short",
            "entities.table.review.note",
        )
        else -> error("unknown Entities table: $keyPrefix")
    }
    return """
      <div class="card entity-list" data-filter="$filter" data-table="$id" data-short-key="${esc(keys.second)}"><h2>${esc(strings.get(keys.first))}</h2>
        <p class="muted">${esc(strings.get(keys.third))}</p>
        <div class="entity-bulk" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px">
          <button class="pbtn" data-bulk="pinned">${esc(strings.get("entities.bulk.pin_selected"))}</button><button class="pbtn" data-bulk="auto">${esc(strings.get("entities.bulk.auto_selected"))}</button><button class="pbtn" data-bulk="forced_exclude">${esc(strings.get("entities.bulk.exclude_selected"))}</button>
          ${if (filter == "candidate") "<button class=\"pbtn\" data-all-candidates=\"true\">${esc(strings.get("entities.bulk.pin_all_suggested"))}</button>" else ""}<span class="muted entity-selected">${esc(strings.get("entities.selection.none"))}</span>
        </div>
        <div class="tablewrap"><table class="entity-table"><thead><tr><th class="col-select"><input type="checkbox" class="entity-select-page" aria-label="${esc(strings.get("entities.table.select_page"))}"></th><th class="col-entity"><button data-sort="entity_id">${esc(strings.get("entities.table.entity"))}</button></th><th class="col-access"><button data-sort="access_1h">${esc(strings.get("entities.table.accesses"))} <small>${esc(strings.get("entities.table.period_tooltip"))}</small></button></th><th class="col-rate"><button data-sort="rate_1h_bps">${esc(strings.get("entities.table.data_rate"))} <small>${esc(strings.get("entities.table.bytes_per_second"))} · ${esc(strings.get("entities.table.period_tooltip"))}</small></button></th><th class="col-reason"><button data-sort="reasons">${esc(strings.get("entities.table.reason"))}</button></th><th class="col-last"><button data-sort="last_access_at">${esc(strings.get("entities.table.last_access"))}</button></th><th class="col-override"><button data-sort="override">${esc(strings.get("entities.table.override"))}</button></th></tr></thead><tbody></tbody></table></div>
        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:10px">
          <button class="pbtn entity-prev">${esc(strings.get("entities.pagination.previous"))}</button><button class="pbtn entity-next">${esc(strings.get("entities.pagination.next"))}</button><span class="muted entity-msg">${esc(strings.get("entities.pagination.loading"))}</span>
        </div>
      </div>
    """.trimIndent()
}

/** Insert only server-owned emphasis/code elements while escaping every translated byte around them. */
private fun entityOwnedMarkup(text: String, replacements: Map<String, String>): String = buildString {
    var offset = 0
    while (offset < text.length) {
        val next = replacements.keys
            .mapNotNull { marker -> text.indexOf(marker, offset).takeIf { it >= 0 }?.let { it to marker } }
            .minByOrNull { it.first }
        if (next == null) {
            append(esc(text.substring(offset)))
            break
        }
        append(esc(text.substring(offset, next.first)))
        append(replacements.getValue(next.second))
        offset = next.first + next.second.length
    }
}
