package io.panelassistant.android.control

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import io.panelassistant.android.platform.RootShell

/** Read-only Android power probes plus one explicit, read-back-verified repair operation. */
class PowerSafetyController(
    context: Context,
    private val power: PowerController,
    private val root: RootShell = Su,
    private val screenOffMechanism: String,
    /** Durable read of the last actual screen-off; never probes su or the helper daemon on its own. */
    private val screenRouteSelection: () -> RouteSelection,
    private val directRootExpected: Boolean = false,
) {
    private val app = context.applicationContext
    private val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun assess(keepAwakeConfigured: Boolean, preventIdleDimConfigured: Boolean): PowerSafetyAssessment =
        PowerSafetyPolicy.assess(observe(keepAwakeConfigured, preventIdleDimConfigured))

    /** Fresh harmless probe for an explicit control request. Never call this from passive page rendering. */
    fun repairCapabilityFresh(): PowerRepairCapability = when {
        runCatching(root::available).getOrDefault(false) -> PowerRepairCapability.DIRECT_ROOT
        directRootExpected -> PowerRepairCapability.DEGRADED
        else -> PowerRepairCapability.APP_ONLY
    }

    fun observe(keepAwakeConfigured: Boolean, preventIdleDimConfigured: Boolean): PowerSafetyObservation {
        val locks = power.safetyState()
        val plugged = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                ?.takeIf { it >= 0 }
        }.getOrNull()
        val stayOn = runCatching {
            Settings.Global.getInt(app.contentResolver, STAY_ON_WHILE_PLUGGED_IN)
        }.getOrNull()
        return PowerSafetyObservation(
            keepAwakeConfigured = keepAwakeConfigured,
            wakeLockHeld = locks.wakeLockHeld,
            wifiLockRequired = locks.wifiLockRequired,
            wifiLockHeld = locks.wifiLockHeld,
            preventIdleDimConfigured = preventIdleDimConfigured,
            screenOffTimeoutMs = runCatching {
                Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            }.getOrNull(),
            interactive = runCatching { powerManager.isInteractive }.getOrNull(),
            pluggedMask = plugged,
            stayOnWhilePluggedIn = stayOn,
            deviceIdleMode = runCatching { powerManager.isDeviceIdleMode }.getOrNull(),
            ignoringBatteryOptimizations = runCatching {
                powerManager.isIgnoringBatteryOptimizations(app.packageName)
            }.getOrNull(),
            screenOffMechanism = screenOffMechanism,
            screenOffSelected = screenRouteSelection().selected?.name?.lowercase(),
            screenOffReason = screenRouteSelection().reason,
        )
    }

    /**
     * Repair is intentionally idempotent and never reboots. The app CPU/Wi-Fi locks are always attempted;
     * global stay-awake and Doze exemption are attempted only after a harmless root capability probe.
     */
    fun repair(
        readKeepAwakeConfigured: () -> Boolean,
        readPreventIdleDimConfigured: () -> Boolean,
        persistKeepAwake: () -> Boolean,
        applyPreventIdleDim: () -> Boolean,
    ): PowerSafetyRepairResult {
        val before = observe(readKeepAwakeConfigured(), readPreventIdleDimConfigured())
        val appGuardBefore = before.wakeLockHeld && (!before.wifiLockRequired || before.wifiLockHeld)
        val keepStep = if (before.keepAwakeConfigured && appGuardBefore) {
            PowerRepairStepStatus.ALREADY
        } else {
            val persisted = runCatching(persistKeepAwake).getOrDefault(false)
            val applied = persisted && runCatching { power.apply(true); power.isHeld() }.getOrDefault(false)
            if (applied) PowerRepairStepStatus.APPLIED else PowerRepairStepStatus.FAILED
        }

        val timeoutStep = if (
            before.preventIdleDimConfigured && before.screenOffTimeoutMs == NEVER_SCREEN_TIMEOUT_MS
        ) {
            PowerRepairStepStatus.ALREADY
        } else {
            if (runCatching(applyPreventIdleDim).getOrDefault(false)) {
                PowerRepairStepStatus.APPLIED
            } else {
                PowerRepairStepStatus.FAILED
            }
        }

        val capability = repairCapabilityFresh()
        val hasRoot = capability == PowerRepairCapability.DIRECT_ROOT
        val privilegedStayOnBefore = if (hasRoot) readStayOnPrivileged() else null
        val stayBaseline = before.stayOnWhilePluggedIn ?: privilegedStayOnBefore
        var verifiedStayOn: Int? = stayBaseline
        val stayStep = when {
            stayBaseline != null && stayBaseline and PowerSafetyRepairPolicy.STANDARD_PLUG_MASK == PowerSafetyRepairPolicy.STANDARD_PLUG_MASK ->
                PowerRepairStepStatus.ALREADY
            !hasRoot -> PowerRepairStepStatus.UNAVAILABLE
            stayBaseline == null -> PowerRepairStepStatus.UNAVAILABLE
            else -> {
                val target = requireNotNull(PowerSafetyRepairPolicy.stayOnTarget(stayBaseline))
                root.runSingleAttempt("settings put global $STAY_ON_WHILE_PLUGGED_IN $target")
                verifiedStayOn = runCatching {
                    Settings.Global.getInt(app.contentResolver, STAY_ON_WHILE_PLUGGED_IN)
                }.getOrNull() ?: readStayOnPrivileged()
                val observedMask = verifiedStayOn
                PowerSafetyRepairPolicy.verifiedMutationStatus(
                    observedMask != null && observedMask and PowerSafetyRepairPolicy.STANDARD_PLUG_MASK ==
                        PowerSafetyRepairPolicy.STANDARD_PLUG_MASK,
                )
            }
        }

        val exemptBefore = before.ignoringBatteryOptimizations == true
        val dozeStep = when {
            exemptBefore -> PowerRepairStepStatus.ALREADY
            !hasRoot -> PowerRepairStepStatus.UNAVAILABLE
            else -> {
                val packageName = app.packageName
                val cmdAvailable = root.runOutput("cmd deviceidle whitelist 2>/dev/null") != null
                val command = PowerSafetyRepairPolicy.dozeMutationCommand(
                    packageName = packageName,
                    cmdAvailable = cmdAvailable,
                    dumpsysAvailable = !cmdAvailable &&
                        root.runOutput("dumpsys deviceidle whitelist 2>/dev/null") != null,
                )
                if (command == null) {
                    PowerRepairStepStatus.UNAVAILABLE
                } else {
                    root.runSingleAttempt(command)
                    val observed = runCatching {
                        powerManager.isIgnoringBatteryOptimizations(packageName)
                    }.getOrDefault(false)
                    PowerSafetyRepairPolicy.verifiedMutationStatus(observed)
                }
            }
        }

        val observedAfter = observe(
            keepAwakeConfigured = readKeepAwakeConfigured(),
            preventIdleDimConfigured = readPreventIdleDimConfigured(),
        )
        val after = PowerSafetyPolicy.assess(
            observedAfter.copy(stayOnWhilePluggedIn = observedAfter.stayOnWhilePluggedIn ?: verifiedStayOn),
        )
        val status = PowerSafetyRepairPolicy.resultStatus(
            after,
            listOf(keepStep, timeoutStep, stayStep, dozeStep),
        )
        return PowerSafetyRepairResult(
            status = status,
            keepAwake = keepStep,
            preventIdleDim = timeoutStep,
            stayOnWhilePluggedIn = stayStep,
            dozeExemption = dozeStep,
            privilegedPowerControl = capability.wireValue,
            assessment = after,
        )
    }

    private fun readStayOnPrivileged(): Int? = root.runOutput(
        "settings get global $STAY_ON_WHILE_PLUGGED_IN 2>/dev/null",
    )?.trim()?.toIntOrNull()

    private companion object {
        const val STAY_ON_WHILE_PLUGGED_IN = "stay_on_while_plugged_in"
    }
}
