package io.panelassistant.android.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.panelassistant.android.sensors.HaAuthenticationException
import io.panelassistant.android.sensors.HaProtocolException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import okhttp3.Dns
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * One shared construction path for every panel→Home Assistant WebSocket client.
 *
 * Exists because the previous per-site CIO construction dialed exactly one resolved address: a host
 * whose leading AAAA is black-holed (SYN dropped, nothing rejects) could never fall back to a
 * working A record, permanently stranding the renderer on a panel whose MQTT — which plans address
 * families — stayed healthy. The OkHttp engine walks every address the resolver returns and races
 * families (fast fallback), and the per-route connect timeout bounds each dead attempt so the walk
 * completes inside every caller's 15 s outer deadline.
 *
 * The request URL keeps the configured hostname — no literal-address dialing — so TLS SNI,
 * certificate hostname verification and the Host header remain the platform defaults throughout.
 */
internal object HaWebSocketClients {

    private val routeTrackerKey = AttributeKey<ConnectedRouteTracker>("HaWebSocketConnectedRouteTracker")

    /**
     * Bound on one route attempt. Worst case before a live sibling family is reached under
     * family-interleaved ordering: one dead route at this bound plus the live connect, which fits
     * the smallest caller deadline (15 s) with room for a second dead route.
     */
    internal const val ROUTE_CONNECT_TIMEOUT_MS = 5_000L

    /**
     * Explicit trust material (for example a private CA). Never a verification bypass: certificate
     * chain checks and hostname verification still run against whatever is supplied here.
     */
    internal class TlsTrust(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    /** DNS and socket attempts share the exact InetAddress objects supplied to OkHttp. */
    private class ResolvedFamilies {
        private val byAddress = IdentityHashMap<InetAddress, Boolean?>()

        @Synchronized fun remember(all: List<InetAddress>, planned: List<InetAddress>) {
            for (address in planned) {
                val other = all.any { (it is Inet6Address) != (address is Inet6Address) }
                byAddress[address] = if (byAddress.containsKey(address)) null else other
            }
        }

        @Synchronized fun take(address: InetAddress): Boolean? = byAddress.remove(address)
    }

    private class ConnectedRouteTracker(
        private val onConnected: (InetAddress, Boolean?) -> Unit,
    ) {
        private data class Record(val sequence: Long, val socket: TrackedSocket)

        private val sockets = mutableListOf<Record>()
        private var sequence = 0L

        @Synchronized fun mark(): Long = sequence

        @Synchronized fun newSocket(resolved: ResolvedFamilies): Socket = TrackedSocket(resolved).also { socket ->
            sockets += Record(++sequence, socket)
        }

        fun publishUniqueConnectedAfter(mark: Long) {
            val selected = synchronized(this) {
                val candidates = sockets.filter {
                    it.sequence > mark && it.socket.isConnected && !it.socket.isClosed
                }
                val selected = candidates.singleOrNull()?.socket
                sockets.removeAll { it.socket.isClosed }
                selected
            }
            selected?.let { runCatching { onConnected(it.inetAddress, it.otherResolved) } }
        }
    }

    private class TrackedSocket(private val resolved: ResolvedFamilies) : Socket() {
        var otherResolved: Boolean? = null
            private set

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            super.connect(endpoint, timeout)
            otherResolved = (endpoint as? InetSocketAddress)?.address?.let(resolved::take)
        }
    }

    private class RouteTrackingSocketFactory(
        private val tracker: ConnectedRouteTracker,
        private val resolved: ResolvedFamilies,
    ) : SocketFactory() {
        override fun createSocket(): Socket = tracker.newSocket(resolved)

        override fun createSocket(host: String, port: Int): Socket =
            connected(InetSocketAddress(host, port))

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            connected(InetSocketAddress(host, port))

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

        private fun connected(remote: SocketAddress, local: SocketAddress? = null): Socket =
            tracker.newSocket(resolved).apply {
                if (local != null) bind(local)
                connect(remote)
            }
    }

