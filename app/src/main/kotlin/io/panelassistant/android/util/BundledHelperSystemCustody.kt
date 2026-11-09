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

internal fun systemCustodyMigrationRecordReadCommand(filesystemRoot: String = ""): String {
    requireFilesystemRoot(filesystemRoot)
    return """
    record=$filesystemRoot/data/local/.hapaneld-helper.custody-migration
    [ -f "${'$'}record" ] && [ ! -L "${'$'}record" ] &&
    meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null) &&
    case "${'$'}meta" in 0:0:600:1:*) ;; *) false ;; esac &&
    bytes=${'$'}{meta##*:} && [ "${'$'}bytes" -ge 1 ] && [ "${'$'}bytes" -le 1024 ] &&
    dd if="${'$'}record" bs=1025 count=1 2>/dev/null
""".trimIndent()
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
 * The root work at the head of a start. An already-current start makes only the released takeover check it
 * always made; the migration record is read only on a start that is going to root anyway (the bundled
 * helper is not the one serving, or nothing answers).
 */
internal fun bundledHelperStartGate(
    admission: BundledHelperInstaller.Result?,
    currentLegacyCustody: () -> BundledHelperInstaller.Result?,
    systemCustody: () -> BundledHelperInstaller.Result?,
): BundledHelperInstaller.Result? = when (admission) {
    null -> systemCustody()
    BundledHelperInstaller.Result.ALREADY_CURRENT -> currentLegacyCustody() ?: admission
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

    fun summary(): String {
        val running = if (startedAt != null) "running=yes" else "running=no"
        val outcome = last ?: return "never_run $running"
        val at = java.time.Instant.ofEpochSecond(outcome.atMs / 1000).toString()
        return "last=${outcome.result} reason=${outcome.reason} at=$at elapsed_ms=${outcome.elapsedMs} $running"
    }
}
