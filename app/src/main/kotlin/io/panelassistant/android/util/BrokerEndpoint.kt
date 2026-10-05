package io.panelassistant.android.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Pure, unit-testable broker-address handling — the IPv6-first-class core of the MQTT connect path,
 * kept out of [io.panelassistant.android.MqttBridge] so it can be tested without HiveMQ or a device.
 *
 * Two concerns:
 *  - [parse]: turn a broker spec (`host`, `host:port`, `tcp://host:port`, `ssl://[v6]:port`, …) into a
 *    clean (host, port) — critically **stripping the `[...]` brackets** that `java.net.URI.getHost()`
 *    returns for an IPv6 literal (HiveMQ's `serverHost()` wants the bare address).
 *  - [select]: happy-eyeballs-style family choice over resolved addresses, so ha-paneld can prefer one
 *    family and fall back to the other when a link (e.g. a flaky IPv6 path) won't hold.
 */
object BrokerEndpoint {
    const val DEFAULT_PORT = 1883

    /** Conventional MQTT-over-TLS port, used when a `ssl://`/`mqtts://` URL omits an explicit port. */
    const val TLS_PORT = 8883

    /** Broker URL schemes that mean "MQTT over TLS". (`ws://`/`wss://` WebSocket transport is not yet
     *  supported — the HiveMQ websocket module breaks the AGP build; see gradle/libs.versions.toml.) */
    private val TLS_SCHEMES = setOf("ssl", "mqtts", "tls")
    private val PLAIN_SCHEMES = setOf("tcp", "mqtt")
    private val SUPPORTED_SCHEMES = PLAIN_SCHEMES + TLS_SCHEMES

    /** A parsed broker spec: bare [host] (IPv6 brackets/zone stripped), resolved [port], whether the
     *  scheme selects TLS, and the normalised [scheme]. */
    data class Endpoint(val host: String, val port: Int, val tls: Boolean, val scheme: String)

    /** Parse a broker spec into an [Endpoint], or null if unusable. Host has any IPv6 `[...]` brackets and
     *  `%zone` suffix stripped; the scheme (default `tcp`) selects TLS; the port defaults to [TLS_PORT] for
     *  a TLS scheme else [DEFAULT_PORT]. A bare `host`/`[v6]`/`v4` (no scheme) is treated as plain `tcp`. */
    fun endpoint(raw: String): Endpoint? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val uri = runCatching { URI(if (s.contains("://")) s else "tcp://$s") }.getOrNull() ?: return null
        // Older releases accepted a conventional trailing slash and persisted it verbatim. Preserve
        // that harmless spelling across upgrade, but reject every meaningful transport path because
        // this client supports raw MQTT only (not WebSocket/topic-path endpoints).
        if (uri.userInfo != null || uri.rawPath !in setOf(null, "", "/") ||
            uri.rawQuery != null || uri.rawFragment != null
        ) {
            return null
        }
        var host = uri.host ?: return null
        if (host.length >= 2 && host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        host = host.substringBefore('%')          // drop any IPv6 scope/zone id
        if (host.isEmpty()) return null
        val scheme = (uri.scheme ?: "tcp").lowercase()
        if (scheme !in SUPPORTED_SCHEMES) return null
        val tls = scheme in TLS_SCHEMES
        val explicitPort = uri.rawAuthority?.let { authority ->
            if (authority.startsWith("[")) {
                val closingBracket = authority.indexOf(']')
                closingBracket >= 0 && closingBracket + 1 < authority.length
            } else {
                ':' in authority
            }
        } == true
        if (explicitPort && uri.port !in 1..65535) return null
        val port = if (explicitPort) uri.port else if (tls) TLS_PORT else DEFAULT_PORT
        return Endpoint(host, port, tls, scheme)
    }

    /** Parse a broker spec into (host, port), or null if unusable — the host/port projection of
     *  [endpoint]. Kept for callers that don't need the TLS flag. */
    fun parse(raw: String): Pair<String, Int>? = endpoint(raw)?.let { it.host to it.port }

    /** Canonical persisted spelling. Validation has already established that a final slash is only the
     * accepted root path, so removing it cannot change the selected raw-MQTT endpoint. */
    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (endpoint(value) == null) return null
        return value.removeSuffix("/")
    }

    /** Happy-eyeballs family selection: return the first address of the preferred family, else the first
     *  of the other family, else null. Deterministic (first-of-family) so reconnect behaviour is stable. */
    fun select(candidates: List<InetAddress>, preferIpv4: Boolean): InetAddress? {
        val v4 = candidates.filterIsInstance<Inet4Address>()
        val v6 = candidates.filterIsInstance<Inet6Address>()
        val (first, second) = if (preferIpv4) v4 to v6 else v6 to v4
        return first.firstOrNull() ?: second.firstOrNull()
    }

    /** Format an address for HiveMQ's `serverHost()` — bare form, no brackets (HiveMQ adds them). Strips
     *  the leading `/` and any `%zone` from [InetAddress.toString]/`hostAddress`. */
    fun hostString(addr: InetAddress): String = addr.hostAddress.substringBefore('%')
}
