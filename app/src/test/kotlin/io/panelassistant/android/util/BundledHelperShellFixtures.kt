package io.panelassistant.android.util

import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import org.junit.Assert.assertTrue

/** Shell fixtures shared by the bundled-helper staging, takeover and fresh-install tests. */
internal object BundledHelperShellFixtures {
    val stagedBuild = "a".repeat(64)
    val incumbentBuild = "b".repeat(64)
    const val emptyStatus = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"

    // 2001-01-01: before any boot of the machine running the test, and before any install it names.
    const val STALE_JOURNAL_MS = 978_307_200_000L

    fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    fun foreignHelperJournal(root: File, path: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText("retained authority\n")
        setMode(this, 600)
    }

    fun staleJournal(root: File, path: String): File = foreignHelperJournal(root, path).apply {
        assertTrue(setLastModified(STALE_JOURNAL_MS))
    }

    data class FreshFixture(val root: File, val live: File, val stage: File, val candidateBytes: String)

    fun withFreshFiles(
        candidateBytes: String = fakeHelper(stagedBuild, candidate = true, starts = true),
        test: (FreshFixture) -> Unit,
    ) {
        val root = scratchDirectory("bundled-helper-fresh-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            File(root, "dev").mkdirs()
            val stage = File(dataLocal, ".hapaneld-helper.new")
            writeExecutable(stage, candidateBytes, 700)
            test(FreshFixture(root, File(dataLocal, "hapaneld-helper"), stage, candidateBytes))
        } finally {
            root.deleteRecursively()
        }
    }

    fun fakeHelper(
        build: String,
        candidate: Boolean,
        starts: Boolean,
        readyAfterFailedProbes: Int = 0,
        signalOnReplacementCheck: Boolean = false,
        signalOnSupervise: Boolean = false,
        sharedSocketMarker: File? = null,
        incumbentReadyMarker: File? = null,
        guardStatus: String = emptyStatus,
        supervisePidFile: File? = null,
        superviseSignal: String = "TERM",
        guardOwnerHeldWhile: File? = null,
    ): String = """
        #!/bin/sh
        if [ "${'$'}1" = --replacement-safe ]; then
          ${if (signalOnReplacementCheck) "kill -TERM \"${'$'}(ps -o ppid= -p \"${'$'}PPID\" | tr -d ' ')\"; sleep 1" else ":"}
          ${if (guardOwnerHeldWhile != null) "if [ -f '${guardOwnerHeldWhile.absolutePath}' ]; then echo GUARD_ARMED; exit 3; fi" else ":"}
          ${if (candidate) "echo REPLACE_SAFE; exit 0" else "exit 2"}
        fi
        if [ "${'$'}1" = --supervise ]; then
          ${if (signalOnSupervise) "sent=${'$'}{0%/*}/.supervise-signalled; if [ ! -f \"${'$'}sent\" ]; then : > \"${'$'}sent\"; kill -$superviseSignal \"${'$'}PPID\"; sleep 1; fi" else ":"}
          ${if (supervisePidFile != null) "echo ${'$'}${'$'} > '${supervisePidFile.absolutePath}'; exec sleep 30" else ":"}
          exit ${if (starts) 0 else 7}
        fi
        if [ "${'$'}1" = --request ]; then
          case "${'$'}2" in
            PING) echo OK ;;
            BUILDID)
              ${if (sharedSocketMarker != null) "if [ -f '${sharedSocketMarker.absolutePath}' ] && [ -f '${sharedSocketMarker.parentFile!!.absolutePath}/hapaneld-helper' ]; then echo 'BUILDID $stagedBuild'; else ${if (!candidate && incumbentReadyMarker != null) "[ -f '${incumbentReadyMarker.absolutePath}' ] || exit 1; " else ""}echo 'BUILDID $build'; fi" else "${if (!candidate && incumbentReadyMarker != null) "[ -f '${incumbentReadyMarker.absolutePath}' ] || exit 1; " else ""}echo 'BUILDID $build'"}
              ;;
            GUARDCAPS) ${if (candidate && starts) "echo '${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE'" else "echo ERR"} ;;
            GUARDSELF)
              ${if (readyAfterFailedProbes > 0) "counter=${'$'}{0%/*}/.candidate-ready-probes; remaining=${'$'}(cat \"${'$'}counter\"); if [ \"${'$'}remaining\" -gt 0 ]; then echo ${'$'}((remaining - 1)) > \"${'$'}counter\"; exit 1; fi" else ":"}
              self_bytes=${'$'}(stat -c %s "${'$'}0")
              self_sha=${'$'}(sha256sum "${'$'}0")
              self_sha=${'$'}{self_sha%% *}
              echo "OK GUARDSELF 1 ${'$'}self_bytes ${'$'}self_sha $build"
              ;;
            GUARDSTATUS) ${if (candidate && starts) "echo '$guardStatus'" else "echo ERR"} ;;
            *) echo ERR ;;
          esac
          exit 0
        fi
        if [ "${'$'}#" -eq 0 ]; then
          ${if (incumbentReadyMarker != null) ": > '${incumbentReadyMarker.absolutePath}'" else ":"}
          exit 0
        fi
        exit 3
    """.trimIndent() + "\n"

