package io.panelassistant.android

import io.panelassistant.android.testsupport.Node
import io.panelassistant.android.testsupport.PageScript
import org.json.JSONArray
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

    /** The arguments the frontend's callback receives when the page that asked evaluates [script]. */
    private fun delivered(script: String, callback: String): JSONArray {
        Node.assumeAvailable()
        val outcome = PageScript.run(script, callback)
        assertNull(outcome.error)
        val call = outcome.calls.single()
        assertEquals(callback, call.function)
        return call.args
    }

    // --- getExternalAuth ---------------------------------------------------------------------------

    @Test
    fun `auth reply carries the token and its real expiry`() {
        val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", "tok123", 1800L)!!
        val args = delivered(js, "externalAuthSetToken")
        assertEquals(true, args.getBoolean(0))
        val json = args.getJSONObject(1)
        assertEquals("tok123", json.getString("access_token"))
        assertEquals(1800L, json.getLong("expires_in"))
    }

    @Test
    fun `auth reply fails closed with no token`() {
        for (token in listOf("", null)) {
            val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken","force":true}""", token, 0L)!!
            val args = delivered(js, "externalAuthSetToken")
            assertEquals(1, args.length())
            assertEquals(false, args.getBoolean(0))
        }
    }

    @Test
    fun `a reply that reaches a page which never asked is dropped, not thrown`() {
        // A reload can hand the old page's reply to its replacement, which has not defined the
        // callback yet and asks for its own token once it boots.
        Node.assumeAvailable()
        val replies = listOf(
            ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", "tok", 1800L)!!,
            ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", null, 0L)!!,
            ExternalAuthProtocol.revokeReply("""{"callback":"externalAuthRevokeToken"}""")!!,
        )
        for (js in replies) assertEquals(js, PageScript.Outcome(emptyList(), null), PageScript.run(js))
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
        val json = delivered(js, "externalAuthSetToken").getJSONObject(1)
        assertEquals("""t"),alert("x""", json.getString("access_token"))
    }

    // --- revokeExternalAuth ------------------------------------------------------------------------

    @Test
    fun `revoke acks only the expected callback`() {
        val js = ExternalAuthProtocol.revokeReply("""{"callback":"externalAuthRevokeToken"}""")!!
        assertEquals(true, delivered(js, "externalAuthRevokeToken").getBoolean(0))
        assertNull(ExternalAuthProtocol.revokeReply("""{"callback":"externalAuthSetToken"}"""))
    }

    @Test
    fun `panel defaults seed forces the panel-appropriate prefs behind a sentinel`() {
        Node.assumeAvailable()
        val script = JSONObject.quote(ExternalAuthProtocol.panelDefaultsJs())
        val result = Node.run(stdin = """
            const vm = require('vm');
            function run(initial = {}, child = false, blocked = false, repeat = false) {
                const store = new Map(Object.entries(initial));
                const page = {};
                page.window = page;
                page.top = child ? {} : page;
                page.localStorage = {
                    getItem: key => { if (blocked) throw new Error('denied'); return store.get(key) ?? null; },
                    setItem: (key, value) => store.set(key, String(value))
                };
                let error = null;
                try {
                    vm.runInNewContext($script, page);
                    if (repeat) {
                        store.set('dockedSidebar', JSON.stringify('auto'));
                        store.set('suspendWhenHidden', JSON.stringify(true));
                        store.set('vibrate', JSON.stringify(true));
                        vm.runInNewContext($script, page);
                    }
                } catch (caught) { error = String(caught); }
                return {store: Object.fromEntries(store), error};
            }
            process.stdout.write(JSON.stringify({
                fresh: run({unrelated: 'keep'}), repeat: run({}, false, false, true),
                child: run({}, true), blocked: run({}, false, true)
            }));
        """.trimIndent())
        assertEquals(result.output, 0, result.exitCode)
        val outcomes = JSONObject(result.output)
        for (name in listOf("fresh", "repeat", "child", "blocked")) {
            assertTrue("$name must not abort the page", outcomes.getJSONObject(name).isNull("error"))
        }
        val fresh = outcomes.getJSONObject("fresh").getJSONObject("store")
        assertEquals("\"always_hidden\"", fresh.getString("dockedSidebar"))
        assertEquals("false", fresh.getString("suspendWhenHidden"))
        assertEquals("false", fresh.getString("vibrate"))
        assertEquals("1", fresh.getString("__hapaneld_panel_defaults"))
        assertEquals("keep", fresh.getString("unrelated"))
        val repeat = outcomes.getJSONObject("repeat").getJSONObject("store")
        assertEquals("\"auto\"", repeat.getString("dockedSidebar"))
        assertEquals("true", repeat.getString("suspendWhenHidden"))
        assertEquals("true", repeat.getString("vibrate"))
        assertEquals(0, outcomes.getJSONObject("child").getJSONObject("store").length())
        assertEquals(0, outcomes.getJSONObject("blocked").getJSONObject("store").length())
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

    // The selectedThemeJs seed and toggle storage behavior is exercised by
    // dashboard.DashboardThemePolicyScriptTest against the emitted scripts.
}
