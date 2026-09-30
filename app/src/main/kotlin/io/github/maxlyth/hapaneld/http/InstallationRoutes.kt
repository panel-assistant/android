package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.ReleaseCatalog
import io.github.maxlyth.hapaneld.util.SelfUpdater
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.installationRoutes(
    appContext: Context,
    config: Config,
    profile: DeviceProfile,
    pendingApks: PendingUploadStore,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    versionCatalogue: (String, String) -> List<ReleaseCatalog.Version> = { name, channel ->
        when (name) {
            "paneld" -> SelfUpdater.versions(channel)
            "companion" -> CompanionInstaller.versions(channel, maxVersion = profile.companionMaxVersion)
            else -> emptyList()
        }
    },
) {
    // Dismiss a component update from the DASHBOARD banner only (per-version; re-surfaces when
    // a newer release ships). The Install tab still lists it. See Config.ignoreUpdate.
    post("/updates/ignore") {
        val p = receiveBoundedFormParameters(call) ?: return@post
        val label = p["label"]?.trim().orEmpty()
        val version = p["version"]?.trim().orEmpty()
        if (label.isEmpty() || version.isEmpty())
            call.respondText("""{"ok":false}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        else { config.ignoreUpdate(label, version); call.respondText("""{"ok":true}""", ContentType.Application.Json) }
    }
    // Recent installable versions for a component's picker (name ∈ {paneld,companion};
    // channel ∈ {stable,prerelease}). Up to 10 choices plus one unavailable explanation on capped panels.
    get("/install/versions") {
        val name = call.request.queryParameters["name"]?.trim().orEmpty()
        val channel = call.request.queryParameters["channel"]?.trim()?.ifEmpty { "stable" } ?: "stable"
        val vers = withContext(Dispatchers.IO) { versionCatalogue(name, channel) }
        val installedVersion = when (name) {
            "paneld" -> Config.VERSION
            "companion" -> CompanionInstaller.installedPkg(appContext)?.let {
                AppInstaller.installedVersion(appContext, it)
            }
            else -> null
        }
        val arr = vers.joinToString(",") { v ->
            val candidate = if (name == "companion") UpdateChecker.stripVariant(v.version) else v.version
            val installed = installedVersion?.let {
                if (name == "companion") UpdateChecker.stripVariant(it) else it
            }
            val comparison = installed?.let { UpdateChecker.compareVersions(candidate, it) }
            val (action, presentation) = when {
                comparison == null || comparison == 0 ->
                    "Install" to InstallPresentation("version-install")
                comparison > 0 ->
                    "Upgrade" to InstallPresentation("version-upgrade")
                else ->
                    "Downgrade" to InstallPresentation("version-downgrade")
            }
            """{"version":${Json.str(v.version)},"tag":${Json.str(v.tag)},"notes":${Json.str(v.notesUrl)},"installable":${v.installable},"unavailableReason":${v.unavailableReason?.let(Json::str) ?: "null"},"maxVersion":${v.maxVersion?.let(Json::str) ?: "null"},"action":${Json.str(action)},"apk":${Json.str(v.apkUrl ?: "")},"presentations":{"action":${presentation.json()}}}"""
        }
        call.respondText("""{"channel":${Json.str(channel)},"versions":[$arr]}""", ContentType.Application.Json)
    }
    get("/install/status") { call.respondText(InstallProgress.json(), ContentType.Application.Json) }
    // Enable/disable the APK-upload capability (the card's toggle).
    post("/install/apk/allow") {
        val on = (receiveBoundedFormParameters(call) ?: return@post)["on"]
            ?.let { it == "true" || it == "1" } ?: true
        config.setApkUploadAllowed(on)
        if (!on) pendingApks.clear()
        call.respondText("""{"ok":true,"allowed":$on}""", ContentType.Application.Json)
    }
    // Removable apps (third-party + updated-system; excludes ha-paneld + stock system apps,
    // which pm can't uninstall anyway) for the Uninstall card's picker.
    get("/packages") { call.respondText(withContext(Dispatchers.IO) { packagesJson(appContext, config) }, ContentType.Application.Json) }
    // Launchable apps plus the supported installed Companion renderer choices —
    // populates the Configure tab's Dashboard-app / Launcher-app pickers.
    // Uninstall a package over root. Guarded: never ha-paneld itself; the picker only offers
    // removable apps. `pm uninstall` (system/vendor apps aren't removable, only disable-able
    // via taming — a separate, safer path).
    post("/uninstall") {
        if (!Su.availableCachedIsolated()) return@post call.respondText(
            """{"ok":false,"error":"no-root"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        val parameters = receiveBoundedFormParameters(call) ?: return@post
        val pkg = parameters["pkg"]?.trim().orEmpty()
        val protected = pkg == appContext.packageName || pkg == io.github.maxlyth.hapaneld.util.WebViewInstaller.WEBVIEW_PKG
        if (pkg.isEmpty() || protected || !AndroidInput.isPackage(pkg) ||
            pkg !in removablePackages(appContext, config).mapTo(hashSetOf()) { it.first })
            return@post call.respondText("""{"ok":false,"error":"bad-package"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        if (!authorizeSensitive(
                call,
                SensitiveOperation.PACKAGE_UNINSTALL,
                exactHttpApprovalPayload(call, parameters.canonicalDigest()),
                "Uninstall $pkg",
            )
        ) return@post
        val progress = InstallProgress.start(
            "Uninstall",
            InstallPresentation("operation-working", mapOf("owner" to "package-uninstall")),
        ) ?: return@post call.respondText(
            """{"ok":false,"error":"busy"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
        var progressResult = "uninstall cancelled"
        var progressPresentation: InstallPresentation? = InstallPresentation("operation-cancelled")
        try {
            val (out, path) = withContext(Dispatchers.IO) {
                Su.runOutput("pm uninstall $pkg")?.trim() to
                    Su.runOutput("pm path $pkg 2>/dev/null")?.trim()
            }
            // Empty stdout can be a real persistent-shell success, but null means the probe failed.
            val ok = uninstallSucceeded(out, path)
            progressResult = if (ok) "uninstalled $pkg" else "uninstall failed: $pkg"
            progressPresentation = InstallPresentation(
                if (ok) "package-uninstalled" else "package-uninstall-failed",
                mapOf("package" to pkg),
            )
            if (ok) Log.i("ha-paneld/http", "uninstalled $pkg")
            val result = out?.ifEmpty { if (ok) "removed" else "uninstall failed" } ?: "uninstall failed"
            call.respondText("""{"ok":$ok,"result":${Json.str(result)}}""", ContentType.Application.Json)
        } finally {
            InstallProgress.finish(
                progress,
                progressResult,
                presentation = progressPresentation,
            )
        }
    }

}

internal fun Route.webViewHealRoute(
    onInstallComponent: (String, String, String) -> Boolean,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
) {
    // Auto-heal the System WebView (download + install the profile's recommended build).
    // Fire-and-forget: the install runs off-thread (large download); the client refreshes.
    post("/webview/heal") {
        if (!authorizeSensitive(
                call,
                SensitiveOperation.APK_INSTALL,
                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                "Reinstall the recommended System WebView",
            )
        ) return@post
        val status = if (onInstallComponent("webview", "reinstall", "")) "started" else "busy"
        call.respondText("""{"status":"$status"}""", ContentType.Application.Json)
    }

}

/** Removable apps (third-party or updated-system) for the Uninstall picker, sorted by label. Stock
 *  system apps + ha-paneld are excluded — pm can't uninstall stock system apps (only disable), and
 *  self-uninstall would kill the tool. */
private fun packagesJson(appContext: Context, config: Config): String {
    val apps = removablePackages(appContext, config)
    val arr = apps.joinToString(",") { (pkg, label) -> "{\"pkg\":${Json.str(pkg)},\"label\":${Json.str(label)}}" }
    return "{\"packages\":[$arr]}"
}


/** The server re-evaluates the same policy used by the picker; UI filtering is never authorization. */
private fun removablePackages(appContext: Context, config: Config): List<Pair<String, String>> {
    val pm = appContext.packageManager
    val homePackage = runCatching {
        pm.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            0,
        )?.activityInfo?.packageName
    }.getOrNull()
    val excluded = setOfNotNull(
        appContext.packageName,
        io.github.maxlyth.hapaneld.util.WebViewInstaller.WEBVIEW_PKG,
        config.dashboardPackage.takeIf { it.isNotBlank() && it != SystemController.BUILTIN_DASHBOARD },
        config.launcherPackage.takeIf(String::isNotBlank),
        homePackage,
    )
    return runCatching {
        pm.getInstalledApplications(0)
            .filter { it.packageName !in excluded }
            .filter {
                it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 ||
                    it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            }
            .map { it.packageName to runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
            .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
    }.getOrDefault(emptyList())
}
