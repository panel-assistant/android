package io.github.maxlyth.hapaneld.metrics

import io.github.maxlyth.hapaneld.control.KioskController
import io.github.maxlyth.hapaneld.control.isCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    @Test fun recurringHotPathsUseAllocationFreeSynchronousMeasurements() {
        val operationsByFile = mapOf(
            "control/AutoBrightnessController.kt" to "AUTO_BRIGHTNESS_APPLY",
            "control/KioskController.kt" to "KIOSK_STATE_POLL",
            "control/RelayController.kt" to "RELAY_STATE_READ",
            "control/ZigbeeHealthMonitor.kt" to "ZIGBEE_HEALTH_SAMPLE",
        )
        for ((relative, operation) in operationsByFile) {
            val source = sequenceOf(
                File("src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
                File("app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            ).first(File::isFile).readText()
            assertTrue(
                "$relative must begin primitive timing",
                Regex("""beginSynchronous\s*\(\s*FeatureCostOperation\.$operation""").containsMatchIn(source),
            )
            assertTrue(
                "$relative must finish primitive timing",
                Regex("""finishSynchronous\s*\(\s*FeatureCostOperation\.$operation""").containsMatchIn(source),
            )
            assertFalse("$relative must not allocate a hot-path span", source.contains("span(FeatureCostOperation.$operation)"))
        }
    }
}
