package io.panelassistant.android.http

import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.RevisionRing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RevisionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun snapshotRetainsExactlyTheConfiguredRingCapacity() {
        val store = RevisionStore(temporary.root)
        repeat(RevisionRing.MAX + 5) { index ->
            store.snapshot(ConfigBundle.fromValues(mapOf("friendly_name" to "panel-$index")))
        }

        val revisions = store.list()
        assertEquals(RevisionRing.MAX, revisions.size)
        assertEquals("panel-${RevisionRing.MAX + 4}", revisions.first().second.values["friendly_name"])
        assertEquals("panel-5", revisions.last().second.values["friendly_name"])
    }
}
