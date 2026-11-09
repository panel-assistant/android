package io.panelassistant.android.util

import io.panelassistant.android.util.BundledHelperShellFixtures.CustodyFixture
import io.panelassistant.android.util.BundledHelperShellFixtures.FIRST_BOOT_ID
import io.panelassistant.android.util.BundledHelperShellFixtures.REGISTRATION_LABEL
import io.panelassistant.android.util.BundledHelperShellFixtures.SYSTEM_BIN_LABEL
import io.panelassistant.android.util.BundledHelperShellFixtures.emptyStatus
import io.panelassistant.android.util.BundledHelperShellFixtures.incumbentBuild
import io.panelassistant.android.util.BundledHelperShellFixtures.runCommand
import io.panelassistant.android.util.BundledHelperShellFixtures.runCommandWithInput
import io.panelassistant.android.util.BundledHelperShellFixtures.setMode
import io.panelassistant.android.util.BundledHelperShellFixtures.sha256
import io.panelassistant.android.util.BundledHelperShellFixtures.stagedBuild
import io.panelassistant.android.util.BundledHelperShellFixtures.withCustodyFiles
import io.panelassistant.android.util.BundledHelperShellFixtures.writeExecutable
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout F: a Guard-era helper served from /system/bin under a /system rc. The custody
 * migration archives and removes both /system files so the panel ends in the app-lease layout the fresh
 * install already accepts, and every interruption either restores the panel or converges on a rerun.
 */
class BundledHelperSystemCustodyMigrationTest {
    @Test fun `layout F selects the custody migration and a foreign registration stays reprovision gated`() {
        withCustodyFiles { fixture ->
            val layout = probe(fixture)
            assertTrue(layout.toString(), layout is SystemCustodyLayout.LayoutF)
            assertEquals(
                BundledHelperReplacementMode.SYSTEM_CUSTODY_MIGRATION,
                bundledHelperCustodyMigrationMode(BundledHelperReplacementMode.GUARDED_RETIRE, null) { layout },
            )
            // An incumbent that proves its own identity is an ordinary Guard replacement; no probe runs.
            assertEquals(
                BundledHelperReplacementMode.GUARDED_RETIRE,
                bundledHelperCustodyMigrationMode(
                    BundledHelperReplacementMode.GUARDED_RETIRE,
                    GuardDbMaintenanceProtocol.SelfIdentity(1, "c".repeat(64), incumbentBuild),
                ) { error("a self-identifying incumbent needs no layout probe") },
            )
            assertEquals(BundledHelperInstaller.Result.INSTALLED, installBundledHelperReplacement(
                BundledHelperReplacementMode.SYSTEM_CUSTODY_MIGRATION, stagedBuild, incumbentBuild, null, null,
                stage = { true },
                guarded = { error("a /system incumbent cannot retire itself through Guard") },
                legacy = { error("the custody migration is not the released takeover") },
                fresh = { error("a serving helper is not a fresh install") },
                cleanup = { error("the migration keeps no legacy takeover record") },
                migrate = { true },
            ))
        }
        withCustodyFiles(registrationBytes = releasedBaseSystemRegistration) { fixture ->
            val layout = probe(fixture)
            assertEquals(SystemCustodyLayout.Refused("registration_unknown", layout.inventory), layout)
            val mode = bundledHelperCustodyMigrationMode(BundledHelperReplacementMode.GUARDED_RETIRE, null) { layout }
            assertEquals(BundledHelperReplacementMode.GUARDED_RETIRE, mode)
            assertEquals(BundledHelperInstaller.Result.REPROVISION_REQUIRED, installBundledHelperReplacement(
                mode, stagedBuild, incumbentBuild, null, null,
                stage = { error("nothing is staged for an unrecognised layout") },
                guarded = { error("unreachable") },
                legacy = { error("unreachable") },
                fresh = { error("unreachable") },
                cleanup = { error("unreachable") },
                migrate = { error("unreachable") },
            ))
        }
    }

    @Test fun `migration archives and removes the system helper and serves the bundled build without starting init`() {
        withCustodyFiles { fixture ->
            val result = runCommand(migrate(fixture))
            assertEquals(result.output, 0, result.exitCode)
            assertMigrated(fixture)
            assertTrue(fixture.callLog().toString(), "mount -o rw,remount /system" in fixture.callLog())
            // Owner, mode and label are kept beside each copy so a rollback can put them back exactly.
            assertEquals("0:0:755 $SYSTEM_BIN_LABEL\n",
                File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}.attr").readText())
            assertEquals("0:0:644 $REGISTRATION_LABEL\n",
                File(fixture.archive, "hapaneld-helper.rc.${fixture.registrationSha}.attr").readText())
            assertTrue(fixture.callLog().toString(), "stop hapaneld_helper" in fixture.callLog())
        }
    }

    @Test fun `the migration shell refuses every other system registration even one the takeover allows`() {
        withCustodyFiles(registrationBytes = releasedBaseSystemRegistration) { fixture ->
            assertEquals(1, runCommand(migrate(fixture)).exitCode)
            assertUntouched(fixture)
        }
    }

