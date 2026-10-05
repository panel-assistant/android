package io.panelassistant.android.device.profile

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A profile catalog carried from one identity of the app to the other, across a release boundary.
 *
 * The source is the legacy identity on an older release; the destination is a successor that has not
 * restored anything, on the release that retired the source's bundled revision. Each keeps its own
 * durable state, exactly as two packages on one panel do, and the backup crosses between them as JSON.
 *
 * The case that matters is a source whose automatic update to a new bundled revision did not report
 * healthy: it is left `ROLLED_BACK` and pinned to the retained snapshot of the old revision, which no
 * backup can carry. Before this, that pin made the whole catalog unrestorable, and the restore that
 * refused it ran after the legacy app had handed the panel over.
 */
class ProfileCatalogMigrationRestoreTest {
    private lateinit var sourceDirectory: File
    private lateinit var destinationDirectory: File
    private val sourcePreferences = TransientProfilePreferences()
    private val destinationPreferences = TransientProfilePreferences()

    private val board = DeviceFacts("test-panel", "test-device", "fw-1")
    private val vendorId = "vendor.test-panel"
    private val genericYaml = ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true))
    private val oldVendorYaml = vendorYaml(version = "1.0.0")
    private val newVendorYaml = vendorYaml(version = "1.0.1")
    private val releaseOne = mapOf("generic.yaml" to genericYaml, "panel.yaml" to oldVendorYaml)
    private val releaseTwo = mapOf("generic.yaml" to genericYaml, "panel.yaml" to newVendorYaml)
    private val genericRef = ProfileRef("generic", ProfileYaml.sha256(genericYaml))
    private val oldRef = ProfileRef(vendorId, ProfileYaml.sha256(oldVendorYaml))
    private val newRef = ProfileRef(vendorId, ProfileYaml.sha256(newVendorYaml))

    @Before fun setUp() {
        sourceDirectory = Files.createTempDirectory("profile-restore-source").toFile()
        destinationDirectory = Files.createTempDirectory("profile-restore-destination").toFile()
    }

    @After fun tearDown() {
        sourceDirectory.deleteRecursively()
        destinationDirectory.deleteRecursively()
    }

    @Test fun `a rolled back source pinned to a retired bundled revision restores onto the current revision`() {
        val backup = carried(rolledBackSource())
        assertEquals("the backup carries the rollback's pin", ProfileSelection.Pinned(oldRef), backup.selection)
        assertTrue("a bundled snapshot is never exported", backup.revisions.isEmpty())
        val destination = successor()

        val plan = destination.planBackupRestore(backup)

        assertTrue(plan.issues.toString(), plan.valid)
        assertEquals(ProfileSelection.Pinned(newRef), plan.selection)
        assertTrue(plan.restartRequired)
        val moved = selectionIssue(plan)
        assertEquals(ProfileIssueSeverity.WARNING, moved.severity)
        assertEquals(
            ProfilePresentation(
                "pinned-revision-retired",
                mapOf("id" to vendorId, "retired_revision" to oldRef.revision.take(12), "current_revision" to newRef.revision.take(12)),
            ),
            moved.presentation,
        )
        assertAuthoritativeEnglishMatches(moved.message, moved.presentation!!)
        assertTrue(plan.issues.none { it.severity == ProfileIssueSeverity.ERROR })

        val result = destination.restoreBackup(backup, plan.expectedCatalogRevision)

        assertEquals(ProfileBackupRestoreOutcome.SUCCEEDED, result.outcome)
        assertTrue(result.selectionStaged)
        assertTrue("the move is reported with the restore", moved in result.issues)
        val staged = destination.status()
        assertEquals(ProfileActivationPhase.PENDING, staged.activation.phase)
        assertEquals(ProfileSelection.Pinned(newRef), staged.activation.desired)
        assertEquals("the destination's own selection is the rollback target", ProfileSelection.Auto, staged.activation.previous)

        val restarted = successor()
        val applying = restarted.resolveForStartup()
        assertEquals(newRef, applying.summary.ref)
        assertTrue(restarted.markActivationHealthy(requireNotNull(applying.activationGeneration)))
        assertEquals(newRef, restarted.status().active!!.ref)
        assertEquals(ProfileSelection.Auto, restarted.status().lastKnownGood)
    }

    @Test fun `a moved pin that fails health again never comes back to the failed revision`() {
        val backup = carried(rolledBackSource())
        val destination = successor()
        destination.restoreBackup(backup, destination.planBackupRestore(backup).expectedCatalogRevision)
        assertEquals(newRef, successor().resolveForStartup().summary.ref)

        val recovered = successor().resolveForStartup()

        assertEquals("automatic matching resolves to the failed revision, so the rollback skips it", genericRef, recovered.summary.ref)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, successor().status().activation.phase)
    }

    @Test fun `a catalog that already restores is restored exactly as before`() {
        val source = install(releaseOne, sourceDirectory, sourcePreferences)
        source.resolveForStartup()
        assertTrue(source.markResolvedStartupHealthy())
        val updated = install(releaseTwo, sourceDirectory, sourcePreferences)
        assertTrue(updated.markActivationHealthy(requireNotNull(updated.resolveForStartup().activationGeneration)))
        val backup = carried(install(releaseTwo, sourceDirectory, sourcePreferences))
        assertEquals(ProfileSelection.Auto, backup.selection)
        assertEquals(ProfileSelection.Pinned(oldRef), backup.lastKnownGood)
        val destination = successor()

        val plan = destination.planBackupRestore(backup)
        val result = destination.restoreBackup(backup, plan.expectedCatalogRevision)

        assertTrue(plan.valid)
        assertEquals(ProfileSelection.Auto, plan.selection)
        assertFalse(plan.restartRequired)
        assertTrue(plan.issues.none { it.path == "profiles.selection" })
        assertEquals(ProfileBackupRestoreOutcome.SUCCEEDED, result.outcome)
        assertFalse(result.selectionStaged)
        assertEquals(ProfilePresentation("backup-restored-selection-unchanged"), result.presentation)
        assertEquals(ProfileSelection.Auto, destination.status().selection)
    }

    @Test fun `a pin carried with its own revision is restored exactly, never moved`() {
        val importedYaml = ProfileYaml.serialize(testProfileDocument(id = "community.example.panel", facts = board))
        val importedRef = ProfileRef("community.example.panel", ProfileYaml.sha256(importedYaml))
        val backup = ProfileBackup(
            revisions = listOf(ProfileBackupRevision(importedRef, importedYaml)),
            selection = ProfileSelection.Pinned(importedRef),
            active = importedRef,
            lastKnownGood = null,
        )

        val plan = successor().planBackupRestore(backup)

        assertTrue(plan.valid)
        assertEquals(ProfileSelection.Pinned(importedRef), plan.selection)
        assertTrue(plan.issues.none { it.presentation?.code == "pinned-revision-retired" })
    }

    @Test fun `a missing revision of an imported lineage is still refused`() {
        val importedYaml = ProfileYaml.serialize(testProfileDocument(id = vendorId, version = "9.0.0", facts = board))
        val importedRef = ProfileRef(vendorId, ProfileYaml.sha256(importedYaml))
        val lost = ProfileRef(vendorId, "e".repeat(64))
        val backup = ProfileBackup(
            revisions = listOf(ProfileBackupRevision(importedRef, importedYaml)),
            selection = ProfileSelection.Pinned(lost),
            active = lost,
            lastKnownGood = null,
        )

        assertRefused(successor().planBackupRestore(backup))
    }

    @Test fun `a pinned profile this release does not ship at all is still refused`() {
        val dropped = ProfileRef("vendor.dropped", "d".repeat(64))
        val backup = ProfileBackup(
            revisions = emptyList(),
            selection = ProfileSelection.Pinned(dropped),
            active = dropped,
            lastKnownGood = null,
        )
        val destination = successor()

        val plan = destination.planBackupRestore(backup)
        val result = destination.restoreBackup(backup, plan.expectedCatalogRevision)

        assertRefused(plan)
        assertEquals(ProfileBackupRestoreOutcome.REJECTED, result.outcome)
        assertEquals("a refused restore changes nothing", ProfileSelection.Auto, destination.status().selection)
    }

    /** Holds because the catalog never admits a bundled asset with errors; the planner adds no check of its own. */
    @Test fun `a current bundled revision this core cannot run is not a destination for the pin`() {
        val backup = carried(rolledBackSource())
        val incompatible = ProfileYaml.serialize(
            testProfileDocument(id = vendorId, version = "1.0.1", facts = board)
                .copy(requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "led.removed-driver"))),
        )
        val destination = install(mapOf("generic.yaml" to genericYaml, "panel.yaml" to incompatible), destinationDirectory, destinationPreferences)

        assertRefused(destination.planBackupRestore(backup))
    }

    @Test fun `a destination already on the current revision stages nothing`() {
        val backup = carried(rolledBackSource())
        val destination = successor()
        val selected = destination.select(ProfileSelection.Pinned(newRef), destination.status().catalogRevision)
        assertTrue(selected.toString(), selected is ProfileMutation.Success)
        val restarted = successor()
        assertTrue(restarted.markActivationHealthy(requireNotNull(restarted.resolveForStartup().activationGeneration)))

        val plan = successor().planBackupRestore(backup)

        assertTrue(plan.valid)
        assertEquals(ProfileSelection.Pinned(newRef), plan.selection)
        assertFalse("the moved pin is already the destination's selection", plan.restartRequired)
    }

    @Test fun `the bundled-only planner writes nothing and matches a real fresh successor`() {
        val backup = carried(rolledBackSource())
        val bundledOnly = RuntimeProfileRegistry(
            filesDir = File(destinationDirectory, "absent"),
            preferences = TransientProfilePreferences(),
            bundledLoader = { releaseTwo },
            facts = board,
            coreVersion = "1.0.0",
            clock = { 1000L },
        )

        val planned = bundledOnly.planBackupRestore(backup)

        assertEquals(successor().planBackupRestore(backup).copy(status = planned.status), planned)
        assertFalse(File(destinationDirectory, "absent").exists())
    }

    /** The legacy app's state after its automatic update to the new bundled revision rolled back. */
    private fun rolledBackSource(): RuntimeProfileRegistry {
        val first = install(releaseOne, sourceDirectory, sourcePreferences)
        assertEquals(oldRef, first.resolveForStartup().summary.ref)
        assertTrue(first.markResolvedStartupHealthy())
        val updating = install(releaseTwo, sourceDirectory, sourcePreferences).resolveForStartup()
        assertEquals(newRef, updating.summary.ref)
        assertNotNull(updating.activationGeneration)
        val rolledBack = install(releaseTwo, sourceDirectory, sourcePreferences)
        assertEquals(oldRef, rolledBack.resolveForStartup().summary.ref)
        val status = rolledBack.status()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
        assertEquals(ProfileSelection.Pinned(oldRef), status.selection)
        assertEquals("activation-rolled-back-unhealthy-pinned", status.activation.presentation?.code)
        return rolledBack
    }

    /** The backup exactly as it reaches the successor: through its JSON form. */
    private fun carried(source: RuntimeProfileRegistry): ProfileBackup =
        requireNotNull(ProfileBackup.fromJson(JSONObject(source.exportBackup().toJson().toString())).payload)

    private fun successor() = install(releaseTwo, destinationDirectory, destinationPreferences)

    private fun install(bundled: Map<String, String>, directory: File, preferences: ProfilePreferences) = RuntimeProfileRegistry(
        filesDir = directory,
        preferences = preferences,
        bundledLoader = { bundled },
        facts = board,
        coreVersion = "1.0.0",
        clock = { 1000L },
    )

    private fun assertRefused(plan: ProfileBackupRestorePlan) {
        assertFalse(plan.valid)
        assertNull(plan.selection)
        val refused = selectionIssue(plan)
        assertEquals(ProfileIssueSeverity.ERROR, refused.severity)
        assertEquals("backup-referenced-revision-unavailable", refused.presentation?.code)
    }

    /** The one issue about the selection, asserted rather than taken, so a mutant fails an assertion. */
    private fun selectionIssue(plan: ProfileBackupRestorePlan): ProfileIssue {
        val found = plan.issues.filter { it.path == "profiles.selection" }
        assertEquals(plan.issues.toString(), 1, found.size)
        return found[0]
    }

    private fun vendorYaml(version: String): String = ProfileYaml.serialize(
        testProfileDocument(id = vendorId, version = version, facts = board)
            .copy(identity = ProfileIdentity(manufacturer = "Vendor", model = "Panel X")),
    )
}
