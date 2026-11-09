package io.panelassistant.android.audio

import android.media.MediaRecorder
import io.panelassistant.android.Config
import io.panelassistant.android.assist.ConfiguredMicrophoneSource
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.device.profile.DataDeviceProfile
import io.panelassistant.android.device.profile.testProfileDocument
import io.panelassistant.android.fakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which capture route a panel opens: the owner's setting, else the profile's `hardware.microphone_capture`
 * block, else the built-in 16 kHz mono route. Read through the real [Config] with a profile attached, the
 * way the service resolves it.
 */
class CaptureRouteResolutionTest {

    private fun profileWith(capture: MicrophoneCaptureDeclaration?): DataDeviceProfile {
        val base = testProfileDocument()
        return DataDeviceProfile(
            document = base.copy(hardware = base.hardware.copy(microphoneDeclared = true, microphoneCapture = capture)),
            productVersion = "",
            revision = "test",
            trustedBundledContent = true,
        )
    }

    private fun routeOf(settings: Map<String, String>, capture: MicrophoneCaptureDeclaration?): CaptureRoute {
        val config = Config(fakePreferences(initial = settings).instance)
        val profile = profileWith(capture)
        config.attachProfile(profile)
        return ConfiguredMicrophoneSource.routeFor(config, profile.microphoneCapture)
    }

    @Test fun noBlockAndAutomaticSettingsOpenTodaysRouteWithNothingAttached() {
        val route = routeOf(emptyMap(), null)
        assertEquals(CaptureRoute(), route)
        assertTrue("the default route is handed to the fan-out unconverted", route.passthrough)
    }

    @Test fun theProfileBlockChoosesTheRouteAndItsDefaults() {
        val route = routeOf(
            emptyMap(),
            MicrophoneCaptureDeclaration(source = "mic", sampleRateHz = 48_000, channels = 2, downmix = "average"),
        )
        assertEquals(
            CaptureRoute(
                source = MediaRecorder.AudioSource.MIC,
                sampleRateHz = 48_000,
                channels = 2,
                downmix = CaptureDownmix.AVERAGE,
                warmupReads = CaptureRoute.DEFAULT_WARMUP_READS,
                platformAec = PlatformAecMode.AUTO,
            ),
            route,
        )
    }

    @Test fun anOwnerSettingOverridesTheProfileField() {
        val route = routeOf(
            mapOf(
                "voice_audio_source" to "voice_recognition",
                "voice_capture_rate" to "16000",
                "voice_capture_channels" to "1",
                "voice_capture_downmix" to "left",
            ),
            MicrophoneCaptureDeclaration(source = "mic", sampleRateHz = 48_000, channels = 2, downmix = "average", platformAec = "off"),
        )
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, route.source)
        assertEquals(16_000, route.sampleRateHz)
        assertEquals(1, route.channels)
        assertEquals(CaptureDownmix.LEFT, route.downmix)
        assertEquals(PlatformAecMode.OFF, route.platformAec)
    }

    @Test fun settingsAloneCanTryARouteWithoutAProfileBlock() {
        val route = routeOf(mapOf("voice_capture_rate" to "48000", "voice_capture_channels" to "2"), null)
        assertEquals(48_000, route.sampleRateHz)
        assertEquals(2, route.channels)
        assertEquals(CaptureDownmix.DOMINANCE, route.downmix)
        assertEquals("a chosen route discards its warm-up", CaptureRoute.DEFAULT_WARMUP_READS, route.warmupReads)
        assertEquals("no block, no platform echo canceller", PlatformAecMode.OFF, route.platformAec)
    }

    @Test fun theProfileSourceIsTheAudioSourceSettingsDefault() {
        val config = Config(fakePreferences().instance)
        config.attachProfile(profileWith(MicrophoneCaptureDeclaration(source = "voice_communication")))
        assertEquals("voice_communication", config.effectiveDefault(SettingsRegistry.spec("voice_audio_source")!!))
        assertEquals("voice_communication", config.voiceAudioSource)
    }
}
