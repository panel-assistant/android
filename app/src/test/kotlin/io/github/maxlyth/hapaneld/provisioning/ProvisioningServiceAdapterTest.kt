package io.github.maxlyth.hapaneld.provisioning

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import io.github.maxlyth.hapaneld.device.profile.ProfileHelperAuthorityDemand
import io.github.maxlyth.hapaneld.device.profile.ProfileMetadata
import io.github.maxlyth.hapaneld.device.profile.ProfileOrigin
import io.github.maxlyth.hapaneld.device.profile.ProfileSummary
import io.github.maxlyth.hapaneld.device.profile.ResolvedProfile
import io.github.maxlyth.hapaneld.provisioning.ProvisioningImportance
import io.github.maxlyth.hapaneld.provisioning.provisioningHelperImportance
import io.github.maxlyth.hapaneld.provisioning.requiresProvisioningHelper
import io.github.maxlyth.hapaneld.provisioning.toProvisioningProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningServiceAdapterTest {
    @Test fun bundledHelperRequirementsAreAnIndependentProvisioningContract() {
        val actual = BundledProfileFixtures.bundled
            .filter { it.profile().requiresProvisioningHelper() }
            .mapTo(mutableSetOf()) { it.document.id }

        assertEquals(
            setOf("s9e", "smt1019", "tpa10", "wf1589t", "zx-smt156"),
            actual,
        )
    }

    @Test fun sandboxPanelsWithoutHelperDriversStillRecommendTheHelper() {
        val actual = BundledProfileFixtures.bundled
            .associate { it.document.id to it.profile().provisioningHelperImportance() }

        assertEquals(
            mapOf(
                "generic" to null,
                "nspanel-pro" to null,
                "s9e" to ProvisioningImportance.REQUIRED,
                "shelly-wall-display" to ProvisioningImportance.RECOMMENDED,
                "shelly-wall-display-v2" to ProvisioningImportance.RECOMMENDED,
                "shelly-wall-display-x2i" to ProvisioningImportance.RECOMMENDED,
                "smt1019" to ProvisioningImportance.REQUIRED,
                "tpa10" to ProvisioningImportance.REQUIRED,
                "wf1589t" to ProvisioningImportance.REQUIRED,
                "zx-smt156" to ProvisioningImportance.REQUIRED,
            ),
            actual,
        )
    }

    @Test fun everyCoreDriverHasThePinnedHelperAuthorityDemand() {
        val expected = mapOf(
            "access.android-su" to ProfileHelperAuthorityDemand.NONE,
            "access.toolbox-su" to ProfileHelperAuthorityDemand.NONE,
            "input.button-backlight" to ProfileHelperAuthorityDemand.REQUIRED,
            "input.evdev" to ProfileHelperAuthorityDemand.REQUIRED,
            "led.autodetect" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "led.rk3576-ioctl" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "led.rk3576-ioctl-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "led.sysfs-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "radio.siliconlabs-host" to ProfileHelperAuthorityDemand.NONE,
            "relay.sysfs" to ProfileHelperAuthorityDemand.NONE,
            "relay.gpio-button-led" to ProfileHelperAuthorityDemand.NONE,
            "screen.brightness-zero" to ProfileHelperAuthorityDemand.NONE,
            "screen.daemon-blpower" to ProfileHelperAuthorityDemand.REQUIRED,
            "screen.su-blpower" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            // Added 2026-08-17: the keyevent route injects through root where the app has it and the
            // helper daemon otherwise, so the helper is a fallback rather than a requirement.
            "screen.keyevent" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
            "sensor.android" to ProfileHelperAuthorityDemand.NONE,
            "sensor.cht8305-daemon" to ProfileHelperAuthorityDemand.SHIZUKU_ALTERNATE,
            // Added 2026-08-14: the VI530x range route is helper-only — there is no sandbox or
            // Shizuku path to a misc-device ioctl, so the demand is REQUIRED rather than an alternate.
            "sensor.vi530x-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
            "sensor.gpio-proximity" to ProfileHelperAuthorityDemand.REQUIRED,
            "update.webview" to ProfileHelperAuthorityDemand.NONE,
        )

        assertEquals(ProfileMetadata.drivers.map { it.id }.toSet(), expected.keys)
        assertEquals(expected, ProfileMetadata.helperAuthorityDemand)
    }

    @Test fun optimisticAppCanSuHintIsNotAdaptedAsObservedRootReadiness() {
        val source = BundledProfileFixtures.bundled.single { it.document.match.fallback }
        val fallback = source.profile()
        assertTrue(fallback.appCanSu)
        val adapted = ResolvedProfile(
            profile = fallback,
            summary = ProfileSummary(
                ref = io.github.maxlyth.hapaneld.device.profile.ProfileRef(source.document.id, source.rawSha256),
                displayName = source.document.displayName,
                origin = ProfileOrigin.BUNDLED,
                schema = source.document.schema,
                minCoreVersion = source.document.requires.minCoreVersion,
                matchesThisDevice = true,
                active = true,
                selected = true,
                shizukuRecommendation = source.document.provisioning.access.shizuku,
                contentVersion = source.document.version,
                maturity = source.document.metadata.maturity,
            ),
        ).toProvisioningProfile()

        assertFalse(adapted.directRootExpected)
    }
}
