package io.panelassistant.android.http

import io.panelassistant.android.control.PowerRiskLevel
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.PowerSafetyAdvisoryAction
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.PowerSafetyPolicy
import io.panelassistant.android.control.PowerSafetyRepairResult
import io.panelassistant.android.util.InstallPresentation
import org.json.JSONArray
import org.json.JSONObject

internal object PowerSafetyPresentation {
    /** Typed metadata for the exact warning emitted by [statusWarningHtml]. */
    fun warningPresentation(advisory: PowerSafetyAdvisory): InstallPresentation? = when (advisory.assessment.level) {
        PowerRiskLevel.AT_RISK -> InstallPresentation("status-power-at-risk")
        PowerRiskLevel.CAUTION -> InstallPresentation("status-power-caution")
        PowerRiskLevel.UNKNOWN -> InstallPresentation("status-power-unknown")
        PowerRiskLevel.SAFE -> null
    }

    fun json(advisory: PowerSafetyAdvisory): String = jsonObject(advisory).toString()

    fun repairJson(result: PowerSafetyRepairResult, advisory: PowerSafetyAdvisory): String = JSONObject()
        .put("status", result.status)
        .put("complete", result.complete)
        .put("privileged_power_control", result.privilegedPowerControl)
        .put("message", repairMessage(result))
        .put("next_action", repairNextAction(result, advisory))
        .put(
            "steps",
            JSONObject()
                .put("keep_awake", result.keepAwake.wireValue)
                .put("prevent_idle_dim", result.preventIdleDim.wireValue)
                .put("stay_on_while_plugged_in", result.stayOnWhilePluggedIn.wireValue)
                .put("doze_exemption", result.dozeExemption.wireValue),
        )
        .put("power_safety", jsonObject(advisory))
        .toString()

    fun statusWarningHtml(advisory: PowerSafetyAdvisory): String? {
        val assessment = advisory.assessment
        if (!assessment.warning) return null
        val prefix = when (assessment.level) {
            PowerRiskLevel.AT_RISK -> "⛔ <b>Panel power safety: at risk</b>"
            PowerRiskLevel.CAUTION -> "⚠ <b>Panel power safety: caution</b>"
            PowerRiskLevel.UNKNOWN -> "⚠ <b>Panel power safety: unknown</b>"
            PowerRiskLevel.SAFE -> return null
        }
        return "$prefix — ${assessment.summary} ${actionText(advisory)}"
    }

    fun bannerHtml(advisory: PowerSafetyAdvisory, inlineRepair: Boolean): String {
        if (!advisory.bannerVisible) return ""
        val assessment = advisory.assessment
        val warning = statusWarningHtml(advisory) ?: return ""
        val action = when {
            !inlineRepair -> " <a href=\"configure#cfg-keep_awake\">Review on Configure →</a>"
            advisory.action == PowerSafetyAdvisoryAction.REPAIR ->
                """ <form method="post" action="api/v1/power-safety/repair" data-power-safety-repair style="display:inline">""" +
                    """<button class="pbtn" type="submit" data-hardened-approval """ +
                    """title="Repair is explicit, read-back verified, and never reboots the panel">Repair power safety</button>""" +
                    """ <span class="power-safety-repair-result" role="status" aria-live="polite"></span></form>"""
            advisory.action == PowerSafetyAdvisoryAction.ACKNOWLEDGE -> {
                val fingerprint = requireNotNull(advisory.acknowledgementFingerprint)
                """ <form method="post" action="api/v1/power-safety/acknowledge" data-power-safety-acknowledge style="display:inline">""" +
                    """<input type="hidden" name="fingerprint" value="$fingerprint">""" +
                    """<button class="pbtn" type="submit" data-hardened-approval title="Hide this unchanged caution in panel web pages; Hardened mode requires physical approval">Hide this caution</button>""" +
                    """ <span class="power-safety-acknowledge-result" role="status" aria-live="polite"></span></form>"""
            }
            else -> ""
        }
        val critical = assessment.level == PowerRiskLevel.AT_RISK
        return "<div class=\"setup${if (critical) " crit" else ""}\" data-power-safety-banner>$warning$action</div>"
    }

    fun diagnosticLine(assessment: PowerSafetyAssessment): String {
        val observation = assessment.observation
        fun value(value: Any?): String = value?.toString() ?: "unknown"
        return buildString {
            append("[power-safety] state=").append(assessment.level.wireValue)
            append(" keep_awake=").append(observation.keepAwakeConfigured)
            append(" wake_lock=").append(observation.wakeLockHeld)
            append(" wifi_lock_required=").append(observation.wifiLockRequired)
            append(" wifi_lock=").append(observation.wifiLockHeld)
            append(" prevent_idle_dim=").append(observation.preventIdleDimConfigured)
            append(" screen_timeout_ms=").append(value(observation.screenOffTimeoutMs))
            append(" interactive=").append(value(observation.interactive))
            append(" power_source=").append(powerSource(observation.pluggedMask))
            append(" plugged_mask=").append(value(observation.pluggedMask))
            append(" stay_on_mask=").append(value(observation.stayOnWhilePluggedIn))
            append(" stay_on_effective=").append(value(PowerSafetyPolicy.stayAwakeEffective(observation)))
            append(" device_idle=").append(value(observation.deviceIdleMode))
            append(" doze_exempt=").append(value(observation.ignoringBatteryOptimizations))
            // screen_off= keeps its established meaning (the profile's DECLARED route) for wire
            // compatibility; the two new fields are what the last actual off applied, and why.
            append(" screen_off=").append(observation.screenOffMechanism)
            append(" screen_off_selected=").append(value(observation.screenOffSelected))
            append(" screen_off_reason=\"").append(observation.screenOffReason).append('"')
            append(" reasons=").append(assessment.reasonCodes.ifEmpty { listOf("none") }.joinToString(","))
        }
    }

