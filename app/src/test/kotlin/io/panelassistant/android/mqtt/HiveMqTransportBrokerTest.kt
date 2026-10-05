package io.panelassistant.android.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import io.moquette.broker.Server
import io.panelassistant.android.MqttConnectAnnouncement
import io.panelassistant.android.MqttConnectAnnouncementDispatcher
import io.panelassistant.android.MqttConnectionEvent
import io.panelassistant.android.MqttConnectionEventDispatcher
import io.panelassistant.android.MqttBrokerProgress
import io.panelassistant.android.MqttRecoveryAuthority
import io.panelassistant.android.MqttRecoverySnapshot
import io.panelassistant.android.recordMqttConnack
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class HiveMqTransportBrokerTest {
    @Test(timeout = 30_000)
    fun advertisedButUnroutableAaaaFallsThroughToIpv4OnSameClientReconnect() {
        EmbeddedBroker().use { broker ->
            val resolutions = AtomicInteger()
            val planner = MqttRoutePlanner(
                logicalHost = "mqtt.test.invalid",
                port = broker.port,
                policy = MqttAddressFamilyPolicy.AUTOMATIC,
                initialPreferIpv4 = false,
                rapidInitialFallbackAllowed = true,
                resolver = MqttBrokerResolver {
                    resolutions.incrementAndGet()
                    // AAAA is syntactically valid and advertised, but this test namespace has no
                    // IPv6 route. This reproduces the DNS-present/route-absent failure mode.
                    listOf(InetAddress.getByName("2001:db8::dead"), InetAddress.getByName("127.0.0.1"))
                },
            )
            assertEquals(io.panelassistant.android.MqttAddressFamily.IPV6, planner.resolveInitial()?.family)
            val connected = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val disconnects = AtomicInteger()
            val connectedFamily = AtomicReference<io.panelassistant.android.MqttAddressFamily?>()
            val recovery = MqttRecoveryAuthority("connecting", MqttBrokerProgress(0L, null))
            val recoverySnapshot = AtomicReference<MqttRecoverySnapshot>()
            val connectionGenerations = MqttConnectionGeneration()
            lateinit var connectionEvents: MqttConnectionEventDispatcher
            connectionEvents = MqttConnectionEventDispatcher { sequenced ->
                if (!connectionEvents.isCurrent(sequenced)) return@MqttConnectionEventDispatcher
                val event = sequenced.event as? MqttConnectionEvent.Connected
                    ?: return@MqttConnectionEventDispatcher
                val generation = connectionGenerations.advance()
                recoverySnapshot.set(
                    recordMqttConnack(recovery, generation, false, event.addressFamily, nowMs = 42L),
                )
                connected.countDown()
            }
            val disconnectCause = AtomicReference<String?>()
            val transport = HiveMqTransport()
            val startedAt = System.nanoTime()
            try {
                transport.connect(
                    broker.config("family-failover-${UUID.randomUUID()}").copy(
                        host = "mqtt.test.invalid",
                        routePlanner = planner,
                    ),
                    object : MqttCallbacks {
                        override fun onConnected(
                            connection: MqttConnectionLease,
                            addressFamily: io.panelassistant.android.MqttAddressFamily?,
                        ) {
                            connectedFamily.set(addressFamily)
                            connectionEvents.submit(MqttConnectionEvent.Connected(connection, addressFamily))
                        }

                        override fun onDisconnected(
                            connection: MqttConnectionLease?,
                            causeMessage: String?,
                        ): Boolean {
                            disconnects.incrementAndGet()
                            disconnectCause.set(causeMessage)
                            disconnected.countDown()
                            return true
                        }
                    },
                )

                disconnected.awaitOrFail("unroutable advertised AAAA was not attempted first")
                assertTrue(
                    connected.await(15, TimeUnit.SECONDS),
                    "transport did not suppress failed IPv6 and reconnect over IPv4 " +
                        "(cause=${disconnectCause.get()}, route=${planner.currentRoute?.family}, " +
                        "resolutions=${resolutions.get()})",
                )
                assertTrue(
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt) < 20,
                    "initial family failover fell back to the minute-scale steady-state watchdog",
                )
                assertEquals(io.panelassistant.android.MqttAddressFamily.IPV4, connectedFamily.get())
                assertEquals(
                    1,
                    disconnects.get(),
                    "the failed IPv6 family was retried before the freshly resolved IPv4 route",
                )
                assertTrue(resolutions.get() >= 2, "automatic reconnect reused stale DNS resolution")
                assertEquals(io.panelassistant.android.MqttAddressFamily.IPV4, planner.currentRoute?.family)
                val announced = recoverySnapshot.get()
                assertEquals("announcing", announced.state)
                assertEquals(42L, announced.brokerProgress.lastOkMs)
                assertEquals(announced.connectionGeneration, announced.brokerProgress.connectionGeneration)
            } finally {
                transport.disconnectDetached()
                connectionEvents.closeAndJoin(2_000)
            }
        }
    }

    @Test(timeout = 90_000)
    fun blackHoledFamilyReachesItsLiveSiblingWithinTheBoundedConnect() {
        EmbeddedBroker(host = "::1").use { broker ->
            run {
                val planner = MqttRoutePlanner(
                    logicalHost = "mqtt.test.invalid",
                    port = broker.port,
                    policy = MqttAddressFamilyPolicy.AUTOMATIC,
                    // Start on the black hole. The families are inverted relative to the field report
                    // because only unrouted IPv4 documentation space actually black-holes here: this
                    // container has no IPv6 default route, so a global IPv6 fails instantly instead.
                    // The contract under test is family-symmetric, and what matters is that the first
                    // family swallows the SYN rather than refusing it.
                    initialPreferIpv4 = true,
                    rapidInitialFallbackAllowed = true,
                    resolver = MqttBrokerResolver {
                        // RFC 5737 documentation space is not routed anywhere, so this connect gets no
                        // SYN-ACK and no RST and hangs until the socket bound expires — a real black
                        // hole rather than a simulated one, and unlike the unroutable AAAA in the test
                        // above, which fails immediately and never exercises the bounded connect.
                        listOf(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("::1"))
                    },
                )
                assertEquals(
                    io.panelassistant.android.MqttAddressFamily.IPV4,
                    planner.resolveInitial()?.family,
                )
                val connected = CountDownLatch(1)
                val connectedFamily = AtomicReference<io.panelassistant.android.MqttAddressFamily?>()
                val firstCause = AtomicReference<String?>()
                val disconnected = CountDownLatch(1)
                val transport = HiveMqTransport()
                val startedAt = System.nanoTime()
                try {
                    transport.connect(
                        broker.config("black-hole-${UUID.randomUUID()}").copy(
                            host = "mqtt.test.invalid",
                            routePlanner = planner,
                        ),
                        object : MqttCallbacks {
                            override fun onConnected(
                                connection: MqttConnectionLease,
                                addressFamily: io.panelassistant.android.MqttAddressFamily?,
                            ) {
                                connectedFamily.set(addressFamily)
                                connected.countDown()
                            }

                            override fun onDisconnected(
                                connection: MqttConnectionLease?,
                                causeMessage: String?,
                            ): Boolean {
                                firstCause.compareAndSet(null, causeMessage)
                                disconnected.countDown()
                                return true
                            }
                        },
                    )

                    // A dropped SYN fails only when the socket-connect bound expires, so this wait must
                    // exceed ADDRESS_FAMILY_CONNECT_TIMEOUT_SECONDS rather than the shared short one.
                    assertTrue(
                        disconnected.await(
                            HiveMqTransport.ADDRESS_FAMILY_CONNECT_TIMEOUT_SECONDS + 20L,
                            TimeUnit.SECONDS,
                        ),
                        "the black-holed family never exhausted its connect bound",
                    )
                    // The suppression this recovery depends on is keyed on that classification, so
                    // prove a dropped-SYN timeout really reads as a network failure, never assume it.
                    assertEquals(
                        "unreachable",
                        classifyDisconnect(firstCause.get()),
                        "black-holed connect cause was not classified as a network failure: " +
                            firstCause.get(),
                    )
                    assertTrue(
                        connected.await(60, TimeUnit.SECONDS),
                        "the same client never reached the live sibling family " +
                            "(cause=${firstCause.get()}, route=${planner.currentRoute?.family})",
                    )
                    assertEquals(
                        io.panelassistant.android.MqttAddressFamily.IPV6,
                        connectedFamily.get(),
                    )
                    val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)
                    assertTrue(
                        elapsedSeconds < 45,
                        "reaching the live sibling took ${elapsedSeconds}s, which is the minute-scale " +
                            "watchdog rather than the bounded socket connect",
                    )
                } finally {
                    transport.disconnectDetached()
                }
            }
        }
    }

    @Test(timeout = 20_000)
    fun connectedAnnouncementBackpressureCannotBlockPubAckProcessing() {
        EmbeddedBroker().use { broker ->
            val transport = HiveMqTransport()
            val announcementEntered = CountDownLatch(1)
            val callbackReturned = CountDownLatch(1)
            val allSubmitted = CountDownLatch(1)
            val allAcknowledged = CountDownLatch(1)
            val releaseAnnouncement = CountDownLatch(1)
            val acknowledged = AtomicInteger(0)
            val publications = 256
            val prefix = "ha-paneld-test/${UUID.randomUUID()}/announcement"
            val announcement = MqttConnectAnnouncementDispatcher { connected ->
                announcementEntered.countDown()
                repeat(publications) { index ->
                    transport.publish(
                        "$prefix/$index",
                        byteArrayOf(index.toByte()),
                        retain = false,
                        onComplete = { success ->
                            if (success && acknowledged.incrementAndGet() == publications) {
                                allAcknowledged.countDown()
                            }
                        },
                        expectedConnection = connected.connection,
                    )
                }
                allSubmitted.countDown()
                releaseAnnouncement.await(10, TimeUnit.SECONDS)
            }
            lateinit var connectionEvents: MqttConnectionEventDispatcher
            connectionEvents = MqttConnectionEventDispatcher { sequenced ->
                if (!connectionEvents.isCurrent(sequenced)) return@MqttConnectionEventDispatcher
                val connected = sequenced.event as? MqttConnectionEvent.Connected
                    ?: return@MqttConnectionEventDispatcher
                announcement.submit(MqttConnectAnnouncement(sequenced.sequence, connected.connection))
            }
            val callbacks = object : MqttCallbacks {
                override fun onConnected(
                    connection: MqttConnectionLease,
                    addressFamily: io.panelassistant.android.MqttAddressFamily?,
                ) {
                    connectionEvents.submit(MqttConnectionEvent.Connected(connection))
                    callbackReturned.countDown()
                }

                override fun onDisconnected(
                    connection: MqttConnectionLease?,
                    causeMessage: String?,
                ): Boolean = true
            }

            try {
                transport.connect(broker.config("announcement-${UUID.randomUUID()}"), callbacks)
                callbackReturned.awaitOrFail("connected callback did not return after bounded announcement admission")
                announcementEntered.awaitOrFail("connect announcement worker did not start")
                allSubmitted.awaitOrFail("publish-flow demand did not recover while announcement submitted")
                allAcknowledged.awaitOrFail("PUBACK callbacks were blocked by connect announcement work")
                assertEquals(publications, acknowledged.get())
            } finally {
                releaseAnnouncement.countDown()
                connectionEvents.closeAndJoin(2_000)
                announcement.closeAndJoin(2_000)
                transport.disconnectDetached()
            }
        }
    }

    @Test
    fun qosAckRetainedStateAndCommandFlagsComposeAgainstBroker() {
        EmbeddedBroker().use { broker ->
            val transport = HiveMqTransport()
            val callbacks = RecordingCallbacks()
            val observer = broker.client("observer")
            val prefix = "ha-paneld-test/${UUID.randomUUID()}"
            val stateTopic = "$prefix/state"
            val liveCommandTopic = "$prefix/command/live"
            val retainedCommandTopic = "$prefix/command/retained"
            try {
                observer.connect().await()
                val observedState = MessageLatch()
                observer.subscribe(stateTopic, observedState)

                transport.connect(broker.config("transport"), callbacks)
                callbacks.connected.awaitOrFail("transport did not connect")

                val completion = CountDownLatch(1)
                transport.publish(stateTopic, "online".toByteArray(), retain = true) { success ->
                    assertTrue(success)
                    completion.countDown()
                }
                completion.awaitOrFail("publish completion did not run")
                val liveState = observedState.awaitMessage("observer did not receive state publication")
                assertContentEquals("online".toByteArray(), liveState.payload)
                assertFalse(liveState.retained)
                assertEquals(MqttQos.AT_LEAST_ONCE, liveState.qos)

                val liveCommand = MessageLatch()
                transport.subscribe(liveCommandTopic) { topic, payload, retained -> liveCommand.offer(topic, payload, retained) }
                observer.publishUntilDelivered(liveCommandTopic, "wake".toByteArray(), retain = false, liveCommand)
                val deliveredLiveCommand = liveCommand.awaitMessage("transport did not receive live command")
                assertEquals(liveCommandTopic, deliveredLiveCommand.topic)
                assertContentEquals("wake".toByteArray(), deliveredLiveCommand.payload)
                assertFalse(deliveredLiveCommand.retained)

                observer.publish(retainedCommandTopic, "sleep".toByteArray(), retain = true)
                val retainedCommand = MessageLatch()
                transport.subscribe(retainedCommandTopic) { topic, payload, retained -> retainedCommand.offer(topic, payload, retained) }
                val deliveredRetainedCommand = retainedCommand.awaitMessage("transport did not receive retained command")
                assertEquals(retainedCommandTopic, deliveredRetainedCommand.topic)
                assertContentEquals("sleep".toByteArray(), deliveredRetainedCommand.payload)
                assertTrue(deliveredRetainedCommand.retained)

                broker.client("retained-reader").use { reader ->
                    reader.connect().await()
                    val retainedState = MessageLatch()
                    reader.subscribe(stateTopic, retainedState)
                    val deliveredRetainedState = retainedState.awaitMessage("fresh client did not receive retained state")
                    assertContentEquals("online".toByteArray(), deliveredRetainedState.payload)
                    assertTrue(deliveredRetainedState.retained)
                }
            } finally {
                transport.disconnectDetached()
                observer.close()
            }
        }
    }

    @Test(timeout = 20_000)
    fun finalPublicationsAreAcknowledgedBeforeTransportDisconnects() {
        EmbeddedBroker().use { broker ->
            val transportClientId = "transport-${UUID.randomUUID()}"
            val transport = HiveMqTransport()
            val callbacks = RecordingCallbacks()
            val observer = broker.client("observer")
            val prefix = "ha-paneld-test/${UUID.randomUUID()}"
            val availabilityTopic = "$prefix/availability"
            val tombstoneTopic = "$prefix/discovery"
            try {
                observer.connect().await()
                observer.publish(tombstoneTopic, "old-config".toByteArray(), retain = true)
                val availability = MessageLatch()
                observer.subscribe(availabilityTopic, availability)

                transport.connect(broker.config(transportClientId), callbacks)
                callbacks.connected.awaitOrFail("transport did not connect")

                val startedAt = System.nanoTime()
                val finalCompletion = transport.publishThenDisconnect(
                    listOf(
                        MqttFinalPublish(availabilityTopic, "offline".toByteArray(), retain = true),
                        MqttFinalPublish(tombstoneTopic, byteArrayOf(), retain = true),
                    ),
                    timeoutMs = 5_000,
                )
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500, "final publication must not block the lifecycle caller")

                assertContentEquals("offline".toByteArray(), availability.awaitMessage("broker did not receive final availability").payload)
                awaitCondition("transport remained connected after final publications") {
                    broker.server.listConnectedClients().none { it.clientID == transportClientId }
                }
                finalCompletion.get(6, TimeUnit.SECONDS)

                broker.client("retained-reader").use { reader ->
                    reader.connect().await()
                    val retainedAvailability = MessageLatch()
                    reader.subscribe(availabilityTopic, retainedAvailability)
                    val deliveredAvailability = retainedAvailability.awaitMessage("final availability was not retained")
                    assertContentEquals("offline".toByteArray(), deliveredAvailability.payload)
                    assertTrue(deliveredAvailability.retained)

                    val clearedDiscovery = MessageLatch()
                    reader.subscribe(tombstoneTopic, clearedDiscovery)
                    assertFalse(clearedDiscovery.await(350, TimeUnit.MILLISECONDS), "empty final publication did not clear retained discovery")
                }
            } finally {
                transport.disconnectDetached()
                observer.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun stalledAcknowledgementYieldsToBoundedFinalPublishTimeout() {
        EmbeddedBroker().use { broker ->
            AckDroppingProxy(broker.port).use { proxy ->
                val transportClientId = "transport-${UUID.randomUUID()}"
                val transport = HiveMqTransport()
                val callbacks = RecordingCallbacks()
                val observer = broker.client("observer")
                val availabilityTopic = "ha-paneld-test/${UUID.randomUUID()}/availability"
                try {
                    observer.connect().await()
                    val availability = MessageLatch()
                    observer.subscribe(availabilityTopic, availability)
                    transport.connect(broker.config(transportClientId).copy(port = proxy.port), callbacks)
                    callbacks.connected.awaitOrFail("transport did not connect through acknowledgement proxy")

                    val timeoutMs = 3_000L
                    val startedAt = System.nanoTime()
                    val finalCompletion = transport.publishThenDisconnect(
                        listOf(MqttFinalPublish(availabilityTopic, "offline".toByteArray(), retain = true)),
                        timeoutMs = timeoutMs,
                    )
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500, "final publication blocked the lifecycle caller")

                    assertContentEquals("offline".toByteArray(), availability.awaitMessage("broker did not receive the unacknowledged final publication").payload)
                    proxy.pubAckDropped.awaitOrFail("proxy did not observe and suppress the broker PUBACK")
                    assertTrue(
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < timeoutMs,
                        "transport detached before the configured timeout",
                    )
                    assertTrue(
                        broker.server.listConnectedClients().any { it.clientID == transportClientId },
                        "transport disconnected before timeout while its PUBACK was suppressed",
                    )
                    assertFalse(finalCompletion.isDone, "final MQTT completion resolved before ACK or timeout")

                    val timeoutDeadline = startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 300)
                    while (System.nanoTime() < timeoutDeadline) Thread.sleep(20)
                    finalCompletion.get(1, TimeUnit.SECONDS)
                    val rejected = CountDownLatch(1)
                    var accepted: Boolean? = null
                    transport.publish("$availabilityTopic/probe", byteArrayOf(1), retain = false) { success ->
                        accepted = success
                        rejected.countDown()
                    }
                    assertTrue(rejected.await(250, TimeUnit.MILLISECONDS), "transport remained attached after final-publication timeout")
                    assertEquals(false, accepted)
                    assertEquals(0, callbacks.disconnected.get(), "timed-out client leaked a disconnect callback after detachment")

                    broker.client("retained-reader").use { reader ->
                        reader.connect().await()
                        val retained = MessageLatch()
                        reader.subscribe(availabilityTopic, retained)
                        val delivered = retained.awaitMessage("unacknowledged final publication was not retained by the broker")
                        assertContentEquals("offline".toByteArray(), delivered.payload)
                        assertTrue(delivered.retained)
                    }
                } finally {
                    transport.disconnectDetached()
                    observer.close()
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun supersededClientCannotReportDisconnectOrOwnReplacementPublication() {
        EmbeddedBroker().use { oldBroker ->
            EmbeddedBroker().use { replacementBroker ->
                val transport = HiveMqTransport()
                val oldCallbacks = RecordingCallbacks()
                val replacementCallbacks = RecordingCallbacks()
                val observer = replacementBroker.client("observer")
                val prefix = "ha-paneld-test/${UUID.randomUUID()}"
                val staleTopic = "$prefix/stale"
                val currentTopic = "$prefix/current"
                try {
                    transport.connect(oldBroker.config("old-transport"), oldCallbacks)
                    oldCallbacks.connected.awaitOrFail("old transport did not connect")
                    val oldLease = oldCallbacks.connection.get()

                    observer.connect().await()
                    val staleObserved = MessageLatch()
                    val currentObserved = MessageLatch()
                    observer.subscribe(staleTopic, staleObserved)
                    observer.subscribe(currentTopic, currentObserved)
                    transport.connect(replacementBroker.config("replacement-transport"), replacementCallbacks)
                    replacementCallbacks.connected.awaitOrFail("replacement transport did not connect")
                    val replacementLease = replacementCallbacks.connection.get()

                    oldBroker.stop()
                    Thread.sleep(350)
                    assertEquals(0, oldCallbacks.disconnected.get(), "superseded client leaked a disconnect callback")

                    val staleCompletion = CountDownLatch(1)
                    transport.publish(
                        staleTopic,
                        "stale".toByteArray(),
                        retain = false,
                        onComplete = { success ->
                            assertFalse(success)
                            staleCompletion.countDown()
                        },
                        expectedConnection = oldLease,
                    )
                    staleCompletion.awaitOrFail("stale publication was not rejected")
                    assertFalse(
                        staleObserved.await(350, TimeUnit.MILLISECONDS),
                        "old connection work crossed into the replacement broker session",
                    )

                    val completion = CountDownLatch(1)
                    transport.publish(
                        currentTopic,
                        "current".toByteArray(),
                        retain = false,
                        onComplete = { success ->
                        assertTrue(success)
                        completion.countDown()
                        },
                        expectedConnection = replacementLease,
                    )
                    completion.awaitOrFail("replacement publication did not complete")
                    assertContentEquals(
                        "current".toByteArray(),
                        currentObserved.awaitMessage("replacement broker did not receive publication").payload,
                    )
                } finally {
                    transport.disconnectDetached()
                    observer.close()
                }
            }
        }
    }

    private class EmbeddedBroker(private val host: String = "127.0.0.1") : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val server = Server().withConfig()
            .host(host)
            .port(port)
            .disablePersistence()
            .disableTelemetry()
            .startServer()

        private var running = true

        fun config(clientId: String) = MqttConnectConfig(
            host = "127.0.0.1",
            port = port,
            tls = false,
            clientId = clientId,
            user = null,
            password = null,
            keepAliveSeconds = 10,
            willTopic = "ha-paneld-test/$clientId/availability",
            willPayload = "offline",
        )

        fun client(name: String): TestClient = TestClient(
            MqttClient.builder()
                .useMqttVersion5()
                .identifier("$name-${UUID.randomUUID()}")
                .serverHost("127.0.0.1")
                .serverPort(port)
                .buildAsync(),
        )

        fun stop() {
            if (running) {
                running = false
                server.stopServer()
            }
        }

        override fun close() = stop()
    }

    private class AckDroppingProxy(targetPort: Int) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val running = AtomicBoolean(true)
        private val sockets = ConcurrentLinkedQueue<Socket>()
        val port: Int = listener.localPort
        val pubAckDropped = CountDownLatch(1)

        private val acceptThread = Thread({
            while (running.get()) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                val broker = try {
                    Socket(InetAddress.getLoopbackAddress(), targetPort)
                } catch (_: Exception) {
                    client.close()
                    break
                }
                sockets += client
                sockets += broker
                forward("mqtt-proxy-client", client.inputStream, broker.outputStream, client, broker, parseFrames = false)
                forward("mqtt-proxy-broker", broker.inputStream, client.outputStream, client, broker, parseFrames = true)
            }
        }, "mqtt-proxy-accept").apply { isDaemon = true; start() }

        private fun forward(
            name: String,
            input: InputStream,
            output: OutputStream,
            client: Socket,
            broker: Socket,
            parseFrames: Boolean,
        ) {
            Thread({
                try {
                    if (parseFrames) {
                        while (running.get()) {
                            val frame = readFrame(input) ?: break
                            if ((frame[0].toInt() and 0xf0) == 0x40) {
                                pubAckDropped.countDown()
                            } else {
                                output.write(frame)
                                output.flush()
                            }
                        }
                    } else {
                        input.copyTo(output)
                        output.flush()
                    }
                } catch (_: Exception) {
                    // Closing either side is the proxy's normal teardown signal.
                } finally {
                    runCatching { client.close() }
                    runCatching { broker.close() }
                }
            }, name).apply { isDaemon = true; start() }
        }

        private fun readFrame(input: InputStream): ByteArray? {
            val first = input.read()
            if (first < 0) return null
            val header = ByteArrayOutputStream(5)
            header.write(first)
            var multiplier = 1
            var remaining = 0
            repeat(4) {
                val encoded = input.read()
                if (encoded < 0) return null
                header.write(encoded)
                remaining += (encoded and 0x7f) * multiplier
                if ((encoded and 0x80) == 0) {
                    val payload = input.readNBytes(remaining)
                    if (payload.size != remaining) return null
                    return header.toByteArray() + payload
                }
                multiplier *= 128
            }
            error("invalid MQTT remaining-length field")
        }

        override fun close() {
            running.set(false)
            runCatching { listener.close() }
            sockets.forEach { runCatching { it.close() } }
            acceptThread.join(1_000)
        }
    }

    private class TestClient(private val client: Mqtt5AsyncClient) : AutoCloseable {
        fun connect() = client.connect()

        fun subscribe(topic: String, messages: MessageLatch) {
            client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { messages.offer(it.topic.toString(), it.payloadAsBytes, it.isRetain, it.qos) }
                .send()
                .await()
        }

        fun publish(topic: String, payload: ByteArray, retain: Boolean) {
            client.publishWith()
                .topic(topic)
                .payload(payload)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .send()
                .await()
        }

        fun publishUntilDelivered(topic: String, payload: ByteArray, retain: Boolean, messages: MessageLatch) {
            repeat(20) {
                publish(topic, payload, retain)
                if (messages.await(50, TimeUnit.MILLISECONDS)) return
            }
        }

        override fun close() {
            runCatching { client.disconnect().await() }
        }
    }

    private class RecordingCallbacks : MqttCallbacks {
        val connected = CountDownLatch(1)
        val disconnected = AtomicInteger(0)
        val connection = AtomicReference<MqttConnectionLease>()

        override fun onConnected(
            connection: MqttConnectionLease,
            addressFamily: io.panelassistant.android.MqttAddressFamily?,
        ) {
            this.connection.set(connection)
            connected.countDown()
        }

        override fun onDisconnected(
            connection: MqttConnectionLease?,
            causeMessage: String?,
        ): Boolean {
            disconnected.incrementAndGet()
            return true
        }
    }

    private data class Message(val topic: String, val payload: ByteArray, val retained: Boolean, val qos: MqttQos?)

    private class MessageLatch {
        private val latch = CountDownLatch(1)
        @Volatile private var message: Message? = null

        fun offer(topic: String, payload: ByteArray, retained: Boolean, qos: MqttQos? = null) {
            message = Message(topic, payload, retained, qos)
            latch.countDown()
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean = latch.await(timeout, unit)

        fun awaitMessage(failure: String): Message {
            latch.awaitOrFail(failure)
            return requireNotNull(message)
        }
    }

    private companion object {
        private const val BROKER_OPERATION_TIMEOUT_SECONDS = 30L

        fun CountDownLatch.awaitOrFail(message: String) {
            assertTrue(await(5, TimeUnit.SECONDS), message)
        }

        // Embedded broker handshakes share the loaded JVM/CI executor with the full test suite. This
        // ceiling protects against a genuine deadlock without turning transient runner starvation into
        // an MQTT ownership failure; behavioral deadlines remain narrow at each assertion above.
        fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
            get(BROKER_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) {
                assertTrue(System.nanoTime() < deadline, message)
                Thread.sleep(20)
            }
        }
    }
}
