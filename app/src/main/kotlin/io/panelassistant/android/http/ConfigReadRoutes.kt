package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.DiscoveryResult
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.HomeDashboardCatalog
import io.panelassistant.android.haUrl
import io.panelassistant.android.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.Strings
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal data class LocalizedConfigSchema(
    val json: String,
    val languages: Collection<String>,
)

/** Select the validated catalogue for any human or machine HTTP surface using one locale precedence. */
internal fun resolvedRequestStrings(
    call: ApplicationCall,
    persistedLanguage: String?,
    deviceLanguageTag: String?,
    allowPseudo: Boolean,
    catalogueLoader: CatalogueLoader,
): Strings = resolvedRequestStrings(call.op(), persistedLanguage, deviceLanguageTag, allowPseudo, catalogueLoader)

/** [resolvedRequestStrings] for a request on either door: a managed request's `lang` arg is its `?lang`. */
internal fun resolvedRequestStrings(
    call: OpCall,
    persistedLanguage: String?,
    deviceLanguageTag: String?,
    allowPseudo: Boolean,
    catalogueLoader: CatalogueLoader,
): Strings {
    // Embedded in Panel Assistant's sidebar, the Home Assistant user's language stands in for Automatic: an
    // explicit `?lang` or a chosen Interface language still wins (maintainer, 2026-10-02: choosing a language
    // must change the page). A language with no catalogue is English.
    val explicit = call.request.queryParameters["lang"]
    val embedLanguage = call.embedMode()?.lang
    val chosen = persistedLanguage?.takeUnless { it.equals("auto", ignoreCase = true) }?.let { AppLocale.canonical(it) }
    if (embedLanguage != null && chosen == null && AppLocale.canonical(explicit, allowPseudo = allowPseudo) == null) {
        return catalogueLoader.strings(AppLocale.canonical(embedLanguage) ?: AppLocale.ENGLISH)
    }
    val locale = AppLocale.resolve(
        explicit = explicit,
        persisted = persistedLanguage,
        haUser = call.request.queryParameters["ha_lang"],
        acceptLanguage = call.request.headers[HttpHeaders.AcceptLanguage],
        deviceLanguageTag = deviceLanguageTag,
        allowPseudo = allowPseudo,
    )
    return catalogueLoader.strings(locale)
}

/**
 * The unfinished-Setup redirect target. `lang` stays explicit; `ha_lang` stays an automatic signal
 * below the persisted setting, reflected as the release locale it already selects (so `ru` carries
 * as `uk` once Ukrainian ships). Only canonical release tags reach the Location header.
 */
internal fun unfinishedSetupLocation(lang: String?, haLang: String?, allowPseudo: Boolean): String {
    val query = mutableListOf<String>()
    AppLocale.canonical(lang, allowPseudo = allowPseudo)?.let { query += "lang=$it" }
    AppLocale.automatic(haLang)?.let { query += "ha_lang=$it" }
    return if (query.isEmpty()) "/setup" else "/setup?${query.joinToString("&")}"
}

/** Production Configure reads and discovery metadata; no configuration or discovery state is retained here. */
internal fun Route.configReadRoutes(
    config: Config,
    values: () -> ConfigValueProjection,
    requestStrings: (OpCall) -> Strings,
    schemaJson: (Strings) -> String,
    homeDashboards: suspend () -> HomeDashboardCatalog,
    discover: () -> ConfigDiscoverySuggestions,
    rememberDiscovery: (DiscoveryResult) -> Unit,
) {
    configReadRoutes(
        currentConfigJson = { values().configJson() },
        localizedSchema = { call ->
            val strings = requestStrings(call)
            LocalizedConfigSchema(schemaJson(strings), strings.languages(setOf("settings.")))
        },
    )
    ops {
        // Polled every half second by the `config` family: su-free, lock-free and never on the management snapshot.
        get("/config/values") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(values().valuesJson(BuiltinDashboard.pendingRendererSettings()), ContentType.Application.Json)
        }
        get("/config/home-dashboards") {
            val catalog = homeDashboards()
            val items = catalog.items.joinToString(",") { dashboard ->
                "{\"path\":${Json.str(dashboard.path)},\"title\":${Json.str(dashboard.title)}," +
                    "\"icon\":${Json.str(dashboard.icon)},\"group\":${Json.str(dashboard.group)}}"
            }
            // `default` reports whether the ACCOUNT carries a real server-side default dashboard
            // (HA ≥ 2025.12 stores the profile picker's choice per user). When it does not, the
            // pickers demote "follow the account's default" and recommend nominating one.
            val default = "{\"explicit\":${catalog.default.explicit}," +
                "\"path\":${Json.str(catalog.default.path)}}"
            call.respondText(
                "{\"queried\":${catalog.queried},\"items\":[$items],\"default\":$default}",
                ContentType.Application.Json,
            )
        }
        get("/config/discovery") {
            val needsHa = config.haUrl.isBlank()
            val found = if (needsHa) {
                withContext(Dispatchers.IO) { discover() }
                    .also { rememberDiscovery(it.haDiscovery) }
            } else ConfigDiscoverySuggestions()
            val ha = found.haUrl.takeIf { needsHa && config.haUrl.isBlank() }.orEmpty()
            call.respondText(
                "{\"ha_url\":${Json.str(ha)}}",
                ContentType.Application.Json,
            )
        }
    }
}

/** Dynamic Configure reads. Neither response may be reused after settings or locale signals change. */
internal fun Route.configReadRoutes(
    currentConfigJson: () -> String,
    localizedSchema: (OpCall) -> LocalizedConfigSchema,
) = ops {
    get("/config") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(currentConfigJson(), ContentType.Application.Json)
    }
    get("/config/schema") {
        val schema = localizedSchema(call)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            schema.languages.joinToString(", "),
        )
        call.respondText(schema.json, ContentType.Application.Json)
    }
}
