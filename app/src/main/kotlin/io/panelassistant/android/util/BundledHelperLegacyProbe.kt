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

/** What the read-only layout F probe found: the one /system custody layout the 1.0.1 migration repairs. */
internal sealed interface SystemCustodyLayout {
    val inventory: List<String>
    data class LayoutF(override val inventory: List<String>) : SystemCustodyLayout
    data class Refused(val reason: String, override val inventory: List<String>) : SystemCustodyLayout
}

/** The only /system rc the migration accepts: the pre-canonical `service hapaneld_helper /system/bin/hapaneld-helper`. */
internal const val SYSTEM_CUSTODY_REGISTRATION_SHA256 =
    "9b430712c493df177a19e5e893df445f6c2e951fc30ea140dcdbcdb7987de659"

internal fun probeSystemCustodyLayout(
    filesystemRoot: String = "",
    execute: (String, Long) -> String? = Su::runOutputLong,
): SystemCustodyLayout = parseSystemCustodyProbe(execute(bundledHelperSystemCustodyProbeCommand(filesystemRoot), 15_000L))

/**
 * Read-only, before anything is staged: prints the inventory the hardware acceptance compares, then one
 * verdict. Layout F is a Guard-capable helper answering `HOLD self` with its binary at /system/bin and
 * the exact pre-canonical /system rc, nothing else that could register or run a helper, a Guard DB holding only
 * its owner lock, and any /data/local copy stopped. Every other layout gets its own refusal reason. The
 * serving process's executable is printed, not judged: the migration itself proves the incumbent by asking
 * the /system binary for the build the socket reported.
 */
