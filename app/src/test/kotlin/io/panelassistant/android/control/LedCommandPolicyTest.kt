package io.panelassistant.android.control

import io.panelassistant.android.hardware.LedEffects
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedCommandPolicyTest {
    @Test fun commandClampsEveryPublicChannel() {
        val desired = LedCommandPolicy.command(
            """{"state":"ON","brightness":999,"color":{"r":-10,"g":128,"b":300},"effect":"pulse"}""",
            LedCommandPolicy.Desired(on = false),
        )

        assertEquals(255, desired.brightness)
        assertEquals(0, desired.red)
        assertEquals(128, desired.green)
        assertEquals(255, desired.blue)
        assertEquals(LedEffects.Effect.PULSE, desired.effect)
    }

    @Test fun omittedColorAndBrightnessReuseThePreviousSolidValues() {
        val previous = LedCommandPolicy.Desired(true, 70, 10, 20, 30)

        val desired = LedCommandPolicy.command("""{"state":"ON","effect":"blink"}""", previous)

        assertEquals(previous.copy(effect = LedEffects.Effect.BLINK), desired)
    }

    @Test fun offClearsPersistedColorAndEffect() {
        val desired = LedCommandPolicy.command(
            """{"state":"OFF"}""",
            LedCommandPolicy.Desired(true, 70, 10, 20, 30, LedEffects.Effect.STROBE),
        )

        assertFalse(desired.on)
        assertEquals("0,0,0,0,0", desired.storedColor())
        assertEquals("", desired.storedEffect())
    }

    @Test fun storedStateIsClampedAndMalformedStateFailsOff() {
        assertEquals(
            LedCommandPolicy.Desired(true, 255, 0, 20, 255, LedEffects.Effect.BLINK),
            LedCommandPolicy.stored("1,999,-2,20,300", "blink"),
        )
        assertFalse(LedCommandPolicy.stored("1,2,3", "pulse").on)
    }

    @Test fun stateIsUnknownUntilActuationAndEffectOwnershipAreConfirmed() {
        val effect = LedCommandPolicy.Desired(true, 70, 10, 20, 30, LedEffects.Effect.STROBE)

        assertNull(LedCommandPolicy.statePayload(effect, false, LedEffectController.Status.RUNNING))
        assertNull(LedCommandPolicy.statePayload(effect, true, LedEffectController.Status.FAILED))
        assertTrue(
            JSONObject(LedCommandPolicy.statePayload(effect, true, LedEffectController.Status.RUNNING)!!)
                .getString("effect") == "strobe",
        )
    }

    @Test fun confirmedOffAndSolidProduceBoundedState() {
        assertEquals(
            "OFF",
            JSONObject(
                LedCommandPolicy.statePayload(
                    LedCommandPolicy.Desired(on = false),
                    true,
                    LedEffectController.Status.IDLE,
                )!!,
            ).getString("state"),
        )
        val solid = LedCommandPolicy.Desired(true, 255, 1, 2, 3)
        val json = JSONObject(LedCommandPolicy.statePayload(solid, true, LedEffectController.Status.IDLE)!!)
        assertEquals(255, json.getInt("brightness"))
        assertEquals(3, json.getJSONObject("color").getInt("b"))
    }
}
