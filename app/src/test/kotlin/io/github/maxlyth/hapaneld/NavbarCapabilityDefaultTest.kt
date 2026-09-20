package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.navbarModeDefault
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A panel with no native navigation bar, no working Recents and no physical buttons has no navigation
 * affordance of its own. Defaulting `navbar_mode` to `Off` there leaves the user with no route to
 * Android Settings once ha-paneld holds Home — which is the outcome `optionRequires` already withholds
 * `Native` to prevent, reached by a different road. This pins the rule that closes it, and pins just
 * as hard the two ways such a rule goes wrong: firing where it was never meant to, and overriding a
 * choice the user made.
 *
 * The capability shapes are read from the bundled profile catalog rather than written out here, so a
 * profile that starts or stops qualifying fails this test rather than changing installed
 * panels silently.
 */
class NavbarCapabilityDefaultTest {

    private fun assetDir(): File = listOf(
        File("src/main/assets/device-profiles"),
        File("app/src/main/assets/device-profiles"),
    ).first(File::isDirectory)

    /** The three declarations the rule reads, as one bundled profile states them. */
    private data class ProfileShape(
        val id: String,
        val hasNativeNavbar: Boolean,
        val hasRecents: Boolean,
        val hasEvdevButtons: Boolean,
    ) {
        /** What the panel reports to the resolver: a real profile, so its declarations are known. */
        fun caps(androidShowsNavbar: Boolean? = null, vendorNavbarProperty: String? = null) = Capabilities(
            hasNativeNavbar = hasNativeNavbar,
            hasRecents = hasRecents,
            hasEvdevButtons = hasEvdevButtons,
            profileId = id,
            hardwareDeclarationsKnown = true,
            androidShowsNavbar = androidShowsNavbar,
            vendorNavbarProperty = vendorNavbarProperty,
        )
    }