internal fun bundledHelperSystemCustodyProbeCommand(filesystemRoot: String = ""): String {
    require(filesystemRoot.isEmpty() || (SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
        !filesystemRoot.endsWith('/') && filesystemRoot.split('/').none { it == ".." }))
    val d = '$'
    val journals = (GUARD_REPLACEMENT_JOURNALS + DELETED_INSTALLER_JOURNALS).underRoot()
    return """
        root=$filesystemRoot
        local=${d}root/data/local
        live=${d}local/hapaneld-helper
        system_bin=${d}root/system/bin/hapaneld-helper
        registration=${d}root/system/etc/init/hapaneld-helper.rc
        vendor_registration=${d}root/vendor/etc/init/hapaneld-helper.rc
        data_bin=${d}root/data/adb/hapaneld/hapaneld-helper
        service_registration=${d}root/data/adb/service.d/hapaneld-helper.sh
        guard=${d}local/.hapaneld-guard-db
        absent() { [ ! -e "${d}1" ] && [ ! -L "${d}1" ]; }
        meta() { stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${d}1" 2>/dev/null; }
        digest() { hash=${d}(sha256sum "${d}1" 2>/dev/null || toybox sha256sum "${d}1" 2>/dev/null) && printf '%s
' "${d}{hash%% *}"; }
        inode() { stat -Lc '%d:%i' "${d}1" 2>/dev/null || toybox stat -L -c '%d:%i' "${d}1" 2>/dev/null; }
        describe() { if absent "${d}2"; then echo "INV ${d}1 absent"; else echo "INV ${d}1 ${d}(meta "${d}2") ${d}(digest "${d}2")${d}3"; fi; }
        verdict=
        refuse() { [ -n "${d}verdict" ] || verdict=${d}1; }
        live_running=0
        if [ -f "${d}live" ] && [ ! -L "${d}live" ]; then
          live_inode=${d}(inode "${d}live")
          for exe in /proc/[0-9]*/exe; do
            [ "${d}(inode "${d}exe")" != "${d}live_inode" ] || live_running=1
          done
        fi
        describe registration "${d}registration"
        if [ -f "${d}registration" ]; then echo "INV registration_text ${d}(head -c 1024 "${d}registration" | tr '
' '|')"; fi
        describe system_bin "${d}system_bin"
        describe live "${d}live" " running=${d}live_running"
        echo "INV data_adb_hapaneld ${d}(ls -A "${d}root/data/adb/hapaneld" 2>/dev/null | tr '
' ' ')"
        echo "INV guard_db ${d}(ls -A "${d}guard" 2>/dev/null | tr '
' ' ' | sed 's/ ${d}//')"
        while IFS= read -r mount_line; do
          set -- ${d}mount_line
          case "${d}5" in /|/system) echo "INV mount ${d}mount_line" ;; esac
        done < "${d}root/proc/1/mountinfo"
        echo "INV selinux ${d}(getenforce 2>/dev/null)"
        echo "INV boot_id ${d}(cat "${d}root/proc/sys/kernel/random/boot_id" 2>/dev/null)"
        echo "INV mnt_ns self=${d}(readlink /proc/self/ns/mnt 2>/dev/null) init=${d}(readlink "${d}root/proc/1/ns/mnt" 2>/dev/null)"
        stale_inode=0
        for exe in /proc/[0-9]*/exe; do
          target=${d}(readlink "${d}exe" 2>/dev/null) || continue
          case "${d}target" in
            "${d}system_bin"|"${d}system_bin (deleted)"|"${d}live"|"${d}live (deleted)")
              pid=${d}{exe#/proc/}; pid=${d}{pid%/exe}
              echo "INV helper_process ${d}pid ${d}target"
              if [ "${d}target" = "${d}live (deleted)" ] ||
                 { [ "${d}target" = "${d}live" ] && [ "${d}(inode "${d}exe")" != "${d}(inode "${d}live")" ]; }; then stale_inode=1; fi ;;
          esac
        done

        for path in "${d}live" "${d}system_bin" "${d}registration" "${d}guard" "${d}local" "${d}root/system/etc/init" "${d}root/system/bin"; do
          [ ! -L "${d}path" ] || refuse symlink
        done
        for rc in "${d}vendor_registration" "${d}vendor_registration"?*; do absent "${d}rc" || refuse vendor_registration; done
        absent "${d}local/tmp/hapaneld-helper" || refuse legacy_tmp_layout
        absent "${d}data_bin" || refuse systemless_guard_custody
        absent "${d}service_registration" || refuse registration_unknown
        absent "${d}root/system/bin/hapaneld-ledd" && absent "${d}root/system/etc/init/hapaneld-ledd.rc" || refuse ledd_present
        for journal in $journals; do absent "${d}journal" || refuse journal_present; done
        absent "${d}local/.hapaneld-helper.previous" && absent "${d}local/.hapaneld-helper.previous.tmp" || refuse previous_present
        absent "${d}local/.hapaneld-helper.legacy-takeover" && absent "${d}local/.hapaneld-helper.legacy-takeover.tmp" ||
          refuse legacy_takeover_record
        absent "${d}local/.hapaneld-helper.custody-migration" && absent "${d}local/.hapaneld-helper.custody-migration.tmp" ||
          refuse custody_record_present
        [ "${d}stale_inode" = 0 ] || refuse stale_live_inode
        [ -f "${d}system_bin" ] && [ -f "${d}registration" ] || refuse no_system_helper
        for rc in "${d}registration"?*; do absent "${d}rc" || refuse registration_sibling; done
        case "${d}(meta "${d}registration")" in 0:0:644:1:*) ;; *) refuse registration_unknown ;; esac
        [ "${d}(digest "${d}registration")" = "$SYSTEM_CUSTODY_REGISTRATION_SHA256" ] || refuse registration_unknown
        case "${d}(meta "${d}system_bin")" in 0:0:755:1:*) ;; *) refuse system_binary_unexpected ;; esac
        if ! absent "${d}live"; then
          [ -f "${d}live" ] || refuse live_unexpected
          case "${d}(meta "${d}live")" in 0:0:*:1:*) ;; *) refuse live_unexpected ;; esac
          [ "${d}live_running" = 0 ] || refuse stale_live_running
        fi
        if ! absent "${d}guard"; then
          [ -d "${d}guard" ] || refuse guard_db_not_empty
          for entry in ${d}(ls -A "${d}guard" 2>/dev/null); do [ "${d}entry" = .owner.lock ] || refuse guard_db_not_empty; done
        fi
        if [ -n "${d}verdict" ]; then echo "REFUSED ${d}verdict"; else echo LAYOUT_F; fi
    """.trimIndent()
}

internal fun parseSystemCustodyProbe(raw: String?): SystemCustodyLayout {
    val lines = raw?.lines()?.filter { it.isNotBlank() }
        ?: return SystemCustodyLayout.Refused("probe_unavailable", emptyList())
    val inventory = lines.filter { it.startsWith("INV ") }
    val verdict = lines.lastOrNull()
    return when {
        verdict == "LAYOUT_F" -> SystemCustodyLayout.LayoutF(inventory)
        verdict != null && verdict.matches(Regex("REFUSED [a-z_]{1,40}")) ->
            SystemCustodyLayout.Refused(verdict.removePrefix("REFUSED "), inventory)
        else -> SystemCustodyLayout.Refused("probe_malformed", inventory)
    }
}
