package io.panelassistant.android.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BrightnessWriteAttributionTest {
    @Test fun matchingOwnedWriteIsConsumedOnlyOnce() {
        val journal = BrightnessWriteAttribution()
        journal.record(120, 1_000)

        assertTrue(journal.consume(120, 1_100))
        assertFalse(journal.consume(120, 1_101))
    }

    @Test fun reorderedCallbacksCanMatchDifferentRecentLevels() {
        val journal = BrightnessWriteAttribution()
        journal.record(80, 1_000)
        journal.record(160, 1_010)

        assertTrue(journal.consume(160, 1_020))
        assertTrue(journal.consume(80, 1_030))
    }

    @Test fun expiredOrDifferentLevelIsExternal() {
        val journal = BrightnessWriteAttribution(windowMs = 100)
        journal.record(100, 1_000)

        assertFalse(journal.consume(101, 1_050))
        assertFalse(journal.consume(100, 1_101))
    }

    @Test fun journalIsBounded() {
        val journal = BrightnessWriteAttribution(maximumEntries = 3)
        repeat(10) { journal.record(it, it.toLong()) }
        assertEquals(3, journal.sizeForTest())
    }
}
