package io.panelassistant.android.hardware

import io.panelassistant.android.hardware.LedEffects.Effect
import io.panelassistant.android.hardware.LedEffects.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedEffectsTest {

    @Test fun frameClampsInputsBeforeMultiplication() {
        assertEquals(Frame.Rgb(0, 128, 255), LedEffects.frame(Effect.STROBE, 0, -10, 128, 999, 999))
        assertEquals(Frame.Rgb(0, 0, 0), LedEffects.frame(Effect.STROBE, 0, 255, 255, 255, -1))
    }

    @Test fun effectNameMapping() {
        assertEquals(Effect.STROBE, Effect.from("strobe"))
        assertEquals(Effect.BLINK, Effect.from("BLINK"))    // case-insensitive
        assertEquals(Effect.PULSE, Effect.from(" pulse "))  // trimmed
        assertNull(Effect.from("none"))
        assertNull(Effect.from(""))
        assertNull(Effect.from(null))
        assertNull(Effect.from("rainbow"))
    }

    @Test fun strobeAndBlinkAlternateLitAndOff() {
        for (fx in listOf(Effect.STROBE, Effect.BLINK)) {
            assertEquals(Frame.Rgb(255, 0, 0), LedEffects.frame(fx, 0, 255, 0, 0, 255))
            assertEquals(Frame.Off, LedEffects.frame(fx, 1, 255, 0, 0, 255))
            assertEquals(Frame.Rgb(255, 0, 0), LedEffects.frame(fx, 2, 255, 0, 0, 255))
            assertEquals(Frame.Off, LedEffects.frame(fx, 3, 255, 0, 0, 255))
        }
    }

    @Test fun litFramesBakeBrightnessIntoColour() {
        // half brightness scales each channel (255 * 128 / 255 = 128)
        assertEquals(Frame.Rgb(128, 0, 0), LedEffects.frame(Effect.STROBE, 0, 255, 0, 0, 128))
    }

    @Test fun pulseRampsUpThenDown() {
        val half = LedEffects.PULSE_STEPS / 2
        assertEquals(0, brightnessOf(LedEffects.frame(Effect.PULSE, 0, 0, 0, 255, 255)))            // trough
        assertEquals(255, brightnessOf(LedEffects.frame(Effect.PULSE, half, 0, 0, 255, 255)))       // peak
        assertEquals(0, brightnessOf(LedEffects.frame(Effect.PULSE, LedEffects.PULSE_STEPS, 0, 0, 255, 255))) // back to trough
        // non-decreasing over the first half of the cycle
        var prev = -1
        for (s in 0..half) {
            val v = brightnessOf(LedEffects.frame(Effect.PULSE, s, 0, 0, 255, 255))
            assertTrue("pulse should rise to the peak", v >= prev)
            prev = v
        }
    }

    @Test fun pulseCycleIsPeriodic() {
        val a = LedEffects.frame(Effect.PULSE, 5, 10, 20, 30, 200)
        val b = LedEffects.frame(Effect.PULSE, 5 + LedEffects.PULSE_STEPS, 10, 20, 30, 200)
        assertEquals(a, b)
    }

    @Test fun stepPeriodsAreSane() {
        assertTrue(Effect.STROBE.stepMs in 50..150)
        assertTrue(Effect.BLINK.stepMs >= 300)
        // Fast enough (≈25 Hz) that the breath doesn't skip 4-bit levels, but ≥ 40 ms so a daemon-backed
        // LED stays ≲ 25 socket writes/s during the effect.
        assertTrue(Effect.PULSE.stepMs in 40..60)
    }

    @Test fun pulseIsSymmetricNoSawtooth() {
        // The fade in and fade out must take equal time — the VISIBLE 4-bit level at phase p equals the
        // level at PULSE_STEPS - p — so the breath never reads as a slow-fade / fast-rise sawtooth. (We
        // compare the hardware level, not the raw 0..255 value: cos(π/2) vs cos(3π/2) differ by 1 ULP, a
        // ±1 wobble at the half-brightness crossing that vanishes once quantised to 16 levels.)
        for (p in 0..LedEffects.PULSE_STEPS) {
            val up = LedTransfer.Rk3576FourBit.toHardware(brightnessOf(LedEffects.frame(Effect.PULSE, p, 0, 0, 255, 255)))
            val down = LedTransfer.Rk3576FourBit.toHardware(brightnessOf(LedEffects.frame(Effect.PULSE, LedEffects.PULSE_STEPS - p, 0, 0, 255, 255)))
            assertEquals("pulse asymmetric at phase $p", up, down)
        }
    }

    @Test fun pulseDoesNotSkipLevels() {
        // At 25 Hz the cosine ramp walks the 4-bit LED one level at a time — no lurching over the up-ramp.
        var prev = -1
        for (s in 0..(LedEffects.PULSE_STEPS / 2)) {
            val v = LedTransfer.Rk3576FourBit.toHardware(brightnessOf(LedEffects.frame(Effect.PULSE, s, 0, 0, 255, 255)))
            if (prev >= 0) assertTrue("4-bit level jumped >1 at step $s ($prev→$v)", v - prev <= 1)
            prev = v
        }
    }

    private fun brightnessOf(f: Frame): Int = when (f) {
        is Frame.Rgb -> maxOf(f.r, f.g, f.b)
        Frame.Off -> 0
    }
}