    fun client(
        preferIpv4: Boolean = false,
        ipv4Only: Boolean = false,
        routeConnectTimeoutMs: Long = ROUTE_CONNECT_TIMEOUT_MS,
        tls: TlsTrust? = null,
        resolver: ((String) -> List<InetAddress>)? = null,
        /**
         * The address used by a successfully upgraded WebSocket session.
         *
         * Only the exact-entity stream passes this, and only so a layer-3 probe can measure THE PATH
         * THE DASHBOARD IS USING rather than a fresh resolution of the same hostname. The two differ
         * exactly when it matters: on 2026-08-10 a black-holed AAAA left one panel unusable while the
         * family race put the socket on IPv4, and a probe that re-resolved would have measured the
         * dead family and blamed a path nothing was riding.
         *
         * Called immediately after Ktor validates the WebSocket upgrade; implementations must only
         * hand the address to an owner that can take it from there.
         */
        onRouteConnected: ((InetAddress, Boolean?) -> Unit)? = null,
    ): HttpClient {
        val routeTracker = onRouteConnected?.let { ConnectedRouteTracker(it) }
        val resolvedFamilies = routeTracker?.let { ResolvedFamilies() }
        val client = HttpClient(OkHttp) {
        // No engine-level maxFrameSize: the OkHttp engine REJECTS any custom value at session
        // start ("Max frame size switch is not supported"), pinned by HaWebSocketClientsFailoverTest.
        // The former per-site inbound bounds are enforced instead by [open], which every caller
        // uses: an oversized frame fails the session with [FrameTooBigException] before delivery.
        followRedirects = false
        install(WebSockets)
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                connectTimeout(routeConnectTimeoutMs, TimeUnit.MILLISECONDS)
                // Default-on in OkHttp 5; pinned so a future engine bump cannot silently drop the
                // concurrent-family race this lane exists to provide.
                fastFallback(true)
                dns(
                    if (resolver == null) {
                        FamilyPlannedDns(
                            preferIpv4 = preferIpv4,
                            ipv4Only = ipv4Only,
                            onResolved = resolvedFamilies?.let { it::remember },
                        )
                    } else {
                        FamilyPlannedDns(
                            preferIpv4 = preferIpv4,
                            ipv4Only = ipv4Only,
                            onResolved = resolvedFamilies?.let { it::remember },
                            systemLookup = resolver,
                        )
                    },
                )
                if (tls != null) sslSocketFactory(tls.socketFactory, tls.trustManager)
                if (routeTracker != null && resolvedFamilies != null) {
                    socketFactory(RouteTrackingSocketFactory(routeTracker, resolvedFamilies))
                }
            }
        }
        }
        if (routeTracker != null) client.attributes.put(routeTrackerKey, routeTracker)
        return client
    }

    /**
     * Open a WebSocket session whose inbound frames are bounded. This restores the pre-delivery
     * size contract the CIO engine enforced natively: a frame larger than [maxInboundFrameBytes]
     * is never handed to the caller — the session is closed with TOO_BIG (1009) and receivers fail
     * with [FrameTooBigException], exactly the failure shape the former engine produced. The bound
     * is checked before delivery to the application; the engine's own transient buffering of the
     * arriving frame is not affected.
     */
    suspend fun open(
        client: HttpClient,
        urlString: String,
        maxInboundFrameBytes: Long,
    ): DefaultClientWebSocketSession {
        val routeTracker = client.attributes.getOrNull(routeTrackerKey)
        val routeMark = routeTracker?.mark()
        val session = client.webSocketSession(urlString)
        if (routeTracker != null && routeMark != null) {
            // webSocketSession returns only after OkHttp validates the complete 101 upgrade. At
            // that point canceled fast-fallback contenders are closed and the one surviving socket
            // is the authenticated stream's actual route.
            routeTracker.publishUniqueConnectedAfter(routeMark)
        }
        return DefaultClientWebSocketSession(
            session.call,
            InboundBoundedWebSocketSession(session, maxInboundFrameBytes),
        )
    }

    /**
     * Home Assistant's WebSocket login: `auth_required`, then `auth`, then `auth_ok`. A refused
     * token is [HaAuthenticationException]; any other reply is [HaProtocolException]. Callers own the
     * deadline, because they classify a timeout differently, and a malformed frame escapes as
     * [org.json.JSONException] for the same reason.
     */
    suspend fun authenticate(session: WebSocketSession, accessToken: String) {
        if (readJson(session).optString("type") != "auth_required") {
            throw HaProtocolException("Home Assistant did not request WebSocket authentication")
        }
        session.send(JSONObject().put("type", "auth").put("access_token", accessToken).toString())
        when (readJson(session).optString("type")) {
            "auth_ok" -> Unit
            "auth_invalid" -> throw HaAuthenticationException("Home Assistant rejected the access token")
            else -> throw HaProtocolException("Unexpected Home Assistant authentication response")
        }
    }

    /** The next text frame as JSON; other frame types are skipped. */
    suspend fun readJson(session: WebSocketSession): JSONObject {
        while (true) {
            val frame = session.incoming.receive()
            if (frame is Frame.Text) return JSONObject(frame.readText())
        }
    }
}

