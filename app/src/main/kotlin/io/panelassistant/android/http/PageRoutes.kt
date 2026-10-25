package io.panelassistant.android.http

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.Strings as AppStrings
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
    requestStrings: (OpCall) -> AppStrings,
    render: (AppStrings, EmbedMode?, String?) -> String,
) {
    ops { served("/", name = "page.info") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "dashboard.")).joinToString(", "),
        )
        call.respondText(render(strings, call.embedMode(), call.origin.peerAddress), ContentType.Text.Html)
    } }
}

internal fun Route.dashboardHydrationRoute(
    requestStrings: (OpCall) -> AppStrings,
    render: (AppStrings) -> String,
) = ops {
    // Hydration payload for the dashboard (see DashboardPageHandler.json) — the one place the probe
    // suite actually runs; cached + single-flight, so concurrent viewers share it.
    get("/info") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("dashboard.")).joinToString(", "),
        )
        call.respondText(withContext(Dispatchers.IO) { render(strings) }, ContentType.Application.Json)
    }
}

internal fun Route.apiSpecificationRoute(asset: (String) -> String) {
    get("/openapi.json") {
        call.respondText(asset("openapi.json"), ContentType.Application.Json)
    }
}

internal fun Route.configurePageRoute(
    requestStrings: (OpCall) -> AppStrings,
    pages: () -> PageShell,
    body: (AppStrings) -> String,
) = ops {
    served("/configure", name = "page.configure") {
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
                peer = call.origin.peerAddress,
            ),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.setupPageRoute(
    requestStrings: (OpCall) -> AppStrings,
    pages: () -> PageShell,
    buildToken: () -> String,
) = ops {
    served("/setup", name = "page.setup") {
        val strings = requestStrings(call)
        val preserveExplicitEnglish = AppLocale.canonical(
            call.request.queryParameters["lang"],
            allowPseudo = BuildConfig.DEBUG,
        ) == AppLocale.ENGLISH
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "setup.", "configure.area.")).joinToString(", "),
        )
        call.respondText(
            pages().pageShell(
                active = "setup",
                sectionTitle = strings.get("shell.nav.setup"),
                bodyAttrs = """data-build="${buildToken()}"""",
                rightControls = ghLink(strings),
                body = setupBody(strings, preserveExplicitEnglish, embedded = call.embedMode() != null),
                strings = strings,
                translationPrefixes = setOf("shell.", "setup.", "runtime.", "configure.area."),
                preserveExplicitEnglish = preserveExplicitEnglish,
                embed = call.embedMode(),
                peer = call.origin.peerAddress,
            ),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.profilesPageRoute(
    requestStrings: (OpCall) -> AppStrings,
    pages: () -> PageShell,
) = ops {
    served("/profiles", name = "page.profiles") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "configure.hardened.", "profiles.")).joinToString(", "),
        )
        call.respondText(
            pages().page("profiles", strings.get("shell.nav.profile"), profilesBody(strings), strings, call.embedMode(), call.origin.peerAddress),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.installPageRoute(
    requestStrings: (OpCall) -> AppStrings,
    pages: () -> PageShell,
    body: (AppStrings) -> String,
) = ops {
    served("/install", name = "page.install") {
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
                pages().page("install", strings.get("shell.nav.install"), body(strings), strings, call.embedMode(), call.origin.peerAddress)
            },
            ContentType.Text.Html,
        )
    }
}

internal fun Route.logsPageRoute(
    requestStrings: (OpCall) -> AppStrings,
    pages: () -> PageShell,
    httpPort: () -> Int,
) = ops {
    served("/logs", name = "page.logs") {
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            strings.languages(setOf("shell.", "configure.hardened.", "logs.")).joinToString(", "),
        )
        call.respondText(
            pages().page("logs", strings.get("shell.nav.logs"), logsBody(strings, httpPort()), strings, call.embedMode(), call.origin.peerAddress),
            ContentType.Text.Html,
        )
    }
}

internal fun Route.entitiesPageRoute(
    requestStrings: (OpCall) -> AppStrings,
    pages: () -> PageShell,
    enabled: () -> Boolean,
) = ops {
    served("/entities", name = "page.entities") {
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
            pages().page("entities", strings.get("shell.nav.entities"), entitiesBody(strings, enabled()), strings, call.embedMode(), call.origin.peerAddress),
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
        val projectionPrefixes = setOf("api.", "configure.hardened.", "shell.hardened.", "shell.pickles.")
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            (strings.languages(projectionPrefixes) + AppLocale.ENGLISH)
                .distinct().sorted().joinToString(", "),
        )
        val html = asset("api.html")
            .replace(
                "<title>Panel Assistant · REST API</title>",
                "<title>${esc(panelBrowserTitle(friendlyName(), "REST API"))}</title>",
            )
            .replace("__API_BRAND__", brandLockup(asset))
            .replace("__API_LANG__", esc(strings.requestedLocale))
            .replace("__API_BACK_HREF__", esc(localizedHref("./", strings)))
            .replace("__API_I18N_PAYLOAD__", browserI18nPayload(strings, projectionPrefixes))
        call.respondText(html, ContentType.Text.Html)
    }
}
