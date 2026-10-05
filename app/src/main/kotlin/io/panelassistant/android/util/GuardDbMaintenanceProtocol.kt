package io.panelassistant.android.util

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.platform.DaemonStreamResult
import java.io.File

/** Strict v1 wire contract for the helper-owned Guard DB transaction. */
internal object GuardDbMaintenanceProtocol {
    const val MAX_EVIDENCE_BYTES = 4096L
    const val MAX_SELF_BYTES = 16L * 1024L * 1024L
    const val STREAM_TIMEOUT_MS = 120_000L
    const val MUTATION_TIMEOUT_MS = 30_000L
    const val LONG_ACTION_TIMEOUT_MS = 180_000L
    const val MIN_OVERALL_BUDGET_MS = 600_000L
    const val MAX_OVERALL_BUDGET_MS = 1_800_000L
    const val RECOVERY_RESERVE_MS = 480_000L
    const val CAPS_REPLY =
        "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL"

    private val HEX_64 = Regex("[0-9a-f]{64}")
    private val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val ERROR_DETAIL = Regex("[a-z][a-z0-9_]{0,63}")
    private val STATUS_ERROR = Regex("[A-Z][A-Z0-9_]{0,63}")

    enum class Role { A, B }

    enum class Action {
        CAPTURE_BASELINE,
        WITHHOLD_PREMIGRATE,
        RESTORE_PREMIGRATE,
        INSTALL_B,
        INSTALL_A,
        ROLLBACK,
        FINALIZE,
    }

    enum class Phase {
        EMPTY,
        STAGING,
        PREPARED,
        SUBMITTED_A,
        SUBMITTED_B,
        WAIT_A_HEALTH,
        WAIT_B_HEALTH,
        B_HEALTHY,
        RECOVERY_WITHHELD,
        WAIT_A_REFUSAL,
        A_REFUSED,
        RECOVERY_RESTORED,
        ROLLBACK_REQUIRED,
        ROLLBACK_A_SUBMITTED,
        ROLLBACK_DB_PREPARED,
        ROLLBACK_DB_RESTORED,
        A_HEALTHY,
        FINALIZED,
        RETIRING,
        AMBIGUOUS,
    }

    enum class Probe { PRESENT, ABSENT }
    enum class RecoveryProof { NA, RESTORED, BASELINE }

    enum class Outcome {
        CANARY_PASSED,
        CANCELLED_NO_MUTATION,
        AMBIGUOUS,
        ROLLED_BACK_PM_REJECTED,
        ROLLED_BACK_HEALTH_FAILED,
        ROLLED_BACK_HEALTH_TIMEOUT,
        ROLLED_BACK_REFUSAL_TIMEOUT,
        ROLLED_BACK_OVERALL_TIMEOUT,
        ROLLED_BACK_OPERATOR,
    }

    data class Baseline(
        val bytes: Long,
        val sha256: String,
        val schema: Int,
        val appStateCount: Long,
        val orderedAppStateSha256: String,
        val settingsSemanticSha256: String,
    )

    data class Candidate(
        val role: Role,
        val file: File,
        val bytes: Long,
        val sha256: String,
        val versionCode: Long,
        val contractMinimum: Int,
        val contractMaximum: Int,
        val expectedSchema: Int,
        val settingsAuthorityVersion: Int,
        val settingsAuthorityBytes: Long,
        val settingsAuthoritySha256: String,
    )

    data class Plan(
        val session: String,
        val bootNonce: String,
        val signerSha256: String,
        val baseline: Baseline,
        val candidates: List<Candidate>,
        val overallBudgetMs: Long,
        val settingsAuthority: GuardDbSettingsAuthority,
    ) {
        fun candidate(role: Role): Candidate = requireNotNull(candidates.singleOrNull { it.role == role })
    }

    data class Status(
        val generation: Long,
        val phase: Phase,
        val session: String?,
        val bootNonce: String?,
        val role: Role?,
        val apkSha256: String?,
        val versionCode: Long?,
        val schema: Int?,
        val baselineAppStateCount: Long,
        val error: String?,
        val outcome: Outcome? = null,
        val overallDeadlineElapsedMs: Long = 1L,
        val forwardDeadlineElapsedMs: Long = 1L,
    ) {
        val ownsMaintenance: Boolean get() = phase != Phase.EMPTY
    }

    data class Capabilities(
        val supervised: Boolean,
        val autonomous: Boolean,
        val terminalRetire: Boolean = false,
    )

