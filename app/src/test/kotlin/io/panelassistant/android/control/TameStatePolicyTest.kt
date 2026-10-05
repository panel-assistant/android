package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TameStatePolicyTest {
    @Test fun parsesHelperAndPlatformAppOpModes() {
        assertEquals("allow", TameStatePolicy.overlayMode("MODE=allow"))
        assertEquals("ignore", TameStatePolicy.overlayMode("SYSTEM_ALERT_WINDOW: ignore; time=+2h"))
        assertEquals("foreground", TameStatePolicy.overlayMode("SYSTEM_ALERT_WINDOW (default): foreground"))
        assertEquals("default", TameStatePolicy.overlayMode("No operations."))
        assertNull(TameStatePolicy.overlayMode("MODE=unexpected"))
    }

    @Test fun currentHomeIsNeverDisabledUntilReplacementIsConfirmedByReadback() {
        assertFalse(TameStatePolicy.homeHandoffSecured(false, "io.github.maxlyth.hapaneld", "io.github.maxlyth.hapaneld"))
        assertFalse(TameStatePolicy.homeHandoffSecured(true, "android", "io.github.maxlyth.hapaneld"))
        assertFalse(TameStatePolicy.homeHandoffSecured(true, null, "io.github.maxlyth.hapaneld"))
        assertTrue(TameStatePolicy.homeHandoffSecured(true, "io.github.maxlyth.hapaneld", "io.github.maxlyth.hapaneld"))
    }

    @Test fun onlyExplicitPlatformModesCanBePersistedOrRestored() {
        listOf("allow", "deny", "ignore", "default", "foreground").forEach {
            assertTrue(it, TameStatePolicy.validOverlayMode(it))
        }
        listOf("", "wipe", "allow;reboot", "MODE=allow").forEach {
            assertFalse(it, TameStatePolicy.validOverlayMode(it))
        }
    }
}
