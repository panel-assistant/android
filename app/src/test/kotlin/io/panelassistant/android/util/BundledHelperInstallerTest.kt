package io.panelassistant.android.util

import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.BundledHelperShellFixtures.STALE_JOURNAL_MS
import io.panelassistant.android.util.BundledHelperShellFixtures.emptyStatus
import io.panelassistant.android.util.BundledHelperShellFixtures.fakeHelper
import io.panelassistant.android.util.BundledHelperShellFixtures.foreignHelperJournal
import io.panelassistant.android.util.BundledHelperShellFixtures.incumbentBuild
import io.panelassistant.android.util.BundledHelperShellFixtures.nowSeconds
import io.panelassistant.android.util.BundledHelperShellFixtures.runCommandWithInput
import io.panelassistant.android.util.BundledHelperShellFixtures.runTakeoverCommand
import io.panelassistant.android.util.BundledHelperShellFixtures.setMode
import io.panelassistant.android.util.BundledHelperShellFixtures.sha256
import io.panelassistant.android.util.BundledHelperShellFixtures.stagedBuild
import io.panelassistant.android.util.BundledHelperShellFixtures.staleJournal
import io.panelassistant.android.util.BundledHelperShellFixtures.writeExecutable

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledHelperInstallerTest {
    @Test fun `an already-current helper is admitted without probing root at all`() {
        var probes = 0
        val result = bundledHelperAdmission(
            bundledBuildMatches = true,
            companionSupported = true,
            guardSupported = true,
            rootObserved = { probes++; true },
        )
        assertEquals(BundledHelperInstaller.Result.ALREADY_CURRENT, result)
        assertEquals(0, probes)
    }

    @Test fun `equal build without canonical Guard support requires reprovision without probing root`() {
        var probes = 0
        val result = bundledHelperAdmission(
            bundledBuildMatches = true,
            companionSupported = true,
            guardSupported = false,
            rootObserved = { probes++; true },
        )
        assertEquals(BundledHelperInstaller.Result.REPROVISION_REQUIRED, result)
        assertEquals(0, probes)
        assertFalse(bundledHelperIsCanonical(
            bundledBuildMatches = true,
            companionSupported = true,
            guardSupported = false,
        ))
    }

    @Test fun `a panel with no observed root is skipped, which is the helper-only protection`() {
        // A helper-only panel is exactly one where the app has no su of its own, so observing root is
        // what keeps this migration away from the daemon such a panel depends on.
        assertEquals(
            BundledHelperInstaller.Result.SKIPPED,
            bundledHelperAdmission(
                bundledBuildMatches = false,
                companionSupported = true,
                guardSupported = true,
                rootObserved = { false },
            ),
        )
    }

    @Test fun `observed root admits a different-build canonical helper migration`() {
        // The regression this pins: `app_can_su` is an attempt-order hint written against the firmware
        // the profile author saw. An owner who flashes a rooted build keeps that stock-derived profile,
        // and vetoing on it denied the helper to a panel that plainly had root. Admission takes no
        // profile argument at all, so the hint cannot re-enter as a veto by being passed in.
        assertNull(bundledHelperAdmission(
            bundledBuildMatches = false,
            companionSupported = true,
            guardSupported = true,
            rootObserved = { true },
        ))
    }

    private val supervisedCaps =
        "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"

    @Test fun `ABI selection follows supported runtime preference`() {
        assertEquals("hapaneld-helper-arm64", helperAssetName(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("hapaneld-helper-arm", helperAssetName(listOf("armeabi-v7a")))
        assertNull(helperAssetName(listOf("x86_64")))
    }

    @Test fun `Gradle and portable helper identity implementations agree`() {
        val sourceId = listOf(java.io.File("../helper/source-id.sh"), java.io.File("helper/source-id.sh"))
            .first { it.isFile }
        val process = ProcessBuilder("bash", sourceId.absolutePath).redirectErrorStream(true).start()
        val shellId = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor())
        assertEquals(io.panelassistant.android.BuildConfig.HELPER_BUILD_ID, shellId)
    }

    @Test fun `stage command accepts the upload limit and rejects larger authenticated bytes`() {
        val root = scratchDirectory("bundled-helper-stage-limit-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            File(root, "dev").mkdirs()
            val stage = File(dataLocal, ".hapaneld-helper.new")
            val limit = 16 * 1024 * 1024
            for (size in listOf(limit, limit + 1)) {
                stage.writeText("retained stage")
                val candidate = "x".repeat(size)
                val result = runCommandWithInput(
                    bundledHelperStageCommand(sha256(candidate), root.absolutePath), candidate,
                )
                if (size == limit) {
                    assertEquals(result.output, 0, result.exitCode)
                    assertEquals(candidate, stage.readText())
                    assertTrue(result.output.lineSequence().any { it == "STAGED_OK" })
                } else {
                    assertEquals(result.output, 1, result.exitCode)
                    assertEquals("retained stage", stage.readText())
                    assertFalse(result.output.lineSequence().any { it == "STAGED_OK" })
                }
                assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())
                assertTrue(dataLocal.listFiles().orEmpty().none { it.name.startsWith(".hapaneld-helper.app-stage-") })
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `stage command replaces only an unowned fixed orphan after authenticating full upload`() {
        val root = scratchDirectory("bundled-helper-stage-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            File(root, "dev").mkdirs()
            val stage = File(dataLocal, ".hapaneld-helper.new").apply { writeText("partial") }
            val recordTmp = File(dataLocal, ".hapaneld-helper.legacy-takeover.tmp")
                .apply { writeText("partial preauthority record") }
            setMode(recordTmp, 600)
            val candidate = "authenticated candidate bytes\n"
            val command = bundledHelperStageCommand(sha256(candidate), root.absolutePath)
            val result = runCommandWithInput(command, candidate)
            assertEquals(0, result.exitCode)
            assertTrue(result.output.lineSequence().any { it == "STAGED_OK" })
            assertEquals(candidate, stage.readText())
            assertFalse(recordTmp.exists())
            assertEquals(setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ), Files.getPosixFilePermissions(stage.toPath()))
            assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())
            assertTrue(dataLocal.listFiles().orEmpty().none { it.name.startsWith(".hapaneld-helper.app-stage-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `stage command rejects mismatched upload bytes without replacing the fixed stage`() {
        val root = scratchDirectory("bundled-helper-stage-digest-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            File(root, "dev").mkdirs()
            val stage = File(dataLocal, ".hapaneld-helper.new").apply { writeText("retained stage") }
            val result = runCommandWithInput(
                bundledHelperStageCommand(sha256("expected candidate"), root.absolutePath),
                "different candidate",
            )
            assertEquals(result.output, 1, result.exitCode)
            assertEquals("retained stage", stage.readText())
            assertFalse(result.output.lineSequence().any { it == "STAGED_OK" })
            assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())
            assertTrue(dataLocal.listFiles().orEmpty().none { it.name.startsWith(".hapaneld-helper.app-stage-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `stage command preserves fixed stage when lock or native authority is active`() {
        val root = scratchDirectory("bundled-helper-stage-busy-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            val dev = File(root, "dev").apply { mkdirs() }
            val stage = File(dataLocal, ".hapaneld-helper.new").apply { writeText("incumbent stage") }
            val candidate = "new candidate\n"
            val command = bundledHelperStageCommand(sha256(candidate), root.absolutePath)
            val lock = File(dev, ".hapaneld-helper-transaction.lock").apply { mkdirs() }
            File(lock, "pid").writeText(java.lang.management.ManagementFactory
                .getRuntimeMXBean().name.substringBefore('@'))
            assertEquals(75, runCommandWithInput(command, candidate).exitCode)
            assertEquals("incumbent stage", stage.readText())

            lock.deleteRecursively()
            File(root, "data/local/.hapaneld-guard-db").mkdirs()
            File(root, "data/local/.hapaneld-guard-db/replacement.v1").writeText("authority\n")
            assertEquals(75, runCommandWithInput(command, candidate).exitCode)
            assertEquals("incumbent stage", stage.readText())
            assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())

            File(root, "data/local/.hapaneld-guard-db/replacement.v1").delete()
            val foreignTmp = File(dataLocal, ".hapaneld-helper.legacy-takeover.tmp")
                .apply { writeText("foreign mode") }
            setMode(foreignTmp, 644)
            assertEquals(75, runCommandWithInput(command, candidate).exitCode)
            assertTrue(foreignTmp.isFile)
            assertEquals("incumbent stage", stage.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `every foreign journal blocks staging without changing retained custody`() {
        foreignHelperJournals.forEach { path ->
            val root = scratchDirectory("bundled-helper-foreign-stage-").toFile()
            try {
                val dataLocal = File(root, "data/local").apply { mkdirs() }
                File(root, "dev").mkdirs()
                val stage = File(dataLocal, ".hapaneld-helper.new").apply { writeText("retained candidate") }
                val journal = foreignHelperJournal(root, path)
                val recordTmp = File(dataLocal, ".hapaneld-helper.legacy-takeover.tmp")
                    .apply { writeText("partial preauthority record") }
                setMode(recordTmp, 600)
                val candidate = "new candidate\n"
                val command = bundledHelperStageCommand(sha256(candidate), root.absolutePath)
                val result = runCommandWithInput(command, candidate)
                assertEquals(path, 75, result.exitCode)
                assertEquals(path, "retained candidate", stage.readText())
                assertEquals(path, "retained authority\n", journal.readText())
                assertEquals(path, "partial preauthority record", recordTmp.readText())
                assertFalse(path, result.output.contains("STAGED_OK"))
                assertFalse(path, File(root, "dev/.hapaneld-helper-transaction.lock").exists())

                assertTrue(path, journal.delete())
                assertEquals(path, 0, runCommandWithInput(command, candidate).exitCode)
                assertEquals(path, candidate, stage.readText())
                assertFalse(path, recordTmp.exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun `every foreign journal blocks takeover without changing retained custody`() {
        foreignHelperJournals.forEach { path ->
            withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
                val journal = foreignHelperJournal(fixture.root, path)
                val command = bundledLegacyHelperTakeoverCommand(
                    sha256(fixture.stage), stagedBuild, incumbentBuild,
                    filesystemRoot = fixture.root.absolutePath, polls = 1,
                )
                assertEquals(path, 75, runTakeoverCommand(command))
                assertEquals(path, fixture.candidateBytes, fixture.stage.readText())
                assertEquals(path, fixture.oldBytes, fixture.oldBin.readText())
                assertEquals(path, fixture.oldRegistration, fixture.registration.readText())
                assertEquals(path, "retained authority\n", journal.readText())
                assertTrue(path, fixture.incumbentReady.isFile)
                assertFalse(path, fixture.live.exists())
                assertFalse(path, File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").exists())
                assertFalse(path, File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())

                assertTrue(path, journal.delete())
                assertEquals(path, 0, runTakeoverCommand(command))
                assertEquals(path, fixture.candidateBytes, fixture.live.readText())
            }
        }
    }

    @Test fun `a stale journal the deleted installers left is retired in place and staging proceeds`() {
        deletedInstallerJournals.forEach { path ->
            val root = scratchDirectory("bundled-helper-stale-journal-").toFile()
            try {
                val dataLocal = File(root, "data/local").apply { mkdirs() }
                File(root, "dev").mkdirs()
                val journal = staleJournal(root, path)
                val candidate = "new candidate\n"
                val result = runCommandWithInput(
                    bundledHelperStageCommand(
                        sha256(candidate), root.absolutePath, appInstalledAtEpochSeconds = nowSeconds(),
                    ),
                    candidate,
                )
                assertEquals(path + result.output, 0, result.exitCode)
                assertTrue(path, bundledHelperStaged(result.output))
                assertEquals(path, candidate, File(dataLocal, ".hapaneld-helper.new").readText())
                assertEquals(path, "retained authority\n", journal.readText())
                assertEquals(path, STALE_JOURNAL_MS, journal.lastModified())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun `a journal from this boot or this install or not a plain root file still blocks staging`() {
        val installed = nowSeconds()
        val cases: List<Pair<String, (File) -> Unit>> = listOf(
            "written this boot" to { root -> foreignHelperJournal(root, "system/bin/.hapaneld-helper-upgrade") },
            "written after this install" to { root -> staleJournal(root, "system/bin/.hapaneld-helper-upgrade") },
            "a stale link to a stale journal" to { root ->
                val target = staleJournal(root, "data/local/tmp/old-journal")
                val link = File(root, "system/bin/.hapaneld-helper-upgrade").apply { parentFile!!.mkdirs() }
                Files.createSymbolicLink(link.toPath(), target.toPath())
                assertEquals(0, ProcessBuilder("touch", "-h", "-d", "@${STALE_JOURNAL_MS / 1000}", link.path).start().waitFor())
            },
            "owned by another user" to { root ->
                val journal = staleJournal(root, "data/local/.hapaneld-helper-manual-upgrade")
                assertEquals(0, ProcessBuilder("chown", "1000:1000", journal.path).start().waitFor())
            },
            "a second link to a stale journal" to { root ->
                val journal = staleJournal(root, "data/adb/hapaneld/.helper-upgrade.marker")
                Files.createLink(File(root, "data/local/journal-copy").toPath(), journal.toPath())
            },
            "Guard replacement, whatever its age" to { root ->
                staleJournal(root, "data/local/.hapaneld-guard-db/replacement.v1")
            },
        )
        cases.forEach { (name, arrange) ->
            val root = scratchDirectory("bundled-helper-live-journal-").toFile()
            try {
                val dataLocal = File(root, "data/local").apply { mkdirs() }
                File(root, "dev").mkdirs()
                arrange(root)
                val candidate = "new candidate\n"
                // Each case fails exactly one condition: "this boot" is still older than an install after it.
                val installedAt = when (name) {
                    "written after this install" -> STALE_JOURNAL_MS / 1000 - 60
                    "written this boot" -> installed + 3600
                    else -> installed
                }
                val result = runCommandWithInput(
                    bundledHelperStageCommand(sha256(candidate), root.absolutePath, appInstalledAtEpochSeconds = installedAt),
                    candidate,
                )
                assertEquals(name, 75, result.exitCode)
                assertFalse(name, File(dataLocal, ".hapaneld-helper.new").exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    // Independent of the production list: what only the installers deleted on 2026-10-08 wrote.
    private val deletedInstallerJournals = listOf(
        "system/bin/.hapaneld-helper-upgrade",
        "system/bin/.hapaneld-helper-manual-upgrade",
        "data/adb/hapaneld/.helper-upgrade.marker",
        "data/adb/hapaneld/.helper-hybrid-upgrade.marker",
        "data/adb/hapaneld/.helper-manual-upgrade.marker",
        "data/local/.hapaneld-helper-manual-upgrade",
    )

    // Independent of the production list: removing an authority must not remove its test case.
    private val foreignHelperJournals = listOf(
        "data/local/.hapaneld-guard-db/replacement.v1",
        "data/local/.hapaneld-guard-db/.replacement.v1.tmp",
        "system/bin/.hapaneld-helper-upgrade",
        "system/bin/.hapaneld-helper-manual-upgrade",
        "data/adb/hapaneld/.helper-upgrade.marker",
        "data/adb/hapaneld/.helper-hybrid-upgrade.marker",
        "data/adb/hapaneld/.helper-manual-upgrade.marker",
        "data/local/.hapaneld-helper-manual-upgrade",
    )

    @Test fun `interrupted upload removes only its unique partial and releases admission`() {
        val root = scratchDirectory("bundled-helper-stage-signal-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            File(root, "dev").mkdirs()
            val stage = File(dataLocal, ".hapaneld-helper.new").apply { writeText("fixed authority") }
            val hash = "c".repeat(64)
            val process = ProcessBuilder(
                "bash", "-c", bundledHelperStageCommand(hash, root.absolutePath),
            ).redirectErrorStream(true).start()
            val writer = Thread {
                runCatching {
                    process.outputStream.use { output ->
                        while (true) {
                            output.write(ByteArray(4096) { 0x5a })
                            output.flush()
                            Thread.sleep(2)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val upload = File(dataLocal, ".hapaneld-helper.app-stage-$hash")
            repeat(100) {
                if (upload.exists()) return@repeat
                Thread.sleep(10)
            }
            assertTrue(upload.exists())
            process.destroy()
            runCatching { process.outputStream.close() }
            writer.join(2_000)
            runCatching { process.inputStream.bufferedReader().readText() }
            assertTrue(process.waitFor() != 0)
            assertEquals("fixed authority", stage.readText())
            assertFalse(upload.exists())
            assertFalse(File(root, "dev/.hapaneld-helper-transaction.lock").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stage command rejects non digest interpolation`() {
        bundledHelperStageCommand("a; reboot")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `legacy takeover rejects non digest build interpolation`() {
        bundledLegacyHelperTakeoverCommand("c".repeat(64), "a; reboot", incumbentBuild)
    }

    @Test fun `released base system systemless and hybrid topologies migrate binary and registration`() {
        TakeoverTopology.entries.forEach { topology ->
            withTakeoverFiles(topology, candidateStarts = true) { fixture ->
            val command = bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath,
                polls = 1,
            )
            assertEquals(BundledHelperInstaller.Result.INSTALLED, installBundledHelperReplacement(
                BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER,
                stagedBuild, incumbentBuild, null, null,
                stage = { true },
                guarded = { error("released helper cannot retire through Guard") },
                legacy = { runTakeoverCommand(command) == 0 },
                fresh = { error("released helper is not a fresh install") },
                cleanup = { error("released recovery record must remain") },
            ))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertFalse(fixture.stage.exists())
            assertTrue(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").isFile)
            assertFalse(
                "boot service stopped before the takeover record was durable",
                File(fixture.root, "stopped-before-record").exists(),
            )
            assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
            }
        }
    }

    @Test fun `released standalone system registration is accepted by its exact base hash`() {
        withTakeoverFiles(
            TakeoverTopology.SYSTEM,
            candidateStarts = true,
            registrationBytes = releasedBaseSystemRegistration,
        ) { fixture ->
            assertEquals(
                "b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4",
                sha256(fixture.registration),
            )
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(releasedBaseSystemRegistration, fixture.registration.readText())
        }
    }

    @Test fun `candidate supervision failure restores exact binary and registration`() {
        withTakeoverFiles(TakeoverTopology.HYBRID, candidateStarts = false) { fixture ->
            val oldSha = sha256(fixture.oldBin)
            val registrationSha = sha256(fixture.registration)
            val command = bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath,
                polls = 1,
            )
            assertEquals(1, runTakeoverCommand(command))
            assertEquals(oldSha, sha256(fixture.oldBin))
            assertEquals(registrationSha, sha256(fixture.registration))
            assertEquals(fixture.candidateBytes, fixture.stage.readText())
            assertFalse(fixture.live.exists())
            assertFalse(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").exists())
            assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
        }
    }

    @Test fun `live shared transaction owner blocks takeover before custody or swap`() {
        withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
            val lock = File(fixture.root, "dev/.hapaneld-helper-transaction.lock").apply { mkdir() }
            File(lock, "pid").writeText("1\n")
            val command = bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath,
                polls = 1,
            )
            assertEquals(75, runTakeoverCommand(command))
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.candidateBytes, fixture.stage.readText())
            assertTrue(lock.isDirectory)
            assertFalse(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").exists())
        }
    }

    @Test fun `ambiguous released topology is refused before custody`() {
        withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
            val alternateBin = File(fixture.root, "data/adb/hapaneld/hapaneld-helper")
            alternateBin.parentFile!!.mkdirs()
            writeExecutable(alternateBin, fixture.oldBytes, 755)
            val alternateRegistration = File(fixture.root, "data/adb/service.d/hapaneld-helper.sh")
            alternateRegistration.parentFile!!.mkdirs()
            writeExecutable(alternateRegistration, legacyRegistration(TakeoverTopology.SYSTEMLESS), 755)
            val result = runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            ))
            assertEquals(1, result)
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.candidateBytes, fixture.stage.readText())
            assertFalse(fixture.live.exists())
            assertFalse(File(fixture.dataLocal, ".hapaneld-helper.takeover").exists())
        }
    }

    @Test fun `startup polling survives four unavailable successor probes`() {
        withTakeoverFiles(
            TakeoverTopology.SYSTEMLESS,
            candidateStarts = true,
            readyAfterFailedProbes = 4,
        ) { fixture ->
            val virtualBin = File(fixture.root, "virtual-bin").apply { mkdirs() }
            writeExecutable(File(virtualBin, "sleep"), "#!/bin/sh\nexit 0\n", 755)
            val command = bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 7,
            )
            assertEquals(0, runTakeoverCommand("PATH='${virtualBin.absolutePath}':\$PATH; $command"))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
        }
    }

    @Test fun `retained authority resumes published and rollback publication cuts`() {
        listOf("published", "rolled_back").forEach { cut ->
            withTakeoverFiles(TakeoverTopology.HYBRID, candidateStarts = true) { fixture ->
                writeTakeoverRecord(fixture)
                if (cut == "published") Files.move(fixture.stage.toPath(), fixture.live.toPath())
                fixture.incumbentReady.delete()
                assertEquals(cut, 0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                    sha256(if (fixture.stage.exists()) fixture.stage else fixture.live), stagedBuild, incumbentBuild,
                    filesystemRoot = fixture.root.absolutePath, polls = 1,
                )))
                assertEquals(fixture.candidateBytes, fixture.live.readText())
                assertEquals(fixture.oldBytes, fixture.oldBin.readText())
                assertEquals(fixture.oldRegistration, fixture.registration.readText())
                assertTrue(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").isFile)
            }
        }
    }

    @Test fun `partial preauthority record is discarded and recreated safely`() {
        withTakeoverFiles(TakeoverTopology.SYSTEMLESS, candidateStarts = true) { fixture ->
            File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover.tmp").also {
                it.writeText("partial")
                setMode(it, 600)
            }
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
        }
    }

    @Test fun `published candidate retained record resumes after candidate socket loss`() {
        withTakeoverFiles(
            TakeoverTopology.HYBRID,
            candidateStarts = true,
        ) { fixture ->
            writeTakeoverRecord(fixture)
            Files.move(fixture.stage.toPath(), fixture.live.toPath())
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.live), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
        }
    }

    @Test fun `retained candidate resumes nonempty Guard without replacement-safe or ordinary admission`() {
        val activeStatus = "OK GUARDSTATUS 1 PREPARED ${"1".repeat(64)} ${"2".repeat(64)} " +
            "NONE NONE 0 0 1 NONE NONE 600000 120000"
        assertTrue(GuardDbMaintenanceProtocol.parseStatus(activeStatus)?.ownsMaintenance == true)
        withTakeoverFiles(
            TakeoverTopology.SYSTEM,
            candidateStarts = true,
            signalOnReplacementCheck = true,
            guardStatus = activeStatus,
        ) { fixture ->
            writeTakeoverRecord(fixture)
            Files.move(fixture.stage.toPath(), fixture.live.toPath())
            val output = runCommandWithInput(
                bundledLegacyHelperResumeCommand(
                    sha256(fixture.live), stagedBuild, incumbentBuild,
                    filesystemRoot = fixture.root.absolutePath, polls = 1,
                ),
                "",
            )
            assertEquals(0, output.exitCode)
            assertTrue(output.output.isBlank())
            assertEquals(fixture.candidateBytes, fixture.live.readText())
            assertTrue(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").isFile)
        }
    }

    @Test fun `successful ephemeral takeover can repeat after old boot authority restarts`() {
        withTakeoverFiles(
            TakeoverTopology.SYSTEMLESS,
            candidateStarts = true,
            sharedSocketReplies = true,
        ) { fixture ->
            val hash = sha256(fixture.stage)
            val command = { bundledLegacyHelperTakeoverCommand(
                hash, stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            ) }
            assertEquals(0, runTakeoverCommand(command()))
            val retained = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
            assertTrue(retained.isFile)
            val retainedRecord = requireNotNull(parseBundledLegacyTakeoverRecord(retained.readText()))
            assertEquals(incumbentBuild, retainedRecord.incumbentBuildId)
            assertEquals(stagedBuild, retainedRecord.stagedBuildId)

            // Reboot starts the untouched old registration. The app restages the same candidate
            // while the prior exact /data/local live bytes remain.
            writeExecutable(fixture.stage, fixture.candidateBytes, 700)
            fixture.incumbentReady.delete()
            assertEquals(0, runTakeoverCommand(command()))
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())
            assertEquals(fixture.candidateBytes, fixture.live.readText())
        }
    }

    @Test fun `guarded successor retains exact recovery authority after supervisor loss`() {
        withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
            val xSha = sha256(fixture.stage)
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                xSha, stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertTrue(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").isFile)

            val recordFile = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
            val retained = requireNotNull(parseBundledLegacyTakeoverRecord(recordFile.readText()))
            val yBuild = "f".repeat(64)
            val yBytes = fakeHelper(yBuild, candidate = true, starts = true)
            writeExecutable(fixture.live, yBytes, 700)
            val successor = retained.copy(
                stagedBuildId = yBuild,
                candidateSha256 = sha256(yBytes),
                candidateBytes = yBytes.toByteArray().size.toLong(),
            )
            assertEquals(0, runTakeoverCommand(bundledLegacyTakeoverRecordCleanupCommand(
                retained.recordSha256, retained.recordBytes, fixture.root.absolutePath,
                preserveIfOldAuthorityExact = successor,
            )))
            assertTrue("successful replacement must retain restart authority", recordFile.isFile)
            val recovered = requireNotNull(parseBundledLegacyTakeoverRecord(recordFile.readText()))
            assertEquals("recovery must name the successor, not the retired candidate", yBuild, recovered.stagedBuildId)
            assertEquals(incumbentBuild, recovered.incumbentBuildId)
            assertEquals(successor.candidateSha256, recovered.candidateSha256)
            assertFalse(fixture.stage.exists())
            // No staging or boot-helper start is available after the new supervisor is lost.
            fixture.incumbentReady.delete()
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperResumeCommand(
                recovered.candidateSha256, recovered.stagedBuildId, recovered.incumbentBuildId,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(yBytes, fixture.live.readText())
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())
        }
    }

    @Test fun `retained record parser enables unreachable socket recovery and rejects extra fields`() {
        val fixture = "OK LEGACYTAKEOVER 1 system ${"c".repeat(64)} 10 ${"d".repeat(64)} 20 644 $incumbentBuild $stagedBuild ${"e".repeat(64)} 30"
        val parsed = requireNotNull(parseBundledLegacyTakeoverRecord(fixture))
        assertEquals(incumbentBuild, parsed.incumbentBuildId)
        assertEquals(stagedBuild, parsed.stagedBuildId)
        assertEquals(fixture.toByteArray().size.toLong(), parsed.recordBytes)
        assertEquals(sha256(fixture), parsed.recordSha256)
        assertNull(parseBundledLegacyTakeoverRecord("$fixture EXTRA"))
        assertNull(parseBundledLegacyTakeoverRecord("$fixture\n\n"))
        assertTrue(bundledLegacyRecoveryAllowed(GuardDbMaintenanceClient.StatusProbe.Unreachable, parsed))
        assertTrue(bundledLegacyRecoveryAllowed(GuardDbMaintenanceClient.StatusProbe.Unsupported, parsed))
        assertFalse(bundledLegacyRecoveryAllowed(GuardDbMaintenanceClient.StatusProbe.Malformed, parsed))
        // Cross-build reuse needs the exact live app-owned candidate, not a record alone.
        val nextBuild = "f".repeat(64)
        assertEquals(
            BundledLegacyPriorRecordDisposition.REPROVISION,
            bundledLegacyPriorRecordDisposition(nextBuild, parsed),
        )
        assertEquals(
            BundledLegacyPriorRecordDisposition.KEEP,
            bundledLegacyPriorRecordDisposition(parsed.stagedBuildId, parsed),
        )
        assertNull(parseBundledLegacyTakeoverRecord(fixture + "0".repeat(1025)))
    }

    @Test fun `staging a guarded successor preserves and rebinds the exact live takeover record`() {
        withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            val recordFile = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
            val recordBytes = recordFile.readText()
            val record = requireNotNull(parseBundledLegacyTakeoverRecord(recordBytes))
            val successor = fakeHelper("f".repeat(64), candidate = true, starts = true)
            val command = bundledHelperStageCommand(
                sha256(successor), fixture.root.absolutePath, retainedRecord = record,
            )
            val staged = runCommandWithInput(command, successor)
            assertEquals(staged.output, 0, staged.exitCode)
            assertEquals(successor, fixture.stage.readText())
            assertEquals(recordBytes, recordFile.readText())
            assertEquals(fixture.candidateBytes, fixture.live.readText())
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())

            // The parsed authority cannot authorize staging after another writer changes its bytes.
            recordFile.writeText(recordBytes.replace(record.oldSha256, "0".repeat(64)))
            fixture.stage.writeText("retained candidate")
            assertEquals(75, runCommandWithInput(command, successor).exitCode)
            assertEquals("retained candidate", fixture.stage.readText())
            assertEquals(fixture.candidateBytes, fixture.live.readText())
        }
    }

    @Test fun `legacy takeover refuses an unrelated stale data local helper before custody`() {
        withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
            val stale = fakeHelper("f".repeat(64), candidate = true, starts = true)
            writeExecutable(fixture.live, stale, 700)
            assertTrue(runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )) != 0)
            assertEquals(stale, fixture.live.readText())
            assertEquals(fixture.candidateBytes, fixture.stage.readText())
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())
            assertFalse(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").exists())
        }
    }

    @Test fun `record cleanup deletes only the exact bytes parsed before lock acquisition`() {
        withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
            writeTakeoverRecord(fixture)
            val record = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
            val parsed = requireNotNull(parseBundledLegacyTakeoverRecord(record.readText()))
            val foreign = record.readText().replaceFirst("system ", "hybrid ")
            assertEquals(foreign.length, record.readText().length)
            record.writeText(foreign)
            record.setExecutable(false, false)
            record.setReadable(true, false)
            record.setWritable(true, true)

            assertEquals(1, runTakeoverCommand(bundledLegacyTakeoverRecordCleanupCommand(
                parsed.recordSha256,
                parsed.recordBytes,
                filesystemRoot = fixture.root.absolutePath,
            )))
            assertEquals(foreign, record.readText())
        }
    }

    @Test fun `canonical helper preserves recovery evidence when boot authority changes`() {
        withTakeoverFiles(TakeoverTopology.HYBRID, candidateStarts = true) { fixture ->
            writeTakeoverRecord(fixture)
            Files.move(fixture.stage.toPath(), fixture.live.toPath())
            val recordFile = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
            val record = requireNotNull(parseBundledLegacyTakeoverRecord(recordFile.readText()))
            val command = { bundledLegacyTakeoverRecordCleanupCommand(
                record.recordSha256,
                record.recordBytes,
                filesystemRoot = fixture.root.absolutePath,
                preserveIfOldAuthorityExact = record,
            ) }
            assertEquals(0, runTakeoverCommand(command()))
            assertTrue(recordFile.isFile)

            fixture.oldBin.appendText("superseded\n")
            assertEquals(1, runTakeoverCommand(command()))
            assertTrue(recordFile.isFile)
        }
    }

    @Test fun `startup repairs interrupted successor record publication before resuming lost supervisor`() {
        for (cut in listOf("before-record", "partial-record", "after-record", "same-build-partial-record")) {
            withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
                assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                    sha256(fixture.stage), stagedBuild, incumbentBuild, fixture.root.absolutePath, polls = 1,
                )))
                val recordFile = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
                val old = requireNotNull(parseBundledLegacyTakeoverRecord(recordFile.readText()))
                val ready = File(fixture.root, "successor-ready")
                val yBuild = "f".repeat(64)
                val yBytes = fakeHelper(yBuild, candidate = true, starts = true).replaceFirst(
                    "#!/bin/sh\n",
                    "#!/bin/sh\n" +
                        "if [ \"\$1\" = --supervise ]; then : > '${ready.absolutePath}'; fi\n" +
                        "if [ \"\$1\" = --request ] && [ ! -f '${ready.absolutePath}' ]; then exit 1; fi\n",
                )
                val identity = GuardDbMaintenanceProtocol.SelfIdentity(
                    yBytes.toByteArray().size.toLong(), sha256(yBytes), yBuild,
                )
                writeExecutable(fixture.live, yBytes, 700)
                fun rebind(record: BundledLegacyTakeoverRecord, target: GuardDbMaintenanceProtocol.SelfIdentity): Boolean =
                    runTakeoverCommand(bundledLegacyTakeoverRecordCleanupCommand(
                        record.recordSha256, record.recordBytes, fixture.root.absolutePath,
                        preserveIfOldAuthorityExact = record.copy(
                            stagedBuildId = target.buildId, candidateSha256 = target.sha256, candidateBytes = target.bytes,
                        ),
                    )) == 0
                when (cut) {
                    "partial-record" -> writeExecutable(File(recordFile.path + ".tmp"), "partial", 600)
                    "after-record", "same-build-partial-record" -> assertTrue(rebind(old, identity))
                }
                if (cut == "same-build-partial-record") {
                    writeExecutable(File(recordFile.path + ".tmp"), "partial", 600)
                }
                var resumes = 0
                var rebinds = 0
                val probe = {
                    val output = runCommandWithInput("'${fixture.live.absolutePath}' --request GUARDSTATUS", "")
                    GuardDbMaintenanceProtocol.parseStatus(output.output.trim())?.let {
                        GuardDbMaintenanceClient.StatusProbe.Valid(it)
                    } ?: GuardDbMaintenanceClient.StatusProbe.Unreachable
                }
                val status = reacquireGuardDbStartupStatus(
                    statusProbe = probe,
                    resumeRetainedCurrent = {
                        resumeBundledLegacyHelper(
                            probe(), parseBundledLegacyTakeoverRecord(recordFile.readText()), yBuild,
                            bundledIdentity = { identity },
                            rebind = { record, target -> rebinds++; rebind(record, target) },
                            resume = { record ->
                                resumes++
                                val resumed = runCommandWithInput(bundledLegacyHelperResumeCommand(
                                    record.candidateSha256, record.stagedBuildId, record.incumbentBuildId,
                                    fixture.root.absolutePath, polls = 2,
                                ), "")
                                assertEquals("$cut resume: ${resumed.output}", 0, resumed.exitCode)
                                true
                            },
                        ) == true
                    },
                    pause = {},
                )
                assertTrue(cut, status is GuardDbMaintenanceClient.StatusProbe.Valid)
                assertEquals(cut, 1, resumes)
                assertEquals(cut, if (cut in listOf("after-record", "same-build-partial-record")) 0 else 1, rebinds)
                val settled = requireNotNull(parseBundledLegacyTakeoverRecord(recordFile.readText()))
                assertEquals(cut, yBuild, settled.stagedBuildId)
                assertEquals(cut, incumbentBuild, settled.incumbentBuildId)
                assertEquals(cut, fixture.oldBytes, fixture.oldBin.readText())
                assertEquals(cut, fixture.oldRegistration, fixture.registration.readText())
                assertEquals(cut, yBytes, fixture.live.readText())
                assertFalse(fixture.stage.exists())
                assertFalse(File(recordFile.path + ".tmp").exists())
            }
        }
    }

    @Test fun `successor rebind refuses changed bytes and in flight replacement custody`() {
        for (fault in listOf("hash", "size", "record", "replacement", "previous", "locked", "temporary-link")) {
            withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
                writeTakeoverRecord(fixture)
                Files.move(fixture.stage.toPath(), fixture.live.toPath())
                val recordFile = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
                val original = recordFile.readText()
                val record = requireNotNull(parseBundledLegacyTakeoverRecord(original))
                val successor = when (fault) {
                    "hash" -> record.copy(candidateSha256 = "0".repeat(64))
                    "size" -> record.copy(candidateBytes = record.candidateBytes + 1)
                    else -> record
                }
                when (fault) {
                    "record" -> recordFile.writeText(original.replaceFirst("system ", "hybrid "))
                    "replacement" -> foreignHelperJournal(fixture.root, "data/local/.hapaneld-guard-db/replacement.v1")
                    "previous" -> File(fixture.dataLocal, ".hapaneld-helper.previous").writeText("custody")
                    "locked" -> File(fixture.root, "dev/.hapaneld-helper-transaction.lock").also {
                        it.mkdirs(); File(it, "pid").writeText(ProcessHandle.current().pid().toString())
                    }
                    "temporary-link" -> Files.createSymbolicLink(File(recordFile.path + ".tmp").toPath(), fixture.oldBin.toPath())
                }
                val before = recordFile.readText()
                assertTrue(fault, runTakeoverCommand(bundledLegacyTakeoverRecordCleanupCommand(
                    record.recordSha256, record.recordBytes, fixture.root.absolutePath,
                    preserveIfOldAuthorityExact = successor,
                )) != 0)
                assertEquals(fault, before, recordFile.readText())
                assertEquals(fault, fixture.candidateBytes, fixture.live.readText())
                assertEquals(fault, fixture.oldBytes, fixture.oldBin.readText())
                assertEquals(fault, fixture.oldRegistration, fixture.registration.readText())
            }
        }
    }

    @Test fun `retained restart refuses foreign temporary record custody`() {
        for (fault in listOf("symlink", "hardlink", "mode", "oversized")) {
            withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
                writeTakeoverRecord(fixture)
                Files.move(fixture.stage.toPath(), fixture.live.toPath())
                val recordFile = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
                val record = recordFile.readText()
                val temporary = File(recordFile.path + ".tmp")
                when (fault) {
                    "symlink" -> Files.createSymbolicLink(temporary.toPath(), fixture.oldBin.toPath())
                    "hardlink" -> {
                        val twin = File(fixture.root, "foreign-temporary")
                        writeExecutable(twin, "partial", 600)
                        Files.createLink(temporary.toPath(), twin.toPath())
                    }
                    "mode" -> writeExecutable(temporary, "partial", 644)
                    "oversized" -> writeExecutable(temporary, "x".repeat(1025), 600)
                }
                assertTrue(fault, runTakeoverCommand(bundledLegacyHelperResumeCommand(
                    sha256(fixture.live), stagedBuild, incumbentBuild, fixture.root.absolutePath, polls = 1,
                )) != 0)
                assertTrue(fault, Files.exists(temporary.toPath()))
                assertEquals(fault, record, recordFile.readText())
                assertEquals(fault, fixture.candidateBytes, fixture.live.readText())
                assertEquals(fault, fixture.oldBytes, fixture.oldBin.readText())
                assertEquals(fault, fixture.oldRegistration, fixture.registration.readText())
            }
        }
    }

    @Test fun `read only retained record probe rejects oversized root file before cat`() {
        val root = scratchDirectory("bundled-helper-record-").toFile()
        try {
            val record = File(root, "data/local/.hapaneld-helper.legacy-takeover")
            record.parentFile!!.mkdirs()
            writeExecutable(record, "x".repeat(1025), 600)
            assertEquals(1, runTakeoverCommand(bundledLegacyTakeoverRecordReadCommand(root.absolutePath)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `candidate rollback spares an unrelated program now holding the candidate's pid`() {
        // The supervisor's pid is what a script would cache from `$!`; exec gives it to another
        // program, as pid reuse would. Rollback must stop only processes running the live inode.
        val pidFile = File(scratchDirectory("bundled-helper-supervise-pid-").toFile(), "pid")
        try {
            withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = false, supervisePidFile = pidFile) { fixture ->
                val command = bundledLegacyHelperTakeoverCommand(
                    sha256(fixture.stage), stagedBuild, incumbentBuild,
                    filesystemRoot = fixture.root.absolutePath, polls = 1,
                )
                val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 1, process.waitFor())
                val deadline = System.nanoTime() + 5_000_000_000L
                while ((!pidFile.isFile || pidFile.readText().isBlank()) && System.nanoTime() < deadline) Thread.sleep(10)
                val pid = pidFile.readText().trim().toLong()
                assertTrue(
                    "rollback killed the unrelated owner of the candidate's pid",
                    ProcessHandle.of(pid).map { it.isAlive }.orElse(false),
                )
                assertEquals(fixture.oldBytes, fixture.oldBin.readText())
                assertEquals(fixture.oldRegistration, fixture.registration.readText())
                assertEquals(fixture.candidateBytes, fixture.stage.readText())
                assertFalse(fixture.live.exists())
                assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
            }
        } finally {
            pidFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
                ?.let { ProcessHandle.of(it).ifPresent { handle -> handle.destroyForcibly() } }
            pidFile.parentFile!!.deleteRecursively()
        }
    }

    @Test fun `termination before custody exits and leaves topology untouched`() {
        withTakeoverFiles(
            TakeoverTopology.SYSTEM,
            candidateStarts = true,
            signalOnReplacementCheck = true,
        ) { fixture ->
            assertEquals(74, runTakeoverCommand(
                bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
                ),
            ))
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())
            assertEquals(fixture.candidateBytes, fixture.stage.readText())
            assertFalse(fixture.live.exists())
            assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
        }
    }

    @Test fun `termination after swap performs authenticated rollback and exits`() {
        withTakeoverFiles(
            TakeoverTopology.SYSTEMLESS,
            candidateStarts = true,
            signalOnSupervise = true,
        ) { fixture ->
            assertEquals(74, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())
            assertTrue(fixture.stage.exists())
            assertFalse(fixture.live.exists())
            assertFalse(File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
        }
    }

    @Test fun `replacement nonce is fresh lowercase sha sized material`() {
        val first = freshBundledHelperReplacementNonce()
        val second = freshBundledHelperReplacementNonce()
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertTrue(second.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first != second)
    }

    @Test fun `reply loss reconciles exact new helper without retrying RETIRE`() {
        var retires = 0
        val result = executeBundledHelperReplacement(
            retire = { retires++; GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate },
            probe = { exactProbe(stagedBuild) },
            pause = {},
            polls = 2,
        )
        assertEquals(BundledHelperReplacementSettlement.INSTALLED, result)
        assertEquals(1, retires)
    }

    @Test fun `PREPARE winning retire race blocks before settlement probes`() {
        var retires = 0
        var probes = 0
        val result = executeBundledHelperReplacement(
            retire = {
                retires++
                GuardDbMaintenanceProtocol.AppRetireResult.Rejected("ARMED", "replacement")
            },
            probe = { probes++; HelperReplacementProbe.Hold },
            pause = {},
            polls = 2,
        )
        assertEquals(BundledHelperReplacementSettlement.BLOCKED_ACTIVE, result)
        assertEquals(1, retires)
        assertEquals(0, probes)
    }

    @Test fun `retire winning race tolerates handoff hold then settles new`() {
        var retires = 0
        val probes = ArrayDeque<HelperReplacementProbe>().apply {
            add(HelperReplacementProbe.Hold)
            add(exactProbe(stagedBuild))
        }
        val result = executeBundledHelperReplacement(
            retire = { retires++; GuardDbMaintenanceProtocol.AppRetireResult.Requested },
            probe = { probes.removeFirst() },
            pause = {},
            polls = 2,
        )
        assertEquals(BundledHelperReplacementSettlement.INSTALLED, result)
        assertEquals(1, retires)
    }

    @Test fun `old safe requires two uninterrupted exact incumbent tuples`() {
        val probes = ArrayDeque<HelperReplacementProbe>().apply {
            add(exactProbe(incumbentBuild))
            add(HelperReplacementProbe.Hold)
            add(exactProbe(incumbentBuild))
            add(exactProbe(incumbentBuild))
        }
        val result = executeBundledHelperReplacement(
            retire = { GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate },
            probe = { probes.removeFirst() },
            pause = {},
            polls = 4,
        )
        assertEquals(BundledHelperReplacementSettlement.OLD_SAFE, result)
    }

    @Test fun `single incumbent tuple or unavailable deadline remains HOLD`() {
        val probes = ArrayDeque<HelperReplacementProbe>().apply {
            add(exactProbe(incumbentBuild))
            add(HelperReplacementProbe.Hold)
            add(exactProbe(incumbentBuild))
        }
        assertEquals(
            BundledHelperReplacementSettlement.HOLD,
            executeBundledHelperReplacement(
                retire = { GuardDbMaintenanceProtocol.AppRetireResult.Requested },
                probe = { probes.removeFirst() },
                pause = {},
                polls = 3,
            ),
        )
    }

    @Test fun `definitely not submitted never probes or mutates`() {
        var probes = 0
        assertEquals(
            BundledHelperReplacementSettlement.NOT_SUBMITTED,
            executeBundledHelperReplacement(
                retire = { GuardDbMaintenanceProtocol.AppRetireResult.NotSubmitted },
                probe = { probes++; HelperReplacementProbe.Hold },
                pause = {},
                polls = 1,
            ),
        )
        assertEquals(0, probes)
    }

    @Test fun `replacement tuple requires ping exact build supervised and exact EMPTY`() {
        assertEquals(HelperReplacementProbe.Settled(HelperReplacementBuild.NEW), exactProbe(stagedBuild))
        assertEquals(HelperReplacementProbe.Settled(HelperReplacementBuild.OLD), exactProbe(incumbentBuild))
        val valid = arrayOf<String?>("OK", "BUILDID $stagedBuild", supervisedCaps, emptyStatus)
        val invalid = listOf(
            valid.copyOf().also { it[0] = "ok" },
            valid.copyOf().also { it[1] = "BUILDID ${"c".repeat(64)}" },
            valid.copyOf().also { it[2] = "${GuardDbMaintenanceProtocol.CAPS_REPLY} SUPERVISED" },
            valid.copyOf().also {
                it[2] = "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED"
            },
            valid.copyOf().also { it[2] = GuardDbMaintenanceProtocol.CAPS_REPLY },
            valid.copyOf().also { it[3] = "ERR HOLD startup" },
            valid.copyOf().also { it[3] = emptyStatus.replace("EMPTY", "STAGING") },
            valid.copyOf().also { it[3] = null },
        )
        invalid.forEach { tuple ->
            assertEquals(
                tuple.toList().toString(),
                HelperReplacementProbe.Hold,
                classifyHelperReplacementProbe(
                    tuple[0], tuple[1], tuple[2], tuple[3], stagedBuild, incumbentBuild,
                ),
            )
        }
    }

    @Test fun `only exact empty or released unsupported status selects a replacement authority`() {
        val status = GuardDbMaintenanceProtocol.Status(
            generation = 8L,
            phase = GuardDbMaintenanceProtocol.Phase.AMBIGUOUS,
            session = "1".repeat(64),
            bootNonce = "2".repeat(64),
            role = null,
            apkSha256 = null,
            versionCode = null,
            schema = null,
            baselineAppStateCount = 4L,
            error = "PM_UNKNOWN",
            outcome = GuardDbMaintenanceProtocol.Outcome.AMBIGUOUS,
            overallDeadlineElapsedMs = 1_800_000L,
            forwardDeadlineElapsedMs = 1_320_000L,
        )
        assertNull(bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Valid(status)))
        assertNull(bundledHelperReplacementMode(
            GuardDbMaintenanceClient.StatusProbe.Valid(status.copy(
                phase = GuardDbMaintenanceProtocol.Phase.FINALIZED,
                role = GuardDbMaintenanceProtocol.Role.A,
                apkSha256 = stagedBuild,
                versionCode = 568L,
                schema = 14,
                error = null,
                outcome = GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED,
            )),
        ))
        assertEquals(
            BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER,
            bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Unsupported),
        )
        assertEquals(
            BundledHelperReplacementMode.GUARDED_RETIRE,
            bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Valid(status.copy(
                generation = 0L,
                phase = GuardDbMaintenanceProtocol.Phase.EMPTY,
                session = null,
                bootNonce = null,
                role = null,
                apkSha256 = null,
                versionCode = null,
                schema = null,
                baselineAppStateCount = 0L,
                error = null,
                outcome = null,
                overallDeadlineElapsedMs = 0L,
                forwardDeadlineElapsedMs = 0L,
            ))),
        )
        assertNull(bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Malformed))
    }

    private fun exactProbe(build: String): HelperReplacementProbe = classifyHelperReplacementProbe(
        "OK",
        "BUILDID $build",
        supervisedCaps,
        emptyStatus,
        stagedBuild,
        incumbentBuild,
    )

    private enum class TakeoverTopology { SYSTEM, SYSTEMLESS, HYBRID }

    private data class TakeoverFixture(
        val topology: TakeoverTopology,
        val root: File,
        val dataLocal: File,
        val oldBin: File,
        val registration: File,
        val live: File,
        val stage: File,
        val oldBytes: String,
        val candidateBytes: String,
        val oldRegistration: String,
        val incumbentReady: File,
    )

    private fun withTakeoverFiles(
        topology: TakeoverTopology,
        candidateStarts: Boolean,
        readyAfterFailedProbes: Int = 0,
        signalOnReplacementCheck: Boolean = false,
        signalOnSupervise: Boolean = false,
        registrationBytes: String = legacyRegistration(topology),
        sharedSocketReplies: Boolean = false,
        guardStatus: String = emptyStatus,
        supervisePidFile: File? = null,
        test: (TakeoverFixture) -> Unit,
    ) {
        val root = scratchDirectory("bundled-helper-takeover-").toFile()
        try {
            val dataLocal = File(root, "data/local").apply { mkdirs() }
            File(root, "dev").mkdirs()
            val live = File(dataLocal, "hapaneld-helper")
            val stage = File(dataLocal, ".hapaneld-helper.new")
            val oldBin = when (topology) {
                TakeoverTopology.SYSTEM -> File(root, "system/bin/hapaneld-helper")
                TakeoverTopology.SYSTEMLESS, TakeoverTopology.HYBRID ->
                    File(root, "data/adb/hapaneld/hapaneld-helper")
            }
            val registration = when (topology) {
                TakeoverTopology.SYSTEM -> File(root, "system/etc/init/hapaneld-helper.rc")
                TakeoverTopology.SYSTEMLESS -> File(root, "data/adb/service.d/hapaneld-helper.sh")
                TakeoverTopology.HYBRID -> File(root, "vendor/etc/init/hapaneld-helper.rc")
            }
            oldBin.parentFile!!.mkdirs()
            registration.parentFile!!.mkdirs()
            val sharedSocketMarker = if (sharedSocketReplies) File(dataLocal, ".candidate-serving") else null
            if (readyAfterFailedProbes > 0) {
                File(dataLocal, ".candidate-ready-probes").writeText("$readyAfterFailedProbes\n")
            }
            val incumbentReady = File(dataLocal, ".incumbent-serving").apply { writeText("ready\n") }
            val oldBytes = fakeHelper(
                incumbentBuild,
                candidate = false,
                starts = true,
                sharedSocketMarker = sharedSocketMarker,
                incumbentReadyMarker = incumbentReady,
            )
            val candidateBytes = fakeHelper(
                stagedBuild,
                candidate = true,
                starts = candidateStarts,
                readyAfterFailedProbes = readyAfterFailedProbes,
                signalOnReplacementCheck = signalOnReplacementCheck,
                signalOnSupervise = signalOnSupervise,
                sharedSocketMarker = sharedSocketMarker,
                guardStatus = guardStatus,
                supervisePidFile = supervisePidFile,
            )
            val oldRegistration = registrationBytes
            writeExecutable(oldBin, oldBytes, 755)
            writeExecutable(registration, oldRegistration, if (topology == TakeoverTopology.SYSTEMLESS) 755 else 644)
            writeExecutable(stage, candidateBytes, 700)
            if (topology != TakeoverTopology.SYSTEMLESS) {
                File(root, "system/bin").mkdirs()
                writeExecutable(File(root, "system/bin/stop"), """
                    #!/bin/sh
                    [ "${'$'}1" = hapaneld_helper ] || exit 2
                    [ -s '${File(dataLocal, ".hapaneld-helper.legacy-takeover").absolutePath}' ] ||
                      : > '${File(root, "stopped-before-record").absolutePath}'
                    rm -f '${incumbentReady.absolutePath}'
                """.trimIndent() + "\n", 755)
                writeExecutable(File(root, "system/bin/start"), """
                    #!/bin/sh
                    [ "${'$'}1" = hapaneld_helper ] || exit 2
                    '${oldBin.absolutePath}' >/dev/null 2>&1
                """.trimIndent() + "\n", 755)
            }
            test(TakeoverFixture(
                topology, root, dataLocal, oldBin, registration, live, stage,
                oldBytes, candidateBytes, oldRegistration,
                incumbentReady,
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun legacyRegistration(topology: TakeoverTopology): String = when (topology) {
        TakeoverTopology.SYSTEM -> """
            service hapaneld_helper /system/bin/hapaneld-helper
                class main
                user root
                group root
                seclabel u:r:su:s0
        """.trimIndent() + "\n"
        TakeoverTopology.HYBRID -> """
            service hapaneld_helper /data/adb/hapaneld/hapaneld-helper
                class main
                user root
                group root
                seclabel u:r:su:s0
        """.trimIndent() + "\n"
        TakeoverTopology.SYSTEMLESS -> """
            #!/system/bin/sh
            while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
            /system/bin/stop hapaneld_helper 2>/dev/null
            /system/bin/stop hapaneld_ledd 2>/dev/null
            /system/bin/pkill -x hapaneld-helper 2>/dev/null
            /system/bin/pkill -x hapaneld-ledd 2>/dev/null
            /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
        """.trimIndent() + "\n"
    }

    private val releasedBaseSystemRegistration = """
        # ha-paneld root helper — boot-persistent control daemon (LED, screen backlight, buttons, sysctls).
        #
        # Installed to /system/etc/init/hapaneld-helper.rc (auto-imported by init at boot). Runs in the `su`
        # domain so it can write the root-only sysfs nodes (sysfs_lights, backlight bl_power) on a
        # userdebug panel — the same domain a manual `su 0 shell uses. `class main` auto-starts it during
        # boot. See helper/install-daemon.sh and helper/README.md.
        #
        # DO NOT add `critical` — it causes a reboot-loop if the daemon crashes at boot (7-second cycle,
        # recovery-mode only). Without it, Android init's built-in backoff disables the service after
        # 4 rapid crashes rather than rebooting, which is the safe behaviour we want.
        service hapaneld_helper /system/bin/hapaneld-helper
            class main
            user root
            group root
            seclabel u:r:su:s0
    """.trimIndent() + "\n"

    private fun writeTakeoverRecord(fixture: TakeoverFixture) {
        val registrationMode = if (fixture.topology == TakeoverTopology.SYSTEMLESS) 755 else 644
        val candidate = if (fixture.stage.exists()) fixture.stage else fixture.live
        val record = File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover")
        record.writeText(
            "OK LEGACYTAKEOVER 1 ${fixture.topology.name.lowercase()} " +
                "${sha256(fixture.oldBin)} ${fixture.oldBin.length()} " +
                "${sha256(fixture.registration)} ${fixture.registration.length()} $registrationMode " +
                "$incumbentBuild $stagedBuild ${sha256(candidate)} ${candidate.length()}\n",
        )
        setMode(record, 600)
    }
}
