package io.panelassistant.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedSetupPresenceTest {
    @Test fun anExplicitHeartbeatHasABoundedPresenceWindow() {
        val heartbeat = 1_000_000L

        GuidedSetupPresence.noteHeartbeat(heartbeat)

        assertTrue(GuidedSetupPresence.activelyWalked(heartbeat + 90_000L))
        assertFalse(GuidedSetupPresence.activelyWalked(heartbeat + 90_001L))
    }
}
