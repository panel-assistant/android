package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ProvisioningIntent
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class RuntimeProfileRegistryTest {
    private lateinit var directory: File
    private lateinit var preferences: MemoryProfilePreferences
    private val facts = DeviceFacts("test-panel", "test-device", "fw-1")

    @Before fun setUp() {
        directory = Files.createTempDirectory("profile-registry-test").toFile()
        preferences = MemoryProfilePreferences()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun `community import remains inert until immutable revision is pinned`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val candidate = ProfileYaml.serialize(testProfileDocument(facts = facts))

        val imported = import(registry, candidate)

        assertEquals("generic", registry.status().active!!.ref.id)
        assertFalse(registry.status().active!!.ref == imported)
        assertEquals(ProfileSelection.Auto, registry.status().selection)
    }

    @Test fun `imported profile cannot be pinned to a different immutable device identity`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val raw = ProfileYaml.serialize(testProfileDocument(facts = DeviceFacts("another-panel", "other", "fw-2")))
        val ref = import(registry, raw)

        val selected = registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        assertTrue(selected is ProfileMutation.Rejected)
        assertTrue((selected as ProfileMutation.Rejected).issues.any { it.path == "selection.match" })
        assertEquals(ProfileSelection.Auto, registry.status().selection)
    }

    @Test fun `imported profile cannot exclusively grab a classified touchscreen`() {
        val touchscreenNode = "/dev/input/event7"
        val registry = registry(
            mapOf("generic.yaml" to genericYaml()),
            evdevInspector = EvdevDeviceInspector { node -> node == touchscreenNode },
        )
        val document = testProfileDocument(facts = facts).copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "input.evdev")),
            input = ProfileInput(listOf(ProfileEvdevButton(touchscreenNode, 116, true, "KEYCODE_POWER"))),
        )
        val ref = import(registry, ProfileYaml.serialize(document))

        val selected = registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        assertTrue(selected is ProfileMutation.Rejected)
        assertTrue((selected as ProfileMutation.Rejected).issues.any { it.path == "input.evdev_buttons[0].grab" })
    }

    @Test fun `linux input capability bitmap identifies touchscreen class conservatively`() {
        assertEquals(true, SysfsEvdevDeviceInspector.capabilitiesAreTouchscreen("3", "400 0 0 0 0 0"))
        assertEquals(true, SysfsEvdevDeviceInspector.capabilitiesAreTouchscreen("3", "400 0 0 0 0 0 0 0 0 0 0"))
        assertEquals(false, SysfsEvdevDeviceInspector.capabilitiesAreTouchscreen("3", "0"))
        assertEquals(false, SysfsEvdevDeviceInspector.capabilitiesAreTouchscreen("0", "400 0 0 0 0 0"))
        assertNull(SysfsEvdevDeviceInspector.capabilitiesAreTouchscreen("not-hex", "400 0 0 0 0 0"))
    }

    @Test fun `unclassified imported evdev target preserves existing convenience workflow`() {
        val registry = registry(
            mapOf("generic.yaml" to genericYaml()),
            evdevInspector = EvdevDeviceInspector { null },
        )
        val document = testProfileDocument(facts = facts).copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "input.evdev")),
            input = ProfileInput(listOf(ProfileEvdevButton("/dev/input/event7", 116, true, "KEYCODE_POWER"))),
        )
        val ref = import(registry, ProfileYaml.serialize(document))

        assertTrue(registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision) is ProfileMutation.Success)
    }

    @Test fun `author maturity is distinct from bundled trust provenance`() {
        val verified = testProfileDocument(facts = facts).copy(
            metadata = testProfileDocument(facts = facts).metadata.copy(maturity = ProfileMaturity.VERIFIED),
        )
        val bundled = registry(mapOf("generic.yaml" to genericYaml(), "verified.yaml" to ProfileYaml.serialize(verified)))
        val importedOnly = registry(mapOf("generic.yaml" to genericYaml()))
        val importedRef = import(importedOnly, ProfileYaml.serialize(verified.copy(id = "community.example.imported")))

        assertTrue(bundled.list().single { it.ref.id == verified.id }.trustedProvenance)
        val imported = importedOnly.list().single { it.ref == importedRef }
        assertEquals(ProfileMaturity.VERIFIED, imported.maturity)
        assertFalse(imported.trustedProvenance)
    }

    @Test fun `soc facts and navigation links cross the summary and runtime boundaries unchanged`() {
        val baseline = testProfileDocument(facts = facts)
        val document = baseline.copy(
            soc = ProfileSoc("Rockchip RK3566", 2020, listOf(ProfileCpuCoreCluster("Arm Cortex-A55", 4))),
            metadata = baseline.metadata.copy(
                links = listOf(ProfileLink("Product page", "https://vendor.example/panel")),
            ),
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(document))

        val summary = registry.list().single { it.ref == ref }
        assertEquals(document.soc, summary.soc)
        assertEquals(
            listOf(
                ProfileLink("Panel details", requireNotNull(document.metadata.source)),
                ProfileLink("Product page", "https://vendor.example/panel"),
            ),
            summary.links,
        )

        assertTrue(registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision) is ProfileMutation.Success)
        val runtime = registry.resolveForStartup().profile
        assertEquals(document.soc, runtime.soc)
        assertEquals(summary.links, runtime.profileLinks)
    }

    @Test fun `incompatible preview summaries never expose navigation links`() {
        val baseline = testProfileDocument(facts = facts)
        val invalid = baseline.copy(
            metadata = baseline.metadata.copy(
                links = listOf(ProfileLink("Misleading\u202eelpmaxe", "https://vendor.example/panel")),
            ),
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val preview = registry.preview(ProfileYaml.serialize(invalid))

        assertFalse(preview.compatible)
        assertTrue(preview.issues.any { it.path == "metadata.links[0].label" })
        assertTrue(requireNotNull(preview.summary).links.isEmpty())
    }

    @Test fun `imported package recommendation cannot enter default privileged tame set`() {
        val packageIntent = ProfilePackageIntent(
            packageName = "com.vendor.panel",
            desiredState = ProfilePackageDesiredState.DISABLED,
            importance = ProfileProvisioningImportance.RECOMMENDED,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val document = testProfileDocument(facts = facts).copy(
            provisioning = testProfileDocument(facts = facts).provisioning.copy(packages = listOf(packageIntent)),
        )
        val ref = import(registry, ProfileYaml.serialize(document))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        val resolved = registry.resolveForStartup().profile

        assertTrue(resolved.tameVendorCandidates.any { it.pkg == "com.vendor.panel" })
        assertTrue(resolved.tameVendorCandidates.none { it.defaultTame })
    }

    @Test fun `preview token is one shot and bound to exact bytes`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val first = ProfileYaml.serialize(testProfileDocument(facts = facts))
        val second = first + "# semantically equivalent but byte-distinct revision\n"
        val preview = registry.preview(first)

        assertEquals(ProfileYaml.parse(first).document, ProfileYaml.parse(second).document)
        assertFalse(ProfileYaml.sha256(first) == ProfileYaml.sha256(second))
        assertEquals(ProfileYaml.sha256(first), preview.contentSha256)
        assertTrue(registry.importProfile(second, preview.previewToken!!) is ProfileMutation.Rejected)
        assertTrue(registry.importProfile(first, preview.previewToken) is ProfileMutation.Rejected)
    }

    @Test fun `same id can hold semantically equivalent immutable raw byte revisions`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val firstRaw = ProfileYaml.serialize(testProfileDocument(version = "1.0.0", facts = facts))
        val secondRaw = firstRaw + "# second immutable raw-byte revision\n"
        assertEquals(ProfileYaml.parse(firstRaw).document, ProfileYaml.parse(secondRaw).document)
        val first = import(registry, firstRaw)
        val second = import(registry, secondRaw)

        assertFalse(first == second)
        assertEquals(ProfileYaml.sha256(firstRaw), first.revision)
        assertEquals(ProfileYaml.sha256(secondRaw), second.revision)
        assertEquals(2, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
        assertEquals(firstRaw, registry.exportProfile(first))
        assertEquals(secondRaw, registry.exportProfile(second))
    }

    @Test fun `failed catalog revision reservation cannot publish an imported file`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val raw = ProfileYaml.serialize(testProfileDocument(facts = facts))
        val preview = registry.preview(raw)
        val ref = preview.summary!!.ref
        preferences.failNextPut()

        val result = registry.importProfile(raw, preview.previewToken!!)

        assertTrue(result is ProfileMutation.Rejected)
        assertFalse(importedFile(ref).exists())
        assertTrue(registry.list().none { it.ref == ref })
    }

    @Test fun `immutable publish syncs content before rename and parent directory after rename`() {
        val persistence = RecordingProfileRevisionPersistence()
        val registry = registry(mapOf("generic.yaml" to genericYaml()), revisionPersistence = persistence)
        val raw = ProfileYaml.serialize(testProfileDocument(facts = facts))

        val result = registry.preview(raw).let { registry.importProfile(raw, it.previewToken!!) }

        assertTrue(result is ProfileMutation.Success)
        assertEquals(
            listOf("write-sync:tmp", "rename:tmp->yaml", "sync-dir:community.example.test-panel"),
            persistence.events
                .filter { it.startsWith("write-sync:") || it.startsWith("rename:") || it.startsWith("sync-dir:") }
                .takeLast(3),
        )
    }

    @Test fun `publish rejects a revision when parent directory sync fails after rename`() {
        val persistence = RecordingProfileRevisionPersistence(failDirectorySync = { it.name == "community.example.test-panel" })
        val registry = registry(mapOf("generic.yaml" to genericYaml()), revisionPersistence = persistence)
        val raw = ProfileYaml.serialize(testProfileDocument(facts = facts))
        val preview = registry.preview(raw)

        val result = registry.importProfile(raw, preview.previewToken!!)

        assertTrue(result is ProfileMutation.Rejected)
        assertTrue(importedFile(preview.summary!!.ref).isFile)
        assertEquals(
            listOf("write-sync:tmp", "rename:tmp->yaml", "sync-dir:community.example.test-panel"),
            persistence.events
                .filter { it.startsWith("write-sync:") || it.startsWith("rename:") || it.startsWith("sync-dir:") }
                .takeLast(3),
        )
    }

    @Test fun `activation preserves one step last known good and supports explicit rollback`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        val beforeSelectRevision = registry.status().catalogRevision

        val selected = registry.select(ProfileSelection.Pinned(ref), beforeSelectRevision)
        assertTrue(selected is ProfileMutation.Success && selected.restartRequired)
        assertEquals("profile-selection-staged", (selected as ProfileMutation.Success).presentation?.code)
        assertEquals(ProfileActivationPhase.PENDING, registry.status().activation.phase)
        assertEquals("activation-pending", registry.status().activation.presentation?.code)
        assertEquals("generic", registry.status().active!!.ref.id)

        val applying = registry.resolveForStartup()
        assertEquals("activation-applying-selected", registry.status().activation.presentation?.code)
        assertEquals(ref, applying.summary.ref)
        assertNotNull(applying.activationGeneration)
        assertTrue(registry.markActivationHealthy(applying.activationGeneration!!))
        assertEquals(ProfileSelection.Auto, registry.status().lastKnownGood)
        assertEquals(ref, registry.status().active!!.ref)

        val rollback = registry.rollbackToLastKnownGood(registry.status().catalogRevision)
        assertTrue(rollback is ProfileMutation.Success && rollback.restartRequired)
        val rolledBack = registry.resolveForStartup()
        assertEquals("generic", rolledBack.summary.ref.id)
        assertTrue(registry.markActivationHealthy(rolledBack.activationGeneration!!))
        assertEquals(ProfileSelection.Pinned(ref), registry.status().lastKnownGood)
    }

    @Test fun `unhealthy applying process rolls back without promoting candidate`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        val applying = registry.resolveForStartup()
        assertEquals(ref, applying.summary.ref)
        val recovered = registry.resolveForStartup()

        assertNull(recovered.activationGeneration)
        assertEquals("generic", recovered.summary.ref.id)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
        assertEquals(ProfileSelection.Auto, registry.status().selection)
    }

    @Test fun `failed unhealthy rollback remains safe and reports that recovery is not durable`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        assertEquals(ref, registry.resolveForStartup().summary.ref)
        preferences.failNextPut()

        val recovered = registry.resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertTrue(recovered.issues.any { it.severity == ProfileIssueSeverity.ERROR && "could not be persisted" in it.message })
        assertEquals(ProfileActivationPhase.APPLYING, registry.status().activation.phase)
    }

    @Test fun `failed invalid pending rollback leaves the staged state atomically intact`() {
        val missing = ProfileRef("community.example.missing", "a".repeat(64))
        preferences.put(
            "selection" to "${missing.id}@${missing.revision}",
            "activation_phase" to ProfileActivationPhase.PENDING.name,
            "activation_generation" to 7L,
            "activation_previous" to "auto",
            "activation_desired" to "${missing.id}@${missing.revision}",
            "activation_message" to "Selection staged; restart required.",
            "catalog_revision" to 41L,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        preferences.failNextPut()

        val recovered = registry.resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        assertTrue(recovered.issues.any { "rollback could not be persisted" in it.message })
        val status = registry.status()
        assertEquals(41L, status.catalogRevision)
        assertEquals(ProfileSelection.Pinned(missing), status.selection)
        assertEquals(ProfileActivationPhase.PENDING, status.activation.phase)
        assertEquals(7L, status.activation.generation)
        assertEquals(ProfileSelection.Auto, status.activation.previous)
        assertEquals(ProfileSelection.Pinned(missing), status.activation.desired)
    }

    @Test fun `unhealthy rollback preserves its established last known good target`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val first = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(first), registry.status().catalogRevision)
        assertTrue(registry.markActivationHealthy(registry.resolveForStartup().activationGeneration!!))
        assertEquals(ProfileSelection.Auto, registry.status().lastKnownGood)

        registry.select(ProfileSelection.Auto, registry.status().catalogRevision)
        assertEquals("generic", registry.resolveForStartup().summary.ref.id)
        val recovered = registry.resolveForStartup()

        assertEquals(first, recovered.summary.ref)
        assertEquals(ProfileSelection.Auto, registry.status().lastKnownGood)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
    }

    @Test fun `automatic bundled revision change uses health gate and retained rollback snapshot`() {
        val oldRaw = ProfileYaml.serialize(testProfileDocument(id = "vendor.test-panel", version = "1.0.0", facts = facts))
        val newRaw = ProfileYaml.serialize(testProfileDocument(id = "vendor.test-panel", version = "1.0.1", facts = facts))
        val oldRef = ProfileRef("vendor.test-panel", ProfileYaml.sha256(oldRaw))
        val newRef = ProfileRef("vendor.test-panel", ProfileYaml.sha256(newRaw))
        val first = registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to oldRaw))

        val initiallyActive = first.resolveForStartup()
        assertEquals(oldRef, initiallyActive.summary.ref)
        assertTrue(first.markStartupHealthy(oldRef))

        val upgraded = registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to newRaw))
        val applying = upgraded.resolveForStartup()
        assertEquals(newRef, applying.summary.ref)
        assertNotNull(applying.activationGeneration)
        assertEquals(ProfileActivationPhase.APPLYING, upgraded.status().activation.phase)

        val afterFailedStartup = registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to newRaw)).resolveForStartup()
        assertEquals(oldRef, afterFailedStartup.summary.ref)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to newRaw)).status().activation.phase)
    }

    @Test fun `rollback revision pruning durably unlinks superseded immutable snapshot`() {
        val persistence = RecordingProfileRevisionPersistence()
        val raws = (0..2).map { patch ->
            ProfileYaml.serialize(testProfileDocument(id = "vendor.test-panel", version = "1.0.$patch", facts = facts))
        }
        val refs = raws.map { ProfileRef("vendor.test-panel", ProfileYaml.sha256(it)) }
        fun bundled(raw: String) = mapOf("generic.yaml" to genericYaml(), "panel.yaml" to raw)

        registry(bundled(raws[0]), revisionPersistence = persistence).let { first ->
            assertTrue(first.markStartupHealthy(first.resolveForStartup().summary.ref))
        }
        registry(bundled(raws[1]), revisionPersistence = persistence).resolveForStartup().let { applying ->
            assertTrue(registry(bundled(raws[1]), revisionPersistence = persistence)
                .markActivationHealthy(applying.activationGeneration!!))
        }
        val third = registry(bundled(raws[2]), revisionPersistence = persistence)
        val applying = third.resolveForStartup()
        persistence.events.clear()

        assertTrue(third.markActivationHealthy(applying.activationGeneration!!))

        assertFalse(rollbackFile(refs[0]).exists())
        assertTrue(rollbackFile(refs[1]).isFile)
        assertTrue(rollbackFile(refs[2]).isFile)
        assertEquals(
            listOf(
                "write-sync:tmp",
                "rename:tmp->yaml",
                "sync-dir:vendor.test-panel",
                "delete:yaml",
                "sync-dir:vendor.test-panel",
            ),
            persistence.events,
        )
    }

    @Test fun `pending status does not mark previous active revision selected`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))

        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        assertFalse(registry.status().active!!.selected)
        assertTrue(registry.list().single { it.ref == ref }.selected)
    }

    @Test fun `healthy imported revision is not copied into trusted bundled rollback storage`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        val applying = registry.resolveForStartup()

        assertTrue(registry.markActivationHealthy(applying.activationGeneration!!))
        assertFalse(File(directory, "device-profiles/rollback/${ref.id}/${ref.revision}.yaml").exists())
        assertEquals(ProfileOrigin.IMPORTED, registry(mapOf("generic.yaml" to genericYaml())).list().single { it.ref == ref }.origin)
    }

    @Test fun `failed teardown can abort only a pending activation`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        assertTrue(registry.abortPendingActivation("Critical owner did not stop."))
        assertEquals(ProfileSelection.Auto, registry.status().selection)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
        assertTrue(registry.status().activation.message!!.contains("did not stop"))
        assertFalse(registry.abortPendingActivation("too late"))
    }

    @Test fun `failed teardown abort leaves the pending activation atomically intact`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        val before = registry.status()
        preferences.failNextPut()

        assertFalse(registry.abortPendingActivation("Critical owner did not stop."))

        val after = registry.status()
        assertEquals(before.catalogRevision, after.catalogRevision)
        assertEquals(before.selection, after.selection)
        assertEquals(before.activation, after.activation)
    }

    @Test fun `missing pinned revision in pending state rolls back and cannot be marked healthy`() {
        var registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        importedFile(ref).delete()
        registry = registry(mapOf("generic.yaml" to genericYaml()))

        val recovered = registry.resolveForStartup()

        assertNull(recovered.activationGeneration)
        assertEquals("generic", recovered.summary.ref.id)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
    }

    @Test fun `selection transitions advance CAS revision across two administrators`() {
        val firstAdmin = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(firstAdmin, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        val secondAdmin = registry(mapOf("generic.yaml" to genericYaml()))
        val sharedRevision = secondAdmin.status().catalogRevision

        assertTrue(firstAdmin.select(ProfileSelection.Pinned(ref), sharedRevision) is ProfileMutation.Success)
        assertTrue(secondAdmin.select(ProfileSelection.Pinned(ref), sharedRevision) is ProfileMutation.Rejected)
    }

    // The next four assertions hold at this lane's base commit: content dedup and the guarded
    // removal path were already shipped. They are written down because Issue #138's catalogue
    // hygiene report is what they answer, and because nothing else stated them as one contract.
    // Reverting the picker collapse cannot make them fail; they are mutation-proven instead.

    @Test fun `byte identical yaml imported twice yields one revision`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val raw = ProfileYaml.serialize(testProfileDocument(version = "0.1.1", facts = facts))

        val first = import(registry, raw)
        val second = import(registry, raw)

        assertEquals(first, second)
        assertEquals(1, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
    }

    @Test fun `changed yaml under the same id remains a distinct revision`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val first = import(registry, ProfileYaml.serialize(testProfileDocument(version = "0.1.1", facts = facts)))
        val second = import(registry, ProfileYaml.serialize(testProfileDocument(version = "0.1.2", facts = facts)))

        assertEquals(first.id, second.id)
        assertFalse("an edited profile must not overwrite the revision it was edited from", first == second)
        assertEquals(2, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
    }

    @Test fun `an inactive imported revision is removable and the active one is refused with its reason`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val inactive = import(registry, ProfileYaml.serialize(testProfileDocument(version = "0.1.1", facts = facts)))
        val active = import(registry, ProfileYaml.serialize(testProfileDocument(version = "0.1.2", facts = facts)))
        registry.select(ProfileSelection.Pinned(active), registry.status().catalogRevision)
        registry.markActivationHealthy(registry.resolveForStartup().activationGeneration!!)

        assertEquals(active, registry.status().active!!.ref)
        val refused = registry.deleteProfile(active, registry.status().catalogRevision)
        assertTrue(refused is ProfileMutation.Rejected)
        assertEquals(
            "referenced-profile-delete-forbidden",
            (refused as ProfileMutation.Rejected).issues.first().presentation!!.code,
        )
        assertTrue(registry.deleteProfile(inactive, registry.status().catalogRevision) is ProfileMutation.Success)
        assertEquals(active, registry.status().active!!.ref)
    }

    // A catalog that already holds the reporter's duplicates before this change lands must present
    // its whole history and keep running exactly what it was running. The picker's collapse is a
    // presentation of this list, so the list is what has to survive; the DOM contract owns the rest.
    @Test fun `a catalog of four pre existing revisions keeps every revision and its active selection`() {
        val refs = (1..4).map { index -> restore(ProfileYaml.serialize(testProfileDocument(version = "0.1.$index", facts = facts))) }
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        registry.select(ProfileSelection.Pinned(refs[2]), registry.status().catalogRevision)
        registry.markActivationHealthy(registry.resolveForStartup().activationGeneration!!)

        val imported = registry.list().filter { it.origin == ProfileOrigin.IMPORTED }
        assertEquals(4, imported.size)
        assertEquals(refs.toSet(), imported.map { it.ref }.toSet())
        assertEquals(1, imported.map { it.ref.id }.distinct().size)
        assertEquals(refs[2], registry.status().active!!.ref)
        assertTrue("every stored revision must carry its import time", imported.all { (it.importedAtEpochMs ?: 0L) > 0L })
    }

    // The import time exists to order a profile's own revisions in the picker. It is read from the
    // immutable file rather than stored beside it, so it must not leak onto bundled content, which
    // has no import event at all and would otherwise sort against a real one.
    @Test fun `bundled revisions carry no import time`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        import(registry, ProfileYaml.serialize(testProfileDocument(version = "0.1.1", facts = facts)))

        val bundled = registry.list().filter { it.origin == ProfileOrigin.BUNDLED }
        assertTrue("the fixture must supply bundled content", bundled.isNotEmpty())
        assertTrue(bundled.all { it.importedAtEpochMs == null })
    }

    @Test fun `last known good imported revision cannot be deleted`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val first = import(registry, ProfileYaml.serialize(testProfileDocument(version = "1.0.0", facts = facts)))
        registry.select(ProfileSelection.Pinned(first), registry.status().catalogRevision)
        val firstApplying = registry.resolveForStartup()
        registry.markActivationHealthy(firstApplying.activationGeneration!!)
        val second = import(registry, ProfileYaml.serialize(testProfileDocument(version = "1.0.1", facts = facts)))
        registry.select(ProfileSelection.Pinned(second), registry.status().catalogRevision)
        val secondApplying = registry.resolveForStartup()
        registry.markActivationHealthy(secondApplying.activationGeneration!!)

        assertEquals(ProfileSelection.Pinned(first), registry.status().lastKnownGood)
        assertTrue(registry.deleteProfile(first, registry.status().catalogRevision) is ProfileMutation.Rejected)
    }

    @Test fun `oversized restored file and interrupted temp are ignored`() {
        val idDir = File(directory, "device-profiles/imported/community.example.oversized").apply { mkdirs() }
        File(idDir, "${"0".repeat(64)}.yaml").writeBytes(ByteArray(ProfileMetadata.MAX_BYTES + 1))
        File(idDir, ".interrupted.tmp").writeText(ProfileYaml.serialize(testProfileDocument()))

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        assertTrue(registry.status().issues.any { "size limit" in it.message })
        assertTrue(registry.list().none { it.origin == ProfileOrigin.IMPORTED })
    }

    @Test fun `restored id mismatch is retained only as an incompatible deletable record`() {
        val raw = ProfileYaml.serialize(testProfileDocument(id = "community.example.actual", facts = facts))
        val wrongDir = File(directory, "device-profiles/imported/community.example.other").apply { mkdirs() }
        File(wrongDir, "${ProfileYaml.sha256(raw)}.yaml").writeText(raw)

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val summary = registry.list().single { it.origin == ProfileOrigin.IMPORTED }
        assertFalse(summary.compatible)
        assertEquals("community.example.other", summary.ref.id)
        assertEquals(raw, registry.exportProfile(summary.ref))
        assertTrue(registry.status().issues.any { "does not match storage id" in it.message })
        assertTrue(registry.deleteProfile(summary.ref, registry.status().catalogRevision) is ProfileMutation.Success)
    }

    @Test fun `per id quota rejects another import before writing`() {
        repeat(ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID) { index ->
            restore(ProfileYaml.serialize(testProfileDocument(version = "1.0.$index", facts = facts)))
        }
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val candidate = ProfileYaml.serialize(testProfileDocument(version = "1.1.0", facts = facts))
        val preview = registry.preview(candidate)

        val result = registry.importProfile(candidate, preview.previewToken!!)

        assertTrue(result is ProfileMutation.Rejected)
        assertTrue((result as ProfileMutation.Rejected).issues.any { it.path == "catalog.quota" })
        assertEquals(ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
    }

    @Test fun `restored catalog deterministically skips files beyond aggregate quota`() {
        repeat(33) { index ->
            val base = ProfileYaml.serialize(testProfileDocument(id = "community.example.aggregate-$index", facts = facts))
            val padding = ProfileMetadata.MAX_BYTES - base.toByteArray().size - 3
            restore(base + "# " + "x".repeat(padding) + "\n")
        }

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        assertEquals(32, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
        assertTrue(registry.status().issues.any { "aggregate byte quota" in it.message })
    }

    @Test fun `restored count quota prioritizes the selected immutable revision`() {
        repeat(ProfileMetadata.MAX_IMPORTED_REVISIONS) { index ->
            restore(ProfileYaml.serialize(testProfileDocument(id = "community.example.count-$index", facts = facts)))
        }
        val protected = restore(ProfileYaml.serialize(testProfileDocument(id = "community.zzz.protected", facts = facts)))
        preferences.put("selection" to "${protected.id}@${protected.revision}")

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        assertEquals(ProfileMetadata.MAX_IMPORTED_REVISIONS, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
        assertTrue(registry.list().any { it.ref == protected })
        assertEquals(protected, registry.status().active!!.ref)
        assertTrue(registry.status().issues.any { "catalog count quota" in it.message })
    }

    @Test fun `missing or corrupt bundled generic uses capability empty emergency fallback`() {
        val missing = registry(emptyMap())
        val corrupt = registry(mapOf("generic.yaml" to "schema: ["))
        val missingResolved = missing.resolveForStartup()
        val corruptResolved = corrupt.resolveForStartup()

        assertEmergency(missingResolved)
        assertEmergency(corruptResolved)
        assertTrue(missing.status().issues.any { "emergency" in it.message.lowercase() })
        assertTrue(corrupt.status().issues.any { "emergency" in it.message.lowercase() })
    }

    @Test fun `startup does not read inactive imported revisions and admin list hydrates their issues`() {
        val inactive = (0 until 20).map { index ->
            val raw = "schema: [ # inactive-$index"
            val ref = ProfileRef("community.example.inactive-$index", ProfileYaml.sha256(raw))
            importedFile(ref).apply { parentFile!!.mkdirs() }.writeText(raw)
            ref
        }
        val reads = mutableListOf<File>()
        val registry = registry(mapOf("generic.yaml" to genericYaml())) { file ->
            reads.add(file)
            file.readText()
        }

        val resolved = registry.resolveForStartup()

        assertEquals("generic", resolved.summary.ref.id)
        assertTrue(registry.markResolvedStartupHealthy())
        assertTrue("inactive revisions read during startup: $reads", reads.isEmpty())

        val listed = registry.list()

        assertEquals(inactive.size, reads.size)
        assertEquals(inactive.toSet(), reads.mapNotNull { expectedRefForTest(it) }.toSet())
        assertEquals(inactive.size, listed.count { it.origin == ProfileOrigin.IMPORTED })
        assertTrue(registry.status().issues.any { "catalog[" in it.path })
    }

    @Test fun `startup reads the pinned revision but leaves unrelated imports cold`() {
        val selectedRaw = ProfileYaml.serialize(
            testProfileDocument(id = "community.example.selected", facts = facts),
        )
        val selected = restore(selectedRaw)
        repeat(12) { index ->
            val raw = "schema: [ # unrelated-$index"
            val ref = ProfileRef("community.example.unrelated-$index", ProfileYaml.sha256(raw))
            importedFile(ref).apply { parentFile!!.mkdirs() }.writeText(raw)
        }
        preferences.put(
            "selection" to "${selected.id}@${selected.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
        )
        val reads = mutableListOf<File>()
        val registry = registry(mapOf("generic.yaml" to genericYaml())) { file ->
            reads.add(file)
            file.readText()
        }

        val resolved = registry.resolveForStartup()

        assertEquals(selected, resolved.summary.ref)
        assertEquals(listOf(selected), reads.mapNotNull(::expectedRefForTest))
    }

    @Test fun `startup preloads last known good revision needed to recover an invalid active pin`() {
        val invalid = restore(incompatibleImportedYaml())
        val lkg = restore(
            ProfileYaml.serialize(
                testProfileDocument(id = "community.example.startup-lkg", facts = facts),
            ),
        )
        val unrelatedRaw = "schema: [ # unrelated"
        val unrelated = ProfileRef("community.example.unrelated", ProfileYaml.sha256(unrelatedRaw))
        importedFile(unrelated).apply { parentFile!!.mkdirs() }.writeText(unrelatedRaw)
        preferences.put(
            "selection" to "${invalid.id}@${invalid.revision}",
            "last_known_good" to "${lkg.id}@${lkg.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
        )
        val reads = mutableListOf<File>()
        val registry = registry(mapOf("generic.yaml" to genericYaml())) { file ->
            reads.add(file)
            file.readText()
        }

        val recovered = registry.resolveForStartup()

        assertEquals(lkg, recovered.summary.ref)
        assertEquals(setOf(invalid, lkg), reads.mapNotNull(::expectedRefForTest).toSet())
        assertFalse(reads.any { expectedRefForTest(it) == unrelated })
        assertTrue(recovered.issues.any { "incompatible" in it.message })
    }

    @Test fun `incompatible imported revision remains listable exportable and deletable but cannot activate`() {
        val raw = incompatibleImportedYaml()
        val ref = restore(raw)
        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val summary = registry.list().single { it.ref == ref }
        assertFalse(summary.compatible)
        assertTrue(summary.issues.any { it.severity == ProfileIssueSeverity.ERROR })
        assertEquals(raw, registry.exportProfile(ref))
        assertTrue(registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision) is ProfileMutation.Rejected)

        val deleted = registry.deleteProfile(ref, registry.status().catalogRevision)
        assertTrue(deleted is ProfileMutation.Success)
        assertNull(registry.exportProfile(ref))
        assertFalse(importedFile(ref).exists())
    }

    @Test fun `failed catalog revision reservation cannot delete an imported file`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        preferences.failNextPut()

        val result = registry.deleteProfile(ref, registry.status().catalogRevision)

        assertTrue(result is ProfileMutation.Rejected)
        assertTrue(importedFile(ref).isFile)
        assertNotNull(registry.exportProfile(ref))
    }

    @Test fun `immutable delete syncs each directory after its entry is removed`() {
        val persistence = RecordingProfileRevisionPersistence()
        val registry = registry(mapOf("generic.yaml" to genericYaml()), revisionPersistence = persistence)
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        persistence.events.clear()

        val result = registry.deleteProfile(ref, registry.status().catalogRevision)

        assertTrue(result is ProfileMutation.Success)
        assertEquals(
            listOf(
                "delete:yaml",
                "sync-dir:community.example.test-panel",
                "delete:community.example.test-panel",
                "sync-dir:imported",
            ),
            persistence.events,
        )
    }

    @Test fun `delete failure after unlink reloads the catalog and stops before directory cleanup`() {
        val persistence = RecordingProfileRevisionPersistence()
        val registry = registry(mapOf("generic.yaml" to genericYaml()), revisionPersistence = persistence)
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        persistence.events.clear()
        persistence.failDirectorySync = { it.name == ref.id }

        val result = registry.deleteProfile(ref, registry.status().catalogRevision)

        assertTrue(result is ProfileMutation.Rejected)
        assertFalse(importedFile(ref).exists())
        assertNull(registry.exportProfile(ref))
        assertEquals(listOf("delete:yaml", "sync-dir:${ref.id}"), persistence.events)
    }

    @Test fun `active pin invalidated by core upgrade restores durable last known good`() {
        // `led.removed-driver` represents a driver accepted by an earlier core but removed by this one.
        val ref = restore(incompatibleImportedYaml())
        val lkg = restore(ProfileYaml.serialize(testProfileDocument(id = "community.example.known-good", facts = facts)))
        preferences.put(
            "selection" to "${ref.id}@${ref.revision}",
            "last_known_good" to "${lkg.id}@${lkg.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val revisionBeforeRecovery = registry.status().catalogRevision

        val recovered = registry.resolveForStartup()

        assertEquals(lkg.id, recovered.profile.id)
        assertNull(recovered.activationGeneration)
        assertEquals(ProfileSelection.Pinned(lkg), registry.status().selection)
        assertEquals(ProfileSelection.Pinned(lkg), registry.status().lastKnownGood)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
        assertTrue(registry.status().catalogRevision > revisionBeforeRecovery)
        val incompatibility = recovered.issues.single { it.presentation?.code == "activation-incompatible-selection-restored" }
        assertEquals(64, ref.revision.length)
        assertEquals(mapOf("id" to ref.id, "revision" to ref.revision.take(12)), incompatibility.presentation!!.params)
        assertAuthoritativeEnglishMatches(incompatibility.message, incompatibility.presentation)
        assertFalse(registry.list().single { it.ref == ref }.compatible)
        assertTrue(registry.deleteProfile(ref, registry.status().catalogRevision) is ProfileMutation.Success)
    }

    @Test fun `active invalid recovery clears an unusable last known good target`() {
        val invalid = restore(incompatibleImportedYaml())
        val stale = ProfileRef("community.example.stale", "b".repeat(64))
        preferences.put(
            "selection" to "${invalid.id}@${invalid.revision}",
            "last_known_good" to "${stale.id}@${stale.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
            "catalog_revision" to 17L,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val recovered = registry.resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        val status = registry.status()
        assertEquals(18L, status.catalogRevision)
        assertEquals(ProfileSelection.Auto, status.selection)
        assertNull(status.lastKnownGood)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, status.activation.phase)
    }

    @Test fun `failed active invalid recovery preserves the entire durable state`() {
        val invalid = restore(incompatibleImportedYaml())
        val stale = ProfileRef("community.example.stale", "b".repeat(64))
        preferences.put(
            "selection" to "${invalid.id}@${invalid.revision}",
            "last_known_good" to "${stale.id}@${stale.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
            "catalog_revision" to 17L,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        preferences.failNextPut()

        val recovered = registry.resolveForStartup()

        assertEquals("generic", recovered.summary.ref.id)
        val incompatibility = recovered.issues.single { it.presentation?.code == "activation-incompatible-recovery-persist-failed" }
        assertEquals(64, invalid.revision.length)
        assertEquals(mapOf("id" to invalid.id, "revision" to invalid.revision.take(12)), incompatibility.presentation!!.params)
        assertAuthoritativeEnglishMatches(incompatibility.message, incompatibility.presentation)
        val status = registry.status()
        assertEquals(17L, status.catalogRevision)
        assertEquals(ProfileSelection.Pinned(invalid), status.selection)
        assertEquals(ProfileSelection.Pinned(stale), status.lastKnownGood)
        assertEquals(ProfileActivationPhase.ACTIVE, status.activation.phase)
    }

    @Test fun `stored schema 1 pin falls back but remains exportable and deletable`() {
        val raw = legacySchemaOneYaml()
        val ref = ProfileRef("community.example.legacy-v1", ProfileYaml.sha256(raw))
        importedFile(ref).apply { parentFile!!.mkdirs() }.writeText(raw)
        preferences.put(
            "selection" to "${ref.id}@${ref.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val stored = registry.list().single { it.ref == ref }
        assertFalse(stored.compatible)
        assertEquals(raw, registry.exportProfile(ref))

        val recovered = registry.resolveForStartup()
        assertEquals("generic", recovered.profile.id)
        assertEquals(ProfileSelection.Auto, registry.status().selection)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)

        assertTrue(registry.deleteProfile(ref, registry.status().catalogRevision) is ProfileMutation.Success)
        assertNull(registry.exportProfile(ref))
    }

    @Test fun `full backup exports imported revisions deterministically with coherent identities`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val second = import(
            registry,
            ProfileYaml.serialize(testProfileDocument(id = "community.example.z", version = "1.0.0", facts = facts)),
        )
        val first = import(
            registry,
            ProfileYaml.serialize(testProfileDocument(id = "community.example.a", version = "1.0.0", facts = facts)),
        )
        registry.select(ProfileSelection.Pinned(second), registry.status().catalogRevision)
        registry.resolveForStartup().activationGeneration!!.let(registry::markActivationHealthy)
        registry.select(ProfileSelection.Pinned(first), registry.status().catalogRevision)
        registry.resolveForStartup().activationGeneration!!.let(registry::markActivationHealthy)

        val backup = registry.exportBackup()
        val encoded = backup.toJson().toString()
        val decoded = ProfileBackup.fromJson(JSONObject(encoded))

        assertEquals(listOf(first, second), backup.revisions.map { it.ref })
        assertEquals(ProfileSelection.Pinned(first), backup.selection)
        assertEquals(first, backup.active)
        assertEquals(ProfileSelection.Pinned(second), backup.lastKnownGood)
        assertEquals(encoded, backup.toJson().toString())
        assertTrue(decoded.issues.toString(), decoded.payload != null)
        assertEquals(backup, decoded.payload)
    }

    @Test fun `restore imports revisions inertly before staging selection with current rollback target`() {
        val target = registry(mapOf("generic.yaml" to genericYaml()))
        val current = import(
            target,
            ProfileYaml.serialize(testProfileDocument(id = "community.example.current", facts = facts)),
        )
        target.select(ProfileSelection.Pinned(current), target.status().catalogRevision)
        target.resolveForStartup().activationGeneration!!.let(target::markActivationHealthy)
        val restoredRaw = ProfileYaml.serialize(
            testProfileDocument(id = "community.example.restored", version = "2.0.0", facts = facts),
        )
        val restoredRef = ProfileRef("community.example.restored", ProfileYaml.sha256(restoredRaw))
        val backup = ProfileBackup(
            revisions = listOf(ProfileBackupRevision(restoredRef, restoredRaw)),
            selection = ProfileSelection.Pinned(restoredRef),
            active = restoredRef,
            lastKnownGood = ProfileSelection.Pinned(restoredRef),
        )

        val plan = target.planBackupRestore(backup)
        val result = target.restoreBackup(backup, plan.expectedCatalogRevision)

        assertTrue(plan.valid)
        assertEquals(ProfileBackupRestoreOutcome.SUCCEEDED, result.outcome)
        assertEquals(listOf(restoredRef), result.imported)
        assertTrue(result.selectionStaged)
        assertTrue(result.restartRequired)
        assertEquals(ProfileActivationPhase.PENDING, target.status().activation.phase)
        assertEquals(ProfileSelection.Pinned(current), target.status().activation.previous)
        assertEquals(ProfileSelection.Pinned(restoredRef), target.status().activation.desired)
        assertEquals(ProfileSelection.Auto, target.status().lastKnownGood)
        assertEquals(restoredRaw, target.exportProfile(restoredRef))
    }

    @Test fun `missing source rollback snapshot is advisory because destination rollback is preserved`() {
        val target = registry(mapOf("generic.yaml" to genericYaml()))
        val oldBundled = ProfileRef("vendor.old-bundled", "1".repeat(64))
        val backup = target.exportBackup().copy(lastKnownGood = ProfileSelection.Pinned(oldBundled))

        val plan = target.planBackupRestore(backup)
        val result = target.restoreBackup(backup, plan.expectedCatalogRevision)

        assertTrue(plan.valid)
        assertTrue(plan.issues.any {
            it.path == "profiles.last_known_good" && it.severity == ProfileIssueSeverity.WARNING
        })
        assertEquals(ProfileBackupRestoreOutcome.SUCCEEDED, result.outcome)
        assertFalse(result.restartRequired)
        assertEquals(ProfileSelection.Auto, target.status().selection)
    }

    @Test fun `invalid backup revision fails closed before any catalog mutation`() {
        val target = registry(mapOf("generic.yaml" to genericYaml()))
        val raw = ProfileYaml.serialize(testProfileDocument(id = "community.example.invalid", facts = facts))
        val wrongRef = ProfileRef("community.example.invalid", "0".repeat(64))
        val backup = ProfileBackup(
            revisions = listOf(ProfileBackupRevision(wrongRef, raw)),
            selection = ProfileSelection.Pinned(wrongRef),
            active = wrongRef,
            lastKnownGood = null,
        )
        val beforeRevision = target.status().catalogRevision

        val plan = target.planBackupRestore(backup)
        val result = target.restoreBackup(backup, plan.expectedCatalogRevision)

        assertFalse(plan.valid)
        assertEquals(ProfileBackupRestoreOutcome.REJECTED, result.outcome)
        assertTrue(result.imported.isEmpty())
        assertEquals(beforeRevision, target.status().catalogRevision)
        assertFalse(importedFile(wrongRef).exists())
        assertEquals(ProfileSelection.Auto, target.status().selection)
    }

    @Test fun `core incompatible backup revision is rejected before filesystem mutation`() {
        val target = registry(mapOf("generic.yaml" to genericYaml()))
        val raw = incompatibleImportedYaml()
        val ref = ProfileRef("community.example.removed-driver", ProfileYaml.sha256(raw))
        val backup = ProfileBackup(
            revisions = listOf(ProfileBackupRevision(ref, raw)),
            selection = ProfileSelection.Pinned(ref),
            active = ref,
            lastKnownGood = ProfileSelection.Pinned(ref),
        )

        val plan = target.planBackupRestore(backup)
        val result = target.restoreBackup(backup, plan.expectedCatalogRevision)

        assertFalse(plan.valid)
        assertTrue(plan.issues.any { "Unknown core driver" in it.message })
        assertEquals(ProfileBackupRestoreOutcome.REJECTED, result.outcome)
        assertFalse(importedFile(ref).exists())
        assertEquals(ProfileSelection.Auto, target.status().selection)
    }

    @Test fun `partial file restore remains inert and reports every stored revision`() {
        val target = registry(mapOf("generic.yaml" to genericYaml()))
        val goodRaw = ProfileYaml.serialize(testProfileDocument(id = "community.example.a", facts = facts))
        val blockedRaw = ProfileYaml.serialize(testProfileDocument(id = "community.example.z", facts = facts))
        val good = ProfileRef("community.example.a", ProfileYaml.sha256(goodRaw))
        val blocked = ProfileRef("community.example.z", ProfileYaml.sha256(blockedRaw))
        File(directory, "device-profiles/imported/${blocked.id}").apply {
            parentFile!!.mkdirs()
            writeText("blocks the id directory")
        }
        val backup = ProfileBackup(
            revisions = listOf(ProfileBackupRevision(good, goodRaw), ProfileBackupRevision(blocked, blockedRaw)),
            selection = ProfileSelection.Pinned(good),
            active = good,
            lastKnownGood = null,
        )

        val plan = target.planBackupRestore(backup)
        val result = target.restoreBackup(backup, plan.expectedCatalogRevision)

        assertTrue(plan.valid)
        assertEquals(ProfileBackupRestoreOutcome.PARTIAL, result.outcome)
        assertEquals(listOf(good), result.imported)
        assertTrue(result.issues.any { "Could not store" in it.message })
        assertFalse(result.selectionStaged)
        assertFalse(result.restartRequired)
        assertEquals(ProfileSelection.Auto, target.status().selection)
        assertEquals(goodRaw, target.exportProfile(good))
    }

    @Test fun `backup json decoder enforces revision and aggregate identity bounds`() {
        val raw = ProfileYaml.serialize(testProfileDocument(facts = facts))
        val malformed = JSONObject()
            .put("schema", ProfileBackup.SCHEMA)
            .put("selection", JSONObject().put("mode", "pinned").put("id", "../escape").put("revision", "ABC"))
            .put("active", JSONObject.NULL)
            .put("last_known_good", JSONObject.NULL)
            .put("revisions", org.json.JSONArray().put(JSONObject()
                .put("id", "community.example.test")
                .put("revision", "0".repeat(64))
                .put("yaml", raw)))

        val decoded = ProfileBackup.fromJson(malformed)

        assertNull(decoded.payload)
        assertTrue(decoded.issues.any { it.path == "profiles.selection.id" })
        assertTrue(decoded.issues.any { it.path == "profiles.selection.revision" })
    }

    private fun registry(
        bundled: Map<String, String>,
        revisionPersistence: ProfileRevisionPersistence = FileProfileRevisionPersistence,
        evdevInspector: EvdevDeviceInspector = EvdevDeviceInspector { null },
        catalogFileReader: ((File) -> String)? = null,
    ) = RuntimeProfileRegistry(
        filesDir = directory,
        preferences = preferences,
        bundledLoader = { bundled },
        facts = facts,
        coreVersion = "1.0.0",
        clock = { 1000L },
        catalogFileReader = catalogFileReader,
        revisionPersistence = revisionPersistence,
        evdevInspector = evdevInspector,
    )

    private fun genericYaml() = ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true))

    private fun assertCapabilityEmpty(profile: DeviceProfile) {
        assertEquals("generic", profile.id)
        assertEquals(ProfileYaml.sha256("capability-empty-emergency-v1"), profile.revision)
        assertNull(profile.soc)
        assertTrue(profile.profileLinks.isEmpty())
        assertEquals(SuForm.NONE, profile.suForm)
        assertFalse(profile.appCanSu)
        assertFalse(profile.usesDaemon)
        assertFalse(profile.hasRecents)
        assertEquals(LedMechanism.NONE, profile.ledMechanism)
        assertEquals(ScreenOff.BRIGHTNESS_ZERO, profile.screenOff)
        assertFalse(profile.hasButtonBacklight)
        assertNull(profile.zigbeeGatewayDir)
        assertNull(profile.relayBase)
        assertTrue(profile.relayBaseFallbacks.isEmpty())
        assertNull(profile.buttonLedGpioBase)
        assertNull(profile.proximityTech)
        assertNull(profile.proximityGpio)
        assertNull(profile.lightTech)
        assertFalse(profile.hasCht8305)
        assertEquals(0f, profile.roomTempOffsetC)
        assertNull(profile.manufacturer)
        assertNull(profile.model)
        assertTrue(profile.evdevButtons.isEmpty())
        assertNull(profile.cpuGovernors)
        assertNull(profile.recommendedDensity)
        assertNull(profile.recommendedFontScale)
        assertNull(profile.physicalPpi)
        assertNull(profile.recommendedWebView)
        assertNull(profile.companionMaxVersion)
        assertTrue(profile.tameVendorCandidates.isEmpty())
        assertEquals(ProvisioningIntent.EMPTY, profile.provisioning)
    }

    private fun assertEmergency(resolved: ResolvedProfile) {
        assertCapabilityEmpty(resolved.profile)
        val expectedRef = ProfileRef("generic", ProfileYaml.sha256("capability-empty-emergency-v1"))
        assertEquals(expectedRef, resolved.summary.ref)
        assertEquals(resolved.profile.id, resolved.summary.ref.id)
        assertEquals(resolved.profile.revision, resolved.summary.ref.revision)
        assertEquals("Emergency safe profile", resolved.summary.displayName)
        assertEquals(ProfileOrigin.BUNDLED, resolved.summary.origin)
        assertEquals(0, resolved.summary.schema)
        assertNull(resolved.summary.minCoreVersion)
        assertTrue(resolved.summary.matchesThisDevice)
        assertTrue(resolved.summary.active)
        assertTrue(resolved.summary.selected)
        assertEquals(ShizukuRecommendation.NONE, resolved.summary.shizukuRecommendation)
        assertTrue(resolved.summary.risks.isEmpty())
        assertEquals("capability-empty-emergency-v1", resolved.summary.contentVersion)
        assertEquals(ProfileMaturity.DRAFT, resolved.summary.maturity)
        assertFalse(resolved.summary.trustedProvenance)
        assertTrue(resolved.summary.compatible)
    }

    private fun import(registry: RuntimeProfileRegistry, raw: String): ProfileRef {
        val preview = registry.preview(raw)
        assertTrue(preview.issues.toString(), preview.compatible)
        assertTrue(registry.importProfile(raw, preview.previewToken!!) is ProfileMutation.Success)
        return preview.summary!!.ref
    }

    private fun importedFile(ref: ProfileRef) = File(directory, "device-profiles/imported/${ref.id}/${ref.revision}.yaml")

    private fun rollbackFile(ref: ProfileRef) = File(directory, "device-profiles/rollback/${ref.id}/${ref.revision}.yaml")

    private fun expectedRefForTest(file: File): ProfileRef? {
        if (file.extension != "yaml") return null
        val id = file.parentFile?.name ?: return null
        val revision = file.nameWithoutExtension
        return ProfileRef(id, revision)
    }

    private fun incompatibleImportedYaml(): String = ProfileYaml.serialize(
        testProfileDocument(id = "community.example.removed-driver", facts = facts).copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "led.removed-driver")),
        ),
    )

    private fun legacySchemaOneYaml(): String = """
        schema: 1
        id: community.example.legacy-v1
        version: 1.0.0
        display_name: Legacy proof of concept
        soc_class: Unknown
        metadata:
          author: Test author
          license: MIT
          maturity: draft
        requires:
          drivers:
            - screen.brightness-zero
        match:
          priority: 500
          fallback: false
          any:
            - priority: 900
              all:
                - field: model
                  op: equals
                  values:
                    - test-panel
        platform:
          su_form: none
          app_can_su: false
          has_recents: true
          shizuku: optional
        hardware:
          led:
            mechanism: none
          screen_off: brightness-zero
        updates: {}
        taming: []
    """.trimIndent() + "\n"

    private fun restore(raw: String): ProfileRef {
        val parsed = ProfileYaml.parse(raw).document!!
        val ref = ProfileRef(parsed.id, ProfileYaml.sha256(raw))
        importedFile(ref).apply { parentFile!!.mkdirs() }.writeText(raw)
        return ref
    }
}

