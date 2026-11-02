package io.panelassistant.android

import io.panelassistant.android.testsupport.Node
import io.panelassistant.android.util.DashboardTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/v1/status` must say when an explicit Home Assistant theme is overriding the panel's
 * `dashboard_theme`. The precedence itself is the documented boundary and is allowed; a panel that
 * looks healthy while quietly not doing what it was told is the defect this covers.
 *
 * Every case is the pure projection, so the whole matrix runs without a WebView or a clock.
 */
class RendererThemeOverrideStatusTest {

    private val url = "https://home-assistant.example.invalid:8123"

    private fun live(connected: Boolean, effectiveDark: Boolean?, admitted: Boolean = true) =
        RendererAdmissionRuntime.Live(
            owner = 7L,
            record = if (admitted) {
                RendererAdmissionRuntime.Record(
                    RendererAdmissionState.ADMITTED, null, false,
                    io.panelassistant.android.util.HaTransportEvidence.NONE, 1_000L,
                )
            } else null,
            frontendConnected = connected,
            effectiveDark = effectiveDark,
        )

    private fun present(
        policy: String,
        connected: Boolean = true,
        effectiveDark: Boolean?,
        mode: RendererMode = RendererMode.BUILTIN,
    ) = RendererAdmissionPresentation.of(
        mode = mode,
        haUrl = url,
        addressFamilyPolicy = "Automatic",
        live = live(connected, effectiveDark),
        nowElapsedMs = 61_000L,
        processStartElapsedMs = 0L,
        packageUpdatedAtMs = 1_000_000L,
        nowWallMs = 1_100_000L,
        themePolicy = policy,
    )

    private fun json(p: RendererAdmissionPresentation) = JSONObject(p.statusJson())

    // --- the case this exists for -------------------------------------------------------------

    @Test fun darkPolicyRenderedLightIsReportedAsOverridden() {
        val p = present(DashboardTheme.DARK, effectiveDark = false)
        assertTrue(p.themeOverridden)
        val j = json(p)
        assertEquals("dark", j.getString("theme_policy"))
        assertEquals("light", j.getString("theme_effective"))
        assertTrue(j.getBoolean("theme_overridden"))
        assertTrue(j.getString("summary"), j.getString("summary").endsWith(RendererAdmissionPresentation.OVERRIDDEN_SUMMARY_SUFFIX))
        assertEquals(RendererAdmissionPresentation.OVERRIDDEN_ACTION, j.getString("action"))
        // The rendered state is untouched: an override is not a fault.
        assertEquals("rendered", j.getString("state"))
        assertTrue(p.statusText()!!.contains("overriding this panel's Dashboard theme"))
        assertTrue(p.diagnosticLine().contains("theme=dark/light theme_overridden=true"))
    }

    @Test fun lightPolicyRenderedDarkIsReportedAsOverridden() {
        val p = present(DashboardTheme.LIGHT, effectiveDark = true)
        assertTrue(p.themeOverridden)
        assertEquals("light", p.themePolicy)
        assertEquals("dark", p.themeEffective)
    }

    // --- every way it must NOT fire -------------------------------------------------------------

    @Test fun aPolicyThatIsHonouredIsNotAnOverride() {
        val p = present(DashboardTheme.DARK, effectiveDark = true)
        assertFalse(p.themeOverridden)
        val j = json(p)
        assertEquals("dark", j.getString("theme_effective"))
        assertFalse(j.getString("summary").contains("overriding"))
        assertEquals("", j.getString("action"))
        assertFalse(p.statusText()!!.contains("overriding"))
    }

    @Test fun followNeverReportsAnOverrideWhateverIsOnScreen() {
        // Follow means the panel does not decide, so there is nothing for Home Assistant to override.
        for (effective in listOf(true, false, null)) {
            val p = present(DashboardTheme.FOLLOW, effectiveDark = effective)
            assertFalse("effective=$effective", p.themeOverridden)
            assertEquals("follow", p.themePolicy)
        }
    }

    @Test fun anUnobservedSchemeIsNeitherAnOverrideNorAClaim() {
        val p = present(DashboardTheme.DARK, effectiveDark = null)
        assertFalse(p.themeOverridden)
        assertNull(p.themeEffective)
        assertTrue(json(p).isNull("theme_effective"))
        assertEquals("", json(p).getString("action"))
    }

    @Test fun aDisconnectedPageCannotBeOverridingAnything() {
        // The runtime clears the observation on disconnect; the projection must not trust a stale
        // one either, or a page that died light would keep the panel reporting an override.
        val p = present(DashboardTheme.DARK, connected = false, effectiveDark = false)
        assertFalse(p.themeOverridden)
        assertNull(p.themeEffective)
    }

    @Test fun anExternalRendererReportsThePolicyButObservesNothing() {
        // Always-present convention: the consumer never infers applicability from an absent field.
        for (mode in listOf(RendererMode.EXTERNAL, RendererMode.NONE)) {
            val p = present(DashboardTheme.DARK, effectiveDark = false, mode = mode)
            val j = json(p)
            assertEquals("dark", j.getString("theme_policy"))
            assertTrue(j.isNull("theme_effective"))
            assertFalse(j.getBoolean("theme_overridden"))
            assertFalse(j.getString("summary").contains("overriding"))
        }
    }

    @Test fun aBlockedStateKeepsItsOwnActionAheadOfTheOverrideHint() {
        // A block is the bigger problem; its action must not be displaced by theme advice.
        val blocked = RendererAdmissionPresentation.of(
            mode = RendererMode.BUILTIN, haUrl = url, addressFamilyPolicy = "Automatic",
            live = RendererAdmissionRuntime.Live(
                owner = 7L,
                record = RendererAdmissionRuntime.Record(
                    RendererAdmissionState.BLOCKED, AdmissionOutcome.NO_LEGAL_DASHBOARD, false,
                    io.panelassistant.android.util.HaTransportEvidence.NONE, 1_000L,
                ),
                frontendConnected = true, effectiveDark = false,
            ),
            nowElapsedMs = 61_000L, processStartElapsedMs = 0L, packageUpdatedAtMs = 1_000_000L, nowWallMs = 1_100_000L,
            themePolicy = DashboardTheme.DARK,
        )
        assertTrue(blocked.action.isNotBlank())
        assertFalse(blocked.action == RendererAdmissionPresentation.OVERRIDDEN_ACTION)
    }

    @Test fun anOlderCallerThatPassesNoPolicyReportsFollow() {
        val p = RendererAdmissionPresentation.of(
            mode = RendererMode.BUILTIN, haUrl = url, addressFamilyPolicy = "Automatic",
            live = live(connected = true, effectiveDark = false),
            nowElapsedMs = 61_000L, processStartElapsedMs = 0L, packageUpdatedAtMs = 1_000_000L, nowWallMs = 1_100_000L,
        )
        assertEquals("follow", p.themePolicy)
        assertFalse(p.themeOverridden)
    }

    // --- the observation parser -------------------------------------------------------------------

    @Test fun theObservationParserReadsBothFieldsAndTreatsGarbageAsInvalidNotAsAbsence() {
        // evaluateJavascript returns the string value as a JSON literal, i.e. quoted and escaped.
        val both = ExternalAuthProtocol.parseThemeObservation("\"{\\\"s\\\":true,\\\"e\\\":false}\"")
        assertEquals(true, both.storedDark)
        assertEquals(false, both.effectiveDark)
        assertTrue(both.valid)

        val absent = ExternalAuthProtocol.parseThemeObservation("\"{\\\"s\\\":null,\\\"e\\\":null}\"")
        assertNull(absent.storedDark)
        assertNull(absent.effectiveDark)
        assertTrue("an explicit absence is a VALID observation", absent.valid)

        // An unreadable result must not erase the stored observation while a page tears down.
        for (garbage in listOf(null, "null", "undefined", "\"not json\"")) {
            assertFalse("$garbage must be invalid", ExternalAuthProtocol.parseThemeObservation(garbage).valid)
        }
        // A readable object with a nonboolean field carries no scheme, rather than coercing text to true.
        val wrongType = ExternalAuthProtocol.parseThemeObservation("\"{\\\"s\\\":\\\"true\\\"}\"")
        assertTrue(wrongType.valid)
        assertNull(wrongType.storedDark)
        assertNull(wrongType.effectiveDark)
    }

    @Test fun theObservationScriptReadsStoredAndEffectiveSchemesIndependently() {
        Node.assumeAvailable()
        val script = JSONObject.quote(ExternalAuthProtocol.THEME_OBSERVATION_JS)
        val harness = """
            const vm = require('vm');
            const cases = [
              {stored:'{"dark":true,"theme":"named"}', effective:false},
              {stored:'{"dark":false}', effective:true},
              {stored:null, effective:null},
              {stored:'not json', effective:true},
              {stored:'{"dark":"true"}', effective:'false'},
              {stored:'{"dark":false}', effective:null, noFrontend:true},
              {stored:null, effective:false, storageThrows:true},
              {stored:'{"dark":true}', effective:null, documentThrows:true}
            ];
            const observations = cases.map(c => {
              const page = {
                localStorage: {getItem: key => {
                  if (c.storageThrows) throw new Error('storage unavailable');
                  return key === 'selectedTheme' ? c.stored : null;
                }},
                document: {querySelector: selector => {
                  if (c.documentThrows) throw new Error('page unavailable');
                  return selector === 'home-assistant' && !c.noFrontend ? {hass:{themes:{darkMode:c.effective}}} : null;
                }}
              };
              return JSON.parse(vm.runInNewContext($script, page));
            });
            process.stdout.write(JSON.stringify(observations));
        """.trimIndent()
        val (code, output) = Node.run(stdin = harness)
        assertEquals("observation script failed: $output", 0, code)
        val observations = org.json.JSONArray(output)
        val expected = listOf(
            true to false, false to true, null to null, null to true,
            null to null, false to null, null to false, true to null,
        )
        assertEquals(expected.size, observations.length())
        expected.forEachIndexed { index, (stored, effective) ->
            val actual = observations.getJSONObject(index)
            assertEquals("case $index has only the two observation fields", setOf("s", "e"), actual.keys().asSequence().toSet())
            assertEquals("case $index stored scheme", stored, actual.opt("s").takeUnless { it == JSONObject.NULL })
            assertEquals("case $index effective scheme", effective, actual.opt("e").takeUnless { it == JSONObject.NULL })
        }
    }
}