    data class SelfIdentity(
        val bytes: Long,
        val sha256: String,
        val buildId: String,
    )

    sealed interface Result {
        data class Accepted(val generation: Long, val phase: Phase) : Result
        data class Rejected(val code: String, val token: String) : Result
        data object Unreachable : Result
        data object Indeterminate : Result
        data object Malformed : Result
    }

    sealed interface AppRetireResult {
        data object Requested : AppRetireResult
        data class Rejected(val code: String, val token: String) : AppRetireResult
        data object NotSubmitted : AppRetireResult
        data object Indeterminate : AppRetireResult
    }

    sealed interface TerminalRetireResult {
        data class Accepted(val retirementGeneration: Long) : TerminalRetireResult
        data class Rejected(val code: String, val token: String) : TerminalRetireResult
        data object NotSubmitted : TerminalRetireResult
        data object Indeterminate : TerminalRetireResult
    }

    fun validSession(value: String): Boolean = HEX_64.matches(value)
    fun validSha256(value: String): Boolean = HEX_64.matches(value)
    fun terminalOutcome(value: Outcome): Boolean =
        value == Outcome.CANARY_PASSED || value.name.startsWith("ROLLED_BACK_")

    fun parseCapabilities(raw: String?): Capabilities? = when (raw) {
        CAPS_REPLY -> Capabilities(supervised = false, autonomous = false)
        "$CAPS_REPLY SUPERVISED" -> Capabilities(supervised = true, autonomous = false)
        "$CAPS_REPLY AUTONOMOUS" -> Capabilities(supervised = false, autonomous = true)
        "$CAPS_REPLY AUTONOMOUS SUPERVISED" -> Capabilities(supervised = true, autonomous = true)
        "$CAPS_REPLY TERMINAL_RETIRE" ->
            Capabilities(supervised = false, autonomous = false, terminalRetire = true)
        "$CAPS_REPLY SUPERVISED TERMINAL_RETIRE" ->
            Capabilities(supervised = true, autonomous = false, terminalRetire = true)
        "$CAPS_REPLY AUTONOMOUS TERMINAL_RETIRE" ->
            Capabilities(supervised = false, autonomous = true, terminalRetire = true)
        "$CAPS_REPLY AUTONOMOUS SUPERVISED TERMINAL_RETIRE" ->
            Capabilities(supervised = true, autonomous = true, terminalRetire = true)
        else -> null
    }

    fun parseSelfIdentity(raw: String?): SelfIdentity? {
        if (raw == null || raw.length !in 1..512 || raw.any { it.code !in 0x20..0x7e }) return null
        val fields = raw.split(' ')
        if (fields.size != 6 || fields.any(String::isEmpty) ||
            fields[0] != "OK" || fields[1] != "GUARDSELF" || fields[2] != "1"
        ) return null
        val bytes = fields[3].strictPositiveLong()?.takeIf { it <= MAX_SELF_BYTES } ?: return null
        if (!validSha256(fields[4]) || !validSha256(fields[5])) return null
        return SelfIdentity(bytes = bytes, sha256 = fields[4], buildId = fields[5])
    }

    fun prepare(plan: Plan): String {
        require(validSession(plan.session) && validSha256(plan.bootNonce) && validSha256(plan.signerSha256))
        require(plan.candidates.map(Candidate::role).toSet() == Role.values().toSet() && plan.candidates.size == 2)
        val baseline = plan.baseline
        require(baseline.bytes > 0L && validSha256(baseline.sha256))
        require(baseline.schema > 0 && baseline.appStateCount > 0L)
        require(validSha256(baseline.orderedAppStateSha256) && validSha256(baseline.settingsSemanticSha256))
        require(plan.overallBudgetMs in MIN_OVERALL_BUDGET_MS..MAX_OVERALL_BUDGET_MS)
        require(plan.settingsAuthority.version == GuardDbSettingsAuthority.VERSION)
        return "GUARDPREPARE ${plan.session} ${plan.bootNonce} ${plan.signerSha256} ${baseline.bytes} " +
            "${baseline.sha256} ${baseline.schema} ${baseline.appStateCount} ${baseline.orderedAppStateSha256} " +
            "${baseline.settingsSemanticSha256} ${plan.overallBudgetMs} ${plan.settingsAuthority.version} " +
            "${plan.settingsAuthority.bytes} ${plan.settingsAuthority.sha256}"
    }

