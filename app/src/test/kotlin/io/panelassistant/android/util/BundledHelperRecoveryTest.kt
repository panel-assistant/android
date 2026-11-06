package io.panelassistant.android.util

import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.BundledHelperShellFixtures.runCommandWithInput
import io.panelassistant.android.util.BundledHelperShellFixtures.sha256
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledHelperRecoveryTest {
    private fun fixture(block: (File, File) -> Unit) {
        val root = scratchDirectory("helper-recovery-").toFile()
        try {
            File(root, "data/local").apply {
                mkdirs()
                Files.setPosixFilePermissions(toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x--x"))
            }
            File(root, "dev").mkdirs()
            block(root, File(root, "asset"))
        } finally { root.deleteRecursively() }
    }

    private fun helper(root: File, asset: File, reply: String, code: Int = 0) {
        asset.writeText("""
            #!/bin/sh
            [ "${'$'}#" = 1 ] && [ "${'$'}1" = --reconcile-stale-replacement ] || exit 99
            [ ! -d '${root.absolutePath}/dev/.hapaneld-helper-transaction.lock' ] || exit 98
            printf '%s\n' '$reply'
            exit $code
        """.trimIndent() + "\n")
    }

    private fun reconcile(root: File, asset: File, hash: String = sha256(asset)): Boolean =
        reconcileBundledHelperReplacement(asset, hash, root.absolutePath) { command, input, timeout ->
            assertEquals(30_000L, timeout)
            val result = runCommandWithInput(command, input.readText())
            result.output.takeIf { result.exitCode == 0 }
        }

    @Test fun `live guarded helper retains its normal retirement authority without root reconciliation`() = fixture { _, asset ->
        val valid = GuardDbMaintenanceClient.StatusProbe.Valid(requireNotNull(
            GuardDbMaintenanceProtocol.parseStatus(BundledHelperShellFixtures.emptyStatus),
        ))
        for (status in listOf(valid, GuardDbMaintenanceClient.StatusProbe.Malformed)) {
            var probes = 0
            var recoveryAttempts = 0
            val observed = bundledHelperReplacementStatusAfterRecovery(asset, "a".repeat(64),
                statusProbe = { probes++; status },
                reconcile = { _, _ -> recoveryAttempts++; false },
            )
            assertEquals(status, observed)
            assertEquals(1, probes)
            assertEquals(0, recoveryAttempts)
        }
        assertEquals(BundledHelperReplacementMode.GUARDED_RETIRE, bundledHelperReplacementMode(valid))
    }

    @Test fun `unreachable and released legacy helper reacquire authority only after authenticated recovery`() = fixture { root, asset ->
        helper(root, asset, "STALE_REPLACEMENT RECONCILED")
        for (initial in listOf(GuardDbMaintenanceClient.StatusProbe.Unsupported, GuardDbMaintenanceClient.StatusProbe.Unreachable)) {
            var probes = 0
            val observed = bundledHelperReplacementStatusAfterRecovery(asset, sha256(asset),
                statusProbe = { if (probes++ == 0) initial else GuardDbMaintenanceClient.StatusProbe.Unreachable },
                reconcile = { input, hash -> reconcile(root, input, hash) },
            )
            assertEquals(2, probes)
            assertEquals(BundledHelperReplacementMode.FRESH_INSTALL, bundledHelperReplacementMode(requireNotNull(observed)))
        }
        helper(root, asset, "STALE_REPLACEMENT HOLD", 1)
        var probes = 0
        assertEquals(null, bundledHelperReplacementStatusAfterRecovery(asset, sha256(asset),
            statusProbe = { probes++; GuardDbMaintenanceClient.StatusProbe.Unsupported },
            reconcile = { input, hash -> reconcile(root, input, hash) },
        ))
        assertEquals(1, probes)
    }

    @Test fun `authenticated recovery runs fixed verb outside upload lock and preserves candidate custody`() = fixture { root, asset ->
        val local = File(root, "data/local")
        val retained = listOf("hapaneld-helper", ".hapaneld-helper.new", ".hapaneld-helper.replace.journal")
            .associateWith { "retained $it\n" }
        retained.forEach { (name, bytes) -> File(local, name).writeText(bytes) }
        for (reply in listOf("STALE_REPLACEMENT NONE", "STALE_REPLACEMENT RECONCILED")) {
            helper(root, asset, reply)
            assertTrue(reconcile(root, asset))
            val recovery = File(local, ".hapaneld-helper.recovery-${sha256(asset)}")
            assertEquals(asset.readText(), recovery.readText())
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(recovery.toPath())))
            retained.forEach { (name, bytes) -> assertEquals(bytes, File(local, name).readText()) }
            assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())
        }
    }

    @Test fun `hold malformed and failed replies cannot authorize fresh staging`() = fixture { root, asset ->
        for ((reply, code) in listOf("STALE_REPLACEMENT HOLD" to 1, "STALE_REPLACEMENT NONE" to 17,
                "STALE_REPLACEMENT HOLD" to 0, "STALE_REPLACEMENT NONE extra" to 0, "" to 0)) {
            helper(root, asset, reply, code)
            assertFalse("$reply exit=$code", reconcile(root, asset))
        }
        helper(root, asset, "STALE_REPLACEMENT NONE")
        assertFalse(reconcileBundledHelperReplacement(asset, sha256(asset)) { _, _, _ -> null })
    }

    @Test fun `wrong authenticated bytes never execute and preserve existing stage`() = fixture { root, asset ->
        helper(root, asset, "STALE_REPLACEMENT NONE")
        val stage = File(root, "data/local/.hapaneld-helper.new").apply { writeText("original candidate") }
        assertFalse(reconcile(root, asset, "0".repeat(64)))
        assertEquals("original candidate", stage.readText())
        assertFalse(File(root, "data/local/.hapaneld-helper.recovery-${"0".repeat(64)}").exists())
        assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())
    }

    @Test fun `foreign recovery slot and upload are preserved without executing them`() = fixture { root, asset ->
        helper(root, asset, "STALE_REPLACEMENT NONE")
        val recovery = File(root, "data/local/.hapaneld-helper.recovery-${sha256(asset)}")
        for (target in listOf(recovery, File(recovery.path + ".upload"))) {
            target.writeText("foreign custody")
            assertFalse(reconcile(root, asset))
            assertEquals("foreign custody", target.readText())
            assertTrue(target.delete())
        }
        val victim = File(root, "victim").apply { writeText("do not touch") }
        Files.createSymbolicLink(recovery.toPath(), victim.toPath())
        assertFalse(reconcile(root, asset))
        assertEquals("do not touch", victim.readText())
    }

    @Test fun `existing transaction lock with dead pid or unknown siblings is never reclaimed by upload`() = fixture { root, asset ->
        helper(root, asset, "STALE_REPLACEMENT NONE")
        val lock = File(root, "dev/.hapaneld-helper-transaction.lock").apply { mkdir() }
        File(lock, "pid").writeText("2147483647\n")
        File(lock, "foreign").writeText("foreign custody")
        assertFalse(reconcile(root, asset))
        assertTrue("the preexisting pid is retained", File(lock, "pid").isFile)
        assertEquals("2147483647\n", File(lock, "pid").readText())
        assertEquals("foreign custody", File(lock, "foreign").readText())
    }

    @Test fun `foreign writable recovery parent cannot publish a privileged executable`() = fixture { root, asset ->
        helper(root, asset, "STALE_REPLACEMENT NONE")
        val local = File(root, "data/local")
        Files.setPosixFilePermissions(local.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"))
        assertFalse(reconcile(root, asset))
        assertTrue(local.listFiles().orEmpty().isEmpty())
        assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())
    }
}
