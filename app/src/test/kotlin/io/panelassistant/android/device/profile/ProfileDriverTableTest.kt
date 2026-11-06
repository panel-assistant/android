package io.panelassistant.android.device.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Driver ids must be unique before derived maps can overwrite duplicate descriptors.
 * Independent helper-demand policy expectations live in ProfileYamlSecurityTest.
 */
class ProfileDriverTableTest {
    @Test fun coreDriverIdsAreUnique() {
        val ids = ProfileMetadata.drivers.map { it.id }
        assertEquals("duplicate core driver ids", ids.size, ids.toSet().size)
    }
}
