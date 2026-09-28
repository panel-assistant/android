package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.discoveryRoutes(peersJson: () -> String, appsJson: () -> String) {
    get("/peers") { call.respondText(peersJson(), ContentType.Application.Json) }
    get("/apps") { call.respondText(withContext(Dispatchers.IO) { appsJson() }, ContentType.Application.Json) }
}

/** Launcher entries feed the Launcher picker; installed Companion renderers feed Dashboard choices. */
internal fun launchableAppsJson(appContext: Context): String {
    val pm = appContext.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    val apps = runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .associate {
                val pkg = it.packageName
                val label = if (pkg == appContext.packageName) {
                    "Panel admin (ha-paneld)"
                } else {
                    runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(pkg)
                }
                pkg to label
            }
            .toList()
            .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
    }.getOrDefault(emptyList())
    val rendererChoices = CompanionInstaller.rendererChoices(CompanionInstaller.installedPackages(appContext))
    return configureAppInventoryJson(apps, rendererChoices)
}

/** Distinct inputs keep a failed broad app query from suppressing Companion renderer choices. */
internal fun configureAppInventoryJson(
    apps: List<Pair<String, String>>,
    rendererChoices: List<CompanionInstaller.RendererChoice>,
): String {
    val appJson = apps.joinToString(",") { (pkg, label) ->
        "{\"pkg\":${Json.str(pkg)},\"label\":${Json.str(label)}}"
    }
    val rendererJson = rendererChoices.joinToString(",") { choice ->
        "{\"pkg\":${Json.str(choice.packageName)},\"label\":${Json.str(choice.label)}}"
    }
    return "{\"apps\":[$appJson],\"renderers\":[$rendererJson]}"
}
