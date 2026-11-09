package io.panelassistant.android.device.profile

import io.panelassistant.android.audio.MicrophoneCaptureDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `hardware.microphone_capture`: a profile's capture route survives the YAML round trip, its values are
 * checked, and a profile without the block is written exactly as before the block existed.
 */
class MicrophoneCaptureProfileTest {

    private val declared = MicrophoneCaptureDeclaration(
        source = "voice_recognition",
        sampleRateHz = 48_000,
        channels = 2,
        downmix = "dominance",
        preferredDevice = "builtin",
        warmupReads = 12,
        platformAec = "auto",
    )

    private fun withCapture(capture: MicrophoneCaptureDeclaration?): ProfileDocument {
        val base = testProfileDocument()
        return base.copy(hardware = base.hardware.copy(microphoneDeclared = true, microphoneCapture = capture))
    }

    private fun issuesOf(yaml: String): List<ProfileIssue> {
        val parsed = ProfileYaml.parse(yaml)
        return parsed.issues + (parsed.document?.let { ProfileValidator.validate(it, "1.0.0", bundled = false) } ?: emptyList())
    }

    @Test fun aDeclaredRouteSurvivesSerialiseAndParse() {
        val yaml = ProfileYaml.serialize(withCapture(declared))
        assertTrue("the block is written under hardware", yaml.contains("  microphone_capture:\n"))
        val parsed = ProfileYaml.parse(yaml)
        assertEquals(emptyList<ProfileIssue>(), parsed.issues)
        assertEquals(declared, parsed.document?.hardware?.microphoneCapture)
        assertEquals(emptyList<ProfileIssue>(), ProfileValidator.validate(parsed.document!!, "1.0.0", bundled = false))
    }

    @Test fun theDeclarationReachesTheResolvedProfile() {
        val profile = DataDeviceProfile(document = withCapture(declared), productVersion = "", revision = "test", trustedBundledContent = true)
        assertEquals(declared, profile.microphoneCapture)
    }

    @Test fun aProfileWithoutTheBlockIsWrittenWithoutIt() {
        val yaml = ProfileYaml.serialize(withCapture(null))
        assertFalse(yaml.contains("microphone_capture"))
        assertNull(ProfileYaml.parse(yaml).document?.hardware?.microphoneCapture)
    }

    @Test fun theBlockRejectsUnknownFields() {
        val yaml = ProfileYaml.serialize(withCapture(declared))
            .replace("    platform_aec: auto\n", "    platform_aec: auto\n    gain_db: 15\n")
        val parsed = ProfileYaml.parse(yaml)
        assertNull(parsed.document)
        assertTrue(parsed.issues.toString(), parsed.issues.any { it.path == "hardware.microphone_capture.gain_db" && it.message == "Unknown field." })
    }

    @Test fun everyValueIsChecked() {
        val cases = mapOf(
            "hardware.microphone_capture.source" to declared.copy(source = "camcorder"),
            "hardware.microphone_capture.sample_rate_hz" to declared.copy(sampleRateHz = 96_000),
            "hardware.microphone_capture.channels" to declared.copy(channels = 4),
            "hardware.microphone_capture.downmix" to declared.copy(downmix = "max"),
            "hardware.microphone_capture.preferred_device" to declared.copy(preferredDevice = "bluetooth"),
            "hardware.microphone_capture.warmup_reads" to declared.copy(warmupReads = 201),
            "hardware.microphone_capture.platform_aec" to declared.copy(platformAec = "always"),
        )
        cases.forEach { (path, capture) ->
            val issues = issuesOf(ProfileYaml.serialize(withCapture(capture)))
            assertEquals("$path: $issues", listOf(path), issues.map { it.path })
        }
    }

    @Test fun aRouteNeedsAMicrophone() {
        val document = withCapture(declared).let { it.copy(hardware = it.hardware.copy(microphoneDeclared = false)) }
        val issues = ProfileValidator.validate(document, "1.0.0", bundled = false)
        assertTrue(issues.toString(), issues.any { it.path == "hardware.microphone_capture" })
    }

    @Test fun theProfileEditorIsToldAboutEveryField() {
        val paths = ProfileMetadata.schema.fields.map { it.path }.filter { it.startsWith("hardware.microphone_capture.") }.toSet()
        assertEquals(
            setOf("source", "sample_rate_hz", "channels", "downmix", "preferred_device", "warmup_reads", "platform_aec")
                .map { "hardware.microphone_capture.$it" }.toSet(),
            paths,
        )
    }

    /**
     * Only panels with capture evidence declare a route, and each is provisional until the oracle run sets it:
     * the X2i and the TPA10 HALs open stereo, so the panel mixes the two channels itself. Resolved through
     * the bundled catalogue the APK ships, as the service resolves it.
     */
    @Test fun theBundledRoutesAreDeclaredOnlyWhereCaptureWasMeasured() {
        val declaring = BundledProfileFixtures.bundled.filter { it.document.hardware.microphoneCapture != null }.map { it.document.id }.toSet()
        assertEquals(setOf("shelly-wall-display-x2i", "tpa10"), declaring)
        for (id in declaring) {
            val route = io.panelassistant.android.audio.CaptureRoute.resolve(
                sourceSetting = null, rateSetting = "auto", channelsSetting = "auto", downmixSetting = "auto",
                declared = BundledProfileFixtures.profile(id).microphoneCapture,
            )
            assertEquals(
                id,
                io.panelassistant.android.audio.CaptureRoute(
                    sampleRateHz = 16_000,
                    channels = 2,
                    downmix = io.panelassistant.android.audio.CaptureDownmix.DOMINANCE,
                    warmupReads = io.panelassistant.android.audio.CaptureRoute.DEFAULT_WARMUP_READS,
                    platformAec = io.panelassistant.android.audio.PlatformAecMode.OFF,
                ),
                route,
            )
        }
        assertNull("the NSPanel Pro keeps Android's enhanced mono route", BundledProfileFixtures.profile("nspanel-pro").microphoneCapture)
    }
}
