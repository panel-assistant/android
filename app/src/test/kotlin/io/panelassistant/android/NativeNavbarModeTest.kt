package io.panelassistant.android

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.NavbarController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Native` means the firmware's own navigation bar has authority. It is physically identical to `Off`
 * — neither draws anything — so the contract worth pinning is not what it actuates but who may select
 * it, what happens to a value that becomes invalid, and that nothing treats it as a drawn bar.
 */
class NativeNavbarModeTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.

    private val navbarSpec get() = SettingsRegistry.spec("navbar_mode")!!

    /**
     * A capability set for the resolver. `hasRecents` defaults to TRUE here, unlike [Capabilities]
     * itself, because every one of these cases describes a panel that can navigate by other means —
     * writing that out at each call site would bury the one case where it is false.
     */
    private fun caps(
        hasNativeNavbar: Boolean = false,
        androidShowsNavbar: Boolean? = null,
        vendorNavbarProperty: String? = null,
        hasRecents: Boolean = true,
        hasEvdevButtons: Boolean = false,
    ) = Capabilities(
        hasNativeNavbar = hasNativeNavbar,
        androidShowsNavbar = resolveNativeNavbar(null, vendorNavbarProperty, androidShowsNavbar),
        hasRecents = hasRecents,
        hasEvdevButtons = hasEvdevButtons,
    )

    // ---- availability ------------------------------------------------------------------------

    @Test fun `native is offered only where the profile declares a native bar`() {
        assertEquals(
            listOf("Off", "Always on", "Swipe reveal"),
            navbarSpec.optionsFor(Capabilities(hasNativeNavbar = false)),
        )
        assertEquals(
            listOf("Off", "Always on", "Swipe reveal", "Native"),
            navbarSpec.optionsFor(Capabilities(hasNativeNavbar = true)),
        )
    }

    @Test fun `the whole setting stays available even where native is withheld`() {
        // Gating the option must not gate the setting: Off/Always on/Swipe reveal remain meaningful.
        assertTrue(navbarSpec.availableWhen(Capabilities(hasNativeNavbar = false)))
        assertTrue(navbarSpec.availableWhen(Capabilities(hasNativeNavbar = true)))
    }

    /** The registry gate and the resolver's guard state the same rule; drift between them would let a
     *  choice be offered that the read path then silently rewrites, or vice versa. */
    @Test fun `the offered options and the permitted modes agree for every mode`() {
        for (capable in listOf(false, true)) {
            val offered = navbarSpec.optionsFor(Capabilities(hasNativeNavbar = capable))
            for (mode in NavbarController.MODES) {
                assertEquals(
                    "mode=$mode hasNativeNavbar=$capable",
                    mode in offered,
                    navbarModePermitted(mode, capable),
                )
            }
        }
    }

    @Test fun `permission checking is case and quote insensitive like the actuator`() {
        assertFalse(navbarModePermitted("native", hasNativeNavbar = false))
        assertFalse(navbarModePermitted("NATIVE", hasNativeNavbar = false))
        assertTrue(navbarModePermitted("native", hasNativeNavbar = true))
    }

    // ---- resolution --------------------------------------------------------------------------

    @Test fun `a capable panel resolves to native when nothing is stored`() {
        assertEquals(
            "Native",
            resolveNavbarMode(null, caps(hasNativeNavbar = true, androidShowsNavbar = true)),
        )
    }

    @Test fun `an explicit choice on a capable panel is authoritative`() {
        assertEquals(
            "Always on",
            resolveNavbarMode("Always on", caps(hasNativeNavbar = true, androidShowsNavbar = true)),
        )
        assertEquals(
            "Off",
            resolveNavbarMode("Off", caps(hasNativeNavbar = true, androidShowsNavbar = true)),
        )
    }

    /** The never-strand case: a config bundle captured on a panel with a native bar, restored onto one
     *  without. Coercing to Off would leave no navigation at all, so it must land on the drawn default. */
    @Test fun `a stored native on a panel without a native bar falls back to a working bar`() {
        assertEquals(
            "Swipe reveal",
            resolveNavbarMode("Native", caps(androidShowsNavbar = false)),
        )
        assertEquals(
            "Swipe reveal",
            resolveNavbarMode("Native", caps(vendorNavbarProperty = "false")),
        )
    }

    // ---- native draws nothing ----------------------------------------------------------------

    @Test fun `native is not a drawn bar`() {
        assertTrue(NavbarController.MODE_NATIVE in NavbarController.MODES)
        assertFalse(NavbarController.MODE_NATIVE in NavbarController.OVERLAY_MODES)
        assertFalse(NavbarController.MODE_OFF in NavbarController.OVERLAY_MODES)
        assertEquals(
            setOf(NavbarController.MODE_ALWAYS, NavbarController.MODE_SWIPE),
            NavbarController.OVERLAY_MODES,
        )
    }

    // ---- write admission ---------------------------------------------------------------------

    /** MQTT coerces rather than rejects, and "Native" is recognised once it joins MODES, so the guard
     *  has to be explicit or a stray command would take a panel's only navigation away. */
    @Test fun `the mqtt command path refuses native before actuating it`() {
        val bridge = TestSources.appFile("src/main/kotlin/io/panelassistant/android/MqttBridge.kt").readText()
        val handler = bridge.substring(
            bridge.indexOf("override fun handleNavbar(payload: String)"),
            bridge.indexOf("override fun handleHomeDashboard"),
        )
        val guard = handler.indexOf("navbarModePermitted(")
        val actuate = handler.indexOf("applyAcknowledgedNavbarMode(")
        assertTrue("handleNavbar must consult navbarModePermitted", guard >= 0)
        assertTrue("the guard must precede actuation", actuate > guard)
        assertTrue("a refusal must republish the canonical state", handler.contains("stateConverger.reconcile(\"navbar\", force = true)\n            return"))
    }

    @Test fun `the public api documents native as profile gated wireFormat`() {
        // Source-text reason: the shipped OpenAPI document is the public API contract.
        val openApi = TestSources.appFile("src/main/assets/openapi.json").readText()
        assertTrue(openApi.contains("\"enum\": [\"Off\", \"Always on\", \"Swipe reveal\", \"Native\"]"))
    }

    /** The five navigation buttons removed in 1657dee8 stay removed; a mode that defers to the system
     *  bar must not become a reason to publish remote navigation actions again. */
    @Test fun `no home assistant navigation entity is reintroduced`() {
        val bridge = TestSources.appFile("src/main/kotlin/io/panelassistant/android/MqttBridge.kt").readText()
        listOf("cmdAdminLauncher", "cmdBack", "cmdHome", "cmdLauncher", "cmdRecents").forEach {
            assertFalse("$it must not return as an MQTT action", bridge.contains("private val $it ="))
        }
    }
}
