package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperCompanionCapabilityTest {
    @Test fun `Companion capability requires the exact journaled protocol envelope`() {
        assertTrue(companionCapabilitySupported("COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL"))
        assertFalse(companionCapabilitySupported("OK"))
        assertFalse(companionCapabilitySupported("COMPANIONCAPS 1 BACKUP RESTORE STATUS"))
        assertFalse(companionCapabilitySupported(null))
    }

    @Test fun `Companion status distinguishes busy unsupported and unavailable`() {
        assertEquals(CompanionOperationStatus.IDLE, parseCompanionOperationStatus("IDLE"))
        assertEquals(CompanionOperationStatus.BUSY, parseCompanionOperationStatus("BUSY"))
        assertEquals(CompanionOperationStatus.UNSUPPORTED, parseCompanionOperationStatus("ERR"))
        assertEquals(CompanionOperationStatus.UNAVAILABLE, parseCompanionOperationStatus(null))
        assertEquals(CompanionOperationStatus.UNAVAILABLE, parseCompanionOperationStatus("unexpected"))
    }

    @Test fun `helper build identity must exactly match the bundled source digest`() {
        val expected = "a".repeat(64)
        assertTrue(helperBuildIdentitySupported("BUILDID $expected", expected))
        assertFalse(helperBuildIdentitySupported("BUILDID ${"b".repeat(64)}", expected))
        assertFalse(helperBuildIdentitySupported("OK", expected))
        assertFalse(helperBuildIdentitySupported(null, expected))
        assertFalse(helperBuildIdentitySupported("BUILDID development", "development"))
    }
}
