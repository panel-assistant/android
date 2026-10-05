package io.panelassistant.android.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** A resolved activity: its package + fully-qualified class. [component] is the `am`-style "pkg/cls". */
data class ActivityRef(val pkg: String, val cls: String) {
    val component: String get() = "$pkg/$cls"
}

/**
 * The package/activity environment [io.panelassistant.android.control.SystemController] needs, seamed
 * off `android.content.Context` so the launcher / default-home / dashboard-resolution logic is
 * unit-testable without a device. All methods are the "always-allowed" PackageManager queries plus the
 * best-effort direct activity start (the pre-BAL fallback). [AndroidSystemEnv] is the real impl over a
 * Context; tests inject a fake. Privileged launches still go through [RootShell] / [Daemon].
 */
interface SystemEnv {
    /** This app's own package name (`context.packageName`). */
    val ownPackage: String

    /** True if [pkg] is installed (any package info resolvable). */
    fun isInstalled(pkg: String): Boolean

    /** The launchable component ("pkg/cls") for [pkg], or null if it has no launcher activity. */
    fun launchComponent(pkg: String): String?

    /** All activities registered for `CATEGORY_HOME` (real launchers + kiosk apps that declare HOME). */
    fun homeActivities(): List<ActivityRef>

    /** The current default HOME activity, or null if unresolved (may be the `android` resolver). */
    fun defaultHome(): ActivityRef?

    /** Whether Android accepted a direct start of [component] ("pkg/cls") — the pre-BAL fallback. */
    fun directStart(component: String): Boolean
}

/** Real [SystemEnv] over the Android PackageManager + Context. */
class AndroidSystemEnv(private val context: Context) : SystemEnv {
    private val pm get() = context.packageManager
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    override val ownPackage: String get() = context.packageName

    override fun isInstalled(pkg: String): Boolean =
        pkg.isNotBlank() && runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

    override fun launchComponent(pkg: String): String? =
        pm.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()

    override fun homeActivities(): List<ActivityRef> =
        pm.queryIntentActivities(homeIntent(), 0).map { ActivityRef(it.activityInfo.packageName, it.activityInfo.name) }

    override fun defaultHome(): ActivityRef? =
        pm.resolveActivity(homeIntent(), 0)?.activityInfo?.let { ActivityRef(it.packageName, it.name) }

    override fun directStart(component: String): Boolean {
        val cn = ComponentName.unflattenFromString(component) ?: return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
