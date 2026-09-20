package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** One full line per failure class per boot, then counts — and never a suppressed first occurrence. */
class SuFailureLogThrottleTest {
    private var now = 0L
    private val throttle = SuFailureLogThrottle(summaryIntervalMs = 60_000L, nowMs = { now })

    @Test
    fun `the first occurrence is detailed and the rest are counted, not re-logged`() {
        assertEquals(SuFailureLogThrottle.Decision.Detailed, throttle.onFailure("denied"))

        repeat(145) { assertEquals(SuFailureLogThrottle.Decision.Silent, throttle.onFailure("denied")) }

        now += 60_000L
        assertEquals(SuFailureLogThrottle.Decision.Summary(146L), throttle.onFailure("denied"))
    }

    @Test
    fun `each count covers only the occurrences since the last line`() {
        throttle.onFailure("denied")
        throttle.onFailure("denied")
        now += 60_000L
        assertEquals(SuFailureLogThrottle.Decision.Summary(2L), throttle.onFailure("denied"))

        throttle.onFailure("denied")
        now += 60_000L
        assertEquals(SuFailureLogThrottle.Decision.Summary(2L), throttle.onFailure("denied"))
    }

    @Test
    fun `a genuinely new classification is never suppressed by one already seen`() {
        throttle.onFailure("denied")
        throttle.onFailure("denied")

        assertEquals(SuFailureLogThrottle.Decision.Detailed, throttle.onFailure("missing"))
        assertEquals(SuFailureLogThrottle.Decision.Detailed, throttle.onFailure("other:java.io.IOException:42"))
    }

    @Test
    fun `a latched class is one signature whatever operation hit it`() {
        val eacces = IOException("Cannot run program \"su\": error=13, Permission denied")

        assertEquals("denied", suFailureSignature(SuExecFailure.FIRST_DENIED, eacces))
        assertEquals("denied", suFailureSignature(SuExecFailure.ALREADY_DENIED, eacces))
        assertEquals("missing", suFailureSignature(SuExecFailure.ALREADY_MISSING, eacces))
    }

    @Test
    fun `an uncached failure keeps its type and errno, so a different one is a new class`() {
        assertEquals(
            "other:java.io.IOException:13",
            suFailureSignature(SuExecFailure.OTHER, IOException("Cannot run program \"su\": error=13, Permission denied")),
        )
        assertEquals(
            "other:java.io.IOException:24",
            suFailureSignature(SuExecFailure.OTHER, IOException("Cannot run program \"su\": error=24, Too many open files")),
        )
        assertEquals(
            "other:java.lang.IllegalStateException:none",
            suFailureSignature(SuExecFailure.OTHER, IllegalStateException("runtime failure")),
        )
    }
}
