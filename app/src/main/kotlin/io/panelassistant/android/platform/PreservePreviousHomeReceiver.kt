package io.panelassistant.android.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import io.panelassistant.android.Config
import io.panelassistant.android.preservePreviousHome

/** Shell-only write-ahead capture for installers that claim HOME before starting the app. */
class PreservePreviousHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = if (runCatching {
            val env = AndroidSystemEnv(context)
            val supplied = intent.getStringExtra("previous_home_component")
            if (supplied == null) env.preservePreviousHome() else {
                val component = ComponentName.unflattenFromString(supplied) ?: return@runCatching false
                val home = ActivityRef(component.packageName, component.className)
                val candidates = env.homeActivities()
                if (home !in candidates || home.pkg == "android" ||
                    io.panelassistant.android.AppIdentity.isPanelApp(home.pkg) ||
                    io.panelassistant.android.config.TamePackagePolicy.isCritical(home.pkg)) false
                else Config(context).preservePreviousHome(home, candidates)
            }
        }.getOrDefault(false)) 1 else 0
    }
}
