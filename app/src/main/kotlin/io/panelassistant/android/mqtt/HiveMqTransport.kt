package io.panelassistant.android.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.util.SingleFlightExecutor

/**
 * The real [MqttTransport] over the HiveMQ MQTT5 async client. Owns the client instance, the
 * connected/disconnected listener wiring, and the **generation guard**: listeners act for the CURRENT
 * client only, and a superseded client is told to stop auto-reconnecting. Without this guard a zombie
 * client's disconnected-listener overwrote the live connection's state and spawned reconnect storms
 * (the long-unexplained NOT_AUTHORIZED-while-connected incident).
 */
class HiveMqTransport : MqttTransport {
    private data class Session(
        val client: Mqtt5AsyncClient?,
        val connection: MqttConnectionLease?,
        val route: MqttDialRoute?,
    )

    private val sessionLock = Any()
    private var session = Session(null, null, null)

    override fun connect(config: MqttConnectConfig, callbacks: MqttCallbacks) {
        var self: Mqtt5AsyncClient? = null
        var builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(config.clientId)
            .addConnectedListener {
                synchronized(sessionLock) {
                    if (session.client === self) {
                        val lease = MqttConnectionLease()
                        val route = session.route
                        session = Session(checkNotNull(self), lease, route)
                        config.routePlanner?.markConnected(route)
                        // Production callbacks perform one fixed-cardinality enqueue. Keeping that enqueue
                        // in the tuple lock preserves transition order against a replacement client.
                        callbacks.onConnected(lease, route?.family)
                    }
                }
            }
            .addDisconnectedListener { ctx ->
                val (current, reconnectAllowed, preConnackFailure) = synchronized(sessionLock) {
                    if (session.client !== self) DisconnectDecision.NOT_CURRENT
                    else {
                        val lease = session.connection
                        val route = session.route
                        session = Session(checkNotNull(self), null, route)
                        DisconnectDecision(
                            current = true,
                            reconnectAllowed = callbacks.onDisconnected(
                                lease,
                                ctx.cause?.message ?: ctx.cause?.toString(),
                            ),
                            preConnackFailure = lease == null,
                        )
                    }
                }
                if (!current || !reconnectAllowed) {
                    runCatching { ctx.reconnector.reconnect(false) } // zombie: kill its auto-reconnect
                } else if (config.automaticReconnect && config.routePlanner != null) {
                    val networkFailure = classifyDisconnect(
                        ctx.cause?.message ?: ctx.cause?.toString(),
                    ) == "unreachable"
                    val routeFuture = config.routePlanner.resolveReconnect(
                        preConnackFailure = preConnackFailure,
                        networkFailure = networkFailure,
                    )
                    ctx.reconnector.reconnectWhen(routeFuture) { route, failure ->
                        if (failure != null || route == null) {
                            runCatching { ctx.reconnector.reconnect(false) }
                        } else {
                            val reconnected = synchronized(sessionLock) {
                                if (session.client !== self) false else try {
                                    route.socketAddress()?.let { socketAddress ->
                                        ctx.reconnector.transportConfig()
                                            .serverAddress(socketAddress)
                                            .applyTransportConfig()
                                    }
                                    session = Session(checkNotNull(self), null, route)
                                    ctx.reconnector.reconnect(true)
                                    true
                                } catch (routeFailure: Throwable) {
                                    // Do not silently strand a current client if Hive rejects its
                                    // transport update. The bridge records the failure and its ordinary
                                    // watchdog can replace this client after reconnect is disabled below.
                                    callbacks.onDisconnected(
                                        null,
                                        "MQTT route reconfiguration failed: " +
                                            (routeFailure.message ?: routeFailure.javaClass.simpleName),
                                    )
                                    false
                                }
                            }
                            if (!reconnected) runCatching { ctx.reconnector.reconnect(false) }
                        }
                    }
                }
            }
        val initialRoute = config.routePlanner?.currentRoute
        val initialAddress = initialRoute?.socketAddress()
        builder = if (initialAddress != null) {
            builder.serverAddress(initialAddress)
        } else {
            builder.serverHost(config.host).serverPort(config.port)
        }
        if (config.routePlanner != null) {
            // Pin HiveMQ 1.3.17's current socket default for family-planned connections. A black-holed
            // first family therefore reaches the sibling within this bound, while steady reconnects
            // keep the timing they already had rather than inheriting the 60-second MQTT CONNECT bound.
            builder = builder.transportConfig()
                .socketConnectTimeout(
                    ADDRESS_FAMILY_CONNECT_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
                .applyTransportConfig()
        }
        // ssl:///mqtts:// broker → TLS with the default JVM trust store (CA-signed cert validates).
        // Auto-reconnect so a network blip / broker restart never permanently orphans a CONFIGURED
        // panel; re-subscribe + re-publish discovery happen in onConnected on every connect. The
        // credential-less discovery probe opts out — see MqttConnectConfig.automaticReconnect.
        if (config.automaticReconnect) builder = builder.automaticReconnectWithDefaultConfig()
        if (config.tls) builder = builder.sslWithDefaultConfig()
        val c = builder.buildAsync()
        self = c
        synchronized(sessionLock) { session = Session(c, null, initialRoute) }
        val connect = c.connectWith()
            .keepAlive(config.keepAliveSeconds)
            // Advertise an application-sized inbound ceiling in CONNECT. HiveMQ enforces it while
            // decoding, before a malicious/broken broker can materialize an MQTT-sized payload.
            .restrictions()
            .maximumPacketSize(MAX_INBOUND_PACKET_BYTES)
            .receiveMaximum(MAX_INBOUND_IN_FLIGHT)
            .applyRestrictions()
            .willPublish()
            .topic(config.willTopic)
            .payload(config.willPayload.toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()
        if (!config.user.isNullOrEmpty()) {
            connect.simpleAuth()
                .username(config.user)
                .password((config.password ?: "").toByteArray())
                .applySimpleAuth()
        }
        connect.send() // async; onConnected does subscribe + discovery on success
    }

    override fun disconnectDetached(): java.util.concurrent.CompletableFuture<Unit> {
        val old = synchronized(sessionLock) {
            session.client.also { session = Session(null, null, null) }
        }
        return old?.let { disconnectBounded(it) }
            ?: java.util.concurrent.CompletableFuture.completedFuture(Unit)
    }

    override fun publishThenDisconnect(
        publications: List<MqttFinalPublish>,
        timeoutMs: Long,
    ): java.util.concurrent.CompletableFuture<Unit> {
        val c = synchronized(sessionLock) { session.client }
            ?: return java.util.concurrent.CompletableFuture.completedFuture(Unit)
        val completion = java.util.concurrent.CompletableFuture<Unit>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val detach = {
            if (finished.compareAndSet(false, true)) {
                synchronized(sessionLock) {
                    if (session.client === c) session = Session(null, null, null)
                }
                disconnectBounded(c).whenComplete { _, failure ->
                    if (failure == null) completion.complete(Unit)
                    else completion.completeExceptionally(failure)
                }
            }
        }
        // HiveMQ's send admission can itself wedge. One process-wide zero-queue slot and one shared
        // scheduler bound both worker and timeout resources across repeated bridge rebuilds.
        when (FINAL_PUBLISH.execute(timeoutMs, onFinish = detach) { finish ->
            try {
                val pending = publications.map { publication ->
                    c.publishWith()
                        .topic(publication.topic)
                        .payload(publication.payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(publication.retain)
                        .send()
                }
                java.util.concurrent.CompletableFuture.allOf(*pending.toTypedArray())
                    .whenComplete { _, _ -> finish() }
            } catch (_: Exception) {
                finish()
            }
        }) {
            FinalPublishAdmission.ADMITTED -> Unit
            FinalPublishAdmission.BUSY -> {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_TEARDOWN)
                detach()
            }
            FinalPublishAdmission.REJECTED -> {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_TEARDOWN)
                // Skipping the final publish is acceptable under pressure; skipping the DETACH is how a
                // live client with auto-reconnect got orphaned. Always sever the session.
                detach()
            }
        }
        return completion
    }

    override fun publish(
        topic: String,
        payload: ByteArray,
        retain: Boolean,
        expectedConnection: MqttConnectionLease?,
        onComplete: ((Boolean) -> Unit)?,
    ) {
        val selected = synchronized(sessionLock) { session }
        val c = selected.client ?: run { onComplete?.invoke(false); return }
        if (expectedConnection != null && selected.connection !== expectedConnection) {
            onComplete?.invoke(false)
            return
        }
        c.publishWith()
            .topic(topic)
            .payload(payload)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .send()
            // A QoS-1 publish the broker ACKs proves the link is truly alive — the liveness signal the
            // watchdog trusts over HiveMQ's self-reported connected state (which lies on a half-open socket).
            .whenComplete { _, ex ->
                // A superseded client's future can complete after a watchdog rebuild. It must not refresh
                // the replacement connection's liveness or complete state work owned by that connection.
                val success = ex == null && synchronized(sessionLock) {
                    session.client === c &&
                        (expectedConnection == null || session.connection === expectedConnection)
                }
                onComplete?.invoke(success)
            }
    }

    override fun subscribe(
        topicFilter: String,
        expectedConnection: MqttConnectionLease?,
        onMessage: (String, ByteArray, Boolean) -> Unit,
    ) {
        val selected = synchronized(sessionLock) { session }
        val c = selected.client ?: return
        if (expectedConnection != null && selected.connection !== expectedConnection) return
        c.subscribeWith()
            .topicFilter(topicFilter)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { p ->
                // A watchdog rebuild can leave the old reactor alive briefly. Commands and HA-birth
                // messages from that superseded client must not cross into the replacement generation.
                val payloadSize = p.payload.map { it.remaining() }.orElse(0)
                val current = synchronized(sessionLock) {
                    session.client === c &&
                        (expectedConnection == null || session.connection === expectedConnection)
                }
                if (current && payloadSize <= MAX_INBOUND_PACKET_BYTES) {
                    onMessage(p.topic.toString(), p.payloadAsBytes, p.isRetain)
                }
            }
            .send()
    }

    override fun isCurrent(connection: MqttConnectionLease): Boolean =
        synchronized(sessionLock) { session.connection === connection }

    internal companion object {
        const val MAX_INBOUND_PACKET_BYTES = 64 * 1024
        const val MAX_INBOUND_IN_FLIGHT = 16
        const val ADDRESS_FAMILY_CONNECT_TIMEOUT_SECONDS = 10L
        private val TEARDOWN = MqttTeardownGate()
        private val FINAL_PUBLISH = MqttFinalPublishGate()

        private fun disconnectBounded(
            client: Mqtt5AsyncClient,
        ): java.util.concurrent.CompletableFuture<Unit> {
            val submitted = TEARDOWN.submit {
                val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_TEARDOWN)
                val result = runCatching {
                    client.disconnect().get(DISCONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                }.recoverCatching { failure ->
                    // Disconnecting an ALREADY-DISCONNECTED client throws in HiveMQ — but a dead client
                    // is exactly what a teardown wants. Without this, the retire fence read the probe
                    // client's rejection-then-idle state (no auto-reconnect since vc474) as a cleanup
                    // FAILURE and looped the whole network reconfigure for minutes on hardware: a fully
                    // retired client was blocking the bridge swap. Dead == done.
                    if (client.state == com.hivemq.client.mqtt.MqttClientState.DISCONNECTED) Unit
                    else throw failure
                }
                result.onFailure { cost.outcome(FeatureCostOutcome.FAILURE) }
                cost.close()
                result.getOrThrow()
            }
            submitted.whenComplete { _, failure ->
                if (failure is java.util.concurrent.RejectedExecutionException) {
                    // The gate bounds thread pile-up; it is not permission to leak. A rejected teardown
                    // used to drop the LAST reference to a client whose automaticReconnect stayed armed —
                    // the immortal anonymous zombie caught in broker logs, reconnecting for the life of
                    // the process with nothing able to reach it. The async disconnect costs no thread,
                    // and an explicit disconnect disables HiveMQ's auto-reconnect at call time.
                    runCatching { client.disconnect() }
                }
            }
            return submitted
        }

        private const val DISCONNECT_TIMEOUT_MS = 2_000L
    }

    private data class DisconnectDecision(
        val current: Boolean,
        val reconnectAllowed: Boolean,
        val preConnackFailure: Boolean,
    ) {
        companion object {
            val NOT_CURRENT = DisconnectDecision(false, false, false)
        }
    }
}

internal enum class FinalPublishAdmission { ADMITTED, BUSY, REJECTED }

/** One process-wide final-publication attempt, retained until ACK completion or its timeout. */
internal class MqttFinalPublishGate(
    private val executor: SingleFlightExecutor = SingleFlightExecutor("mqtt-final-publish"),
) : AutoCloseable {
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mqtt-final-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun execute(
        timeoutMs: Long,
        onFinish: () -> Unit,
        action: (finish: () -> Unit) -> Unit,
    ): FinalPublishAdmission {
        require(timeoutMs > 0L)
        if (!active.compareAndSet(false, true)) return FinalPublishAdmission.BUSY
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<*>>()
        val finish = {
            if (completed.compareAndSet(false, true)) {
                timeout.get()?.cancel(false)
                runCatching(onFinish)
                active.set(false)
            }
        }
        val scheduled = scheduler.schedule(finish, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        timeout.set(scheduled)
        if (completed.get()) scheduled.cancel(false)
        if (!executor.execute {
                try { action(finish) } catch (_: Exception) { finish() }
            }
        ) {
            finish()
            return FinalPublishAdmission.REJECTED
        }
        return FinalPublishAdmission.ADMITTED
    }

    override fun close() {
        scheduler.shutdownNow()
        executor.close(500L)
    }
}

/** Process-wide zero-queue gate: one wedged client disconnect can consume at most one daemon thread. */
internal class MqttTeardownGate(
    private val executor: SingleFlightExecutor = SingleFlightExecutor("mqtt-teardown"),
) : AutoCloseable {
    fun execute(action: () -> Unit): Boolean = executor.execute(action)

    fun submit(action: () -> Unit): java.util.concurrent.CompletableFuture<Unit> {
        val completion = java.util.concurrent.CompletableFuture<Unit>()
        if (!executor.execute {
                try {
                    action()
                    completion.complete(Unit)
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
        ) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_TEARDOWN)
            completion.completeExceptionally(
                java.util.concurrent.RejectedExecutionException("MQTT teardown owner is busy"),
            )
        }
        return completion
    }

    override fun close() = executor.close(500L)
}
