package io.panelassistant.android

import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.NavbarController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * `Native` means the firmware's own navigation bar has authority. It is physically identical to `Off`
 * — neither draws anything — so the contract worth pinning is not what it actuates but who may select
 * it, what happens to a value that becomes invalid, and that nothing treats it as a drawn bar.
 */
internal class NativeNavbarModeTest : PanelRuntimeRig() {
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

    /** A remote command coerces rather than rejects, and "Native" is recognised once it joins MODES, so
     *  the guard has to be explicit or a stray command would take a panel's only navigation away. */
    @Test fun `the remote command path refuses native before actuating it`() {
        val rig = rig()
        try {
            rig.start()
            assertFalse(rig.config.hasNativeNavbar)
            val before = rig.config.navbarMode
            assertEquals(
                PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_HARDWARE_UNAVAILABLE),
                submitNative(rig, "navbar", "native"),
            )
            assertEquals(before, rig.config.navbarMode)
            // A permitted mode does reach the controller.
            assertTrue(submitNative(rig, "navbar", "always_on") is PanelAssistantCommandResult.Failed)
        } finally { rig.close() }
    }

    @Test fun `the public api documents native as profile gated wireFormat`() {
        // Source-text reason: the shipped OpenAPI document is the public API contract.
        val schema = JSONObject(TestSources.appFile("src/main/assets/openapi.json").readText())
            .getJSONObject("paths").getJSONObject("/api/v1/config").getJSONObject("post")
            .getJSONObject("requestBody").getJSONObject("content").getJSONObject("application/x-www-form-urlencoded")
            .getJSONObject("schema").getJSONObject("properties").getJSONObject("navbar_mode")
        assertEquals("string", schema.getString("type"))
        val modes = schema.getJSONArray("enum")
        assertEquals(listOf("Off", "Always on", "Swipe reveal", "Native"), (0 until modes.length()).map { modes.getString(it) })
    }
}
