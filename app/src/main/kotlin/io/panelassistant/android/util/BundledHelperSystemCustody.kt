package io.panelassistant.android.util

import java.security.MessageDigest

/*
 * Layout F custody migration: a Guard-capable helper served from /system/bin under a /system
 * rc cannot prove its own identity (GUARDSELF answers `HOLD self`), so no Guard replacement can start. The
 * takeover shell's migration mode archives both /system files, swaps the bundled build in, then removes them,
 * leaving the plain app-lease layout the fresh install already accepts. The record spans only that
 * transaction: the same invocation retires it once init's view proves both files gone, so the reboot after
 * it is an ordinary fresh install and an app update before the reboot is an ordinary Guard replacement.
 */

internal const val SYSTEM_CUSTODY_PHASE_ARCHIVED = "ARCHIVED"
internal const val SYSTEM_CUSTODY_PHASE_SWAPPED = "SWAPPED"

/**
 * `OK CUSTODYMIGRATION 1 <phase> <old_sha> <old_bytes> <rc_sha> <rc_bytes> <stale_sha|NONE> <stale_bytes>
 * <incumbent_build> <staged_build> <candidate_sha> <candidate_bytes>`, root 0600 at
 * `/data/local/.hapaneld-helper.custody-migration`. It exists from the first mutation until both /system
 * files are proved gone; any start that finds it drives the same shell forward from wherever it was cut.
 */
internal data class SystemCustodyMigrationRecord(
    val phase: String,
    val oldSha256: String,
    val oldBytes: Long,
    val registrationSha256: String,
    val registrationBytes: Long,
    val staleSha256: String?,
    val staleBytes: Long,
    val incumbentBuildId: String,
    val stagedBuildId: String,
    val candidateSha256: String,
    val candidateBytes: Long,
    val recordSha256: String,
    val recordBytes: Long,
) {
    val candidateIdentity: GuardDbMaintenanceProtocol.SelfIdentity
        get() = GuardDbMaintenanceProtocol.SelfIdentity(candidateBytes, candidateSha256, stagedBuildId)
}

private const val MAX_CUSTODY_BYTES = 16L * 1024 * 1024

internal fun parseSystemCustodyMigrationRecord(reply: String): SystemCustodyMigrationRecord? {
    if ('\r' in reply || reply.toByteArray(Charsets.UTF_8).size !in 1..1024) return null
    val line = if (reply.endsWith('\n')) reply.dropLast(1) else reply
    if ('\n' in line) return null
    val fields = line.split(' ')
    if (fields.size != 14 || fields[0] != "OK" || fields[1] != "CUSTODYMIGRATION" || fields[2] != "1") return null
    val bytes = { field: String -> field.toLongOrNull()?.takeIf { it in 1..MAX_CUSTODY_BYTES } }
    val phase = fields[3].takeIf {
        it in setOf(SYSTEM_CUSTODY_PHASE_ARCHIVED, SYSTEM_CUSTODY_PHASE_SWAPPED)
    } ?: return null
    val staleSha = fields[8].takeIf { it != "NONE" }
    val staleBytes = if (staleSha == null) {
        fields[9].takeIf { it == "0" }?.toLong()
    } else {
        bytes(fields[9]).takeIf { GuardDbMaintenanceProtocol.validSha256(staleSha) }
    } ?: return null
    if (listOf(fields[4], fields[6], fields[10], fields[11], fields[12]).any { !GuardDbMaintenanceProtocol.validSha256(it) }) {
        return null
    }
    val recordBytes = reply.toByteArray(Charsets.UTF_8)
    return SystemCustodyMigrationRecord(
        phase = phase,
        oldSha256 = fields[4],
        oldBytes = bytes(fields[5]) ?: return null,
        registrationSha256 = fields[6],
        registrationBytes = bytes(fields[7]) ?: return null,
        staleSha256 = staleSha,
        staleBytes = staleBytes,
        incumbentBuildId = fields[10],
        stagedBuildId = fields[11],
        candidateSha256 = fields[12],
        candidateBytes = bytes(fields[13]) ?: return null,
        recordSha256 = MessageDigest.getInstance("SHA-256").digest(recordBytes).joinToString("") { "%02x".format(it) },
        recordBytes = recordBytes.size.toLong(),
    )
}

private fun requireFilesystemRoot(filesystemRoot: String) = require(filesystemRoot.isEmpty() ||
    (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
        SAFE_ABSOLUTE_PATH.matches(filesystemRoot) && filesystemRoot.split('/').none { it == ".." }))

/**
 * Reads the migration record for a start. A temporary with no record is a cut before the first record write,
 * when nothing beyond staging had changed: it is discarded under the transaction lock (a live transaction
 * holds that lock and keeps its own temporary), so the layout probe and staging see the panel as it was.
 */
