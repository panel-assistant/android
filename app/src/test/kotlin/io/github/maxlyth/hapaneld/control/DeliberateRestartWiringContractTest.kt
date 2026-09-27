package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Executes how a superseded reload is consumed together with its reason. */
class DeliberateRestartWiringContractTest {

    @Test fun supersededReloadIsConsumedWithItsReason() {
        BuiltinDashboard.requestExplicitReload(BuiltinDashboard.LEARNING_RELOAD_REASON)
        assertEquals(BuiltinDashboard.LEARNING_RELOAD_REASON, BuiltinDashboard.consumeSupersededReload())
        assertFalse(BuiltinDashboard.consumeReloadRequest())
        assertEquals("", BuiltinDashboard.consumeReloadReason())
    }
}
