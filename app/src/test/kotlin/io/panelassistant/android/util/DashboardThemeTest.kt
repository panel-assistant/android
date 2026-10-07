package io.panelassistant.android.util

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DashboardThemeTest {

    @Test
    fun `follow is the default and forces nothing`() {
        assertEquals(DashboardTheme.FOLLOW, DashboardTheme.DEFAULT)
        assertNull(DashboardTheme.forcedDark(DashboardTheme.FOLLOW))
        assertEquals(false, DashboardTheme.forces(DashboardTheme.FOLLOW))
    }

    @Test
    fun `dark and light each force their own scheme`() {
        assertEquals(true, DashboardTheme.forcedDark(DashboardTheme.DARK))
        assertEquals(false, DashboardTheme.forcedDark(DashboardTheme.LIGHT))
        assertTrue(DashboardTheme.forces(DashboardTheme.DARK))
        assertTrue(DashboardTheme.forces(DashboardTheme.LIGHT))
    }

    @Test
    fun `an absent, blank or unrecognised value resolves to follow rather than forcing`() {
        // The failure that matters: a policy this build cannot act on must never be read as a force.
        // Defaulting the other way would have a panel imposing a scheme nobody selected.
        assertEquals(DashboardTheme.FOLLOW, DashboardTheme.policy(null))
        assertEquals(DashboardTheme.FOLLOW, DashboardTheme.policy(""))
        assertEquals(DashboardTheme.FOLLOW, DashboardTheme.policy("   "))
        assertEquals(DashboardTheme.FOLLOW, DashboardTheme.policy("Sepia"))
        assertNull(DashboardTheme.forcedDark("Sepia"))
    }

    @Test
    fun `aliases are resolved by the validator, which is the only place they can act`() {
        // Stated precisely, because it is easy to mis-read: `policy()` does not resolve aliases, and a
        // test asserting `policy("follow") == FOLLOW` would prove nothing, since FOLLOW is also the
        // fallback for anything unrecognised. Aliases act in SettingValue.validate, before a value is
        // ever persisted, and that is where they are pinned — see the validator test below.
        val spec = requireNotNull(SettingsRegistry.spec("dashboard_theme"))
        for ((alias, canonical) in DashboardTheme.ALIASES) {
            assertEquals(Validation.Ok(canonical), SettingValue.validate(spec, alias), alias)
            // And the canonical result is what actually reaches the store, so it round-trips.
            assertEquals(canonical, DashboardTheme.policy((SettingValue.validate(spec, alias) as Validation.Ok).normalized))
        }
    }

    @Test
    fun `case and surrounding space do not change the choice`() {
        assertEquals(DashboardTheme.DARK, DashboardTheme.policy("dark"))
        assertEquals(DashboardTheme.DARK, DashboardTheme.policy("  DARK  "))
        assertEquals(DashboardTheme.LIGHT, DashboardTheme.policy("light"))
        assertEquals(DashboardTheme.FOLLOW, DashboardTheme.policy("follow home assistant"))
    }

    @Test
    fun `the registry spec and the vocabulary cannot drift apart`() {
        val spec = requireNotNull(SettingsRegistry.spec("dashboard_theme"))
        assertEquals(SettingType.ENUM, spec.type)
        assertEquals(DashboardTheme.OPTIONS, spec.options)
        assertEquals(DashboardTheme.DEFAULT, spec.default)
        assertEquals(DashboardTheme.ALIASES, spec.aliases)
        // The spec default must itself be a declared option, or every fresh panel would hold a value
        // the validator rejects.
        assertTrue(spec.default in spec.options)
    }

    @Test
    fun `the HTTP validator accepts every option and alias and names the choices when it refuses`() {
        val spec = requireNotNull(SettingsRegistry.spec("dashboard_theme"))
        for (option in DashboardTheme.OPTIONS) {
            assertEquals(Validation.Ok(option), SettingValue.validate(spec, option), option)
        }
        for ((alias, canonical) in DashboardTheme.ALIASES) {
            assertEquals(Validation.Ok(canonical), SettingValue.validate(spec, alias), alias)
        }
        // Lowercase `dark`/`light` need no alias entry: the ENUM matcher is case-insensitive.
        assertEquals(Validation.Ok(DashboardTheme.DARK), SettingValue.validate(spec, "dark"))
        val bad = SettingValue.validate(spec, "Sepia")
        assertTrue(bad is Validation.Bad, "expected a refusal, got $bad")
        assertEquals("dashboard_theme: must be one of Follow Home Assistant, Dark, Light, Ambient", bad.reason)
    }

    @Test
    fun `the public api documents the same three choices the registry declares`() {
        // OpenAPI is hand-maintained here, so nothing but a test keeps it from drifting away from the
        // registry. Follows the navbar_mode precedent, which pins its enum the same way.
        // Source-text reason: the shipped OpenAPI schema is the public API wire format.
        val openApi = TestSources.appFileOrNull("src/main/assets/openapi.json")
            ?.readText()
        assertTrue(openApi != null, "openapi.json not found")
        assertTrue(
            openApi!!.contains("\"enum\": [\"Follow Home Assistant\", \"Dark\", \"Light\", \"Ambient\"]"),
            "openapi.json must declare the dashboard_theme choices",
        )
        assertTrue(
            openApi.contains("\"dashboard_theme\"") && openApi.contains("\"default\": \"Follow Home Assistant\""),
            "openapi.json must declare the same default the registry does",
        )
    }

    @Test
    fun `the existing three choices resolve to themselves whatever the room says`() {
        for (policy in listOf(DashboardTheme.FOLLOW, DashboardTheme.DARK, DashboardTheme.LIGHT)) {
            for (verdict in listOf(true, false, null)) for (running in listOf(true, false)) {
                assertEquals(policy, DashboardTheme.effective(policy, verdict, running))
            }
        }
    }

    @Test
    fun `ambient resolves to the room's verdict and to follow without one`() {
        assertEquals(DashboardTheme.DARK, DashboardTheme.effective(DashboardTheme.AMBIENT, true, ambientModelRunning = true))
        assertEquals(DashboardTheme.LIGHT, DashboardTheme.effective(DashboardTheme.AMBIENT, false, ambientModelRunning = true))
        assertEquals(DashboardTheme.FOLLOW, DashboardTheme.effective(DashboardTheme.AMBIENT, null, ambientModelRunning = true))
        assertEquals(DashboardTheme.DARK, DashboardTheme.effective("ambient", true, ambientModelRunning = true))
    }

    @Test
    fun `ambient follows home assistant while auto-brightness is off, however the room was last judged`() {
        // Also the no-light-sensor case: the service turns auto-brightness off on a panel with no source.
        for (verdict in listOf(true, false, null)) {
            assertEquals(DashboardTheme.FOLLOW, DashboardTheme.effective(DashboardTheme.AMBIENT, verdict, ambientModelRunning = false))
        }
    }

    @Test
    fun `ambient itself never forces anything, so a consumer that skips effective cannot force the wrong scheme`() {
        assertNull(DashboardTheme.forcedDark(DashboardTheme.AMBIENT))
        assertEquals(false, DashboardTheme.forces(DashboardTheme.AMBIENT))
    }

    @Test
    fun `no option is ever renamed, because one unknown value fails a whole restore`() {
        // A restore is all-or-nothing: a single unrecognised enum member takes the entire archive down
        // with a 422. Widening or respelling this set later therefore breaks newer-to-older restore for
        // any panel holding the new value. Pinning the exact set makes that a deliberate decision.
        assertEquals(listOf("Follow Home Assistant", "Dark", "Light", "Ambient"), DashboardTheme.OPTIONS)
    }
}
