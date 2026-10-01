package io.github.maxlyth.hapaneld.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.util.HelperClient

/** Repair installer omissions before the service constructs its feature owners. */
internal object PanelPermissionRepair {
    enum class Grant { NOTIFICATIONS, WRITESETTINGS, OVERLAY, ACCESSIBILITY, MICROPHONE, CAMERA }
    enum class Outcome { HELD, CLAIMED, NO_HELPER, REFUSED, UNREADABLE }

    /** Below Android 13 notifications are granted at install. Also used during package migration. */
    fun notificationsHeld(sdkInt: Int, granted: () -> Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || granted()

    fun accessibilityHeld(packageName: String, services: String?, enabled: Boolean): Boolean {
        val component = AppIdentity.component(packageName, ".input.PanelAccessibilityService")
        val fullComponent = "$packageName/${AppIdentity.CODE_PACKAGE}.input.PanelAccessibilityService"
        return enabled && services.orEmpty().split(':').any { it == component || it == fullComponent }
    }

    fun repair(context: Context, hasMicrophone: Boolean, hasCamera: Boolean) {
        repair(Build.VERSION.SDK_INT, hasMicrophone, hasCamera, { grant ->
            when (grant) {
                Grant.NOTIFICATIONS -> context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                Grant.MICROPHONE -> context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                Grant.CAMERA -> context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                Grant.WRITESETTINGS -> Settings.System.canWrite(context)
                Grant.OVERLAY -> Settings.canDrawOverlays(context)
                Grant.ACCESSIBILITY -> accessibilityHeld(
                    context.packageName,
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1,
                )
            }
        }, HelperClient, context.packageName).forEach { (grant, outcome) ->
            when (outcome) {
                Outcome.HELD -> Unit
                Outcome.CLAIMED -> Log.i(TAG, "${grant.name.lowercase()} permission repaired through the root helper")
                else -> Log.w(TAG, "${grant.name.lowercase()} permission not repaired (${outcome.name.lowercase()}); dependent features remain unavailable")
            }
        }
    }

    /**
     * Only an observed missing grant is submitted. Re-reading Android, rather than trusting an OK,
     * proves the repair; an unreadable state or a refused grant leaves the rest of startup running.
     * Camera/microphone access does not enable their features or open either device.
     */
    fun repair(
        sdkInt: Int,
        hasMicrophone: Boolean,
        hasCamera: Boolean,
        granted: (Grant) -> Boolean,
        helper: Daemon,
        packageName: String,
    ): Map<Grant, Outcome> = Grant.entries.filter { grant ->
        when (grant) {
            Grant.NOTIFICATIONS -> sdkInt >= Build.VERSION_CODES.TIRAMISU
            Grant.MICROPHONE -> hasMicrophone
            Grant.CAMERA -> hasCamera
            else -> true
        }
    }.associateWith { grant ->
        when (runCatching { granted(grant) }.getOrNull()) {
            true -> Outcome.HELD
            null -> Outcome.UNREADABLE
            false -> {
                val reply = helper.sendLong("GRANT $packageName ${grant.name}", TIMEOUT_MS)
                // A lost reply may still have granted it. Android's readback remains the authority.
                if (runCatching { granted(grant) }.getOrDefault(false)) Outcome.CLAIMED
                else if (reply == DaemonLongResult.NotSubmitted) Outcome.NO_HELPER
                else Outcome.REFUSED
            }
        }
    }

    private const val TIMEOUT_MS = 30_000L
    private const val TAG = "ha-paneld/permissions"
}