internal fun systemCustodyMigrationRecordReadCommand(filesystemRoot: String = ""): String {
    requireFilesystemRoot(filesystemRoot)
    val d = '$'
    return """
    record=$filesystemRoot/data/local/.hapaneld-helper.custody-migration
    record_tmp=${d}record.tmp
    lock=$filesystemRoot/dev/.hapaneld-helper-transaction.lock
    file_meta() { stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null; }
    if [ ! -e "${d}record" ] && [ ! -L "${d}record" ] && { [ -e "${d}record_tmp" ] || [ -L "${d}record_tmp" ]; }; then
      locked=0
      if mkdir "${d}lock" 2>/dev/null; then locked=1
      else
        holder=${d}(cat "${d}lock/pid" 2>/dev/null || true)
        case "${d}holder" in ''|*[!0-9]*) ;; *)
          if [ ! -d "/proc/${d}holder" ] && rm -rf "${d}lock" 2>/dev/null && mkdir "${d}lock" 2>/dev/null; then locked=1; fi ;;
        esac
      fi
      if [ "${d}locked" = 1 ]; then
        trap 'rm -rf "${d}lock"' 0
        echo ${d}${d} > "${d}lock/pid"
        if [ ! -e "${d}record" ] && [ ! -L "${d}record" ] && [ -f "${d}record_tmp" ] && [ ! -L "${d}record_tmp" ]; then
          tmp_meta=${d}(file_meta "${d}record_tmp") &&
          case "${d}tmp_meta" in 0:0:600:1:*) [ "${d}{tmp_meta##*:}" -le 1024 ] && true ;; esac
        fi
        rm -rf "${d}lock"; trap - 0
      fi
    fi
    [ -f "${d}record" ] && [ ! -L "${d}record" ] &&
    meta=${d}(file_meta "${d}record") &&
    case "${d}meta" in 0:0:600:1:*) ;; *) false ;; esac &&
    bytes=${d}{meta##*:} && [ "${d}bytes" -ge 1 ] && [ "${d}bytes" -le 1024 ] &&
    dd if="${d}record" bs=1025 count=1 2>/dev/null
""".trimIndent()
}

/**
 * A start's answer to an unfinished migration: none without a record; otherwise the record's own build, with
 * nothing but its candidate (or nothing) serving, drives the takeover shell forward from the cut.
 */
internal fun systemCustodyStartOutcome(
    recordReply: String?,
    currentBuildId: String,
    statusProbe: () -> GuardDbMaintenanceClient.StatusProbe,
    selfIdentity: () -> GuardDbMaintenanceProtocol.SelfIdentity?,
    drive: (SystemCustodyMigrationRecord) -> Boolean,
    refused: (BundledHelperInstaller.Result, String) -> BundledHelperInstaller.Result,
): BundledHelperInstaller.Result? {
    val reply = recordReply ?: return null
    val record = parseSystemCustodyMigrationRecord(reply)
        ?: return refused(BundledHelperInstaller.Result.FAILED, "custody_record_malformed")
    if (record.stagedBuildId != currentBuildId) {
        return refused(BundledHelperInstaller.Result.REPROVISION_REQUIRED, "custody_record_other_build")
    }
    val serving = when (val status = statusProbe()) {
        is GuardDbMaintenanceClient.StatusProbe.Valid -> {
            if (status.status.ownsMaintenance) {
                return refused(BundledHelperInstaller.Result.BLOCKED_ACTIVE, "custody_guard_maintenance")
            }
            selfIdentity()
        }
        GuardDbMaintenanceClient.StatusProbe.Unreachable -> null
        else -> return refused(BundledHelperInstaller.Result.FAILED, "custody_status_unusable")
    }
    if (serving != null && serving != record.candidateIdentity) {
        return refused(BundledHelperInstaller.Result.FAILED, "custody_foreign_helper")
    }
    return if (drive(record)) BundledHelperInstaller.Result.INSTALLED else BundledHelperInstaller.Result.FAILED
}

/** Layout F is the only reason a Guard-capable incumbent that cannot name itself is not reprovision-gated. */
internal fun bundledHelperCustodyMigrationMode(
    mode: BundledHelperReplacementMode,
    incumbentIdentity: GuardDbMaintenanceProtocol.SelfIdentity?,
    layout: () -> SystemCustodyLayout,
): BundledHelperReplacementMode =
    if (mode == BundledHelperReplacementMode.GUARDED_RETIRE && incumbentIdentity == null &&
        layout() is SystemCustodyLayout.LayoutF
    ) BundledHelperReplacementMode.SYSTEM_CUSTODY_MIGRATION else mode

/**
 * The root work at the head of a start. An unfinished migration record outranks whatever answers the socket:
 * a cut after the candidate launched leaves the bundled build serving with the /system files and the record
 * still there, so an already-current start reads the record before the released takeover check, and only a
 * start without root (no record can be read) keeps its admission unread.
 */
