package io.panelassistant.android.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDataOperationGateTest {
    @Test fun leaseBlocksOnlyItsPackageAndReleasesExactlyOnce() {
        val lease = CompanionDataOperationGate.acquire("io.homeassistant.companion.android")!!
        assertTrue(CompanionDataOperationGate.blocks("io.homeassistant.companion.android"))
        assertTrue(CompanionDataOperationGate.blocks("io.homeassistant.companion.android.minimal"))
        assertTrue(CompanionDataOperationGate.blocksImplicitNavigation())
        assertFalse(CompanionDataOperationGate.blocks("io.github.maxlyth.hapaneld"))
        assertNull(CompanionDataOperationGate.acquire("io.homeassistant.companion.android.minimal"))

        lease.close()
        lease.close()
        assertFalse(CompanionDataOperationGate.blocks("io.homeassistant.companion.android"))
        assertFalse(CompanionDataOperationGate.blocksImplicitNavigation())
        CompanionDataOperationGate.acquire("io.homeassistant.companion.android.minimal")!!.close()
    }
}
