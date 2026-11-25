package io.panelassistant.android.control

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.device.profile.DataDeviceProfile
import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.ProfileBackup
import io.panelassistant.android.device.profile.ProfileBackupRestoreOutcome
import io.panelassistant.android.device.profile.ProfileMutation
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfilePreferences
import io.panelassistant.android.device.profile.ProfileYaml
import io.panelassistant.android.device.profile.ResolvedProfile
import io.panelassistant.android.device.profile.RuntimeProfileRegistry
import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.ZigbeeReadFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NSPanel Pro Gen2 relays switch mains loads, so they are offered only on the Gen2's own identity.
 * Each case resolves the shipped profiles from Android identity facts, then asks the relay controller
 * built from the chosen profile what it offers, against a helper that reports four `st_relay` nodes
 * (the first-generation 120P shows four with no relay behind them).
 */
class NspanelProGen2RelayTest {

    @Test fun firstGenerationIdentitiesGetNoRelay() {
        // ro.product.device px30_evb is what every first-generation 86P and 120P update package requires.
        val firstGeneration = listOf(
            DeviceFacts("px30_evb", "px30_evb", "NSPanel86P_1.5.5"),
            DeviceFacts("px30_evb", "px30_evb", "NSPanel120P_3.7.1"),
            DeviceFacts("px30_evb", "px30_evb", "s6_android_2.9.9"),
            DeviceFacts("px30_evb", "px30_evb", ""),
            DeviceFacts("PX30_EVB", "PX30_EVB", "NSPanel86P_4.9.5"),
        )
        for (facts in firstGeneration) {
            val resolved = resolve(facts)
            assertEquals("$facts", "nspanel-pro", resolved.profile.id)
            assertTrue("$facts: ${resolved.issues}", resolved.issues.none { it.message.contains("Ambiguous") })
            val root = FakeRootShell()
            val relay = RelayController(resolved.profile, root, fourNodeHelper().client)
            assertEquals("$facts", 0, relay.count())
            assertFalse(relay.set(1, true))
            assertTrue(root.ran.isEmpty())
        }
    }

    @Test fun secondGenerationIdentityGetsExactlyTwoRelays() {
        // ro.product.device PX30_Android11 is what every Gen2 update package requires. The product
        // version is unknown, so cases with none and with a first-generation-shaped one both count.
        val secondGeneration = listOf(
            DeviceFacts("unknown", "PX30_Android11", ""),
            DeviceFacts("PX30_Android11", "PX30_Android11", "NSPanel86P_4.9.0"),
            DeviceFacts("rk3326", "px30_android11", "unknown"),
        )
        for (facts in secondGeneration) {
            val resolved = resolve(facts)
            assertEquals("$facts", "nspanel-pro-gen2", resolved.profile.id)
            val root = FakeRootShell()
            val relay = RelayController(resolved.profile, root, fourNodeHelper().client)
            assertEquals("$facts", 2, relay.count())
            assertFalse(relay.set(3, true))
            assertTrue(root.ran.isEmpty())
            assertTrue(relay.set(2, true))
            assertEquals(1, root.ran.size)
            assertTrue(root.ran.single(), root.ran.single().contains("/sys/class/st_relay/relay2"))
        }
    }

    @Test fun nearMissDevicesStayOffTheGen2Profile() {
        for (device in listOf("px30_android11_x", "px30_android1", "px30_android12", "rk3326_r")) {
            val resolved = resolve(DeviceFacts("unknown", device, ""))
            assertFalse(device, resolved.profile.id == "nspanel-pro-gen2")
            assertEquals(device, 0, RelayController(resolved.profile, FakeRootShell(), fourNodeHelper().client).count())
        }
    }

    @Test fun exportedGen2ProfileKeepsItsRelayCount() {
        // Export and managed delivery re-serialise the profile; a dropped count would lift the cap.
        val shipped = BundledProfileFixtures.bundledById.getValue("nspanel-pro-gen2").document
        val reparsed = requireNotNull(ProfileYaml.parse(ProfileYaml.serialize(shipped)).document)
        assertEquals(shipped, reparsed)
        val profile = DataDeviceProfile(document = reparsed, productVersion = "", revision = "exported", trustedBundledContent = false)
        assertEquals(2, RelayController(profile, FakeRootShell(), fourNodeHelper().client).count())
    }

