package io.github.maxlyth.hapaneld

import io.panelassistant.android.BuildConfig
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import io.github.maxlyth.hapaneld.upgrade.PREPARE_UPGRADE_ACTION
import io.github.maxlyth.hapaneld.upgrade.RELEASE_UPGRADE_ACTION
import io.github.maxlyth.hapaneld.upgrade.RENEW_UPGRADE_ACTION
import io.github.maxlyth.hapaneld.upgrade.UPGRADE_NONCE_EXTRA
import io.github.maxlyth.hapaneld.upgrade.UpgradeRequestCompletion
import io.github.maxlyth.hapaneld.upgrade.UpgradeShutdownCoordinator
import io.github.maxlyth.hapaneld.upgrade.canonicalUpgradeNonce
import io.github.maxlyth.hapaneld.upgrade.formatUpgradeReady
import io.github.maxlyth.hapaneld.upgrade.formatUpgradeReleased
import io.github.maxlyth.hapaneld.upgrade.formatUpgradeRenewed
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import java.util.concurrent.atomic.AtomicBoolean

/** ADB-shell-only ordered-broadcast barrier; all quiescence work is owned by PaneldService.onDestroy. */
class UpgradeControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
            failResult("guard_db_maintenance_active")
            return
        }
        when (intent.action) {
            PREPARE_UPGRADE_ACTION -> prepare(context, intent)
            RELEASE_UPGRADE_ACTION -> release(context, intent)
            RENEW_UPGRADE_ACTION -> renew(context, intent)
            else -> failResult("unknown_action")
        }
    }

    private fun prepare(context: Context, intent: Intent) {
        val nonce = canonicalUpgradeNonce(intent.getStringExtra(UPGRADE_NONCE_EXTRA))
            ?: return failResult("invalid_nonce")
        val completion = OrderedUpgradeCompletion(goAsync())
        if (!UpgradeShutdownCoordinator.arm(context, nonce, completion)) {
            completion.failed("request_already_active")
            return
        }
        val stopped = runCatching {
            context.stopService(Intent(context, PaneldService::class.java))
        }.getOrDefault(false)
        if (!stopped) {
            UpgradeShutdownCoordinator.cancelAndResume(context, nonce, "service_not_running")
        }
    }

    private fun release(context: Context, intent: Intent) {
        val nonce = canonicalUpgradeNonce(intent.getStringExtra(UPGRADE_NONCE_EXTRA))
            ?: return failResult("invalid_nonce")
        if (!UpgradeShutdownCoordinator.releaseAndResume(context, nonce)) {
            failResult("release_failed")
            return
        }
        resultCode = Activity.RESULT_OK
        resultData = formatUpgradeReleased(nonce)
    }

    private fun renew(context: Context, intent: Intent) {
        val nonce = canonicalUpgradeNonce(intent.getStringExtra(UPGRADE_NONCE_EXTRA))
            ?: return failResult("invalid_nonce")
        if (!UpgradeShutdownCoordinator.renew(context, nonce)) return failResult("renewal_failed")
        resultCode = Activity.RESULT_OK
        resultData = formatUpgradeRenewed(nonce)
    }

    private fun failResult(reason: String) {
        resultCode = Activity.RESULT_CANCELED
        resultData = "HAPANELD_UPGRADE_ERROR_V1:$reason"
    }

    private class OrderedUpgradeCompletion(
        private val pending: PendingResult,
    ) : UpgradeRequestCompletion {
        private val finished = AtomicBoolean()

        override fun ready(nonce: String, proof: CleanDatabaseProof) {
            finish(
                resultCode = Activity.RESULT_OK,
                data = formatUpgradeReady(
                    nonce = nonce,
                    pid = android.os.Process.myPid(),
                    versionCode = BuildConfig.VERSION_CODE,
                    proof = proof,
                ),
            )
        }

        override fun failed(reason: String) {
            finish(Activity.RESULT_CANCELED, "HAPANELD_UPGRADE_ERROR_V1:$reason")
        }

        private fun finish(resultCode: Int, data: String) {
            if (!finished.compareAndSet(false, true)) return
            runCatching {
                pending.setResultCode(resultCode)
                pending.setResultData(data)
            }.onFailure { Log.e("UpgradeControl", "could not write ordered-broadcast result", it) }
            runCatching { pending.finish() }
                .onFailure { Log.e("UpgradeControl", "could not finish ordered-broadcast result", it) }
        }
    }
}
