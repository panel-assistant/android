package io.panelassistant.android

import io.panelassistant.android.device.TameCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The startup gate that hands HOME back when a setup that never completed left the panel app holding it. */
class UnfinishedSetupHomeHandbackTest {
    private val vendor = listOf(
        TameCandidate("com.vendor.launcher", tags = listOf("vendor"), note = "Vendor home screen", defaultTame = true),
        TameCandidate("com.vendor.settings", tags = listOf("vendor")),
    )

    @Test fun anUnfinishedSetupWithThePanelAppAsHomeHandsHomeBackWithTheVendorPackages() {
        listOf(AppIdentity.OWN, AppIdentity.LEGACY).forEach { home ->
            val calls = mutableListOf<Set<String>>()
            val result = handBackUnfinishedSetupHome(false, { home }, vendor) { calls += it; "handed back" }
            assertEquals(home, "handed back", result)
            assertEquals(home, listOf(setOf("com.vendor.launcher", "com.vendor.settings")), calls)
        }
    }

    @Test fun aCompletedSetupLeavesHomeAloneWithoutObservingIt() {
        val observed = mutableListOf<String>()
        val calls = mutableListOf<Set<String>>()
        val result = handBackUnfinishedSetupHome(true, { observed += "home"; AppIdentity.OWN }, vendor) { calls += it }
        assertNull(result)
        assertEquals(emptyList<Set<String>>(), calls)
        assertEquals("HOME is only observed for an unfinished setup", emptyList<String>(), observed)
    }

    @Test fun anotherHomeLeavesHomeAloneDuringAnUnfinishedSetup() {
        listOf("com.vendor.launcher", "com.android.settings", null).forEach { home ->
            val calls = mutableListOf<Set<String>>()
            val result = handBackUnfinishedSetupHome(false, { home }, vendor) { calls += it }
            assertNull("$home", result)
            assertEquals("$home", emptyList<Set<String>>(), calls)
        }
    }
}
