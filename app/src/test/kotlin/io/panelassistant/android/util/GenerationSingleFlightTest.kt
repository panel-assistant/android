package io.panelassistant.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSingleFlightTest {
    @Test fun `one claim is admitted and close invalidates a posted callback`() {
        val gate = GenerationSingleFlight()
        val token = gate.claim()
        assertNotNull(token)
        assertNull(gate.claim())
        assertTrue(gate.isCurrent(token!!))
        gate.close()
        assertFalse(gate.isCurrent(token))
        assertNull(gate.claim())
        gate.finish(token)
    }

    @Test fun `completion permits a fresh generation`() {
        val gate = GenerationSingleFlight()
        val first = gate.claim()!!
        gate.finish(first)
        val second = gate.claim()!!
        assertTrue(second > first)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
