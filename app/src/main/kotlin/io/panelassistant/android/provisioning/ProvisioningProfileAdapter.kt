package io.panelassistant.android.provisioning

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.SuForm
import io.panelassistant.android.device.profile.DataDeviceProfile
import io.panelassistant.android.device.profile.ProfileArtifacts
import io.panelassistant.android.device.profile.ProfileHelperAuthorityDemand
import io.panelassistant.android.device.profile.ProfileMetadata
import io.panelassistant.android.device.profile.ResolvedProfile
import io.panelassistant.android.device.profile.ShizukuRecommendation

/**
 * The single adaptation boundary from the active normalized runtime profile into planner intent.
 * Release-owned artifact metadata is resolved here; no URL, signer, hash, or author prose reaches
 * the plan.
 */
internal fun ResolvedProfile.toProvisioningProfile(): ProvisioningProfile {
    val artifactId = profile.provisioning.webViewArtifactId
    val target = artifactId?.let { id ->
        ProfileArtifacts.webViews[id]?.let { spec ->
            ProvisioningWebViewTarget(artifactId = id, version = spec.version)
        }
    }
    return ProvisioningProfile(
        ref = summary.ref,
        displayName = summary.displayName,
        origin = summary.origin,
        contentVersion = summary.contentVersion,
        // appCanSu is an attempt-order hint, not an observation that this installation currently has
        // root. Privilege guidance is suppressed only after the planner sees a compatible root helper.
        directRootExpected = false,
        helperImportance = profile.provisioningHelperImportance(),
        shizuku = profile.provisioning.shizuku,
        webView = target,
    )
}

/**
 * A selected helper-only driver is a hard dependency. Without one, a sandbox-walled panel still needs
 * the helper for display sizing, CPU governor, screenshots and performance readings, which no profile
 * driver declares, so its absence is a degradation rather than a missing hardware route.
 */
internal fun DeviceProfile.provisioningHelperImportance(): ProvisioningImportance? = when {
    requiresProvisioningHelper() -> ProvisioningImportance.REQUIRED
    !appCanSu -> ProvisioningImportance.RECOMMENDED
    else -> null
}

/**
 * A helper is required only when a selected core driver demands that authority. This keeps privileged
 * host/app-su operations out of the helper item while covering app-first controllers whose documented
 * runtime fallback becomes the only privileged authority on a sandbox-walled profile.
 */
internal fun DeviceProfile.requiresProvisioningHelper(): Boolean =
    provisioningDriverIds().any { driver ->
        when (ProfileMetadata.helperAuthorityDemand.getValue(driver)) {
            ProfileHelperAuthorityDemand.NONE -> false
            ProfileHelperAuthorityDemand.SANDBOX_FALLBACK -> !appCanSu
            ProfileHelperAuthorityDemand.SHIZUKU_ALTERNATE ->
                provisioning.shizuku == ShizukuRecommendation.NONE
            ProfileHelperAuthorityDemand.REQUIRED -> true
        }
    }

/**
 * Runtime YAML profiles retain their validated driver declaration. The emergency profile and test
 * contracts use the same core ids inferred from their normalized capability fields.
 */
private fun DeviceProfile.provisioningDriverIds(): Set<String> =
    (this as? DataDeviceProfile)?.document?.requires?.drivers ?: buildSet {
        when (suForm) {
            SuForm.ANDROID -> if (appCanSu) add("access.android-su")
            SuForm.TOOLBOX -> if (appCanSu) add("access.toolbox-su")
            SuForm.NONE -> Unit
        }
        when (ledMechanism) {
            LedMechanism.RK3576_IOCTL -> add("led.rk3576-ioctl")
            LedMechanism.RK3576_IOCTL_DAEMON -> add("led.rk3576-ioctl-daemon")
            LedMechanism.SYSFS_DAEMON -> add("led.sysfs-daemon")
            LedMechanism.AUTODETECT -> add("led.autodetect")
            LedMechanism.NONE -> Unit
        }
        add(
            when (screenOff) {
                ScreenOff.SU_BLPOWER -> "screen.su-blpower"
                ScreenOff.DAEMON_BLPOWER -> "screen.daemon-blpower"
                ScreenOff.KEYEVENT -> "screen.keyevent"
                ScreenOff.BRIGHTNESS_ZERO -> "screen.brightness-zero"
            },
        )
        if (zigbeeGatewayDir != null) add("radio.siliconlabs-host")
        if (relayBase != null || relayBaseFallbacks.isNotEmpty()) add("relay.sysfs")
        if (buttonLedGpioBase != null) add("relay.gpio-button-led")
        if (hasButtonBacklight) add("input.button-backlight")
        if (proximityTech != null || lightTech != null) add("sensor.android")
        if (proximityGpio != null) add("sensor.gpio-proximity")
        if (hasCht8305) add("sensor.cht8305-daemon")
        if (hasVi530x) add("sensor.vi530x-daemon")
        if (evdevButtons.isNotEmpty()) add("input.evdev")
        if (recommendedWebView != null) add("update.webview")
    }