internal fun bundledHelperStartGate(
    admission: BundledHelperInstaller.Result?,
    currentLegacyCustody: () -> BundledHelperInstaller.Result?,
    systemCustody: () -> BundledHelperInstaller.Result?,
): BundledHelperInstaller.Result? = when (admission) {
    null -> systemCustody()
    BundledHelperInstaller.Result.ALREADY_CURRENT -> systemCustody() ?: currentLegacyCustody() ?: admission
    else -> admission
}

/**
 * Defines `system_mount_find` (init's /system mount: `system_mount`, `system_type`, `system_options`) and
 * `system_upper_volatile`, true when /system is an overlay whose upper layer sits on tmpfs, where a removal
 * would come back after a reboot.
 */
internal fun systemMountShell(): String {
    val d = '$'
    return """
        system_mount_find() {
          system_mount=; system_type=; system_options=; system_rest=
          while IFS= read -r mount_line; do
            set -- ${d}mount_line
            case "${d}5" in
              /system) system_mount=/system; system_rest=${d}{mount_line#* - } ;;
              /) if [ "${d}system_mount" != /system ]; then system_mount=/; system_rest=${d}{mount_line#* - }; fi ;;
            esac
          done < "${d}root/proc/1/mountinfo"
          [ -n "${d}system_mount" ] || return 1
          set -- ${d}system_rest
          system_type=${d}1; system_options=${d}3
        }
        system_upper_volatile() {
          [ "${d}system_type" = overlay ] || return 1
          case ",${d}system_options," in *,upperdir=*) ;; *) return 1 ;; esac
          upper=${d}{system_options#*upperdir=}; upper=${d}{upper%%,*}
          best=; best_type=
          while IFS= read -r mount_line; do
            set -- ${d}mount_line
            point=${d}5
            if [ "${d}point" = / ] || [ "${d}upper" = "${d}point" ] || [ "${d}{upper#"${d}point"/}" != "${d}upper" ]; then
              if [ ${d}{#point} -ge ${d}{#best} ]; then
                best=${d}point; set -- ${d}{mount_line#* - }; best_type=${d}1
              fi
            fi
          done < "${d}root/proc/1/mountinfo"
          [ "${d}best_type" = tmpfs ]
        }
    """.trimIndent().replace("\n", "\n        ")
}

/**
 * The last helper install outcome for the diag dump, as one line: result, reason, wall-clock time and elapsed
 * time, and whether a run is in progress now, so a start that returned silently or never returned shows.
 */
internal class BundledHelperInstallDiagnostics(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Outcome(val result: BundledHelperInstaller.Result, val reason: String, val atMs: Long, val elapsedMs: Long)

    @Volatile private var startedAt: Long? = null
    @Volatile private var last: Outcome? = null

    fun began() {
        startedAt = monotonic()
    }

    fun finished(result: BundledHelperInstaller.Result, reason: String?) {
        val elapsed = startedAt?.let { (monotonic() - it).coerceAtLeast(0L) } ?: 0L
        last = Outcome(result, reason?.takeIf { it.isNotBlank() }?.replace(Regex("\\s+"), "_") ?: "unrecorded", wallClock(), elapsed)
        startedAt = null
    }

    @Volatile private var probeInventory: List<String> = emptyList()
    @Volatile private var shellTail: String? = null

    /** The last layout F probe's inventory: mount, SELinux, namespaces, the stale copy and the Guard DB. */
    fun recordProbe(inventory: List<String>) {
        probeInventory = inventory.take(40).map { it.take(240) }
    }

    /** The end of the last migration shell's traced output, on one line. */
    fun recordShell(output: String) {
        shellTail = output.lines().filter { it.isNotBlank() }.takeLast(40).joinToString(" | ").takeLast(2000)
    }

    fun detailLines(): List<String> =
        probeInventory.map { "[helper install probe] $it" } + listOfNotNull(shellTail?.let { "[helper install shell] $it" })

    fun summary(): String {
        val running = if (startedAt != null) "running=yes" else "running=no"
        val outcome = last ?: return "never_run $running"
        val at = java.time.Instant.ofEpochSecond(outcome.atMs / 1000).toString()
        return "last=${outcome.result} reason=${outcome.reason} at=$at elapsed_ms=${outcome.elapsedMs} $running"
    }
}

/**
 * Why the migration shell stopped: its last `CUSTODY_REASON`, or else its exit code and the last ten lines of
 * its traced output on one line, so a refusal on a panel is readable from `/api/v1/diag` alone.
 */
internal fun custodyShellReason(exit: Int?, output: String?): String {
    if (exit == null || output == null) return "no_reply"
    val lines = output.lines().filter { it.isNotBlank() }
    lines.lastOrNull { it.startsWith("CUSTODY_REASON ") }?.let { return it.removePrefix("CUSTODY_REASON ").trim() }
    return "exit_$exit:" + lines.takeLast(10).joinToString("|") { it.trim().replace(Regex("\\s+"), "_") }.take(600)
}