    fun writeExecutable(file: File, bytes: String, mode: Int = 700) {
        file.writeText(bytes)
        setMode(file, mode)
    }

    fun setMode(file: File, mode: Int) {
        val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (mode == 700 || mode == 755) permissions += PosixFilePermission.OWNER_EXECUTE
        if (mode == 644 || mode == 755) permissions += PosixFilePermission.GROUP_READ
        if (mode == 755) permissions += PosixFilePermission.GROUP_EXECUTE
        if (mode == 644 || mode == 755) permissions += PosixFilePermission.OTHERS_READ
        if (mode == 755) permissions += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun runTakeoverCommand(command: String, environment: Map<String, String> = emptyMap()): Int =
        ProcessBuilder("bash", "-c", command)
        .redirectErrorStream(true)
        .apply { environment().putAll(environment) }
        .start()
        .let { process ->
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        }

    data class CommandResult(val exitCode: Int, val output: String)

    fun runCommand(command: String): CommandResult = runCommandWithInput(command, "")

    // The pre-canonical /system rc a Guard-era helper still runs under, exactly: 119 bytes, sha256 9b430712.
    val preCanonicalSystemRegistration = """
        service hapaneld_helper /system/bin/hapaneld-helper
            class main
            user root
            group root
            seclabel u:r:su:s0
    """.trimIndent() + "\n"

    const val FIRST_BOOT_ID = "11111111-1111-4111-8111-111111111111"
    const val SECOND_BOOT_ID = "22222222-2222-4222-8222-222222222222"
    const val SYSTEM_BIN_LABEL = "u:object_r:hapaneld_exec:s0"
    const val REGISTRATION_LABEL = "u:object_r:system_file:s0"

    /**
     * Layout F: a Guard-era helper served from /system/bin under the pre-canonical /system rc, with an
     * unrelated stale copy at /data/local. `proc/1/root` is init's view of the same tree.
     */
    data class CustodyFixture(
        val root: File,
        val dataLocal: File,
        val systemBin: File,
        val registration: File,
        val live: File,
        val stage: File,
        val archive: File,
        val record: File,
        val calls: File,
        val bootId: File,
        val incumbentReady: File,
        val oldBytes: String,
        val candidateBytes: String,
        val registrationBytes: String,
        val staleBytes: String?,
    ) {
        val oldSha: String get() = sha256(oldBytes)
        val registrationSha: String get() = sha256(registrationBytes)
        val candidateSha: String get() = sha256(candidateBytes)
        fun callLog(): List<String> = if (calls.isFile) calls.readLines() else emptyList()

        /** Power returns: init starts the /system helper only when its rc and binary are both there. */
        fun reboot(bootId: String = SECOND_BOOT_ID) {
            this.bootId.writeText("$bootId\n")
            if (systemBin.isFile && registration.isFile) incumbentReady.writeText("ready\n") else incumbentReady.delete()
        }

        /** Arms a stub: the named hook delivers [signal] to the calling shell. */
        fun signalOn(hook: String, signal: String) = File(root, hook).writeText(signal)

        /** SIGKILLs the shell at its first sync after [condition] (a bash test) holds. */
        fun killAtSyncWhen(condition: String) = File(root, "kill-on-sync").writeText(condition)

        /** The power stays on: init keeps its stopped service and nothing starts the incumbent again. */
        fun sameBoot() = Unit

        /** The SELinux label the `ls -Z` stub reports: by inode and change time, so a recreated file is unlabeled. */
        fun labelOf(file: File): String = runCommand("'${File(root, "system/bin/ls").absolutePath}' -Zd '${file.absolutePath}'")
            .output.trim().substringBefore(' ')
    }

    fun withCustodyFiles(
        candidateStarts: Boolean = true,
        staleBytes: String? = fakeHelper("f".repeat(64), candidate = true, starts = true),
        registrationBytes: String = preCanonicalSystemRegistration,
        signalOnReplacementCheck: Boolean = false,
        signalOnSupervise: Boolean = false,
        superviseSignal: String = "TERM",
        guardStatus: String = emptyStatus,
        guardOwnerHeldAfterStop: Boolean = false,
        test: (CustodyFixture) -> Unit,
    ) {
        val root = scratchDirectory("bundled-helper-custody-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            File(root, "dev").mkdirs()
            File(dataLocal, ".hapaneld-guard-db").mkdirs()
            File(dataLocal, ".hapaneld-guard-db/.owner.lock").writeText("")
            val systemBin = File(root, "system/bin/hapaneld-helper").apply { parentFile!!.mkdirs() }
            val registration = File(root, "system/etc/init/hapaneld-helper.rc").apply { parentFile!!.mkdirs() }
            val incumbentReady = File(dataLocal, ".incumbent-serving").apply { writeText("ready\n") }
            val calls = File(root, "calls")
            val oldBytes = fakeHelper(incumbentBuild, candidate = false, starts = true, incumbentReadyMarker = incumbentReady)
            val candidateBytes = fakeHelper(
                stagedBuild,
                candidate = true,
                starts = candidateStarts,
                signalOnReplacementCheck = signalOnReplacementCheck,
                signalOnSupervise = signalOnSupervise,
                guardStatus = guardStatus,
                superviseSignal = superviseSignal,
                // A Guard-era incumbent holds the Guard DB owner lock while it runs, so the candidate's
                // --replacement-safe cannot take it until the incumbent has stopped.
                guardOwnerHeldWhile = if (guardOwnerHeldAfterStop) {
                    File(root, "guard-owner-held").apply { writeText("held\n") }
                } else incumbentReady,
            )
            writeExecutable(systemBin, oldBytes, 755)
            writeExecutable(registration, registrationBytes, 644)
            val live = File(dataLocal, "hapaneld-helper")
            if (staleBytes != null) writeExecutable(live, staleBytes, 700)
            val stage = File(dataLocal, ".hapaneld-helper.new")
            writeExecutable(stage, candidateBytes, 700)
            val dollar = '$'
            // Each stub records its call; a marker file makes it fail or deliver a signal to the shell.
            fun hook(name: String): String {
                val marker = File(root, name).absolutePath
                // One shot: a rollback that calls the same stub again is not interrupted a second time.
                return "if [ -f '$marker' ]; then signal=${dollar}(cat '$marker'); rm -f '$marker'; " +
                    "kill -\"${dollar}signal\" \"${dollar}PPID\"; sleep 1; fi"
            }
            writeExecutable(File(root, "system/bin/stop"), """
                #!/bin/sh
                echo "stop ${dollar}*" >> '${calls.absolutePath}'
                [ "${dollar}1" = hapaneld_helper ] || exit 2
                rm -f '${incumbentReady.absolutePath}'
                tamper='${File(root, "tamper-on-stop").absolutePath}'
                if [ -f "${dollar}tamper" ]; then echo tampered >> "${dollar}(cat "${dollar}tamper")"; rm -f "${dollar}tamper"; fi
                doomed='${File(root, "delete-on-stop").absolutePath}'
                if [ -f "${dollar}doomed" ]; then rm -f "${dollar}(cat "${dollar}doomed")"; rm -f "${dollar}doomed"; fi
                ${hook("signal-on-stop")}
                exit 0
            """.trimIndent() + "\n", 755)
            writeExecutable(File(root, "system/bin/start"), """
                #!/bin/sh
                echo "start ${dollar}*" >> '${calls.absolutePath}'
                [ "${dollar}1" = hapaneld_helper ] || exit 2
                [ -f '${registration.absolutePath}' ] && [ -f '${systemBin.absolutePath}' ] || exit 0
                '${systemBin.absolutePath}' >/dev/null 2>&1
            """.trimIndent() + "\n", 755)
            // SELinux stand-ins: labels live in a table keyed by inode and change time, so a file recreated
            // by a restore reads unlabeled until chcon labels it.
            val labels = File(root, "labels")
            val labelKey = "${dollar}(stat -c '%i@%z' \"${dollar}path\" | tr ' ' _)"
            labels.writeText(
                "${runCommand("stat -c '%i@%z' '${systemBin.absolutePath}' | tr ' ' _").output.trim()} $SYSTEM_BIN_LABEL\n" +
                    "${runCommand("stat -c '%i@%z' '${registration.absolutePath}' | tr ' ' _").output.trim()} $REGISTRATION_LABEL\n",
            )
            writeExecutable(File(root, "system/bin/ls"), """
                #!/bin/sh
                [ "${dollar}1" = -Zd ] || exit 2
                [ ! -f '${File(root, "ls-fails").absolutePath}' ] || exit 1
                path=${dollar}2
                [ -e "${dollar}path" ] || exit 1
                key=$labelKey
                label=${dollar}(grep "^${dollar}key " '${labels.absolutePath}' | tail -n 1 | cut -d' ' -f2)
                echo "${dollar}{label:-u:object_r:unlabeled:s0} ${dollar}path"
            """.trimIndent() + "\n", 755)
            writeExecutable(File(root, "system/bin/chcon"), """
                #!/bin/sh
                echo "chcon ${dollar}*" >> '${calls.absolutePath}'
                [ ! -f '${File(root, "chcon-fails").absolutePath}' ] || exit 1
                # A chcon that reports success but leaves the label unchanged.
                [ ! -f '${File(root, "chcon-noop").absolutePath}' ] || exit 0
                path=${dollar}2
                key=$labelKey
                echo "${dollar}key ${dollar}1" >> '${labels.absolutePath}'
            """.trimIndent() + "\n", 755)
            // A sync that kills its caller with SIGKILL, as BoundedLaunch's destroyForcibly or the app's death
            // does, the first time the shell condition in kill-on-sync holds. Tests put stubbin first on PATH.
            val realSync = runCommand("command -v sync").output.trim()
            File(root, "stubbin").mkdirs()
            writeExecutable(File(root, "stubbin/sync"), """
                #!/bin/bash
                marker='${File(root, "kill-on-sync").absolutePath}'
                if [ -f "${dollar}marker" ] && bash -c "${dollar}(cat "${dollar}marker")"; then
                  rm -f "${dollar}marker"; kill -9 "${dollar}PPID"; exit 0
                fi
                exec '$realSync'
            """.trimIndent() + "\n", 755)
            writeExecutable(File(root, "system/bin/mount"), """
                #!/bin/sh
                echo "mount ${dollar}*" >> '${calls.absolutePath}'
                case "${dollar}2" in
                  rw,remount)
                    ${hook("signal-on-remount-rw")}
                    [ ! -f '${File(root, "remount-fails").absolutePath}' ] || exit 32 ;;
                  ro,remount)
                    ${hook("signal-on-remount-ro")} ;;
                esac
                exit 0
            """.trimIndent() + "\n", 755)
            File(root, "proc/1").mkdirs()
            Files.createSymbolicLink(File(root, "proc/1/root").toPath(), root.toPath())
            File(root, "proc/1/mountinfo").writeText(
                "1 0 254:1 / / ro,relatime shared:1 - ext4 /dev/root ro\n" +
                    "25 1 0:25 / /system ro,relatime shared:2 - overlay overlay ro,lowerdir=/x\n",
            )
            val bootId = File(root, "proc/sys/kernel/random/boot_id").apply {
                parentFile!!.mkdirs()
                writeText("$FIRST_BOOT_ID\n")
            }
            test(CustodyFixture(
                root, dataLocal, systemBin, registration, live, stage,
                File(dataLocal, ".hapaneld-custody-archive"),
                File(dataLocal, ".hapaneld-helper.custody-migration"),
                calls, bootId, incumbentReady, oldBytes, candidateBytes, registrationBytes, staleBytes,
            ))
        } finally {
            // Unlink init's view first: a recursive delete must not walk back into the tree through it.
            Files.deleteIfExists(File(root, "proc/1/root").toPath())
            root.deleteRecursively()
        }
    }

    fun runCommandWithInput(command: String, input: String): CommandResult =
        ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                // A refused command exits before reading its input; the broken pipe is not the result.
                runCatching { process.outputStream.bufferedWriter().use { it.write(input) } }
                val output = process.inputStream.bufferedReader().readText()
                CommandResult(process.waitFor(), output)
            }
}
