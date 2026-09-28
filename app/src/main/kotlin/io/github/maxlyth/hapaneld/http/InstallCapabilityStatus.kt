package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.util.AppInstaller

/** The self-update route available to the panel now; a specific APK still has its own admission. */
internal fun installCapabilityStatusJson(privilege: PrivilegedRouteObservation): String {
    val route = if (AppInstaller.selectInstallRoute(
            privilege.directSuReady,
            privilege.helperRootReady,
            privilege.shizuku.ready,
            allowShizuku = true,
        ) != AppInstaller.InstallRoute.NONE
    ) "api" else "none"
    return "\"install_capability\":\"$route\""
}
