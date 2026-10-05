package io.github.maxlyth.hapaneld.shizuku

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.R

/** On-panel-only opt-in surface. ConfigActivity is not exported and there is no remote equivalent. */
object ShizukuSetupDialog {
    internal enum class PrimaryAction(val label: String) {
        NONE(""),
        ENABLE("Enable"),
        REQUEST_PERMISSION("Request permission"),
        OPEN_MANAGER("Open Shizuku"),
        DISABLE("Disable"),
    }

    internal fun entryVisible(
        consented: Boolean,
        managerStatus: ShizukuManagerIdentity.Status,
    ): Boolean = consented || managerStatus != ShizukuManagerIdentity.Status.MISSING

    fun show(activity: AppCompatActivity) {
        ShizukuBridge.refresh()
        val consented = ShizukuConsent.enabled(activity)
        val state = ShizukuBridge.state
        val managerRunning = ShizukuBridge.managerRunning()
        val message = activity.getString(
            R.string.shizuku_grant_scope,
            activity.getString(R.string.shizuku_scope),
        ).let { "${localizedDescription(activity, state)}\n\n$it" }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.shizuku_title)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)

        val primaryAction = primaryAction(consented, state, managerRunning)
        when {
            state == ShizukuState.MANAGER_MISSING || state == ShizukuState.MANAGER_UNTRUSTED -> {
                if (disableAvailable(consented, state)) {
                    dialog.setNeutralButton(R.string.disable) { _, _ -> ShizukuBridge.disable() }
                }
            }
            else -> {
                when (primaryAction) {
                    PrimaryAction.ENABLE,
                    PrimaryAction.REQUEST_PERMISSION -> dialog.setPositiveButton(primaryAction.labelId()) { _, _ ->
                        ShizukuBridge.enable(activity)
                    }
                    PrimaryAction.OPEN_MANAGER -> dialog.setPositiveButton(primaryAction.labelId()) { _, _ ->
                        launchManager(activity)
                    }
                    PrimaryAction.DISABLE -> dialog.setPositiveButton(primaryAction.labelId()) { _, _ ->
                        ShizukuBridge.disable()
                    }
                    PrimaryAction.NONE -> Unit
                }
                if (consented && primaryAction != PrimaryAction.DISABLE) {
                    dialog.setNeutralButton(R.string.disable) { _, _ -> ShizukuBridge.disable() }
                }
            }
        }
        dialog.show()
    }

    private fun PrimaryAction.labelId(): Int = when (this) {
        PrimaryAction.ENABLE -> R.string.shizuku_enable
        PrimaryAction.REQUEST_PERMISSION -> R.string.shizuku_request_permission
        PrimaryAction.OPEN_MANAGER -> R.string.shizuku_open
        PrimaryAction.DISABLE -> R.string.disable
        PrimaryAction.NONE -> error("NONE has no action label")
    }

    private fun localizedDescription(activity: AppCompatActivity, state: ShizukuState): String = activity.getString(
        when (state) {
            ShizukuState.MANAGER_MISSING -> R.string.shizuku_not_installed
            ShizukuState.MANAGER_UNTRUSTED -> R.string.shizuku_untrusted
            ShizukuState.DISABLED -> R.string.shizuku_disabled
            ShizukuState.STOPPED -> R.string.shizuku_stopped
            ShizukuState.PERMISSION_REQUIRED -> R.string.shizuku_permission_needed
            ShizukuState.MANUAL_GRANT_REQUIRED -> R.string.shizuku_permission_denied
            ShizukuState.BINDING -> R.string.shizuku_connecting
            ShizukuState.READY -> R.string.shizuku_ready
            ShizukuState.INCOMPATIBLE -> R.string.shizuku_incompatible
            ShizukuState.ERROR -> R.string.shizuku_error
        },
    )

    internal fun description(state: ShizukuState): String = when (state) {
        ShizukuState.MANAGER_MISSING ->
            "Shizuku is not installed. Use the ha-paneld provisioning script once over ADB, then approve access here on the panel."
        ShizukuState.MANAGER_UNTRUSTED ->
            "A package named Shizuku is installed, but its signing certificate is not trusted. ha-paneld will not connect to it."
        ShizukuState.DISABLED ->
            "Enhanced access is disabled in ha-paneld. Choose Enable here on this panel to begin local approval."
        ShizukuState.STOPPED ->
            "Enhanced access is enabled in ha-paneld, but the Shizuku service is stopped. Open Shizuku, start its service, then return here."
        ShizukuState.PERMISSION_REQUIRED ->
            "Shizuku is running and ready to show its local permission prompt. Request permission to continue."
        ShizukuState.MANUAL_GRANT_REQUIRED ->
            "Shizuku access was denied, so another permission prompt cannot be shown. Open Shizuku, choose Authorized applications, select ha-paneld, and grant access manually. Then return here."
        ShizukuState.BINDING -> "Connecting to Shizuku…"
        ShizukuState.READY -> "Enhanced access is ready."
        ShizukuState.INCOMPATIBLE ->
            "The Shizuku service returned an unexpected identity or protocol version. Access is blocked."
        ShizukuState.ERROR -> "Shizuku could not be connected. Close this dialog and try again."
    }

    internal fun disableAvailable(consented: Boolean, state: ShizukuState): Boolean = consented &&
        (state == ShizukuState.MANAGER_MISSING || state == ShizukuState.MANAGER_UNTRUSTED)

    internal fun primaryAction(
        consented: Boolean,
        state: ShizukuState,
        managerRunning: Boolean,
    ): PrimaryAction = when {
        state == ShizukuState.MANAGER_MISSING || state == ShizukuState.MANAGER_UNTRUSTED ->
            PrimaryAction.NONE
        state == ShizukuState.MANUAL_GRANT_REQUIRED -> PrimaryAction.OPEN_MANAGER
        state == ShizukuState.STOPPED && !managerRunning -> PrimaryAction.OPEN_MANAGER
        consented && state == ShizukuState.PERMISSION_REQUIRED -> PrimaryAction.REQUEST_PERMISSION
        consented -> PrimaryAction.DISABLE
        else -> PrimaryAction.ENABLE
    }

    private fun launchManager(activity: AppCompatActivity) {
        val intent: Intent = activity.packageManager.getLaunchIntentForPackage(ShizukuManagerIdentity.PACKAGE)
            ?: return
        runCatching { activity.startActivity(intent) }
    }
}
