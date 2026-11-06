package io.panelassistant.android.logship

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogCaptureRecordsTest {
    @Test fun appRecordsRetainEventTimeAndShareTheViewersRedaction() {
        val input = listOf(
            "--------- beginning of main",
            "12-31 23:59:58.123  900  950 W Example: password=hunter2secret",
            "    at Example.first(Example.kt:12)",
            "01-01 00:00:01.456  900  951 F Example: recovered",
        )
        val (records, raw) = capture(input, false, "2027-01-01T00:00:02Z", "Europe/London")
        assertEquals(listOf(
            LogCaptureRecord("2026-12-31T23:59:58.123Z", 5, "Example", 900, "password=***"),
            LogCaptureRecord("2027-01-01T00:00:01.456Z", 7, "Example", 900, "recovered"),
        ), records)
        assertEquals(input.map(LogCapture::redact), raw)
    }

    @Test fun appThreadtimeUsesTheDeviceTimezoneAndNearestYear() {
        val (records, _) = capture(
            listOf("01-01 00:00:01.000  42  43 D Local: hello"), false,
            "2026-12-31T23:59:59Z", "GMT+02:00",
        )
        assertEquals(listOf(LogCaptureRecord("2026-12-31T22:00:01Z", 3, "Local", 42, "hello")), records)
    }

    @Test fun runningCaptureUsesTheCurrentDeviceTimezoneForEachEvent() {
        var zone = TimeZone.getTimeZone("UTC")
        val emitted = CountDownLatch(1)
        val records = CopyOnWriteArrayList<LogCaptureRecord>()
        val capture = LogCapture(
            CoroutineScope(Dispatchers.IO), emptyList(), { emptyList() },
            wallMillis = { Instant.parse("2026-10-28T12:00:00Z").toEpochMilli() },
            timeZone = { zone },
            lineStream = { emit ->
                emit("10-28 12:00:00.000  42  43 I Local: before")
                zone = TimeZone.getTimeZone("GMT+02:00")
                emit("10-28 14:00:00.000  42  43 I Local: after")
                emitted.countDown()
                awaitCancellation()
            },
        )
        val subscription = capture.subscribeRecords(records::add)
        try {
            assertTrue("capture did not emit", emitted.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("2026-10-28T12:00:00Z", "2026-10-28T12:00:00Z"), records.map { it.timestamp })
        } finally {
            subscription.close()
            capture.close()
        }
    }

    @Test fun systemRecordsRetainEpochTimeAndWholeRedactedException() {
        val (records, raw) = capture(listOf(
            "[ 1790592713.123  123: 456 E/AndroidRuntime ]",
            "failed password=hunter2secret\\n    at Example.first(Example.kt:12)",
            "",
        ), true, "2030-01-01T00:00:00Z", "GMT+12:00")
        assertEquals(listOf(LogCaptureRecord(
            "2026-09-28T10:51:53.123Z", 6, "AndroidRuntime", 123,
            "failed password=***\n    at Example.first(Example.kt:12)",
        )), records)
        assertEquals("[ 1790592713.123  123: 456 E/AndroidRuntime ]\n${records.single().message}", raw.single())
    }

    @Test fun malformedRecordsAreIgnoredWithoutChangingViewerOutput() {
        val input = listOf(
            "plain line", "02-30 12:00:00.000  1  2 I BadDate: invalid",
            "10-28 12:00:00.000  999999999999  2 I BadPid: invalid",
            "10-28 12:00:00.000  1  2 X BadPriority: invalid",
        )
        val (records, raw) = capture(input, false, "2026-10-28T12:00:00Z", "UTC")
        assertTrue(records.isEmpty())
        assertEquals(input, raw)
    }

    private fun capture(
        input: List<String>, longRecords: Boolean, now: String, zone: String,
    ): Pair<List<LogCaptureRecord>, List<String>> {
        val ready = CountDownLatch(1)
        val emitted = CountDownLatch(1)
        val raw = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<LogCaptureRecord>()
        val capture = LogCapture(
            CoroutineScope(Dispatchers.IO), emptyList(), { emptyList() },
            lineStream = { emit ->
                ready.await()
                input.forEach(emit)
                emitted.countDown()
                awaitCancellation()
            },
            longLogcatRecords = longRecords,
            wallMillis = { Instant.parse(now).toEpochMilli() },
            timeZone = { TimeZone.getTimeZone(zone) },
        )
        val viewer = capture.subscribe(raw::add)
        val structured = capture.subscribeRecords(records::add)
        try {
            ready.countDown()
            assertTrue("capture did not emit", emitted.await(3, TimeUnit.SECONDS))
            return records.toList() to raw.toList()
        } finally {
            structured.close()
            viewer.close()
            capture.close()
        }
    }
}
