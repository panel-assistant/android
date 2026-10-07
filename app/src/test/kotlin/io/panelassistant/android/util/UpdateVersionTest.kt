package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun numericAndPrereleaseOrderIsDeterministic() {
        assertTrue(UpdateChecker.isNewer("0.9.3", "0.9.2"))
        assertTrue(UpdateChecker.isNewer("0.9.2", "0.9.2-rc4"))
        assertTrue(UpdateChecker.isNewer("0.9.2-rc4", "0.9.2-rc3"))
        assertTrue(UpdateChecker.isNewer("0.9.2-rc1", "0.9.2-beta9"))
        assertFalse(UpdateChecker.isNewer("0.9.2-rc3", "0.9.2"))
        assertEquals(0, UpdateChecker.compareVersions("2026.5.4", "2026.5.4.0"))
    }

    @Test fun malformedVersionIsNotTreatedAsZero() {
        assertNull(UpdateChecker.compareVersions("release-next", "2026.5.4"))
        assertNull(UpdateChecker.compareVersions("2026.x.4", "2026.5.4"))
        assertFalse(UpdateChecker.isNewer("release-next", "0.9.2"))
    }

    @Test fun currentInstallStateDropsOrRefreshesCachedCompanionUpdate() {
        val cached = UpdateChecker.UpdateInfo("HA Companion", "2026.5.4-minimal", "2026.6.5", "u")
        assertTrue(UpdateChecker.filterCurrent(listOf(cached), null).isEmpty())
        assertTrue(UpdateChecker.filterCurrent(listOf(cached), "2026.6.5-minimal").isEmpty())
        assertEquals(
            "2026.5.3-minimal",
            UpdateChecker.filterCurrent(listOf(cached), "2026.5.3-minimal").single().currentVersion,
        )
    }
}