    @Test fun `a stale data local copy that is still running refuses the migration and keeps running`() {
        withCustodyFiles(staleBytes = null) { fixture ->
            File("/bin/sleep").copyTo(fixture.live)
            setMode(fixture.live, 700)
            val running = ProcessBuilder(fixture.live.absolutePath, "60").start()
            try {
                assertEquals(SystemCustodyLayout.Refused("stale_live_running", emptyList()), probe(fixture).withoutInventory())
                assertEquals(1, runCommand(migrate(fixture)).exitCode)
                assertTrue(running.isAlive)
                assertTrue(fixture.live.isFile)
                assertTrue(fixture.systemBin.isFile)
                assertTrue(fixture.registration.isFile)
                assertFalse(fixture.record.exists())
            } finally {
                running.destroyForcibly()
            }
        }
    }

    @Test fun `the candidate must report the exact empty Guard status`() {
        // EMPTY always prints generation 0, so anything else is not an idle candidate.
        listOf(
            "OK GUARDSTATUS 3 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0",
            emptyStatus.replace(" EMPTY ", " PREPARED "),
        ).forEach { status ->
            withCustodyFiles(guardStatus = status) { fixture ->
                assertEquals(status, 1, runCommand(migrate(fixture)).exitCode)
                assertRolledBack(fixture, status)
            }
        }
    }

    @Test fun `a Guard owner lock still held after the incumbent stopped rolls the panel back`() {
        withCustodyFiles(guardOwnerHeldAfterStop = true) { fixture ->
            val result = runCommand(migrate(fixture))
            assertEquals(result.output, 1, result.exitCode)
            assertTrue(result.output, "CUSTODY_REASON guard_namespace_not_empty" in result.output)
            assertRolledBack(fixture)
            assertFalse(fixture.callLog().any { it.startsWith("mount") })
        }
    }

    @Test fun `a refused remount rolls the panel back to the system helper`() {
        withCustodyFiles { fixture ->
            File(fixture.root, "remount-fails").writeText("")
            val result = runCommand(migrate(fixture))
            assertEquals(result.output, 1, result.exitCode)
            assertTrue(result.output, "CUSTODY_REASON system_removal_refused" in result.output)
            assertRolledBack(fixture)
        }
    }

    @Test fun `an archive copy that changed after archiving stops the removal`() {
        withCustodyFiles { fixture ->
            File(fixture.root, "tamper-on-stop")
                .writeText(File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}").absolutePath)
            assertEquals(1, runCommand(migrate(fixture)).exitCode)
            assertRolledBack(fixture)
            assertFalse(fixture.callLog().any { it.startsWith("mount") })
        }
    }

    @Test fun `a restore that cannot relabel the system files fails closed and keeps the archive`() {
        withCustodyFiles { fixture ->
            File(fixture.root, "chcon-fails").writeText("")
            fixture.signalOn("signal-on-remount-ro", "TERM")
            assertTrue(runCommand(migrate(fixture)).exitCode != 0)
            assertTrue(fixture.callLog().toString(), fixture.callLog().any { it.startsWith("chcon ") })
            assertTrue(fixture.record.isFile)
            assertEquals(fixture.oldBytes, File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}").readText())
            assertTrue(File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}.attr").isFile)
            assertFalse(fixture.callLog().any { it.startsWith("start") })
        }
    }

    @Test fun `a chcon that reports success without relabelling fails the restore closed`() {
        withCustodyFiles { fixture ->
            File(fixture.root, "chcon-noop").writeText("")
            fixture.signalOn("signal-on-remount-ro", "TERM")
            assertTrue(runCommand(migrate(fixture)).exitCode != 0)
            assertTrue(fixture.callLog().toString(), fixture.callLog().any { it.startsWith("chcon ") })
            assertTrue(fixture.record.isFile)
            assertEquals(fixture.oldBytes, File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}").readText())
            assertTrue(File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}.attr").isFile)
            assertFalse(fixture.callLog().any { it.startsWith("start") })
        }
    }

    @Test fun `an archived attribute record that changed or vanished after archiving stops the removal`() {
        listOf("tamper-on-stop", "delete-on-stop").forEach { hook ->
            listOf("system.%s.attr", "rc.%r.attr").forEach { name ->
                withCustodyFiles { fixture ->
                    val attr = File(fixture.archive, "hapaneld-helper." +
                        name.replace("%s", fixture.oldSha).replace("%r", fixture.registrationSha))
                    File(fixture.root, hook).writeText(attr.absolutePath)
                    val label = "$hook $name"
                    assertEquals(label, 1, runCommand(migrate(fixture)).exitCode)
                    assertRolledBack(fixture, label)
                    assertFalse(label, fixture.callLog().any { it.startsWith("mount") })
                }
            }
        }
    }

    @Test fun `a label that cannot be read refuses the migration before anything leaves system`() {
        withCustodyFiles { fixture ->
            File(fixture.root, "ls-fails").writeText("")
            assertEquals(1, runCommand(migrate(fixture)).exitCode)
            File(fixture.root, "ls-fails").delete()
            assertRolledBack(fixture)
            assertFalse(fixture.callLog().any { it.startsWith("mount") || it.startsWith("stop") })
        }
    }

