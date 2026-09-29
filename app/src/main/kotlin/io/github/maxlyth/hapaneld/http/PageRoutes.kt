package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.dashboardPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    render: (AppStrings, EmbedMode?) -> String,
) {
    get("/") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "dashboard.")).joinToString(", "),
        )
        call.respondText(render(strings, call.embedMode()), ContentType.Text.Html)
    }
}

internal fun Route.configurePageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    pages: () -> PageShell,
    body: (AppStrings) -> String,
) {
    get("/configure") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "configure.")).joinToString(", "),
        )
        call.respondText(
            pages().page(
                active = "configure",
                title = strings.get("shell.nav.configure"),
                body = body(strings),
                strings = strings,
                embed = call.embedMode(),
            ),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.setupPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    pages: () -> PageShell,
    buildToken: () -> String,
) {
    get("/setup") {
        val strings = requestStrings(call)
        val preserveExplicitEnglish = AppLocale.canonical(
            call.request.queryParameters["lang"],
            allowPseudo = BuildConfig.DEBUG,
        ) == AppLocale.ENGLISH
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "setup.")).joinToString(", "),
        )
        // No data-cfg, unlike every other page: buildwatch.js reloads /configure when settings
        // change underneath it, and that same reload mid-step would throw away what the user is
        // typing. The wizard tracks server state by polling instead. The build token stays, so a
        // reinstall still refreshes the page.
        call.respondText(
            pages().pageShell(
                active = "setup",
                sectionTitle = strings.get("shell.nav.setup"),
                bodyAttrs = """data-build="${buildToken()}"""",
                rightControls = ghLink(strings),
                body = setupBody(strings, preserveExplicitEnglish, embedded = call.embedMode() != null),
                strings = strings,
                translationPrefixes = setOf("shell.", "setup.", "runtime."),
                preserveExplicitEnglish = preserveExplicitEnglish,
                embed = call.embedMode(),
            ),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.profilesPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    pages: () -> PageShell,
) {
    get("/profiles") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "configure.hardened.", "profiles.")).joinToString(", "),
        )
        call.respondText(
            pages().page("profiles", strings.get("shell.nav.profile"), profilesBody(strings), strings, call.embedMode()),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.installPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    pages: () -> PageShell,
    body: (AppStrings) -> String,
) {
    get("/install") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            (strings.languages(
                setOf(
                    "shell.",
                    "configure.hardened.",
                    "dashboard.banner.",
                    "install.",
                    "runtime.",
                ),
            ) + AppLocale.ENGLISH)
                .distinct().sorted().joinToString(", "),
        )
        call.respondText(
            withContext(Dispatchers.IO) {
                pages().page("install", strings.get("shell.nav.install"), body(strings), strings, call.embedMode())
            },
            ContentType.Text.Html,
        )
    }
}

internal fun Route.fleetPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    pages: () -> PageShell,
    httpPort: () -> Int,
) {
    get("/fleet") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "configure.hardened.", "fleet.")).joinToString(", "),
        )
        call.respondText(
            pages().page("fleet", strings.get("shell.nav.fleet"), fleetBody(strings, httpPort()), strings, call.embedMode()),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.logsPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    pages: () -> PageShell,
    httpPort: () -> Int,
) {
    get("/logs") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "configure.hardened.", "logs.")).joinToString(", "),
        )
        call.respondText(
            pages().page("logs", strings.get("shell.nav.logs"), logsBody(strings, httpPort()), strings, call.embedMode()),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.entitiesPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    pages: () -> PageShell,
    enabled: () -> Boolean,
) {
    get("/entities") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            (
                strings.languages(setOf("shell.", "configure.hardened.", "entities.")) +
                    strings.resolve("settings.dashboard_entity_learning.label").language +
                    strings.resolve("configure.group.dashboard").language +
                    AppLocale.ENGLISH
                )
                .distinct().sorted().joinToString(", "),
        )
        call.respondText(
            pages().page("entities", strings.get("shell.nav.entities"), entitiesBody(strings, enabled()), strings, call.embedMode()),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.apiPageRoute(
    requestStrings: (ApplicationCall) -> AppStrings,
    asset: (String) -> String,
    friendlyName: () -> String,
) {
    get("/api") {
        val strings = requestStrings(call)
        val projectionPrefixes = setOf("api.", "configure.hardened.", "shell.hardened.")
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            (strings.languages(projectionPrefixes) + AppLocale.ENGLISH)
                .distinct().sorted().joinToString(", "),
        )
        val html = asset("api.html")
            .replace(
                "<title>ha-paneld · REST API</title>",
                "<title>${esc(panelBrowserTitle(friendlyName(), "REST API"))}</title>",
            )
            .replace("__API_LANG__", esc(strings.requestedLocale))
            .replace("__API_BACK_HREF__", esc(localizedHref("./", strings)))
            .replace("__API_I18N_PAYLOAD__", browserI18nPayload(strings, projectionPrefixes))
        call.respondText(html, ContentType.Text.Html)
    }
}
