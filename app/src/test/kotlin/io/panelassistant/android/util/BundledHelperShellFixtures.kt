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
    ): String = """
        #!/bin/sh
        if [ "${'$'}1" = --replacement-safe ]; then
          ${if (signalOnReplacementCheck) "kill -TERM \"${'$'}(ps -o ppid= -p \"${'$'}PPID\" | tr -d ' ')\"; sleep 1" else ":"}
          ${if (candidate) "echo REPLACE_SAFE; exit 0" else "exit 2"}
        fi
        if [ "${'$'}1" = --supervise ]; then
          ${if (signalOnSupervise) "kill -TERM \"${'$'}PPID\"; sleep 1" else ":"}
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
