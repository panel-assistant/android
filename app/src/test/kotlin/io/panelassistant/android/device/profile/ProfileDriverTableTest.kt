package io.panelassistant.android.device.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the single-source driver table. `ProfileMetadata.drivers` owns the driver vocabulary together
 * with each driver's root-helper demand, and every driver-keyed table is derived from it rather than
 * re-enumerating the ids. These assertions close the previously unchecked cross-component invariant
 * that the driver→demand map covers every driver (and nothing else), and prove the derived map agrees
 * with the descriptors it is built from.
 */
class ProfileDriverTableTest {
    @Test fun coreDriverIdsAreUnique() {
        val ids = ProfileMetadata.drivers.map { it.id }
        assertEquals("duplicate core driver ids", ids.size, ids.toSet().size)
    }

    @Test fun helperAuthorityDemandCoversExactlyTheCanonicalDriverTable() {
        assertEquals(
            "the helper-authority demand map must cover exactly the canonical driver table",
            ProfileMetadata.drivers.map { it.id }.toSet(),
            ProfileMetadata.helperAuthorityDemand.keys,
        )
    }

    @Test fun helperAuthorityDemandIsDerivedFromTheDriverDescriptors() {
        ProfileMetadata.drivers.forEach { driver ->
            assertEquals(
                "helper demand for ${driver.id} must come from its descriptor",
                driver.helperDemand,
                ProfileMetadata.helperAuthorityDemand.getValue(driver.id),
            )
        }
    }
}
