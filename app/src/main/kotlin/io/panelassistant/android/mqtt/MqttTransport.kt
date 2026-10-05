package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttAddressFamily
import java.util.concurrent.CompletableFuture

/**
 * The MQTT client boundary — everything MqttBridge needs from the underlying client, behind an interface
 * so the connect / reconnect / onConnected lifecycle (the flow with the worst incident history) can be
 * driven by a fake in tests. The real implementation is [HiveMqTransport]; tests inject a fake.
 */
interface MqttTransport {
    /** Build + connect a fresh client, wiring the lifecycle [callbacks]. Replaces any current client; a
     *  superseded client is neutralised (its auto-reconnect stopped, its callbacks ignored). */
    fun connect(config: MqttConnectConfig, callbacks: MqttCallbacks)

    /** Detach + tear the current client down on a throwaway thread — a wedged client (the case that
     *  triggers a liveness rebuild) must never block the caller while the replacement connects. */
    fun disconnectDetached(): CompletableFuture<Unit>

    /** Publish final retained values, wait for their QoS acknowledgements, then disconnect. Admission
     * returns immediately; the completion resolves only after ACK or the bounded timeout has detached
     * the client, so lifecycle owners can prove final MQTT teardown without blocking their caller. */
    fun publishThenDisconnect(
        publications: List<MqttFinalPublish>,
        timeoutMs: Long,
    ): CompletableFuture<Unit>

    /** Publish [payload] to [topic]; [onComplete] reports true only after the broker ACKs the QoS-1
     * publication, and is scoped to the client that submitted it. No-op without a current client. */
    fun publish(
        topic: String,
        payload: ByteArray,
        retain: Boolean,
        expectedConnection: MqttConnectionLease? = null,
        onComplete: ((Boolean) -> Unit)? = null,
    )

    /** Subscribe to [topicFilter]; [onMessage] receives (topic, payload, retained) per delivery. The
     *  retained flag lets the bridge reject stale retained commands (the never-blank incident guard). */
    fun subscribe(
        topicFilter: String,
        expectedConnection: MqttConnectionLease? = null,
        onMessage: (topic: String, payload: ByteArray, retained: Boolean) -> Unit,
    )

    /** True only while [connection] still identifies the active broker session. */
    fun isCurrent(connection: MqttConnectionLease): Boolean
}

/** Opaque identity for one connected broker session, including automatic reconnects of one client. */
class MqttConnectionLease internal constructor()

data class MqttFinalPublish(val topic: String, val payload: ByteArray, val retain: Boolean)

/** Everything needed to open one MQTT connection. */
data class MqttConnectConfig(
    val host: String,
    val port: Int,
    // Connect over TLS (from a ssl:///mqtts:// broker URL). Uses the default JVM trust store — a
    // CA-signed broker cert validates; self-signed brokers are a separate follow-up.
    val tls: Boolean,
    val clientId: String,
    val user: String?,
    val password: String?,
    val keepAliveSeconds: Int,
    val willTopic: String,
    val willPayload: String,
    /**
     * Whether the client may auto-reconnect on its own. True for configured, credentialed connections
     * (a network blip must never orphan a working panel). False for the credential-LESS discovery
     * probe of an unconfigured panel: its transport-level retry loop hammered brokers with anonymous
     * failures for the whole first-run journey, and a leaked probe client reconnected forever. A
     * successful anonymous session that later drops is re-established by the bridge's own watchdog.
     */
    val automaticReconnect: Boolean = true,
    /** Concrete-address owner. Null preserves the ordinary HiveMQ hostname-resolution path. */
    val routePlanner: MqttRoutePlanner? = null,
)

/** Lifecycle callbacks the transport invokes for the LIVE client only (superseded clients are filtered).
 * Implementations must remain constant-time: HiveMqTransport calls them inside its tiny session lock so
 * client/lease transition order and callback enqueue order cannot diverge. */
interface MqttCallbacks {
    fun onConnected(connection: MqttConnectionLease, addressFamily: MqttAddressFamily? = null)
    /** False suppresses HiveMQ's in-place reconnect; the owner will build a fresh client. */
    fun onDisconnected(connection: MqttConnectionLease?, causeMessage: String?): Boolean
}
