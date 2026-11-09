package io.panelassistant.android.util

/*
 * The bundled helper's fresh install: a panel whose helper socket does not answer gets this app's helper at
 * /data/local, started by the app on each service start. It also decides what the command-line installers
 * deleted on 2026-10-08 left behind on rooted panels, and which of those leftovers no longer count.
 */

// Every place a provisioned helper lives or boots from. A fresh install must find none of them; the
// deleted provisioner's /system registration and its /data/adb custody are judged on their contents.
private val BUNDLED_HELPER_BOOT_AUTHORITIES = listOf(
    "/vendor/etc/init/hapaneld-helper.rc",
    "/data/adb/hapaneld/hapaneld-helper",
    "/data/adb/service.d/hapaneld-helper.sh",
    "/system/bin/hapaneld-ledd",
    "/system/etc/init/hapaneld-ledd.rc",
)

// The only rc the deleted provisioner installed that names this app's own path: exactly
// `service hapaneld_helper /data/local/hapaneld-helper --supervise`, root, su domain, 131 bytes.
private const val PROVISIONED_DATA_LOCAL_REGISTRATION_SHA256 =
    "33055e49ba0d229c382c7b143a5f8cbcb6c507ef2ce170aa9c7bff8c28e6b232"

// Written by a running helper's own Guard replacement: always a live authority.
internal val GUARD_REPLACEMENT_JOURNALS = listOf(
    "/data/local/.hapaneld-guard-db/replacement.v1",
    "/data/local/.hapaneld-guard-db/.replacement.v1.tmp",
)

// Written only by the command-line installers deleted on 2026-10-08 (the provisioner and the standalone
// helper installer). Nothing can finish or roll back their transactions any more.
internal val DELETED_INSTALLER_JOURNALS = listOf(
    "/system/bin/.hapaneld-helper-upgrade",
    "/system/bin/.hapaneld-helper-manual-upgrade",
    "/data/adb/hapaneld/.helper-upgrade.marker",
    "/data/adb/hapaneld/.helper-hybrid-upgrade.marker",
    "/data/adb/hapaneld/.helper-manual-upgrade.marker",
    "/data/local/.hapaneld-helper-manual-upgrade",
)

internal fun List<String>.underRoot(): String = joinToString(" ") { "\"\$root$it\"" }

/**
 * Defines `installer_journals_retired`: true when every journal the deleted installers left is retired in
 * place, never honoured and never touched. Each must be a root file, not a link, written before this boot,
 * so no installer step that wrote it is still running (the caller also holds the lock each such step took,
 * though not across the steps), and written before this app was installed. The installers that could finish
 * or roll back these transactions are gone, so nothing else ever will. A journal from this boot or from
 * after this install, which only a removed installer run by hand could write, or one that cannot be read,
 * still blocks. The boot check reads the wall clock, so a panel without a real-time clock that ran such an
 * installer before its clock was set could see a journal from this boot as older.
 */
internal fun installerJournalsRetiredShell(appInstalledAtEpochSeconds: Long): String {
    require(appInstalledAtEpochSeconds >= 0)
    val dollar = '$'
    return """
        installer_journals_retired() {
          read uptime_seconds _ < /proc/uptime || return 1
          now=${dollar}(date +%s) || return 1
          booted=${dollar}((now - ${dollar}{uptime_seconds%%.*}))
          for journal in ${DELETED_INSTALLER_JOURNALS.underRoot()}; do
            [ ! -e "${dollar}journal" ] && [ ! -L "${dollar}journal" ] && continue
            [ ! -L "${dollar}journal" ] || return 1
            written=${dollar}(stat -c '%u:%g:%h:%Y' "${dollar}journal" 2>/dev/null || toybox stat -c '%u:%g:%h:%Y' "${dollar}journal" 2>/dev/null) || return 1
            case "${dollar}written" in 0:0:1:*) ;; *) return 1 ;; esac
            written=${dollar}{written##*:}
            [ "${dollar}written" -lt "${dollar}booted" ] && [ "${dollar}written" -lt $appInstalledAtEpochSeconds ] || return 1
          done
        }
    """.trimIndent().replace("\n", "\n        ") // keeps the enclosing script's common indent
}

/**
 * The only helper is the one this app publishes: no boot binary, boot registration, provisioner
 * custody, recovery journal, takeover or custody-migration record or Guard DB state beyond its owner lock may exist, or a second root authority would appear
 * beside one that a reboot or a running transaction brings back. Under the shared helper lock the
 * staged candidate replaces any earlier `/data/local` copy that is not running (a reboot keeps the file
 * and loses the process), then supervises itself and must prove its exact bytes and Guard capabilities.
 * Refusal leaves no candidate behind; a candidate that cannot prove itself is stopped.
 *
 * What the deleted provisioner left is no such authority, and is left exactly as it is: its exact
 * `/data/local` rc, and any identical leftover copy beside it, registers this install's own path (init's
 * exec of it is refused on the panels that carry it, so the helper is gone after every reboot until this
 * runs), its `/system/bin` copy is named by no registration and must not be running, its
 * `/data/adb/hapaneld` directory holds only custody once the systemless binary and registration are
 * absent, and its stale journals are retired by [installerJournalsRetiredShell].
 */