    /**
     * Minimal reader for the three fields, deliberately not the production YAML parser: a bug shared
     * with the parser would otherwise be invisible here. `has_recents` absent reads as TRUE, matching
     * `ProfileYaml`'s `?: true`, so an undeclared profile stays out of the rule.
     */
    private fun shapes(): List<ProfileShape> = assetDir().listFiles { f: File -> f.name.endsWith(".yaml") }!!
        .sortedBy(File::getName)
        .map { file ->
            val text = file.readText()
            fun flag(name: String): Boolean? =
                Regex("""^\s{2,}$name:\s*(\S+)\s*$""", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            val buttons = Regex("""^\s{2,}evdev_buttons:\s*(.*)$""", RegexOption.MULTILINE).find(text)
            ProfileShape(
                id = file.name.removeSuffix(".yaml"),
                hasNativeNavbar = flag("has_native_navbar") ?: false,
                hasRecents = flag("has_recents") ?: true,
                // An inline `[]` is an empty list; anything else opens a block list of real mappings.
                hasEvdevButtons = buttons != null && buttons.groupValues[1].trim() !in setOf("[]", ""),
            )
        }

    /** The legacy tiers exactly as they stood before the no-way-out rule was added, so "unchanged for
     *  every other panel" is asserted against the old behaviour rather than against itself. */
    private fun legacyDefault(caps: Capabilities): String {
        if (caps.hasNativeNavbar) return "Native"
        when (caps.vendorNavbarProperty?.trim()?.lowercase(java.util.Locale.ROOT)) {
            "false", "0", "no", "off" -> return "Swipe reveal"
            "true", "1", "yes", "on" -> return "Off"
        }
        if (caps.profileId in setOf("nspanel-pro")) return "Swipe reveal"
        return if (caps.androidShowsNavbar == false) "Swipe reveal" else "Off"
    }

    private val x2i get() = shapes().single { it.id == "shelly-wall-display-x2i" }

    // ---- the catalog is what the rule is aimed at --------------------------------------------

    @Test fun `exactly one bundled profile has no navigation affordance of its own`() {
        val stranded = shapes().filter { !it.hasNativeNavbar && !it.hasRecents && !it.hasEvdevButtons }
        assertEquals(
            "a profile joining or leaving this set changes what a fresh panel does on first boot",
            listOf("shelly-wall-display-x2i"),
            stranded.map(ProfileShape::id),
        )
        // The near misses, named so a future edit to either profile has to come through this test.
        val tpa10 = shapes().single { it.id == "tpa10" }
        assertTrue("tpa10 declares no Recents and is excluded only by its buttons", !tpa10.hasRecents)
        assertTrue(tpa10.hasEvdevButtons)
        assertTrue("wf1589t is excluded by its native bar", shapes().single { it.id == "wf1589t" }.hasNativeNavbar)
        assertTrue("generic must keep Recents, or every unprofiled panel joins the rule",
            shapes().single { it.id == "generic" }.hasRecents)
    }

    // ---- the four acceptance cases -----------------------------------------------------------

    @Test fun `a capable panel keeps the default it had before the rule existed`() {
        val others = shapes().filterNot { it.id == "shelly-wall-display-x2i" }
        assertTrue("the catalog must still hold the panels this guards", others.size >= 8)
        for (shape in others) {
            // Both readable states of the Android resource, and no vendor property, per real hardware.
            for (androidShowsNavbar in listOf(null, true, false)) {
                val caps = shape.caps(androidShowsNavbar = androidShowsNavbar)
                assertEquals(
                    "${shape.id} androidShowsNavbar=$androidShowsNavbar",
                    legacyDefault(caps),
                    navbarModeDefault(caps),
                )
            }
        }
    }

    @Test fun `an x2i shaped panel defaults to a drawn bar instead of nothing`() {
        // The firmware claims a navigation bar it does not usably provide, which is exactly why the
        // legacy tiers answered Off and left such a panel with no way out.
        val caps = x2i.caps(androidShowsNavbar = true)
        assertEquals("Off", legacyDefault(caps))
        assertEquals("Always on", navbarModeDefault(caps))
        assertTrue("the default must be a bar that is actually drawn",
            navbarModeDefault(caps) in NavbarController.OVERLAY_MODES)
        // Always on rather than Swipe reveal: a stranded user does not know to swipe.
        assertNotEquals(NavbarController.MODE_SWIPE, navbarModeDefault(caps))
        // The rule must not depend on the Android resource it is there to overrule.
        assertEquals("Always on", navbarModeDefault(x2i.caps(androidShowsNavbar = false)))
        assertEquals("Always on", navbarModeDefault(x2i.caps(androidShowsNavbar = null)))
        assertEquals("Always on", navbarModeDefault(x2i.caps(vendorNavbarProperty = "true")))
    }

    @Test fun `an explicit choice on an x2i shaped panel is respected including off`() {
        val caps = x2i.caps(androidShowsNavbar = true)
        assertEquals("Off", resolveNavbarMode("Off", caps))
        assertEquals("Swipe reveal", resolveNavbarMode("Swipe reveal", caps))
        assertEquals("Always on", resolveNavbarMode("Always on", caps))
        // Native is still withheld where the firmware draws no bar, so it alone is coerced — to the
        // derived default, which on this panel is a working bar rather than nothing.
        assertEquals("Always on", resolveNavbarMode("Native", caps))
    }

    @Test fun `an upgrade from a build without the rule changes no stored value`() {
        // A panel upgrading carries whatever it had on disk. Every such value must survive, on every
        // shape in the catalogue — that is the whole reason a derived default is safe to introduce.
        for (shape in shapes()) {
            val caps = shape.caps(androidShowsNavbar = true)
            for (stored in NavbarController.MODES) {
                if (stored == NavbarController.MODE_NATIVE && !shape.hasNativeNavbar) continue
                assertEquals("${shape.id} stored=$stored", stored, resolveNavbarMode(stored, caps))
            }
        }
    }

    // ---- the rule must not fire where nothing is known ---------------------------------------

    /**
     * The landmine this rule is one line away from: every input is a Boolean whose `false` is also its
     * unset value. A snapshot that was never populated, or a last-resort profile that declares nothing,
     * would satisfy the predicate while knowing nothing — and put a bar on every panel that installs it.
     */
    @Test fun `an unpopulated capability snapshot cannot trigger the rule`() {
        assertEquals("Off", navbarModeDefault(Capabilities()))
        assertEquals("Off", resolveNavbarMode(null, Capabilities()))
        // The shape of the emergency profile used when the bundled catalog fails to load: it declares
        // no capability at all, and declaring nothing is not evidence of absent hardware.
        val emergency = Capabilities(
            hasNativeNavbar = false,
            hasRecents = false,
            hasEvdevButtons = false,
            profileId = "emergency",
            hardwareDeclarationsKnown = false,
        )
        assertEquals("Off", navbarModeDefault(emergency))
    }

    // ---- the mechanism is general, and the sealed default is untouched ------------------------

    /** `default` feeds the guard-database settings authority, which is sealed and verified natively.
     *  The derived default must sit beside it rather than replace it. */
    @Test fun `the static spec default is unchanged`() {
        assertEquals("Off", SettingsRegistry.spec("navbar_mode")!!.default)
    }

    /** The hook is a property of SettingSpec, not a branch for this one key: a spec that declares no
     *  derivedDefault must resolve to its static default for every capability set. */
    @Test fun `a spec without a derived default is unaffected by capabilities`() {
        val untouched = SettingsRegistry.settable().filter { it.derivedDefault == null }
        assertTrue("the registry must still hold specs that declare no derivation", untouched.size > 20)
        val shapes = listOf(Capabilities(), x2i.caps(), Capabilities(hasNativeNavbar = true))
        for (spec in untouched) {
            for (caps in shapes) assertEquals(spec.key, spec.default, spec.defaultFor(caps))
        }
        assertEquals(
            "navbar_mode is the only key deriving a default today",
            listOf("navbar_mode"),
            SettingsRegistry.settable().filter { it.derivedDefault != null }.map { it.key },
        )
    }

    // ---- the wiring, not just the rule -------------------------------------------------------

    /**
     * Everything above calls the rule with a capability set built by hand, which proves the rule and
     * nothing about whether a panel's profile ever reaches it. This runs the whole path a real panel
     * takes — profile declarations to `Config.navbarMode` — because a correct rule wired to nothing is
     * indistinguishable from the defect it was written to fix.
     */
    @Test fun `a panel resolves its default through the profile it has attached`() {
        assertEquals("Off", Config(readOnlyPreferences()).navbarMode)

        val stranded = Config(readOnlyPreferences())
        stranded.attachProfile(fakeProfile(hasRecents = false))
        assertEquals("Always on", stranded.navbarMode)

        val capable = Config(readOnlyPreferences())
        capable.attachProfile(fakeProfile(hasRecents = true))
        assertEquals("Off", capable.navbarMode)

        val buttoned = Config(readOnlyPreferences())
        buttoned.attachProfile(fakeProfile(hasRecents = false, evdev = listOf(EvdevButton("/dev/input/event0", 1, false, "KEY"))))
        assertEquals("Off", buttoned.navbarMode)

        // A profile that is not catalog-backed declares nothing, and declaring nothing must not act.
        val undeclared = Config(readOnlyPreferences())
        undeclared.attachProfile(fakeProfile(hasRecents = false, fromCatalog = false))
        assertEquals("Off", undeclared.navbarMode)
    }

    private fun fakeProfile(
        hasRecents: Boolean,
        evdev: List<EvdevButton> = emptyList(),
        fromCatalog: Boolean = true,
    ): DeviceProfile = object : DeviceProfile {
        override val id = "test-profile"
        override val revision = "1"
        override val displayName = "Test profile"
        override val socClass = "unknown"
        override val suForm = SuForm.NONE
        override val appCanSu = false
        override val usesDaemon = false
        override val hasRecents = hasRecents
        override val hasNativeNavbar = false
        override val declarationsFromCatalog = fromCatalog
        override val ledMechanism = LedMechanism.NONE
        override val screenOff = ScreenOff.BRIGHTNESS_ZERO
        override val zigbeeGatewayDir: String? = null
        override val relayBase: String? = null
        override val buttonLedGpioBase: Int? = null
        override val manufacturer: String? = null
        override val model: String? = null
        override val evdevButtons = evdev
        override val cpuGovernors: Map<String, String>? = null
    }

    /** Read-only SharedPreferences proxy: every key is absent, so nothing was ever stored. */
    private fun readOnlyPreferences(): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> emptyMap<String, Any?>()
                "getString", "getStringSet", "getInt", "getLong", "getFloat", "getBoolean" -> args!![1]
                "contains" -> false
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> null
                "toString" -> "ReadOnlyEmptyPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

    // ---- the service snapshot must populate what the rule reads -------------------------------

    /** The rule reads three declarations. Two of them were dead fields on the live snapshot before
     *  this change: a capability that is always its `false` default cannot be told from absent
     *  hardware, and reading one as evidence is what would put a bar on every panel. */
    @Test fun `the service snapshot populates the declarations the rule reads`() {
        val service = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
        ).first(File::isFile).readText()
        assertTrue(service.contains("hasEvdevButtons = profile.evdevButtons.isNotEmpty()"))
        assertTrue(service.contains("hardwareDeclarationsKnown = profile.declarationsFromCatalog"))
        assertTrue(service.contains("hasRecents = profile.hasRecents"))
    }
}
