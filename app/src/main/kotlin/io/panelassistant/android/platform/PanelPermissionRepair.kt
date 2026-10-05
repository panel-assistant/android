package io.panelassistant.android.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.panelassistant.android.AppIdentity
import io.panelassistant.android.camera.CameraCapabilityReason
import io.panelassistant.android.util.HelperClient

/** Repair installer omissions before the service constructs its feature owners. */
internal object PanelPermissionRepair {
    enum class Grant(val wire: String) {
        NOTIFICATIONS("notifications"), WRITESETTINGS("write_settings"), OVERLAY("overlay"),
        ACCESSIBILITY("accessibility"), MICROPHONE("microphone"), CAMERA("camera"),
    }
    enum class State(val wire: String) {
        HELD("held"), MISSING("missing"), NOT_REQUIRED("not_required"), UNREADABLE("unreadable"),
    }
    enum class Outcome { HELD, CLAIMED, NO_HELPER, REFUSED, UNREADABLE }

    /** Below Android 13 notifications are granted at install. Also used during package migration. */
    fun notificationsHeld(sdkInt: Int, granted: () -> Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || granted()

    fun accessibilityHeld(packageName: String, services: String?, enabled: Boolean): Boolean {
        val component = AppIdentity.component(packageName, ".input.PanelAccessibilityService")
        val fullComponent = "$packageName/${AppIdentity.CODE_PACKAGE}.input.PanelAccessibilityService"
        return enabled && services.orEmpty().split(':').any { it == component || it == fullComponent }
    }

    /** Feature metadata can prove hardware while early camera enumeration is still unavailable. */
    fun cameraRequired(reason: CameraCapabilityReason, hardwareFeature: Boolean?): Boolean? = when {
        reason.capable -> true
        reason != CameraCapabilityReason.UNDETERMINED -> false
        hardwareFeature == true -> true
        else -> null // An unanswered enumeration is not proof that the hardware is absent.
    }

    fun repair(context: Context, hasMicrophone: Boolean, cameraReason: CameraCapabilityReason) {
        repair(Build.VERSION.SDK_INT, hasMicrophone, cameraRequired(context, cameraReason),
            { granted(context, it) }, HelperClient, context.packageName).forEach { (grant, outcome) ->
            when (outcome) {
                Outcome.HELD -> Unit
                Outcome.CLAIMED -> Log.i(TAG, "${grant.name.lowercase()} permission repaired through the root helper")
                else -> Log.w(TAG, "${grant.name.lowercase()} permission not repaired (${outcome.name.lowercase()}); dependent features remain unavailable")
            }
        }
    }

    /** Fresh readback, independent of feature settings, capture and privileged repair availability. */
    fun observe(context: Context, hasMicrophone: Boolean, cameraReason: CameraCapabilityReason): Map<Grant, State> =
        observe(Build.VERSION.SDK_INT, hasMicrophone, cameraRequired(context, cameraReason)) { granted(context, it) }

    private fun cameraRequired(context: Context, reason: CameraCapabilityReason): Boolean? =
        cameraRequired(reason, if (reason == CameraCapabilityReason.UNDETERMINED) {
            runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }.getOrNull()
        } else null)

    private fun granted(context: Context, grant: Grant): Boolean = when (grant) {
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

    fun observe(
        sdkInt: Int,
        hasMicrophone: Boolean,
        hasCamera: Boolean?,
        granted: (Grant) -> Boolean,
    ): Map<Grant, State> = Grant.entries.associateWith { grant ->
        observe(grant, required(sdkInt, hasMicrophone, hasCamera, grant), granted)
    }

    private fun required(sdkInt: Int, hasMicrophone: Boolean, hasCamera: Boolean?, grant: Grant): Boolean? =
        when (grant) {
            Grant.NOTIFICATIONS -> sdkInt >= Build.VERSION_CODES.TIRAMISU
            Grant.MICROPHONE -> hasMicrophone
            Grant.CAMERA -> hasCamera
            else -> true
        }

    private fun observe(grant: Grant, required: Boolean?, granted: (Grant) -> Boolean): State =
        when (required) {
            false -> State.NOT_REQUIRED
            null -> State.UNREADABLE
            true -> observe(grant, granted)
        }

    private fun observe(grant: Grant, granted: (Grant) -> Boolean): State =
        when (runCatching { granted(grant) }.getOrNull()) {
            true -> State.HELD
            false -> State.MISSING
            null -> State.UNREADABLE
        }

    /** All six fields are present; incomplete observations never imply a held or inapplicable grant. */
    fun statusJson(states: Map<Grant, State>): String = Grant.entries.joinToString(",", "{", "}") {
        "\"${it.wire}\":\"${(states[it] ?: State.UNREADABLE).wire}\""
    }

    /**
     * Only an observed missing grant is submitted. Re-reading Android, rather than trusting an OK,
     * proves the repair; an unreadable state or a refused grant leaves the rest of startup running.
     * Camera/microphone access does not enable their features or open either device.
     */
    fun repair(
        sdkInt: Int,
        hasMicrophone: Boolean,
        hasCamera: Boolean?,
        granted: (Grant) -> Boolean,
        helper: Daemon,
        packageName: String,
    ): Map<Grant, Outcome> = Grant.entries.filter { required(sdkInt, hasMicrophone, hasCamera, it) != false }
        .associateWith { grant ->
            when (observe(grant, required(sdkInt, hasMicrophone, hasCamera, grant), granted)) {
                State.HELD, State.NOT_REQUIRED -> Outcome.HELD
                State.UNREADABLE -> Outcome.UNREADABLE
                State.MISSING -> {
                    val reply = runCatching { helper.sendLong("GRANT $packageName ${grant.name}", TIMEOUT_MS) }.getOrNull()
                    // A lost reply may still have granted it. Android's readback remains the authority.
                    when (observe(grant, granted)) {
                        State.HELD -> Outcome.CLAIMED
                        State.UNREADABLE -> Outcome.UNREADABLE
                        else -> if (reply == DaemonLongResult.NotSubmitted) Outcome.NO_HELPER else Outcome.REFUSED
                    }
                }
            }
        }

    private const val TIMEOUT_MS = 30_000L
    private const val TAG = "ha-paneld/permissions"
}
