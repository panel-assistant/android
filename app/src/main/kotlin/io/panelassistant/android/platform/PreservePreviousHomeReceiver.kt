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
import io.panelassistant.android.previousHomeComponent
import io.panelassistant.android.completeHomeTakeover
import io.panelassistant.android.withHomeCustody
import io.panelassistant.android.control.Su
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.AndroidInput
import kotlin.concurrent.thread

/** Shell-only write-ahead capture and acknowledgement for the installer's deferred HOME ownership. */
class PreservePreviousHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operation = intent.getStringExtra("operation")
        if (operation == "claim_pending_home" || operation == "pending_home_shell_lock_eligibility") {
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
                        restoreHome = { SystemController(env).restoreDashboardHome(it) },
                        shellLockIdentity = intent.getStringExtra("home_lock_identity"),
                    )
                    pending.resultCode = result.code
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
        var data: String? = null
        val ok = runCatching {
            val config = Config(context)
            val env = AndroidSystemEnv(context)
            val result = previousHomeOperation(
                config, env, context.packageName, operation, intent.getStringExtra("previous_home_component"),
                shellLockIdentity = intent.getStringExtra("home_lock_identity"),
            )
            data = result.data
            result.code == 1
        }.getOrDefault(false)
        resultCode = if (ok) 1 else 0
        if (ok && data != null) resultData = data
    }
}

internal class PreviousHomeReply(val code: Int, val data: String? = null)

/** The synchronous operations: setup eligibility, acknowledgement and previous-HOME capture. */
internal fun previousHomeOperation(
    config: Config,
    env: SystemEnv,
    ownPackage: String,
    operation: String?,
    supplied: String?,
    shellLockIdentity: String? = null,
    lockIdentity: () -> String? = NativeHomeLock::identity,
    parse: (String) -> ActivityRef? = {
        ComponentName.unflattenFromString(it)?.let { component -> ActivityRef(component.packageName, component.className) }
    },
): PreviousHomeReply {
    if (operation != null) config.migrateSetupQuestionsForExistingInstall()
    if (operation == "dashboard_home_policy" || operation == "restore_previous_home_shell_lock_eligibility" ||
        operation == "acknowledge_retained_home") return withHomeCustody {
        if (!config.setupEverCompleted) return@withHomeCustody PreviousHomeReply(0)
        if (config.dashboardOwnsHome(env)) return@withHomeCustody if (operation == "dashboard_home_policy")
            PreviousHomeReply(1, "panel-assistant-home-builtin") else PreviousHomeReply(0)
        if (!AndroidInput.isPackage(config.dashboardPackage)) return@withHomeCustody PreviousHomeReply(0)
        val retained = retainedDashboardHome(env, config.previousHomeComponent)
            ?: return@withHomeCustody PreviousHomeReply(0)
        when (operation) {
            "acknowledge_retained_home" -> if (retained.component == supplied && env.defaultHome() == retained &&
                config.completeHomeTakeover()) PreviousHomeReply(1, "panel-assistant-home-retained") else PreviousHomeReply(0)
            "restore_previous_home_shell_lock_eligibility" -> if (retained.component == supplied &&
                env.defaultHome() != retained && shellLockIdentity != null && shellLockIdentity == lockIdentity())
                PreviousHomeReply(1, "panel-assistant-home-restore-shell-locked") else PreviousHomeReply(0)
            else -> if (env.defaultHome() == retained) {
                if (config.completeHomeTakeover()) PreviousHomeReply(2, "panel-assistant-home-retained") else PreviousHomeReply(0)
            } else PreviousHomeReply(3, "panel-assistant-home-restore:${retained.component}")
        }
    }
    val ok = when (operation) {
        "setup_eligibility" -> config.setupEverCompleted
        "acknowledge_home" -> {
            val home = env.defaultHome()
            config.setupEverCompleted && home != null && home.pkg == ownPackage &&
                home in env.homeActivities() && config.confirmHomeTakeover(home, home)
        }
        null -> {
            val home = if (supplied == null) env.defaultHome() else {
                parse(supplied) ?: return PreviousHomeReply(0)
            }
            val candidates = env.homeActivities()
            if (supplied != null && (home !in candidates || home?.pkg == "android" ||
                io.panelassistant.android.AppIdentity.isPanelApp(home?.pkg) ||
                io.panelassistant.android.config.TamePackagePolicy.isCritical(home?.pkg.orEmpty()))) false
            else config.preservePreviousHome(home, candidates, armTakeover = true)
        }
        else -> false
    }
    return if (!ok) PreviousHomeReply(0)
    else PreviousHomeReply(1, if (operation == "setup_eligibility") "panel-assistant-setup-eligible" else null)
}

internal enum class PendingHomeResult(val code: Int, val data: String?) {
    DEFERRED(0, null),
    CLAIMED(1, "panel-assistant-pending-home-claimed"),
    SHELL(2, "panel-assistant-pending-home-shell"),
    SHELL_LOCKED(1, "panel-assistant-pending-home-shell-locked"),
    RETAINED(1, "panel-assistant-pending-home-retained"),
}

/** The automatic setup caller has less authority than an explicit install or update repair. */
internal fun pendingHomeOperation(
    config: Config,
    env: SystemEnv,
    component: String?,
    claim: Boolean,
    nativeAvailable: () -> Boolean,
    shellLockIdentity: String? = null,
    lockIdentity: () -> String? = NativeHomeLock::identity,
    restoreHome: ((String) -> Boolean)? = null,
    setHome: (String) -> Boolean,
): PendingHomeResult {
    // Probing can block. The intent must be read again after it, under HOME custody.
    if (!claim && (shellLockIdentity == null || shellLockIdentity != lockIdentity())) {
        return PendingHomeResult.DEFERRED
    }
    val native = nativeAvailable()
    return withHomeCustody {
        if (!config.setupEverCompleted || !config.homeTakeoverPending) {
            return@withHomeCustody PendingHomeResult.DEFERRED
        }
        if (!config.dashboardOwnsHome(env)) {
            if (!AndroidInput.isPackage(config.dashboardPackage)) return@withHomeCustody PendingHomeResult.DEFERRED
            val retained = retainedDashboardHome(env, config.previousHomeComponent)
                ?: return@withHomeCustody PendingHomeResult.DEFERRED
            if (env.defaultHome() != retained && (!claim || !native || restoreHome?.invoke(retained.component) != true)) {
                return@withHomeCustody PendingHomeResult.DEFERRED
            }
            return@withHomeCustody if (env.defaultHome() == retained && config.completeHomeTakeover())
                PendingHomeResult.RETAINED else PendingHomeResult.DEFERRED
        }
        if (!claim) return@withHomeCustody if (native) PendingHomeResult.DEFERRED else PendingHomeResult.SHELL_LOCKED
        val target = env.homeActivities().singleOrNull {
            it.pkg == env.ownPackage && it.component == component
        } ?: return@withHomeCustody PendingHomeResult.DEFERRED
        // A rootless app cannot execute the ADB shell's HOME command. This receipt is deliberately
        // distinct; the shell must obtain shared exclusion and recheck intent before mutating HOME.
        if (!native) return@withHomeCustody PendingHomeResult.SHELL
        // Handback clears pending under the same HOME custody: it either follows this actual mutation,
        // or has already revoked it. An earlier eligibility reply cannot grant a later shell write.
        if (setHome(target.component) && config.confirmHomeTakeover(target, env.defaultHome())) {
            PendingHomeResult.CLAIMED
        } else PendingHomeResult.DEFERRED
    }
}
