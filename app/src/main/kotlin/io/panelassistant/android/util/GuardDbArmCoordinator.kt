package io.panelassistant.android.util

import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Process
import io.panelassistant.android.PaneldService
import io.panelassistant.android.persistence.CleanDatabaseProof
import io.panelassistant.android.upgrade.UpgradeRequestCompletion
import io.panelassistant.android.upgrade.UpgradeShutdownCoordinator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class GuardDbArmManifest(
    val session: String,
    val bootNonce: String,
    val a: GuardDbMaintenanceProtocol.Candidate,
    val b: GuardDbMaintenanceProtocol.Candidate,
    val overallBudgetMs: Long,
    val settingsAuthority: GuardDbSettingsAuthority,
    val securityAuthorityEpoch: Long,
) {
    init {
        require(a.role == GuardDbMaintenanceProtocol.Role.A && b.role == GuardDbMaintenanceProtocol.Role.B)
        require(a.sha256 != b.sha256 && a.versionCode < b.versionCode)
        require(a.expectedSchema + 1 == b.expectedSchema)
        require(a.contractMaximum == a.expectedSchema && b.contractMaximum == b.expectedSchema)
        require(overallBudgetMs in GuardDbMaintenanceProtocol.MIN_OVERALL_BUDGET_MS..
            GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS)
        require(settingsAuthority.version == GuardDbSettingsAuthority.VERSION)
        require(securityAuthorityEpoch > 0L)
        require(listOf(a, b).all {
            it.settingsAuthorityVersion == settingsAuthority.version &&
                it.settingsAuthorityBytes == settingsAuthority.bytes &&
                it.settingsAuthoritySha256 == settingsAuthority.sha256
        })
    }

    val signerSha256: String get() = AppInstaller.HA_PANELD.certSha256
}

internal sealed interface GuardDbArmTransferResult {
    data class Submitted(val generation: Long, val phase: GuardDbMaintenanceProtocol.Phase) : GuardDbArmTransferResult
    data class Failed(val result: GuardDbMaintenanceProtocol.Result) : GuardDbArmTransferResult
    data class Indeterminate(val probe: GuardDbMaintenanceClient.StatusProbe) : GuardDbArmTransferResult
    data class InvalidProof(val reason: String) : GuardDbArmTransferResult
}

private sealed interface GuardDbStepSettlement {
    data class Settled(val generation: Long, val phase: GuardDbMaintenanceProtocol.Phase) : GuardDbStepSettlement
    data class Failed(val result: GuardDbMaintenanceProtocol.Result) : GuardDbStepSettlement
    data class Indeterminate(val probe: GuardDbMaintenanceClient.StatusProbe) : GuardDbStepSettlement
}

internal fun executeGuardDbArmTransfer(
    client: GuardDbMaintenanceClient,
    manifest: GuardDbArmManifest,
    proof: CleanDatabaseProof,
): GuardDbArmTransferResult {
    if (proof.databaseBytes <= 0L || !GuardDbMaintenanceProtocol.validSha256(proof.sha256) ||
        proof.userVersion != manifest.a.expectedSchema || proof.appStateRows <= 0L ||
        !GuardDbMaintenanceProtocol.validSha256(proof.orderedAppStateSha256) ||
        !GuardDbMaintenanceProtocol.validSha256(proof.settingsSemanticSha256)
    ) {
        return GuardDbArmTransferResult.InvalidProof("clean database proof does not match exact A")
    }
    val plan = GuardDbMaintenanceProtocol.Plan(
        session = manifest.session,
        bootNonce = manifest.bootNonce,
        signerSha256 = manifest.signerSha256,
        baseline = GuardDbMaintenanceProtocol.Baseline(
            bytes = proof.databaseBytes,
            sha256 = proof.sha256,
            schema = proof.userVersion,
            appStateCount = proof.appStateRows,
            orderedAppStateSha256 = proof.orderedAppStateSha256,
            settingsSemanticSha256 = proof.settingsSemanticSha256,
        ),
        candidates = listOf(manifest.a, manifest.b),
        overallBudgetMs = manifest.overallBudgetMs,
        settingsAuthority = manifest.settingsAuthority,
    )
    val preparedStep = settleGuardDbStep(
        client, manifest, client.prepare(plan), previousGeneration = 0L,
        phases = setOf(GuardDbMaintenanceProtocol.Phase.STAGING), exactNextGeneration = true,
    )
    val prepared = preparedStep as? GuardDbStepSettlement.Settled ?: return preparedStep.asArmResult()
    var generation = prepared.generation
    for (candidate in listOf(manifest.a, manifest.b)) {
        val definedStep = settleGuardDbStep(
            client, manifest, client.define(manifest.session, generation, candidate), generation,
            setOf(GuardDbMaintenanceProtocol.Phase.STAGING), exactNextGeneration = true,
        )
        val defined = definedStep as? GuardDbStepSettlement.Settled ?: return definedStep.asArmResult()
        generation = defined.generation
    }
    for (candidate in listOf(manifest.a, manifest.b)) {
        val streamedStep = settleGuardDbStep(
            client, manifest, client.stream(manifest.session, generation, candidate), generation,
            setOf(GuardDbMaintenanceProtocol.Phase.STAGING), exactNextGeneration = true,
        )
        val streamed = streamedStep as? GuardDbStepSettlement.Settled ?: return streamedStep.asArmResult()
        generation = streamed.generation
    }
    val settingsStep = settleGuardDbStep(
        client, manifest,
        client.streamSettings(manifest.session, generation, manifest.settingsAuthority),
        generation,
        setOf(GuardDbMaintenanceProtocol.Phase.STAGING),
        exactNextGeneration = true,
    )
    val settings = settingsStep as? GuardDbStepSettlement.Settled ?: return settingsStep.asArmResult()
    generation = settings.generation
    val captured = client.action(
        manifest.session,
        generation,
        GuardDbMaintenanceProtocol.Action.CAPTURE_BASELINE,
    )
    val terminalStep = settleGuardDbStep(
        client = client,
        manifest = manifest,
        result = captured,
        previousGeneration = generation,
        phases = CAPTURE_SETTLED_PHASES,
        exactNextGeneration = false,
    )
    val terminal = terminalStep as? GuardDbStepSettlement.Settled ?: return terminalStep.asArmResult()
    return GuardDbArmTransferResult.Submitted(terminal.generation, terminal.phase)
}

