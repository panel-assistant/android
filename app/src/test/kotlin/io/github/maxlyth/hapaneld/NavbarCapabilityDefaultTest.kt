package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.navbarModeDefault
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
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
 * Every case runs against the real bundled profiles, parsed from the shipped YAML by the production
 * parser, so a profile that starts or stops qualifying fails here rather than changing installed
 * panels silently — and so the rule is proven on the declarations panels actually carry.
 */
class NavbarCapabilityDefaultTest {

    private val profiles: List<DeviceProfile> get() =
        BundledProfileFixtures.bundled.map { it.profile() }.sortedBy(DeviceProfile::id)

    private val x2i: DeviceProfile get() = BundledProfileFixtures.profile("shelly-wall-display-x2i")

    /** The capability set a panel carrying this profile reports, as `Config.navbarCapabilities` builds
     *  it. The two visibility signals are parameters because only the Android edge can read them. */
    private fun capsOf(
        profile: DeviceProfile,
        androidShowsNavbar: Boolean? = null,
        vendorNavbarProperty: String? = null,
    ) = Capabilities(
        hasNativeNavbar = profile.hasNativeNavbar,
        hasRecents = profile.hasRecents,
        hasEvdevButtons = profile.evdevButtons.isNotEmpty(),
        profileId = profile.id,
        hardwareDeclarationsKnown = profile.declarationsFromCatalog,
        androidShowsNavbar = androidShowsNavbar,
        vendorNavbarProperty = vendorNavbarProperty,
    )

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

    // ---- the catalog is what the rule is aimed at --------------------------------------------

    @Test fun `exactly one bundled profile has no navigation affordance of its own`() {
        val stranded = profiles.filter {
            !it.hasNativeNavbar && !it.hasRecents && it.evdevButtons.isEmpty()
        }
        assertEquals(
            "a profile joining or leaving this set changes what a fresh panel does on first boot",
            listOf("shelly-wall-display-x2i"),
            stranded.map(DeviceProfile::id),
        )
        // The near misses, named so a future edit to either profile has to come through this test.
        val tpa10 = BundledProfileFixtures.profile("tpa10")
        assertTrue("tpa10 declares no Recents and is excluded only by its buttons", !tpa10.hasRecents)
        assertTrue(tpa10.evdevButtons.isNotEmpty())
        assertTrue(
            "wf1589t is excluded by its native bar",
            BundledProfileFixtures.profile("wf1589t").hasNativeNavbar,
        )
        assertTrue(
            "generic must keep Recents, or every unprofiled panel joins the rule",
            BundledProfileFixtures.profile("generic").hasRecents,
        )
        // Every bundled profile is catalog-backed, so its declarations are evidence rather than silence.
        assertTrue(profiles.all(DeviceProfile::declarationsFromCatalog))
    }

    // ---- the four acceptance cases -----------------------------------------------------------

    @Test fun `a capable panel keeps the default it had before the rule existed`() {
        val others = profiles.filterNot { it.id == "shelly-wall-display-x2i" }
        assertTrue("the catalog must still hold the panels this guards", others.size >= 8)
        for (profile in others) {
            // Both readable states of the Android resource plus unknown, and no vendor property.
            for (androidShowsNavbar in listOf(null, true, false)) {
                val caps = capsOf(profile, androidShowsNavbar = androidShowsNavbar)
                assertEquals(
                    "${profile.id} androidShowsNavbar=$androidShowsNavbar",
                    legacyDefault(caps),
                    navbarModeDefault(caps),
                )
            }
        }
    }

    @Test fun `an x2i shaped panel defaults to a drawn bar instead of nothing`() {
        // The firmware claims a navigation bar it does not usably provide, which is exactly why the
        // legacy tiers answered Off and left such a panel with no way out.
        val caps = capsOf(x2i, androidShowsNavbar = true)
        assertEquals("Off", legacyDefault(caps))
        assertEquals("Always on", navbarModeDefault(caps))
        // Through the mechanism that actually ships, not only the rule function: the spec must declare
        // the derivation and SettingSpec must consult it, or the rule is correct and never consulted.
        assertEquals("Always on", SettingsRegistry.spec("navbar_mode")!!.defaultFor(caps))
        assertTrue(
            "the default must be a bar that is actually drawn",
            navbarModeDefault(caps) in NavbarController.OVERLAY_MODES,
        )
        // Always on rather than Swipe reveal: a stranded user does not know to swipe.
        assertNotEquals(NavbarController.MODE_SWIPE, navbarModeDefault(caps))
        // The rule must not depend on the signals it is there to overrule.
        assertEquals("Always on", navbarModeDefault(capsOf(x2i, androidShowsNavbar = false)))
        assertEquals("Always on", navbarModeDefault(capsOf(x2i, androidShowsNavbar = null)))
        assertEquals("Always on", navbarModeDefault(capsOf(x2i, vendorNavbarProperty = "true")))
    }