    @Test fun `an already current start reads the migration record before the released takeover check`() {
        val calls = mutableListOf<String>()
        val current = { calls += "legacy takeover record"; null }
        val custody = { calls += "custody record"; null }
        assertEquals(BundledHelperInstaller.Result.ALREADY_CURRENT,
            bundledHelperStartGate(BundledHelperInstaller.Result.ALREADY_CURRENT, current, custody))
        assertEquals(listOf("custody record", "legacy takeover record"), calls)
        calls.clear()
        // A pending record wins: the released takeover check is not reached.
        assertEquals(BundledHelperInstaller.Result.INSTALLED, bundledHelperStartGate(
            BundledHelperInstaller.Result.ALREADY_CURRENT, current,
        ) { calls += "custody record"; BundledHelperInstaller.Result.INSTALLED })
        assertEquals(listOf("custody record"), calls)
        calls.clear()
        listOf(BundledHelperInstaller.Result.SKIPPED, BundledHelperInstaller.Result.REPROVISION_REQUIRED).forEach {
            assertEquals(it, bundledHelperStartGate(it, current, custody))
        }
        assertEquals(emptyList<String>(), calls)
        assertNull(bundledHelperStartGate(null, current, custody))
        assertEquals(listOf("custody record"), calls)
    }

    @Test fun `a cut after the candidate serves is finished by the next start through the start routing`() {
        val record = ".hapaneld-helper.custody-migration"
        val cuts = listOf<Pair<String, (CustodyFixture) -> Unit>>(
            "during swap" to { _ -> },
            "before removal" to { f -> f.signalOn("signal-on-remount-rw", "KILL") },
            "after removal" to { f -> f.signalOn("signal-on-remount-ro", "KILL") },
            "between the removals" to { f -> f.killAtSyncWhen(
                "[ ! -e '${f.root.absolutePath}/system/etc/init/hapaneld-helper.rc' ] && " +
                    "[ -e '${f.root.absolutePath}/system/bin/hapaneld-helper' ]") },
            "before the record is retired" to { f -> f.killAtSyncWhen(
                "[ ! -e '${f.root.absolutePath}/system/bin/hapaneld-helper' ] && [ -f '${f.dataLocal.absolutePath}/$record' ]") },
        )
        for (reboot in listOf(false, true)) cuts.forEach { (point, arm) ->
            val label = "$point reboot=$reboot"
            withCustodyFiles(signalOnSupervise = point == "during swap", superviseSignal = "KILL") { fixture ->
                arm(fixture)
                assertEquals(label, 137, runCommand(migrate(fixture)).exitCode)
                assertTrue(label, fixture.record.isFile)
                if (reboot) fixture.reboot() else fixture.sameBoot()
                // The bundled build answers as current: the start must still finish the transaction.
                val reasons = mutableListOf<String>()
                val outcome = startGate(fixture, BundledHelperInstaller.Result.ALREADY_CURRENT, reasons,
                    serving = { record -> record.candidateIdentity })
                assertEquals("$label $reasons", BundledHelperInstaller.Result.INSTALLED, outcome)
                assertMigrated(fixture, label)
            }
        }
    }

    @Test fun `a kill before the first record write reaches the migration again through the start routing`() {
        val record = ".hapaneld-helper.custody-migration"
        for (reboot in listOf(false, true)) withCustodyFiles { fixture ->
            val label = "reboot=$reboot"
            fixture.killAtSyncWhen("[ -f '${fixture.dataLocal.absolutePath}/$record.tmp' ] && " +
                "[ ! -f '${fixture.dataLocal.absolutePath}/$record' ]")
            assertEquals(label, 137, runCommand(migrate(fixture)).exitCode)
            File(fixture.root, "kill-on-sync").delete()
            assertTrue(label, File(fixture.dataLocal, "$record.tmp").isFile)
            assertFalse(label, fixture.record.exists())
            if (reboot) fixture.reboot() else fixture.sameBoot()
            // The incumbent still serves, so nothing answers as the bundled build.
            val reasons = mutableListOf<String>()
            assertNull("$label $reasons", startGate(fixture, null, reasons, serving = { null }))
            assertFalse(label, File(fixture.dataLocal, "$record.tmp").exists())
            assertTrue("$label ${probe(fixture)}", probe(fixture) is SystemCustodyLayout.LayoutF)
            fixture.stage.delete()
            val staged = runCommandWithInput(
                bundledHelperStageCommand(fixture.candidateSha, fixture.root.absolutePath), fixture.candidateBytes,
            )
            assertEquals("$label ${staged.output}", 0, staged.exitCode)
            val resumed = runCommand(migrate(fixture))
            assertEquals("$label: ${resumed.output}", 0, resumed.exitCode)
            assertMigrated(fixture, label)
        }
    }