/** Delegates everything except [incoming], which enforces the inbound frame bound. */
internal class InboundBoundedWebSocketSession(
    private val delegate: DefaultWebSocketSession,
    private val maxInboundFrameBytes: Long,
) : DefaultWebSocketSession by delegate {
    private val boundedIncoming: ReceiveChannel<Frame> = produce(capacity = 0) {
        for (frame in delegate.incoming) {
            val size = frame.data.size
            if (size > maxInboundFrameBytes) {
                delegate.close(CloseReason(CloseReason.Codes.TOO_BIG, "inbound frame exceeds $maxInboundFrameBytes bytes"))
                throw FrameTooBigException(size.toLong())
            }
            send(frame)
        }
    }

    override val incoming: ReceiveChannel<Frame> get() = boundedIncoming
}

/**
 * Resolver wrapper implementing the user address-family policy for HA WebSocket connections, with
 * the same vocabulary as the MQTT route planner: Automatic keeps the resolver's leading family,
 * Prefer IPv4 leads with A records, Force IPv4 never emits an IPv6 address.
 *
 * Families are interleaved (RFC 8305 §4) rather than concatenated so a host publishing several
 * addresses of a dead family costs one bounded route attempt — not one per dead address — before
 * the first address of the live family is tried.
 */
internal class FamilyPlannedDns(
    private val preferIpv4: Boolean,
    private val ipv4Only: Boolean,
    private val onResolved: ((List<InetAddress>, List<InetAddress>) -> Unit)? = null,
    private val systemLookup: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = systemLookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filterIsInstance<Inet6Address>()
        if (ipv4Only) {
            if (v4.isEmpty()) {
                throw UnknownHostException(
                    "$hostname resolves to no IPv4 address and the address-family policy is Force IPv4",
                )
            }
            onResolved?.invoke(all, v4)
            return v4
        }
        val ipv4Leads = preferIpv4 || all.firstOrNull() is Inet4Address
        val lead = if (ipv4Leads) v4 else v6
        val trail = if (ipv4Leads) v6 else v4
        val interleaved = ArrayList<InetAddress>(all.size)
        val leadIterator = lead.iterator()
        val trailIterator = trail.iterator()
        while (leadIterator.hasNext() || trailIterator.hasNext()) {
            if (leadIterator.hasNext()) interleaved.add(leadIterator.next())
            if (trailIterator.hasNext()) interleaved.add(trailIterator.next())
        }
        onResolved?.invoke(all, interleaved)
        return interleaved
    }
}