internal fun bundledFreshHelperInstallCommand(
    expectedSha256: String,
    stagedBuildId: String,
    filesystemRoot: String = "",
    polls: Int = LEGACY_TAKEOVER_POLLS,
    appInstalledAtEpochSeconds: Long = 0,
): String {
    require(GuardDbMaintenanceProtocol.validSha256(expectedSha256))
    require(GuardDbMaintenanceProtocol.validSha256(stagedBuildId))
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    require(polls in 1..LEGACY_TAKEOVER_POLLS)
    val dollar = '$'
    val expectedCapabilities =
        "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
    val otherHelpers = BUNDLED_HELPER_BOOT_AUTHORITIES.underRoot()
    val guardJournals = GUARD_REPLACEMENT_JOURNALS.underRoot()
    val installerJournalsRetired = installerJournalsRetiredShell(appInstalledAtEpochSeconds)
    return """
        root=$filesystemRoot
        data_local=${dollar}root/data/local
        lock=${dollar}root/dev/.hapaneld-helper-transaction.lock
        stage=${dollar}data_local/.hapaneld-helper.new
        live=${dollar}data_local/hapaneld-helper
        if ! mkdir "${dollar}lock" 2>/dev/null; then
          holder=${dollar}(cat "${dollar}lock/pid" 2>/dev/null || true)
          case "${dollar}holder" in ''|*[!0-9]*) exit 75 ;; *) [ ! -d "/proc/${dollar}holder" ] || exit 75 ;; esac
          rm -rf "${dollar}lock" 2>/dev/null || exit 75
          mkdir "${dollar}lock" 2>/dev/null || exit 75
        fi
        echo ${dollar}${dollar} > "${dollar}lock/pid" || { rm -rf "${dollar}lock"; exit 75; }
        trap 'rm -rf "${dollar}lock"' 0
        trap 'rm -rf "${dollar}lock"; trap - 0; exit 74' 1 2 3 15
        [ -d "${dollar}data_local" ] && [ ! -L "${dollar}data_local" ] || exit 1

        file_hash() {
          hash=${dollar}(sha256sum "${dollar}1" 2>/dev/null || toybox sha256sum "${dollar}1" 2>/dev/null) || return 1
          printf '%s\n' "${dollar}{hash%% *}"
        }
        file_meta() {
          stat -c '%u:%g:%a:%h:%s' "${dollar}1" 2>/dev/null ||
            toybox stat -c '%u:%g:%a:%h:%s' "${dollar}1" 2>/dev/null
        }
        absent() { [ ! -e "${dollar}1" ] && [ ! -L "${dollar}1" ]; }
        exact_candidate() {
          [ -f "${dollar}1" ] && [ ! -L "${dollar}1" ] &&
            [ "${dollar}(file_hash "${dollar}1")" = "$expectedSha256" ] &&
            case "${dollar}(file_meta "${dollar}1")" in 0:0:700:1:*) true ;; *) false ;; esac
        }
        refuse() {
          if exact_candidate "${dollar}stage"; then rm -f "${dollar}stage"; fi
          exit 1
        }
        # Guard recovery custody is durable on disk, whatever the socket or the app's own data say.
        # Empty means what the helper itself accepts as empty: nothing but its owner lock.
        guard_empty() {
          guard=${dollar}data_local/.hapaneld-guard-db
          absent "${dollar}guard" && return 0
          [ -d "${dollar}guard" ] && [ ! -L "${dollar}guard" ] || return 1
          entries=${dollar}(ls -A "${dollar}guard") || return 1
          for entry in ${dollar}entries; do [ "${dollar}entry" = .owner.lock ] || return 1; done
        }
        $installerJournalsRetired
        # True when a process runs from this file, or when one named like the helper cannot be resolved.
        runs() {
          target=${dollar}(stat -c '%d:%i' "${dollar}1" 2>/dev/null || toybox stat -c '%d:%i' "${dollar}1" 2>/dev/null) || return 0
          for process in /proc/[0-9]*; do
            if inode=${dollar}(stat -Lc '%d:%i' "${dollar}process/exe" 2>/dev/null || toybox stat -L -c '%d:%i' "${dollar}process/exe" 2>/dev/null); then
              [ "${dollar}inode" != "${dollar}target" ] || return 0
            else
              # Kernel threads have no executable; an unreadable one named hapaneld-helper might be this copy.
              name=${dollar}(tr '\0' '\n' < "${dollar}process/cmdline" 2>/dev/null | head -n 1) || continue
              [ "${dollar}{name##*/}" != hapaneld-helper ] || return 0
            fi
          done
          return 1
        }
        # The provisioner's rc names this install's own path; its /system/bin copy is then named by nothing.
        provisioned_registration_inert() {
          registration=${dollar}root/system/etc/init/hapaneld-helper.rc
          system_copy=${dollar}root/system/bin/hapaneld-helper
          # init reads every file in its directories: a leftover copy beside the rc must be the same rc.
          for rc in "${dollar}registration" "${dollar}registration"?*; do
            absent "${dollar}rc" && continue
            # stat does not follow a link, so a link to the right bytes reads 777 and is refused here.
            [ "${dollar}(file_meta "${dollar}rc")" = "0:0:644:1:131" ] &&
              [ "${dollar}(file_hash "${dollar}rc")" = "$PROVISIONED_DATA_LOCAL_REGISTRATION_SHA256" ] || return 1
          done
          for rc in "${dollar}root"/vendor/etc/init/hapaneld-helper.rc?*; do absent "${dollar}rc" || return 1; done
          absent "${dollar}system_copy" && return 0
          ! absent "${dollar}registration" || return 1
          [ ! -L "${dollar}system_copy" ] && ! runs "${dollar}system_copy"
        }
        # With no systemless binary or registration, the provisioner's directory holds only custody.
        provisioner_custody_inert() {
          custody=${dollar}root/data/adb/hapaneld
          absent "${dollar}custody" || { [ -d "${dollar}custody" ] && [ ! -L "${dollar}custody" ]; }
        }
        no_other_authority() {
          for authority in \
            $guardJournals \
            $otherHelpers \
            "${dollar}data_local/.hapaneld-helper.legacy-takeover" \
            "${dollar}data_local/.hapaneld-helper.legacy-takeover.tmp" \
            "${dollar}data_local/.hapaneld-helper.custody-migration" \
            "${dollar}data_local/.hapaneld-helper.custody-migration.tmp" \
            "${dollar}data_local/.hapaneld-helper.previous" \
            "${dollar}data_local/.hapaneld-helper.previous.tmp"; do
            absent "${dollar}authority" || return 1
          done
          installer_journals_retired || return 1
          provisioned_registration_inert || return 1
          provisioner_custody_inert || return 1
          guard_empty
        }
        no_other_authority || refuse
        if ! absent "${dollar}live"; then
          [ -f "${dollar}live" ] && [ ! -L "${dollar}live" ] || refuse
          case "${dollar}(file_meta "${dollar}live")" in 0:0:700:1:*) ;; *) refuse ;; esac
        fi
        exact_candidate "${dollar}stage" || exact_candidate "${dollar}live" || exit 1

        live_processes() {
          absent "${dollar}live" && return 0
          target_inode=${dollar}(stat -c '%d:%i' "${dollar}live" 2>/dev/null || toybox stat -c '%d:%i' "${dollar}live" 2>/dev/null) || return 1
          found=
          for executable in /proc/[0-9]*/exe; do
            inode=${dollar}(stat -Lc '%d:%i' "${dollar}executable" 2>/dev/null || toybox stat -L -c '%d:%i' "${dollar}executable" 2>/dev/null) || continue
            [ "${dollar}inode" != "${dollar}target_inode" ] || found="${dollar}found ${dollar}{executable#/proc/}"
          done
          printf '%s\n' "${dollar}found"
        }
        stop_live() {
          candidates=${dollar}(live_processes) || return 1
          for candidate in ${dollar}candidates; do kill "${dollar}{candidate%/exe}" 2>/dev/null || true; done
          attempt=0
          while [ "${dollar}attempt" -lt 3 ]; do
            candidates=${dollar}(live_processes) || return 1
            [ -n "${dollar}candidates" ] || return 0
            sleep 1; attempt=${dollar}((attempt + 1))
          done
          for candidate in ${dollar}candidates; do kill -9 "${dollar}{candidate%/exe}" 2>/dev/null || true; done
          sleep 1
          [ -z "${dollar}(live_processes)" ]
        }

        stop_live || exit 1
        # A copy stopped just now may have been writing custody; nothing runs it from here on.
        no_other_authority || refuse
        if exact_candidate "${dollar}stage"; then mv -f "${dollar}stage" "${dollar}live" || exit 1; fi
        sync || exit 1
        exact_candidate "${dollar}live" && absent "${dollar}stage" || exit 1
        candidate_bytes=${dollar}(file_meta "${dollar}live") || exit 1
        candidate_bytes=${dollar}{candidate_bytes##*:}

        "${dollar}live" --supervise >/dev/null 2>&1 &
        attempt=0
        while [ "${dollar}attempt" -lt $polls ]; do
          self=${dollar}("${dollar}live" --request GUARDSELF 2>/dev/null) || self=
          [ "${dollar}self" != "OK GUARDSELF 1 ${dollar}candidate_bytes $expectedSha256 $stagedBuildId" ] || break
          attempt=${dollar}((attempt + 1))
          [ "${dollar}attempt" -ge $polls ] || sleep 1
        done
        if [ "${dollar}self" = "OK GUARDSELF 1 ${dollar}candidate_bytes $expectedSha256 $stagedBuildId" ] &&
           [ "${dollar}("${dollar}live" --request GUARDCAPS 2>/dev/null)" = "$expectedCapabilities" ]; then
          exit 0
        fi
        stop_live || true
        exit 1
    """.trimIndent()
}
