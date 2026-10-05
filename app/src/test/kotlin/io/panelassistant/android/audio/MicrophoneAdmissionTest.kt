package io.panelassistant.android.audio

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneAdmissionTest {

    private val silentConsumer = object : PcmConsumer {
        override fun onFrame(frame: PcmFrame) = Unit
    }

    @After
    fun restoreDefaults() {
        MicrophoneAdmission.reset()
    }

    // ---- the WebView opt-in --------------------------------------------------------------------

    @Test
    fun webViewCaptureIsRefusedUntilAFeatureOwnsAnOptIn() {
        MicrophoneAdmission.reset()
        assertFalse(
            "provisioning grants RECORD_AUDIO to every panel, so the default here must be a refusal",
            MicrophoneAdmission.webViewCaptureAllowed(),
        )
    }

    @Test
    fun anIdleMicrophoneDoesNotAdmitTheWebView() {
        MicrophoneAdmission.reset()
        assertTrue("nothing holds the microphone", MicrophoneAdmission.isIdle())
        assertFalse(
            "being free is an opportunity, not a licence",
            MicrophoneAdmission.webViewCaptureAllowed(),
        )
    }

    @Test
    fun anOwningFeatureCanAdmitAndWithdrawWebViewCapture() {
        var optedIn = false
        MicrophoneAdmission.allowWebViewCaptureWhen { optedIn }
        assertFalse("the feature has not opted in", MicrophoneAdmission.webViewCaptureAllowed())
        optedIn = true
        assertTrue("the feature opted in", MicrophoneAdmission.webViewCaptureAllowed())
        MicrophoneAdmission.reset()
        assertFalse("reset returns the panel to refusing", MicrophoneAdmission.webViewCaptureAllowed())
    }

    // ---- shared-microphone idleness ------------------------------------------------------------

    @Test
    fun anUnwiredPanelReportsTheMicrophoneIdle() {
        MicrophoneAdmission.reset()
        assertTrue("nothing can be holding a microphone nothing leases", MicrophoneAdmission.isIdle())
    }

    @Test
    fun anObservedSourceIsIdleOnlyWhileNoLeaseIsHeld() {
        val source = FakeMicrophoneSource()
        MicrophoneAdmission.observe(source)
        assertTrue("no lease has been taken", MicrophoneAdmission.isIdle())

        val lease = source.lease(MicPurpose.ASSIST, consumer = silentConsumer)
        assertFalse("a held lease makes the microphone busy", MicrophoneAdmission.isIdle())

        lease.close()
        assertTrue("releasing the last lease frees the microphone", MicrophoneAdmission.isIdle())
    }

    @Test
    fun aPausedLeaseStillHoldsTheMicrophone() {
        val source = FakeMicrophoneSource()
        MicrophoneAdmission.observe(source)
        val lease = source.lease(MicPurpose.WAKE_WORD, consumer = silentConsumer)
        lease.pause()
        assertFalse("a paused holder has not given the microphone back", MicrophoneAdmission.isIdle())
        lease.close()
    }
}
