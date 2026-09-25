package io.github.maxlyth.hapaneld

import android.Manifest
import android.webkit.PermissionRequest
import io.github.maxlyth.hapaneld.audio.MicrophoneAdmission
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The dashboard page gets neither capture device by default, for two different reasons.
 *
 * The camera is refused outright: one owner holds it and the panel HALs cannot share it with a
 * second. The microphone is shared with ha-paneld's own features and recording is something the
 * panel's owner has to have asked for — and since provisioning grants `RECORD_AUDIO` to every
 * panel, "we hold the permission" says nothing about intent. These tests pin the consequence: audio
 * capture is refused unless an explicit admission says otherwise, and that admission is refused by
 * default.
 */
class DashboardWebViewCaptureGrantsTest {

    private val allPermissionsHeld: (String) -> Boolean = { true }
    private val noPermissionsHeld: (String) -> Boolean = { false }
    private val audio = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
    private val video = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

    @After
    fun restoreAdmissionDefaults() {
        MicrophoneAdmission.reset()
    }

    private fun grants(
        requested: Array<String>,
        permissionHeld: (String) -> Boolean = allPermissionsHeld,
        idle: Boolean = true,
        allowed: Boolean = true,
    ) = webViewCaptureGrants(requested, permissionHeld, { idle }, { allowed })

    // ---- the opt-in is the gate, and it is shut ---------------------------------------------------

    @Test
    fun audioCaptureIsRefusedWithoutAnExplicitOptIn() {
        assertArrayEquals(
            "a granted Android permission is not a decision anybody made about this page",
            emptyArray<String>(),
            grants(audio, allowed = false),
        )
    }

    @Test
    fun theShippedAdmissionRefusesWebViewCapture() {
        MicrophoneAdmission.reset()
        assertFalse(
            "no feature owns a microphone opt-in yet, so the default must be a refusal",
            MicrophoneAdmission.webViewCaptureAllowed(),
        )
        assertArrayEquals(
            "the default admission denies audio capture even on an idle, fully permitted panel",
            emptyArray<String>(),
            webViewCaptureGrants(
                audio,
                allPermissionsHeld,
                MicrophoneAdmission.isIdle,
                MicrophoneAdmission.webViewCaptureAllowed,
            ),
        )
    }

    @Test
    fun anOptInAloneIsNotEnoughWhileTheMicrophoneIsHeld() {
        assertArrayEquals(
            "a page must not take the microphone away from a lease, opt-in or not",
            emptyArray<String>(),
            grants(audio, idle = false),
        )
    }

    @Test
    fun anOptInAloneIsNotEnoughWithoutTheAndroidPermission() {
        assertArrayEquals(
            emptyArray<String>(),
            grants(audio, permissionHeld = { it != Manifest.permission.RECORD_AUDIO }),
        )
    }

    @Test
    fun audioCaptureIsGrantedOnlyWithOptInPermissionAndAnIdleMicrophone() {
        assertArrayEquals(audio, grants(audio))
    }

    // ---- the camera is refused for its own reason ------------------------------------------------

    @Test
    fun videoCaptureIsRefusedOutrightHoweverPermittedThePageIs() {
        assertArrayEquals(
            "one owner holds the camera and the panel HALs cannot share it with a second",
            emptyArray<String>(),
            grants(video),
        )
    }

    @Test
    fun aMixedRequestKeepsOnlyTheAdmittedMicrophone() {
        assertArrayEquals(
            "the camera is dropped for its own reason, the microphone kept on its own terms",
            audio,
            grants(video + audio),
        )
    }

    @Test
    fun everythingIsRefusedWithoutPermissions() {
        assertArrayEquals(emptyArray<String>(), grants(video + audio, permissionHeld = noPermissionsHeld))
    }

    @Test
    fun anUnknownResourceIsNeverGranted() {
        assertArrayEquals(
            emptyArray<String>(),
            grants(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)),
        )
    }
}
