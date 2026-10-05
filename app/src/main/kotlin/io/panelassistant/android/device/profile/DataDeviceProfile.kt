package io.panelassistant.android.device.profile

import io.panelassistant.android.device.BacklightRoute
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.EvdevButton
import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.device.PackageDesiredState
import io.panelassistant.android.device.PackageIntent
import io.panelassistant.android.device.ProvisioningImportance
import io.panelassistant.android.device.ProvisioningIntent
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.SuForm
import io.panelassistant.android.hardware.LedTransfer
import io.panelassistant.android.hardware.TransferCurve

/** DeviceProfile adapter for a validated declarative document. */
class DataDeviceProfile internal constructor(
    val document: ProfileDocument,
    private val productVersion: String,
    override val revision: String,
    private val trustedBundledContent: Boolean,
) : DeviceProfile {
    override val id = document.id
    override val displayName = document.displayName
    override val socClass = document.socClass
    override val soc = document.soc
    override val profileLinks = buildList {
        document.metadata.source?.let { add(ProfileLink("Panel details", it)) }
        addAll(document.metadata.links)
    }.distinctBy { it.url }
    override val suForm = when (document.platform.suForm) {
        "toolbox" -> SuForm.TOOLBOX
        "android" -> SuForm.ANDROID
        else -> SuForm.NONE
    }
    override val appCanSu = document.platform.appCanSu
    override val hasRecents = document.platform.hasRecents
    override val declarationsFromCatalog = true
    override val hasNativeNavbar = document.platform.hasNativeNavbar
    override val vendorHomePackages = document.platform.launcher.vendorHomePackages.toSet()
    // Never default an unknown name to NONE: a NONE LED is stated unsupported and Panel Assistant deletes
    // the entity. The validator refuses the profile first (`unknown-led-mechanism`), so this is unreachable.
    override val ledMechanism = LedMechanism.ofYaml(document.hardware.led.mechanism)
        ?: error("Unknown LED mechanism '${document.hardware.led.mechanism}' in an unvalidated profile.")
    override val ledTransfer: LedTransfer =
        if (document.hardware.led.transfer == LedTransfer.RK3576_FOUR_BIT) {
            LedTransfer.Rk3576FourBit
        } else {
            LedTransfer.curved(validCurve(document.hardware.led.curve))
        }
    override val backlightTransfer: TransferCurve = validCurve(document.hardware.backlight?.curve)
    override val backlightRoute: BacklightRoute =
        if (document.hardware.backlight?.route == "setting") BacklightRoute.SETTING else BacklightRoute.NODE
    override val buttonBacklightTransfer: TransferCurve = validCurve(document.hardware.buttonBacklight)
    override val screenOff = when (document.hardware.screenOff) {
        "su-blpower" -> ScreenOff.SU_BLPOWER
        "daemon-blpower" -> ScreenOff.DAEMON_BLPOWER
        "keyevent" -> ScreenOff.KEYEVENT
        else -> ScreenOff.BRIGHTNESS_ZERO
    }
    override val hasButtonBacklight = document.hardware.hasButtonBacklight
    override val cameraDeclared = document.hardware.cameraDeclared
    override val hasMicrophone = document.hardware.hasMicrophone
    override val hasSpeaker = document.hardware.hasSpeaker
    override val cameraLensOffsetPx = document.hardware.cameraLensOffsetPx
    override val touchClickGain = document.hardware.touchClickGain ?: 0.2f
    override val zigbeeGatewayDir = document.hardware.zigbeeGatewayDir
    override val relayBase = document.hardware.relayBase
    override val relayBaseFallbacks = document.hardware.relayBaseFallbacks
    override val buttonLedGpioBase = document.hardware.buttonLedGpioBase
    override val proximityTech = document.sensors.proximityTechnology
    override val proximityCalibration = document.sensors.proximityCalibration
    override val proximityGpio = document.sensors.proximityGpio
    override val lightTech = document.sensors.lightTechnology
    override val hasCht8305 = document.sensors.cht8305
    override val hasVi530x = document.sensors.vi530x
    override val roomTempOffsetC = document.sensors.roomTempOffsetC
    override val manufacturer = document.identity.manufacturer
    override val model = document.identity.model
    override val evdevButtons = document.input.evdevButtons.map {
        EvdevButton(it.node, it.code, it.grab, it.eventType, it.sw)
    }
    override val cpuGovernors = document.cpu.governors
    override fun displayGeometry(physicalWidthPx: Int, physicalHeightPx: Int) =
        DisplayGeometryResolver.resolve(document.display, productVersion, physicalWidthPx, physicalHeightPx)
    override val provisioning = ProvisioningIntent(
        shizuku = document.provisioning.access.shizuku,
        webViewArtifactId = document.provisioning.software.webView?.artifact,
        companionMaxVersion = document.provisioning.software.companion?.maxVersion,
        density = when (val density = document.provisioning.display.density) {
            is ProfileDensity.Fixed -> density.value
            is ProfileDensity.Strategy -> when (density.id) {
                "nspanel-variant" -> if ("120" in productVersion.substringBefore('_')) 250 else 160
                else -> null
            }
            null -> null
        },
        fontScale = document.provisioning.display.fontScale,
        packages = document.provisioning.packages.map {
            PackageIntent(
                packageName = it.packageName,
                desiredState = when (it.desiredState) {
                    ProfilePackageDesiredState.DISABLED -> PackageDesiredState.DISABLED
                },
                importance = when (it.importance) {
                    // Imported authors may recommend packages in the preview, but that declaration must
                    // not feed the privileged "Tame all recommended" action.
                    ProfileProvisioningImportance.RECOMMENDED -> if (trustedBundledContent) {
                        ProvisioningImportance.RECOMMENDED
                    } else {
                        ProvisioningImportance.OPTIONAL
                    }
                    ProfileProvisioningImportance.OPTIONAL -> ProvisioningImportance.OPTIONAL
                },
                tags = it.tags,
                note = it.note,
            )
        },
        recipeIds = document.provisioning.recipes.mapTo(linkedSetOf()) { it.id },
    )

    override fun panelModelLabel(productVersion: String): String =
        if (document.identity.modelLabelStrategy == "nspanel-product-version") {
            when {
                productVersion.startsWith(S6_VERSION_PREFIX, ignoreCase = true) ->
                    "NSPanel 86P" + nspanelFirmwareVersion(productVersion)?.let { " · fw $it" }.orEmpty()
                productVersion.startsWith("NSPanel", ignoreCase = true) -> {
                    val suffix = productVersion.substringBefore('_').drop("NSPanel".length)
                    if (suffix.isBlank()) displayName
                    else "NSPanel $suffix" + nspanelFirmwareVersion(productVersion)?.let { " · fw $it" }.orEmpty()
                }
                else -> displayName
            }
        } else {
            displayName
        }

    private fun nspanelFirmwareVersion(value: String): String? = when {
        value.startsWith(S6_VERSION_PREFIX, ignoreCase = true) -> value.drop(S6_VERSION_PREFIX.length)
        value.startsWith("NSPanel", ignoreCase = true) -> value.substringAfter('_', "")
        else -> ""
    }.ifBlank { null }

    private companion object {
        const val S6_VERSION_PREFIX = "s6_android_"
    }
}

internal fun ProfileDocument.matchedGroupPriority(rawFacts: DeviceFacts): Int? {
    if (match.fallback) return null
    val facts = rawFacts.normalized()
    return match.any.filter { group ->
        group.all.all { predicate ->
            val actual = when (predicate.field) {
                ProfileFact.MODEL -> facts.model
                ProfileFact.DEVICE -> facts.device
                ProfileFact.PRODUCT_VERSION -> facts.productVersion
            }
            predicate.values.any { expected ->
                when (predicate.op) {
                    ProfileMatchOp.EQUALS -> actual == expected
                    ProfileMatchOp.STARTS_WITH -> actual.startsWith(expected)
                    ProfileMatchOp.CONTAINS -> expected in actual
                }
            }
        }
    }.maxOfOrNull { it.priority }
}

internal fun ProfileDocument.matches(rawFacts: DeviceFacts): Boolean = matchedGroupPriority(rawFacts) != null

/** The validator refuses an invalid curve before a profile is activated; this only keeps the fallback explicit. */
private fun validCurve(curve: ProfileLightCurve?): TransferCurve =
    curve?.let { runCatching { it.toTransferCurve() }.getOrNull() } ?: TransferCurve.Identity
