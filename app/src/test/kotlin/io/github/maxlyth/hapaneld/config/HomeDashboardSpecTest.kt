package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `home_dashboard` accepts a specific VIEW, not only a dashboard root, so the Custom path input in
 * setup and Configure has a real API behind it. The validator's job is narrow and deliberate: reject
 * what could never address a dashboard on this panel's own Home Assistant, and leave "does this
 * dashboard exist" to the renderer, which is the only layer that knows the account's list.
 */
class HomeDashboardSpecTest {
    private val spec = requireNotNull(SettingsRegistry.spec("home_dashboard"))

    private fun accepted(raw: String): String {
        val result = SettingValue.validate(spec, raw)
        assertTrue("rejected $raw: $result", result is Validation.Ok)
        return (result as Validation.Ok).normalized
    }

    private fun rejected(raw: String) {
        val result = SettingValue.validate(spec, raw)
        assertTrue("accepted $raw", result is Validation.Bad)
        assertTrue(
            "the reason must name the setting and show the shape expected: $result",
            (result as Validation.Bad).reason.startsWith("home_dashboard:"),
        )
    }

    @Test fun `a view below a dashboard root is accepted and stored canonically`() {
        // The issue's own examples. Before this, only the root /dashboard-test could be chosen.
        assertEquals("/dashboard-test/alpha", accepted("/dashboard-test/alpha"))
        assertEquals("/dashboard-test/laundry", accepted("dashboard-test/laundry"))
        assertEquals("/alpha/view?kiosk=1#main", accepted("  /alpha/view?kiosk=1#main  "))
        assertEquals("/lovelace/0", accepted("/lovelace/0"))
    }

    @Test fun `every account-default spelling is stored as the blank sentinel`() {
        // Stored verbatim, `/` or `/?kiosk` is absent from the dashboard list, so the renderer would
        // follow the account default while both pickers reopened in Custom showing a path the panel is
        // not using. One stored spelling removes that disagreement.
        for (auto in listOf("", "   ", "/", "//", "/#view", " /?kiosk ")) {
            assertEquals("Auto spelling $auto was not normalized", "", accepted(auto))
        }
    }

    @Test fun `a path that could never address a dashboard here is refused at the API`() {
        // These are exactly the values the renderer would discard, so accepting them would persist a
        // setting that silently does nothing — the failure mode this validator exists to remove.
        for (bad in listOf(
            "https://ha.example/wall-panel",
            "//ha.example/wall-panel",
            "../wall-panel",
            "wall\\panel",
            "/alpha/%2e%2e/evil",
            "/alpha/%2Foutside",
            "/Alpha",
            "null",
        )) {
            rejected(bad)
        }
    }

    @Test fun `an unknown but well-formed dashboard is saveable because existence is a runtime fact`() {
        // The catalogue can be unfetched, the account may not see the dashboard yet, and a dashboard
        // may be created after setup. Both pickers warn about this; the API must not block it, or a
        // panel could not be configured ahead of the dashboard it is meant to show.
        assertEquals("/not-created-yet/alpha", accepted("/not-created-yet/alpha"))
    }

    @Test fun `a scheme inside a query or fragment is opaque state, not an absolute URL`() {
        // The write path used to send every value through a URL-to-local-path conversion that searched
        // for "://" ANYWHERE in the string. A legal route carrying a link in its own query therefore
        // matched inside itself and was silently rewritten to a different dashboard after a successful
        // save. Only a scheme at the START makes a value absolute; one inside query or fragment state
        // belongs to the dashboard being addressed, so it survives byte for byte.
        assertEquals(
            "/lovelace/view?url=https://example.invalid/x",
            accepted("/lovelace/view?url=https://example.invalid/x"),
        )
        assertEquals("/alpha/view#ref=ws://bridge", accepted("/alpha/view#ref=ws://bridge"))
    }

    @Test fun `the shapes a command path used to coerce are canonicalized or refused, never rewritten`() {
        // MQTT persisted through a weaker conversion than the API's, so these two shapes could be
        // retained in a form the renderer and Configure then disagreed about. One authority now decides:
        // a trailing slash is canonicalized away, and traversal is refused outright rather than flattened
        // into some other dashboard's route.
        assertEquals("/alpha", accepted("/alpha/"))
        rejected("/alpha/../evil")
    }

    @Test fun `the setting still lives on the Dashboard card and applies without a restart`() {
        assertEquals(SettingType.STRING, spec.type)
        assertEquals("Dashboard", spec.group)
        assertEquals("ha_dashboard", spec.picker)
        assertEquals(Scope.DEVICE, spec.scope)
        assertTrue("a dashboard change must not need a restart", spec.liveApply)
        assertEquals(2_048, spec.maxChars)
    }
}
