package io.panelassistant.android.platform

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.Config
import io.panelassistant.android.RendererResolver
import io.panelassistant.android.config.TamePackagePolicy
import io.panelassistant.android.util.AndroidInput

/** Renderer selection never makes a foreign dashboard an Android launcher. */
internal fun Config.dashboardOwnsHome(env: SystemEnv): Boolean =
    launcherPackage == env.ownPackage || RendererResolver.isBuiltinSelection(dashboardPackage, env.ownPackage)

/** Preserve an owner's current launcher; recover only an exact, still eligible recorded HOME. */
internal fun retainedDashboardHome(env: SystemEnv, previous: String?): ActivityRef? {
    val homes = env.homeActivities()
    fun eligible(home: ActivityRef) = home in homes && AndroidInput.isComponent(home.component) &&
        home.pkg != "android" && !AppIdentity.isPanelApp(home.pkg) && !TamePackagePolicy.isCritical(home.pkg)
    val current = env.defaultHome()
    if (current != null && eligible(current)) return current
    // Unknown HOME is not authority to replace another owner's activity.
    if (current != null && current.pkg != "android" && !AppIdentity.isPanelApp(current.pkg) &&
        !(current.pkg == "com.android.settings" && current.cls.endsWith("FallbackHome"))) return null
    return homes.singleOrNull { it.component == previous && eligible(it) }
}
