package io.panelassistant.android.util

import io.panelassistant.android.util.BundledHelperShellFixtures.CustodyFixture
import io.panelassistant.android.util.BundledHelperShellFixtures.FIRST_BOOT_ID
import io.panelassistant.android.util.BundledHelperShellFixtures.SECOND_BOOT_ID
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
            assertMigrated(fixture, FIRST_BOOT_ID)
            assertTrue(fixture.callLog().toString(), "mount -o rw,remount /system" in fixture.callLog())
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

    @Test fun `the candidate may report counters the incumbent left but never a Guard phase that owns recovery`() {
        withCustodyFiles(guardStatus = "OK GUARDSTATUS 3 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0") { fixture ->
            val result = runCommand(migrate(fixture))
            assertEquals(result.output, 0, result.exitCode)
            assertMigrated(fixture, FIRST_BOOT_ID)
        }
        withCustodyFiles(guardStatus = emptyStatus.replace(" EMPTY ", " PREPARED ")) { fixture ->
            assertEquals(1, runCommand(migrate(fixture)).exitCode)
            assertRolledBack(fixture)
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

    @Test fun `power lost at each step converges on the next start`() {
        val points = listOf(
            "after archive" to { f: CustodyFixture -> f.signalOn("signal-on-stop", "KILL") },
            "during swap" to { _: CustodyFixture -> },
            "before removal" to { f: CustodyFixture -> f.signalOn("signal-on-remount-rw", "KILL") },
            "after removal" to { f: CustodyFixture -> f.signalOn("signal-on-remount-ro", "KILL") },
        )
        points.forEach { (point, arm) ->
            withCustodyFiles(signalOnSupervise = point == "during swap", superviseSignal = "KILL") { fixture ->
                arm(fixture)
                assertEquals(point, 137, runCommand(migrate(fixture)).exitCode)
                assertTrue(point, fixture.record.isFile)
                fixture.root.listFiles()!!.filter { it.name.startsWith("signal-on-") }.forEach(File::delete)
                fixture.reboot()
                val resumed = runCommand(migrate(fixture))
                assertEquals("$point: ${resumed.output}", 0, resumed.exitCode)
                assertMigrated(fixture, SECOND_BOOT_ID, point)
            }
        }
    }

    @Test fun `a removal cut between the two system files is completed after the reboot`() {
        listOf("registration removed", "binary removed").forEach { cut ->
            withCustodyFiles { fixture ->
                fixture.signalOn("signal-on-remount-rw", "KILL")
                assertEquals(cut, 137, runCommand(migrate(fixture)).exitCode)
                File(fixture.root, "signal-on-remount-rw").delete()
                if (cut == "registration removed") fixture.registration.delete() else fixture.systemBin.delete()
                fixture.reboot()
                val resumed = runCommand(migrate(fixture))
                assertEquals("$cut: ${resumed.output}", 0, resumed.exitCode)
                assertMigrated(fixture, SECOND_BOOT_ID, cut)
            }
        }
    }

    @Test fun `settlement waits for a reboot and then retires the migration record`() {
        withCustodyFiles { fixture ->
            assertEquals(0, runCommand(migrate(fixture)).exitCode)
            val record = requireNotNull(parseSystemCustodyMigrationRecord(fixture.record.readText()))
            val kept = runCommand(systemCustodyMigrationSettleCommand(record, fixture.root.absolutePath))
            assertEquals(kept.output, 0, kept.exitCode)
            assertTrue(kept.output, "KEPT same_boot" in kept.output)
            assertTrue(fixture.record.isFile)

            // After the reboot nothing registers a helper: the socket is unreachable until the app
            // relaunches the bundled copy under the record, and only then is the record retired.
            fixture.reboot()
            val relaunched = runCommand(migrate(fixture))
            assertEquals(relaunched.output, 0, relaunched.exitCode)
            assertEquals(record.copy(recordSha256 = "", recordBytes = 0),
                parseSystemCustodyMigrationRecord(fixture.record.readText())!!.copy(recordSha256 = "", recordBytes = 0))
            assertFalse(fixture.callLog().any { it.startsWith("start") })
            val settled = runCommand(systemCustodyMigrationSettleCommand(record, fixture.root.absolutePath))
            assertEquals(settled.output, 0, settled.exitCode)
            assertTrue(settled.output, "SETTLED" in settled.output)
            assertFalse(fixture.record.exists())
            assertTrue(File(fixture.archive, "hapaneld-helper.system.${fixture.oldSha}").isFile)
        }
    }

    @Test fun `settlement refuses when the system helper came back`() {
        withCustodyFiles { fixture ->
            assertEquals(0, runCommand(migrate(fixture)).exitCode)
            val record = parseSystemCustodyMigrationRecord(fixture.record.readText())!!
            writeExecutable(fixture.systemBin, fixture.oldBytes, 755)
            fixture.reboot()
            assertEquals(1, runCommand(systemCustodyMigrationSettleCommand(record, fixture.root.absolutePath)).exitCode)
            assertTrue(fixture.record.isFile)
        }
    }

    @Test fun `fresh install refuses while the record exists and accepts the migrated layout`() {
        withCustodyFiles { fixture ->
            assertEquals(0, runCommand(migrate(fixture)).exitCode)
            fixture.reboot()
            writeExecutable(fixture.stage, fixture.candidateBytes, 700)
            val fresh = bundledFreshHelperInstallCommand(
                fixture.candidateSha, stagedBuild, filesystemRoot = fixture.root.absolutePath, polls = 1,
            )
            assertEquals(1, runCommand(fresh).exitCode)
            assertTrue(fixture.record.isFile)

            val record = parseSystemCustodyMigrationRecord(fixture.record.readText())!!
            assertEquals(0, runCommand(systemCustodyMigrationSettleCommand(record, fixture.root.absolutePath)).exitCode)
            assertFalse(fixture.record.exists())
            writeExecutable(fixture.stage, fixture.candidateBytes, 700)
            val accepted = runCommand(fresh)
            assertEquals(accepted.output, 0, accepted.exitCode)
            assertEquals(fixture.candidateBytes, fixture.live.readText())

            // A record write cut before its rename still fences the fresh install.
            val tmp = File(fixture.dataLocal, ".hapaneld-helper.custody-migration.tmp").apply { writeText("partial\n") }
            setMode(tmp, 600)
            writeExecutable(fixture.stage, fixture.candidateBytes, 700)
            assertEquals(1, runCommand(fresh).exitCode)
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
            "$incumbentBuild $stagedBuild ${"3".repeat(64)} 20 NONE\n"
        val parsed = requireNotNull(parseSystemCustodyMigrationRecord(line))
        assertEquals("SWAPPED", parsed.phase)
        assertNull(parsed.staleSha256)
        assertNull(parsed.bootId)
        assertEquals(sha256(line), parsed.recordSha256)
        assertNull(parseSystemCustodyMigrationRecord(line.replace("SWAPPED", "RC_WRITTEN")))
        assertNull(parseSystemCustodyMigrationRecord(line.replace(" NONE\n", " NONE extra\n")))
        assertNull(parseSystemCustodyMigrationRecord(line.replace(" NONE 0 ", " NONE 5 ")))
        assertNull(parseSystemCustodyMigrationRecord(line.replace("OK ", "OK  ")))
    }

    private fun migrate(fixture: CustodyFixture, polls: Int = 1) = bundledLegacyHelperTakeoverCommand(
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

    private fun assertMigrated(fixture: CustodyFixture, bootId: String, label: String = "") {
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
        val fields = fixture.record.readText().trimEnd('\n').split(' ')
        assertEquals(label, "SYSTEM_REMOVED", fields[3])
        assertEquals(label, bootId, fields.last())
        assertFalse(label, File(fixture.dataLocal, ".hapaneld-helper.legacy-takeover").exists())
        assertFalse(label, fixture.callLog().any { it.startsWith("start") })
        assertFalse(label, File(fixture.root, "dev/.hapaneld-helper-transaction.lock").exists())
    }

    /** The incumbent serves again from its untouched /system files; only the stale copy stays archived. */
    private fun assertRolledBack(fixture: CustodyFixture, label: String = "") {
        assertEquals(label, fixture.oldBytes, fixture.systemBin.readText())
        assertTrue(label, fixture.systemBin.canExecute())
        assertEquals(label, fixture.registrationBytes, fixture.registration.readText())
        assertFalse(label, fixture.live.exists())
        assertEquals(label, fixture.candidateBytes, fixture.stage.readText())
        assertFalse(label, fixture.record.exists())
        assertTrue(label + fixture.callLog(), "start hapaneld_helper" in fixture.callLog())
        assertTrue(label, fixture.incumbentReady.isFile)
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
