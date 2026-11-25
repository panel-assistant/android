package io.panelassistant.android.control

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.ProfileMutation
import io.panelassistant.android.device.profile.ProfilePreferences
import io.panelassistant.android.device.profile.ProfileRef
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ResolvedProfile
import io.panelassistant.android.device.profile.RuntimeProfileRegistry
import io.panelassistant.android.hardware.GpioControl
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Electron SMT97T reports the same rk3566_t build properties as the ZX-SMT156; only its build string
 * names it. Its two mains relays are GPIO 4 and 5 on the vendor /dev/gpio_control ioctl, so they are
 * offered only on the SMT97's own identity and only when that node is usable.
 */
class ElectronSmt97RelayTest {

    @Test fun smt97BuildStringSelectsItsOwnProfileWithTwoRelays() {
        val resolved = resolve(SMT97)
        assertEquals("zx-smt97", resolved.profile.id)
        val gpio = FakeGpio()
        val relay = RelayController(resolved.profile, FakeRootShell(), gpioControl = gpio, buildDisplay = { SMT97.buildDisplay })
        assertEquals(2, relay.count())
        assertTrue(relay.set(1, true))
        assertTrue(relay.set(2, false))
        assertFalse(relay.set(3, true))
        assertEquals(listOf(4 to true, 5 to false), gpio.writes)
        gpio.state[5] = true
        assertEquals(true, relay.read(2))
    }

    @Test fun smt156AndOtherRk3566BuildsKeepTheirProfileAndGetNoRelay() {
        for (facts in listOf(SMT156, DeviceFacts("rk3566_t", "rk3566_t", "", "ZX-SMT156-R128V1.2B"), DeviceFacts("rk3566_t", "rk3566_t", "", "zx-smt9"))) {
            val resolved = resolve(facts)
            assertEquals("$facts", "zx-smt156", resolved.profile.id)
            val gpio = FakeGpio()
            val relay = RelayController(resolved.profile, FakeRootShell(), gpioControl = gpio, buildDisplay = { SMT97.buildDisplay })
            assertEquals("$facts", 0, relay.count())
            assertFalse(relay.set(1, true))
            assertTrue(gpio.writes.isEmpty())
        }
    }

    @Test fun relaysStayHiddenWhenTheNodeIsNotUsable() {
        val gpio = FakeGpio(available = false)
        val relay = RelayController(resolve(SMT97).profile, FakeRootShell(), gpioControl = gpio, buildDisplay = { SMT97.buildDisplay })
        assertEquals(0, relay.count())
        assertFalse(relay.set(1, true))
        assertNull(relay.read(1))
        assertTrue(gpio.writes.isEmpty())
    }

    // An owner's pin bypasses automatic matching, so it must still refuse the SMT97 relays on an SMT156.
    @Test fun smt97PinIsRefusedOnSmt156AndAcceptedOnSmt97() {
        for ((facts, accepted) in listOf(SMT156 to false, SMT97 to true)) withRegistry(facts) { registry ->
            val ref = BundledProfileFixtures.bundledById.getValue("zx-smt97").let { ProfileRef(it.document.id, it.rawSha256) }
            val selected = registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
            assertEquals("$facts $selected", accepted, selected is ProfileMutation.Success)
        }
    }

    // The SMT97 profile reaches a panel whose live build string is something else (a pin made before a
    // firmware change, or a restored backup): its relays stay off.
    @Test fun relaysNeedTheLiveSmt97BuildString() {
        val gpio = FakeGpio()
        val relay = RelayController(resolve(SMT97).profile, FakeRootShell(), gpioControl = gpio, buildDisplay = { "ZX-SMT156-R128V1.2B" })
        assertEquals(0, relay.count())
        assertFalse(relay.set(1, true))
        assertTrue(gpio.writes.isEmpty())
    }

    // An unreadable relay output keeps the topology unknown, and discovery retries once it reads.
    @Test fun anUnreadableRelayHidesBothUntilItReads() {
        val gpio = FakeGpio(unreadable = mutableSetOf(5))
        val relay = RelayController(resolve(SMT97).profile, FakeRootShell(), gpioControl = gpio, buildDisplay = { SMT97.buildDisplay })
        assertEquals(0, relay.count())
        assertFalse(relay.set(1, true))
        gpio.unreadable.clear()
        assertEquals(2, relay.count())
    }

    // These GPIOs switch mains loads, and an imported profile supplies its own match, so only the reviewed
    // SMT97T mapping is accepted.
    @Test fun anImportedProfileCannotPointTheIoctlAtOtherPins() {
        val shipped = BundledProfileFixtures.bundledById.getValue("zx-smt97").rawYaml
        for (mapping in listOf("    - 4\n    - 6", "    - 0\n    - 1", "    - 5\n    - 4")) {
            val altered = shipped.replace("  relay_gpio_control:\n    - 4\n    - 5", "  relay_gpio_control:\n$mapping")
            assertTrue(altered != shipped)
            val document = requireNotNull(io.panelassistant.android.device.profile.ProfileYaml.parse(altered).document)
            val issues = io.panelassistant.android.device.profile.ProfileValidator.validate(document, BuildConfig.VERSION_NAME, bundled = false)
            assertTrue("$mapping $issues", issues.any { it.path == "hardware.relay_gpio_control" })
        }
    }

    private class FakeGpio(private val available: Boolean = true, val unreadable: MutableSet<Int> = mutableSetOf()) : GpioControl {
        val writes = mutableListOf<Pair<Int, Boolean>>()
        val state = mutableMapOf<Int, Boolean>()
        override fun available() = available
        override fun write(gpio: Int, on: Boolean): Boolean { writes += gpio to on; state[gpio] = on; return true }
        override fun read(gpio: Int): Boolean? = if (gpio in unreadable) null else state[gpio] ?: false
    }

    private fun resolve(facts: DeviceFacts): ResolvedProfile = withRegistry(facts) { it.resolveForStartup() }

    private fun <T> withRegistry(facts: DeviceFacts, body: (RuntimeProfileRegistry) -> T): T {
        val filesDir = scratchDirectory("smt97-relay").toFile()
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
        // From the owner's getprop (ha-integration #37): both boards report rk3566_t.
        val SMT97 = DeviceFacts("rk3566_t", "rk3566_t", "", "ZX-SMT97-R128V1.2A-GG-9.7-J4.62-20250106")
        val SMT156 = DeviceFacts("rk3566_t", "rk3566_t", "")
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

    @Test fun theShippedMappingStillValidates() {
        val document = BundledProfileFixtures.bundledById.getValue("zx-smt97").document
        val issues = io.panelassistant.android.device.profile.ProfileValidator.validate(document, BuildConfig.VERSION_NAME, bundled = false)
        assertTrue("$issues", issues.none { it.path == "hardware.relay_gpio_control" })
    }
}
