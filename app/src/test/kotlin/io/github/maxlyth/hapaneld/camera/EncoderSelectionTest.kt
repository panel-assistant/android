package io.github.maxlyth.hapaneld.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The encoder choice is the resource contract in miniature: hardware only, and the bitrate cap is a ceiling. */
class EncoderSelectionTest {

    private val rockchip = EncoderCandidate("OMX.rk.video_encoder.avc", hardware = true, minBps = 1, maxBps = 10_000_000, sizeSupported = true)
    private val software = EncoderCandidate("c2.android.avc.encoder", hardware = false, minBps = 1, maxBps = 12_000_000, sizeSupported = true)

    @Test fun theFirstHardwareEncoderInPlatformOrderIsChosenAtTheRequestedBitrate() {
        val choice = EncoderSelection.choose(listOf(software, rockchip), kbps = 2_000)
        assertEquals(EncoderChoice.Chosen("OMX.rk.video_encoder.avc", bps = 2_000_000), choice)
    }

    @Test fun aSoftwareEncoderIsNeverAcceptableEvenWhenItIsTheOnlyOne() {
        assertEquals(EncoderChoice.Refused("no_hardware_encoder"), EncoderSelection.choose(listOf(software), kbps = 2_000))
        assertEquals(EncoderChoice.Refused("no_hardware_encoder"), EncoderSelection.choose(emptyList(), kbps = 2_000))
    }

    @Test fun theBitrateCapIsACeilingNotATarget() {
        val floorAbove = rockchip.copy(minBps = 3_000_000)
        assertEquals("refused rather than driven past the cap", EncoderChoice.Refused("bitrate_below_encoder_floor"), EncoderSelection.choose(listOf(floorAbove), kbps = 2_000))
        val small = rockchip.copy(maxBps = 1_000_000)
        assertEquals("less than asked is fine", EncoderChoice.Chosen(small.name, bps = 1_000_000), EncoderSelection.choose(listOf(small), kbps = 2_000))
    }

    @Test fun anEncoderThatCannotDoTheSizeIsRefusedWithThatReason() {
        val tooSmall = rockchip.copy(sizeSupported = false)
        assertEquals(EncoderChoice.Refused("size_unsupported"), EncoderSelection.choose(listOf(tooSmall), kbps = 2_000))
        val nextInOrder = rockchip.copy(name = "c2.rk.avc.encoder")
        assertEquals(EncoderChoice.Chosen("c2.rk.avc.encoder", bps = 2_000_000), EncoderSelection.choose(listOf(tooSmall, nextInOrder), kbps = 2_000))
    }

    @Test fun beforeApi29TheNameIsTheOnlyEvidenceOfHardware() {
        assertTrue(EncoderSelection.hardwareByName("OMX.rk.video_encoder.avc"))
        assertTrue(EncoderSelection.hardwareByName("c2.rk.avc.encoder"))
        assertFalse(EncoderSelection.hardwareByName("OMX.google.h264.encoder"))
        assertFalse(EncoderSelection.hardwareByName("c2.android.avc.encoder"))
        assertFalse(EncoderSelection.hardwareByName("c2.exynos.sw.avc.encoder"))
    }
}
