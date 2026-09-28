package io.github.maxlyth.hapaneld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Restarts the agent after a panel reboot or a self-update (`adb install -r`). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> PaneldService.start(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                    // On Oreo the first post-install process can spend the foreground-service deadline
                    // verifying dex. MainActivity warms the process and Config before it starts the
                    // service, then returns to the configured dashboard through the normal launch path.
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    PaneldService.start(context)
                }
            }
        }
    }
}