    // An owner's pin and a restored backup bypass automatic matching, so both refuse the Gen2 profile (and its
    // relays) on a first-generation panel and accept it on a Gen2.
    @Test fun gen2PinIsRefusedOnFirstGenerationAndAcceptedOnGen2() {
        for ((facts, accepted) in listOf(FIRST_GENERATION to false, GEN2 to true)) withRegistry(facts) { registry ->
            val selected = registry.select(ProfileSelection.Pinned(gen2Ref()), registry.status().catalogRevision)
            if (accepted) {
                assertTrue("$facts $selected", selected is ProfileMutation.Success)
                assertEquals(ProfileSelection.Pinned(gen2Ref()), registry.status().selection)
            } else {
                assertTrue("$facts $selected", selected is ProfileMutation.Rejected)
                assertTrue("$facts", (selected as ProfileMutation.Rejected).issues.any { it.path == "selection.match" })
                assertEquals(ProfileSelection.Auto, registry.status().selection)
                assertEquals("nspanel-pro", registry.resolveForStartup().profile.id)
            }
        }
    }

    @Test fun restoredGen2PinIsRefusedOnFirstGenerationAndAcceptedOnGen2() {
        val backup = ProfileBackup(
            revisions = emptyList(),
            selection = ProfileSelection.Pinned(gen2Ref()),
            active = gen2Ref(),
            lastKnownGood = ProfileSelection.Pinned(gen2Ref()),
        )
        for ((facts, accepted) in listOf(FIRST_GENERATION to false, GEN2 to true)) withRegistry(facts) { registry ->
            val plan = registry.planBackupRestore(backup)
            val result = registry.restoreBackup(backup, plan.expectedCatalogRevision)
            assertEquals("$facts ${plan.issues}", accepted, plan.valid)
            if (accepted) {
                assertEquals(ProfileBackupRestoreOutcome.SUCCEEDED, result.outcome)
                assertTrue(result.selectionStaged)
                assertEquals(ProfileSelection.Pinned(gen2Ref()), registry.status().selection)
            } else {
                assertTrue("$facts", plan.issues.any { it.path == "profiles.selection" && it.presentation?.code == "activation-device-mismatch" })
                assertEquals(ProfileBackupRestoreOutcome.REJECTED, result.outcome)
                assertFalse(result.selectionStaged)
                assertEquals(ProfileSelection.Auto, registry.status().selection)
            }
        }
    }

    private fun gen2Ref() = BundledProfileFixtures.bundledById.getValue("nspanel-pro-gen2").let { ProfileRef(it.document.id, it.rawSha256) }

    private fun fourNodeHelper() = ZigbeeReadFixture().apply {
        version = "HELPER version=1.12.0 proto=1.12"
        replies["RELAYREAD ST_RELAY COUNT"] = "RELAYREAD ST_RELAY COUNT=4\n".toByteArray()
        replies["RELAYREAD STRELAY COUNT"] = "RELAYREAD STRELAY COUNT=4\n".toByteArray()
        replies["RELAYREAD RELAY COUNT"] = "RELAYREAD RELAY COUNT=4\n".toByteArray()
    }

    private fun resolve(facts: DeviceFacts): ResolvedProfile = withRegistry(facts) { it.resolveForStartup() }

    private fun <T> withRegistry(facts: DeviceFacts, body: (RuntimeProfileRegistry) -> T): T {
        val filesDir = scratchDirectory("gen2-relay").toFile()
        return try {
            body(RuntimeProfileRegistry(
                filesDir = filesDir,
                preferences = MemoryPreferences(),
                bundledLoader = BundledProfileFixtures::rawByName,
                facts = facts,
                coreVersion = BuildConfig.VERSION_NAME,
                clock = { 1_000L },
            ))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private companion object {
        // ro.product.device as each generation's update packages require it.
        val FIRST_GENERATION = DeviceFacts("px30_evb", "px30_evb", "NSPanel86P_1.5.5")
        val GEN2 = DeviceFacts("unknown", "PX30_Android11", "")
    }

    private class MemoryPreferences : ProfilePreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getString(key: String, default: String): String = values[key] as? String ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun put(vararg values: Pair<String, Any?>): Boolean {
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }
}
