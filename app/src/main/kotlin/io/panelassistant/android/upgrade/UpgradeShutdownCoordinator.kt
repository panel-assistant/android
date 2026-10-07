package io.panelassistant.android.upgrade

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.PaneldService
import io.panelassistant.android.persistence.CleanDatabaseProof
import io.panelassistant.android.persistence.StateQuiescence
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val UPGRADE_HOLD_TIMEOUT_MS = 180_000L

internal interface UpgradeRequestCompletion {
    fun ready(nonce: String, proof: CleanDatabaseProof)
    fun failed(reason: String)

    /**
     * MIGRATION-ONLY. Delete with the identity migration; nothing else implements this.
     *
     * The service is tearing down into a process exit, so no clean proof and no same-process
     * successor will follow. An ordinary upgrade request has nothing to do here and keeps the
     * default; the bridge release overrides it, because a bridge whose process is ending has in
     * substance retired — the port frees and the service stops — and its durable marker must not
     * depend on the one teardown shape it cannot have. Measured on hardware, 2026-09-20: every
     * release ended this way, so the marker was never written and the successor waited forever.
     *
     * Removing it means deleting this method, its default, the call in [failShutdown] and
     * [Gate.notifyExitingProcess]. No permanent caller depends on any of them.
     */
    fun exitingProcess(reason: String) {}
}

internal data class UpgradeCancellation(
    val matched: Boolean,
    val freeze: StateQuiescence? = null,
    val releaseSuccessor: (() -> Unit)? = null,
)

internal class UpgradeShutdownClaim internal constructor(internal val token: Any)

internal fun releaseUpgradeHold(
    freeze: StateQuiescence?,
    additionalFreeze: StateQuiescence? = null,
    releaseSuccessor: (() -> Unit)?,
    restartService: (() -> Unit)?,
): List<Throwable> {
    val failures = mutableListOf<Throwable>()
    listOfNotNull(freeze, additionalFreeze).distinct().forEach { lease ->
        runCatching { lease.close() }.exceptionOrNull()?.let(failures::add)
    }
    releaseSuccessor?.let { release ->
        runCatching(release).exceptionOrNull()?.let(failures::add)
    }
    restartService?.let { restart ->
        runCatching(restart).exceptionOrNull()?.let(failures::add)
    }
    return failures
}

/** One process-local upgrade request. Service shutdown remains the sole quiescence implementation. */
internal class UpgradeRequestGate {
    private data class Active(
        val nonce: String,
        val completion: UpgradeRequestCompletion,
        var freeze: StateQuiescence? = null,
        var releaseSuccessor: (() -> Unit)? = null,
        var ready: Boolean = false,
        var claimToken: Any? = null,
        var expiresAtMillis: Long? = null,
    )

    private var active: Active? = null

    @Synchronized
    fun arm(nonce: String, completion: UpgradeRequestCompletion, expiresAtMillis: Long? = null): Boolean {
        if (active != null) return false
        active = Active(nonce, completion, expiresAtMillis = expiresAtMillis)
        return true
    }

    /** A request is armed, whether or not the service has torn down yet. */
    @Synchronized
    fun isArmed(): Boolean = active != null

    @Synchronized
    fun claimShutdown(): UpgradeShutdownClaim? {
        val request = active ?: return null
        if (request.claimToken != null) return null
        val token = Any()
        request.claimToken = token
        return UpgradeShutdownClaim(token)
    }

    /** Transfer the normal shutdown freeze to the request only after the normal stable-DB proof. */
    fun holdReady(
        claim: UpgradeShutdownClaim,
        freeze: StateQuiescence,
        proof: CleanDatabaseProof,
        releaseSuccessor: () -> Unit,
    ): Boolean {
        val completion = synchronized(this) {
            val request = active ?: return false
            if (request.ready || request.claimToken !== claim.token) return false
            request.freeze = freeze
            request.releaseSuccessor = releaseSuccessor
            request.ready = true
            request.completion to request.nonce
        }
        // Clean proof persistence or a helper handoff can block. Never run it while holding the gate
        // monitor: cancel/recovery observers must still be able to inspect the unique active claim.
        completion.first.ready(completion.second, proof)
        return true
    }

    /**
     * MIGRATION-ONLY. Delete with the identity migration, along with its call in [failShutdown].
     *
     * Tell the armed request that this teardown ends in a process exit, before the claim is
     * cancelled. Read the completion under the monitor and call it outside: retirement writes a
     * durable marker and may end the process, neither of which may run while the gate is held.
     */
    fun notifyExitingProcess(claim: UpgradeShutdownClaim?, reason: String) {
        val completion = synchronized(this) {
            val request = active ?: return
            if (claim != null && request.claimToken !== claim.token) return
            if (request.ready) return
            request.completion
        }
        completion.exitingProcess(reason)
    }

