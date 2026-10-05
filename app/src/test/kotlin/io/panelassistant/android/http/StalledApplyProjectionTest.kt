package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `apply_stalled` never widens what the panel reports as durable desired state; it only says which of
 * those values this panel has been found unable to apply. The desired value itself keeps coming through
 * `apply_pending` unchanged, including a pending OFF, so nothing the page shows depends on the stall.
 */
class StalledApplyProjectionTest {
    @Test fun `a stalled key is reported only while it is still pending`() {
        assertEquals(
            listOf("silence_boot_chime"),
            stalledApplyKeys(
                pending = setOf("silence_boot_chime", "touch_sound"),
                stalled = setOf("silence_boot_chime"),
            ),
        )
    }

    @Test fun `a stall for a key that is no longer pending is dropped`() {
        // The value applied, or was superseded: there is no longer a desired value to describe, so the
        // page must not be told a row it is not showing as waiting cannot be applied.
        assertEquals(
            emptyList<String>(),
            stalledApplyKeys(pending = setOf("touch_sound"), stalled = setOf("silence_boot_chime")),
        )
    }

    @Test fun `the projection is ordered so the response does not churn`() {
        assertEquals(
            listOf("silence_boot_chime", "touch_sound"),
            stalledApplyKeys(
                pending = setOf("touch_sound", "silence_boot_chime"),
                stalled = setOf("touch_sound", "silence_boot_chime"),
            ),
        )
    }

    @Test fun `nothing stalled reports nothing`() {
        assertEquals(
            emptyList<String>(),
            stalledApplyKeys(pending = setOf("silence_boot_chime"), stalled = emptySet()),
        )
    }
}
