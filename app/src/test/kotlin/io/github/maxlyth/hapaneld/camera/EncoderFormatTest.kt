package io.github.maxlyth.hapaneld.camera

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configure-time bitrate contract. The quantiser ceiling is the assertion that matters: without it
 * `c2.rk.avc.encoder` delivered 4716 kbps against a 1000 kbps target while holding a median QP of 22.
 */
class EncoderFormatTest {

    private fun keys(fps: Int = 15, bps: Int = 2_000_000, cbr: Boolean = false, sdkInt: Int = 34) =
        EncoderFormat.keys(fps = fps, bps = bps, cbr = cbr, sdkInt = sdkInt)

    @Test fun theBoundBitrateFrameRateAndIdrIntervalAreConfigured() {
        val k = keys(fps = 30, bps = 1_000_000)
        assertEquals(1_000_000, k[EncoderFormat.BIT_RATE])
        assertEquals(30, k[EncoderFormat.FRAME_RATE])
        assertEquals(EncoderFormat.IDR_INTERVAL_S, k[EncoderFormat.I_FRAME_INTERVAL])
    }

    @Test fun theWholeQuantiserRangeIsGrantedSoTheTargetIsReachableDownward() {
        val k = keys()
        assertEquals(51, EncoderFormat.QP_CEILING)
        for (key in listOf(EncoderFormat.QP_MAX, EncoderFormat.QP_I_MAX, EncoderFormat.QP_P_MAX, EncoderFormat.QP_B_MAX)) {
            assertEquals("$key must grant the whole range", EncoderFormat.QP_CEILING, k[key])
        }
    }

    @Test fun noQuantiserFloorIsImposed() {
        // A minimum is the vendor's: it costs quality nothing and is not this change's business.
        assertTrue(keys().keys.none { it.endsWith("-min") })
    }

    @Test fun theQuantiserKeysAreOmittedBelowTheApiThatCarriesThem() {
        val old = keys(sdkInt = 30)
        assertEquals(31, EncoderFormat.QP_KEYS_FROM_SDK)
        assertNull(old[EncoderFormat.QP_MAX])
        assertNull(old[EncoderFormat.QP_I_MAX])
        assertEquals("the bitrate is still configured", 2_000_000, old[EncoderFormat.BIT_RATE])
        assertEquals(EncoderFormat.QP_CEILING, keys(sdkInt = 31)[EncoderFormat.QP_MAX])
    }

    @Test fun constantBitrateIsRequestedOnlyWhenTheEncoderOffersIt() {
        assertNull(keys(cbr = false)[EncoderFormat.BITRATE_MODE])
        assertEquals(EncoderFormat.BITRATE_MODE_CBR, keys(cbr = true)[EncoderFormat.BITRATE_MODE])
    }

    @Test fun theKeysAreTheFrameworkSpellingsTheEncoderReads() {
        // A misspelled key is silently ignored by MediaFormat, so each one is checked against the
        // framework's own constant rather than against a second copy of the same guess.
        assertEquals(MediaFormat.KEY_BIT_RATE, EncoderFormat.BIT_RATE)
        assertEquals(MediaFormat.KEY_FRAME_RATE, EncoderFormat.FRAME_RATE)
        assertEquals(MediaFormat.KEY_I_FRAME_INTERVAL, EncoderFormat.I_FRAME_INTERVAL)
        assertEquals(MediaFormat.KEY_BITRATE_MODE, EncoderFormat.BITRATE_MODE)
        assertEquals(MediaFormat.KEY_VIDEO_QP_MAX, EncoderFormat.QP_MAX)
        assertEquals(MediaFormat.KEY_VIDEO_QP_I_MAX, EncoderFormat.QP_I_MAX)
        assertEquals(MediaFormat.KEY_VIDEO_QP_P_MAX, EncoderFormat.QP_P_MAX)
        assertEquals(MediaFormat.KEY_VIDEO_QP_B_MAX, EncoderFormat.QP_B_MAX)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, EncoderFormat.BITRATE_MODE_CBR)
    }

    @Test fun nothingElseIsConfiguredHereSoTheSessionCarriesNoHiddenPolicy() {
        assertFalse(keys().containsKey("quality"))
        assertEquals(setOf(EncoderFormat.BIT_RATE, EncoderFormat.FRAME_RATE, EncoderFormat.I_FRAME_INTERVAL), keys(sdkInt = 30).keys)
        assertEquals(
            setOf(
                EncoderFormat.BIT_RATE, EncoderFormat.FRAME_RATE, EncoderFormat.I_FRAME_INTERVAL, EncoderFormat.BITRATE_MODE,
                EncoderFormat.QP_MAX, EncoderFormat.QP_I_MAX, EncoderFormat.QP_P_MAX, EncoderFormat.QP_B_MAX,
            ),
            keys(cbr = true).keys,
        )
    }
}
