package io.panelassistant.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityTelemetryAccumulatorTest {
    private val target = EntityTelemetryTarget(1, "instance-a", "/dashboard")

    @Test fun repeatedRendererBatchesCoalesceAndSaturateInConstantSpace() {
        val accumulator = EntityTelemetryAccumulator(2)
        assertEquals(
            EntityTelemetryAdmission(backlog = 2, coalesced = 0, dropped = 1),
            accumulator.admit(
                target,
                newAccessed = linkedMapOf("light.a" to Long.MAX_VALUE, "light.b" to 2),
                newMissing = setOf("sensor.overflow"),
                newMetrics = emptyMap(),
            ),
        )
        assertEquals(
            EntityTelemetryAdmission(backlog = 2, coalesced = 1, dropped = 1),
            accumulator.admit(
                target,
                newAccessed = mapOf("light.a" to 2),
                newMissing = emptySet(),
                newMetrics = mapOf("light.b" to (Long.MAX_VALUE to Long.MAX_VALUE), "sensor.overflow" to (1L to 1L)),
            ),
        )

        val batch = accumulator.drain()!!
        assertEquals(Long.MAX_VALUE, batch.accessed.getValue("light.a"))
        assertEquals(Long.MAX_VALUE, batch.metrics.getValue("light.b").first)
        assertEquals(Long.MAX_VALUE, batch.metrics.getValue("light.b").second)
        assertEquals(2, batch.uniqueIds)
        assertNull(accumulator.drain())
    }

    @Test fun targetChangeDropsOldEvidenceAndDrainOrDiscardClearsTheBacklog() {
        val accumulator = EntityTelemetryAccumulator(4)
        accumulator.admit(target, mapOf("light.old" to 1), emptySet(), emptyMap())
        val replacement = target.copy(generation = 2, path = "/other")

        val admission = accumulator.admit(replacement, emptyMap(), setOf("light.new"), emptyMap())
        assertEquals(1L, admission.dropped)
        assertEquals(replacement, accumulator.drain()!!.target)
        assertEquals(0, accumulator.size())

        accumulator.admit(replacement, emptyMap(), emptySet(), mapOf("sensor.one" to (1L to 2L)))
        assertEquals(1, accumulator.discard())
        assertTrue(accumulator.size() == 0)
        assertNull(accumulator.drain())
    }

    @Test fun maintenanceGateAdmitsInitiallyAtIntervalAndAfterClockRollback() {
        val gate = MaintenanceIntervalGate(100)
        assertTrue(gate.admit(1_000))
        assertTrue(!gate.admit(1_099))
        assertTrue(gate.admit(1_100))
        assertTrue(gate.admit(900))
    }
}
