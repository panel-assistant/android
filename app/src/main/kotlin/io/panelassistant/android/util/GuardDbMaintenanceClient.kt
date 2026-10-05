package io.panelassistant.android.util

import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import java.io.File

internal interface GuardDbMaintenanceTransport {
    fun send(command: String): String?
    fun sendLong(command: String, timeoutMs: Long): DaemonLongResult
    fun sendFile(command: String, file: File, timeoutMs: Long): DaemonStreamResult
    fun sendBytesBounded(command: String, maxBytes: Long): ByteArray?
}

internal class GuardDbMaintenanceClient(
    private val transport: GuardDbMaintenanceTransport,
) {
    sealed interface StatusProbe {
        data class Valid(val status: GuardDbMaintenanceProtocol.Status) : StatusProbe
        data object Unsupported : StatusProbe
        data object Unreachable : StatusProbe
        data object Malformed : StatusProbe
    }

    fun capabilities(): GuardDbMaintenanceProtocol.Capabilities? =
        GuardDbMaintenanceProtocol.parseCapabilities(transport.send("GUARDCAPS"))

    fun selfIdentity(): GuardDbMaintenanceProtocol.SelfIdentity? =
        GuardDbMaintenanceProtocol.parseSelfIdentity(transport.send("GUARDSELF"))

    /** ARM requires the same-session crash supervisor; a valid one-shot v1 helper stays observable
     *  for status/retirement but is not sufficient custody authority. */
    fun supported(): Boolean = capabilities()?.let {
        it.supervised && it.autonomous && it.terminalRetire
    } == true

    fun statusProbe(): StatusProbe {
        val raw = transport.send("GUARDSTATUS") ?: return StatusProbe.Unreachable
        if (raw == "ERR") return StatusProbe.Unsupported
        return GuardDbMaintenanceProtocol.parseStatus(raw)?.let(StatusProbe::Valid) ?: StatusProbe.Malformed
    }

    fun status(): GuardDbMaintenanceProtocol.Status? =
        (statusProbe() as? StatusProbe.Valid)?.status

    fun prepare(plan: GuardDbMaintenanceProtocol.Plan): GuardDbMaintenanceProtocol.Result =
        mutation("GUARDPREPARE", GuardDbMaintenanceProtocol.prepare(plan))

    fun define(
        session: String,
        expectedGeneration: Long,
        candidate: GuardDbMaintenanceProtocol.Candidate,
    ): GuardDbMaintenanceProtocol.Result = mutation(
        "GUARDDEFINE",
        GuardDbMaintenanceProtocol.define(session, expectedGeneration, candidate),
    )

    fun stream(
        session: String,
        expectedGeneration: Long,
        candidate: GuardDbMaintenanceProtocol.Candidate,
    ): GuardDbMaintenanceProtocol.Result = GuardDbMaintenanceProtocol.streamResult(
        "GUARDSTREAM",
        transport.sendFile(
            GuardDbMaintenanceProtocol.stream(session, expectedGeneration, candidate),
            candidate.file,
            GuardDbMaintenanceProtocol.STREAM_TIMEOUT_MS,
        ),
    )

    fun streamSettings(
        session: String,
        expectedGeneration: Long,
        authority: GuardDbSettingsAuthority,
    ): GuardDbMaintenanceProtocol.Result = GuardDbMaintenanceProtocol.streamResult(
        "GUARDSTREAM",
        transport.sendFile(
            GuardDbMaintenanceProtocol.streamSettings(session, expectedGeneration, authority),
            authority.file,
            GuardDbMaintenanceProtocol.STREAM_TIMEOUT_MS,
        ),
    )

    fun action(
        session: String,
        expectedGeneration: Long,
        action: GuardDbMaintenanceProtocol.Action,
    ): GuardDbMaintenanceProtocol.Result = longMutation(
        "GUARDACTION",
        GuardDbMaintenanceProtocol.action(session, expectedGeneration, action),
    )

    fun health(
        status: GuardDbMaintenanceProtocol.Status,
        role: GuardDbMaintenanceProtocol.Role,
        apkSha256: String,
        versionCode: Long,
        schema: Int,
        healthy: Boolean,
        appStateCount: Long,
        orderedAppStateSha256: String,
        settingsSemanticSha256: String,
        probe: GuardDbMaintenanceProtocol.Probe,
        recoveryProof: GuardDbMaintenanceProtocol.RecoveryProof,
    ): GuardDbMaintenanceProtocol.Result = mutation(
        "GUARDHEALTH",
        GuardDbMaintenanceProtocol.health(
            status, role, apkSha256, versionCode, schema, healthy, appStateCount,
            orderedAppStateSha256, settingsSemanticSha256, probe,
            recoveryProof,
        ),
    )

    fun refusal(
        status: GuardDbMaintenanceProtocol.Status,
        aSha256: String,
        aVersionCode: Long,
    ): GuardDbMaintenanceProtocol.Result = mutation(
        "GUARDREFUSAL",
        GuardDbMaintenanceProtocol.refusal(status, aSha256, aVersionCode),
    )

    fun cancel(session: String, expectedGeneration: Long): GuardDbMaintenanceProtocol.Result = mutation(
        "GUARDCANCEL",
        GuardDbMaintenanceProtocol.cancel(session, expectedGeneration),
    )

    fun retireApp(
        nonce: String,
        stagedSha256: String,
        stagedBuildId: String,
    ): GuardDbMaintenanceProtocol.AppRetireResult = when (val result = transport.sendLong(
        GuardDbMaintenanceProtocol.retireApp(nonce, stagedSha256, stagedBuildId),
        GuardDbMaintenanceProtocol.MUTATION_TIMEOUT_MS,
    )) {
        is DaemonLongResult.Reply -> GuardDbMaintenanceProtocol.parseAppRetireReply(result.value)
        DaemonLongResult.NotSubmitted -> GuardDbMaintenanceProtocol.AppRetireResult.NotSubmitted
        DaemonLongResult.Indeterminate -> GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate
    }

    fun retireTerminal(
        session: String,
        expectedGeneration: Long,
        evidenceSha256: String,
    ): GuardDbMaintenanceProtocol.TerminalRetireResult = when (val result = transport.sendLong(
        GuardDbMaintenanceProtocol.retireTerminal(session, expectedGeneration, evidenceSha256),
        GuardDbMaintenanceProtocol.MUTATION_TIMEOUT_MS,
    )) {
        is DaemonLongResult.Reply ->
            GuardDbMaintenanceProtocol.parseTerminalRetireReply(expectedGeneration, result.value)
        DaemonLongResult.NotSubmitted -> GuardDbMaintenanceProtocol.TerminalRetireResult.NotSubmitted
        DaemonLongResult.Indeterminate -> GuardDbMaintenanceProtocol.TerminalRetireResult.Indeterminate
    }

    fun evidence(session: String): ByteArray? = transport.sendBytesBounded(
        GuardDbMaintenanceProtocol.evidence(session),
        GuardDbMaintenanceProtocol.MAX_EVIDENCE_BYTES,
    )?.takeIf(GuardDbMaintenanceProtocol::validEvidence)

    /** Every mutation uses the tri-state transport. Once writing may have begun, a missing reply is
     *  indeterminate and callers must reconcile GUARDSTATUS rather than replaying the mutation. */
    private fun mutation(verb: String, command: String): GuardDbMaintenanceProtocol.Result =
        timedMutation(verb, command, GuardDbMaintenanceProtocol.MUTATION_TIMEOUT_MS)

    private fun longMutation(verb: String, command: String): GuardDbMaintenanceProtocol.Result =
        timedMutation(verb, command, GuardDbMaintenanceProtocol.LONG_ACTION_TIMEOUT_MS)

    private fun timedMutation(verb: String, command: String, timeoutMs: Long): GuardDbMaintenanceProtocol.Result =
        when (val result = transport.sendLong(command, timeoutMs)) {
            is DaemonLongResult.Reply -> GuardDbMaintenanceProtocol.parseMutationReply(verb, result.value)
            DaemonLongResult.NotSubmitted -> GuardDbMaintenanceProtocol.Result.Unreachable
            DaemonLongResult.Indeterminate -> GuardDbMaintenanceProtocol.Result.Indeterminate
        }
}

internal object HelperGuardDbMaintenanceTransport : GuardDbMaintenanceTransport {
    override fun send(command: String): String? = HelperClient.send(command)
    override fun sendLong(command: String, timeoutMs: Long): DaemonLongResult = HelperClient.sendLong(command, timeoutMs)
    override fun sendFile(command: String, file: File, timeoutMs: Long): DaemonStreamResult =
        HelperClient.sendFile(command, file, timeoutMs)
    override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? =
        HelperClient.sendBytesBounded(command, maxBytes)
}

internal object GuardDbMaintenance {
    val client = GuardDbMaintenanceClient(HelperGuardDbMaintenanceTransport)
}
