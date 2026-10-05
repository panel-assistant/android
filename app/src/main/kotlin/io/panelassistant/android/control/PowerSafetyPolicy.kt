package io.panelassistant.android.control

import java.security.MessageDigest

/** Android power observations that are safe to expose in diagnostics and HTTP responses. */
data class PowerSafetyObservation(
    val keepAwakeConfigured: Boolean,
    val wakeLockHeld: Boolean,
    val wifiLockRequired: Boolean,
    val wifiLockHeld: Boolean,
    val preventIdleDimConfigured: Boolean,
    val screenOffTimeoutMs: Int?,
    val interactive: Boolean?,
    /** Android BatteryManager plugged bit mask; zero means Android reports no external power. */
    val pluggedMask: Int?,
    /** Android's global stay_on_while_plugged_in bit mask. */
    val stayOnWhilePluggedIn: Int?,
    val deviceIdleMode: Boolean?,
    val ignoringBatteryOptimizations: Boolean?,
    /** The profile's declared screen-off route (an intent, not an observation of what ran). */
    val screenOffMechanism: String,
    /** What the last actual screen-off applied, or null if none has run since this profile activated.
     *  See [ScreenController.routeSelection]: this is read, never probed, so it costs nothing here. */
    val screenOffSelected: String? = null,
    /** Why [screenOffSelected] matches or differs from [screenOffMechanism]. */
    val screenOffReason: String = ScreenController.REASON_UNEXERCISED,
)

enum class PowerRiskLevel(val wireValue: String) {
    SAFE("safe"),
    CAUTION("caution"),
    AT_RISK("at_risk"),
    UNKNOWN("unknown"),
}

data class PowerSafetyAssessment(
    val level: PowerRiskLevel,
    val observation: PowerSafetyObservation,
    val reasonCodes: List<String>,
    val summary: String,
    val action: String,
) {
    val warning: Boolean get() = level != PowerRiskLevel.SAFE
}

/** Exact system-power mutation capability. Helper/Shizuku do not count until they expose typed verbs. */
enum class PowerRepairCapability(val wireValue: String) {
    DIRECT_ROOT("direct_root"),
    /** This profile normally supports direct app su, but the live probe is currently failing. */
    DEGRADED("degraded"),
    /** App-owned guards can still be repaired; Android global/Doze mutation is unavailable. */
    APP_ONLY("app_only"),
}

enum class PowerSafetyAdvisoryAction(val wireValue: String) {
    NONE("none"),
    REPAIR("repair"),
    ACKNOWLEDGE("acknowledge"),
    MANUAL_ONLY("manual_only"),
}

enum class PowerSafetyAcknowledgementDecision {
    ACCEPT,
    MALFORMED,
    STALE,
    NOT_ACKNOWLEDGEABLE,
}

data class PowerSafetyAdvisory(
    val assessment: PowerSafetyAssessment,
    val repairCapability: PowerRepairCapability,
    val action: PowerSafetyAdvisoryAction,
    /** Non-null only for the exact healthy, caution-only state that may be acknowledged. */
    val acknowledgementFingerprint: String?,
    val acknowledged: Boolean,
) {
    val repairActionable: Boolean get() = action == PowerSafetyAdvisoryAction.REPAIR
    val acknowledgeable: Boolean get() = action == PowerSafetyAdvisoryAction.ACKNOWLEDGE
    val bannerVisible: Boolean get() = assessment.warning && !acknowledged
}

/**
 * Presentation admission for one observed assessment. Acknowledgement never changes risk truth: it only
 * suppresses browser banners for one exact, healthy app-guarded caution that has no supported mutator.
 */
object PowerSafetyAdvisoryPolicy {
    private val acknowledgementFingerprintPattern = Regex("[0-9a-f]{64}")

    fun isAcknowledgementFingerprint(value: String): Boolean =
        value.matches(acknowledgementFingerprintPattern)

