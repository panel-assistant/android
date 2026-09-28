package io.github.maxlyth.hapaneld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Restarts the agent after a panel reboot or a self-update (`adb install -r`). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        dispatchStartup(intent.action, Build.VERSION.SDK_INT,
            startService = { PaneldService.start(context) },
            openLauncher = {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}

/** The post-install route must warm Oreo before arming its foreground-service deadline. */
internal fun dispatchStartup(
    action: String?,
    sdkInt: Int,
    startService: () -> Unit,
    openLauncher: () -> Unit,
) {
    when (action) {
        Intent.ACTION_BOOT_COMPLETED -> startService()
        Intent.ACTION_MY_PACKAGE_REPLACED ->
            if (sdkInt <= Build.VERSION_CODES.O_MR1) openLauncher() else startService()
    }
}
