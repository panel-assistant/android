package io.panelassistant.android.panelassistant

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.logship.LogCapture
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.sensors.HaApiSessionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelAssistantLogsTest {
    @Test fun grantedSessionReportsTheRealCapturesRedactedEvent() = runTest {
        val connection = Connection(sources = JSONObject().put("app", 5))
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("W", "password=hunter2secret"))
            assertEquals("granted log must reach the authenticated native session", 1, connection.reports.size)
            assertTrue(connection.hello.getJSONArray("capabilities").let { offered ->
                (0 until offered.length()).any { offered.getString(it) == "logs" }
            })
            val report = connection.reports.single()
            assertEquals("session", report.getString("session"))
            val record = report.getJSONArray("records").getJSONObject(0)
            assertEquals("app", record.getString("source"))
            assertEquals(5, record.getInt("priority"))
            assertEquals("Example", record.getString("tag"))
            assertEquals(123, record.getInt("pid"))
            assertEquals("password=***", record.getString("message"))
            assertEquals(EVENT.toString(), record.getString("timestamp"))
            assertTrue(fixture.forwarding.last())
            assertFalse(report.toString().contains("hunter2secret"))
            assertEquals(listOf("password=***"), fixture.raw.map { it.substringAfter("Example: ") })
        } finally { fixture.close() }
    }

    @Test fun missingGrantLeavesDirectShippingAndEmitsNoNativeLogs() = runTest {
        val connection = Connection(sources = null)
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("E", "ordinary error"))
            assertTrue(connection.reports.isEmpty())
            assertFalse(fixture.forwarding.any { it })
        } finally { fixture.close() }
    }

    @Test fun emptySourceGrantLeavesDirectShippingActive() = runTest {
        val connection = Connection(sources = JSONObject())
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("F", "no destination configured"))
            assertTrue(connection.reports.isEmpty())
            assertFalse(fixture.forwarding.any { it })
        } finally { fixture.close() }
    }

    @Test fun systemOnlyGrantRoutesCompleteSystemEventsAndLeavesAppEventsOff() = runTest {
        val connection = Connection(sources = JSONObject().put("system", 6))
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("F", "app source omitted"))
            assertTrue(connection.reports.isEmpty())
            fixture.emitSystem(
                "[ ${EVENT.epochSecond}.${EVENT.nano.toString().padStart(9, '0')}  123: 456 E/AndroidRuntime ]",
                "failure password=hidden\\n    at Example.first(Example.kt:12)", "",
            )
            val record = connection.reports.single().getJSONArray("records").getJSONObject(0)
            assertEquals("system", record.getString("source"))
            assertEquals(EVENT.toString(), record.getString("timestamp"))
            assertEquals("failure password=***\n    at Example.first(Example.kt:12)", record.getString("message"))
        } finally { fixture.close() }
    }

    @Test fun oversizedUtf8EventDropsWholeRecordAndReportsTheDrop() = runTest {
        val connection = Connection(sources = JSONObject().put("app", 5))
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("W", "é".repeat(4_097)), line("W", "fits"))
            assertEquals(listOf("fits"), connection.reports.single().messages())
            assertEquals(1, connection.reports.single().getInt("dropped"))
        } finally { fixture.close() }
    }

    @Test fun utf8BatchBudgetSplitsRetainedEventsWithoutTruncation() = runTest {
        val connection = Connection(sources = JSONObject().put("app", 5))
        val fixture = Fixture(this, connection, capacity = 100)
        try {
            fixture.start()
            val messages = (1..40).map { "$it:" + "é".repeat(4_000) }
            fixture.emit(*messages.map { line("W", it) }.toTypedArray())
            val first = connection.reports.single()
            assertTrue(first.getJSONArray("records").length() in 1..39)
            assertTrue(first.getJSONArray("records").toString().toByteArray(Charsets.UTF_8).size <= 262_144)
            connection.ack(first)
            runCurrent()
            assertEquals(2, connection.reports.size)
            assertEquals(messages, connection.reports.flatMap { it.messages() })
            assertEquals(0, connection.reports.sumOf { it.getInt("dropped") })
        } finally { fixture.close() }
    }

    @Test fun failedDeliveryIsNotReplayedWhenTheNextEventArrives() = runTest {
        val connection = Connection(sources = JSONObject().put("app", 5))
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("W", "failed once"))
            connection.reject(connection.reports.single())
            runCurrent()
            assertEquals(2, connection.reports.size)
            assertTrue(connection.reports.last().messages().isEmpty())
            assertEquals("discarded failed batch must be observable", 1, connection.reports.last().getInt("dropped"))
            connection.ack(connection.reports.last())
            runCurrent()
            fixture.emit(line("W", "later event"))
            assertEquals(listOf(listOf("failed once"), emptyList(), listOf("later event")), connection.reports.map { it.messages() })
        } finally { fixture.close() }
    }

    @Test fun acknowledgementTimeoutRestoresDirectShippingAndTheNextHelloCannotReuseTheOldGrant() = runTest {
        val first = Connection(sources = JSONObject().put("app", 5))
        val second = Connection(sources = null)
        val fixture = Fixture(this, first, second)
        try {
            fixture.start()
            fixture.emit(line("W", "unacknowledged"))
            assertEquals(1, first.reports.size)
            advanceTimeBy(15_000)
            runCurrent()
            assertFalse(fixture.forwarding.last())
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("panel_assistant/hello", second.hello.getString("type"))
            fixture.emit(line("E", "new session has no log grant"))
            assertTrue(second.reports.isEmpty())
            assertFalse(fixture.forwarding.last())
        } finally { fixture.close() }
    }

    @Test fun grantedPriorityFloorSuppressesLowerPriorityCaptureEvents() = runTest {
        val connection = Connection(sources = JSONObject().put("app", 5))
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("D", "debug hidden"), line("I", "info hidden"), line("W", "warning retained"))
            assertEquals("warning must be reported even when lower priorities precede it", 1, connection.reports.size)
            assertEquals(listOf("warning retained"), connection.reports.single().messages())
            assertEquals(3, fixture.raw.size)
        } finally { fixture.close() }
    }

    @Test fun boundedQueueDropsOldestAndWaitsForTheOutstandingRequest() = runTest {
        val connection = Connection(sources = JSONObject().put("app", 5))
        val fixture = Fixture(this, connection)
        try {
            fixture.start()
            fixture.emit(line("W", "outstanding"))
            assertEquals("first batch must be sent", 1, connection.reports.size)
            fixture.emit(*(1..5).map { line("W", "queued $it") }.toTypedArray())
            assertEquals("one outstanding native log request", 1, connection.reports.size)
            connection.ack(connection.reports.single())
            runCurrent()
            assertEquals(2, connection.reports.size)
            assertEquals(listOf("queued 3", "queued 4", "queued 5"), connection.reports.last().messages())
            assertEquals(2, connection.reports.last().getInt("dropped"))
        } finally { fixture.close() }
    }

    @Test fun unsentEventKeepsItsTimeAcrossReconnectWithoutReplayingTheAmbiguousBatch() = runTest {
        val first = Connection(sources = JSONObject().put("app", 5))
        val second = Connection(sources = JSONObject().put("app", 5))
        val fixture = Fixture(this, first, second)
        try {
            fixture.start()
            fixture.emit(line("W", "ambiguous sent batch"))
            assertEquals("first batch must be sent before disconnect", 1, first.reports.size)
            fixture.emit(line("E", "queued before outage"))
            first.inbound.close(IOException("socket lost before acknowledgement"))
            runCurrent()
            assertFalse(fixture.forwarding.last())
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("unsent event must survive reconnection", 1, second.reports.size)
            assertEquals(listOf("queued before outage"), second.reports.single().messages())
            val event = second.reports.single().getJSONArray("records").getJSONObject(0)
            assertEquals(EVENT.toString(), event.getString("timestamp"))
            assertTrue(fixture.forwarding.last())
        } finally { fixture.close() }
    }

    private class Fixture(private val scope: TestScope, vararg connections: Connection, capacity: Int = 3) : AutoCloseable {
        private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val input = Channel<Pair<List<String>, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        private val systemInput = Channel<Pair<List<String>, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        val raw = java.util.concurrent.CopyOnWriteArrayList<String>()
        val forwarding = mutableListOf<Boolean>()
        private fun capture(input: Channel<Pair<List<String>, CompletableDeferred<Unit>>>, longRecords: Boolean = false) =
            LogCapture(captureScope, emptyList(), { emptyList() }, longLogcatRecords = longRecords, lineStream = { emit ->
            for ((lines, done) in input) {
                lines.forEach(emit)
                done.complete(Unit)
            }
        })
        private val capture = capture(input)
        private val systemCapture = capture(systemInput, longRecords = true)
        private val viewer = capture.subscribe(raw::add)
        private val sender = PanelAssistantLogs(mapOf("app" to capture, "system" to systemCapture), forwarding::add, { JSONObject() }, capacity)
        private val pending = ArrayDeque(connections.toList())
        private var accepted = false
        private val owner = PanelAssistantTransportOwner(
            scope = scope.backgroundScope,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "access", owner = CREDENTIAL) },
            connector = PanelAssistantTransportConnector { _, _ -> pending.removeFirstOrNull() ?: throw IOException("offline") },
            workerDispatcher = StandardTestDispatcher(scope.testScheduler),
            monotonicMillis = { scope.testScheduler.currentTime },
            jitter = { 0 },
            observeForHello = { true },
            log = {},
            onConnection = { _, _ -> accepted = true; true },
            logs = sender,
        )

        fun start() {
            owner.replaceDemand(PanelAssistantTransportDemand(CREDENTIAL, IDENTITY))
            scope.runCurrent()
            assertTrue("real owner must accept the authenticated hello before log assertions", accepted)
        }

        fun emit(vararg lines: String) = emit(input, lines)
        fun emitSystem(vararg lines: String) = emit(systemInput, lines)

        private fun emit(input: Channel<Pair<List<String>, CompletableDeferred<Unit>>>, lines: Array<out String>) {
            val done = CompletableDeferred<Unit>()
            val captured = CountDownLatch(1)
            done.invokeOnCompletion { captured.countDown() }
            check(input.trySend(lines.toList() to done).isSuccess)
            // Keep virtual acknowledgement deadlines still while the real capture IO thread emits.
            assertTrue("capture IO did not emit input", captured.await(3, TimeUnit.SECONDS))
            scope.runCurrent()
        }

        override fun close() {
            owner.close()
            viewer.close()
            capture.close()
            systemCapture.close()
            captureScope.cancel()
        }
    }

    private class Connection(private val sources: JSONObject?) : PanelAssistantTransportConnection {
        val inbound = Channel<String>(Channel.UNLIMITED)
        private val sent = mutableListOf<JSONObject>()
        val hello: JSONObject get() = sent.single { it.optString("type") == "panel_assistant/hello" }
        val reports: List<JSONObject> get() = sent.filter { it.optString("type") == "panel_assistant/report_log" }

        override suspend fun send(text: String) {
            val frame = JSONObject(text)
            sent += frame
            when (frame.getString("type")) {
                "panel_assistant/hello" -> {
                    // Like PA, grant only capabilities this panel offered; older panels still authenticate.
                    val offered = frame.getJSONArray("capabilities")
                    val grantLogs = sources != null && (0 until offered.length()).any { offered.getString(it) == "logs" }
                    inbound.trySend(JSONObject().put("id", frame.getLong("id"))
                    .put("type", "result").put("success", true).put("result", JSONObject()
                        .put("protocol", 5).put("session", "session")
                        .put("integration", JSONObject().put("version", "0.3.0"))
                        .put("capabilities", JSONArray(if (grantLogs) listOf("logs") else emptyList<String>()))
                        .put("channels", JSONObject().put("accepted", 0).put("unknown", JSONArray()))
                        .apply { if (grantLogs) put("logs", JSONObject().put("sources", sources)) }).toString())
                }
                "ping" -> inbound.trySend(JSONObject().put("id", frame.getLong("id")).put("type", "pong").toString())
            }
        }

        fun ack(frame: JSONObject) {
            inbound.trySend(JSONObject().put("id", frame.getLong("id")).put("type", "result")
                .put("success", true).put("result", JSONObject().put("accepted", frame.getJSONArray("records").length())
                    .put("dropped", 0)).toString())
        }

        fun reject(frame: JSONObject) {
            inbound.trySend(JSONObject().put("id", frame.getLong("id")).put("type", "result")
                .put("success", false).put("error", JSONObject().put("code", "log_delivery_failed").put("message", "failed")).toString())
        }

        override suspend fun receive(timeoutMs: Long): String? = select {
            inbound.onReceive { it }
            onTimeout(timeoutMs) { null }
        }

        override suspend fun close() { inbound.close() }
    }

    private companion object {
        val CREDENTIAL = HaAuthOwner("https://ha.example", "refresh", "", "")
        val IDENTITY = PanelAssistantHelloIdentity("0".repeat(64), "1.0.0-rc1", 1234)
        val EVENT: Instant = Instant.now().minusSeconds(600).let { Instant.ofEpochMilli(it.toEpochMilli()) }
        val THREADTIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        fun line(priority: String, message: String) = "${THREADTIME.format(EVENT)}  123  456 $priority Example: $message"
        fun JSONObject.messages(): List<String> = getJSONArray("records").let { records ->
            (0 until records.length()).map { records.getJSONObject(it).getString("message") }
        }
    }
}