    private fun jsonObject(advisory: PowerSafetyAdvisory): JSONObject {
        val assessment = advisory.assessment
        val observation = assessment.observation
        return JSONObject()
            .put("state", assessment.level.wireValue)
            .put("warning", assessment.warning)
            .put("summary", assessment.summary)
            .put("action", actionText(advisory))
            .put("repair_capability", advisory.repairCapability.wireValue)
            .put("repair_available", advisory.repairActionable)
            .put("manual_only", advisory.action == PowerSafetyAdvisoryAction.MANUAL_ONLY || advisory.acknowledgeable)
            .put("acknowledge_available", advisory.acknowledgeable)
            .put("acknowledged", advisory.acknowledged)
            .put("acknowledgement_fingerprint", advisory.acknowledgementFingerprint ?: JSONObject.NULL)
            .put("reason_codes", JSONArray(assessment.reasonCodes))
            .put("keep_awake_configured", observation.keepAwakeConfigured)
            .put("wake_lock_held", observation.wakeLockHeld)
            .put("wifi_lock_required", observation.wifiLockRequired)
            .put("wifi_lock_held", observation.wifiLockHeld)
            .put("prevent_idle_dim_configured", observation.preventIdleDimConfigured)
            .put("screen_off_timeout_ms", observation.screenOffTimeoutMs ?: JSONObject.NULL)
            .put("interactive", observation.interactive ?: JSONObject.NULL)
            .put("power_source", powerSource(observation.pluggedMask))
            .put("plugged_mask", observation.pluggedMask ?: JSONObject.NULL)
            .put("stay_on_while_plugged_in", observation.stayOnWhilePluggedIn ?: JSONObject.NULL)
            .put("stay_on_effective", PowerSafetyPolicy.stayAwakeEffective(observation) ?: JSONObject.NULL)
            .put("device_idle", observation.deviceIdleMode ?: JSONObject.NULL)
            .put("doze_exempt", observation.ignoringBatteryOptimizations ?: JSONObject.NULL)
            .put("screen_off_mechanism", observation.screenOffMechanism)
            .put("screen_off_selected", observation.screenOffSelected ?: JSONObject.NULL)
            .put("screen_off_reason", observation.screenOffReason)
    }

    private fun actionText(advisory: PowerSafetyAdvisory): String = when (advisory.action) {
        PowerSafetyAdvisoryAction.NONE -> advisory.assessment.action
        PowerSafetyAdvisoryAction.REPAIR -> when (advisory.repairCapability.wireValue) {
            "direct_root" -> "Use Repair power safety to enable and verify the app and Android power guards. The repair never reboots the panel."
            "degraded" -> "Use Repair power safety to verify the app guards and retry this panel's currently degraded direct-root route. The repair never reboots the panel."
            else -> "Use Repair power safety to enable and verify the app CPU/Wi-Fi locks and infinite timeout. Android stay-awake and Doze changes are unavailable through this panel's current route."
        }
        PowerSafetyAdvisoryAction.ACKNOWLEDGE -> if (advisory.acknowledged) {
            "This unchanged caution is acknowledged in panel web pages; API, diagnostics, and installer checks continue to report it."
        } else {
            "The app CPU/Wi-Fi locks and infinite timeout are active, but Android stay-awake and Doze cannot be changed through this panel's current route. You may hide this exact caution; it returns if the evidence or capability changes."
        }
        PowerSafetyAdvisoryAction.MANUAL_ONLY ->
            "Automatic repair is unavailable through this panel's current route. Review the reported probes manually; this warning cannot be hidden while risk is unknown or elevated."
    }

    fun repairMessage(result: PowerSafetyRepairResult): String = when (result.status) {
        "repaired" -> "Power safety repaired and verified."
        "partial" -> buildString {
            append("Power safety repair was partial.")
            appendStepDetails(result)
            append(" No reboot was attempted.")
        }
        else -> buildString {
            append("Power safety repair failed.")
            appendStepDetails(result)
            append(" No reboot was attempted.")
        }
    }

    private fun StringBuilder.appendStepDetails(result: PowerSafetyRepairResult) {
        fun matching(status: String): List<String> = listOfNotNull(
            "app CPU/Wi-Fi locks".takeIf { result.keepAwake.wireValue == status },
            "infinite screen timeout".takeIf { result.preventIdleDim.wireValue == status },
            "Android stay-awake".takeIf { result.stayOnWhilePluggedIn.wireValue == status },
            "Doze exemption".takeIf { result.dozeExemption.wireValue == status },
        )
        val failed = matching("failed")
        if (failed.isNotEmpty()) append(" Failed: ${failed.joinToString(", ")}.")
        val unavailable = matching("unavailable")
        if (unavailable.isNotEmpty()) append(" Unavailable: ${unavailable.joinToString(", ")}.")
    }

    private fun repairNextAction(result: PowerSafetyRepairResult, advisory: PowerSafetyAdvisory): String = when {
        result.complete -> "none"
        advisory.acknowledgeable -> "acknowledge_caution"
        advisory.repairActionable -> "retry_repair"
        advisory.assessment.level == PowerRiskLevel.UNKNOWN -> "inspect_unknown"
        else -> "manual_configuration"
    }

    private fun powerSource(mask: Int?): String = when (mask) {
        null -> "unknown"
        0 -> "android_reports_unplugged"
        1 -> "ac"
        2 -> "usb"
        4 -> "wireless"
        8 -> "dock"
        else -> "multiple_or_oem_$mask"
    }
}