    fun evaluate(
        assessment: PowerSafetyAssessment,
        repairCapability: PowerRepairCapability,
        acknowledgedFingerprint: String?,
    ): PowerSafetyAdvisory {
        val observation = assessment.observation
        val appGuard = observation.keepAwakeConfigured && observation.wakeLockHeld &&
            (!observation.wifiLockRequired || observation.wifiLockHeld)
        val timeoutGuard = observation.preventIdleDimConfigured &&
            observation.screenOffTimeoutMs == Int.MAX_VALUE
        val appRepairNeeded = !appGuard || !timeoutGuard
        val acknowledgeable = assessment.level == PowerRiskLevel.CAUTION &&
            !appRepairNeeded && repairCapability == PowerRepairCapability.APP_ONLY
        val action = when {
            assessment.level == PowerRiskLevel.SAFE -> PowerSafetyAdvisoryAction.NONE
            appRepairNeeded -> PowerSafetyAdvisoryAction.REPAIR
            repairCapability != PowerRepairCapability.APP_ONLY -> PowerSafetyAdvisoryAction.REPAIR
            acknowledgeable -> PowerSafetyAdvisoryAction.ACKNOWLEDGE
            else -> PowerSafetyAdvisoryAction.MANUAL_ONLY
        }
        val fingerprint = if (acknowledgeable) fingerprint(assessment, repairCapability) else null
        return PowerSafetyAdvisory(
            assessment = assessment,
            repairCapability = repairCapability,
            action = action,
            acknowledgementFingerprint = fingerprint,
            acknowledged = fingerprint != null && fingerprint == acknowledgedFingerprint,
        )
    }