private class RecordingProfileRevisionPersistence(
    var failDirectorySync: (File) -> Boolean = { false },
) : ProfileRevisionPersistence {
    val events = mutableListOf<String>()

    override fun createDirectory(directory: File): Boolean =
        FileProfileRevisionPersistence.createDirectory(directory)

    override fun writeAndSync(file: File, bytes: ByteArray) {
        events += "write-sync:${if (file.name.endsWith(".tmp")) "tmp" else file.extension}"
        FileProfileRevisionPersistence.writeAndSync(file, bytes)
    }

    override fun atomicRename(source: File, target: File): Boolean {
        events += "rename:${if (source.name.endsWith(".tmp")) "tmp" else source.extension}->${target.extension}"
        return FileProfileRevisionPersistence.atomicRename(source, target)
    }

    override fun delete(file: File): Boolean {
        events += "delete:${if (file.isDirectory) file.name else file.extension}"
        return FileProfileRevisionPersistence.delete(file)
    }

    override fun syncDirectory(directory: File) {
        events += "sync-dir:${directory.name}"
        if (failDirectorySync(directory)) error("injected directory sync failure")
        FileProfileRevisionPersistence.syncDirectory(directory)
    }
}

private class MemoryProfilePreferences : ProfilePreferences {
    private val values = linkedMapOf<String, Any>()
    private var rejectNextPut = false
    fun failNextPut() { rejectNextPut = true }
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
