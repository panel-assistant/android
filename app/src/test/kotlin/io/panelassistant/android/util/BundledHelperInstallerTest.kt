package io.panelassistant.android.util

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
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

    private val stagedBuild = "a".repeat(64)
    private val incumbentBuild = "b".repeat(64)
    private val emptyStatus = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
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

    @Test fun `stage command atomically publishes a bounded candidate under shared authority lock`() {
        val hash = "c".repeat(64)
        val command = bundledHelperStageCommand(hash)
        assertTrue(command.contains(".hapaneld-helper-transaction.lock"))
        assertTrue(command.contains("upload=\$data_local/.hapaneld-helper.app-stage-$hash"))
        assertTrue(command.contains("cat > \"\$upload\""))
        assertTrue(command.contains("[ \"\${actual%% *}\" = \"$hash\" ]"))
        assertTrue(command.contains("chown 0:0 \"\$upload\""))
        assertTrue(command.contains("chmod 700 \"\$upload\""))
        assertTrue(command.contains("0:0:700:1:*"))
        assertTrue(command.contains("16777216"))
        assertTrue(command.contains("mv -f \"\$upload\" \"\$stage\""))
        assertTrue(command.contains("echo STAGED_OK"))
        assertFalse(command.contains("cat > \"\$stage\""))
        listOf("cp ", "pkill", " stop ", " start ", "--supervise", "awk", "/data/local/tmp")
            .forEach { assertFalse(it, command.contains(it)) }
    }

    @Test fun `stage command replaces only an unowned fixed orphan after authenticating full upload`() {
        val root = Files.createTempDirectory("bundled-helper-stage-").toFile()
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

    @Test fun `stage command preserves fixed stage when lock or native authority is active`() {
        val root = Files.createTempDirectory("bundled-helper-stage-busy-").toFile()
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
            val root = Files.createTempDirectory("bundled-helper-foreign-stage-").toFile()
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

    private fun foreignHelperJournal(root: File, path: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText("retained authority\n")
        setMode(this, 600)
    }

    @Test fun `interrupted upload removes only its unique partial and releases admission`() {
        val root = Files.createTempDirectory("bundled-helper-stage-signal-").toFile()
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

    @Test fun `released base without Guard verbs uses candidate owned takeover`() {
        // RC2 answers bare ERR to GUARDSTATUS, which statusProbe classifies as Unsupported. It cannot
        // consume GUARDRETIRE, so this must never enter the guarded-retire settlement loop.
        assertEquals(
            BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER,
            bundledHelperReplacementMode(GuardDbMaintenanceClient.StatusProbe.Unsupported),
        )

        val hash = "c".repeat(64)
        val command = bundledLegacyHelperTakeoverCommand(hash, stagedBuild, incumbentBuild)
        assertTrue(command.contains("stage=\$data_local/.hapaneld-helper.new"))
        assertTrue(command.contains("live=\$data_local/hapaneld-helper"))
        assertTrue(command.contains("lock=\$root/dev/.hapaneld-helper-transaction.lock"))
        assertTrue(command.contains("--replacement-safe"))
        assertTrue(command.contains(".hapaneld-helper.legacy-takeover"))
        assertTrue(command.contains("LEGACYTAKEOVER 1"))
        assertTrue(command.contains("= \"BUILDID $incumbentBuild\""))
        assertTrue(command.contains("mv -f \"\$stage\" \"\$live\""))
        assertTrue(command.indexOf("mv -f \"\$record_tmp\" \"\$record\"") <
            command.indexOf("\"\$root/system/bin/stop\" hapaneld_helper"))
        assertTrue(command.contains("\"\$root/system/bin/start\" hapaneld_helper"))
        assertTrue(command.contains("\"\$live\" --supervise"))
        assertTrue(command.contains("--request BUILDID"))
        assertTrue(command.contains("--request GUARDSELF"))
        assertTrue(command.contains("$hash $stagedBuild"))
        assertTrue(command.contains("AUTONOMOUS SUPERVISED TERMINAL_RETIRE"))
        assertTrue(command.contains(emptyStatus))
        assertFalse(command.contains("GUARDRETIRE"))
        assertFalse(command.contains("killall"))
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
            assertEquals(0, runTakeoverCommand(command))
            assertEquals(fixture.candidateBytes, fixture.live.readText())
            assertEquals(fixture.oldRegistration, fixture.registration.readText())
            assertEquals(fixture.oldBytes, fixture.oldBin.readText())
            assertFalse(fixture.stage.exists())
            assertTrue(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").isFile)
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

    @Test fun `guarded successor cleans stale authority before old boot helper needs legacy takeover`() {
        withTakeoverFiles(TakeoverTopology.SYSTEM, candidateStarts = true) { fixture ->
            val xSha = sha256(fixture.stage)
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                xSha, stagedBuild, incumbentBuild,
                filesystemRoot = fixture.root.absolutePath, polls = 1,
            )))
            assertTrue(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").isFile)

            // Exact guarded X->Y settlement owns this cleanup before the next reboot can expose RC2.
            val retained = requireNotNull(parseBundledLegacyTakeoverRecord(
                File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").readText(),
            ))
            assertEquals(0, runTakeoverCommand(bundledLegacyTakeoverRecordCleanupCommand(
                retained.recordSha256,
                retained.recordBytes,
                filesystemRoot = fixture.root.absolutePath,
            )))
            assertFalse(File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").exists())

            val yBuild = "f".repeat(64)
            val yBytes = fakeHelper(yBuild, candidate = true, starts = true)
            writeExecutable(fixture.live, yBytes, 700)
            writeExecutable(fixture.stage, yBytes, 700)
            assertEquals(0, runTakeoverCommand("'${fixture.oldBin.absolutePath}'"))
            assertEquals(0, runTakeoverCommand(bundledLegacyHelperTakeoverCommand(
                sha256(fixture.stage), yBuild, incumbentBuild,
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
        // Even if X is currently Guard-capable, APK Y cannot retire X and discard the record:
        // the released boot helper would return after reboot with no way to resume Y.
        val nextBuild = "f".repeat(64)
        assertEquals(
            BundledLegacyPriorRecordDisposition.REPROVISION,
            bundledLegacyPriorRecordDisposition(nextBuild, parsed),
        )
        assertEquals(
            BundledLegacyPriorRecordDisposition.KEEP,
            bundledLegacyPriorRecordDisposition(parsed.stagedBuildId, parsed),
        )
        val readCommand = bundledLegacyTakeoverRecordReadCommand()
        assertTrue(readCommand.contains(".hapaneld-helper.legacy-takeover"))
        assertTrue(readCommand.contains("\"\$bytes\" -le 1024"))
        assertNull(parseBundledLegacyTakeoverRecord(fixture + "0".repeat(1025)))
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
            assertFalse(bundledLegacyTakeoverRecordCleanupCommand(
                parsed.recordSha256,
                parsed.recordBytes,
                filesystemRoot = fixture.root.absolutePath,
            ).contains("awk"))
        }
    }

    @Test fun `canonical helper retains record only while exact released boot authority survives`() {
        withTakeoverFiles(TakeoverTopology.HYBRID, candidateStarts = true) { fixture ->
            writeTakeoverRecord(fixture)
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
            assertEquals(0, runTakeoverCommand(command()))
            assertFalse(recordFile.exists())
        }
    }

    @Test fun `read only retained record probe rejects oversized root file before cat`() {
        val root = Files.createTempDirectory("bundled-helper-record-").toFile()
        try {
            val record = File(root, "data/local/.hapaneld-helper.legacy-takeover")
            record.parentFile!!.mkdirs()
            writeExecutable(record, "x".repeat(1025), 600)
            assertEquals(1, runTakeoverCommand(bundledLegacyTakeoverRecordReadCommand(root.absolutePath)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `candidate termination never trusts a cached possibly reused pid`() {
        val command = bundledLegacyHelperTakeoverCommand("c".repeat(64), stagedBuild, incumbentBuild)
        assertFalse(command.contains("kill \"\$candidate_pid\""))
        assertTrue(command.contains("stat -Lc '%d:%i'"))
        assertTrue(LEGACY_TAKEOVER_TIMEOUT_MS >= 120_000L)
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

    private data class FreshFixture(val root: File, val live: File, val stage: File, val candidateBytes: String)

    private fun withFreshFiles(
        candidateBytes: String = fakeHelper(stagedBuild, candidate = true, starts = true),
        test: (FreshFixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("bundled-helper-fresh-").toFile()
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
        test: (TakeoverFixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("bundled-helper-takeover-").toFile()
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

    private fun fakeHelper(
        build: String,
        candidate: Boolean,
        starts: Boolean,
        readyAfterFailedProbes: Int = 0,
        signalOnReplacementCheck: Boolean = false,
        signalOnSupervise: Boolean = false,
        sharedSocketMarker: File? = null,
        incumbentReadyMarker: File? = null,
        guardStatus: String = emptyStatus,
    ): String = """
        #!/bin/sh
        if [ "${'$'}1" = --replacement-safe ]; then
          ${if (signalOnReplacementCheck) "kill -TERM \"${'$'}(ps -o ppid= -p \"${'$'}PPID\" | tr -d ' ')\"; sleep 1" else ":"}
          ${if (candidate) "echo REPLACE_SAFE; exit 0" else "exit 2"}
        fi
        if [ "${'$'}1" = --supervise ]; then
          ${if (signalOnSupervise) "kill -TERM \"${'$'}PPID\"; sleep 1" else ":"}
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

    private fun writeExecutable(file: File, bytes: String, mode: Int = 700) {
        file.writeText(bytes)
        setMode(file, mode)
    }

    private fun setMode(file: File, mode: Int) {
        val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (mode == 700 || mode == 755) permissions += PosixFilePermission.OWNER_EXECUTE
        if (mode == 644 || mode == 755) permissions += PosixFilePermission.GROUP_READ
        if (mode == 755) permissions += PosixFilePermission.GROUP_EXECUTE
        if (mode == 644 || mode == 755) permissions += PosixFilePermission.OTHERS_READ
        if (mode == 755) permissions += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun runTakeoverCommand(command: String): Int = ProcessBuilder("bash", "-c", command)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun runCommandWithInput(command: String, input: String): CommandResult =
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
