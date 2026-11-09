package io.panelassistant.android.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Opening a route never leaves the panel deaf when another route would work: the route's source, then
 * `VOICE_RECOGNITION`, then `MIC`, each in the route's format and then in 16 kHz mono. A lost permission
 * ends the ladder at once. And the platform echo canceller is kept only when it reads back as enabled.
 */
class CaptureOpenLadderTest {

    private val vr = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val mic = MediaRecorder.AudioSource.MIC
    private val vc = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    private data class Attempt(val source: Int, val rate: Int, val channels: Int)

    private fun ladder(route: CaptureRoute, accept: (Attempt) -> Boolean): Pair<BuiltRecord<Attempt>?, List<Attempt>> {
        val tried = ArrayList<Attempt>()
        val built = openRecorderLadder(route, { source, rate, channels ->
            Attempt(source, rate, channels).also(tried::add).takeIf(accept)
        }, log = {})
        return built to tried
    }

    @Test fun theRouteOpensAsAskedWhenThePlatformAcceptsIt() {
        val (built, tried) = ladder(CaptureRoute(source = vc, sampleRateHz = 48_000, channels = 2)) { true }
        assertEquals(RecorderFormat(vc, 48_000, 2), built?.format)
        assertEquals(1, tried.size)
    }

    @Test fun aRefusedFormatFallsBackToSixteenKilohertzMonoOnTheSameSource() {
        val (built, tried) = ladder(CaptureRoute(sampleRateHz = 44_100, channels = 2)) { it.rate == 16_000 }
        assertEquals(RecorderFormat(vr, 16_000, 1), built?.format)
        assertEquals(listOf(Attempt(vr, 44_100, 2), Attempt(vr, 16_000, 1)), tried)
    }

    @Test fun aRefusedSourceFallsBackToVoiceRecognitionThenMicWithoutRepeats() {
        val (built, tried) = ladder(CaptureRoute(source = vc)) { it.source == mic }
        assertEquals(RecorderFormat(mic, 16_000, 1), built?.format)
        assertEquals(listOf(Attempt(vc, 16_000, 1), Attempt(vr, 16_000, 1), Attempt(mic, 16_000, 1)), tried)

        val (_, fromVr) = ladder(CaptureRoute(source = vr, sampleRateHz = 48_000, channels = 2)) { false }
        assertEquals(
            "a source already tried is not tried twice",
            listOf(Attempt(vr, 48_000, 2), Attempt(vr, 16_000, 1), Attempt(mic, 48_000, 2), Attempt(mic, 16_000, 1)),
            fromVr,
        )
    }

    @Test fun aSecurityExceptionEndsTheLadder() {
        val tried = ArrayList<Int>()
        val built = openRecorderLadder<Any>(CaptureRoute(source = vc, sampleRateHz = 48_000), { source, _, _ ->
            tried += source
            throw SecurityException("RECORD_AUDIO revoked")
        }, log = {})
        assertNull(built)
        assertEquals("nothing after the refusal is tried", listOf(vc), tried)
    }

    @Test fun anyOtherConstructionFailureMovesToTheNextRung() {
        var calls = 0
        val built = openRecorderLadder(CaptureRoute(sampleRateHz = 48_000, channels = 2), { _, rate, _ ->
            calls += 1
            if (rate == 48_000) throw IllegalArgumentException("bad format") else "record"
        }, log = {})
        assertEquals(RecorderFormat(vr, 16_000, 1), built?.format)
        assertEquals(2, calls)
    }

    // ---- platform echo canceller --------------------------------------------------------------------

    private class FakeEffect(var reportsEnabled: Boolean) : EchoCancellerEffect {
        var asked: Boolean? = null
        var released = 0

        override fun setEnabled(enabled: Boolean) {
            asked = enabled
        }

        override fun enabled(): Boolean = reportsEnabled

        override fun release() {
            released += 1
        }
    }

    private class FakePlatform(val available: Boolean, val effect: FakeEffect?) : EchoCancellerPlatform {
        var created = 0

        override fun isAvailable(): Boolean = available

        override fun create(sessionId: Int): EchoCancellerEffect? {
            created += 1
            return effect
        }
    }

    @Test fun anAvailableCancellerThatReadsBackEnabledIsArmedAndReadLive() {
        val effect = FakeEffect(reportsEnabled = true)
        val armed = armPlatformAec(PlatformAecMode.AUTO, sessionId = 7, appSideCanceller = false, FakePlatform(true, effect))
        assertEquals(true, effect.asked)
        assertEquals(AecState.ARMED, armed.state())

        // The platform may disable it later; the report says so rather than repeating what it once read.
        effect.reportsEnabled = false
        assertEquals(AecState.READBACK_FALSE, armed.state())

        armed.release()
        assertEquals(1, effect.released)
    }

    @Test fun aCancellerThatReadsBackDisabledIsReleasedNotKept() {
        val effect = FakeEffect(reportsEnabled = false)
        val armed = armPlatformAec(PlatformAecMode.ON, sessionId = 7, appSideCanceller = false, FakePlatform(true, effect))
        assertEquals(AecState.READBACK_FALSE, armed.state())
        assertEquals("a disabled effect is not a working canceller", 1, effect.released)
        armed.release()
        assertEquals("and is never released twice", 1, effect.released)
    }

    @Test fun nothingIsCreatedWhenTheCancellerIsOffUnavailableOrOwnedByTheApp() {
        val platform = FakePlatform(true, FakeEffect(true))
        assertEquals(AecState.REQUESTED_OFF, armPlatformAec(PlatformAecMode.OFF, 7, false, platform).state())
        assertEquals(AecState.REQUESTED_OFF, armPlatformAec(PlatformAecMode.ON, 7, appSideCanceller = true, platform).state())
        assertEquals(AecState.UNAVAILABLE, armPlatformAec(PlatformAecMode.AUTO, 0, false, platform).state())
        assertEquals(0, platform.created)

        val unavailable = FakePlatform(false, FakeEffect(true))
        assertEquals(AecState.UNAVAILABLE, armPlatformAec(PlatformAecMode.AUTO, 7, false, unavailable).state())
        assertEquals(AecState.UNAVAILABLE, armPlatformAec(PlatformAecMode.ON, 7, false, unavailable).state())
        assertEquals(0, unavailable.created)

        assertEquals(AecState.UNAVAILABLE, armPlatformAec(PlatformAecMode.AUTO, 7, false, FakePlatform(true, null)).state())
    }
}