    fun define(session: String, expectedGeneration: Long, candidate: Candidate): String {
        requireIdentity(session, expectedGeneration)
        requireCandidate(candidate)
        return "GUARDDEFINE $session $expectedGeneration ${candidate.role} ${candidate.bytes} ${candidate.sha256} " +
            "${candidate.versionCode} ${candidate.contractMinimum} ${candidate.contractMaximum} ${candidate.expectedSchema}"
    }

    fun stream(session: String, expectedGeneration: Long, candidate: Candidate): String {
        requireIdentity(session, expectedGeneration)
        requireCandidate(candidate)
        return "GUARDSTREAM $session $expectedGeneration ${candidate.role} ${candidate.bytes} ${candidate.sha256}"
    }

    fun streamSettings(
        session: String,
        expectedGeneration: Long,
        authority: GuardDbSettingsAuthority,
    ): String {
        requireIdentity(session, expectedGeneration)
        require(authority.version == GuardDbSettingsAuthority.VERSION &&
            authority.bytes in 1..GuardDbSettingsAuthority.MAX_BYTES && authority.file.length() == authority.bytes &&
            validSha256(authority.sha256))
        return "GUARDSTREAM $session $expectedGeneration SETTINGS ${authority.bytes} ${authority.sha256}"
    }

    fun action(session: String, expectedGeneration: Long, action: Action): String {
        requireIdentity(session, expectedGeneration)
        return "GUARDACTION $session $expectedGeneration $action"
    }

    fun health(
        status: Status,
        role: Role,
        apkSha256: String,
        versionCode: Long,
        schema: Int,
        healthy: Boolean,
        appStateCount: Long,
        orderedAppStateSha256: String,
        settingsSemanticSha256: String,
        probe: Probe,
        recoveryProof: RecoveryProof,
    ): String {
        val session = requireNotNull(status.session)
        val boot = requireNotNull(status.bootNonce)
        requireIdentity(session, status.generation)
        require(validSha256(boot) && validSha256(apkSha256))
        require(versionCode > 0L && schema > 0 && appStateCount > 0L)
        require(validSha256(orderedAppStateSha256) && validSha256(settingsSemanticSha256))
        return "GUARDHEALTH $session ${status.generation} $boot $role $apkSha256 $versionCode $schema " +
            "${if (healthy) "OK" else "FAIL"} $appStateCount $orderedAppStateSha256 $settingsSemanticSha256 " +
            "$probe $recoveryProof"
    }

    fun refusal(status: Status, aSha256: String, aVersionCode: Long): String {
        val session = requireNotNull(status.session)
        val boot = requireNotNull(status.bootNonce)
        requireIdentity(session, status.generation)
        require(validSha256(boot) && validSha256(aSha256) && aVersionCode > 0L)
        return "GUARDREFUSAL $session ${status.generation} $boot A $aSha256 $aVersionCode " +
            "PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE"
    }

    fun evidence(session: String): String {
        require(validSession(session))
        return "GUARDEVIDENCE $session"
    }

    fun cancel(session: String, expectedGeneration: Long): String {
        requireIdentity(session, expectedGeneration)
        return "GUARDCANCEL $session $expectedGeneration"
    }

    fun retireApp(nonce: String, stagedSha256: String, stagedBuildId: String): String {
        require(validSha256(nonce) && validSha256(stagedSha256) && validSha256(stagedBuildId))
        return "GUARDRETIRE APP $nonce $stagedSha256 $stagedBuildId"
    }

    fun retireTerminal(session: String, expectedGeneration: Long, evidenceSha256: String): String {
        requireIdentity(session, expectedGeneration)
        require(validSha256(evidenceSha256))
        return "GUARDRETIRE TERMINAL $session $expectedGeneration $evidenceSha256"
    }