private fun GuardDbStepSettlement.asArmResult(): GuardDbArmTransferResult = when (this) {
    is GuardDbStepSettlement.Settled -> error("settled Guard step has no failure result")
    is GuardDbStepSettlement.Failed -> GuardDbArmTransferResult.Failed(result)
    is GuardDbStepSettlement.Indeterminate -> GuardDbArmTransferResult.Indeterminate(probe)
}

private fun settleGuardDbStep(
    client: GuardDbMaintenanceClient,
    manifest: GuardDbArmManifest,
    result: GuardDbMaintenanceProtocol.Result,
    previousGeneration: Long,
    phases: Set<GuardDbMaintenanceProtocol.Phase>,
    exactNextGeneration: Boolean,
): GuardDbStepSettlement {
    fun accepted(generation: Long, phase: GuardDbMaintenanceProtocol.Phase): GuardDbStepSettlement.Settled? {
        val generationValid = if (exactNextGeneration) generation == previousGeneration + 1L
        else generation > previousGeneration
        return GuardDbStepSettlement.Settled(generation, phase).takeIf {
            generationValid && phase in phases
        }
    }
    if (result is GuardDbMaintenanceProtocol.Result.Accepted) {
        accepted(result.generation, result.phase)?.let { return it }
    } else if (result is GuardDbMaintenanceProtocol.Result.Rejected ||
        result == GuardDbMaintenanceProtocol.Result.Unreachable
    ) {
        return GuardDbStepSettlement.Failed(result)
    }
    // A syntactically bad/lost reply may follow a fully fsynced mutation. Status is the only receipt;
    // do not replay or cancel the command. An unrelated or unreachable record remains indeterminate.
    val probe = client.statusProbe()
    val status = (probe as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
    if (status != null && status.session == manifest.session && status.bootNonce == manifest.bootNonce) {
        accepted(status.generation, status.phase)?.let { return it }
    }
    return GuardDbStepSettlement.Indeterminate(probe)
}

private val CAPTURE_SETTLED_PHASES = setOf(
    GuardDbMaintenanceProtocol.Phase.PREPARED,
    GuardDbMaintenanceProtocol.Phase.SUBMITTED_B,
    GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
    GuardDbMaintenanceProtocol.Phase.ROLLBACK_REQUIRED,
    GuardDbMaintenanceProtocol.Phase.ROLLBACK_A_SUBMITTED,
    GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_PREPARED,
    GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_RESTORED,
    GuardDbMaintenanceProtocol.Phase.WAIT_A_HEALTH,
    GuardDbMaintenanceProtocol.Phase.A_HEALTHY,
)

/** Transfers recovery authority to an OS-owned same-boot alarm before this process may exit. */
internal class GuardDbSuccessorHandoff(
    private val publishAlarmRetry: () -> Unit,
    private val exitCurrentProcess: () -> Unit,
    private val scheduleAlarmPublicationRetry: (delayMs: Long, retry: () -> Unit) -> Unit,
    private val onPublicationFailure: (Throwable) -> Unit,
) {
    fun request() = publishAlarmAuthority()

    private fun publishAlarmAuthority() {
        val publicationFailure = runCatching { publishAlarmRetry() }.exceptionOrNull()
        if (publicationFailure != null) {
            onPublicationFailure(publicationFailure)
            scheduleAlarmPublicationRetry(RETRY_DELAY_MS, ::publishAlarmAuthority)
            return
        }
        exitCurrentProcess()
    }

    companion object {
        internal const val RETRY_DELAY_MS = 1_000L
    }
}

/** Bridges an approved HTTP ARM into the existing complete service-drain/checkpoint barrier. */
internal object GuardDbArmCoordinator {
    private const val TAG = "ha-paneld/guard-db-arm"
    private val alarmPublicationRetry = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ha-paneld-guard-db-alarm-retry").apply { isDaemon = false }
    }

    @Synchronized
    fun prepare(
        context: Context,
        manifest: GuardDbArmManifest,
        scheduleShutdown: (() -> Unit) -> Unit = { shutdown -> shutdown() },
    ): Boolean {
        val appContext = context.applicationContext
        val sentinelStore = guardDbSentinelStore(appContext)
        val sentinel = (sentinelStore.load() as? GuardDbSentinelLoad.Valid)?.sentinel ?: return false
        if (sentinel.session != manifest.session || sentinel.bootNonce != manifest.bootNonce ||
            sentinel.aSha256 != manifest.a.sha256 || sentinel.aVersionCode != manifest.a.versionCode ||
            sentinel.bSha256 != manifest.b.sha256 || sentinel.bVersionCode != manifest.b.versionCode
            || sentinel.settingsAuthorityVersion != manifest.settingsAuthority.version ||
            sentinel.settingsAuthorityBytes != manifest.settingsAuthority.bytes ||
            sentinel.settingsAuthoritySha256 != manifest.settingsAuthority.sha256
        ) return false
        val completion = object : UpgradeRequestCompletion {
            override fun ready(nonce: String, proof: CleanDatabaseProof) {
                val prepared = runCatching { GuardDbPreparedArm.create(manifest, proof) }.getOrNull()
                val store = guardDbPreparedArmStore(appContext)
                val durable = prepared != null && store.write(prepared) &&
                    store.load() == GuardDbPreparedArmLoad.Valid(prepared)
                val ready = durable && sentinelStore.promoteBaselineReady(manifest.session)
                if (ready) {
                    Log.i(TAG, "exact clean database proof retained for separate physical ARM approval")
                } else {
                    Log.e(TAG, "clean database proof could not be durably retained; helper remains untouched")
                }
                // proveCleanShutdown closed the cached SQLite owner. Never resume it in this process.
                // The successor exposes only the exact retained proof for a second, separately approved
                // custody commit. PREPARE/CAPTURE cannot run in this writer-owning process.
                requestFreshGuardDbProcess(appContext)
            }

            override fun failed(reason: String) {
                Log.e(TAG, "clean shutdown failed before helper custody: $reason")
                requestFreshGuardDbProcess(appContext)
            }
        }
        val shutdownNonce = manifest.session.take(32)
        if (!UpgradeShutdownCoordinator.armWithoutWatchdog(shutdownNonce, completion)) return false
        return runCatching {
            scheduleShutdown {
                val stopped = runCatching {
                    appContext.stopService(Intent(appContext, PaneldService::class.java))
                }.getOrDefault(false)
                if (!stopped) {
                    UpgradeShutdownCoordinator.cancelAndResume(appContext, shutdownNonce, "service_not_running")
                }
            }
            true
        }.getOrElse {
            UpgradeShutdownCoordinator.cancelAndResume(appContext, shutdownNonce, "shutdown_schedule_failed")
            false
        }
    }

    fun submitPrepared(
        client: GuardDbMaintenanceClient,
        manifest: GuardDbArmManifest,
        prepared: GuardDbPreparedArm,
    ): GuardDbArmTransferResult {
        if (!prepared.matches(manifest)) {
            return GuardDbArmTransferResult.InvalidProof("prepared ARM identity changed")
        }
        return executeGuardDbArmTransfer(client, manifest, prepared.proof())
    }

    private fun requestFreshGuardDbProcess(context: Context) {
        GuardDbSuccessorHandoff(
            publishAlarmRetry = { GuardDbSuccessorAlarm.schedule(context) },
            exitCurrentProcess = { Process.killProcess(Process.myPid()) },
            scheduleAlarmPublicationRetry = { delayMs, retry ->
                alarmPublicationRetry.schedule(retry, delayMs, TimeUnit.MILLISECONDS)
            },
            onPublicationFailure = { failure ->
                Log.e(TAG, "Guard DB successor alarm publication failed; retaining writer-free authority", failure)
            },
        ).request()
    }
}
