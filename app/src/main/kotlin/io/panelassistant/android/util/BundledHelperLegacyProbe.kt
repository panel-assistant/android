package io.panelassistant.android.util

import io.panelassistant.android.control.Su
import java.io.File

internal sealed interface BundledHelperLegacyProbe {
    data object Absent : BundledHelperLegacyProbe
    data class Legacy(val incumbentBuildId: String) : BundledHelperLegacyProbe
    data object Refused : BundledHelperLegacyProbe
}

/** Root can observe a released helper which still authenticates only the previous app package. */
internal fun probeBundledLegacyHelper(
    asset: File,
    expectedSha256: String,
    candidateBuildId: String,
    filesystemRoot: String = "",
    execute: (String, Long) -> String? = Su::runOutputLong,
): BundledHelperLegacyProbe {
    require(GuardDbMaintenanceProtocol.validSha256(candidateBuildId))
    val raw = execute(bundledHelperLegacyProbeCommand(
        expectedSha256, asset.length(), filesystemRoot,
    ), 15_000L) ?: return BundledHelperLegacyProbe.Refused
    if (raw == "PROBE_EXIT=1\n".repeat(3)) return BundledHelperLegacyProbe.Absent
    val matched = LEGACY_ROOT_PROBE_REPLY.matchEntire(raw) ?: return BundledHelperLegacyProbe.Refused
    val incumbent = matched.groupValues[1]
    return if (incumbent == matched.groupValues[2] && incumbent != candidateBuildId) {
        BundledHelperLegacyProbe.Legacy(incumbent)
    } else BundledHelperLegacyProbe.Refused
}

private val LEGACY_ROOT_PROBE_REPLY = Regex(
    "BUILDID ([a-f0-9]{64})\nPROBE_EXIT=0\nERR\nPROBE_EXIT=0\nBUILDID ([a-f0-9]{64})\nPROBE_EXIT=0\n",
)

/** Execute only the root-owned copy already authenticated by native replacement reconciliation. */
internal fun bundledHelperLegacyProbeCommand(
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
        local=${d}root/data/local
        recovery=${d}local/.hapaneld-helper.recovery-$expectedSha256
        [ -d "${d}local" ] && [ ! -L "${d}local" ] || exit 1
        parent_meta=${d}(stat -c '%u:%a' "${d}local" 2>/dev/null || toybox stat -c '%u:%a' "${d}local" 2>/dev/null) || exit 1
        case "${d}parent_meta" in 0:700|0:710|0:711|0:750|0:751|0:755) ;; *) exit 1 ;; esac
        exact() {
          [ -f "${d}recovery" ] && [ ! -L "${d}recovery" ] || return 1
          meta=${d}(stat -c '%u:%g:%a:%h:%s' "${d}recovery" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${d}recovery" 2>/dev/null) || return 1
          [ "${d}meta" = '0:0:700:1:$expectedBytes' ] || return 1
          hash=${d}(sha256sum "${d}recovery" 2>/dev/null || toybox sha256sum "${d}recovery" 2>/dev/null) || return 1
          [ "${d}{hash%% *}" = '$expectedSha256' ]
        }
        for verb in BUILDID GUARDSTATUS BUILDID; do
          exact || exit 1
          # The exit marker prevents command substitution stripping the helper's wire LF.
          observed=${d}("${d}recovery" --request "${d}verb" 2>/dev/null; printf 'PROBE_EXIT=%s\n' "${d}?")
          printf '%s\n' "${d}observed"
        done
    """.trimIndent()
}