    @Test fun `the start leaves a live transaction's temporary and a record's temporary alone`() {
        val record = ".hapaneld-helper.custody-migration"
        withCustodyFiles { fixture ->
            val tmp = File(fixture.dataLocal, "$record.tmp").apply { writeText("partial") }.also { setMode(it, 600) }
            val lock = File(fixture.root, "dev/.hapaneld-helper-transaction.lock").apply { mkdirs() }
            File(lock, "pid").writeText("${ProcessHandle.current().pid()}\n")
            assertEquals(1, runCommand(systemCustodyMigrationRecordReadCommand(fixture.root.absolutePath)).exitCode)
            assertTrue(tmp.isFile)
            assertTrue(lock.isDirectory)
            // A dead holder's lock is reclaimed and released.
            File(lock, "pid").writeText("999999999\n")
            assertEquals(1, runCommand(systemCustodyMigrationRecordReadCommand(fixture.root.absolutePath)).exitCode)
            assertFalse(tmp.exists())
            assertFalse(lock.exists())
        }
        withCustodyFiles { fixture ->
            val line = "OK CUSTODYMIGRATION 1 ARCHIVED ${"1".repeat(64)} 10 ${"2".repeat(64)} 119 NONE 0 " +
                "$incumbentBuild $stagedBuild ${"3".repeat(64)} 20\n"
            fixture.record.writeText(line); setMode(fixture.record, 600)
            val tmp = File(fixture.dataLocal, "$record.tmp").apply { writeText("partial") }.also { setMode(it, 600) }
            val read = runCommand(systemCustodyMigrationRecordReadCommand(fixture.root.absolutePath))
            assertEquals(0, read.exitCode)
            assertEquals(line, read.output)
            assertTrue(tmp.isFile)
        }
        withCustodyFiles { fixture ->
            // Anything but a root 0600 single-link file stays for the probe to refuse.
            val tmp = File(fixture.dataLocal, "$record.tmp").apply { writeText("partial") }.also { setMode(it, 644) }
            assertEquals(1, runCommand(systemCustodyMigrationRecordReadCommand(fixture.root.absolutePath)).exitCode)
            assertTrue(tmp.isFile)
            assertEquals(SystemCustodyLayout.Refused("custody_record_present", emptyList()), probe(fixture).withoutInventory())
        }
    }

    @Test fun `a start refuses a pending record it cannot own`() {
        val reasons = mutableListOf<String>()
        val refuse = { result: BundledHelperInstaller.Result, reason: String -> reasons += reason; result }
        val line = "OK CUSTODYMIGRATION 1 SWAPPED ${"1".repeat(64)} 10 ${"2".repeat(64)} 119 NONE 0 " +
            "$incumbentBuild $stagedBuild ${"3".repeat(64)} 20\n"
        val candidate = requireNotNull(parseSystemCustodyMigrationRecord(line)).candidateIdentity
        val valid = GuardDbMaintenanceClient.StatusProbe.Valid(requireNotNull(GuardDbMaintenanceProtocol.parseStatus(emptyStatus)))
        var driven = 0
        fun start(reply: String?, build: String = stagedBuild,
                  status: GuardDbMaintenanceClient.StatusProbe = valid,
                  self: GuardDbMaintenanceProtocol.SelfIdentity? = candidate) =
            systemCustodyStartOutcome(reply, build, { status }, { self }, { driven++; true }, refuse)
        assertNull(start(null))
        assertEquals(BundledHelperInstaller.Result.FAILED, start("garbage\n"))
        assertEquals(BundledHelperInstaller.Result.REPROVISION_REQUIRED, start(line, build = "e".repeat(64)))
        assertEquals(BundledHelperInstaller.Result.FAILED,
            start(line, self = candidate.copy(sha256 = "f".repeat(64))))
        assertEquals(BundledHelperInstaller.Result.FAILED, start(line, status = GuardDbMaintenanceClient.StatusProbe.Malformed))
        assertEquals(listOf("custody_record_malformed", "custody_record_other_build", "custody_foreign_helper",
            "custody_status_unusable"), reasons)
        assertEquals(0, driven)
        assertEquals(BundledHelperInstaller.Result.INSTALLED, start(line))
        assertEquals(BundledHelperInstaller.Result.INSTALLED,
            start(line, status = GuardDbMaintenanceClient.StatusProbe.Unreachable, self = null))
        assertEquals(2, driven)
    }

    @Test fun `a removal init still sees is restored and rolled back`() {
        withCustodyFiles { fixture ->
            // init's mount namespace keeps both files although this shell's view lost them.
            val initView = File(fixture.root, "init-view")
            File(initView, "system/bin").mkdirs()
            File(initView, "system/etc/init").mkdirs()
            fixture.systemBin.copyTo(File(initView, "system/bin/hapaneld-helper"))
            fixture.registration.copyTo(File(initView, "system/etc/init/hapaneld-helper.rc"))
            val link = File(fixture.root, "proc/1/root").toPath()
            Files.delete(link)
            Files.createSymbolicLink(link, initView.toPath())
            val result = runCommand(migrate(fixture))
            assertEquals(result.output, 1, result.exitCode)
            assertTrue(result.output, "CUSTODY_REASON system_removal_refused" in result.output)
            assertRolledBack(fixture)
        }
    }

