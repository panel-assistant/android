package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home Assistant screen command: a missing brightness stays absent, and an ON is sequenced so a
 * lit panel is never re-woken while an explicit brightness is still applied.
 */
class ScreenCommandPolicyTest {

    // --- parse: absent is absent ---
    @Test fun bareOnCarriesNoBrightness() {
        val command = ScreenCommandPolicy.parse("""{"state":"ON"}""")
        assertTrue(command.on)
        assertNull("a missing brightness must not become zero or a default", command.brightness)
    }

    @Test fun jsonNullBrightnessIsAbsentNotZero() {
        assertNull(ScreenCommandPolicy.parse("""{"state":"ON","brightness":null}""").brightness)
    }

    @Test fun explicitBrightnessIsFlooredAndCapped() {
        assertEquals(BrightnessController.MIN_VISIBLE, ScreenCommandPolicy.parse("""{"state":"ON","brightness":0}""").brightness)
        assertEquals(255, ScreenCommandPolicy.parse("""{"state":"ON","brightness":900}""").brightness)
        assertEquals(128, ScreenCommandPolicy.parse("""{"state":"on","brightness":128}""").brightness)
    }

    @Test fun offIsOffWhateverElseIsSent() {
        assertFalse(ScreenCommandPolicy.parse("""{"state":"OFF","brightness":200}""").on)
        assertFalse(ScreenCommandPolicy.parse("""{"state":"off"}""").on)
    }

    @Test fun missingStateMeansOn() {
        assertTrue(ScreenCommandPolicy.parse("""{"brightness":90}""").on)
    }

    // --- executeOn sequencing ---
    private class Run(outcome: WakeOutcome, commanded: Int = 180) {
        val log = mutableListOf<String>()
        val delivered: (ScreenCommandPolicy.Command) -> Boolean = { command ->
            ScreenCommandPolicy.executeOn(
                command,
                ensureOn = { log += "ensureOn"; outcome },
                setBrightness = { log += "set:$it" },
                commandedLevel = { log += "commanded"; commanded },
                noteLevel = { log += "note:$it" },
                publish = { log += "publish:$it" },
            )
        }
    }

    @Test fun alreadyOnBareOnPublishesTheCommandedLevelAndWritesNothing() {
        val run = Run(WakeOutcome.ALREADY_ON, commanded = 180)
        assertTrue(run.delivered(ScreenCommandPolicy.Command(on = true, brightness = null)))
        assertEquals(listOf("ensureOn", "commanded", "note:180", "publish:180"), run.log)
    }

    @Test fun alreadyOnExplicitBrightnessIsStillApplied() {
        val run = Run(WakeOutcome.ALREADY_ON)
        assertTrue(run.delivered(ScreenCommandPolicy.Command(on = true, brightness = 42)))
        assertEquals(listOf("ensureOn", "set:42", "note:42", "publish:42"), run.log)
    }

    @Test fun trueOffToOnBareRestoresThenPublishesTheCommandedLevel() {
        val run = Run(WakeOutcome.WOKEN, commanded = 90)
        assertTrue(run.delivered(ScreenCommandPolicy.Command(on = true, brightness = null)))
        assertEquals(listOf("ensureOn", "commanded", "note:90", "publish:90"), run.log)
    }

    @Test fun bareOnNeverPublishesZeroEvenWhenNothingWasCommanded() {
        val run = Run(WakeOutcome.WOKEN, commanded = 0)
        assertTrue(run.delivered(ScreenCommandPolicy.Command(on = true, brightness = null)))
        assertEquals(listOf("ensureOn", "commanded", "note:1", "publish:1"), run.log)
    }

    @Test fun controllerFailureAppliesAndPublishesNothing() {
        val run = Run(WakeOutcome.ACTUATION_FAILED)
        assertFalse(run.delivered(ScreenCommandPolicy.Command(on = true, brightness = 42)))
        assertEquals(listOf("ensureOn"), run.log)
    }
}
