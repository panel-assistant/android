package io.github.maxlyth.hapaneld.metrics

import io.github.maxlyth.hapaneld.control.KioskController
import io.github.maxlyth.hapaneld.control.isCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PollingFeatureCostContractTest {
    @Test fun recurringPollersHaveDistinctFixedCostKeys() {
        assertEquals("kiosk.state_poll", FeatureCostOperation.KIOSK_STATE_POLL.id)
        assertEquals("zigbee.health_sample", FeatureCostOperation.ZIGBEE_HEALTH_SAMPLE.id)
    }

    @Test fun experimentalKioskPollingUsesItsReducedCadence() {
        assertEquals(3_000L, KioskController.RETURN_POLL_MS)
    }

    @Test fun kioskPollGenerationCannotReviveAfterOffOnTransition() {
        val generation = AtomicLong(1)
        val old = generation.get()
        assertTrue(generation.isCurrent(old, enabled = true))
        generation.incrementAndGet()
        assertFalse(generation.isCurrent(old, enabled = true))
        assertFalse(generation.isCurrent(generation.get(), enabled = false))
        assertEquals(6_000L, KioskController.RETURN_STOP_JOIN_MS)
    }

}
