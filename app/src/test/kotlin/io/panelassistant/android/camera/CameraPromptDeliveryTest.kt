package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lifecycle-order regression the fourth hold asked for: a signal that lands before the activity is
 * observably resumed — during `onResume` itself, or while paused — is held and delivered at the next
 * `ON_RESUME`, never dropped; a signal while resumed asks at once; nothing asks twice for one signal.
 */
class CameraPromptDeliveryTest {

    @Test fun aSignalBeforeResumeIsHeldAndDeliveredExactlyOnceAtResume() {
        var asked = 0
        val gate = CameraPromptDelivery { asked++ }
        gate.onSignal()
        assertEquals("not resumed yet: held, not dropped and not asked", 0, asked)
        assertTrue(gate.hasPending)
        gate.onResumed()
        assertEquals("delivered at the observably resumed point", 1, asked)
        assertFalse(gate.hasPending)
        gate.onResumed()
        assertEquals("a second resume does not ask again for the same signal", 1, asked)
    }

    @Test fun aSignalWhileResumedAsksAtOnce() {
        var asked = 0
        val gate = CameraPromptDelivery { asked++ }
        gate.onResumed()
        gate.onSignal()
        assertEquals(1, asked)
        assertFalse(gate.hasPending)
    }

    @Test fun aSignalWhilePausedWaitsForTheNextResume() {
        var asked = 0
        val gate = CameraPromptDelivery { asked++ }
        gate.onResumed()
        gate.onPaused()
        assertFalse(gate.isResumed)
        gate.onSignal()
        assertEquals("paused: held", 0, asked)
        gate.onResumed()
        assertEquals("asked on the resume that follows", 1, asked)
    }

    @Test fun theSignalThatArrivesDuringOnResumeIsTheCaseThatUsedToBeLost() {
        // Registering the prompt listener fires synchronously when an ask is already due, and that
        // happens before AndroidX reports RESUMED. Modelled here as: signal, then the ON_RESUME event.
        var asked = 0
        val gate = CameraPromptDelivery { asked++ }
        gate.onSignal()
        gate.onResumed()
        assertEquals(1, asked)
    }
}
