package io.panelassistant.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardScreenPolicyTest {
    @Test fun preventIdleDimOwnsTheBuiltInRendererWindowFlag() {
        assertTrue(shouldKeepBuiltInRendererScreenOn(preventIdleDim = true))
        assertFalse(shouldKeepBuiltInRendererScreenOn(preventIdleDim = false))
    }
}
