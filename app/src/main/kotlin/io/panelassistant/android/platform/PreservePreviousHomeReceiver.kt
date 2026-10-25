package io.panelassistant.android.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import io.panelassistant.android.Config
import io.panelassistant.android.confirmHomeTakeover
import io.panelassistant.android.preservePreviousHome
import io.panelassistant.android.setupEverCompleted
import io.panelassistant.android.migrateSetupQuestionsForExistingInstall

/** Shell-only write-ahead capture and acknowledgement for the installer's deferred HOME ownership. */
class PreservePreviousHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultCode = if (runCatching {
            val config = Config(context)
            val env = AndroidSystemEnv(context)
            val operation = intent.getStringExtra("operation")
            if (operation != null) config.migrateSetupQuestionsForExistingInstall()
            when (operation) {
                "setup_eligibility" -> config.setupEverCompleted.also {
                    if (it) resultData = "panel-assistant-setup-eligible"
                }
                "acknowledge_home" -> {
                    val home = env.defaultHome()
                    config.setupEverCompleted && home != null && home.pkg == context.packageName &&
                        home in env.homeActivities() && config.confirmHomeTakeover(home, home)
                }
                null -> {
                    val supplied = intent.getStringExtra("previous_home_component")
                    val home = if (supplied == null) env.defaultHome() else {
                        val component = ComponentName.unflattenFromString(supplied) ?: return@runCatching false
                        ActivityRef(component.packageName, component.className)
                    }
                    val candidates = env.homeActivities()
                    if (supplied != null && (home !in candidates || home?.pkg == "android" ||
                        io.panelassistant.android.AppIdentity.isPanelApp(home?.pkg) ||
                        io.panelassistant.android.config.TamePackagePolicy.isCritical(home?.pkg.orEmpty()))) false
                    else config.preservePreviousHome(home, candidates, armTakeover = true)
                }
                else -> false
            }
        }.getOrDefault(false)) 1 else 0
    }
}
