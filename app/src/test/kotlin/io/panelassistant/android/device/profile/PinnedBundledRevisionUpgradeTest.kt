package io.panelassistant.android.device.profile

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A release upgrade against a panel whose administrator pinned a bundled profile.
 *
 * A bundled revision is the SHA-256 of its YAML, so a release that edits a bundled profile retires the
 * revision an older build persisted. The panel's durable state (the preference store and the
 * `device-profiles` directory) survives the upgrade; only the bundled bytes change. Every test drives
 * the production registry through that boundary: the old release installs and proves the pin, then the
 * new release starts against the state the old one left behind.
 *
 * The board is one automatic matching does not recognise unless a test says otherwise, because that is
 * the board an administrator pins for, and it is the board on which a lost pin surfaces as generic.
 */
class PinnedBundledRevisionUpgradeTest {
    private lateinit var directory: File
    private lateinit var preferences: PanelPreferences

    private val unmatchedBoard = DeviceFacts("other-board", "other-device", "fw-9")
    private val vendorBoard = DeviceFacts("test-panel", "test-device", "fw-1")

    private val vendorId = "vendor.test-panel"
    private val genericYaml = ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true))
    private val oldVendorYaml = vendorYaml(version = "1.0.0", model = "Panel X")
    private val newVendorYaml = vendorYaml(version = "1.0.1", model = "Panel X rev B")
    private val releaseOne = mapOf("generic.yaml" to genericYaml, "panel.yaml" to oldVendorYaml)
    private val releaseTwo = mapOf("generic.yaml" to genericYaml, "panel.yaml" to newVendorYaml)
    private val genericOnly = mapOf("generic.yaml" to genericYaml)
    private val genericRef = ProfileRef("generic", ProfileYaml.sha256(genericYaml))
    private val oldRef = ProfileRef(vendorId, ProfileYaml.sha256(oldVendorYaml))
    private val newRef = ProfileRef(vendorId, ProfileYaml.sha256(newVendorYaml))

    @Before fun setUp() {
        directory = Files.createTempDirectory("pinned-bundled-upgrade-test").toFile()
        preferences = PanelPreferences()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun `a healthy pin whose retained snapshot is gone follows the bundled successor instead of generic`() {
        pinHealthyOnReleaseOne()
        assertTrue(rollbackFile(oldRef).delete())

        val upgraded = install(releaseTwo)
        val resolved = upgraded.resolveForStartup()

        assertEquals(newRef, resolved.summary.ref)
        assertEquals("Vendor", resolved.profile.manufacturer)
        assertEquals("Panel X rev B", resolved.profile.model)
        assertNotNull(resolved.activationGeneration)
        assertEquals(64, oldRef.revision.length)
        assertEquals(64, newRef.revision.length)
        assertEquals(listOf(repinWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
        val retired = resolved.issues.single { it.presentation?.code == "pinned-revision-retired" }
        assertAuthoritativeEnglishMatches(retired.message, retired.presentation!!)
        assertTrue(resolved.issues.none { it.severity == ProfileIssueSeverity.ERROR })
        val staged = upgraded.status()
        assertEquals(ProfileSelection.Pinned(newRef), staged.selection)
        assertEquals(ProfileActivationPhase.APPLYING, staged.activation.phase)
        assertEquals(ProfileSelection.Pinned(newRef), staged.activation.desired)
        assertEquals(ProfileSelection.Auto, staged.activation.previous)

        assertTrue(upgraded.markActivationHealthy(generationOf(resolved)))
        val healthy = upgraded.status()
        assertEquals(ProfileActivationPhase.ACTIVE, healthy.activation.phase)
        assertEquals(newRef, healthy.active!!.ref)
        assertEquals(ProfileSelection.Auto, healthy.lastKnownGood)
        assertTrue(rollbackFile(newRef).isFile)
        assertFalse(rollbackFile(oldRef).exists())

        val restarted = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, restarted.summary.ref)
        assertNull(restarted.activationGeneration)
        assertTrue(restarted.issues.isEmpty())
        assertEquals(ProfileSelection.Pinned(newRef), install(releaseTwo).status().selection)
    }

    @Test fun `a staged pin never proven healthy follows the successor on the upgraded restart`() {
        val old = install(releaseOne)
        old.resolveForStartup()
        assertTrue(old.markResolvedStartupHealthy())
        val selected = old.select(ProfileSelection.Pinned(oldRef), old.status().catalogRevision)
        assertTrue(selected is ProfileMutation.Success && selected.restartRequired)
        val stagedGeneration = old.status().activation.generation

        val upgraded = install(releaseTwo)
        val resolved = upgraded.resolveForStartup()

        assertEquals(newRef, resolved.summary.ref)
        assertEquals(stagedGeneration, resolved.activationGeneration)
        assertEquals(listOf(repinWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
        val status = upgraded.status()
        assertEquals(ProfileActivationPhase.APPLYING, status.activation.phase)
        assertEquals(ProfileSelection.Pinned(newRef), status.selection)
        assertEquals(ProfileSelection.Pinned(newRef), status.activation.desired)
        assertEquals(ProfileSelection.Auto, status.activation.previous)

        assertTrue(upgraded.markActivationHealthy(generationOf(resolved)))
        assertEquals(ProfileActivationPhase.ACTIVE, upgraded.status().activation.phase)
        assertEquals(newRef, upgraded.status().active!!.ref)
        assertEquals(ProfileSelection.Auto, upgraded.status().lastKnownGood)
    }

    @Test fun `an unchanged bundled revision is left exactly as the old build proved it`() {
        val old = pinHealthyOnReleaseOne()
        val catalogRevision = old.status().catalogRevision

        val reinstalled = install(releaseOne)
        val resolved = reinstalled.resolveForStartup()

        assertEquals(oldRef, resolved.summary.ref)
        assertNull(resolved.activationGeneration)
        assertTrue(resolved.issues.toString(), resolved.issues.isEmpty())
        val status = reinstalled.status()
        assertEquals(ProfileActivationPhase.ACTIVE, status.activation.phase)
        assertEquals(ProfileSelection.Pinned(oldRef), status.selection)
        assertEquals(catalogRevision, status.catalogRevision)
    }

    @Test fun `a healthy pin whose retained snapshot survived still follows the asset with the snapshot as rollback`() {
        pinHealthyOnReleaseOne()

        val upgraded = install(releaseTwo)
        val resolved = upgraded.resolveForStartup()

        assertEquals(newRef, resolved.summary.ref)
        assertEquals("Panel X rev B", resolved.profile.model)
        assertNotNull(resolved.activationGeneration)
        assertEquals(listOf(repinWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
        val staged = upgraded.status()
        assertEquals(ProfileActivationPhase.APPLYING, staged.activation.phase)
        assertEquals(ProfileSelection.Pinned(oldRef), staged.activation.previous)
        assertEquals(ProfileSelection.Pinned(newRef), staged.selection)

        assertTrue(upgraded.markActivationHealthy(generationOf(resolved)))
        assertEquals(ProfileSelection.Pinned(oldRef), upgraded.status().lastKnownGood)
        assertTrue(rollbackFile(oldRef).isFile)
        assertTrue(rollbackFile(newRef).isFile)
        val restarted = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, restarted.summary.ref)
        assertNull(restarted.activationGeneration)
        assertTrue(restarted.issues.isEmpty())
    }

    @Test fun `an unhealthy successor returns to the retained snapshot and is not retried until a newer revision ships`() {
        pinHealthyOnReleaseOne()
        val applying = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, applying.summary.ref)

        val rolledBack = install(releaseTwo).resolveForStartup()

        assertEquals(oldRef, rolledBack.summary.ref)
        assertEquals("Panel X", rolledBack.profile.model)
        assertNull(rolledBack.activationGeneration)
        val status = install(releaseTwo).status()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
        assertEquals(ProfileSelection.Pinned(oldRef), status.selection)
        assertEquals("Previous activation did not report healthy; rolled back to $vendorId@${oldRef.revision.take(12)}.", status.activation.message)
        assertEquals(
            mapOf("id" to vendorId, "revision" to oldRef.revision.take(12)),
            status.activation.presentation!!.params,
        )
        assertAuthoritativeEnglishMatches(status.activation.message!!, status.activation.presentation)

        val again = install(releaseTwo).resolveForStartup()
        assertEquals(oldRef, again.summary.ref)
        assertNull(again.activationGeneration)
        assertEquals(listOf(heldWarning(oldRef, newRef)), again.issues.filter { it.path == "selection" })
        val held = again.issues.single { it.presentation?.code == "pinned-successor-held" }
        assertAuthoritativeEnglishMatches(held.message, held.presentation!!)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, install(releaseTwo).status().activation.phase)

        val thirdYaml = vendorYaml(version = "1.0.2", model = "Panel X rev C")
        val thirdRef = ProfileRef(vendorId, ProfileYaml.sha256(thirdYaml))
        val later = install(mapOf("generic.yaml" to genericYaml, "panel.yaml" to thirdYaml)).resolveForStartup()
        assertEquals(thirdRef, later.summary.ref)
        assertNotNull(later.activationGeneration)
        assertEquals(listOf(repinWarning(oldRef, thirdRef)), later.issues.filter { it.path == "selection" })
    }

    @Test fun `an explicit re-selection of a rolled back successor is honoured`() {
        pinHealthyOnReleaseOne()
        install(releaseTwo).resolveForStartup()
        install(releaseTwo).resolveForStartup()
        val admin = install(releaseTwo)
        assertEquals(oldRef, admin.resolveForStartup().summary.ref)

        val selected = admin.select(ProfileSelection.Pinned(newRef), admin.status().catalogRevision)

        assertTrue(selected.toString(), selected is ProfileMutation.Success && selected.restartRequired)
        val applying = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, applying.summary.ref)
        assertNotNull(applying.activationGeneration)
        assertTrue(applying.issues.none { it.path == "selection" })
    }

    @Test fun `rolling back to the retained revision after a healthy re-pin is honoured and held`() {
        pinHealthyOnReleaseOne()
        val upgraded = install(releaseTwo)
        assertTrue(upgraded.markActivationHealthy(generationOf(upgraded.resolveForStartup())))
        assertEquals(ProfileSelection.Pinned(oldRef), upgraded.status().lastKnownGood)

        val rollback = upgraded.rollbackToLastKnownGood(upgraded.status().catalogRevision)

        assertTrue(rollback.toString(), rollback is ProfileMutation.Success && rollback.restartRequired)
        assertEquals("${vendorId}@${newRef.revision}", preferences.getString("rejected_successor", ""))
        val restarted = install(releaseTwo)
        val applying = restarted.resolveForStartup()
        assertEquals(oldRef, applying.summary.ref)
        assertEquals("Panel X", applying.profile.model)
        assertNotNull(applying.activationGeneration)
        assertTrue(applying.issues.none { it.path == "selection" })
        assertTrue(restarted.markActivationHealthy(generationOf(applying)))
        val held = install(releaseTwo).resolveForStartup()
        assertEquals(oldRef, held.summary.ref)
        assertNull(held.activationGeneration)
        assertEquals(listOf(heldWarning(oldRef, newRef)), held.issues.filter { it.path == "selection" })
        assertEquals(ProfileActivationPhase.ACTIVE, install(releaseTwo).status().activation.phase)
        assertEquals(ProfileSelection.Pinned(oldRef), install(releaseTwo).status().selection)
    }

    @Test fun `a direct selection of a retained revision is honoured and held`() {
        pinHealthyOnReleaseOne()
        val upgraded = install(releaseTwo)
        assertTrue(upgraded.markActivationHealthy(generationOf(upgraded.resolveForStartup())))

        val selected = upgraded.select(ProfileSelection.Pinned(oldRef), upgraded.status().catalogRevision)

        assertTrue(selected.toString(), selected is ProfileMutation.Success && selected.restartRequired)
        val applying = install(releaseTwo).resolveForStartup()
        assertEquals(oldRef, applying.summary.ref)
        assertTrue(applying.issues.none { it.path == "selection" })
        assertTrue(install(releaseTwo).markActivationHealthy(generationOf(applying)))
        val held = install(releaseTwo).resolveForStartup()
        assertEquals(oldRef, held.summary.ref)
        assertNull(held.activationGeneration)
        assertEquals(listOf(heldWarning(oldRef, newRef)), held.issues.filter { it.path == "selection" })
    }

    @Test fun `a held revision follows a newer asset and the hold does not outlive it`() {
        pinHealthyOnReleaseOne()
        val upgraded = install(releaseTwo)
        assertTrue(upgraded.markActivationHealthy(generationOf(upgraded.resolveForStartup())))
        assertTrue(upgraded.select(ProfileSelection.Pinned(oldRef), upgraded.status().catalogRevision) is ProfileMutation.Success)
        val held = install(releaseTwo)
        assertTrue(held.markActivationHealthy(generationOf(held.resolveForStartup())))
        assertEquals("${vendorId}@${newRef.revision}", preferences.getString("rejected_successor", ""))
        val thirdYaml = vendorYaml(version = "1.0.2", model = "Panel X rev C")
        val thirdRef = ProfileRef(vendorId, ProfileYaml.sha256(thirdYaml))
        val releaseThree = mapOf("generic.yaml" to genericYaml, "panel.yaml" to thirdYaml)

        val third = install(releaseThree)
        val resolved = third.resolveForStartup()

        assertEquals(thirdRef, resolved.summary.ref)
        assertEquals(listOf(repinWarning(oldRef, thirdRef)), resolved.issues.filter { it.path == "selection" })
        assertEquals("", preferences.getString("rejected_successor", ""))
        assertTrue(third.markActivationHealthy(generationOf(resolved)))
        // A later release that ships the previously held bytes again is followed like any other asset.
        val reverted = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, reverted.summary.ref)
        assertNotNull(reverted.activationGeneration)
        assertEquals(listOf(repinWarning(thirdRef, newRef)), reverted.issues.filter { it.path == "selection" })
    }

    @Test fun `a hold staged before an upgrade follows the newer asset and releases the hold`() {
        pinHealthyOnReleaseOne()
        val upgraded = install(releaseTwo)
        assertTrue(upgraded.markActivationHealthy(generationOf(upgraded.resolveForStartup())))
        assertTrue(upgraded.rollbackToLastKnownGood(upgraded.status().catalogRevision) is ProfileMutation.Success)
        assertEquals("${vendorId}@${newRef.revision}", preferences.getString("rejected_successor", ""))
        val thirdYaml = vendorYaml(version = "1.0.2", model = "Panel X rev C")
        val thirdRef = ProfileRef(vendorId, ProfileYaml.sha256(thirdYaml))

        val third = install(mapOf("generic.yaml" to genericYaml, "panel.yaml" to thirdYaml))
        val resolved = third.resolveForStartup()

        assertEquals(thirdRef, resolved.summary.ref)
        assertEquals(ProfileActivationPhase.APPLYING, third.status().activation.phase)
        assertEquals(listOf(repinWarning(oldRef, thirdRef)), resolved.issues.filter { it.path == "selection" })
        assertEquals("", preferences.getString("rejected_successor", ""))
    }

    @Test fun `a retained snapshot of an older revision does not hide the successor`() {
        pinHealthyOnReleaseOne()
        val second = install(releaseTwo)
        val applying = second.resolveForStartup()
        assertEquals(newRef, applying.summary.ref)
        assertTrue(second.markActivationHealthy(generationOf(applying)))
        assertEquals(ProfileSelection.Pinned(oldRef), second.status().lastKnownGood)
        assertTrue(rollbackFile(oldRef).isFile)
        assertTrue(rollbackFile(newRef).delete())
        val thirdYaml = vendorYaml(version = "1.0.2", model = "Panel X rev C")
        val thirdRef = ProfileRef(vendorId, ProfileYaml.sha256(thirdYaml))

        val upgraded = install(mapOf("generic.yaml" to genericYaml, "panel.yaml" to thirdYaml))
        val resolved = upgraded.resolveForStartup()

        assertEquals(thirdRef, resolved.summary.ref)
        assertEquals("Panel X rev C", resolved.profile.model)
        assertEquals(listOf(repinWarning(newRef, thirdRef)), resolved.issues.filter { it.path == "selection" })
        assertEquals(ProfileSelection.Pinned(oldRef), upgraded.status().activation.previous)
        assertTrue(upgraded.markActivationHealthy(generationOf(resolved)))
        assertEquals(ProfileSelection.Pinned(oldRef), upgraded.status().lastKnownGood)
        assertTrue(rollbackFile(oldRef).isFile)
    }

    @Test fun `a pinned imported revision that went missing is still refused`() {
        val registry = install(releaseOne)
        registry.resolveForStartup()
        val imported = import(registry, ProfileYaml.serialize(testProfileDocument(facts = unmatchedBoard)))
        activateHealthy(registry, imported)
        assertEquals("imported", preferences.getString("selection_origin", ""))
        assertTrue(importedFile(imported).delete())

        val recovered = install(releaseTwo).resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertNull(recovered.activationGeneration)
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Pinned revision is missing." })
        assertTrue(recovered.issues.none { it.severity == ProfileIssueSeverity.WARNING && it.path == "selection" })
        val status = install(releaseTwo).status()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
        assertEquals(ProfileSelection.Auto, status.selection)
        assertEquals("", preferences.getString("selection_origin", ""))
    }

    @Test fun `a lost imported override of a bundled id is refused rather than swapped for the stock profile`() {
        val registry = install(releaseOne)
        registry.resolveForStartup()
        val override = ProfileYaml.serialize(
            testProfileDocument(id = vendorId, version = "9.9.9", facts = unmatchedBoard)
                .copy(identity = ProfileIdentity(manufacturer = "Vendor", model = "Panel X community")),
        )
        val imported = import(registry, override)
        assertEquals(vendorId, imported.id)
        activateHealthy(registry, imported)
        assertTrue(importedFile(imported).delete())
        assertTrue(importedFile(imported).parentFile.isDirectory)

        val recovered = install(releaseTwo).resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Pinned revision is missing." })
        assertTrue(recovered.issues.none { it.path == "selection" && it.severity == ProfileIssueSeverity.WARNING })
        assertNotEquals(ProfileSelection.Pinned(newRef), install(releaseTwo).status().selection)
    }

    @Test fun `a staged pin on a matching board cannot roll back onto its successor`() {
        val old = install(releaseOne, vendorBoard)
        assertEquals(oldRef, old.resolveForStartup().summary.ref)
        assertTrue(old.markResolvedStartupHealthy())
        assertTrue(old.select(ProfileSelection.Pinned(oldRef), old.status().catalogRevision) is ProfileMutation.Success)
        assertEquals(ProfileSelection.Auto, old.status().activation.previous)
        // The healthy automatic startup retained a snapshot of the old revision; this case is the one
        // where nothing but automatic matching or the generic profile is left to fall back to.
        assertTrue(rollbackFile(oldRef).delete())

        val applying = install(releaseTwo, vendorBoard).resolveForStartup()

        assertEquals(newRef, applying.summary.ref)
        assertEquals(ProfileSelection.Pinned(genericRef), install(releaseTwo, vendorBoard).status().activation.previous)
        val rolledBack = install(releaseTwo, vendorBoard).resolveForStartup()
        assertEquals(genericRef, rolledBack.summary.ref)
        assertNull(rolledBack.activationGeneration)
        assertEquals(ProfileSelection.Pinned(genericRef), install(releaseTwo, vendorBoard).status().selection)
    }

    @Test fun `a blocked imported override of a bundled id is refused even though its file survives`() {
        val touchscreen = "/dev/input/event7"
        val registry = install(releaseOne, inspector = EvdevDeviceInspector { null })
        registry.resolveForStartup()
        val override = ProfileYaml.serialize(
            testProfileDocument(id = vendorId, version = "9.9.9", facts = unmatchedBoard).copy(
                identity = ProfileIdentity(manufacturer = "Vendor", model = "Panel X community"),
                requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "input.evdev")),
                input = ProfileInput(listOf(ProfileEvdevButton(touchscreen, 116, true, "KEYCODE_POWER"))),
            ),
        )
        val imported = import(registry, override)
        activateHealthy(registry, imported)
        assertTrue(importedFile(imported).isFile)

        val recovered = install(releaseTwo, inspector = EvdevDeviceInspector { node -> node == touchscreen }).resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Imported profiles cannot exclusively grab a touchscreen input device." })
        assertTrue(recovered.issues.none { it.path == "selection" && it.severity == ProfileIssueSeverity.WARNING })
        assertNotEquals(ProfileSelection.Pinned(newRef), install(releaseTwo).status().selection)
    }

    @Test fun `a staged re-pin that cannot be persisted rolls back truthfully`() {
        val old = install(releaseOne)
        old.resolveForStartup()
        assertTrue(old.markResolvedStartupHealthy())
        assertTrue(old.select(ProfileSelection.Pinned(oldRef), old.status().catalogRevision) is ProfileMutation.Success)
        val upgraded = install(releaseTwo)
        preferences.failNextPut()

        val resolved = upgraded.resolveForStartup()

        assertEquals("generic", resolved.summary.ref.id)
        assertNull(resolved.activationGeneration)
        assertEquals(listOf(repinWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
        assertTrue(resolved.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Could not persist the applying state." })
        val status = upgraded.status()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
        assertEquals(ProfileSelection.Auto, status.selection)
    }

    @Test fun `a staged re-pin that cannot be persisted cannot run the successor on a matching board`() {
        // The board's automatic match IS the successor, so an unexcluded rollback would resolve
        // straight back to the revision this start failed to arm a health gate for.
        val old = install(releaseOne, vendorBoard)
        old.resolveForStartup()
        assertTrue(old.markResolvedStartupHealthy())
        assertTrue(old.select(ProfileSelection.Pinned(oldRef), old.status().catalogRevision) is ProfileMutation.Success)
        assertTrue(rollbackFile(oldRef).delete())
        val upgraded = install(releaseTwo, vendorBoard)
        preferences.failNextPut()

        val resolved = upgraded.resolveForStartup()

        assertNotEquals(newRef, resolved.summary.ref)
        assertEquals(genericRef, resolved.summary.ref)
        assertNull(resolved.activationGeneration)
        assertTrue(resolved.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Could not persist the applying state." })
        assertEquals(listOf(repinWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
        val status = install(releaseTwo, vendorBoard).status()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
        assertEquals(ProfileSelection.Pinned(genericRef), status.selection)
        val next = install(releaseTwo, vendorBoard).resolveForStartup()
        assertEquals(genericRef, next.summary.ref)
        assertNull(next.activationGeneration)
    }

    @Test fun `a staged activation of the auto-matched revision that cannot be persisted does not run it`() {
        val panel = install(releaseTwo, vendorBoard)
        assertEquals(newRef, panel.resolveForStartup().summary.ref)
        assertTrue(panel.markResolvedStartupHealthy())
        assertEquals(ProfileSelection.Auto, panel.status().selection)
        assertTrue(rollbackFile(newRef).delete())
        assertTrue(panel.select(ProfileSelection.Pinned(newRef), panel.status().catalogRevision) is ProfileMutation.Success)
        assertEquals(ProfileSelection.Auto, panel.status().activation.previous)
        val restarted = install(releaseTwo, vendorBoard)
        preferences.failNextPut()

        val resolved = restarted.resolveForStartup()

        // Automatic matching on this board resolves to the very revision the failed write left ungated.
        assertNotEquals(newRef, resolved.summary.ref)
        assertEquals(genericRef, resolved.summary.ref)
        assertNull(resolved.activationGeneration)
        assertTrue(resolved.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Could not persist the applying state." })
        assertEquals(ProfileActivationPhase.ROLLED_BACK, install(releaseTwo, vendorBoard).status().activation.phase)
    }

    @Test fun `a staged ordinary activation that cannot be persisted still rolls back to its own previous`() {
        val registry = install(releaseOne)
        registry.resolveForStartup()
        val imported = import(registry, ProfileYaml.serialize(testProfileDocument(facts = unmatchedBoard)))
        assertTrue(registry.select(ProfileSelection.Pinned(imported), registry.status().catalogRevision) is ProfileMutation.Success)
        assertTrue(importedFile(imported).delete())
        val restarted = install(releaseOne)
        preferences.failNextPut()

        val resolved = restarted.resolveForStartup()

        assertEquals("generic", resolved.summary.ref.id)
        assertNull(resolved.activationGeneration)
        assertTrue(resolved.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Pinned revision is missing." })
    }

    @Test fun `a profile the release dropped entirely is refused with the missing revision named`() {
        pinHealthyOnReleaseOne()
        assertTrue(rollbackFile(oldRef).delete())

        val recovered = install(genericOnly).resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertNull(recovered.activationGeneration)
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Pinned revision is missing." })
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.path == "activation" && oldRef.revision.take(12) in it.message })
        assertEquals(ProfileActivationPhase.ROLLED_BACK, install(genericOnly).status().activation.phase)
    }

    @Test fun `a re-pin that cannot be persisted keeps the current selection and retries after restart`() {
        pinHealthyOnReleaseOne()
        val upgraded = install(releaseTwo)
        val catalogRevision = upgraded.status().catalogRevision
        preferences.failNextPut()

        val resolved = upgraded.resolveForStartup()

        // Nothing recorded that the successor was being tried, so it must not run unguarded.
        assertEquals(oldRef, resolved.summary.ref)
        assertEquals("Panel X", resolved.profile.model)
        assertNull(resolved.activationGeneration)
        assertTrue(
            resolved.issues.any {
                it.severity == ProfileIssueSeverity.ERROR && it.path == "activation" &&
                    "Could not persist the re-pinned bundled revision" in it.message &&
                    "$vendorId@${oldRef.revision.take(12)}" in it.message
            },
        )
        val persistFailure = resolved.issues.single { it.presentation?.code == "repin-persist-failed-pinned" }
        assertEquals(
            mapOf("id" to vendorId, "revision" to oldRef.revision.take(12)),
            persistFailure.presentation!!.params,
        )
        assertAuthoritativeEnglishMatches(persistFailure.message, persistFailure.presentation)
        assertEquals(listOf(repinWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
        val untouched = upgraded.status()
        assertEquals(ProfileSelection.Pinned(oldRef), untouched.selection)
        assertEquals(ProfileActivationPhase.ACTIVE, untouched.activation.phase)
        assertEquals(catalogRevision, untouched.catalogRevision)

        val retried = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, retried.summary.ref)
        assertNotNull(retried.activationGeneration)
        assertEquals(ProfileSelection.Pinned(newRef), install(releaseTwo).status().selection)
    }

    @Test fun `a re-pin with no retained snapshot that cannot be persisted keeps the generic profile`() {
        pinHealthyOnReleaseOne()
        assertTrue(rollbackFile(oldRef).delete())
        val upgraded = install(releaseTwo)
        preferences.failNextPut()

        val resolved = upgraded.resolveForStartup()

        assertEquals("generic", resolved.summary.ref.id)
        assertNull(resolved.activationGeneration)
        assertTrue(resolved.issues.any { it.severity == ProfileIssueSeverity.ERROR && "Could not persist the re-pinned bundled revision" in it.message })
        assertEquals(ProfileSelection.Pinned(oldRef), upgraded.status().selection)
    }

    @Test fun `an unhealthy successor rolls back to the staged target and is not retried`() {
        pinHealthyOnReleaseOne()
        assertTrue(rollbackFile(oldRef).delete())
        val applying = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, applying.summary.ref)
        assertNotNull(applying.activationGeneration)

        val rolledBack = install(releaseTwo).resolveForStartup()

        assertEquals("generic", rolledBack.summary.ref.id)
        assertNull(rolledBack.activationGeneration)
        assertTrue(rolledBack.issues.any { it.message == "Previous profile activation was rolled back after an unhealthy restart." })
        val status = install(releaseTwo).status()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
        assertEquals(ProfileSelection.Auto, status.selection)
        val again = install(releaseTwo).resolveForStartup()
        assertEquals("generic", again.summary.ref.id)
        assertNull(again.activationGeneration)
        assertTrue(again.issues.none { it.path == "selection" })
    }

    @Test fun `a matching board keeps its explicit pin instead of silently becoming automatic`() {
        pinHealthyOnReleaseOne(vendorBoard)
        assertTrue(rollbackFile(oldRef).delete())

        val upgraded = install(releaseTwo, vendorBoard)
        val resolved = upgraded.resolveForStartup()

        assertEquals(newRef, resolved.summary.ref)
        assertEquals(ProfileSelection.Pinned(newRef), upgraded.status().selection)
        assertTrue(upgraded.markActivationHealthy(generationOf(resolved)))
        assertEquals(ProfileSelection.Pinned(newRef), upgraded.status().selection)
        assertEquals(ProfileActivationPhase.ACTIVE, upgraded.status().activation.phase)
    }

    @Test fun `an unhealthy successor on a matching board cannot resolve straight back to itself`() {
        pinHealthyOnReleaseOne(vendorBoard)
        assertTrue(rollbackFile(oldRef).delete())
        val applying = install(releaseTwo, vendorBoard).resolveForStartup()
        assertEquals(newRef, applying.summary.ref)
        assertNotNull(applying.activationGeneration)
        assertEquals(ProfileSelection.Pinned(genericRef), install(releaseTwo, vendorBoard).status().activation.previous)

        val rolledBack = install(releaseTwo, vendorBoard).resolveForStartup()

        assertEquals(genericRef, rolledBack.summary.ref)
        assertNull(rolledBack.activationGeneration)
        val status = install(releaseTwo, vendorBoard).status()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
        assertEquals(ProfileSelection.Pinned(genericRef), status.selection)
        assertEquals("Previous activation did not report healthy; rolled back to generic@${genericRef.revision.take(12)}.", status.activation.message)
        val again = install(releaseTwo, vendorBoard).resolveForStartup()
        assertEquals(genericRef, again.summary.ref)
        assertNull(again.activationGeneration)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, install(releaseTwo, vendorBoard).status().activation.phase)
    }

    @Test fun `a recorded bundled origin re-pins even beside a coexisting imported store for the same id`() {
        val registry = install(releaseOne)
        registry.resolveForStartup()
        val override = ProfileYaml.serialize(
            testProfileDocument(id = vendorId, version = "9.9.9", facts = unmatchedBoard)
                .copy(identity = ProfileIdentity(manufacturer = "Vendor", model = "Panel X community")),
        )
        val imported = import(registry, override)
        assertTrue(importedFile(imported).isFile)
        activateHealthy(registry, oldRef)
        assertEquals("bundled", preferences.getString("selection_origin", ""))
        assertTrue(rollbackFile(oldRef).delete())

        val resolved = install(releaseTwo).resolveForStartup()

        assertEquals(newRef, resolved.summary.ref)
        assertEquals(listOf(repinWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
    }

    @Test fun `a recorded imported origin is refused even when its store is gone and the id is bundled`() {
        val registry = install(releaseOne)
        registry.resolveForStartup()
        val override = ProfileYaml.serialize(
            testProfileDocument(id = vendorId, version = "9.9.9", facts = unmatchedBoard)
                .copy(identity = ProfileIdentity(manufacturer = "Vendor", model = "Panel X community")),
        )
        val imported = import(registry, override)
        activateHealthy(registry, imported)
        assertEquals("imported", preferences.getString("selection_origin", ""))
        assertTrue(importedFile(imported).delete())
        assertTrue(importedFile(imported).parentFile.delete())
        assertFalse(importedFile(imported).parentFile.exists())

        val recovered = install(releaseTwo).resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Pinned revision is missing." })
        assertTrue(recovered.issues.none { it.path == "selection" && it.severity == ProfileIssueSeverity.WARNING })
    }

    @Test fun `state from an older build without an origin record reads the retained snapshot as bundled`() {
        pinHealthyOnReleaseOne()
        assertTrue(preferences.put("selection_origin" to null))

        val resolved = install(releaseTwo).resolveForStartup()

        assertEquals(newRef, resolved.summary.ref)
        assertEquals(ProfileSelection.Pinned(oldRef), install(releaseTwo).status().activation.previous)
    }

    @Test fun `state from an older build without an origin record reads an imported store as imported`() {
        val registry = install(releaseOne)
        registry.resolveForStartup()
        val override = ProfileYaml.serialize(
            testProfileDocument(id = vendorId, version = "9.9.9", facts = unmatchedBoard)
                .copy(identity = ProfileIdentity(manufacturer = "Vendor", model = "Panel X community")),
        )
        val imported = import(registry, override)
        activateHealthy(registry, imported)
        assertTrue(importedFile(imported).delete())
        assertTrue(preferences.put("selection_origin" to null))

        val recovered = install(releaseTwo).resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && it.message == "Pinned revision is missing." })
        assertNotEquals(ProfileSelection.Pinned(newRef), install(releaseTwo).status().selection)
    }

    @Test fun `a legacy pin without an origin record has one written durably at the first startup`() {
        pinHealthyOnReleaseOne()
        assertTrue(preferences.put("selection_origin" to null))

        assertEquals(oldRef, install(releaseOne).resolveForStartup().summary.ref)

        // Written once, from evidence that may not survive: the snapshot can be pruned later.
        assertEquals("bundled", preferences.getString("selection_origin", ""))
        assertTrue(rollbackFile(oldRef).delete())
        val resolved = install(releaseTwo).resolveForStartup()
        assertEquals(newRef, resolved.summary.ref)
    }

    @Test fun `a legacy imported pin has its origin recorded before the store can disappear`() {
        val registry = install(releaseOne)
        registry.resolveForStartup()
        val override = ProfileYaml.serialize(
            testProfileDocument(id = vendorId, version = "9.9.9", facts = unmatchedBoard)
                .copy(identity = ProfileIdentity(manufacturer = "Vendor", model = "Panel X community")),
        )
        val imported = import(registry, override)
        activateHealthy(registry, imported)
        assertTrue(preferences.put("selection_origin" to null))

        install(releaseOne).resolveForStartup()

        assertEquals("imported", preferences.getString("selection_origin", ""))
        assertTrue(importedFile(imported).delete())
        assertTrue(importedFile(imported).parentFile.delete())
        val recovered = install(releaseTwo).resolveForStartup()
        assertEquals("generic", recovered.summary.ref.id)
        assertTrue(recovered.issues.none { it.path == "selection" && it.severity == ProfileIssueSeverity.WARNING })
    }

    @Test fun `aborting a staged activation restores the held selection with its hold`() {
        pinHealthyOnReleaseOne()
        val upgraded = install(releaseTwo)
        assertTrue(upgraded.markActivationHealthy(generationOf(upgraded.resolveForStartup())))
        assertTrue(upgraded.rollbackToLastKnownGood(upgraded.status().catalogRevision) is ProfileMutation.Success)
        val held = install(releaseTwo)
        assertTrue(held.markActivationHealthy(generationOf(held.resolveForStartup())))
        val staging = install(releaseTwo)
        assertTrue(staging.select(ProfileSelection.Auto, staging.status().catalogRevision) is ProfileMutation.Success)
        assertEquals("", preferences.getString("rejected_successor", ""))

        assertTrue(staging.abortPendingActivation("teardown could not release an owner"))

        assertEquals(ProfileSelection.Pinned(oldRef), staging.status().selection)
        assertEquals("$vendorId@${newRef.revision}", preferences.getString("rejected_successor", ""))
        val resolved = install(releaseTwo).resolveForStartup()
        assertEquals(oldRef, resolved.summary.ref)
        assertNull(resolved.activationGeneration)
        assertEquals(listOf(heldWarning(oldRef, newRef)), resolved.issues.filter { it.path == "selection" })
    }

    @Test fun `recovering from an unresolvable staged selection restores the held selection with its hold`() {
        pinHealthyOnReleaseOne()
        val upgraded = install(releaseTwo)
        assertTrue(upgraded.markActivationHealthy(generationOf(upgraded.resolveForStartup())))
        assertTrue(upgraded.rollbackToLastKnownGood(upgraded.status().catalogRevision) is ProfileMutation.Success)
        val held = install(releaseTwo)
        assertTrue(held.markActivationHealthy(generationOf(held.resolveForStartup())))
        val missing = ProfileRef("community.example.missing", "a".repeat(64))
        assertTrue(
            preferences.put(
                "selection" to "${missing.id}@${missing.revision}",
                "selection_origin" to "imported",
                "rejected_successor" to null,
                "activation_phase" to ProfileActivationPhase.PENDING.name,
                "activation_previous" to "$vendorId@${oldRef.revision}",
                "activation_desired" to "${missing.id}@${missing.revision}",
            ),
        )

        val recovered = install(releaseTwo).resolveForStartup()

        assertEquals(oldRef, recovered.summary.ref)
        assertEquals("$vendorId@${newRef.revision}", preferences.getString("rejected_successor", ""))
        assertEquals("bundled", preferences.getString("selection_origin", ""))
        val next = install(releaseTwo).resolveForStartup()
        assertEquals(oldRef, next.summary.ref)
        assertNull(next.activationGeneration)
        assertEquals(listOf(heldWarning(oldRef, newRef)), next.issues.filter { it.path == "selection" })
    }

    @Test fun `a last known good that names a retired revision is skipped, not rewritten`() {
        val old = pinHealthyOnReleaseOne()
        assertTrue(old.select(ProfileSelection.Auto, old.status().catalogRevision) is ProfileMutation.Success)
        val back = install(releaseOne)
        assertTrue(back.markActivationHealthy(generationOf(back.resolveForStartup())))
        assertEquals(ProfileSelection.Pinned(oldRef), back.status().lastKnownGood)
        assertTrue(rollbackFile(oldRef).delete())

        val upgraded = install(releaseTwo)
        val resolved = upgraded.resolveForStartup()

        assertEquals("generic", resolved.summary.ref.id)
        assertTrue(resolved.issues.none { it.severity == ProfileIssueSeverity.ERROR })
        assertEquals(ProfileSelection.Pinned(oldRef), upgraded.status().lastKnownGood)
        val rollback = upgraded.rollbackToLastKnownGood(upgraded.status().catalogRevision)
        assertTrue(rollback is ProfileMutation.Rejected)
        assertTrue((rollback as ProfileMutation.Rejected).issues.any { it.message == "Profile revision does not exist." })
    }

    @Test fun `automatic selection through the same upgrade keeps its own semantics`() {
        val old = install(releaseOne, vendorBoard)
        assertEquals(oldRef, old.resolveForStartup().summary.ref)
        assertTrue(old.markResolvedStartupHealthy())
        assertEquals(ProfileSelection.Auto, old.status().selection)

        val upgraded = install(releaseTwo, vendorBoard)
        val resolved = upgraded.resolveForStartup()

        assertEquals(newRef, resolved.summary.ref)
        assertNotNull(resolved.activationGeneration)
        assertTrue(resolved.issues.none { it.path == "selection" })
        assertEquals(ProfileSelection.Auto, upgraded.status().selection)
        assertEquals(ProfileSelection.Pinned(oldRef), upgraded.status().activation.previous)
    }

    @Test fun `the released rc2 TPA10 pin follows the current bundled revision with its capabilities`() {
        val rc2Yaml = requireNotNull(javaClass.getResource("/fixtures/device-profiles/tpa10-v0.9.7-rc2.yaml")).readText()
        assertEquals(RC2_TPA10_REVISION, ProfileYaml.sha256(rc2Yaml))
        val current = BundledProfileFixtures.rawByName()
        val currentTpa10 = requireNotNull(current["tpa10.yaml"])
        val rc2Ref = ProfileRef("tpa10", RC2_TPA10_REVISION)
        val currentRef = ProfileRef("tpa10", ProfileYaml.sha256(currentTpa10))
        assertNotEquals("the fixture proves nothing while the current profile still carries the rc2 bytes", rc2Ref, currentRef)
        val rc2Release = mapOf("generic.yaml" to requireNotNull(current["generic.yaml"]), "tpa10.yaml" to rc2Yaml)

        val rc2 = install(rc2Release)
        rc2.resolveForStartup()
        assertTrue(rc2.markResolvedStartupHealthy())
        activateHealthy(rc2, rc2Ref)
        assertTrue(rollbackFile(rc2Ref).delete())

        val upgraded = install(current)
        val resolved = upgraded.resolveForStartup()

        assertEquals(currentRef, resolved.summary.ref)
        val expected = BundledProfileFixtures.profile("tpa10")
        assertEquals(expected.manufacturer, resolved.profile.manufacturer)
        assertEquals(expected.model, resolved.profile.model)
        assertEquals(expected.ledMechanism, resolved.profile.ledMechanism)
        assertEquals(expected.screenOff, resolved.profile.screenOff)
        assertEquals(expected.proximityTech, resolved.profile.proximityTech)
        assertEquals(expected.cameraDeclared, resolved.profile.cameraDeclared)
        assertNotEquals("generic", resolved.profile.id)
        assertEquals(listOf(repinWarning(rc2Ref, currentRef)), resolved.issues.filter { it.path == "selection" })
        assertTrue(upgraded.markActivationHealthy(generationOf(resolved)))
        assertEquals(currentRef, install(current).resolveForStartup().summary.ref)
        assertEquals(ProfileSelection.Pinned(currentRef), install(current).status().selection)
    }

    @Test fun `the released rc2 TPA10 pin with its snapshot retained follows the current bundled revision`() {
        val rc2Yaml = requireNotNull(javaClass.getResource("/fixtures/device-profiles/tpa10-v0.9.7-rc2.yaml")).readText()
        val current = BundledProfileFixtures.rawByName()
        val currentTpa10 = requireNotNull(current["tpa10.yaml"])
        val rc2Ref = ProfileRef("tpa10", RC2_TPA10_REVISION)
        val currentRef = ProfileRef("tpa10", ProfileYaml.sha256(currentTpa10))
        val rc2 = install(mapOf("generic.yaml" to requireNotNull(current["generic.yaml"]), "tpa10.yaml" to rc2Yaml))
        rc2.resolveForStartup()
        assertTrue(rc2.markResolvedStartupHealthy())
        activateHealthy(rc2, rc2Ref)
        assertTrue(rollbackFile(rc2Ref).isFile)

        val upgraded = install(current)
        val resolved = upgraded.resolveForStartup()

        assertEquals(currentRef, resolved.summary.ref)
        assertEquals(BundledProfileFixtures.profile("tpa10").model, resolved.profile.model)
        assertEquals(ProfileSelection.Pinned(rc2Ref), upgraded.status().activation.previous)
        assertTrue(upgraded.markActivationHealthy(generationOf(resolved)))
        assertEquals(ProfileSelection.Pinned(rc2Ref), upgraded.status().lastKnownGood)
        assertEquals(currentRef, install(current).resolveForStartup().summary.ref)
    }

    /** The staged activation generation, asserted rather than dereferenced, so a mutant fails an assertion. */
    private fun generationOf(resolved: ResolvedProfile): Long {
        val generation = resolved.activationGeneration
        assertNotNull("expected a staged activation generation", generation)
        return generation!!
    }

    private fun install(
        bundled: Map<String, String>,
        facts: DeviceFacts = unmatchedBoard,
        inspector: EvdevDeviceInspector = EvdevDeviceInspector { null },
    ) = RuntimeProfileRegistry(
        filesDir = directory,
        preferences = preferences,
        bundledLoader = { bundled },
        facts = facts,
        coreVersion = "1.0.0",
        clock = { 1000L },
        evdevInspector = inspector,
    )

    /** The durable state the old release leaves behind: the bundled pin proven healthy, snapshot retained. */
    private fun pinHealthyOnReleaseOne(facts: DeviceFacts = unmatchedBoard): RuntimeProfileRegistry {
        val first = install(releaseOne, facts)
        first.resolveForStartup()
        assertTrue(first.markResolvedStartupHealthy())
        val selected = first.select(ProfileSelection.Pinned(oldRef), first.status().catalogRevision)
        assertTrue(selected.toString(), selected is ProfileMutation.Success && selected.restartRequired)
        val restarted = install(releaseOne, facts)
        val applying = restarted.resolveForStartup()
        assertEquals(oldRef, applying.summary.ref)
        assertTrue(restarted.markActivationHealthy(generationOf(applying)))
        assertEquals(ProfileActivationPhase.ACTIVE, restarted.status().activation.phase)
        assertTrue(rollbackFile(oldRef).isFile)
        return restarted
    }

    private fun activateHealthy(registry: RuntimeProfileRegistry, ref: ProfileRef) {
        val selected = registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        assertTrue(selected.toString(), selected is ProfileMutation.Success && selected.restartRequired)
        val applying = registry.resolveForStartup()
        assertEquals(ref, applying.summary.ref)
        assertTrue(registry.markActivationHealthy(generationOf(applying)))
        assertEquals(ref, registry.status().active!!.ref)
    }

    private fun import(registry: RuntimeProfileRegistry, raw: String): ProfileRef {
        val preview = registry.preview(raw)
        assertTrue(preview.issues.toString(), preview.compatible)
        assertTrue(registry.importProfile(raw, preview.previewToken!!) is ProfileMutation.Success)
        return preview.summary!!.ref
    }

    private fun vendorYaml(version: String, model: String): String = ProfileYaml.serialize(
        testProfileDocument(id = vendorId, version = version, facts = vendorBoard)
            .copy(identity = ProfileIdentity(manufacturer = "Vendor", model = model)),
    )

    private fun repinWarning(retired: ProfileRef, successor: ProfileRef) = ProfileIssue(
        ProfileIssueSeverity.WARNING,
        "selection",
        "Pinned profile '${retired.id}' revision ${retired.revision.take(12)} was retired by this release; " +
            "following its current bundled revision ${successor.revision.take(12)}.",
        ProfilePresentation(
            "pinned-revision-retired",
            mapOf(
                "id" to retired.id,
                "retired_revision" to retired.revision.take(12),
                "current_revision" to successor.revision.take(12),
            ),
        ),
    )

    private fun heldWarning(held: ProfileRef, current: ProfileRef) = ProfileIssue(
        ProfileIssueSeverity.WARNING,
        "selection",
        "Pinned profile '${held.id}' is held at revision ${held.revision.take(12)}; " +
            "the current bundled revision ${current.revision.take(12)} is not applied automatically. Select it to adopt it.",
        ProfilePresentation(
            "pinned-successor-held",
            mapOf(
                "id" to held.id,
                "retired_revision" to held.revision.take(12),
                "current_revision" to current.revision.take(12),
            ),
        ),
    )

    private fun importedFile(ref: ProfileRef) = File(directory, "device-profiles/imported/${ref.id}/${ref.revision}.yaml")

    private fun rollbackFile(ref: ProfileRef) = File(directory, "device-profiles/rollback/${ref.id}/${ref.revision}.yaml")

    private companion object {
        /** SHA-256 of the shipped `device-profiles/tpa10.yaml` asset as released in v0.9.7-rc2. */
        const val RC2_TPA10_REVISION = "1aef00dc9ecde07bd2770a09dc40c48f19b6a6a303c5516202a889f005ce0653"
    }
}

private class PanelPreferences : ProfilePreferences {
    private val values = linkedMapOf<String, Any>()
    private var rejectNextPut = false

    fun failNextPut() {
        rejectNextPut = true
    }

    override fun getString(key: String, default: String) = values[key] as? String ?: default

    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default

    override fun put(vararg values: Pair<String, Any?>): Boolean {
        if (rejectNextPut) {
            rejectNextPut = false
            return false
        }
        val next = LinkedHashMap(this.values)
        values.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        this.values.clear()
        this.values.putAll(next)
        return true
    }
}
