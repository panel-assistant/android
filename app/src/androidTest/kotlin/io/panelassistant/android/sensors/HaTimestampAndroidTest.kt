package io.panelassistant.android.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the HA wire-format timestamp parser on the device's actual java.time implementation. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class HaTimestampAndroidTest {
    @Test fun offsetTimestampParsesOnFleetApiFloor() {
        assertEquals(1_754_044_530_123L, parseHaTimestampEpochMs("2025-08-01T10:35:30.123456+00:00"))
        assertEquals(
            parseHaTimestampEpochMs("2026-07-16T09:42:00Z"),
            parseHaTimestampEpochMs("2026-07-16T10:42:00+01:00"),
        )
        assertNull(parseHaTimestampEpochMs("not-a-timestamp"))
    }
}
