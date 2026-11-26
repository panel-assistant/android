package io.panelassistant.android.http

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import io.panelassistant.android.Config
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.previousHomeComponent

/** One installed application as the Uninstall policy sees it. */
internal data class InstalledApp(val pkg: String, val label: String, val flags: Int)

/** The current HOME holder plus every installed application. */
internal class PackageInventory(val homePackage: String?, val apps: List<InstalledApp>)

internal fun systemPackageInventory(appContext: Context): PackageInventory {
    val pm = appContext.packageManager
    val home = runCatching {
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()
    val apps = runCatching {
        pm.getInstalledApplications(0).map {
            InstalledApp(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName), it.flags)
        }
    }.getOrDefault(emptyList())
    return PackageInventory(home, apps)
}

// Companion app variants and the third-party NSPanel Pro setup tool that a previous panel setup leaves behind.
internal val PREVIOUS_SETUP_PACKAGES = setOf(
    "io.homeassistant.companion.android",
    "io.homeassistant.companion.android.minimal",
    "com.seaky.nspanelpro.tools",
)

/** Removable apps (third-party or updated-system), sorted by label. The server re-evaluates this
 *  same policy for `uninstall`; UI filtering is never authorization. */
internal fun removablePackages(appContext: Context, config: Config, inventory: PackageInventory): List<InstalledApp> {
    val excluded = setOfNotNull(
        appContext.packageName,
        io.panelassistant.android.util.AppInstaller.WEBVIEW_PKG,
        config.dashboardPackage.takeIf { it.isNotBlank() && it != SystemController.BUILTIN_DASHBOARD },
        config.launcherPackage.takeIf(String::isNotBlank),
        inventory.homePackage,
    )
    return inventory.apps
        .filter { it.pkg !in excluded }
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
        .sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
}

/** A removable app left by the panel's previous setup: the HOME app Panel Assistant replaced, or a known leftover. */
internal fun isPreviousSetupPackage(pkg: String, config: Config): Boolean =
    pkg in PREVIOUS_SETUP_PACKAGES ||
        config.previousHomeComponent?.substringBefore('/') == pkg