    fun cancel(nonce: String?, reason: String, expiredAtMillis: Long? = null): UpgradeCancellation {
        val cancelled = synchronized(this) {
            val request = active ?: return UpgradeCancellation(matched = false)
            if (nonce != null && request.nonce != nonce) return UpgradeCancellation(matched = false)
            if (expiredAtMillis != null) {
                val deadline = request.expiresAtMillis ?: return UpgradeCancellation(matched = false)
                if (expiredAtMillis < deadline) return UpgradeCancellation(matched = false)
            }
            active = null
            request to UpgradeCancellation(
                matched = true,
                freeze = request.freeze,
                releaseSuccessor = request.releaseSuccessor,
            )
        }
        cancelled.first.completion.failed(reason)
        return cancelled.second
    }

    fun cancelClaim(claim: UpgradeShutdownClaim, reason: String): UpgradeCancellation {
        val cancelled = synchronized(this) {
            val request = active ?: return UpgradeCancellation(matched = false)
            if (request.claimToken !== claim.token) return UpgradeCancellation(matched = false)
            active = null
            request to UpgradeCancellation(
                matched = true,
                freeze = request.freeze,
                releaseSuccessor = request.releaseSuccessor,
            )
        }
        cancelled.first.completion.failed(reason)
        return cancelled.second
    }

}

internal object UpgradeShutdownCoordinator {
    private val gate = UpgradeRequestGate()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ha-paneld-upgrade-hold-watchdog").apply { isDaemon = true }
    }

    fun arm(context: Context, nonce: String, completion: UpgradeRequestCompletion): Boolean {
        if (!gate.arm(nonce, completion, SystemClock.elapsedRealtime() + UPGRADE_HOLD_TIMEOUT_MS)) return false
        scheduleWatchdog(context, nonce)
        return true
    }

    private fun scheduleWatchdog(context: Context, nonce: String) {
        val appContext = context.applicationContext
        watchdog.schedule(
            { cancelAndResume(appContext, nonce, "watchdog_expired", SystemClock.elapsedRealtime()) },
            UPGRADE_HOLD_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Guard DB owns its settlement deadline in the root journal. A generic app watchdog must never
     *  release writers while CAPTURE/install has an indeterminate reply. */
    fun armWithoutWatchdog(nonce: String, completion: UpgradeRequestCompletion): Boolean =
        gate.arm(nonce, completion)

    fun holdAfterCleanShutdown(
        claim: UpgradeShutdownClaim?,
        freeze: StateQuiescence,
        proof: CleanDatabaseProof,
        releaseSuccessor: () -> Unit,
    ): Boolean = claim != null && gate.holdReady(claim, freeze, proof, releaseSuccessor)

    fun claimShutdown(): UpgradeShutdownClaim? = gate.claimShutdown()

    /** True from [arm] until the request is released or cancelled: the service is, or is being, held. */
    fun isArmed(): Boolean = gate.isArmed()

    fun failShutdown(
        context: Context,
        claim: UpgradeShutdownClaim?,
        freeze: StateQuiescence?,
        releaseSuccessor: () -> Unit,
        reason: String,
    ) {
        // MIGRATION-ONLY (delete with the identity migration). A request that can still act on a
        // process exit gets told before its claim is torn down.
        // The bridge release retires here; every other request keeps the default no-op and is
        // cancelled and resumed exactly as before.
        gate.notifyExitingProcess(claim, reason)
        val cancelled = if (claim == null) UpgradeCancellation(matched = false)
            else gate.cancelClaim(claim, reason)
        logReleaseFailures(releaseUpgradeHold(
            freeze = cancelled.freeze,
            additionalFreeze = freeze?.takeUnless { it === cancelled.freeze },
            releaseSuccessor = if (cancelled.matched) cancelled.releaseSuccessor ?: releaseSuccessor else null,
            restartService = if (cancelled.matched) {
                { PaneldService.start(context.applicationContext) }
            } else null,
        ))
    }

    fun cancelAndResume(context: Context, nonce: String, reason: String, expiredAtMillis: Long? = null): Boolean {
        val cancelled = gate.cancel(nonce, reason, expiredAtMillis)
        if (!cancelled.matched) return false
        logReleaseFailures(releaseUpgradeHold(cancelled.freeze, releaseSuccessor = cancelled.releaseSuccessor, restartService = {
            PaneldService.start(context.applicationContext)
        }))
        return true
    }

    private fun logReleaseFailures(failures: List<Throwable>) {
        failures.forEach { Log.e("UpgradeControl", "upgrade release step failed", it) }
    }
}