    internal fun fingerprint(
        assessment: PowerSafetyAssessment,
        repairCapability: PowerRepairCapability,
    ): String {
        val o = assessment.observation
        val canonical = listOf(
            "power-safety-ack-v1",
            assessment.level.wireValue,
            repairCapability.wireValue,
            assessment.reasonCodes.distinct().sorted().joinToString(","),
            o.keepAwakeConfigured,
            o.wakeLockHeld,
            o.wifiLockRequired,
            o.wifiLockHeld,
            o.preventIdleDimConfigured,
            o.screenOffTimeoutMs,
            o.pluggedMask,
            o.stayOnWhilePluggedIn,
            o.deviceIdleMode,
            o.ignoringBatteryOptimizations,
            o.screenOffMechanism,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Exact, presentation-only admission. A stale request cannot suppress newer risk evidence. */
    fun admitAcknowledgement(
        requestedFingerprint: String,
        current: PowerSafetyAdvisory,
    ): PowerSafetyAcknowledgementDecision = when {
        !isAcknowledgementFingerprint(requestedFingerprint) ->
            PowerSafetyAcknowledgementDecision.MALFORMED
        !current.acknowledgeable || current.acknowledgementFingerprint == null ->
            PowerSafetyAcknowledgementDecision.NOT_ACKNOWLEDGEABLE
        requestedFingerprint != current.acknowledgementFingerprint ->
            PowerSafetyAcknowledgementDecision.STALE
        else -> PowerSafetyAcknowledgementDecision.ACCEPT
    }
}

/**
 * Conservative reachability classification. It deliberately assesses combinations of effective guards;
 * no individual Android setting is presented as proof against OEM-specific suspend behaviour.
 */
object PowerSafetyPolicy {
    fun assess(observation: PowerSafetyObservation): PowerSafetyAssessment {
        val reasons = buildList {
            if (!observation.keepAwakeConfigured) add("keep_awake_disabled")
            else if (!observation.wakeLockHeld) add("wake_lock_not_held")
            if (observation.wifiLockRequired && !observation.wifiLockHeld) add("wifi_lock_not_held")
            if (!observation.preventIdleDimConfigured) add("prevent_idle_dim_disabled")
            when (observation.screenOffTimeoutMs) {
                null -> add("screen_timeout_unknown")
                Int.MAX_VALUE -> Unit
                else -> add("native_screen_timeout_finite")
            }
            if (observation.deviceIdleMode == true) add("device_idle_active")
            when (observation.ignoringBatteryOptimizations) {
                false -> add("doze_not_exempt")
                null -> add("doze_exemption_unknown")
                true -> Unit
            }
            when (observation.pluggedMask) {
                null -> add("power_source_unknown")
                0 -> add("power_source_reports_unplugged")
                else -> Unit
            }
            when (stayAwakeEffective(observation)) {
                false -> add(if (observation.stayOnWhilePluggedIn == 0) "stay_on_disabled" else "stay_on_misses_source")
                null -> add("stay_on_effect_unknown")
                true -> Unit
            }
        }

        val appGuard = observation.keepAwakeConfigured && observation.wakeLockHeld &&
            (!observation.wifiLockRequired || observation.wifiLockHeld)
        val nativeTimeoutGuard = observation.preventIdleDimConfigured &&
            observation.screenOffTimeoutMs == Int.MAX_VALUE
        val dozeGuard = observation.ignoringBatteryOptimizations == true
        val stayAwakeGuard = stayAwakeEffective(observation) == true
        val knownSystemGuard = dozeGuard || (nativeTimeoutGuard && stayAwakeGuard)
        val unknownRequiredProbe = observation.pluggedMask == null ||
            observation.stayOnWhilePluggedIn == null ||
            observation.screenOffTimeoutMs == null ||
            observation.deviceIdleMode == null ||
            observation.ignoringBatteryOptimizations == null

        val level = when {
            observation.deviceIdleMode == true && !dozeGuard -> PowerRiskLevel.AT_RISK
            appGuard && knownSystemGuard -> PowerRiskLevel.SAFE
            appGuard -> if (unknownRequiredProbe) PowerRiskLevel.UNKNOWN else PowerRiskLevel.CAUTION
            knownSystemGuard -> PowerRiskLevel.CAUTION
            unknownRequiredProbe -> PowerRiskLevel.UNKNOWN
            else -> PowerRiskLevel.AT_RISK
        }
        val summary = when (level) {
            PowerRiskLevel.SAFE ->
                "Panel reachability has the required app power locks and a positively observed Android power guard."
            PowerRiskLevel.CAUTION ->
                "Panel reachability currently depends on only one observed power guard."
            PowerRiskLevel.AT_RISK ->
                "The panel can suspend or enter Doze after screen-off, making HTTP, MQTT, and background work unreachable."
            PowerRiskLevel.UNKNOWN ->
                "Power safety could not be established because one or more Android power probes were unavailable or ambiguous."
        }
        return PowerSafetyAssessment(
            level = level,
            observation = observation,
            reasonCodes = reasons.distinct(),
            summary = summary,
            action = "Review the observed power guards. Android stay-awake and Doze changes require direct root; power-safety actions never reboot the panel.",
        )
    }

    fun stayAwakeEffective(observation: PowerSafetyObservation): Boolean? {
        val plugged = observation.pluggedMask ?: return null
        val stayOn = observation.stayOnWhilePluggedIn ?: return null
        if (plugged == 0) return false
        return plugged and stayOn != 0
    }
}

enum class PowerRepairStepStatus(val wireValue: String) {
    ALREADY("already"),
    APPLIED("applied"),
    UNAVAILABLE("unavailable"),
    FAILED("failed"),
}

data class PowerSafetyRepairResult(
    val status: String,
    val keepAwake: PowerRepairStepStatus,
    val preventIdleDim: PowerRepairStepStatus,
    val stayOnWhilePluggedIn: PowerRepairStepStatus,
    val dozeExemption: PowerRepairStepStatus,
    val privilegedPowerControl: String,
    val assessment: PowerSafetyAssessment,
) {
    val complete: Boolean get() = status == "repaired"
}

/** Pure decisions used by the Android repair controller and directly covered by JVM tests. */
internal object PowerSafetyRepairPolicy {
    const val STANDARD_PLUG_MASK = 1 or 2 or 4 or 8

    fun stayOnTarget(baseline: Int?): Int? = baseline?.or(STANDARD_PLUG_MASK)

    fun verifiedMutationStatus(effectiveAfter: Boolean): PowerRepairStepStatus =
        if (effectiveAfter) PowerRepairStepStatus.APPLIED else PowerRepairStepStatus.FAILED

    fun dozeMutationCommand(packageName: String, cmdAvailable: Boolean, dumpsysAvailable: Boolean): String? {
        require(packageName.matches(Regex("[A-Za-z0-9_.]{1,255}")))
        return when {
            cmdAvailable -> "cmd deviceidle whitelist +$packageName"
            dumpsysAvailable -> "dumpsys deviceidle whitelist +$packageName"
            else -> null
        }
    }

    fun resultStatus(assessment: PowerSafetyAssessment, steps: List<PowerRepairStepStatus>): String = when {
        assessment.level == PowerRiskLevel.SAFE && PowerRepairStepStatus.FAILED !in steps -> "repaired"
        steps.firstOrNull() == PowerRepairStepStatus.FAILED -> "failed"
        else -> "partial"
    }
}

/** Admission decisions shared by HTTP configuration and broker-originated live settings. */
internal object PowerSafetyMutationPolicy {
    fun requestsSafetyReduction(
        keepAwake: Boolean,
        requestedKeepAwake: Boolean?,
        preventIdleDim: Boolean,
        requestedPreventIdleDim: Boolean?,
    ): Boolean =
        (keepAwake && requestedKeepAwake == false) ||
            (preventIdleDim && requestedPreventIdleDim == false)

    /** Malformed broker payloads must never collapse into a safety-reducing OFF transition. */
    fun parseGuardSwitch(payload: String): Boolean? = when {
        payload.trim().equals("ON", ignoreCase = true) -> true
        payload.trim().equals("OFF", ignoreCase = true) -> false
        else -> null
    }
}
