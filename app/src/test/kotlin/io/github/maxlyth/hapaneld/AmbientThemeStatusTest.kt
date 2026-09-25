package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.AmbientThemeReason
import io.github.maxlyth.hapaneld.util.DashboardTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Under Ambient, `/api/v1/status` and the diagnostics line say which scheme the room resolves to and
 * why, and say so loudly when Ambient has fallen back to Home Assistant.
 */
class AmbientThemeStatusTest {

    private val url = "https://home-assistant.example.invalid:8123"

    private fun present(
        effectivePolicy: String,
        reason: AmbientThemeReason?,
        level: Double? = null,
        effectiveDark: Boolean? = null,
        storedPolicy: String = DashboardTheme.AMBIENT,
    ) = RendererAdmissionPresentation.of(
        mode = RendererMode.BUILTIN,
        haUrl = url,
        addressFamilyPolicy = "Automatic",
        live = RendererAdmissionRuntime.Live(
            owner = 7L,
            record = RendererAdmissionRuntime.Record(
                RendererAdmissionState.ADMITTED, null, false,
                io.github.maxlyth.hapaneld.util.HaTransportEvidence.NONE, 1_000L,
            ),
            frontendConnected = true,
            effectiveDark = effectiveDark,
        ),
        nowElapsedMs = 61_000L,
        processStartElapsedMs = 0L,
        packageUpdatedAtMs = 1_000_000L,
        nowWallMs = 1_100_000L,
        themePolicy = storedPolicy,
        themeEffectivePolicy = effectivePolicy,
        ambientReason = reason,
        ambientLevel = level,
    )

    private fun json(p: RendererAdmissionPresentation) = JSONObject(p.statusJson())

    @Test fun aDarkRoomReportsAmbientResolvedDarkWithItsReasonAndLevel() {
        val p = present(DashboardTheme.DARK, AmbientThemeReason.ROOM_DARK, level = 0.04321, effectiveDark = true)
        val j = json(p)
        assertEquals("ambient", j.getString("theme_policy"))
        assertEquals("dark", j.getString("theme_ambient"))
        assertEquals("room_dark", j.getString("theme_ambient_reason"))
        assertEquals(0.04, j.getDouble("theme_ambient_level"), 1e-9)
        assertFalse(j.getBoolean("theme_overridden"))
        assertEquals("", j.getString("action"))
        assertTrue(p.diagnosticLine(), p.diagnosticLine().contains("theme=ambient/dark ambient=dark ambient_reason=room_dark ambient_level=0.04"))
    }

    @Test fun anExplicitHomeAssistantThemeOverridingAmbientIsStillReported() {
        val p = present(DashboardTheme.DARK, AmbientThemeReason.ROOM_DARK, level = 0.0, effectiveDark = false)
        assertTrue(p.themeOverridden)
        assertEquals(RendererAdmissionPresentation.OVERRIDDEN_ACTION, p.action)
    }

    @Test fun aPanelWithoutALightSensorSaysAmbientIsFollowingHomeAssistant() {
        val p = present(DashboardTheme.FOLLOW, AmbientThemeReason.NO_LIGHT_SOURCE)
        val j = json(p)
        assertEquals("ambient", j.getString("theme_policy"))
        assertTrue(j.isNull("theme_ambient"))
        assertEquals("no_light_source", j.getString("theme_ambient_reason"))
        assertTrue(j.isNull("theme_ambient_level"))
        assertTrue(j.getString("summary").endsWith(RendererAdmissionPresentation.AMBIENT_NO_LIGHT_SOURCE_SUFFIX))
        assertEquals(RendererAdmissionPresentation.AMBIENT_NO_LIGHT_SOURCE_ACTION, j.getString("action"))
        assertTrue(p.statusText()!!.contains("no light sensor"))
        assertTrue(p.diagnosticLine().contains("ambient=follow ambient_reason=no_light_source ambient_level=none"))
    }

    @Test fun autoBrightnessOffSaysSoAndNamesTheFix() {
        val p = present(DashboardTheme.FOLLOW, AmbientThemeReason.AUTO_BRIGHTNESS_OFF, level = 0.5)
        assertTrue(p.summary.endsWith(RendererAdmissionPresentation.AMBIENT_AUTO_BRIGHTNESS_OFF_SUFFIX))
        assertEquals(RendererAdmissionPresentation.AMBIENT_AUTO_BRIGHTNESS_OFF_ACTION, p.action)
    }

    @Test fun waitingForTheFirstVerdictIsNamedButAsksForNothing() {
        val p = present(DashboardTheme.FOLLOW, AmbientThemeReason.WAITING, level = 0.2)
        assertTrue(p.summary.endsWith(RendererAdmissionPresentation.AMBIENT_WAITING_SUFFIX))
        assertEquals("", p.action)
    }

    @Test fun holdingTheLastVerdictKeepsReportingIt() {
        val p = present(DashboardTheme.LIGHT, AmbientThemeReason.HOLDING, level = 0.7)
        assertEquals("light", p.themeAmbient)
        assertEquals("holding_source_unavailable", p.themeAmbientReason)
        assertFalse(p.summary.contains("Ambient"))
    }

    @Test fun theExistingPoliciesCarryNoAmbientFieldsEvenWhenGivenAReason() {
        for (policy in listOf(DashboardTheme.FOLLOW, DashboardTheme.DARK, DashboardTheme.LIGHT)) {
            val p = present(policy, AmbientThemeReason.NO_LIGHT_SOURCE, level = 0.1, storedPolicy = policy)
            val j = json(p)
            assertTrue(policy, j.isNull("theme_ambient"))
            assertTrue(policy, j.isNull("theme_ambient_reason"))
            assertTrue(policy, j.isNull("theme_ambient_level"))
            assertFalse(policy, j.getString("summary").contains("Ambient"))
            assertFalse(policy, p.diagnosticLine().contains("ambient"))
        }
    }

    @Test fun everyStatusFieldIsDeclaredRequiredInTheOpenApi() {
        val openApi = listOf("src/main/assets/openapi.json", "app/src/main/assets/openapi.json")
            .map { java.io.File(it) }.first { it.isFile }.readText()
        val keys = json(present(DashboardTheme.DARK, AmbientThemeReason.ROOM_DARK)).keys().asSequence().toList()
        val required = Regex("\"required\": \\[(\"mode\", \"state\"[^\\]]*)]").find(openApi)!!.groupValues[1]
        keys.forEach { assertTrue("$it must be required in openapi.json", required.contains("\"$it\"")) }
        for (reason in AmbientThemeReason.values()) assertTrue(reason.wire, openApi.contains("\"${reason.wire}\""))
    }
}
