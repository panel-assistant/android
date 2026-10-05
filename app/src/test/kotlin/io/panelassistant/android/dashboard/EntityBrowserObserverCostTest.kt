package io.panelassistant.android.dashboard

import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityBrowserObserverCostTest {
    @Test fun disabledMeasurementArmDoesNotInstallDashboardTrafficObserver() {
        assertFalse(shouldInstallDashboardTrafficObserver(
            featureCostsEnabled = false,
            filterLeasePresent = true,
        ))
        assertFalse(shouldInstallDashboardTrafficObserver(
            featureCostsEnabled = true,
            filterLeasePresent = false,
        ))
        assertTrue(shouldInstallDashboardTrafficObserver(
            featureCostsEnabled = true,
            filterLeasePresent = true,
        ))
    }

    @Test fun browserPressureIsReportedThroughFixedFeatureCostOperation() {
        val registry = FeatureCostRegistry({ 0L }, { -1L }, { 1L })

        recordBrowserObserverCosts(
            registry,
            EntityLearningProtocol.BrowserObserverCosts(
                frames = 4,
                entities = 7,
                frameChars = 8_192,
                parseMicros = 2_100,
                stringifyMicros = 900,
                dropped = 11,
                coalesced = 13,
            ),
        )

        val operations = JSONObject(registry.json()).getJSONArray("operations")
        val observer = (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == FeatureCostOperation.ENTITY_BROWSER_OBSERVER.id }
        assertEquals(1L, observer.getLong("calls"))
        assertEquals(0L, observer.getLong("wall_ns_total"))
        assertEquals(3_000_000L, observer.getLong("external_execution_ns_total"))
        assertEquals(1L, observer.getLong("external_execution_samples"))
        assertEquals(4L, observer.getLong("external_events"))
        assertEquals(8_192L, observer.getLong("external_input_chars"))
        assertEquals(7L, observer.getLong("work_units"))
        assertEquals(11L, observer.getLong("dropped"))
        assertEquals(13L, observer.getLong("coalesced"))
    }
}