    @Test fun `a signal at each step restores the system helper and a later run completes`() {
        val points = listOf(
            "after archive" to { f: CustodyFixture -> f.signalOn("signal-on-stop", "TERM") },
            "during swap" to { _: CustodyFixture -> },
            "before removal" to { f: CustodyFixture -> f.signalOn("signal-on-remount-rw", "TERM") },
            "after removal" to { f: CustodyFixture -> f.signalOn("signal-on-remount-ro", "TERM") },
        )
        points.forEach { (point, arm) ->
            withCustodyFiles(signalOnSupervise = point == "during swap") { fixture ->
                arm(fixture)
                val interrupted = runCommand(migrate(fixture))
                assertEquals("$point: ${interrupted.output}", 74, interrupted.exitCode)
                assertRolledBack(fixture, point)
            }
        }
    }

    @Test fun `power lost at each step converges on the next start in the same boot or after a reboot`() {
        val points = listOf<Pair<String, (CustodyFixture) -> Unit>>(
            "after archive" to { f -> f.signalOn("signal-on-stop", "KILL") },
            "during swap" to { _ -> },
            "before removal" to { f -> f.signalOn("signal-on-remount-rw", "KILL") },
            "after removal" to { f -> f.signalOn("signal-on-remount-ro", "KILL") },
        )
        for (reboot in listOf(false, true)) points.forEach { (point, arm) ->
            val label = "$point reboot=$reboot"
            withCustodyFiles(signalOnSupervise = point == "during swap", superviseSignal = "KILL") { fixture ->
                arm(fixture)
                assertEquals(label, 137, runCommand(migrate(fixture)).exitCode)
                assertTrue(label, fixture.record.isFile)
                // The dead shell's transaction lock is left behind; the next start reclaims it.
                assertTrue(label, File(fixture.root, "dev/.hapaneld-helper-transaction.lock").isDirectory)
                if (reboot) fixture.reboot() else fixture.sameBoot()
                val resumed = runCommand(migrate(fixture))
                assertEquals("$label: ${resumed.output}", 0, resumed.exitCode)
                assertMigrated(fixture, label)
            }
        }
    }

    @Test fun `a kill before any record write or between the removals converges`() {
        val record = ".hapaneld-helper.custody-migration"
        val cuts = listOf(
            "before the first record write" to "[ -f '%L/$record.tmp' ] && [ ! -f '%L/$record' ]",
            "before the swapped record write" to "[ -f '%L/$record.tmp' ] && grep -q ' ARCHIVED ' '%L/$record'",
            "between the removals" to "[ ! -e '%R/system/etc/init/hapaneld-helper.rc' ] && [ -e '%R/system/bin/hapaneld-helper' ]",
            "before the record is retired" to "[ ! -e '%R/system/bin/hapaneld-helper' ] && [ -f '%L/$record' ]",
        )
        for (reboot in listOf(false, true)) cuts.forEach { (point, condition) ->
            val label = "$point reboot=$reboot"
            withCustodyFiles { fixture ->
                fixture.killAtSyncWhen(condition.replace("%L", fixture.dataLocal.absolutePath)
                    .replace("%R", fixture.root.absolutePath))
                assertEquals(label, 137, runCommand(migrate(fixture)).exitCode)
                if (reboot) fixture.reboot() else fixture.sameBoot()
                val resumed = runCommand(migrate(fixture))
                assertEquals("$label: ${resumed.output}", 0, resumed.exitCode)
                assertMigrated(fixture, label)
            }
        }
    }

    @Test fun `a removal cut between the two system files is completed after the reboot`() {
        listOf("registration removed", "binary removed").forEach { cut ->
            withCustodyFiles { fixture ->
                fixture.signalOn("signal-on-remount-rw", "KILL")
                assertEquals(cut, 137, runCommand(migrate(fixture)).exitCode)
                if (cut == "registration removed") fixture.registration.delete() else fixture.systemBin.delete()
                fixture.reboot()
                val resumed = runCommand(migrate(fixture))
                assertEquals("$cut: ${resumed.output}", 0, resumed.exitCode)
                assertMigrated(fixture, cut)
            }
        }
    }

    @Test fun `a removal the overlay did not keep is refused once and never retried`() {
        withCustodyFiles { fixture ->
            assertEquals(0, runCommand(migrate(fixture)).exitCode)
            // The reboot brings both /system files back and init serves the incumbent again.
            writeExecutable(fixture.systemBin, fixture.oldBytes, 755)
            writeExecutable(fixture.registration, fixture.registrationBytes, 644)
            fixture.reboot()
            assertEquals(SystemCustodyLayout.Refused("system_removal_not_persistent", emptyList()),
                probe(fixture).withoutInventory())
            writeExecutable(fixture.stage, fixture.candidateBytes, 700)
            fixture.calls.delete()
            val again = runCommand(migrate(fixture))
            assertEquals(again.output, 1, again.exitCode)
            assertTrue(again.output, "CUSTODY_REASON system_removal_not_persistent" in again.output)
            assertFalse(fixture.record.exists())
            assertEquals(fixture.oldBytes, fixture.systemBin.readText())
            assertTrue(fixture.callLog().isEmpty())
        }
    }