    /** Any non-exact reply after TERMINAL submission is epistemically indeterminate: namespace
     * retirement may already have begun, so the caller must probe status once and never replay. */
    fun parseTerminalRetireReply(expectedGeneration: Long, raw: String?): TerminalRetireResult {
        if (expectedGeneration < 0L || expectedGeneration == Long.MAX_VALUE || raw == null ||
            raw.length !in 1..512 || raw.any { it.code !in 0x20..0x7e }
        ) return TerminalRetireResult.Indeterminate
        val fields = raw.split(' ')
        if (fields.any(String::isEmpty)) return TerminalRetireResult.Indeterminate
        if (fields.size == 3 && fields[0] == "ERR" && ERROR_CODE.matches(fields[1]) &&
            ERROR_DETAIL.matches(fields[2])
        ) {
            return if (fields[1] == "INDETERMINATE" && fields[2] == "retirement") {
                TerminalRetireResult.Indeterminate
            } else {
                TerminalRetireResult.Rejected(fields[1], fields[2])
            }
        }
        val retirementGeneration = (expectedGeneration + 1L).toString()
        return if (fields == listOf("OK", "GUARDRETIRE", retirementGeneration, "EMPTY")) {
            TerminalRetireResult.Accepted(expectedGeneration + 1L)
        } else {
            TerminalRetireResult.Indeterminate
        }
    }

    /** R1 REQUESTED is a durable handoff receipt, not proof that the new worker is listening yet. */
    fun parseAppRetireReply(raw: String?): AppRetireResult {
        if (raw == null || raw.length !in 1..512 || raw.any { it.code !in 0x20..0x7e }) {
            return AppRetireResult.Indeterminate
        }
        return when (raw) {
            "OK GUARDRETIRE 1 REQUESTED" -> AppRetireResult.Requested
            "ERR ARGS retire" -> AppRetireResult.Rejected("ARGS", "retire")
            "ERR ARMED replacement" -> AppRetireResult.Rejected("ARMED", "replacement")
            "ERR HOLD replacement" -> AppRetireResult.Rejected("HOLD", "replacement")
            "ERR INDETERMINATE replacement" -> AppRetireResult.Indeterminate
            else -> AppRetireResult.Indeterminate
        }
    }

    fun parseMutationReply(expectedVerb: String, raw: String?): Result {
        if (raw == null) return Result.Unreachable
        if (raw.length !in 1..512 || raw.any { it.code !in 0x20..0x7e }) return Result.Malformed
        val fields = raw.split(' ')
        if (fields.any(String::isEmpty)) return Result.Malformed
        if (fields.size == 3 && fields[0] == "ERR" && ERROR_CODE.matches(fields[1]) && ERROR_DETAIL.matches(fields[2])) {
            if (fields[1] == "INDETERMINATE" && fields[2] in PUBLICATION_INDETERMINATE_DETAILS) {
                return Result.Indeterminate
            }
            return Result.Rejected(fields[1], fields[2])
        }
        if (fields.size != 4 || fields[0] != "OK" || fields[1] != expectedVerb) return Result.Malformed
        val generation = fields[2].strictNonNegativeLong() ?: return Result.Malformed
        val phase = enumValueOrNull<Phase>(fields[3]) ?: return Result.Malformed
        return Result.Accepted(generation, phase)
    }

