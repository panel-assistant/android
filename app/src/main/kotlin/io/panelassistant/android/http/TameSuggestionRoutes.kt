package io.panelassistant.android.http

import io.panelassistant.android.control.TameController
import io.panelassistant.android.i18n.Strings
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** On-demand vendor package discovery; selection mutations remain with [TameRoutes]. */
internal fun Route.tameSuggestionRoutes(
    requestStrings: (ApplicationCall) -> Strings,
    admitActiveRead: suspend (ApplicationCall) -> Boolean,
    suggestionGroups: () -> List<TameController.Group>,
) {
    // The "Find a package…" picker pop-up content: an on-demand, grouped list of packages a
    // non-expert might want to control — Recommended (profile) / Other apps / Using the most
    // CPU. Lazy (only built when the dialog opens) and excludes what's already tamed (the card).
    get("/tame/suggest") {
        if (!admitActiveRead(call)) return@get
        val strings = requestStrings(call)
        PerfReader.touch()   // keep the CPU sampler warm so the "most CPU" group can populate
        val groups = runCatching {
            suggestionGroups()
        }.getOrDefault(emptyList())
        val frag = if (groups.isEmpty())
            """<p class="note">${esc(strings.get("install.tame.suggest.none_found"))}</p>"""
        else groups.joinToString("\n") { g ->
            val items = if (g.items.isEmpty())
                """<p class="note" style="margin:0 0 4px;color:#666">${esc(strings.get("install.tame.suggest.none"))}</p>"""
            else g.items.joinToString("\n") { tameRowHtml(it, strings = strings) }
            """<h4 style="margin:14px 0 1px">${esc(localizedTameGroupTitle(g.title, strings))}</h4>""" +
                """<p class="note" style="margin:0 0 4px">${esc(localizedTameGroupHint(g.hint, strings))}</p>$items"""
        }
        // One-click "Tame all recommended", shown only when there's an active recommended pick.
        val hasRec = groups.any { g -> g.items.any { it.recommended && !it.blocked && !it.disabled && it.installed } }
        val recBtn = if (hasRec)
            """<form method="post" action="${localizedHref("api/v1/tame", strings)}" style="margin:0 0 12px"><input type="hidden" name="action" value="recommended"><button type="submit"${hardenedApprovalA11yAttrs(strings = strings)} style="background:#2e6b3f;border-color:#2e6b3f">✓ ${esc(strings.get("install.tame.suggest.all_recommended"))}</button> <span class="note" style="font-size:.8em">${esc(strings.get("install.tame.suggest.recommended_hint"))}</span></form>"""
            else ""
        call.respondText(recBtn + frag, ContentType.Text.Html)
    }
}