    @Test fun `a system overlay whose upper layer is tmpfs is refused`() {
        withCustodyFiles { fixture ->
            File(fixture.root, "proc/1/mountinfo").writeText(
                "1 0 254:1 / / ro,relatime shared:1 - ext4 /dev/root ro\n" +
                    "20 1 0:20 / /mnt/scratch rw,relatime shared:3 - tmpfs tmpfs rw\n" +
                    "25 1 0:25 / /system ro,relatime shared:2 - overlay overlay ro,lowerdir=/x,upperdir=/mnt/scratch/upper,workdir=/mnt/scratch/work\n",
            )
            assertEquals(SystemCustodyLayout.Refused("system_overlay_not_persistent", emptyList()),
                probe(fixture).withoutInventory())
            val result = runCommand(migrate(fixture))
            assertEquals(result.output, 1, result.exitCode)
            assertTrue(result.output, "CUSTODY_REASON system_overlay_not_persistent" in result.output)
            assertRolledBack(fixture)
            assertFalse(fixture.callLog().any { it.startsWith("mount") })
        }
    }

    @Test fun `the migrated layout is a plain fresh install after the reboot even after an app update`() {
        withCustodyFiles { fixture ->
            assertEquals(0, runCommand(migrate(fixture)).exitCode)
            assertFalse(fixture.record.exists())
            // An app update before the reboot replaced the live helper through Guard; the next build stages.
            val newer = BundledHelperShellFixtures.fakeHelper("d".repeat(64), candidate = true, starts = true)
            writeExecutable(fixture.live, newer, 700)
            fixture.reboot()
            writeExecutable(fixture.stage, newer, 700)
            val accepted = runCommand(bundledFreshHelperInstallCommand(
                sha256(newer), "d".repeat(64), filesystemRoot = fixture.root.absolutePath, polls = 1,
            ))
            assertEquals(accepted.output, 0, accepted.exitCode)
            assertEquals(newer, fixture.live.readText())
        }
    }

    @Test fun `fresh install refuses while a migration record or its temporary exists`() {
        listOf(".hapaneld-helper.custody-migration", ".hapaneld-helper.custody-migration.tmp").forEach { name ->
            withCustodyFiles(staleBytes = null) { fixture ->
                fixture.systemBin.delete()
                fixture.registration.delete()
                File(fixture.dataLocal, name).apply { writeText("held\n") }.also { setMode(it, 600) }
                assertEquals(name, 1, runCommand(bundledFreshHelperInstallCommand(
                    fixture.candidateSha, stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
                )).exitCode)
                assertFalse(name, fixture.live.exists())
            }
        }
    }

    @Test fun `staging is fenced by the migration record and its temporary`() {
        listOf(".hapaneld-helper.custody-migration", ".hapaneld-helper.custody-migration.tmp").forEach { name ->
            withCustodyFiles { fixture ->
                fixture.stage.delete()
                File(fixture.dataLocal, name).apply { writeText("held\n") }.also { setMode(it, 600) }
                val staged = runCommandWithInput(
                    bundledHelperStageCommand(fixture.candidateSha, fixture.root.absolutePath),
                    fixture.candidateBytes,
                )
                assertEquals("$name: ${staged.output}", 75, staged.exitCode)
                assertFalse(name, fixture.stage.exists())
                assertEquals(name, "held\n", File(fixture.dataLocal, name).readText())
            }
        }
    }

    @Test fun `each refused topology has its own reason`() {
        val cases = listOf<Pair<String, (CustodyFixture) -> Unit>>(
            "vendor_registration" to { f -> put(f, "vendor/etc/init/hapaneld-helper.rc", "service x /x\n") },
            "legacy_tmp_layout" to { f -> put(f, "data/local/tmp/hapaneld-helper", "#!/bin/sh\n") },
            "systemless_guard_custody" to { f -> put(f, "data/adb/hapaneld/hapaneld-helper", "#!/bin/sh\n") },
            "registration_unknown" to { f -> f.registration.appendText("# changed\n") },
            "registration_unknown" to { f -> put(f, "data/adb/service.d/hapaneld-helper.sh", "#!/bin/sh\n") },
            "registration_sibling" to { f -> put(f, "system/etc/init/hapaneld-helper.rc.bak", "x\n") },
            "ledd_present" to { f -> put(f, "system/bin/hapaneld-ledd", "#!/bin/sh\n") },
            "journal_present" to { f -> put(f, "data/local/.hapaneld-guard-db/replacement.v1", "x\n") },
            "previous_present" to { f -> put(f, "data/local/.hapaneld-helper.previous", "x\n") },
            "legacy_takeover_record" to { f -> put(f, "data/local/.hapaneld-helper.legacy-takeover", "x\n") },
            "guard_db_not_empty" to { f -> put(f, "data/local/.hapaneld-guard-db/manifest.v1", "x\n") },
            "system_binary_unexpected" to { f -> setMode(f.systemBin, 700) },
            "no_system_helper" to { f -> f.systemBin.delete(); f.registration.delete() },
            "symlink" to { f ->
                f.live.delete()
                Files.createSymbolicLink(f.live.toPath(), f.stage.toPath())
            },
        )
        cases.forEach { (reason, arrange) ->
            withCustodyFiles { fixture ->
                arrange(fixture)
                assertEquals(reason, SystemCustodyLayout.Refused(reason, emptyList()), probe(fixture).withoutInventory())
            }
        }
    }

