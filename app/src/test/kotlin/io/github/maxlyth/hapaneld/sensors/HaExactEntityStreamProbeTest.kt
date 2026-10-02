package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.HaAuthOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What the stream owner tells the network-path monitor, driven on virtual time with the owner's
 * monotonic clock bound to the scheduler so probe deadlines and pong timeouts share one clock.
 */
class HaExactEntityStreamProbeTest {
    private class Report(val kind: String, val value: Any?)

    private class RecordingPathObserver : HaNetworkPathObserver {
        val reports = mutableListOf<Report>()
        override fun onSocketState(state: HaSocketState) { reports += Report("socket", state) }
        override fun onRoundTrip(rttMs: Long) { reports += Report("rtt", rttMs) }
        override fun onProbeTimeout() { reports += Report("timeout", null) }
        override fun onConnectionFailure(kind: HaPathFailureKind) { reports += Report("failure", kind) }
    }

    private fun List<Report>.of(kind: String) = filter { it.kind == kind }.map { it.value }

    /** Socket states with consecutive repeats collapsed: only the transitions are asserted. */
    private fun List<Report>.socketTransitions(): List<HaSocketState> =
        of("socket").map { it as HaSocketState }.fold(mutableListOf()) { acc, state ->
            if (acc.lastOrNull() != state) acc += state
            acc
        }

    @Test fun probesGoOutOnTheCadenceEvenWhileEntityTrafficNeverStops() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection(pongDelayMs = 7L, scope = this)
        val transport = FakeTransport(connection)
        val observer = RecordingPathObserver()
        val owner = owner(dispatcher, transport, probeIntervalMs = 100L)
        owner.bindNetworkPath(observer)
        assertEquals(listOf(HaSocketState.STOPPED), observer.reports.socketTransitions())
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        assertEquals(
            listOf(HaSocketState.STOPPED, HaSocketState.CONNECTING, HaSocketState.LIVE),
            observer.reports.socketTransitions(),
        )

