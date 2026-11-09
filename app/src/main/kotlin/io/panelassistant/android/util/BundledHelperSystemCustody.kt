package io.panelassistant.android.util

import java.security.MessageDigest

/*
 * Layout F custody migration: a Guard-capable helper served from /system/bin under a /system
 * rc cannot prove its own identity (GUARDSELF answers `HOLD self`), so no Guard replacement can start. The
 * takeover shell's migration mode archives both /system files, swaps the bundled build in, then removes them,
 * leaving the plain app-lease layout the fresh install already accepts. This file holds the record that
 * spans that transaction and the reboot after it, and the settlement that retires it.
 */

internal const val SYSTEM_CUSTODY_PHASE_ARCHIVED = "ARCHIVED"
internal const val SYSTEM_CUSTODY_PHASE_SWAPPED = "SWAPPED"
internal const val SYSTEM_CUSTODY_PHASE_REMOVED = "SYSTEM_REMOVED"

/**
 * `OK CUSTODYMIGRATION 1 <phase> <old_sha> <old_bytes> <rc_sha> <rc_bytes> <stale_sha|NONE> <stale_bytes>
 * <incumbent_build> <staged_build> <candidate_sha> <candidate_bytes> <boot_id|NONE>`, root 0600 at
 * `/data/local/.hapaneld-helper.custody-migration`. It exists from the first mutation until the bundled
 * helper has served across a reboot with no /system helper left.
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
    val bootId: String?,
    val recordSha256: String,
    val recordBytes: Long,
) {
    val candidateIdentity: GuardDbMaintenanceProtocol.SelfIdentity
        get() = GuardDbMaintenanceProtocol.SelfIdentity(candidateBytes, candidateSha256, stagedBuildId)
}

private val BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private const val MAX_CUSTODY_BYTES = 16L * 1024 * 1024

internal fun parseSystemCustodyMigrationRecord(reply: String): SystemCustodyMigrationRecord? {
    if ('\r' in reply || reply.toByteArray(Charsets.UTF_8).size !in 1..1024) return null
    val line = if (reply.endsWith('\n')) reply.dropLast(1) else reply
    if ('\n' in line) return null
    val fields = line.split(' ')
    if (fields.size != 15 || fields[0] != "OK" || fields[1] != "CUSTODYMIGRATION" || fields[2] != "1") return null
    val bytes = { field: String -> field.toLongOrNull()?.takeIf { it in 1..MAX_CUSTODY_BYTES } }
    val phase = fields[3].takeIf {
        it in setOf(SYSTEM_CUSTODY_PHASE_ARCHIVED, SYSTEM_CUSTODY_PHASE_SWAPPED, SYSTEM_CUSTODY_PHASE_REMOVED)
    } ?: return null
    val staleSha = fields[8].takeIf { it != "NONE" }
    val staleBytes = if (staleSha == null) {
        fields[9].takeIf { it == "0" }?.toLong()
    } else {
        bytes(fields[9]).takeIf { GuardDbMaintenanceProtocol.validSha256(staleSha) }
    } ?: return null
    val bootId = fields[14].takeIf { it != "NONE" }
    if (bootId != null && !BOOT_ID.matches(bootId)) return null
    if ((bootId != null) != (phase == SYSTEM_CUSTODY_PHASE_REMOVED)) return null
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
        bootId = bootId,
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

/**
 * Retires the record once the bundled helper has served across a reboot with no /system helper left:
 * same boot prints `KEPT same_boot` and keeps it. Otherwise both /system paths must be gone in init's view,
 * the live copy and the archive exact, and nothing running a /system helper; the serving process's parent
 * and command line are printed for the receipt (`--supervise` under pid 1 is init, the app lease otherwise).
 * The caller has already proved GUARDSELF on the recorded build.
 */