    @Test fun `a helper still running from a replaced data local inode is the stale live topology`() {
        withCustodyFiles(staleBytes = null) { fixture ->
            File("/bin/sleep").copyTo(fixture.live)
            setMode(fixture.live, 700)
            val running = ProcessBuilder(fixture.live.absolutePath, "60").start()
            try {
                fixture.live.delete()
                writeExecutable(fixture.live, fixture.candidateBytes, 700)
                assertEquals(SystemCustodyLayout.Refused("stale_live_inode", emptyList()), probe(fixture).withoutInventory())
            } finally {
                running.destroyForcibly()
            }
        }
    }

    @Test fun `a probe that runs out of time is told apart from one that failed`() {
        var now = 0L
        val timedOut = probeSystemCustodyLayout(
            execute = { _, timeout -> now += timeout; null },
            monotonicMs = { now },
        )
        assertEquals(SystemCustodyLayout.Refused("probe_timeout", emptyList()), timedOut)
        now = 0L
        val failed = probeSystemCustodyLayout(execute = { _, _ -> now += 40; null }, monotonicMs = { now })
        assertEquals(SystemCustodyLayout.Refused("probe_failed", emptyList()), failed)
        var asked = 0L
        probeSystemCustodyLayout(execute = { _, timeout -> asked = timeout; "LAYOUT_F\n" }, monotonicMs = { 0L })
        assertEquals(SYSTEM_CUSTODY_PROBE_TIMEOUT_MS, asked)
        assertTrue(SYSTEM_CUSTODY_PROBE_TIMEOUT_MS >= 60_000L)
    }

    @Test fun `the probe and the shells scan processes in one pass`() {
        // A fork per process costs seconds on a panel with hundreds of them: each scan is one stat call.
        listOf(
            bundledHelperSystemCustodyProbeCommand(),
            bundledLegacyHelperTakeoverCommand("c".repeat(64), stagedBuild, incumbentBuild, systemCustodyMigration = true),
            bundledLegacyHelperTakeoverCommand("c".repeat(64), stagedBuild, incumbentBuild),
            bundledFreshHelperInstallCommand("c".repeat(64), stagedBuild),
        ).forEach { command ->
            assertFalse(command, Regex("for [a-z_]+ in /proc/\\[0-9\\]\\*/exe").containsMatchIn(command))
            assertFalse(command, "stat -Lc '%d:%i' \"\$executable\"" in command)
        }
    }

    @Test fun `the probe prints the inventory the hardware acceptance checks`() {
        withCustodyFiles { fixture ->
            val inventory = probe(fixture).inventory.joinToString("\n")
            listOf(
                "INV registration 0:0:644:1:119 ${fixture.registrationSha}",
                "INV system_bin 0:0:755:1:${fixture.oldBytes.length} ${fixture.oldSha}",
                "INV live 0:0:700:1:${fixture.staleBytes!!.length} ${sha256(fixture.staleBytes!!)} running=0",
                "INV guard_db .owner.lock",
                "INV boot_id $FIRST_BOOT_ID",
                "INV mount 25 1 0:25 / /system ro,relatime shared:2 - overlay overlay ro,lowerdir=/x",
            ).forEach { assertTrue("$it\n$inventory", inventory.lines().contains(it)) }
        }
    }

    @Test fun `the migration record parser accepts only the exact line`() {
        val line = "OK CUSTODYMIGRATION 1 SWAPPED ${"1".repeat(64)} 10 ${"2".repeat(64)} 119 NONE 0 " +
            "$incumbentBuild $stagedBuild ${"3".repeat(64)} 20\n"
        val parsed = requireNotNull(parseSystemCustodyMigrationRecord(line))
        assertEquals("SWAPPED", parsed.phase)
        assertNull(parsed.staleSha256)
        assertEquals(sha256(line), parsed.recordSha256)
        assertNotNull(parseSystemCustodyMigrationRecord(line.replace("SWAPPED", "ARCHIVED")))
        assertNull(parseSystemCustodyMigrationRecord(line.replace("SWAPPED", "SYSTEM_REMOVED")))
        assertNull(parseSystemCustodyMigrationRecord(line.replace(" 20\n", " 20 extra\n")))
        assertNull(parseSystemCustodyMigrationRecord(line.replace(" NONE 0 ", " NONE 5 ")))
        assertNull(parseSystemCustodyMigrationRecord(line.replace("OK ", "OK  ")))
    }

