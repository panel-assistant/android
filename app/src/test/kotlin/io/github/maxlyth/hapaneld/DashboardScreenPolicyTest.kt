package io.github.maxlyth.hapaneld

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardScreenPolicyTest {
    @Test fun preventIdleDimOwnsTheBuiltInRendererWindowFlag() {
        assertTrue(shouldKeepBuiltInRendererScreenOn(preventIdleDim = true))
        assertFalse(shouldKeepBuiltInRendererScreenOn(preventIdleDim = false))
    }
}
