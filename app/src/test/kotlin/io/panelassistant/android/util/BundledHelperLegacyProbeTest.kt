package io.panelassistant.android.util

import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.BundledHelperShellFixtures.runCommandWithInput
import io.panelassistant.android.util.BundledHelperShellFixtures.sha256
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BundledHelperLegacyProbeTest {
    private val oldBuild = "b".repeat(64)
    private val candidateBuild = "a".repeat(64)

    private fun replies(first: String = oldBuild, status: String = "ERR", last: String = first): String =
        "BUILDID $first\nPROBE_EXIT=0\n$status\nPROBE_EXIT=0\nBUILDID $last\nPROBE_EXIT=0\n"

    @Test fun `only exact released legacy replies confer root takeover identity`() {
        val asset = File.createTempFile("legacy-probe-parser-", ".bin")
        try {
            asset.writeText("candidate")
            fun probe(raw: String?) = probeBundledLegacyHelper(asset, "c".repeat(64), candidateBuild,
                execute = { _, timeout -> assertEquals(15_000L, timeout); raw },
            )
            assertEquals(BundledHelperLegacyProbe.Legacy(oldBuild), probe(replies()))
            assertEquals(BundledHelperLegacyProbe.Absent, probe("PROBE_EXIT=1\n".repeat(3)))
            for (raw in listOf(
                null, "", replies(status = BundledHelperShellFixtures.emptyStatus),
                replies(status = "ERR HOLD retirement"), replies(status = "ERR "),
                replies(status = "ERR\n"), replies(status = "ERR\r"),
                replies(first = oldBuild.uppercase()), replies(first = "short"),
                replies(last = "c".repeat(64)), replies(first = candidateBuild),
                replies() + "\n", " " + replies(), replies().dropLast(1),
                replies().replaceFirst("PROBE_EXIT=0", "PROBE_EXIT=1"),
                "PROBE_EXIT=1\nERR\nPROBE_EXIT=0\nPROBE_EXIT=1\n",
            )) assertEquals("unexpected admission for $raw", BundledHelperLegacyProbe.Refused, probe(raw))
        } finally { asset.delete() }
    }

    private data class Fixture(val root: File, val asset: File, val recovery: File, val calls: File)

    private fun fixture(block: (Fixture) -> Unit) {
        val root = scratchDirectory("legacy-root-probe-").toFile()
        try {
            val local = File(root, "data/local").apply {
                mkdirs()
                Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwxr-x--x"))
            }
            val calls = File(root, "calls")
            val asset = File(root, "asset").apply {
                writeText("""
                    #!/bin/sh
                    # original candidate
                    [ "${'$'}#" = 2 ] && [ "${'$'}1" = --request ] || exit 9
                    printf '%s\n' "${'$'}2" >> '${calls.absolutePath}'
                    case "${'$'}2" in
                      BUILDID) cat '${root.absolutePath}/build-reply' ;;
                      GUARDSTATUS) cat '${root.absolutePath}/status-reply' ;;
                      *) exit 9 ;;
                    esac
                """.trimIndent() + "\n")
            }
            val recovery = File(local, ".hapaneld-helper.recovery-${sha256(asset)}").apply {
                writeBytes(asset.readBytes())
                Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwx------"))
            }
            File(root, "build-reply").writeText("BUILDID $oldBuild\n")
            File(root, "status-reply").writeText("ERR\n")
            block(Fixture(root, asset, recovery, calls))
        } finally { root.deleteRecursively() }
    }

    private fun probe(f: Fixture): BundledHelperLegacyProbe = probeBundledLegacyHelper(
        f.asset, sha256(f.asset), candidateBuild, f.root.absolutePath,
    ) { command, _ ->
        val result = runCommandWithInput(command, "")
        result.output.takeIf { result.exitCode == 0 }
    }

    @Test fun `authenticated candidate executes only three read only probes in order`() = fixture { f ->
        assertEquals(BundledHelperLegacyProbe.Legacy(oldBuild), probe(f))
        assertEquals("BUILDID\nGUARDSTATUS\nBUILDID\n", f.calls.readText())
        assertEquals(f.asset.readText(), f.recovery.readText())
        assertFalse(File(f.root, "data/local/hapaneld-helper").exists())
        assertFalse(File(f.root, "data/local/.hapaneld-helper.legacy-takeover").exists())
    }

    @Test fun `shell preserves wire framing instead of trimming extra or missing newlines`() {
        for (status in listOf("ERR\n\n", "ERR", "ERR\r\n", " ERR\n")) fixture { f ->
            File(f.root, "status-reply").writeText(status)
            assertEquals(BundledHelperLegacyProbe.Refused, probe(f))
        }
    }

    @Test fun `changed candidate custody refuses before executing any probe`() {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.recovery.writeText(it.recovery.readText().replace("original", "modified")) },
            { Files.setPosixFilePermissions(it.recovery.toPath(), PosixFilePermissions.fromString("rwxr-xr-x")) },
            { Files.createLink(File(it.root, "alias").toPath(), it.recovery.toPath()) },
            {
                Files.setPosixFilePermissions(it.asset.toPath(), PosixFilePermissions.fromString("rwx------"))
                it.recovery.delete()
                Files.createSymbolicLink(it.recovery.toPath(), it.asset.toPath())
            },
            { Files.setAttribute(it.recovery.toPath(), "unix:gid", 12345) },
            { Files.setPosixFilePermissions(requireNotNull(it.recovery.parentFile).toPath(), PosixFilePermissions.fromString("rwxrwxrwx")) },
        )
        for (mutate in mutations) fixture { f ->
            mutate(f)
            assertEquals(BundledHelperLegacyProbe.Refused, probe(f))
            assertFalse(f.calls.exists())
        }
    }

    @Test fun `candidate custody is rechecked between socket probes`() = fixture { f ->
        val original = f.asset.readText()
        f.asset.writeText(original.replace(
            "BUILDID) cat", "BUILDID) chmod 755 \"${'$'}0\"; cat",
        ))
        val changed = File(f.recovery.parentFile, ".hapaneld-helper.recovery-${sha256(f.asset)}")
        changed.writeBytes(f.asset.readBytes())
        Files.setPosixFilePermissions(changed.toPath(), PosixFilePermissions.fromString("rwx------"))
        assertEquals(BundledHelperLegacyProbe.Refused, probe(f))
        assertEquals("BUILDID\n", f.calls.readText())
    }
}