    /**
     * The production start routing over a fixture: the gate, the record command, the start outcome and the
     * takeover shell the record names. [serving] stands in for the socket's GUARDSELF answer.
     */
    private fun startGate(
        fixture: CustodyFixture,
        admission: BundledHelperInstaller.Result?,
        reasons: MutableList<String>,
        serving: (SystemCustodyMigrationRecord) -> GuardDbMaintenanceProtocol.SelfIdentity?,
    ): BundledHelperInstaller.Result? = bundledHelperStartGate(
        admission,
        currentLegacyCustody = { null },
        systemCustody = {
            val read = runCommand(systemCustodyMigrationRecordReadCommand(fixture.root.absolutePath))
            val reply = read.output.takeIf { read.exitCode == 0 }
            val pending = reply?.let(::parseSystemCustodyMigrationRecord)
            systemCustodyStartOutcome(
                reply, stagedBuild,
                statusProbe = {
                    if (pending != null && serving(pending) != null) {
                        GuardDbMaintenanceClient.StatusProbe.Valid(
                            requireNotNull(GuardDbMaintenanceProtocol.parseStatus(emptyStatus)))
                    } else GuardDbMaintenanceClient.StatusProbe.Unreachable
                },
                selfIdentity = { pending?.let(serving) },
                drive = { record ->
                    val driven = runCommand("PATH='${File(fixture.root, "stubbin").absolutePath}':\$PATH\n" +
                        bundledLegacyHelperTakeoverCommand(
                            record.candidateSha256, record.stagedBuildId, record.incumbentBuildId,
                            filesystemRoot = fixture.root.absolutePath, polls = 1, systemCustodyMigration = true,
                        ))
                    if (driven.exitCode != 0) reasons += driven.output.takeLast(400)
                    driven.exitCode == 0
                },
                refused = { result, reason -> reasons += reason; result },
            )
        },
    )

    private fun migrate(fixture: CustodyFixture, polls: Int = 1) =
        "PATH='${File(fixture.root, "stubbin").absolutePath}':\$PATH\n" + bundledLegacyHelperTakeoverCommand(
            fixture.candidateSha, stagedBuild, incumbentBuild,
            filesystemRoot = fixture.root.absolutePath, polls = polls, systemCustodyMigration = true,
        )

    private fun probe(fixture: CustodyFixture): SystemCustodyLayout =
        parseSystemCustodyProbe(runCommand(bundledHelperSystemCustodyProbeCommand(fixture.root.absolutePath)).output)

    private fun SystemCustodyLayout.withoutInventory(): SystemCustodyLayout = when (this) {
        is SystemCustodyLayout.LayoutF -> copy(inventory = emptyList())
        is SystemCustodyLayout.Refused -> copy(inventory = emptyList())
    }

    private fun put(fixture: CustodyFixture, path: String, bytes: String) {
        File(fixture.root, path).apply { parentFile!!.mkdirs(); writeText(bytes) }
    }

    /** The plain app-lease layout: no /system helper, the bundled copy live, and no record left to settle. */
    private fun assertMigrated(fixture: CustodyFixture, label: String = "") {
        assertFalse(label, fixture.systemBin.exists())
        assertFalse(label, fixture.registration.exists())
        assertEquals(label, fixture.candidateBytes, fixture.live.readText())
        assertFalse(label, fixture.stage.exists())
        assertEquals(label, fixture.registrationBytes,
            File(fixture.archive, "hapaneld-helper.rc.${fixture.registrationSha}").readText())
        assertEquals(label, fixture.oldBytes, File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}").readText())
        fixture.staleBytes?.let {
            assertEquals(label, it, File(fixture.archive, "hapaneld-helper.stale.${sha256(it)}").readText())
        }
        assertFalse(label, fixture.record.exists())
        assertFalse(label, File(fixture.dataLocal, ".hapaneld-helper.custody-migration.tmp").exists())
        assertTrue(label, File(fixture.archive, "hapaneld-helper.removed.${fixture.oldSha}").isFile)
        assertFalse(label, File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").exists())
        assertFalse(label, fixture.callLog().any { it.startsWith("start") })
        assertFalse(label, File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
    }

    /** The incumbent serves again from its untouched /system files; only the stale copy stays archived. */
    private fun assertRolledBack(fixture: CustodyFixture, label: String = "") {
        assertTrue("$label: /system binary missing", fixture.systemBin.isFile)
        assertTrue("$label: /system rc missing", fixture.registration.isFile)
        assertEquals(label, fixture.oldBytes, fixture.systemBin.readText())
        assertTrue(label, fixture.systemBin.canExecute())
        assertEquals(label, fixture.registrationBytes, fixture.registration.readText())
        assertFalse(label, fixture.live.exists())
        assertEquals(label, fixture.candidateBytes, fixture.stage.readText())
        assertFalse(label, fixture.record.exists())
        assertTrue(label + fixture.callLog(), "start hapaneld_helper" in fixture.callLog())
        assertTrue(label, fixture.incumbentReady.isFile)
        assertEquals(label, SYSTEM_BIN_LABEL, fixture.labelOf(fixture.systemBin))
        assertEquals(label, REGISTRATION_LABEL, fixture.labelOf(fixture.registration))
        assertFalse(label, File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
    }

    private fun assertUntouched(fixture: CustodyFixture) {
        assertEquals(fixture.oldBytes, fixture.systemBin.readText())
        assertEquals(fixture.registrationBytes, fixture.registration.readText())
        assertEquals(fixture.staleBytes, fixture.live.readText())
        assertEquals(fixture.candidateBytes, fixture.stage.readText())
        assertFalse(fixture.record.exists())
        assertFalse(fixture.archive.exists())
        assertTrue(fixture.callLog().isEmpty())
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
}
