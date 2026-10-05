package io.panelassistant.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the built-in renderer's side of the HA frontend external-auth contract: the frontend loads
 * `?external_auth=1`, posts a V2 `getExternalAuth` envelope with callback `externalAuthSetToken`,
 * and expects `externalAuthSetToken(true, {"access_token":…,"expires_in":<seconds>})` evaluated back.
 * `config/get` on the external bus is the one message the frontend blocks on during startup.
 */
class ExternalAuthProtocolTest {

    // --- getExternalAuth ---------------------------------------------------------------------------

    @Test
    fun `auth reply carries the token and its real expiry`() {
        val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", "tok123", 1800L)!!
        assertTrue(js.startsWith("externalAuthSetToken(true, "))
        val json = JSONObject(js.removePrefix("externalAuthSetToken(true, ").removeSuffix(")"))
        assertEquals("tok123", json.getString("access_token"))
        assertEquals(1800L, json.getLong("expires_in"))
    }

    @Test
    fun `auth reply fails closed with no token`() {
        assertEquals(
            "externalAuthSetToken(false)",
            ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken","force":true}""", "", 0L),
        )
        assertEquals(
            "externalAuthSetToken(false)",
            ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", null, 0L),
        )
    }

    @Test
    fun `unexpected callback name is never evaluated`() {
        // The callback name becomes a JS function call — anything but the frontend's fixed constant
        // must be dropped, or a hostile page could execute an arbitrary function with our token.
        assertNull(ExternalAuthProtocol.authReply("""{"callback":"alert"}""", "tok", 1800L))
        assertNull(ExternalAuthProtocol.authReply("not json", "tok", 1800L))
        assertNull(ExternalAuthProtocol.authReply("{}", "tok", 1800L))
        assertNull(ExternalAuthProtocol.validAuthRequestForce("""{"callback":"alert","force":true}"""))
        assertNull(ExternalAuthProtocol.validAuthRequestForce("x".repeat(4 * 1024 + 1)))
    }

    @Test fun `valid auth request is admitted before token work`() {
        assertEquals(false, ExternalAuthProtocol.validAuthRequestForce("""{"callback":"externalAuthSetToken"}"""))
        assertEquals(true, ExternalAuthProtocol.validAuthRequestForce("""{"callback":"externalAuthSetToken","force":true}"""))
    }

    @Test
    fun `token with quotes is JSON-escaped not injected`() {
        val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", """t"),alert("x""", 1800L)!!
        val json = JSONObject(js.removePrefix("externalAuthSetToken(true, ").removeSuffix(")"))
        assertEquals("""t"),alert("x""", json.getString("access_token"))
    }

    // --- revokeExternalAuth ------------------------------------------------------------------------

    @Test
    fun `revoke acks only the expected callback`() {
        assertEquals("externalAuthRevokeToken(true)", ExternalAuthProtocol.revokeReply("""{"callback":"externalAuthRevokeToken"}"""))
        assertNull(ExternalAuthProtocol.revokeReply("""{"callback":"externalAuthSetToken"}"""))
    }

    @Test
    fun `panel defaults seed forces the panel-appropriate prefs behind a sentinel`() {
        val js = ExternalAuthProtocol.panelDefaultsJs()
        assertTrue("only the top HA document should receive panel defaults", js.contains("if(window.top&&window.top!==window)return"))
        assertTrue("self-gated so it runs once", js.contains("__hapaneld_panel_defaults"))
        assertTrue("hide the sidebar", js.contains("dockedSidebar") && js.contains("always_hidden"))
        assertTrue("keep the background connection", js.contains("suspendWhenHidden"))
        assertTrue("no haptics", js.contains("vibrate"))
        assertTrue("values JSON-stringified to match ha-pref-storage", js.contains("JSON.stringify"))
    }

    // --- dashboardUrl -----------------------------------------------------------------------------

    @Test
    fun `dashboard url appends path and external_auth`() {
        assertEquals("https://ha/my-panel/dash?external_auth=1",
            ExternalAuthProtocol.dashboardUrl("https://ha", "my-panel/dash"))
        // trailing/leading slashes on either side are normalised, not doubled
        assertEquals("https://ha/lovelace/0?external_auth=1",
            ExternalAuthProtocol.dashboardUrl("https://ha/", "/lovelace/0/"))
        // blank path → the HA root
        assertEquals("https://ha/?external_auth=1", ExternalAuthProtocol.dashboardUrl("https://ha", ""))
    }

    @Test
    fun `dashboard url inserts external auth before fragments`() {
        assertEquals(
            "https://ha/lovelace/0?external_auth=1#kitchen",
            ExternalAuthProtocol.dashboardUrl("https://ha", "/lovelace/0/#kitchen"),
        )
        assertEquals(
            "https://ha/?external_auth=1#kitchen",
            ExternalAuthProtocol.dashboardUrl("https://ha", "#kitchen"),
        )
    }

    @Test
    fun `dashboard url preserves existing query and fragment semantics`() {
        assertEquals(
            "https://ha/lovelace/0?theme=dark&return=/&external_auth=1#kitchen?tab=lights",
            ExternalAuthProtocol.dashboardUrl(
                "https://ha/",
                "/lovelace/0/?theme=dark&return=/#kitchen?tab=lights",
            ),
        )
        assertEquals(
            "https://ha/lovelace/0?external_auth=1#kitchen/",
            ExternalAuthProtocol.dashboardUrl("https://ha", "/lovelace/0#kitchen/"),
        )
    }

    // --- selectedThemeJs: the HA per-device theme store is the ONLY lever that re-renders HA ---

    @Test fun themeSeedOnlyWritesWhenAbsent() {
        val js = ExternalAuthProtocol.selectedThemeJs(dark = true, onlyIfAbsent = true)
        assertTrue("only the top HA document should receive the theme seed", js.contains("if(window.top&&window.top!==window)return"))
        // Guarded on the RAW stored string, not on its parse: an entry ha-paneld cannot read still
        // belongs to the user, and overwriting it would be stomping rather than seeding.
        assertTrue("guarded on absence — must not stomp a user-picked HA theme", js.contains("if(r)return;"))
        assertTrue("the guard must test the value the script just read", js.indexOf("getItem") < js.indexOf("if(r)return;"))
        assertTrue("only the dark field is ha-paneld's to write", js.contains("o.dark=true;"))
        assertTrue("localStorage can throw on data: URLs — must be try/caught", js.contains("try{"))
    }

    @Test fun themeToggleOverridesTheDarkFieldWithoutDiscardingTheRest() {
        val js = ExternalAuthProtocol.selectedThemeJs(dark = false, onlyIfAbsent = false)
        assertTrue("a deliberate toggle overrides like HA's own radio, rather than seeding", !js.contains("if(r)return;"))
        // HA's radio spreads the existing ThemeSettings and sets one field. Stringifying a fresh
        // {dark:false} instead would silently discard the user's named theme and custom colours —
        // invisibly, since the frontend falls back to default_theme when `theme` is absent.
        assertTrue("the toggle must read the stored object before writing", js.contains("JSON.parse(r)"))
        assertTrue("only the dark field is written", js.contains("o.dark=false;localStorage.setItem"))
        assertTrue("the read object is what gets re-stringified", js.contains("JSON.stringify(o)"))
        assertTrue("a freshly built theme object would drop theme/primaryColor/accentColor", !js.contains("{dark:"))
    }
}
