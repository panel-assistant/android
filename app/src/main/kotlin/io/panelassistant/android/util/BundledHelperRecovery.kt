package io.panelassistant.android.util

import io.panelassistant.android.control.Su
import java.io.File

internal fun bundledHelperReplacementStatusAfterRecovery(
    asset: File,
    expectedSha256: String,
    statusProbe: () -> GuardDbMaintenanceClient.StatusProbe = GuardDbMaintenance.client::statusProbe,
    reconcile: (File, String) -> Boolean = { file, hash -> reconcileBundledHelperReplacement(file, hash) },
): GuardDbMaintenanceClient.StatusProbe? {
    val before = statusProbe()
    if (before is GuardDbMaintenanceClient.StatusProbe.Valid) return before
    if (!reconcile(asset, expectedSha256)) return null
    return statusProbe()
}

/** One failed guarded settlement can recover an exact stalled incumbent and retry installation. */
internal fun retryBundledHelperAfterStalledReplacement(
    result: BundledHelperInstaller.Result,
    retryStalled: Boolean,
    statusProbe: () -> GuardDbMaintenanceClient.StatusProbe,
    reconcile: () -> Boolean,
    retry: () -> BundledHelperInstaller.Result,
): BundledHelperInstaller.Result = if (
    result == BundledHelperInstaller.Result.FAILED && retryStalled &&
    statusProbe() == GuardDbMaintenanceClient.StatusProbe.Malformed && reconcile()
) retry() else result

internal fun reconcileBundledHelperReplacement(
    asset: File,
    expectedSha256: String,
    filesystemRoot: String = "",
    execute: (String, File, Long) -> String? = Su::runWithStdinLongChecked,
): Boolean = bundledHelperRecoveryAccepted(execute(
    bundledHelperRecoveryCommand(expectedSha256, asset.length(), filesystemRoot), asset, 30_000L,
))

/** Upload separately from candidate custody; only the authenticated native helper may retire a journal. */
internal fun bundledHelperRecoveryCommand(
    expectedSha256: String,
    expectedBytes: Long,
    filesystemRoot: String = "",
): String {
    require(GuardDbMaintenanceProtocol.validSha256(expectedSha256))
    require(expectedBytes in 1..16L * 1024 * 1024)
    require(filesystemRoot.isEmpty() || (SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
        !filesystemRoot.endsWith('/') && filesystemRoot.split('/').none { it == ".." }))
    val d = '$'
    return """
        root=$filesystemRoot
        data_local=${d}root/data/local
        lock=${d}root/dev/.hapaneld-helper-transaction.lock
        recovery=${d}data_local/.hapaneld-helper.recovery-$expectedSha256
        upload=${d}recovery.upload
        [ -d "${d}data_local" ] && [ ! -L "${d}data_local" ] || exit 1
        parent_meta=${d}(stat -c '%u:%a' "${d}data_local" 2>/dev/null || toybox stat -c '%u:%a' "${d}data_local" 2>/dev/null) || exit 1
        case "${d}parent_meta" in 0:700|0:710|0:711|0:750|0:751|0:755) ;; *) exit 1 ;; esac
        umask 077
        # Existing lock custody belongs to native reconciliation, never this upload script.
        mkdir "${d}lock" 2>/dev/null || exit 75
        echo ${d}${d} > "${d}lock/pid" || { rmdir "${d}lock"; exit 75; }
        upload_owned=0
        release_upload() {
          if [ "${d}upload_owned" = 1 ]; then rm -f "${d}upload" || return 1; fi
          [ "${d}(cat "${d}lock/pid" 2>/dev/null)" = "${d}${d}" ] || return 1
          rm -f "${d}lock/pid" && rmdir "${d}lock"
        }
        trap release_upload 0
        trap 'release_upload; trap - 0; exit 74' 1 2 3 15
        absent() { [ ! -e "${d}1" ] && [ ! -L "${d}1" ]; }
        meta() { stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null; }
        exact() {
          [ -f "${d}1" ] && [ ! -L "${d}1" ] &&
          [ "${d}(meta "${d}1")" = "0:0:700:1:$expectedBytes" ] || return 1
          hash=${d}(sha256sum "${d}1" 2>/dev/null || toybox sha256sum "${d}1" 2>/dev/null) || return 1
          [ "${d}{hash%% *}" = "$expectedSha256" ]
        }
        # Never follow or replace a foreign recovery slot or upload, including a hard link.
        absent "${d}upload" || exit 75
        if ! absent "${d}recovery"; then exact "${d}recovery" || exit 75; fi
        umask 077
        upload_owned=1
        cat > "${d}upload" || exit 1
        [ -f "${d}upload" ] && [ ! -L "${d}upload" ] || exit 1
        chown 0:0 "${d}upload" && chmod 700 "${d}upload" || exit 1
        exact "${d}upload" || exit 1
        if absent "${d}recovery"; then mv "${d}upload" "${d}recovery" || exit 1
        else rm -f "${d}upload" || exit 1; fi
        sync || exit 1
        exact "${d}recovery" || exit 1
        # Native reconciliation takes this same transaction lock and owns all journal decisions.
        release_upload || exit 75
        trap - 0 1 2 3 15
        exec "${d}recovery" --reconcile-stale-replacement
    """.trimIndent()
}

internal fun bundledHelperRecoveryAccepted(reply: String?): Boolean = reply in setOf(
    "STALE_REPLACEMENT RECONCILED\n", "STALE_REPLACEMENT NONE\n",
)