    @Test fun `an explicit choice on an x2i shaped panel is respected including off`() {
        val caps = capsOf(x2i, androidShowsNavbar = true)
        assertEquals("Off", resolveNavbarMode("Off", caps))
        assertEquals("Swipe reveal", resolveNavbarMode("Swipe reveal", caps))
        assertEquals("Always on", resolveNavbarMode("Always on", caps))
        // Native is still withheld where the firmware draws no bar, so it alone is coerced — to the
        // derived default, which on this panel is a working bar rather than nothing.
        assertEquals("Always on", resolveNavbarMode("Native", caps))
    }

    @Test fun `an upgrade from a build without the rule changes no stored value`() {
        // A panel upgrading carries whatever it had on disk. Every such value must survive, on every
        // profile in the catalogue — that is the whole reason a derived default is safe to introduce.
        for (profile in profiles) {
            val caps = capsOf(profile, androidShowsNavbar = true)
            for (stored in NavbarController.MODES) {
                if (stored == NavbarController.MODE_NATIVE && !profile.hasNativeNavbar) continue
                assertEquals("${profile.id} stored=$stored", stored, resolveNavbarMode(stored, caps))
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

    // ---- the wiring, not just the rule -------------------------------------------------------

    /**
     * Everything above calls the rule with a capability set assembled in the test, which proves the
     * rule and nothing about whether a panel's profile ever reaches it. This runs the whole path a
     * real panel takes — shipped YAML, production parser, attached profile, `Config.navbarMode` —
     * because a correct rule wired to nothing is indistinguishable from the defect it was written to
     * fix. On this classpath neither navbar-visibility signal is readable, which is the honest unknown
     * case and the one the legacy tiers answered with `Off`.
     */
    @Test fun `a panel resolves its default through the profile it has attached`() {
        assertEquals("a Config with no profile must derive nothing", "Off", emptyConfig().navbarMode)

        val stranded = emptyConfig()
        stranded.attachProfile(x2i)
        assertEquals("Always on", stranded.navbarMode)

        for (profile in profiles.filterNot { it.id == "shelly-wall-display-x2i" }) {
            val config = emptyConfig()
            config.attachProfile(profile)
            assertEquals(profile.id, legacyDefault(capsOf(profile)), config.navbarMode)
        }

        // A profile that is not catalog-backed carries the same stranded shape and must still derive
        // nothing: the last-resort profile used when the catalog fails to load declares no capability
        // at all, so reading its silence as absent hardware would put a bar on a panel nobody measured.
        val undeclared = emptyConfig()
        undeclared.attachProfile(NonCatalogProfile)
        assertTrue("the fixture must have the stranded shape, or it proves nothing",
            !NonCatalogProfile.hasNativeNavbar && !NonCatalogProfile.hasRecents &&
                NonCatalogProfile.evdevButtons.isEmpty())
        assertEquals("Off", undeclared.navbarMode)
    }

    /** The shape of the last-resort profile: stranded on every declaration, and catalog-backed on
     *  none. `declarationsFromCatalog` is left at its interface default deliberately. */
    private object NonCatalogProfile : DeviceProfile {
        override val id = "last-resort"
        override val revision = "1"
        override val displayName = "Last resort"
        override val socClass = "unknown"
        override val suForm = SuForm.NONE
        override val appCanSu = false
        override val usesDaemon = false
        override val hasRecents = false
        override val hasNativeNavbar = false
        override val ledMechanism = LedMechanism.NONE
        override val screenOff = ScreenOff.BRIGHTNESS_ZERO
        override val zigbeeGatewayDir: String? = null
        override val relayBase: String? = null
        override val buttonLedGpioBase: Int? = null
        override val manufacturer: String? = null
        override val model: String? = null
        override val evdevButtons = emptyList<EvdevButton>()
        override val cpuGovernors: Map<String, String>? = null
    }

    /** A stored value wins over the derived default on the real path too, not only in the resolver. */
    @Test fun `a stored value survives the whole path on a stranded panel`() {
        val config = Config(preferences(mapOf("navbar_mode" to "Off")))
        config.attachProfile(x2i)
        assertEquals("Off", config.navbarMode)
    }

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
        val shapes = listOf(Capabilities(), capsOf(x2i), Capabilities(hasNativeNavbar = true))
        for (spec in untouched) {
            for (caps in shapes) assertEquals(spec.key, spec.default, spec.defaultFor(caps))
        }
        assertEquals(
            "navbar_mode is the only key deriving a default today",
            listOf("navbar_mode"),
            SettingsRegistry.settable().filter { it.derivedDefault != null }.map { it.key },
        )
    }

    private fun emptyConfig(): Config = Config(preferences(emptyMap()))

    /** SharedPreferences proxy over a fixed map; absent keys return the caller's default. */
    private fun preferences(stored: Map<String, String>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> stored.toMap()
                "getString" -> stored[args!![0] as String] ?: args[1]
                "getStringSet", "getInt", "getLong", "getFloat", "getBoolean" -> args!![1]
                "contains" -> stored.containsKey(args!![0] as String)
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> null
                "toString" -> "FixedPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
}
