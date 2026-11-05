package io.panelassistant.android.util

import io.panelassistant.android.util.BundledHelperShellFixtures.fakeHelper
import io.panelassistant.android.util.BundledHelperShellFixtures.incumbentBuild
import io.panelassistant.android.util.BundledHelperShellFixtures.nowSeconds
import io.panelassistant.android.util.BundledHelperShellFixtures.runCommandWithInput
import io.panelassistant.android.util.BundledHelperShellFixtures.runTakeoverCommand
import io.panelassistant.android.util.BundledHelperShellFixtures.setMode
import io.panelassistant.android.util.BundledHelperShellFixtures.sha256
import io.panelassistant.android.util.BundledHelperShellFixtures.staleJournal
import io.panelassistant.android.util.BundledHelperShellFixtures.stagedBuild
import io.panelassistant.android.util.BundledHelperShellFixtures.withFreshFiles
import io.panelassistant.android.util.BundledHelperShellFixtures.writeExecutable
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A panel whose helper socket does not answer gets the bundled helper, unless another authority could run. */
class BundledHelperFreshInstallTest {
    @Test fun `no helper socket is a fresh install and not maintenance owning recovery`() {
        // Issue #182: a fresh install has no helper to answer GUARDSTATUS. That used to read as
        // "maintenance owns recovery", so no panel installed by Panel Assistant ever got a helper.
        assertEquals(
            BundledHelperReplacementMode.FRESH_INSTALL,
            bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Unreachable),
        )
    }

    @Test fun `fresh install publishes and supervises the bundled helper and repeats after a reboot`() {
        withFreshFiles { fixture ->
            val command = bundledFreshHelperInstallCommand(
                sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
            )
            assertEquals(0, runTakeoverCommand(command))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
            assertFalse(fixture.stage.exists())
            assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())

            // A reboot keeps the /data/local file but not the process; the next start restages and relaunches.
            writeExecutable(fixture.stage, fixture.candidateBytes, 700)
            assertEquals(0, runTakeoverCommand(command))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
            assertFalse(fixture.stage.exists())
            // Staging may also find nothing to stage when the exact live copy is already there.
            assertEquals(0, runTakeoverCommand(command))
        }
    }

    @Test fun `first start on a panel with no helper stages then installs it`() {
        // The root-side sequence ensureCurrent issues when no helper answers: stage, then fresh install.
        withFreshFiles { fixture ->
            fixture.stage.delete()
            val hash = sha256(fixture.candidateBytes)
            val staged = runCommandWithInput(
                bundledHelperStageCommand(hash, filesystemRoot = fixture.root.absolutePath),
                fixture.candidateBytes,
            )
            assertEquals(staged.output, 0, staged.exitCode)
            assertTrue(staged.output, bundledHelperStaged(staged.output))
            assertEquals(0, runTakeoverCommand(bundledFreshHelperInstallCommand(
                hash, stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
        }
    }

    @Test fun `fresh install replaces an earlier build left at data local by a reboot`() {
        withFreshFiles { fixture ->
            writeExecutable(fixture.live, fakeHelper(incumbentBuild, candidate = true, starts = true), 700)
            assertEquals(0, runTakeoverCommand(bundledFreshHelperInstallCommand(
                sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
            assertFalse(fixture.stage.exists())
        }
    }

    @Test fun `fresh install refuses beside any provisioned helper or journal or takeover authority`() {
        val authorities = listOf(
            "system/bin/hapaneld-helper",
            "system/etc/init/hapaneld-helper.rc",
            "vendor/etc/init/hapaneld-helper.rc",
            "data/adb/hapaneld",
            "data/adb/service.d/hapaneld-helper.sh",
            "system/bin/hapaneld-ledd",
            "system/etc/init/hapaneld-ledd.rc",
            "data/local/.hapaneld-guard-db/replacement.v1",
            "data/local/.hapaneld-guard-db/.replacement.v1.tmp",
            "system/bin/.hapaneld-helper-upgrade",
            "system/bin/.hapaneld-helper-manual-upgrade",
            "data/adb/hapaneld/.helper-upgrade.marker",
            "data/adb/hapaneld/.helper-hybrid-upgrade.marker",
            "data/adb/hapaneld/.helper-manual-upgrade.marker",
            "data/local/.hapaneld-helper-manual-upgrade",
            "data/local/.hapaneld-helper.legacy-takeover",
            "data/local/.hapaneld-helper.legacy-takeover.tmp",
            "data/local/.hapaneld-helper.previous",
            "data/local/.hapaneld-helper.previous.tmp",
            "data/local/.hapaneld-guard-db/manifest.v1",
            "data/local/.hapaneld-guard-db/journal.v1",
            "data/local/.hapaneld-guard-db/draft.v1",
            "data/local/.hapaneld-guard-db/capture.v1",
            "data/local/.hapaneld-guard-db/.journal.v1.tmp",
            "data/local/.hapaneld-guard-db/a.apk",
            "data/local/.hapaneld-guard-db/unrecognised",
        )
        authorities.forEach { path ->
            withFreshFiles { fixture ->
                val authority = File(fixture.root, path).apply { parentFile!!.mkdirs(); writeText("kept\n") }
                assertEquals(path, 1, runTakeoverCommand(bundledFreshHelperInstallCommand(
                    sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                )))
                assertFalse(path, fixture.live.exists())
                assertFalse(path, fixture.stage.exists())
                assertEquals(path, "kept\n", authority.readText())
                assertFalse(path, File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
            }
        }
    }

    @Test fun `a panel the deleted provisioner set up gets the bundled helper back and keeps its files`() {
        // Rooted panels the provisioner set up: init cannot exec the /data/local helper it registered, so
        // the helper is gone after every reboot, and its rc and journal refused the app's. Some panels
        // carry the rc without a journal.
        listOf(false, true).forEach { journals ->
            withFreshFiles { fixture ->
                fixture.stage.delete()
                val kept = provisionedLayout(fixture.root, journals)
                val hash = sha256(fixture.candidateBytes)
                val staged = runCommandWithInput(
                    bundledHelperStageCommand(hash, fixture.root.absolutePath, appInstalledAtEpochSeconds = nowSeconds()),
                    fixture.candidateBytes,
                )
                assertEquals("journals=$journals " + staged.output, 0, staged.exitCode)
                assertEquals("journals=$journals", 0, runTakeoverCommand(bundledFreshHelperInstallCommand(
                    hash, stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                    appInstalledAtEpochSeconds = nowSeconds(),
                )))
                assertEquals(fixture.candidateBytes, fixture.live.readText())
                assertFalse(fixture.stage.exists())
                kept.forEach { (file, bytes) -> assertEquals(file.path, bytes, file.readText()) }
                assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
            }
        }
    }

    @Test fun `a provisioned layout that could start another helper still refuses a fresh install`() {
        val cases: List<Pair<String, (File) -> Unit>> = listOf(
            "rc naming the system copy" to { root ->
                writeExecutable(File(root, "system/etc/init/hapaneld-helper.rc"), systemCopyRegistration, 644)
            },
            "rc of the same length naming another binary" to { root ->
                writeExecutable(
                    File(root, "system/etc/init/hapaneld-helper.rc"),
                    provisionedDataLocalRegistration.replace("/data/local/hapaneld-helper", "/data/local/hapaneld-helpex"),
                    644,
                )
            },
            "rc with this path but writable" to { root ->
                writeExecutable(File(root, "system/etc/init/hapaneld-helper.rc"), provisionedDataLocalRegistration, 755)
            },
            "rc that is a link" to { root ->
                val target = File(root, "data/local/tmp/rc").apply { parentFile!!.mkdirs(); writeText(provisionedDataLocalRegistration) }
                setMode(target, 644)
                val registration = File(root, "system/etc/init/hapaneld-helper.rc").apply { assertTrue(delete()) }
                Files.createSymbolicLink(registration.toPath(), target.toPath())
            },
            "system copy without the rc" to { root -> File(root, "system/etc/init/hapaneld-helper.rc").delete() },
            "rc owned by another user" to { root ->
                val registration = File(root, "system/etc/init/hapaneld-helper.rc")
                assertEquals(0, ProcessBuilder("chown", "1000:1000", registration.path).start().waitFor())
            },
            "rc with a second link" to { root ->
                Files.createLink(File(root, "data/local/rc-copy").toPath(), File(root, "system/etc/init/hapaneld-helper.rc").toPath())
            },
            "a leftover rc beside it naming the system copy" to { root ->
                writeExecutable(File(root, "system/etc/init/hapaneld-helper.rc.hapaneld-recovery"), systemCopyRegistration, 644)
            },
            "a vendor rc beside the vendor path" to { root ->
                val sibling = File(root, "vendor/etc/init/hapaneld-helper.rc.new").apply { parentFile!!.mkdirs() }
                writeExecutable(sibling, provisionedDataLocalRegistration, 644)
            },
            "custody directory that is a link" to { root ->
                val custody = File(root, "data/adb/hapaneld")
                val moved = File(root, "data/adb/moved").also { custody.renameTo(it) }
                Files.createSymbolicLink(custody.toPath(), moved.toPath())
            },
            "systemless binary in the custody directory" to { root ->
                writeExecutable(File(root, "data/adb/hapaneld/hapaneld-helper"), "old helper\n", 755)
            },
            "a journal written this boot" to { root ->
                assertTrue(File(root, "system/bin/.hapaneld-helper-upgrade").setLastModified(System.currentTimeMillis()))
            },
        )
        cases.forEach { (name, arrange) ->
            withFreshFiles { fixture ->
                provisionedLayout(fixture.root)
                arrange(fixture.root)
                assertEquals(name, 1, runTakeoverCommand(bundledFreshHelperInstallCommand(
                    sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                    appInstalledAtEpochSeconds = nowSeconds() - 3600,
                )))
                assertFalse(name, fixture.live.exists())
                assertFalse(name, fixture.stage.exists())
            }
        }
    }

    @Test fun `a running system copy beside the provisioned rc still refuses a fresh install`() {
        listOf(false, true).forEach { linked ->
            withFreshFiles { fixture ->
                provisionedLayout(fixture.root)
                val systemCopy = File(fixture.root, "system/bin/hapaneld-helper").apply { delete() }
                val binary = if (linked) File(fixture.root, "data/local/tmp/running-helper") else systemCopy
                binary.parentFile!!.mkdirs()
                File("/bin/sleep").copyTo(binary)
                setMode(binary, 755)
                if (linked) Files.createSymbolicLink(systemCopy.toPath(), binary.toPath())
                val running = ProcessBuilder(binary.absolutePath, "60").start()
                try {
                    assertEquals("linked=$linked", 1, runTakeoverCommand(bundledFreshHelperInstallCommand(
                        sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                        appInstalledAtEpochSeconds = nowSeconds(),
                    )))
                    assertTrue(running.isAlive)
                    assertFalse(fixture.live.exists())
                } finally {
                    running.destroyForcibly()
                }
            }
        }
    }

    @Test fun `a helper process whose executable cannot be read keeps the system copy from being replaced`() {
        // stat of another process's executable can be refused; a process named like the helper is then
        // treated as the system copy. Any other unreadable process is not.
        listOf("hapaneld-helper" to 1, "unrelated" to 0).forEach { (name, expected) ->
            withFreshFiles { fixture ->
                provisionedLayout(fixture.root)
                val binary = File(fixture.root, "data/local/tmp/$name").apply { parentFile!!.mkdirs() }
                File("/bin/sleep").copyTo(binary)
                setMode(binary, 755)
                val pidFile = File(fixture.root, "running.pid")
                val running = ProcessBuilder(
                    "bash", "-c", "echo ${'$'}${'$'} > '${pidFile.absolutePath}'; exec '${binary.absolutePath}' 60",
                ).start()
                repeat(50) { if (pidFile.length() == 0L) Thread.sleep(100) }
                val pid = pidFile.readText().trim()
                val fakeBin = File(fixture.root, "fake-bin").apply { mkdirs() }
                writeExecutable(File(fakeBin, "stat"), """
                    #!/bin/bash
                    [ "${'$'}1" = -Lc ] && [ "${'$'}3" = /proc/$pid/exe ] && exit 1
                    exec /usr/bin/stat "${'$'}@"
                """.trimIndent() + "\n", 755)
                try {
                    assertEquals(name, expected, runTakeoverCommand(
                        bundledFreshHelperInstallCommand(
                            sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                            appInstalledAtEpochSeconds = nowSeconds(),
                        ),
                        mapOf("PATH" to "${fakeBin.absolutePath}:${System.getenv("PATH")}"),
                    ))
                    assertTrue(name, running.isAlive)
                } finally {
                    running.destroyForcibly()
                }
            }
        }
    }

    // An older rc that boots the /system/bin copy itself.
    private val systemCopyRegistration = """
        service hapaneld_helper /system/bin/hapaneld-helper
            class main
            user root
            group root
            seclabel u:r:su:s0
    """.trimIndent() + "\n"

    // Byte for byte the rc the deleted provisioner installed, as read back from rooted panels.
    private val provisionedDataLocalRegistration = """
        service hapaneld_helper /data/local/hapaneld-helper --supervise
            class main
            user root
            group root
            seclabel u:r:su:s0
    """.trimIndent() + "\n"

    /** The deleted provisioner's leftovers as found on the panels; returns each file with its bytes. */
    private fun provisionedLayout(root: File, journals: Boolean = true): Map<File, String> {
        val registration = File(root, "system/etc/init/hapaneld-helper.rc").apply { parentFile!!.mkdirs() }
        writeExecutable(registration, provisionedDataLocalRegistration, 644)
        // A later provisioner run left an identical copy beside it on some panels.
        val leftover = File(root, "system/etc/init/hapaneld-helper.rc.new")
        writeExecutable(leftover, provisionedDataLocalRegistration, 644)
        val systemCopy = File(root, "system/bin/hapaneld-helper").apply { parentFile!!.mkdirs() }
        writeExecutable(systemCopy, "provisioned helper 1.3.0\n", 755)
        val custody = File(root, "data/adb/hapaneld/.helper-recovery-0123456789abcdef.sysbin").apply { parentFile!!.mkdirs() }
        writeExecutable(custody, "previous helper\n", 600)
        val journalFiles = if (journals) listOf(
            staleJournal(root, "system/bin/.hapaneld-helper-upgrade"),
            staleJournal(root, "data/adb/hapaneld/.helper-upgrade.marker"),
        ) else emptyList()
        File(root, "data/local/.hapaneld-guard-db").mkdirs()
        File(root, "data/local/.hapaneld-guard-db/.owner.lock").createNewFile()
        return (listOf(registration, leftover, systemCopy, custody) + journalFiles).associateWith { it.readText() }
    }

    @Test fun `fresh install stops an earlier data local helper that is still running`() {
        withFreshFiles { fixture ->
            File("/bin/sleep").copyTo(fixture.live)
            setMode(fixture.live, 700)
            val running = ProcessBuilder(fixture.live.absolutePath, "60").start()
            try {
                assertEquals(0, runTakeoverCommand(bundledFreshHelperInstallCommand(
                    sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                )))
                assertTrue(running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(fixture.candidateBytes, fixture.live.readText())
            } finally {
                running.destroyForcibly()
            }
        }
    }

    @Test fun `fresh install without its candidate leaves a running data local helper alone`() {
        withFreshFiles { fixture ->
            fixture.stage.delete()
            File("/bin/sleep").copyTo(fixture.live)
            setMode(fixture.live, 700)
            val running = ProcessBuilder(fixture.live.absolutePath, "60").start()
            try {
                assertEquals(1, runTakeoverCommand(bundledFreshHelperInstallCommand(
                    sha256(fixture.candidateBytes), stagedBuild,
                    filesystemRoot = fixture.root.absolutePath, polls = 1,
                )))
                assertTrue(running.isAlive)
            } finally {
                running.destroyForcibly()
            }
        }
    }

    @Test fun `fresh install accepts a Guard directory holding only its owner lock`() {
        withFreshFiles { fixture ->
            File(fixture.root, "data/local/.hapaneld-guard-db").apply { mkdirs() }
                .resolve(".owner.lock").writeText("")
            assertEquals(0, runTakeoverCommand(bundledFreshHelperInstallCommand(
                sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
        }
    }

    @Test fun `fresh install refuses a Guard directory that is a link`() {
        withFreshFiles { fixture ->
            val elsewhere = File(fixture.root, "elsewhere").apply { mkdirs() }
            Files.createSymbolicLink(File(fixture.root, "data/local/.hapaneld-guard-db").toPath(), elsewhere.toPath())
            assertEquals(1, runTakeoverCommand(bundledFreshHelperInstallCommand(
                sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertFalse(fixture.live.exists())
        }
    }

    @Test fun `fresh install refuses when a stopped copy leaves Guard custody behind`() {
        withFreshFiles { fixture ->
            File("/bin/bash").copyTo(fixture.live)
            setMode(fixture.live, 700)
            val guard = File(fixture.root, "data/local/.hapaneld-guard-db").apply { mkdirs() }
            val earlier = fixture.live.readBytes()
            val running = ProcessBuilder(
                fixture.live.absolutePath, "-c",
                "trap 'echo held > \"${guard.absolutePath}/journal.v1\"; exit 0' TERM; while :; do sleep 0.1; done",
            ).start()
            try {
                Thread.sleep(300)
                assertEquals(1, runTakeoverCommand(bundledFreshHelperInstallCommand(
                    sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                )))
                assertTrue(File(guard, "journal.v1").isFile)
                assertTrue(earlier.contentEquals(fixture.live.readBytes()))
                assertFalse(fixture.stage.exists())
            } finally {
                running.destroyForcibly()
            }
        }
    }

    @Test fun `fresh install waits for a live transaction owner`() {
        withFreshFiles { fixture ->
            File(fixture.root, "dev/.hapaneld-helper-transaction.lock").apply { mkdirs() }
                .resolve("pid").writeText("${ProcessHandle.current().pid()}\n")
            assertEquals(75, runTakeoverCommand(bundledFreshHelperInstallCommand(
                sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertFalse(fixture.live.exists())
            assertEquals(fixture.candidateBytes, fixture.stage.readText())
        }
    }

    @Test fun `fresh candidate that cannot prove its build or Guard surface fails`() {
        listOf(
            fakeHelper(incumbentBuild, candidate = true, starts = true),
            fakeHelper(stagedBuild, candidate = true, starts = false),
        ).forEach { bytes ->
            withFreshFiles(candidateBytes = bytes) { fixture ->
                assertEquals(1, runTakeoverCommand(bundledFreshHelperInstallCommand(
                    sha256(fixture.stage), stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                )))
                assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
            }
        }
    }
}
