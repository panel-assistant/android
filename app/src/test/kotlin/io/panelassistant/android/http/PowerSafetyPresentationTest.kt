package io.panelassistant.android.http

import io.panelassistant.android.control.PowerRepairCapability
import io.panelassistant.android.control.PowerRepairStepStatus
import io.panelassistant.android.control.PowerRiskLevel
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.PowerSafetyAdvisoryPolicy
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.PowerSafetyObservation
import io.panelassistant.android.control.PowerSafetyRepairResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSafetyPresentationTest {
    @Test fun structuredJsonAndDiagnosticsRetainBoundedProbeTruth() {
        val assessment = assessment(PowerRiskLevel.UNKNOWN)
        val json = JSONObject(PowerSafetyPresentation.json(advisory(assessment)))

        assertEquals("unknown", json.getString("state"))
        assertTrue(json.getBoolean("warning"))
        assertFalse(json.getBoolean("acknowledge_available"))
        assertTrue(json.isNull("acknowledgement_fingerprint"))
        assertTrue(json.isNull("plugged_mask"))
        assertEquals("unknown", json.getString("power_source"))
        assertTrue(json.isNull("stay_on_effective"))
        assertEquals("doze_exemption_unknown", json.getJSONArray("reason_codes").getString(0))

        val diagnostic = PowerSafetyPresentation.diagnosticLine(assessment)
        assertTrue(diagnostic.startsWith("[power-safety] state=unknown"))
        assertTrue(diagnostic.contains("power_source=unknown"))
        assertTrue(diagnostic.contains("doze_exempt=unknown"))
        assertFalse(diagnostic.contains("battery"))
    }

    /** Issue #138: declared and selected/reason are distinct wire fields, not one overloaded key —
     *  screen_off= keeps its established declared-only meaning. */
    @Test fun declaredAndSelectedScreenOffRouteAreDistinctWireFields() {
        val assessment = assessment(PowerRiskLevel.SAFE).let {
            it.copy(
                observation = it.observation.copy(
                    screenOffMechanism = "brightness_zero",
                    screenOffSelected = "su_blpower",
                    screenOffReason = "the declared su bl_power route was unavailable; fell back to the helper daemon",
                ),
            )
        }
        val json = JSONObject(PowerSafetyPresentation.json(advisory(assessment)))
        assertEquals("brightness_zero", json.getString("screen_off_mechanism"))
        assertTrue("screen_off_selected must be present on the JSON payload", json.has("screen_off_selected"))
        assertEquals("su_blpower", json.optString("screen_off_selected"))
        assertTrue("screen_off_reason must be present on the JSON payload", json.has("screen_off_reason"))
        assertTrue(json.optString("screen_off_reason").contains("fell back"))

        val diagnostic = PowerSafetyPresentation.diagnosticLine(assessment)
        assertTrue(diagnostic.contains("screen_off=brightness_zero"))
        assertTrue(diagnostic.contains("screen_off_selected=su_blpower"))
        assertTrue(diagnostic.contains("screen_off_reason=\"the declared su bl_power route was unavailable"))
    }

    @Test fun unexercisedScreenOffSelectionReportsNullNotAGuess() {
        val json = JSONObject(PowerSafetyPresentation.json(advisory(assessment(PowerRiskLevel.SAFE))))
        assertTrue(json.isNull("screen_off_selected"))

        val diagnostic = PowerSafetyPresentation.diagnosticLine(assessment(PowerRiskLevel.SAFE))
        assertTrue(diagnostic.contains("screen_off_selected=unknown"))
    }

    @Test fun repairableWarningsUseOneSharedSummaryAndOfferOnlyAnExplicitPostRepair() {
        val assessment = assessment(PowerRiskLevel.AT_RISK)
        val advisory = advisory(assessment, PowerRepairCapability.DIRECT_ROOT)
        val warning = PowerSafetyPresentation.statusWarningHtml(advisory).orEmpty()
        val banner = PowerSafetyPresentation.bannerHtml(advisory, inlineRepair = true)

        assertTrue(warning.contains(assessment.summary))
        assertTrue(banner.contains("method=\"post\""))
        assertTrue(banner.contains("action=\"api/v1/power-safety/repair\""))
        assertTrue(banner.contains("data-power-safety-repair"))
        assertTrue(banner.contains("Repair power safety"))
        assertTrue(banner.contains("never reboots"))
        assertFalse(banner.contains("onclick="))
        assertEquals("", PowerSafetyPresentation.bannerHtml(advisory(assessment(PowerRiskLevel.SAFE)), true))
    }

    @Test fun healthyAppOnlyCautionCanBeHiddenWithoutChangingStructuredRiskTruth() {
        val assessment = assessment(PowerRiskLevel.CAUTION)
        val offered = advisory(assessment)
        val banner = PowerSafetyPresentation.bannerHtml(offered, inlineRepair = true)
        val json = JSONObject(PowerSafetyPresentation.json(offered))

        assertTrue(banner.contains("action=\"api/v1/power-safety/acknowledge\""))
        assertTrue(banner.contains("data-power-safety-acknowledge"))
        assertTrue(banner.contains("Hide this caution"))
        assertTrue(banner.contains("data-hardened-approval"))
        assertFalse(banner.contains("Repair power safety"))
        assertEquals("caution", json.getString("state"))
        assertTrue(json.getBoolean("warning"))
        assertTrue(json.getBoolean("manual_only"))
        assertTrue(json.getBoolean("acknowledge_available"))
        assertEquals(64, json.getString("acknowledgement_fingerprint").length)

        val hidden = PowerSafetyAdvisoryPolicy.evaluate(
            assessment,
            PowerRepairCapability.APP_ONLY,
            offered.acknowledgementFingerprint,
        )
        assertEquals("", PowerSafetyPresentation.bannerHtml(hidden, inlineRepair = true))
        assertTrue(PowerSafetyPresentation.statusWarningHtml(hidden).orEmpty().contains("caution"))
        assertTrue(JSONObject(PowerSafetyPresentation.json(hidden)).getBoolean("warning"))
        assertTrue(JSONObject(PowerSafetyPresentation.json(hidden)).getBoolean("acknowledged"))
    }

    @Test fun repairResultReportsEveryCapabilityAwareStepAndNextAction() {
        val result = PowerSafetyRepairResult(
            status = "partial",
            keepAwake = PowerRepairStepStatus.APPLIED,
            preventIdleDim = PowerRepairStepStatus.FAILED,
            stayOnWhilePluggedIn = PowerRepairStepStatus.UNAVAILABLE,
            dozeExemption = PowerRepairStepStatus.UNAVAILABLE,
            privilegedPowerControl = "app_only",
            assessment = assessment(PowerRiskLevel.CAUTION),
        )
        val json = JSONObject(PowerSafetyPresentation.repairJson(result, advisory(result.assessment)))
        val steps = json.getJSONObject("steps")

        assertEquals("partial", json.getString("status"))
        assertFalse(json.getBoolean("complete"))
        assertEquals("app_only", json.getString("privileged_power_control"))
        assertEquals("acknowledge_caution", json.getString("next_action"))
        assertTrue(json.getString("message").contains("Failed: infinite screen timeout"))
        assertTrue(json.getString("message").contains("Unavailable: Android stay-awake, Doze exemption"))
        assertEquals("applied", steps.getString("keep_awake"))
        assertEquals("failed", steps.getString("prevent_idle_dim"))
        assertEquals("unavailable", steps.getString("stay_on_while_plugged_in"))
        assertEquals("unavailable", steps.getString("doze_exemption"))
    }

    private fun advisory(
        assessment: PowerSafetyAssessment,
        capability: PowerRepairCapability = PowerRepairCapability.APP_ONLY,
        acknowledgedFingerprint: String? = null,
    ): PowerSafetyAdvisory = PowerSafetyAdvisoryPolicy.evaluate(
        assessment,
        capability,
        acknowledgedFingerprint,
    )

    private fun assessment(level: PowerRiskLevel): PowerSafetyAssessment = PowerSafetyAssessment(
        level = level,
        observation = PowerSafetyObservation(
            keepAwakeConfigured = true,
            wakeLockHeld = true,
            wifiLockRequired = true,
            wifiLockHeld = true,
            preventIdleDimConfigured = true,
            screenOffTimeoutMs = Int.MAX_VALUE,
            interactive = true,
            pluggedMask = null,
            stayOnWhilePluggedIn = 3,
            deviceIdleMode = false,
            ignoringBatteryOptimizations = null,
            screenOffMechanism = "sysfs",
        ),
        reasonCodes = listOf("doze_exemption_unknown"),
        summary = "bounded summary",
        action = "bounded action",
    )
}