    fun parseStatus(raw: String?): Status? {
        if (raw == null || raw.length !in 1..512 || raw.any { it.code !in 0x20..0x7e }) return null
        val fields = raw.split(' ')
        if (fields.size != 15 || fields.any(String::isEmpty) || fields[0] != "OK" || fields[1] != "GUARDSTATUS") {
            return null
        }
        val generation = fields[2].strictNonNegativeLong() ?: return null
        val phase = enumValueOrNull<Phase>(fields[3]) ?: return null
        val session = if (fields[4] == "NONE") null else fields[4].takeIf(::validSession) ?: return null
        val boot = if (fields[5] == "NONE") null else fields[5].takeIf(::validSha256) ?: return null
        val role = if (fields[6] == "NONE") null else enumValueOrNull<Role>(fields[6]) ?: return null
        val apk = if (fields[7] == "NONE") null else fields[7].takeIf(::validSha256) ?: return null
        val versionToken = fields[8].strictNonNegativeLong() ?: return null
        val version = versionToken.takeIf { it > 0L }
        val schemaToken = fields[9].strictNonNegativeLong() ?: return null
        val schema = when {
            schemaToken == 0L -> null
            schemaToken <= Int.MAX_VALUE.toLong() -> schemaToken.toInt()
            else -> return null
        }
        val baselineCount = fields[10].strictNonNegativeLong() ?: return null
        val error = fields[11].takeUnless { it == "NONE" }
            ?.takeIf(STATUS_ERROR::matches) ?: if (fields[11] == "NONE") null else return null
        val outcome = fields[12].takeUnless { it == "NONE" }
            ?.let { enumValueOrNull<Outcome>(it) } ?: if (fields[12] == "NONE") null else return null
        val overallDeadline = fields[13].strictNonNegativeLong() ?: return null
        val forwardDeadline = fields[14].strictNonNegativeLong() ?: return null
        val empty = phase == Phase.EMPTY
        if (empty && generation != 0L) return null
        val identityAbsent = session == null && boot == null
        if (empty != identityAbsent) return null
        if (!empty && (session == null || boot == null || baselineCount <= 0L)) return null
        if (empty && (baselineCount != 0L || error != null || outcome != null ||
                overallDeadline != 0L || forwardDeadline != 0L)
        ) return null
        if (!empty && (overallDeadline <= 0L || forwardDeadline <= 0L ||
                forwardDeadline != overallDeadline - RECOVERY_RESERVE_MS)
        ) return null
        if ((role == null) != (apk == null) || (role == null) != (version == null) || (role == null) != (schema == null)) return null
        val expectedRole = when (phase) {
            Phase.WAIT_B_HEALTH,
            Phase.B_HEALTHY,
            Phase.WAIT_A_REFUSAL,
            Phase.A_REFUSED,
            Phase.RECOVERY_WITHHELD,
            Phase.RECOVERY_RESTORED -> Role.B
            Phase.WAIT_A_HEALTH,
            Phase.ROLLBACK_DB_PREPARED,
            Phase.ROLLBACK_DB_RESTORED,
            Phase.A_HEALTHY,
            Phase.FINALIZED,
            Phase.RETIRING -> Role.A
            else -> null
        }
        if (role != expectedRole) return null
        if (phase == Phase.AMBIGUOUS && error == null) return null
        if (phase == Phase.AMBIGUOUS && outcome != Outcome.AMBIGUOUS) return null
        if (phase in setOf(Phase.FINALIZED, Phase.RETIRING) && outcome !in terminalOutcomes()) return null
        if (phase !in setOf(Phase.FINALIZED, Phase.RETIRING, Phase.AMBIGUOUS) && outcome != null) return null
        return Status(
            generation, phase, session, boot, role, apk, version, schema, baselineCount, error,
            outcome, overallDeadline, forwardDeadline,
        )
    }

    fun validEvidence(bytes: ByteArray?): Boolean = evidenceStatus(bytes) != null

