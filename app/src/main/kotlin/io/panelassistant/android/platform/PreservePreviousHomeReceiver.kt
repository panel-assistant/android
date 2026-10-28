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
import io.panelassistant.android.homeTakeoverPending
import io.panelassistant.android.control.Su
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.util.HelperClient
import kotlin.concurrent.thread

/** Shell-only write-ahead capture and acknowledgement for the installer's deferred HOME ownership. */
class PreservePreviousHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operation = intent.getStringExtra("operation")
        if (operation == "claim_pending_home" || operation == "pending_home_shell_eligibility") {
            val pending = goAsync()
            // HOME actuators can wait for Android. Keep that work off the receiver's main thread.
            thread(name = "pending-home") {
                try {
                    val config = Config(context)
                    val env = AndroidSystemEnv(context)
                    config.migrateSetupQuestionsForExistingInstall()
                    val result = pendingHomeOperation(
                        config, env, intent.getStringExtra("home_component"),
                        claim = operation == "claim_pending_home",
                        nativeAvailable = { HelperClient.available() || Su.availableIsolated() },
                        setHome = { SystemController(env).setHomeActivity(it) },
                    )
                    pending.resultCode = if (operation == "pending_home_shell_eligibility" &&
                        result == PendingHomeResult.SHELL) 1 else result.code
                    pending.resultData = result.data
                } catch (_: Exception) {
                    pending.resultCode = 0
                    pending.resultData = null
                } finally {
                    pending.finish()
                }
            }
            return
        }
        resultCode = if (runCatching {
            val config = Config(context)
            val env = AndroidSystemEnv(context)
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

internal enum class PendingHomeResult(val code: Int, val data: String?) {
    DEFERRED(0, null),
    CLAIMED(1, "panel-assistant-pending-home-claimed"),
    SHELL(2, "panel-assistant-pending-home-shell"),
}

/** The automatic setup caller has less authority than an explicit install or update repair. */
internal fun pendingHomeOperation(
    config: Config,
    env: SystemEnv,
    component: String?,
    claim: Boolean,
    nativeAvailable: () -> Boolean,
    setHome: (String) -> Boolean,
): PendingHomeResult {
    // Probing can block. The intent must be read again after it, inside the mutation transaction.
    val native = nativeAvailable()
    return config.synchronizedTransaction {
        if (!config.setupEverCompleted || !config.homeTakeoverPending) {
            return@synchronizedTransaction PendingHomeResult.DEFERRED
        }
        if (!claim) return@synchronizedTransaction if (native) PendingHomeResult.DEFERRED else PendingHomeResult.SHELL
        val target = env.homeActivities().singleOrNull {
            it.pkg == env.ownPackage && it.component == component
        } ?: return@synchronizedTransaction PendingHomeResult.DEFERRED
        // A rootless app cannot execute the ADB shell's HOME command. This receipt is deliberately
        // distinct; the shell rechecks it before each mutation, and never follows a failed native claim.
        if (!native) return@synchronizedTransaction PendingHomeResult.SHELL
        // Handback clears pending using the same Config lock: it either follows this actual mutation,
        // or has already revoked it. An earlier eligibility reply cannot grant a later shell write.
        if (setHome(target.component) && config.confirmHomeTakeover(target, env.defaultHome())) {
            PendingHomeResult.CLAIMED
        } else PendingHomeResult.DEFERRED
    }
}
