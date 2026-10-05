package io.panelassistant.android.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetImportPolicyTest {
    @Test fun `blank fleet HA URL preserves target local login`() {
        assertTrue(fleetImportPreservesTargetLocalValue(fleet = true, key = "ha_url", normalized = ""))
        assertFalse(fleetImportPreservesTargetLocalValue(fleet = false, key = "ha_url", normalized = ""))
        assertFalse(fleetImportPreservesTargetLocalValue(fleet = true, key = "ha_url", normalized = "https://ha.example"))
    }
}