        // A state frame every 30 ms: the old idle-timer ping would never have fired.
        repeat(20) {
            advanceTimeBy(30L)
            connection.messages.trySend(HaExactSocketMessage.State(state(ENTITY_A, "$it")))
            runCurrent()
        }
        advanceTimeBy(1L)
        runCurrent()
        // 601 ms of virtual time at a 100 ms cadence with a 7 ms reply: probes at 100, 207, 314, 421, 528.
        assertEquals(5, connection.pings.size)
        assertEquals(List(5) { 7L }, observer.reports.of("rtt"))
        assertTrue(observer.reports.of("timeout").isEmpty())
        owner.close()
    }

    @Test fun theRoundTripIsTheTransportsDecodeStampNotTheConsumersMatchTime() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // The fake decodes and stamps the pong 3 ms after the ping, then the consumer only sees it
        // 20 ms later; a match-time clock would report 23 ms.
        val connection = FakeConnection(pongDelayMs = 3L, scope = this, stampPong = true, deliverLagMs = 20L)
        val transport = FakeTransport(connection)
        val observer = RecordingPathObserver()
        val owner = owner(dispatcher, transport, probeIntervalMs = 100L)
        owner.bindNetworkPath(observer)
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(124L)
        runCurrent()
        assertEquals(listOf<Any?>(3L), observer.reports.of("rtt"))
        // And without a stamp the owner's own clock at the match is the honest fallback.
        val unstamped = FakeConnection(pongDelayMs = 3L, scope = this, stampPong = false, deliverLagMs = 20L)
        val second = owner(dispatcher, FakeTransport(unstamped), probeIntervalMs = 100L)
        val secondObserver = RecordingPathObserver()
        second.bindNetworkPath(secondObserver)
        second.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(124L)
        runCurrent()
        assertEquals(listOf<Any?>(23L), secondObserver.reports.of("rtt"))
        owner.close()
        second.close()
    }

    @Test fun anUnansweredProbeIsReportedOnceAsATimeoutAndTheStreamReconnects() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val silent = FakeConnection(pongDelayMs = null, scope = this)
        val second = FakeConnection(pongDelayMs = 5L, scope = this)
        val transport = FakeTransport(silent, second)
        val observer = RecordingPathObserver()
        val owner = owner(dispatcher, transport, probeIntervalMs = 100L, pongTimeoutMs = 50L, reconnectBaseMs = 10L, reconnectMaxMs = 10L)
        owner.bindNetworkPath(observer)
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(151L)
        runCurrent()
        assertEquals(1, observer.reports.of("timeout").size)
        // No second report for the same miss through the protocol catch.
        assertTrue(observer.reports.of("failure").isEmpty())
        advanceTimeBy(10L)
        runCurrent()
        assertEquals(2, transport.subscribeCount)
        advanceTimeBy(106L)
        runCurrent()
        assertEquals(listOf<Any?>(5L), observer.reports.of("rtt"))
        owner.close()
    }

    @Test fun aMissingPongWhileEntityFramesKeepArrivingIsTheServersAndNeverTearsTheStreamDown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Home Assistant streams state but never answers the ping: overloaded, not unreachable.
        val busy = FakeConnection(pongDelayMs = null, scope = this)
        val transport = FakeTransport(busy)
        val observer = RecordingPathObserver()
        val ambient = RecordingAmbient()
        val owner = owner(dispatcher, transport, probeIntervalMs = 100L, pongTimeoutMs = 50L, reconnectBaseMs = 10L, reconnectMaxMs = 10L, ambient = ambient)
        owner.bindNetworkPath(observer)
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        // Frames every 30 ms across four probe cycles: the pong wait always sees traffic.
        var frames = 0
        repeat(20) {
            advanceTimeBy(30L)
            busy.messages.trySend(HaExactSocketMessage.State(state(ENTITY_A, "${++frames}")))
            runCurrent()
        }
        advanceTimeBy(1L)
        runCurrent()
        // Probes went out on the cadence and every wait timed out busy, not silent.
        assertTrue("pings=${busy.pings.size}", busy.pings.size >= 3)
        assertTrue(observer.reports.of("timeout").isEmpty())
        assertTrue(observer.reports.of("rtt").isEmpty())
        assertEquals(busy.pings.size, observer.reports.of("failure").count { it == HaPathFailureKind.SERVER })
        assertTrue(observer.reports.of("failure").none { it == HaPathFailureKind.NETWORK })
        // The shared stream was never torn down: one subscription, and every frame reached the consumer.
        assertEquals(1, transport.subscribeCount)
        // One hydration update from the REST read, then every streamed frame, none lost to a reconnect.
        assertEquals(frames + 1, ambient.updates.size)
        assertEquals(HaExactEntityStreamPhase.LIVE, ambient.statuses.last().phase)
        owner.close()
    }

    @Test fun aLatePongFromAnAbandonedProbeCountsAsInboundAndNeverTearsTheStreamDown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Home Assistant answers every ping, but a whole probe cycle late: probe N's pong lands
        // inside probe N+1's wait (160 ms, with a 100 ms cadence and a 50 ms pong timeout). The pong
        // never matches the probe being waited on, but it is a frame the socket delivered, so the
        // wait is BUSY. Ignoring it made every wait look SILENT and tore down a live socket.
        val late = FakeConnection(pongDelayMs = 160L, scope = this)
        val transport = FakeTransport(late)
        val observer = RecordingPathObserver()
        val ambient = RecordingAmbient()
        val owner = owner(dispatcher, transport, probeIntervalMs = 100L, pongTimeoutMs = 50L, reconnectBaseMs = 10L, reconnectMaxMs = 10L, ambient = ambient)
        owner.bindNetworkPath(observer)
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        // One entity frame inside the FIRST probe's wait bootstraps the chain; after that each
        // abandoned probe's late pong is the only thing arriving during the next probe's wait.
        advanceTimeBy(120L)
        late.messages.trySend(HaExactSocketMessage.State(state(ENTITY_A, "1")))
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertTrue("pings=${late.pings.size}", late.pings.size >= 4)
        assertTrue("a late pong is proof of life, never a silent timeout", observer.reports.of("timeout").isEmpty())
        // A late pong is not attributable to the probe being waited on, so it is never a round trip.
        assertTrue(observer.reports.of("rtt").isEmpty())
        // Every wait that CONCLUDED was busy; at most the newest probe is still in flight.
        val busyReports = observer.reports.of("failure").count { it == HaPathFailureKind.SERVER }
        assertTrue(
            "pings=${late.pings.size} busy=$busyReports",
            busyReports >= late.pings.size - 1 && busyReports <= late.pings.size,
        )
        assertTrue(observer.reports.of("failure").none { it == HaPathFailureKind.NETWORK })
        // The shared stream survived: one subscription, still LIVE, never re-established.
        assertEquals(1, transport.subscribeCount)
        assertEquals(HaSocketState.LIVE, observer.reports.of("socket").last())
        assertEquals(HaExactEntityStreamPhase.LIVE, ambient.statuses.last().phase)
        owner.close()
    }

    @Test fun aRefusedSignInIsReportedAsAuthNeverAsLoss() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection(pongDelayMs = 5L, scope = this)).apply { rejectSubscriptions = 1 }
        val observer = RecordingPathObserver()
        val owner = owner(dispatcher, transport, reconnectBaseMs = 10L, reconnectMaxMs = 10L)
        owner.bindNetworkPath(observer)
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(20L)
        runCurrent()
        assertEquals(listOf<Any?>(HaPathFailureKind.AUTH), observer.reports.of("failure"))
        assertTrue(observer.reports.of("timeout").isEmpty())
        owner.close()
    }

    @Test fun aRefusedConnectionAndAProtocolFailureAreTheServersAConnectTimeoutIsThePaths() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection(pongDelayMs = 5L, scope = this)).apply {
            subscribeErrors.addAll(listOf(
                ConnectException("Connection refused"),
                HaProtocolException("invalid handshake"),
                io.ktor.client.network.sockets.ConnectTimeoutException("connect timed out"),
            ))
        }
        val observer = RecordingPathObserver()
        val owner = owner(dispatcher, transport, reconnectBaseMs = 10L, reconnectMaxMs = 10L)
        owner.bindNetworkPath(observer)
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        repeat(3) { advanceTimeBy(10L); runCurrent() }
        assertEquals(
            listOf<Any?>(HaPathFailureKind.SERVER, HaPathFailureKind.SERVER, HaPathFailureKind.NETWORK),
            observer.reports.of("failure"),
        )
        owner.close()
    }

    @Test fun anOuterSubscribeDeadlineIsAttributedToTheServer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection(pongDelayMs = 5L, scope = this)).apply { subscribeTimeouts = 1 }
        val observer = RecordingPathObserver()
        val owner = owner(dispatcher, transport, subscribeTimeoutMs = 40L, reconnectBaseMs = 10L, reconnectMaxMs = 10L)
        owner.bindNetworkPath(observer)
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(41L)
        runCurrent()
        assertEquals(listOf<Any?>(HaPathFailureKind.SERVER), observer.reports.of("failure"))
        owner.close()
    }

    @Test fun droppingTheLastDemandAndClosingBothMakeTheVerdictUnreportable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(*Array(4) { FakeConnection(pongDelayMs = 5L, scope = this) })
        val observer = RecordingPathObserver()
        val owner = owner(dispatcher, transport)
        owner.bindNetworkPath(observer)
        // Binding with no demand reports STOPPED, and nothing is measured until a socket is LIVE.
        assertEquals(listOf(HaSocketState.STOPPED), observer.reports.socketTransitions())
        owner.replacePresenceRegistryWatch(true)
        runCurrent()
        assertEquals(HaSocketState.LIVE, observer.reports.of("socket").last())
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        // Adding a second demand while the socket is already wanted re-establishes the stream, but
        // it must never report STOPPED: the measurement is not ended by a demand change that keeps
        // the socket wanted.
        var stopsSoFar = observer.reports.of("socket").count { it == HaSocketState.STOPPED }
        assertEquals(1, stopsSoFar)
        assertEquals(HaSocketState.LIVE, observer.reports.of("socket").last())
        owner.replaceAmbientSource(null)
        runCurrent()
        stopsSoFar = observer.reports.of("socket").count { it == HaSocketState.STOPPED }
        assertEquals("dropping one of two demands must not stop the measurement", 1, stopsSoFar)
        assertEquals(HaSocketState.LIVE, observer.reports.of("socket").last())
        // Dropping the LAST demand stops it.
        owner.replacePresenceRegistryWatch(false)
        runCurrent()
        assertEquals(HaSocketState.STOPPED, observer.reports.of("socket").last())
        owner.replacePresenceRegistryWatch(true)
        runCurrent()
        assertEquals(HaSocketState.LIVE, observer.reports.of("socket").last())
        // Closing the owner ends it too.
        owner.close()
        runCurrent()
        assertEquals(HaSocketState.STOPPED, observer.reports.of("socket").last())
    }

    @Test fun failureAttributionIsAPureFunctionOfTheExceptionChain() {
        assertEquals(HaPathFailureKind.SERVER, haPathFailureKind(ConnectException("refused")))
        assertEquals(HaPathFailureKind.NETWORK, haPathFailureKind(UnknownHostException("ha.local")))
        assertEquals(HaPathFailureKind.NETWORK, haPathFailureKind(NoRouteToHostException("no route")))
        assertEquals(HaPathFailureKind.NETWORK, haPathFailureKind(SocketTimeoutException("read timed out")))
        assertEquals(HaPathFailureKind.NETWORK, haPathFailureKind(io.ktor.client.network.sockets.ConnectTimeoutException("c")))
        assertEquals(HaPathFailureKind.NETWORK, haPathFailureKind(IOException("wrapped", SocketTimeoutException("t"))))
        assertEquals(HaPathFailureKind.SERVER, haPathFailureKind(IOException("wrapped", ConnectException("refused"))))
        assertEquals(HaPathFailureKind.SERVER, haPathFailureKind(IOException("plain")))
        assertEquals(HaPathFailureKind.NETWORK, haPathFailureKind(java.net.SocketException("Network is unreachable")))
        assertEquals(HaPathFailureKind.NETWORK, haPathFailureKind(java.net.SocketException("Host is unreachable")))
        assertEquals(HaPathFailureKind.SERVER, haPathFailureKind(java.net.SocketException("Connection reset")))
        assertEquals(HaPathFailureKind.SERVER, haPathFailureKind(HaProtocolException("bad frame")))
        assertEquals(HaPathFailureKind.SERVER, haPathFailureKind(IllegalStateException("upgrade failed")))
        assertEquals(HaPathFailureKind.AUTH, haPathFailureKind(RuntimeException("x", HaAuthenticationException("no"))))
    }

    private class RecordingAmbient : HaExactEntityStreamObserver {
        val statuses = mutableListOf<HaExactEntityStreamStatus>()
        val updates = mutableListOf<HaExactEntityUpdate>()
        override fun onStatus(status: HaExactEntityStreamStatus) { statuses += status }
        override fun onUpdate(update: HaExactEntityUpdate) { updates += update }
    }

    private fun TestScope.owner(
        dispatcher: CoroutineDispatcher,
        transport: FakeTransport,
        probeIntervalMs: Long = 100L,
        pongTimeoutMs: Long = 50L,
        reconnectBaseMs: Long = 1_000L,
        reconnectMaxMs: Long = 60_000L,
        subscribeTimeoutMs: Long = 35_000L,
        ambient: HaExactEntityStreamObserver = RecordingAmbient(),
    ) = HaExactEntityStreamOwner(
        scope = this,
        auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
        transport = transport,
        workerDispatcher = dispatcher,
        probeIntervalMs = probeIntervalMs,
        pongTimeoutMs = pongTimeoutMs,
        reconnectBaseMs = reconnectBaseMs,
        reconnectMaxMs = reconnectMaxMs,
        subscribeTimeoutMs = subscribeTimeoutMs,
        monotonicMillis = { testScheduler.currentTime },
    ).also { it.bindAmbient(ambient) }

    /**
     * A connection whose pong is decoded [pongDelayMs] after the ping on virtual time (null: never).
     * With [stampPong] the frame carries that decode instant; [deliverLagMs] then holds the decoded
     * frame back before the consumer can see it, which is the channel hop and scheduling delay the
     * stamp exists to exclude.
     */
    private class FakeConnection(
        private val pongDelayMs: Long?,
        private val scope: TestScope,
        private val stampPong: Boolean = false,
        private val deliverLagMs: Long = 0L,
    ) : HaExactEntityConnection {
        val messages = Channel<HaExactSocketMessage>(Channel.UNLIMITED)
        val pings = mutableListOf<Int>()
        override suspend fun receive(): HaExactSocketMessage = messages.receive()
        override suspend fun ping(id: Int) {
            pings += id
            val delayMs = pongDelayMs ?: return
            scope.backgroundScope.launch {
                delay(delayMs)
                val at = if (stampPong) scope.testScheduler.currentTime else -1L
                delay(deliverLagMs)
                messages.trySend(HaExactSocketMessage.Pong(id, at))
            }
        }
        override suspend fun close() { messages.close() }
    }

    private class FakeTransport(vararg connections: FakeConnection) : HaExactEntityStreamTransport {
        private val connections = ArrayDeque(connections.toList())
        var rejectSubscriptions = 0
        var subscribeTimeouts = 0
        val subscribeErrors = ArrayDeque<Exception>()
        var subscribeCount = 0

        override suspend fun subscribe(baseUrl: String, accessToken: String, entityIds: Set<String>): HaExactEntityConnection =
            subscribe(baseUrl, accessToken, entityIds, false)

        override suspend fun subscribe(
            baseUrl: String,
            accessToken: String,
            entityIds: Set<String>,
            watchRegistry: Boolean,
        ): HaExactEntityConnection {
            subscribeCount++
            if (rejectSubscriptions-- > 0) throw HaAuthenticationException("rejected")
            if (subscribeTimeouts-- > 0) awaitCancellation()
            subscribeErrors.removeFirstOrNull()?.let { throw it }
            return connections.removeFirst()
        }

        override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? =
            CompletableDeferred(state(entityId, "1")).await()
    }

    private companion object {
        const val ENTITY_A = "sensor.room_illuminance"
        val OWNER = HaAuthOwner("https://ha.example", "refresh", "client", "")
        fun state(entityId: String, value: String) = JSONObject()
            .put("entity_id", entityId)
            .put("state", value)
            .put("last_updated", "2026-07-17T10:00:00Z")
            .put("attributes", JSONObject().put("device_class", "illuminance").put("unit_of_measurement", "lx"))
    }
}
