package io.panelassistant.android.util

import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real supervisor, socket retirement, authenticated upload and fresh-install callers. */
class BundledHelperHandoffTest {
    @Test fun occupiedDescriptorUpdateRecoversThroughAppCallers() {
        val root = scratchDirectory("helper-handoff-").toFile()
        val helperRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "helper/test/replacement_handoff.py").isFile }
        fun command(vararg argv: String): Pair<Int, String> {
            val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "command did not finish: ${argv.first()}" }
            return process.exitValue() to output.trimEnd()
        }
        fun request(verb: String) = command("python3", File(helperRoot, "helper/test/replacement_handoff.py").path, "--request", root.path, verb).second
        fun waitUntil(description: String, predicate: () -> Boolean) {
            repeat(100) { if (predicate()) return; Thread.sleep(50) }
            fail(description)
        }
        try {
            val built = command("python3", File(helperRoot, "helper/test/replacement_handoff.py").path, root.path)
            assertEquals(built.second, 0, built.first)
            waitUntil("incumbent did not serve") { request("BUILDID") == "BUILDID ${"b".repeat(64)}" }
            val asset = File(root, "asset")
            val sha = BundledHelperShellFixtures.sha256(asset)
            File(root, "data/local/.hapaneld-helper.new").apply {
                asset.copyTo(this)
                java.nio.file.Files.setPosixFilePermissions(toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
            val nonce = "c".repeat(64)
            assertEquals("OK GUARDRETIRE 1 REQUESTED", request("GUARDRETIRE APP $nonce $sha ${"a".repeat(64)}"))
            waitUntil("replacement neither settled nor held") {
                request("BUILDID") == "BUILDID ${"a".repeat(64)}" || request("GUARDSTATUS") == "ERR HOLD retirement"
            }
            run {
                assertEquals("fixture must exercise a stalled installed incumbent", "ERR HOLD retirement", request("GUARDSTATUS"))
                val stage = File(root, "data/local/.hapaneld-helper.new")
                val bytes = stage.readBytes()
                val journal = File(root, "data/local/.hapaneld-guard-db/replacement.v1")
                val journalBytes = journal.readBytes()
                stage.outputStream().use { it.write(bytes.apply { this[0] = (this[0].toInt() xor 1).toByte() }) }
                assertEquals("unsafe stage must refuse recovery", 3, command(asset.path, "--reconcile-stale-replacement").first)
                assertEquals("refusal preserves old service", "BUILDID ${"b".repeat(64)}", request("BUILDID"))
                assertArrayEquals(journalBytes, journal.readBytes())
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
                stage.writeBytes(bytes)
                var recoveryCalls = 0
                var installCalls = 0
                val result = retryBundledHelperAfterStalledReplacement(BundledHelperInstaller.Result.FAILED, true,
                    statusProbe = { GuardDbMaintenanceClient.StatusProbe.Malformed },
                    reconcile = {
                        recoveryCalls++
                        val recovered = bundledHelperReplacementStatusAfterRecovery(asset, sha,
                            statusProbe = {
                                val reply = request("GUARDSTATUS")
                                if (reply.isEmpty()) GuardDbMaintenanceClient.StatusProbe.Unreachable
                                else GuardDbMaintenanceProtocol.parseStatus(reply)?.let(GuardDbMaintenanceClient.StatusProbe::Valid)
                                    ?: GuardDbMaintenanceClient.StatusProbe.Malformed
                            },
                            reconcile = { file, hash -> reconcileBundledHelperReplacement(file, hash, root.path) { shell, input, _ ->
                                val process = ProcessBuilder("bash", "-c", shell).redirectErrorStream(true).start()
                                process.outputStream.use { input.inputStream().use { source -> source.copyTo(it) } }
                                val output = process.inputStream.bufferedReader().readText()
                                output.takeIf { process.waitFor() == 0 }
                            } },
                        )
                        assertEquals("app recovery did not reacquire fresh install authority",
                            GuardDbMaintenanceClient.StatusProbe.Unreachable, recovered)
                        recovered == GuardDbMaintenanceClient.StatusProbe.Unreachable
                    },
                    retry = {
                        installCalls++
                        val installed = command("bash", "-c", bundledFreshHelperInstallCommand(sha, "a".repeat(64), root.path, polls = 5))
                        assertEquals(installed.second, 0, installed.first)
                        BundledHelperInstaller.Result.INSTALLED
                    },
                )
                assertEquals(BundledHelperInstaller.Result.INSTALLED, result)
                assertEquals(1, recoveryCalls)
                assertEquals(1, installCalls)
            }
            assertEquals("BUILDID ${"a".repeat(64)}", request("BUILDID"))
            assertEquals(BundledHelperShellFixtures.emptyStatus, request("GUARDSTATUS"))
            assertFalse(File(root, "data/local/.hapaneld-guard-db/replacement.v1").exists())
            assertFalse(File(root, "data/local/.hapaneld-helper.previous").exists())
            assertEquals(sha, BundledHelperShellFixtures.sha256(File(root, "data/local/hapaneld-helper")))
        } finally {
            // Select only the fixture's private socket peer and its exact supervisor parent.
            val live = File(root, "data/local/hapaneld-helper")
            if (live.exists()) {
                File("/proc").listFiles().orEmpty().filter { it.name.toLongOrNull() != null }.forEach { proc ->
                    val target = runCatching { java.nio.file.Files.readSymbolicLink(File(proc, "exe").toPath()).toString() }.getOrNull()
                    if (target == live.path || target == live.path + " (deleted)")
                        ProcessHandle.of(proc.name.toLong()).ifPresent { it.destroyForcibly() }
                }
            }
            root.deleteRecursively()
        }
    }
}