    fun evidenceStatus(bytes: ByteArray?): Status? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_EVIDENCE_BYTES) return null
        if (bytes.any { byte -> byte.toInt() !in 0x0a..0x7e || byte.toInt() in 0x0b..0x1f }) return null
        val lines = bytes.toString(Charsets.US_ASCII).split('\n').dropLastWhile(String::isEmpty)
        if (lines.size != 13 || lines[0] != "OK GUARDEVIDENCE 1" || lines[12] != "END") return null
        val session = exactEvidenceFields(lines[1], "SESSION", 1) ?: return null
        val boot = exactEvidenceFields(lines[2], "BOOT", 1) ?: return null
        val packageName = exactEvidenceFields(lines[3], "PACKAGE", 1) ?: return null
        val signer = exactEvidenceFields(lines[4], "SIGNER", 1) ?: return null
        val state = exactEvidenceFields(lines[5], "STATE", 10) ?: return null
        val baseline = exactEvidenceFields(lines[6], "BASELINE", 6) ?: return null
        val settings = exactEvidenceFields(lines[7], "SETTINGS", 3) ?: return null
        val a = exactEvidenceFields(lines[8], "A", 8) ?: return null
        val b = exactEvidenceFields(lines[9], "B", 8) ?: return null
        val premigrate = exactEvidenceFields(lines[10], "PREMIGRATE", 2) ?: return null
        val bPrimary = exactEvidenceFields(lines[11], "B_PRIMARY", 2) ?: return null
        if (!validSession(session[0]) || !validSha256(boot[0]) ||
            !AppIdentity.isPanelApp(packageName[0]) || !validSha256(signer[0]) ||
            state.isEmpty()
        ) return null
        if (baseline[0].strictPositiveLong() == null || !validSha256(baseline[1]) ||
            baseline[2].strictPositiveInt() == null || baseline[3].strictPositiveLong() == null ||
            !validSha256(baseline[4]) || !validSha256(baseline[5])
        ) return null
        if (settings[0].strictPositiveInt() != GuardDbSettingsAuthority.VERSION ||
            settings[1].strictPositiveLong()?.takeIf { it <= GuardDbSettingsAuthority.MAX_BYTES } == null ||
            !validSha256(settings[2])
        ) return null
        if (!validEvidenceCandidate(a, Role.A) || !validEvidenceCandidate(b, Role.B) ||
            !validEvidenceArtifact(premigrate) || !validEvidenceArtifact(bPrimary)
        ) return null
        return evidenceState(session[0], boot[0], baseline, state)
    }

    fun streamResult(expectedVerb: String, result: DaemonStreamResult): Result = when (result) {
        is DaemonStreamResult.Reply -> parseMutationReply(expectedVerb, result.value)
        DaemonStreamResult.NotSubmitted -> Result.Unreachable
        DaemonStreamResult.Indeterminate -> Result.Indeterminate
        DaemonStreamResult.Unsupported -> Result.Malformed
    }

    private fun requireIdentity(session: String, expectedGeneration: Long) {
        require(validSession(session) && expectedGeneration >= 0L)
    }

    private fun requireCandidate(candidate: Candidate) {
        require(candidate.file.isFile && candidate.bytes > 0L && candidate.file.length() == candidate.bytes)
        require(validSha256(candidate.sha256) && candidate.versionCode > 0L)
        require(candidate.contractMinimum > 0 && candidate.contractMaximum >= candidate.contractMinimum)
        require(candidate.expectedSchema in candidate.contractMinimum..candidate.contractMaximum)
        require(candidate.settingsAuthorityVersion == GuardDbSettingsAuthority.VERSION)
        require(candidate.settingsAuthorityBytes in 1..GuardDbSettingsAuthority.MAX_BYTES)
        require(validSha256(candidate.settingsAuthoritySha256))
    }

    private fun String.strictNonNegativeLong(): Long? {
        if (isEmpty() || any { it !in '0'..'9' } || (length > 1 && first() == '0')) return null
        return toLongOrNull()
    }

    private fun String.strictPositiveLong(): Long? = strictNonNegativeLong()?.takeIf { it > 0L }

    private fun String.strictPositiveInt(): Int? =
        strictPositiveLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun exactEvidenceFields(line: String, key: String, count: Int): List<String>? {
        val fields = line.split(' ')
        if (fields.size != count + 1 || fields.any(String::isEmpty) || fields[0] != key) return null
        return fields.drop(1)
    }

    private fun validEvidenceCandidate(fields: List<String>, role: Role): Boolean {
        val defined = fields[0]
        val staged = fields[1]
        if (defined !in setOf("0", "1") || staged !in setOf("0", "1") ||
            (staged == "1" && defined != "1")
        ) return false
        val bytes = fields[2].strictNonNegativeLong() ?: return false
        val sha = fields[3]
        val version = fields[4].strictNonNegativeLong() ?: return false
        val minimum = fields[5].strictNonNegativeLong() ?: return false
        val maximum = fields[6].strictNonNegativeLong() ?: return false
        val schema = fields[7].strictNonNegativeLong() ?: return false
        if (defined == "0") {
            return staged == "0" && bytes == 0L && sha == "NONE" && version == 0L &&
                minimum == 0L && maximum == 0L && schema == 0L
        }
        if (bytes <= 0L || !validSha256(sha) || version <= 0L ||
            minimum <= 0L || maximum < minimum || schema !in minimum..maximum
        ) return false
        return role == Role.A || role == Role.B
    }

    private fun evidenceState(
        session: String,
        boot: String,
        baseline: List<String>,
        fields: List<String>,
    ): Status? {
        val status = parseStatus(
            "OK GUARDSTATUS ${fields[0]} ${fields[1]} $session $boot ${fields[2]} ${fields[3]} " +
                "${fields[4]} ${fields[5]} ${baseline[3]} ${fields[6]} ${fields[7]} ${fields[8]} ${fields[9]}",
        ) ?: return null
        return status.takeIf { it.phase != Phase.EMPTY }
    }

    private fun validEvidenceArtifact(fields: List<String>): Boolean {
        val bytes = fields[0].strictNonNegativeLong() ?: return false
        return if (bytes == 0L) fields[1] == "NONE" else validSha256(fields[1])
    }

    private fun terminalOutcomes(): Set<Outcome> = Outcome.values().filterTo(linkedSetOf(), ::terminalOutcome)

    private val PUBLICATION_INDETERMINATE_DETAILS = setOf(
        "draft", "artifact", "journal", "capture_intent", "capture", "premigrate",
        "restore", "terminal", "retirement",
    )

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }
}
