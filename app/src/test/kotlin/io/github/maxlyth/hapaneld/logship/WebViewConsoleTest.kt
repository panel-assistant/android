package io.github.maxlyth.hapaneld.logship

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The dashboard console source: CDP event mapping into logcat-shaped lines, the shared redaction every
 * shipped line passes, and storm coalescing.
 */
class WebViewConsoleTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-09-24T14:05:06.789Z
    private val ts = 1790258706789.0

    private fun consoleFrame(type: String, argsJson: String, stack: String = "") =
        """{"method":"Runtime.consoleAPICalled","params":{"type":"$type","args":$argsJson,""" +
            """"executionContextId":1,"timestamp":$ts$stack}}"""

    /** A missing mapping is an assertion failure, never a NullPointerException. */
    private fun mapped(frame: String): ConsoleEvent {
        val event = CdpConsoleMapper.map(frame)
        assertNotNull("frame was not mapped: $frame", event)
        return event!!
    }

    private fun line(event: ConsoleEvent) =
        CdpConsoleMapper.format(event.level, event.text, event.timestampMs, utc)

    @Test
    fun consoleErrorMapsToAnELineWithItsSourceLocation() {
        val event = mapped(
            consoleFrame(
                "error",
                """[{"type":"string","value":"card failed:"},{"type":"number","value":42},""" +
                    """{"type":"object","subtype":"error","description":"TypeError: x is undefined"}]""",
                ""","stackTrace":{"callFrames":[{"functionName":"f","url":"http://ha.local/card.js",""" +
                    """"lineNumber":9,"columnNumber":3}]}""",
            ),
        )
        assertEquals(
            "09-24 14:05:06.789     0     0 E webview/console: " +
                "card failed: 42 TypeError: x is undefined (http://ha.local/card.js:10)",
            line(event),
        )
    }

    @Test
    fun consoleLevelsMapToLogcatLetters() {
        val expected = mapOf("warning" to 'W', "debug" to 'D', "log" to 'I', "info" to 'I', "assert" to 'E')
        for ((type, letter) in expected) {
            val event = mapped(consoleFrame(type, """[{"type":"string","value":"m"}]"""))
            assertEquals(type, letter, event.level)
        }
        assertNull(CdpConsoleMapper.map(consoleFrame("clear", "[]")))
        assertNull(CdpConsoleMapper.map("""{"method":"Page.loadEventFired","params":{"timestamp":1}}"""))
        assertNull(CdpConsoleMapper.map("""{"id":1,"result":{}}"""))
        assertNull(CdpConsoleMapper.map("not json"))
    }

    @Test
    fun styleDirectivesDropTheirCssArguments() {
        val event = mapped(
            consoleFrame(
                "log",
                """[{"type":"string","value":"%c CARD %c v1.1.0"},{"type":"string","value":"color: red"},""" +
                    """{"type":"string","value":"color: blue"},{"type":"string","value":"loaded"}]""",
            ),
        )
        assertEquals("CARD  v1.1.0 loaded", event.text)
    }

    @Test
    fun logEntryMapsLevelTextAndLocation() {
        val event = mapped(
            """{"method":"Log.entryAdded","params":{"entry":{"source":"network","level":"error",""" +
                """"text":"Failed to load resource","timestamp":$ts,"url":"http://ha.local/x.png"}}}""",
        )
        assertEquals(
            "09-24 14:05:06.789     0     0 E webview/console: Failed to load resource (http://ha.local/x.png)",
            line(event),
        )
        val verbose = mapped(
            """{"method":"Log.entryAdded","params":{"entry":{"level":"verbose","text":"v","timestamp":$ts}}}""",
        )
        assertEquals('V', verbose.level)
        val warning = mapped(
            """{"method":"Log.entryAdded","params":{"entry":{"level":"warning","text":"w","timestamp":$ts}}}""",
        )
        assertEquals('W', warning.level)
    }

    @Test
    fun multilineTextIsFoldedOntoOneLine() {
        val formatted = CdpConsoleMapper.format('E', "Error: boom\n    at a (x.js:1)\r\n    at b", ts, utc)
        assertFalse(formatted.contains('\n'))
        assertFalse(formatted.contains('\r'))
        assertTrue(formatted.endsWith("Error: boom ⏎     at a (x.js:1) ⏎     at b"))
    }

    @Test
    fun consoleLineCarryingATokenIsRedactedBeforeAnyConsumerSeesIt() {
        val raw = line(
            mapped(
                consoleFrame(
                    "error",
                    """[{"type":"string","value":"auth failed token=abc123secret for """ +
                        """https://admin:hunter2@ha.local:8123/api?access_token=qwerty bearer """ +
                        """eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"}]""",
                ),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launches = java.util.concurrent.atomic.AtomicInteger()
            val capture = LogCapture(
                scope,
                streamCmd = emptyList(),
                dumpCmd = { emptyList() },
                processStarter = { launches.incrementAndGet(); error("a stream source runs no process") },
                lineStream = { emit ->
                    emit(raw)
                    awaitCancellation()
                },
            )
            val received = Collections.synchronizedList(ArrayList<String>())
            val got = CountDownLatch(1)
            val subscription = capture.subscribe { received.add(it); got.countDown() }
            assertTrue("no console line delivered", got.await(5, TimeUnit.SECONDS))
            subscription.close()
            val shipped = received.single()
            for (secret in listOf("abc123secret", "hunter2", "admin:", "qwerty", "eyJhbGciOiJIUzI1NiJ9")) {
                assertFalse("leaked $secret in $shipped", shipped.contains(secret))
            }
            assertTrue(shipped, shipped.contains("token=***"))
            assertTrue(shipped, shipped.contains("https://***@ha.local:8123/api?access_token=***"))
            assertTrue(shipped, shipped.contains("***jwt***"))
            assertTrue(shipped, shipped.contains(" E webview/console: auth failed"))
            // The console source never runs a dump subprocess; backlog is the ring alone.
            assertEquals(emptyList<String>(), capture.dump(10))
            assertEquals(0, launches.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shipperSubscriptionCarriesBothSourcesAndDetachesBoth() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val running = java.util.concurrent.atomic.AtomicInteger()
            fun source(text: String) = LogCapture(
                scope,
                streamCmd = emptyList(),
                dumpCmd = { emptyList() },
                lineStream = { emit ->
                    running.incrementAndGet()
                    try {
                        emit(text)
                        awaitCancellation()
                    } finally {
                        running.decrementAndGet()
                    }
                },
            )
            val received = Collections.synchronizedList(ArrayList<String>())
            val got = CountDownLatch(2)
            val subscription = subscribeAll(source("app line"), source("console line"))
                .invoke { received.add(it); got.countDown() }
            assertTrue("both sources did not deliver", got.await(5, TimeUnit.SECONDS))
            assertEquals(setOf("app line", "console line"), received.toSet())
            subscription.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (running.get() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(0, running.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun urlCredentialRedactionIsIdempotent() {
        val once = LogCapture.redact("GET https://user:pa55@host/path")
        assertEquals("GET https://***@host/path", once)
        assertEquals(once, LogCapture.redact(once))
    }

    private class Clock(var ms: Long = 1_000_000L) : () -> Long {
        override fun invoke() = ms
    }

    private fun event(text: String, level: Char = 'E') = ConsoleEvent(level, text, ts)

    @Test
    fun identicalStormCollapsesIntoOneLineAndOneExactSummary() {
        val clock = Clock()
        val coalescer = ConsoleCoalescer(windowMs = 2_000, maxHoldMs = 10_000, now = clock, zone = utc)
        val first = coalescer.offer(event("boom"))
        assertEquals(1, first.size)
        assertTrue(first[0].endsWith(" E webview/console: boom"))
        repeat(49) {
            clock.ms += 10
            assertEquals(emptyList<String>(), coalescer.offer(event("boom")))
        }
        clock.ms += 10
        val next = coalescer.offer(event("different"))
        assertEquals(2, next.size)
        assertTrue(next[0], next[0].endsWith(" E webview/console: previous message repeated 49 more times"))
        assertTrue(next[1].endsWith(" E webview/console: different"))
    }

    @Test
    fun aQuietStormFlushesItsCountAndInterleavingBreaksTheRun() {
        val clock = Clock()
        val coalescer = ConsoleCoalescer(windowMs = 2_000, maxHoldMs = 10_000, now = clock, zone = utc)
        coalescer.offer(event("a"))
        clock.ms += 5
        assertEquals(emptyList<String>(), coalescer.offer(event("a")))
        clock.ms += 5
        assertEquals(emptyList<String>(), coalescer.offer(event("a")))
        clock.ms += 1_000
        assertEquals(emptyList<String>(), coalescer.flush())
        clock.ms += 1_001
        val flushed = coalescer.flush()
        assertEquals(1, flushed.size)
        assertTrue(flushed[0], flushed[0].endsWith("previous message repeated 2 more times"))
        assertEquals(emptyList<String>(), coalescer.flush(force = true))

        // a, b, a: no two adjacent identical events, so nothing is suppressed.
        val fresh = ConsoleCoalescer(windowMs = 2_000, maxHoldMs = 10_000, now = clock, zone = utc)
        assertEquals(1, fresh.offer(event("a")).size)
        assertEquals(1, fresh.offer(event("b")).size)
        assertEquals(1, fresh.offer(event("a")).size)
        // Same text at another level is a different message.
        assertEquals(1, fresh.offer(event("a", 'W')).size)
    }

    @Test
    fun aSustainedStormReportsAtTheHoldLimitAndAfterTheWindowPasses() {
        val clock = Clock()
        val coalescer = ConsoleCoalescer(windowMs = 2_000, maxHoldMs = 10_000, now = clock, zone = utc)
        coalescer.offer(event("spam"))
        var summaries = 0
        repeat(1_100) {
            clock.ms += 10
            summaries += coalescer.offer(event("spam")).size
        }
        // 11 s of repeats: exactly one summary at the 10 s hold limit, the remainder still held.
        assertEquals(1, summaries)
        val tail = coalescer.flush(force = true)
        assertEquals(1, tail.size)
        clock.ms += 3_000
        // A repeat after the window is a new first occurrence, not a suppressed one.
        assertEquals(1, coalescer.offer(event("spam")).size)
    }

    @Test
    fun linesDifferingOnlyInASecretCoalesce() {
        val clock = Clock()
        val coalescer = ConsoleCoalescer(windowMs = 2_000, maxHoldMs = 10_000, now = clock, zone = utc)
        assertEquals(1, coalescer.offer(event("retry token=aaa")).size)
        clock.ms += 10
        assertEquals(emptyList<String>(), coalescer.offer(event("retry token=bbb")))
    }

    @Test
    fun consoleSourceRunsOnlyWhileShippingIsConfiguredOutsideHardened() {
        assertTrue(webViewConsoleEnabled(shippingEnabled = true, shippingHost = "sink.lan", hardened = false))
        assertFalse(webViewConsoleEnabled(shippingEnabled = false, shippingHost = "sink.lan", hardened = false))
        assertFalse(webViewConsoleEnabled(shippingEnabled = true, shippingHost = " ", hardened = false))
        assertFalse(webViewConsoleEnabled(shippingEnabled = true, shippingHost = "sink.lan", hardened = true))
    }

    @Test
    fun reattachmentDropsReplayedEventsAndANewPageStartsAfresh() {
        val replay = ReplayHighWater()
        replay.attach("P1")
        assertTrue(replay.admit(100.0))
        assertTrue(replay.admit(200.0))
        // Relay idle timeout, reconnect, Runtime.enable replays the buffer.
        replay.attach("P1")
        assertFalse(replay.admit(100.0))
        assertFalse(replay.admit(200.0))
        assertTrue(replay.admit(201.0))
        assertTrue(replay.admit(0.0))
        replay.attach("P2")
        assertTrue(replay.admit(150.0))
    }

    @Test
    fun onlyASinglePageTargetIsSelected() {
        val page = """{"id":"P1","type":"page","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/P1"}"""
        val worker = """{"id":"W1","type":"service_worker","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/W1"}"""
        assertEquals(
            "P1" to "ws://127.0.0.1:9222/devtools/page/P1",
            WebViewConsoleStream.selectPageTarget("[$worker,$page]"),
        )
        assertNull(WebViewConsoleStream.selectPageTarget("[$page,${page.replace("P1", "P2")}]"))
        assertNull(WebViewConsoleStream.selectPageTarget("[$worker]"))
        assertNull(WebViewConsoleStream.selectPageTarget("<html>"))
    }
}
