package io.panelassistant.android.camera

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The configure-time bitrate contract. Constant bitrate is the assertion that matters: in the variable
 * mode its own capability query left it in, `c2.rk.avc.encoder` delivered 4716 kbps against a 1000 kbps
 * target at 1080p15, and 1899 kbps against 2000 once the same key was set.
 */
class EncoderFormatTest {

    @Test fun theBoundBitrateFrameRateAndIdrIntervalAreConfigured() {
        val k = EncoderFormat.keys(fps = 30, bps = 1_000_000)
        assertEquals(1_000_000, k[EncoderFormat.BIT_RATE])
        assertEquals(30, k[EncoderFormat.FRAME_RATE])
        assertEquals("the IDR cadence a joining client waits for", 2, EncoderFormat.IDR_INTERVAL_S)
        assertEquals(EncoderFormat.IDR_INTERVAL_S, k[EncoderFormat.I_FRAME_INTERVAL])
    }

    @Test fun constantBitrateIsAskedOfEveryEncoderRatherThanOnlyOneThatDeclaresIt() {
        // The default is the whole point: nothing about the encoder is consulted before asking.
        assertEquals(EncoderFormat.BITRATE_MODE_CBR, EncoderFormat.keys(fps = 15, bps = 2_000_000)[EncoderFormat.BITRATE_MODE])
    }

    @Test fun theFallbackAfterARefusalLeavesTheModeToTheVendor() {
        val k = EncoderFormat.keys(fps = 15, bps = 2_000_000, constantBitrate = false)
        assertNull(k[EncoderFormat.BITRATE_MODE])
        assertEquals("the target is still configured", 2_000_000, k[EncoderFormat.BIT_RATE])
    }

    @Test fun theKeysAreTheFrameworkSpellingsTheEncoderReads() {
        // A misspelled key is silently ignored by MediaFormat, so each one is checked against the
        // framework's own constant rather than against a second copy of the same guess.
        assertEquals(MediaFormat.KEY_BIT_RATE, EncoderFormat.BIT_RATE)
        assertEquals(MediaFormat.KEY_FRAME_RATE, EncoderFormat.FRAME_RATE)
        assertEquals(MediaFormat.KEY_I_FRAME_INTERVAL, EncoderFormat.I_FRAME_INTERVAL)
        assertEquals(MediaFormat.KEY_BITRATE_MODE, EncoderFormat.BITRATE_MODE)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, EncoderFormat.BITRATE_MODE_CBR)
    }

    @Test fun nothingElseIsConfiguredHereSoTheSessionCarriesNoHiddenPolicy() {
        assertFalse(EncoderFormat.keys(fps = 15, bps = 2_000_000).containsKey("quality"))
        assertEquals(
            setOf(EncoderFormat.BIT_RATE, EncoderFormat.FRAME_RATE, EncoderFormat.I_FRAME_INTERVAL, EncoderFormat.BITRATE_MODE),
            EncoderFormat.keys(fps = 15, bps = 2_000_000).keys,
        )
        assertEquals(
            setOf(EncoderFormat.BIT_RATE, EncoderFormat.FRAME_RATE, EncoderFormat.I_FRAME_INTERVAL),
            EncoderFormat.keys(fps = 15, bps = 2_000_000, constantBitrate = false).keys,
        )
    }
}
