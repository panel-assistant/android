package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSafetyPolicyTest {
    private fun observation(
        keepAwake: Boolean = true,
        wakeLock: Boolean = true,
        wifiRequired: Boolean = true,
        wifiLock: Boolean = true,
        preventIdleDim: Boolean = true,
        timeoutMs: Int? = Int.MAX_VALUE,
        pluggedMask: Int? = 1,
        stayOnMask: Int? = 1,
        idle: Boolean? = false,
        exempt: Boolean? = false,
        interactive: Boolean? = true,
    ) = PowerSafetyObservation(
        keepAwakeConfigured = keepAwake,
        wakeLockHeld = wakeLock,
        wifiLockRequired = wifiRequired,
        wifiLockHeld = wifiLock,
        preventIdleDimConfigured = preventIdleDim,
        screenOffTimeoutMs = timeoutMs,
        interactive = interactive,
        pluggedMask = pluggedMask,
        stayOnWhilePluggedIn = stayOnMask,
        deviceIdleMode = idle,
        ignoringBatteryOptimizations = exempt,
        screenOffMechanism = "brightness-zero",
    )

    @Test fun `two positively observed guards classify safe`() {
        val assessment = PowerSafetyPolicy.assess(observation())
        assertEquals(PowerRiskLevel.SAFE, assessment.level)
        assertFalse(assessment.warning)
    }

    @Test fun `disabled app guard and ineffective native guard classify at risk`() {
        val assessment = PowerSafetyPolicy.assess(
            observation(keepAwake = false, wakeLock = false, preventIdleDim = false, timeoutMs = 60_000, stayOnMask = 0),
        )
        assertEquals(PowerRiskLevel.AT_RISK, assessment.level)
        assertTrue("keep_awake_disabled" in assessment.reasonCodes)
        assertTrue("native_screen_timeout_finite" in assessment.reasonCodes)
        assertTrue("stay_on_disabled" in assessment.reasonCodes)
    }

    @Test fun `configured but missing wake lock is not called safe`() {
        val assessment = PowerSafetyPolicy.assess(observation(wakeLock = false))
        assertEquals(PowerRiskLevel.CAUTION, assessment.level)
        assertTrue("wake_lock_not_held" in assessment.reasonCodes)
    }

    @Test fun `configured but missing required wifi lock is not called safe`() {
        val assessment = PowerSafetyPolicy.assess(observation(wifiLock = false))
        assertEquals(PowerRiskLevel.CAUTION, assessment.level)
        assertTrue("wifi_lock_not_held" in assessment.reasonCodes)
    }

    @Test fun `wifi lock is not required when Android exposes no wifi service`() {
        assertEquals(PowerRiskLevel.SAFE, PowerSafetyPolicy.assess(observation(wifiRequired = false, wifiLock = false)).level)
    }

    @Test fun `effective app lock requires wifi whenever wifi is present`() {
        assertFalse(PowerLockState(true, wifiLockRequired = true, wifiLockHeld = false).effective)
        assertTrue(PowerLockState(true, wifiLockRequired = false, wifiLockHeld = false).effective)
    }

    @Test fun `active device idle without exemption is at risk even with wake lock`() {
        val assessment = PowerSafetyPolicy.assess(observation(idle = true, exempt = false))
        assertEquals(PowerRiskLevel.AT_RISK, assessment.level)
        assertTrue("device_idle_active" in assessment.reasonCodes)
    }

    @Test fun `unknown power source remains unknown when only app guard is observed`() {
        val assessment = PowerSafetyPolicy.assess(
            observation(pluggedMask = null, stayOnMask = null, exempt = false),
        )
        assertEquals(PowerRiskLevel.UNKNOWN, assessment.level)
        assertTrue("power_source_unknown" in assessment.reasonCodes)
        assertFalse(assessment.summary.contains("unplugged", ignoreCase = true))
    }

    @Test fun `Android reported no external power is bounded wording not physical inference`() {
        val assessment = PowerSafetyPolicy.assess(observation(pluggedMask = 0, stayOnMask = 15))
        assertEquals(PowerRiskLevel.CAUTION, assessment.level)
        assertTrue("power_source_reports_unplugged" in assessment.reasonCodes)
        assertFalse(assessment.summary.contains("battery-powered", ignoreCase = true))
    }

    @Test fun `doze exemption plus active app guard is safe despite unreliable charging report`() {
        val assessment = PowerSafetyPolicy.assess(
            observation(pluggedMask = 0, stayOnMask = 0, exempt = true),
        )
        assertEquals(PowerRiskLevel.SAFE, assessment.level)
    }

    @Test fun `stay awake must cover the currently reported source bit`() {
        val usb = observation(keepAwake = false, wakeLock = false, pluggedMask = 2, stayOnMask = 1)
        assertEquals(false, PowerSafetyPolicy.stayAwakeEffective(usb))
        assertTrue("stay_on_misses_source" in PowerSafetyPolicy.assess(usb).reasonCodes)
    }

    @Test fun `repair preserves OEM stay-on bits and refuses an unknown baseline`() {
        assertEquals(null, PowerSafetyRepairPolicy.stayOnTarget(null))
        assertEquals(31, PowerSafetyRepairPolicy.stayOnTarget(16))
    }

    @Test fun `repair mutation status follows readback`() {
        assertEquals(PowerRepairStepStatus.APPLIED, PowerSafetyRepairPolicy.verifiedMutationStatus(true))
        assertEquals(PowerRepairStepStatus.FAILED, PowerSafetyRepairPolicy.verifiedMutationStatus(false))
    }

    @Test fun `Doze repair selects exactly one admitted mutator`() {
        assertEquals(
            "cmd deviceidle whitelist +io.github.maxlyth.hapaneld",
            PowerSafetyRepairPolicy.dozeMutationCommand("io.github.maxlyth.hapaneld", true, true),
        )
        assertEquals(
            "dumpsys deviceidle whitelist +io.github.maxlyth.hapaneld",
            PowerSafetyRepairPolicy.dozeMutationCommand("io.github.maxlyth.hapaneld", false, true),
        )
        assertEquals(null, PowerSafetyRepairPolicy.dozeMutationCommand("io.github.maxlyth.hapaneld", false, false))
    }

    @Test fun `safe assessment with a failed repair step remains partial`() {
        val safe = PowerSafetyPolicy.assess(observation())
        assertEquals(
            "partial",
            PowerSafetyRepairPolicy.resultStatus(
                safe,
                listOf(PowerRepairStepStatus.ALREADY, PowerRepairStepStatus.FAILED),
            ),
        )
    }

    @Test fun `healthy app-only caution is exactly acknowledgeable and remains truthful`() {
        val assessment = PowerSafetyPolicy.assess(observation(stayOnMask = 0, exempt = false))
        val offered = PowerSafetyAdvisoryPolicy.evaluate(assessment, PowerRepairCapability.APP_ONLY, null)

        assertEquals(PowerRiskLevel.CAUTION, assessment.level)
        assertEquals(PowerSafetyAdvisoryAction.ACKNOWLEDGE, offered.action)
        assertTrue(offered.acknowledgeable)
        assertEquals(64, offered.acknowledgementFingerprint?.length)
        assertTrue(offered.bannerVisible)

        val hidden = PowerSafetyAdvisoryPolicy.evaluate(
            assessment,
            PowerRepairCapability.APP_ONLY,
            offered.acknowledgementFingerprint,
        )
        assertTrue(hidden.acknowledged)
        assertFalse(hidden.bannerVisible)
        assertEquals(PowerRiskLevel.CAUTION, hidden.assessment.level)
    }

    @Test fun `acknowledgement fingerprint ignores reason order and volatile screen interaction`() {
        val base = PowerSafetyPolicy.assess(observation(stayOnMask = 0, exempt = false))
        val first = PowerSafetyAdvisoryPolicy.fingerprint(base, PowerRepairCapability.APP_ONLY)
        val reordered = PowerSafetyAdvisoryPolicy.fingerprint(
            base.copy(reasonCodes = base.reasonCodes.reversed() + base.reasonCodes.first()),
            PowerRepairCapability.APP_ONLY,
        )
        val screenOff = PowerSafetyAdvisoryPolicy.fingerprint(
            base.copy(observation = base.observation.copy(interactive = false)),
            PowerRepairCapability.APP_ONLY,
        )

        assertEquals(first, reordered)
        assertEquals(first, screenOff)
    }

    @Test fun `causal evidence or repair capability changes re-arm the caution`() {
        val base = PowerSafetyPolicy.assess(observation(stayOnMask = 0, exempt = false))
        val fingerprint = PowerSafetyAdvisoryPolicy.fingerprint(base, PowerRepairCapability.APP_ONLY)

        assertFalse(
            fingerprint == PowerSafetyAdvisoryPolicy.fingerprint(
                base.copy(observation = base.observation.copy(pluggedMask = 2)),
                PowerRepairCapability.APP_ONLY,
            ),
        )
        assertFalse(
            fingerprint == PowerSafetyAdvisoryPolicy.fingerprint(base, PowerRepairCapability.DIRECT_ROOT),
        )
    }

    @Test fun `repair routes and elevated uncertainty cannot be acknowledged`() {
        val caution = PowerSafetyPolicy.assess(observation(stayOnMask = 0, exempt = false))
        assertEquals(
            PowerSafetyAdvisoryAction.REPAIR,
            PowerSafetyAdvisoryPolicy.evaluate(caution, PowerRepairCapability.DIRECT_ROOT, null).action,
        )
        assertEquals(
            PowerSafetyAdvisoryAction.REPAIR,
            PowerSafetyAdvisoryPolicy.evaluate(caution, PowerRepairCapability.DEGRADED, null).action,
        )
        val missingAppGuard = PowerSafetyPolicy.assess(observation(wakeLock = false, stayOnMask = 0))
        assertEquals(
            PowerSafetyAdvisoryAction.REPAIR,
            PowerSafetyAdvisoryPolicy.evaluate(missingAppGuard, PowerRepairCapability.APP_ONLY, null).action,
        )
        val unknown = PowerSafetyPolicy.assess(observation(pluggedMask = null, stayOnMask = null))
        assertEquals(
            PowerSafetyAdvisoryAction.MANUAL_ONLY,
            PowerSafetyAdvisoryPolicy.evaluate(unknown, PowerRepairCapability.APP_ONLY, null).action,
        )
        val atRisk = PowerSafetyPolicy.assess(observation(idle = true, stayOnMask = 0))
        assertEquals(
            PowerSafetyAdvisoryAction.MANUAL_ONLY,
            PowerSafetyAdvisoryPolicy.evaluate(atRisk, PowerRepairCapability.APP_ONLY, null).action,
        )
    }

    @Test fun `acknowledgement admission rejects malformed stale and no-longer-eligible requests`() {
        val caution = PowerSafetyPolicy.assess(observation(stayOnMask = 0, exempt = false))
        val current = PowerSafetyAdvisoryPolicy.evaluate(caution, PowerRepairCapability.APP_ONLY, null)
        val fingerprint = requireNotNull(current.acknowledgementFingerprint)

        assertEquals(
            PowerSafetyAcknowledgementDecision.MALFORMED,
            PowerSafetyAdvisoryPolicy.admitAcknowledgement("bad", current),
        )
        assertEquals(
            PowerSafetyAcknowledgementDecision.STALE,
            PowerSafetyAdvisoryPolicy.admitAcknowledgement("0".repeat(64), current),
        )
        assertEquals(
            PowerSafetyAcknowledgementDecision.ACCEPT,
            PowerSafetyAdvisoryPolicy.admitAcknowledgement(fingerprint, current),
        )
        val repairable = PowerSafetyAdvisoryPolicy.evaluate(caution, PowerRepairCapability.DIRECT_ROOT, null)
        assertEquals(
            PowerSafetyAcknowledgementDecision.NOT_ACKNOWLEDGEABLE,
            PowerSafetyAdvisoryPolicy.admitAcknowledgement(fingerprint, repairable),
        )
    }
}
