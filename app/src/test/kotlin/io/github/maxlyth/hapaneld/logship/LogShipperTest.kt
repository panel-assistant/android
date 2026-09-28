package io.github.maxlyth.hapaneld.logship

import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LogShipperTest {
    @Test fun trafficSummaryNamesPositivePluralAndMultiDropCounts() {
        assertEquals("2 records sent · 3 dropped", logShipTrafficText(sent = 2, dropped = 3))
        assertEquals("1 record sent", logShipTrafficText(sent = 1, dropped = 0))
    }

    private class FakeCapture {
        val subscriptions = AtomicInteger()
        val closes = AtomicInteger()
        private val listener = AtomicReference<((String) -> Unit)?>(null)

        fun subscribe(candidate: (String) -> Unit): AutoCloseable {
            subscriptions.incrementAndGet()
            listener.set(candidate)
            return AutoCloseable {
                if (listener.compareAndSet(candidate, null)) closes.incrementAndGet()
            }
        }

        fun emit(line: String) = listener.get()?.invoke(line)
    }

    private class RecordingSink : LogSink {
        val connected = CountDownLatch(1)
        val closed = AtomicBoolean()
        val lines = CopyOnWriteArrayList<String>()

        override fun connect() {
            connected.countDown()
        }

        override fun send(lines: List<String>) {
            this.lines.addAll(lines)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private fun await(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            assertTrue("timed out waiting for condition", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    private fun snapshot(
        host: String = "first",
        panelId: String = "panel-a",
        protocol: String = "syslog",
        systemEnabled: Boolean = false,
    ) = LogShipConfigSnapshot(
        enabled = true,
        host = host,
        port = 514,
        protocol = protocol,
        panelId = panelId,
        systemEnabled = systemEnabled,
    )

    /** Frames each captured line through the production encoder, exactly as a sink receives it. */
    private fun encodedFrames(protocol: String, line: String): List<String> {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val framed = CopyOnWriteArrayList<String>()
        val shipper = LogShipper(
            configSnapshot = { snapshot(protocol = protocol) },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, encode ->
                object : LogSink {
                    override fun connect() = Unit
                    override fun send(lines: List<String>) { lines.forEach { framed += encode(it) } }
                    override fun close() = Unit
                }
            },
        )
        try {
            shipper.start()
            await { capture.subscriptions.get() == 1 }
            await { capture.emit(line); framed.isNotEmpty() }
            return framed.toList()
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun everySyslogFrameCarriesTheBuildAsStructuredData() {
        val line = "09-25 10:00:00.000  123  456 W ha-paneld/x: a \"quoted\" [bracketed] line"
        val frame = encodedFrames("syslog-tcp", line).first()
        val sd = "[hapaneld@32473 versionCode=\"${BuildConfig.VERSION_CODE}\" " +
            "package=\"${BuildConfig.APPLICATION_ID}\"]"

        // HEADER then SD in the position that was `-`, then the MSG untouched: a collector that ignores
        // structured data still sees exactly the line it saw before.
        val header = Regex("""^<12>1 \S+ panel-a ha-paneld - - """)
        assertTrue(frame, header.containsMatchIn(frame))
        assertEquals(frame, "$sd $line\n", frame.replaceFirst(header, ""))
    }

    @Test fun ndjsonEventCarriesTheBuildAsTwoKeys() {
        val event = JSONObject(encodedFrames("http", "hello").first())
        assertEquals(BuildConfig.VERSION_CODE, event.optInt("versionCode", -1))
        assertEquals(BuildConfig.APPLICATION_ID, event.optString("package", "<absent>"))
        assertEquals("hello", event.getString("message"))
        assertEquals("ha-paneld", event.getString("app"))
    }

    @Test fun multilineExceptionIsOneLosslessSyslogFrame() {
        val exception = "[ 1790592713.123  123: 456 E/AndroidRuntime ]\n" +
            "java.lang.IllegalStateException: failure\\path\n" +
            "    at Example.first(Example.kt:12)\n" +
            "    at Example.second(Example.kt:25)"
        val frame = encodedFrames("syslog-tcp", exception).first()

        // The existing TCP sink uses newline-delimited frames. An embedded raw newline would be
        // parsed as another event, and a truncated stack would be impossible to debug.
        assertTrue(frame, frame.startsWith("<11>1 ")) // AndroidRuntime E stays error severity
        assertEquals(1, frame.count { it == '\n' })
        assertTrue(frame.endsWith("\\n    at Example.second(Example.kt:25)\n"))
        assertTrue(frame.contains("failure\\\\path\\n"))
        assertEquals(exception, JSONObject(encodedFrames("http", exception).first()).getString("message"))
    }

    /** Real package names never contain the three characters, so only an adversarial value proves the escaper. */
    @Test fun structuredDataEscapesQuoteBackslashAndBracket() {
        val sd = LogShipRecord.structuredData(LogShipRecord.Build(42, "a\"b\\c]d"))
        assertEquals("[hapaneld@32473 versionCode=\"42\" package=\"a\\\"b\\\\c\\]d\"]", sd)
        assertEquals("plain.pkg_1", LogShipRecord.paramValue("plain.pkg_1"))
    }

    @Test fun runOwnsItsQueueCountersAndTerminalBoundary() {
        val instrumentedDrops = AtomicLong()
        val run = LogShipRun(
            snapshot().targetOrNull()!!,
            queueCapacity = 2,
            onDropped = { instrumentedDrops.addAndGet(it) },
        )
        run.offer("one")
        run.offer("two")
        run.offer("three")

        assertEquals(listOf("two", "three"), run.takeBatch(2, 0))
        assertEquals(1L, run.status().dropped)
        assertEquals(1L, instrumentedDrops.get())

        run.close()
        run.offer("after-close")
        assertTrue(run.takeBatch(2, 0).isEmpty())
        assertFalse(run.isOpen())
    }

    @Test fun systemBudgetAdmitsCompleteBurstThenBoundsSustainedBytesAndOversizeEntries() {
        val now = AtomicLong(0L)
        val run = LogShipRun(
            snapshot(systemEnabled = true).targetOrNull()!!,
            queueCapacity = 1000,
            nowNs = now::get,
        )
        try {
            val prefix = "[ 1790592713.123  123: 456 E/AndroidRuntime ]\n" +
                "java.lang.IllegalStateException: failure\n" +
                "    at Example.first(Example.kt:12)\n"
            val completeEntry = prefix + "x".repeat(8_192 - prefix.toByteArray().size)
            val bytes = completeEntry.toByteArray().size
            assertTrue(bytes in 8_000..8_192)
            repeat(64) { run.offerSystem(completeEntry) }
            run.offerSystem(completeEntry)
            assertEquals(1L, run.status().dropped)
            assertEquals(List(64) { completeEntry }, run.takeBatch(100, 0))

            now.set(1_000_000_000L)
            repeat(16) { run.offerSystem(completeEntry) }
            run.offerSystem(completeEntry)
            assertEquals(2L, run.status().dropped)
            assertEquals(List(16) { completeEntry }, run.takeBatch(100, 0))

            run.offerSystem("z".repeat(8_193))
            assertEquals(3L, run.status().dropped)
            assertTrue(run.takeBatch(100, 0).isEmpty())
        } finally {
            run.close()
        }
    }

    @Test fun systemCaptureSubscribesOnlyForItsSeparateOptInAndSharesTheSink() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ordinary = FakeCapture()
        val system = FakeCapture()
        val config = AtomicReference(snapshot())
        val sink = RecordingSink()
        val shipper = LogShipper(
            configSnapshot = config::get,
            scope = scope,
            subscribeCapture = ordinary::subscribe,
            subscribeSystem = system::subscribe,
            sinkFactory = LogSinkFactory { _, _ -> sink },
        )
        try {
            shipper.start()
            ordinary.emit("browser console")
            await { sink.lines.contains("browser console") }
            assertEquals(0, system.subscriptions.get())

            config.set(snapshot(systemEnabled = true))
            shipper.reconfigure()
            await { system.subscriptions.get() == 1 }
            val exception = "[ 1790592713.123  123: 456 E/AndroidRuntime ]\n" +
                "java.lang.IllegalStateException: failure\n    at Example.first(Example.kt:12)"
            system.emit(exception)
            await { sink.lines.contains(exception) }
            assertEquals(1, sink.lines.count { it == exception })

            config.set(snapshot())
            shipper.reconfigure()
            assertEquals(1, system.closes.get())
            system.emit("must not ship")
            assertFalse(sink.lines.contains("must not ship"))
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun udpDoesNotTurnCompleteExceptionsIntoTruncatedSystemRecords() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ordinary = FakeCapture()
        val system = FakeCapture()
        val shipper = LogShipper(
            configSnapshot = { snapshot(protocol = "syslog-udp", systemEnabled = true) },
            scope = scope,
            subscribeCapture = ordinary::subscribe,
            subscribeSystem = system::subscribe,
            sinkFactory = LogSinkFactory { _, _ -> RecordingSink() },
        )
        try {
            shipper.start()
            assertEquals(0, system.subscriptions.get())
            assertTrue(shipper.statusText().contains("system logs need TCP or HTTP"))
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun reconfigureClosesOldGenerationBeforeReplacementReceivesLines() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val config = AtomicReference(snapshot())
        val capture = FakeCapture()
        val sinks = CopyOnWriteArrayList<Pair<LogShipTarget, RecordingSink>>()
        val shipper = LogShipper(
            configSnapshot = config::get,
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { target, _ -> RecordingSink().also { sinks += target to it } },
        )
        try {
            shipper.start()
            shipper.start()
            await { sinks.size == 1 }
            assertEquals(1, capture.subscriptions.get())
            capture.emit("first-line")
            await { sinks[0].second.lines == listOf("first-line") }

            config.set(snapshot(host = "second", panelId = "panel-b"))
            shipper.reconfigure()
            await { sinks.size == 2 && sinks[0].second.closed.get() }
            assertEquals(2, capture.subscriptions.get())
            assertEquals(1, capture.closes.get())

            capture.emit("second-line")
            await { sinks[1].second.lines == listOf("second-line") }
            assertEquals(listOf("first-line"), sinks[0].second.lines)
            assertEquals("second", sinks[1].first.host)
            assertEquals("panel-b", sinks[1].first.panelId)

            shipper.stop()
            assertTrue(sinks[1].second.closed.get())
            assertEquals(2, capture.closes.get())
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun failedInFlightLineIsReportedAsDropped() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val sendAttempted = CountDownLatch(1)
        val shipper = LogShipper(
            configSnapshot = { snapshot() },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, _ ->
                object : LogSink {
                    override fun connect() = Unit
                    override fun send(lines: List<String>) {
                        sendAttempted.countDown()
                        throw IOException("collector rejected line")
                    }
                    override fun close() = Unit
                }
            },
        )
        try {
            shipper.start()
            capture.emit("lost")
            assertTrue(sendAttempted.await(2, TimeUnit.SECONDS))
            await { "1 dropped" in shipper.statusText() }
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun dedicatedStatusReportsFailureThenRecoveryWithoutLeakingAuthorityCredentials() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val generations = AtomicInteger()
        val shipper = LogShipper(
            configSnapshot = { snapshot(host = "operator:super-secret@collector.lan") },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, _ ->
                if (generations.getAndIncrement() == 0) {
                    object : LogSink {
                        override fun connect() = Unit
                        override fun send(lines: List<String>) {
                            throw IOException("operator:super-secret@collector.lan refused connection")
                        }
                        override fun close() = Unit
                    }
                } else {
                    RecordingSink()
                }
            },
        )
        try {
            shipper.start()
            capture.emit("trigger failure")
            await { shipper.status().text.startsWith("disconnected (") }
            val failed = shipper.status()
            assertTrue(failed.enabled)
            assertTrue(failed.configured)
            // Credentials cannot leak through a status that carries no destination at all — a
            // stronger guarantee than redacting them out of an address it still printed.
            assertFalse(failed.text, failed.text.contains("super-secret"))
            assertFalse(failed.text, failed.text.contains("collector.lan"))
            assertFalse(failed.text, failed.text.contains("operator"))

            await(timeoutMs = 8_000) { shipper.status().text.startsWith("connected") }
            val recovered = shipper.status()
            // "disconnected" also contains "connected"; the prefix is the only safe discriminator.
            assertTrue(recovered.text, recovered.text.startsWith("connected"))
            assertTrue(recovered.text.contains(" records sent"))
            assertFalse(recovered.text.contains("refused connection"))
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    /**
     * An HTTP sink names the request URL in its error, and that URL may carry userinfo credentials.
     * Only the matched status token may reach the status line — an earlier revision returned the whole
     * message whenever it matched an HTTP status, which put the destination and any credential straight
     * back into a line that exists to omit them.
     */
    @Test fun anHttpFailureExposesOnlyTheStatusTokenNotTheRequestUrlOrItsCredentials() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val shipper = LogShipper(
            configSnapshot = { snapshot(host = "operator:super-secret@collector.lan", protocol = "http") },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, _ ->
                object : LogSink {
                    override fun connect() = Unit
                    override fun send(lines: List<String>) {
                        throw IOException(
                            "HTTP 401 for https://operator:super-secret@collector.lan/ingest?token=abc123",
                        )
                    }
                    override fun close() = Unit
                }
            },
        )
        try {
            shipper.start()
            capture.emit("trigger http failure")
            await { shipper.status().text.startsWith("disconnected (") }
            val text = shipper.status().text

            assertTrue(text, text.contains("HTTP 401"))
            assertFalse(text, text.contains("super-secret"))
            assertFalse(text, text.contains("operator"))
            assertFalse(text, text.contains("collector.lan"))
            assertFalse(text, text.contains("token=abc123"))
            assertFalse(text, text.contains("https://"))
            assertFalse(text, text.contains("/ingest"))
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    /** An address embedded in the host field still determines the route without appearing in status text. */
    @Test fun anEmbeddedSchemeAndPortStillRouteButAreNotRestatedInTheStatus() {
        val routed = AtomicReference<LogShipTarget?>(null)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val shipper = LogShipper(
            configSnapshot = {
                LogShipConfigSnapshot(
                    enabled = true,
                    host = "udp://collector.lan:1514",
                    port = 514,
                    protocol = "syslog-tcp",
                    panelId = "panel-a",
                )
            },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { target, _ ->
                routed.set(target)
                RecordingSink()
            },
        )
        try {
            shipper.start()
            await { routed.get() != null }
            val status = shipper.statusText()
            val target = requireNotNull(routed.get())
            assertEquals("collector.lan", target.host)
            assertEquals(1514, target.port)
            assertEquals("syslog-udp", target.protocol)
            assertFalse(status, "collector.lan" in status)
            assertFalse(status, "1514" in status)
            assertFalse(status, "udp://" in status)
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun queuedLinesShipAsOneMeasuredBoundedBatch() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val connectStarted = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<String>()
        val wall = AtomicLong()
        val costs = FeatureCostRegistry(
            wallNanos = { wall.getAndAdd(100L) },
            threadCpuNanos = { -1L },
            threadId = { 1L },
        )
        val shipper = LogShipper(
            configSnapshot = { snapshot() },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, _ ->
                object : LogSink {
                    override fun connect() {
                        connectStarted.countDown()
                        releaseConnect.await(2, TimeUnit.SECONDS)
                    }

                    override fun send(lines: List<String>) {
                        sent.addAll(lines)
                    }

                    override fun close() {
                        releaseConnect.countDown()
                    }
                }
            },
            featureCosts = costs,
        )
        try {
            shipper.start()
            assertTrue(connectStarted.await(2, TimeUnit.SECONDS))
            capture.emit("one")
            capture.emit("two")
            capture.emit("three")
            releaseConnect.countDown()
            await { sent == listOf("one", "two", "three") }

            val operation = operation(costs, FeatureCostOperation.LOG_SHIP_BATCH)
            assertEquals(1L, operation.getLong("calls"))
            assertEquals(1L, operation.getLong("succeeded"))
            assertEquals(3L, operation.getLong("work_units"))
            assertEquals(11L, operation.getLong("work_bytes"))
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun stopClosesTransportWhileConnectIsBlocked() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val connectStarted = CountDownLatch(1)
        val closeCalled = CountDownLatch(1)
        val shipper = LogShipper(
            configSnapshot = { snapshot() },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, _ ->
                object : LogSink {
                    override fun connect() {
                        connectStarted.countDown()
                        closeCalled.await(10, TimeUnit.SECONDS)
                        throw IOException("closed")
                    }
                    override fun send(lines: List<String>) = Unit
                    override fun close() {
                        closeCalled.countDown()
                    }
                }
            },
        )
        try {
            shipper.start()
            assertTrue(connectStarted.await(2, TimeUnit.SECONDS))
            shipper.stop()
            assertTrue("stop must own and close a blocked transport", closeCalled.await(1, TimeUnit.SECONDS))
            assertEquals(1, capture.closes.get())
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun operation(
        registry: FeatureCostRegistry,
        expected: FeatureCostOperation,
    ): JSONObject {
        val operations = JSONObject(registry.json()).getJSONArray("operations")
        return (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == expected.id }
    }
}