internal fun systemCustodyMigrationSettleCommand(
    record: SystemCustodyMigrationRecord,
    filesystemRoot: String = "",
): String {
    requireFilesystemRoot(filesystemRoot)
    require(record.phase == SYSTEM_CUSTODY_PHASE_REMOVED && record.bootId != null && BOOT_ID.matches(record.bootId))
    require(GuardDbMaintenanceProtocol.validSha256(record.recordSha256) && record.recordBytes in 1..1024)
    val d = '$'
    return """
        root=$filesystemRoot
        data_local=${d}root/data/local
        lock=${d}root/dev/.hapaneld-helper-transaction.lock
        record=${d}data_local/.hapaneld-helper.custody-migration
        live=${d}data_local/hapaneld-helper
        archive=${d}data_local/.hapaneld-custody-archive
        system_bin=${d}root/system/bin/hapaneld-helper
        registration=${d}root/system/etc/init/hapaneld-helper.rc
        init_root=${d}root/proc/1/root
        if ! mkdir "${d}lock" 2>/dev/null; then
          holder=${d}(cat "${d}lock/pid" 2>/dev/null || true)
          case "${d}holder" in ''|*[!0-9]*) exit 75 ;; *) [ ! -d "/proc/${d}holder" ] || exit 75 ;; esac
          rm -rf "${d}lock" 2>/dev/null || exit 75
          mkdir "${d}lock" 2>/dev/null || exit 75
        fi
        echo ${d}${d} > "${d}lock/pid" || { rm -rf "${d}lock"; exit 75; }
        trap 'rm -rf "${d}lock"' 0
        trap 'rm -rf "${d}lock"; trap - 0; exit 74' 1 2 3 15
        file_meta() { stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null; }
        file_hash() { hash=${d}(sha256sum "${d}1" 2>/dev/null || toybox sha256sum "${d}1" 2>/dev/null) && printf '%s\n' "${d}{hash%% *}"; }
        absent() { [ ! -e "${d}1" ] && [ ! -L "${d}1" ]; }
        exact_file() {
          [ -f "${d}1" ] && [ ! -L "${d}1" ] &&
            [ "${d}(file_hash "${d}1")" = "${d}2" ] && [ "${d}(file_meta "${d}1")" = "0:0:${d}3:1:${d}4" ]
        }
        exact_file "${d}record" ${record.recordSha256} 600 ${record.recordBytes} || exit 1
        boot_now=${d}(cat "${d}root/proc/sys/kernel/random/boot_id" 2>/dev/null) || exit 1
        [ -n "${d}boot_now" ] || exit 1
        if [ "${d}boot_now" = ${record.bootId} ]; then echo "KEPT same_boot"; exit 0; fi
        absent "${d}system_bin" && absent "${d}registration" &&
          absent "${d}init_root/system/bin/hapaneld-helper" && absent "${d}init_root/system/etc/init/hapaneld-helper.rc" || exit 1
        exact_file "${d}live" ${record.candidateSha256} 700 ${record.candidateBytes} || exit 1
        exact_file "${d}archive/hapaneld-helper.system.${record.oldSha256}" ${record.oldSha256} 600 ${record.oldBytes} &&
          exact_file "${d}archive/hapaneld-helper.rc.${record.registrationSha256}" ${record.registrationSha256} 600 ${record.registrationBytes} || exit 1
        live_inode=${d}(stat -c '%d:%i' "${d}live" 2>/dev/null || toybox stat -c '%d:%i' "${d}live" 2>/dev/null) || exit 1
        for exe in /proc/[0-9]*/exe; do
          target=${d}(readlink "${d}exe" 2>/dev/null) || continue
          case "${d}target" in "${d}system_bin"|"${d}system_bin (deleted)"|/system/bin/hapaneld-helper|"/system/bin/hapaneld-helper (deleted)") exit 1 ;; esac
          inode=${d}(stat -Lc '%d:%i' "${d}exe" 2>/dev/null || toybox stat -L -c '%d:%i' "${d}exe" 2>/dev/null) || continue
          [ "${d}inode" = "${d}live_inode" ] || continue
          process=${d}{exe%/exe}
          parent=${d}(sed -n 's/^PPid:[[:space:]]*//p' "${d}process/status" 2>/dev/null)
          echo "SERVING ${d}{process#/proc/} ppid=${d}parent cmd=${d}(tr '\0' ' ' < "${d}process/cmdline" 2>/dev/null)"
        done
        rm -f "${d}record" "${d}record.tmp" && sync || exit 1
        echo SETTLED
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
 * Writer-free startup may relaunch only a record whose /system files are already gone: that is the exact
 * current-build candidate, nothing else could serve, and relaunching it stages and replaces nothing.
 */
internal fun systemCustodyResumeOnlyAllowed(record: SystemCustodyMigrationRecord, currentBuildId: String): Boolean =
    record.phase == SYSTEM_CUSTODY_PHASE_REMOVED && record.stagedBuildId == currentBuildId
