package io.github.maxlyth.hapaneld.upgrade

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.PaneldService
import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import io.github.maxlyth.hapaneld.persistence.StateQuiescence
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val PREPARE_UPGRADE_ACTION = "io.github.maxlyth.hapaneld.action.PREPARE_UPGRADE"
internal const val RELEASE_UPGRADE_ACTION = "io.github.maxlyth.hapaneld.action.RELEASE_UPGRADE"
internal const val UPGRADE_NONCE_EXTRA = "nonce"

private const val UPGRADE_HOLD_TIMEOUT_MS = 180_000L
private val UPGRADE_NONCE = Regex("[0-9a-f]{32}")

internal fun canonicalUpgradeNonce(value: String?): String? =
    value?.takeIf { UPGRADE_NONCE.matches(it) }

internal fun formatUpgradeReady(
    nonce: String,
    pid: Int,
    versionCode: Int,
    proof: CleanDatabaseProof,
): String = "HAPANELD_UPGRADE_READY_V1:$nonce:$pid:$versionCode:${proof.databaseBytes}:" +
    "${proof.sha256}:${proof.userVersion}:${proof.appStateRows}"

internal fun formatUpgradeReleased(nonce: String): String =
    "HAPANELD_UPGRADE_RELEASED_V1:$nonce"

internal interface UpgradeRequestCompletion {
    fun ready(nonce: String, proof: CleanDatabaseProof)
    fun failed(reason: String)

    /**
     * The service is tearing down into a process exit, so no clean proof and no same-process
     * successor will follow. An ordinary upgrade request has nothing to do here and keeps the
     * default; the bridge release overrides it, because a bridge whose process is ending has in
     * substance retired — the port frees and the service stops — and its durable marker must not
     * depend on the one teardown shape it cannot have. Landing panel, 2026-09-20: every release
     * ended this way, so the marker was never written and the successor waited at RELEASE forever.
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

internal data class UpgradeReleaseOutcome(
    val accepted: Boolean,
    val failures: List<Throwable> = emptyList(),
) {
    val succeeded: Boolean get() = accepted && failures.isEmpty()
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
    )

    private var active: Active? = null

    @Synchronized
    fun arm(nonce: String, completion: UpgradeRequestCompletion): Boolean {
        if (active != null) return false
        active = Active(nonce, completion)
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

    fun cancel(nonce: String?, reason: String): UpgradeCancellation {
        val cancelled = synchronized(this) {
            val request = active ?: return UpgradeCancellation(matched = false)
            if (nonce != null && request.nonce != nonce) return UpgradeCancellation(matched = false)
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

    @Synchronized
    fun release(nonce: String): UpgradeCancellation {
        // RELEASE is deliberately stateless when no request survives (for example, package manager
        // killed the READY process). A different live nonce remains a conflict and cannot be released.
        val request = active ?: return UpgradeCancellation(matched = true)
        if (request.nonce != nonce) return UpgradeCancellation(matched = false)
        active = null
        if (!request.ready) request.completion.failed("released_before_ready")
        return UpgradeCancellation(
            matched = true,
            freeze = request.freeze,
            releaseSuccessor = request.releaseSuccessor,
        )
    }
}

internal fun executeUpgradeRelease(
    gate: UpgradeRequestGate,
    nonce: String,
    restartService: () -> Unit,
): UpgradeReleaseOutcome {
    val released = gate.release(nonce)
    if (!released.matched) return UpgradeReleaseOutcome(accepted = false)
    return UpgradeReleaseOutcome(
        accepted = true,
        failures = releaseUpgradeHold(
            freeze = released.freeze,
            releaseSuccessor = released.releaseSuccessor,
            restartService = restartService,
        ),
    )
}

internal object UpgradeShutdownCoordinator {
    private val gate = UpgradeRequestGate()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ha-paneld-upgrade-hold-watchdog").apply { isDaemon = true }
    }

    fun arm(context: Context, nonce: String, completion: UpgradeRequestCompletion): Boolean {
        if (!gate.arm(nonce, completion)) return false
        val appContext = context.applicationContext
        watchdog.schedule(
            { cancelAndResume(appContext, nonce, "watchdog_expired") },
            UPGRADE_HOLD_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        return true
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
        // A request that can still act on a process exit gets told before its claim is torn down.
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

    fun cancelAndResume(context: Context, nonce: String, reason: String): Boolean {
        val cancelled = gate.cancel(nonce, reason)
        if (!cancelled.matched) return false
        logReleaseFailures(releaseUpgradeHold(cancelled.freeze, releaseSuccessor = cancelled.releaseSuccessor, restartService = {
            PaneldService.start(context.applicationContext)
        }))
        return true
    }

    fun releaseAndResume(context: Context, nonce: String): Boolean {
        val outcome = executeUpgradeRelease(gate, nonce) {
            PaneldService.start(context.applicationContext)
        }
        logReleaseFailures(outcome.failures)
        return outcome.succeeded
    }

    private fun logReleaseFailures(failures: List<Throwable>) {
        failures.forEach { Log.e("UpgradeControl", "upgrade release step failed", it) }
    }
}
