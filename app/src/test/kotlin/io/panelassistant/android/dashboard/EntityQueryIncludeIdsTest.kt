package io.panelassistant.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The id restriction is composed above the store, so it decides what the SQL can ever select. A
 * predicate that surfaces stale pins is inert if the query never offers them, which is why this is
 * asserted behaviourally rather than by reading the predicate's text.
 */
class EntityQueryIncludeIdsTest {

    private val active = setOf("light.hall", "sensor.porch")
    private val pinned = setOf("light.hall", "switch.retired")

    @Test fun `review reaches a pin the active filter has dropped`() {
        // switch.retired was pinned by hand and then dropped from the applied ids after repeated
        // misses. It is the row Review exists to show; restricted to the active ids it could never be
        // returned, so it could neither be seen nor unpinned.
        val ids = entityQueryIncludeIds(
            subscribed = false, review = true, held = false, filtered = true,
            activeIds = active, pinnedIds = pinned,
        )
        assertEquals(setOf("light.hall", "sensor.porch", "switch.retired"), ids)
    }

    @Test fun `subscribed reports the live subscription and never inflates it with pins`() {
        // Folding pins in here would claim the panel is watching something it is not.
        val ids = entityQueryIncludeIds(
            subscribed = true, review = false, held = false, filtered = true,
            activeIds = active, pinnedIds = pinned,
        )
        assertEquals(active, ids)
    }

    @Test fun `a held bootstrap offers nothing rather than a partial truth`() {
        for (review in listOf(true, false)) {
            assertEquals(
                "held must select nothing whether reviewing or listing the subscription",
                emptySet<String>(),
                entityQueryIncludeIds(
                    subscribed = !review, review = review, held = true, filtered = true,
                    activeIds = active, pinnedIds = pinned,
                ),
            )
        }
    }

    @Test fun `an unfiltered panel restricts nothing at all`() {
        assertNull(
            entityQueryIncludeIds(
                subscribed = true, review = false, held = false, filtered = false,
                activeIds = active, pinnedIds = pinned,
            ),
        )
        assertNull(
            entityQueryIncludeIds(
                subscribed = false, review = false, held = false, filtered = true,
                activeIds = active, pinnedIds = pinned,
            ),
        )
    }
}
