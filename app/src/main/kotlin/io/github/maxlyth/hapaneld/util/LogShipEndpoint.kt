package io.github.maxlyth.hapaneld.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Pure, unit-testable log-sink addressing — the sibling of [BrokerEndpoint] for the remote
 * log-shipping destination, kept out of `logship/LogShipper` so it can be tested without a socket.
 *
 * One parser serves the value a user types into the Configure form, the value stored in preferences,
 * and the value a config bundle restores, so a scheme typed into the host box, a retired protocol
 * name, and a `host:port` shorthand all resolve identically wherever they are read.
 */
object LogShipEndpoint {
    /** RFC5426: one datagram per message. What a stock syslog collector listens for on port 514. */
    const val SYSLOG_UDP = "syslog-udp"

    /** RFC5424 frames over a stream, newline-delimited per RFC6587 non-transparent framing. */
    const val SYSLOG_TCP = "syslog-tcp"

    /** NDJSON batches POSTed to `http://host:port/`. */
    const val HTTP = "http"

    /**
     * TCP, chosen for the failure mode rather than the happy path.
     *
     * UDP/514 is what a stock collector listens on, which argues for defaulting to it — but the two
     * wrong-default outcomes are not symmetric. Defaulting to TCP against a UDP-only collector is
     * refused loudly and the user switches; defaulting to UDP against a TCP-only collector succeeds
     * locally and every record vanishes, with nothing to notice. TCP also carries whole lines, while
     * UDP truncates each datagram at [LogShipper.UDP_DATAGRAM_MAX_BYTES], and both parse identically
     * at the collector. So the default is the transport that tells you when it is wrong.
     */
    const val DEFAULT_PROTOCOL = SYSLOG_TCP

    val PROTOCOLS = listOf(SYSLOG_UDP, SYSLOG_TCP, HTTP)

    /**
     * Retired spellings, resolved before an enum match so an older config bundle still imports.
     * `syslog` predates the UDP transport and meant TCP, so it must keep meaning TCP — a panel that
     * was deliberately shipping over TCP does not change transport merely because it was upgraded.
     */
    val ALIASES = mapOf(
        "syslog" to SYSLOG_TCP,
        "tcp" to SYSLOG_TCP,
        "udp" to SYSLOG_UDP,
    )

    /** A resolved sink address: bare [host] (IPv6 brackets/zone stripped), [port], and [protocol]. */
    data class Endpoint(val host: String, val port: Int, val protocol: String)

    /** Canonical protocol for any stored or user-supplied value; unrecognised input takes the default. */
    fun protocol(raw: String): String = known(raw) ?: DEFAULT_PROTOCOL

    /**
     * The host as it must appear inside a URL. [resolve] strips IPv6 brackets because that is what
     * `InetAddress` and `Socket` want, but a URL needs them back or `http://::1:514/` is ambiguous
     * nonsense — the parser cannot tell the address's colons from the port separator.
     */
    fun urlHost(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    /**
     * Candidate sink addresses in the order they should be tried: **IPv4 first**, then IPv6.
     *
     * This is deliberately the opposite of the MQTT bridge's IPv6-first preference, because the two
     * have opposite feedback. MQTT learns — a failed connect flips the preference and the working
     * family is persisted. UDP syslog cannot learn: a datagram sent to an address whose service is
     * not listening succeeds locally and vanishes, so the wrong first choice is a permanent, silent
     * failure with no signal to correct it.
     *
     * A collector name with both A and AAAA records can resolve first to a global IPv6 address while
     * UDP reports a successful local send and nothing arrives; the same collector by IPv4 literal can
     * still work on every transport. LAN log collectors are IPv4 in practice, so IPv4-first is the
     * ordering that fails least often — and where the transport does
     * give feedback (TCP, HTTP) the caller walks the whole list rather than trusting this order.
     *
     * An IPv6-only collector is unaffected: it resolves to AAAA records only, so IPv6 is all there
     * is to choose.
     */
    fun orderedCandidates(addresses: List<InetAddress>): List<InetAddress> =
        addresses.filterIsInstance<Inet4Address>() + addresses.filterNot { it is Inet4Address }

    /** Short transport word for status text: `udp://host:514` reads better than `syslog-udp://…`. */
    fun scheme(protocol: String): String = when (protocol) {
        SYSLOG_UDP -> "udp"
        HTTP -> "http"
        else -> "tcp"
    }

    /** Host text safe for status, diagnostics and logs; authority credentials are never rendered. */
    fun displayHost(host: String): String {
        val raw = host.trim()
        val withoutUserInfo = raw.substringAfterLast('@')
        return withoutUserInfo.substringBefore('?').substringBefore('#').ifBlank { "<redacted>" }
    }

    /** Remove the configured authority from transport errors before they cross a reporting boundary. */
    fun displayFailure(message: String, host: String): String {
        val safeHost = displayHost(host)
        return message.replace(host, safeHost).replace(host.trim(), safeHost)
    }

    /**
     * Resolve what the user actually typed into the free-text host box against the separately stored
     * port and protocol. People paste a scheme or a `host:port` in there, and taking that literally is
     * precisely how `udp://collector` became a hostname lookup whose failure message was the hostname
     * itself — a warning that named the destination and no fault.
     *
     * A recognised scheme wins over the stored protocol, because typing one is a deliberate statement
     * about transport. An unrecognised scheme is ignored rather than allowed to reset the protocol to
     * the default. A value [URI] will not parse — an underscore in a hostname, or a meaningful path —
     * is passed through exactly as typed rather than silently rewritten, so it fails at resolution
     * quoting what the user actually entered.
     */
    fun resolve(host: String, port: Int, protocol: String): Endpoint {
        val raw = host.trim()
        val typedScheme = if ("://" in raw) raw.substringBefore("://") else ""
        val authority = (if (typedScheme.isEmpty()) raw else raw.substringAfter("://")).trim()
        val resolvedProtocol = known(typedScheme) ?: protocol(protocol)
        if (authority.isEmpty()) return Endpoint("", port, resolvedProtocol)

        val verbatim = Endpoint(authority.removeSuffix("/"), port, resolvedProtocol)
        val uri = runCatching { URI("logsink://$authority") }.getOrNull() ?: return verbatim
        val addressOnly = uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath in setOf(null, "", "/")
        val parsedHost = uri.host?.takeIf { addressOnly } ?: return verbatim

        val bare = if (parsedHost.length >= 2 && parsedHost.startsWith("[") && parsedHost.endsWith("]")) {
            parsedHost.substring(1, parsedHost.length - 1)
        } else {
            parsedHost
        }
        val resolvedPort = if (uri.port in 1..65535) uri.port else port
        return Endpoint(bare.substringBefore('%'), resolvedPort, resolvedProtocol)
    }

    /** The three stored preference keys that together describe one sink destination. */
    val ADDRESS_KEYS = setOf("log_ship_host", "log_ship_port", "log_ship_protocol")

    /**
     * Reconcile a partial update to [ADDRESS_KEYS] into one destination the three fields all describe.
     *
     * Without this, a host carrying its own scheme or port — `udp://collector.lan:1514`, from a
     * hand-edited bundle or one exported before hosts were normalised — is stored verbatim while the
     * separate Port and Protocol fields keep describing somewhere else. [resolve] then sends to what
     * the host says, so shipping goes one place while every surface reports another: the settings lie
     * about where logs go.
     *
     * **Precedence is the same one [resolve] applies at send time: an embedded scheme or port wins.**
     * That is what makes a restore faithful — a panel whose stored host was `udp://collector.lan:1514`
     * was really shipping UDP to 1514, so a bundle taken from it must reproduce that, not the stale
     * Port and Protocol it also carried. Fields the host does not specify fall back to the update's
     * own value, then to what is already stored.
     *
     * **Order-independent by construction:** inputs are read from [update] by key, so no result here
     * depends on the order a caller happens to iterate its batch. Returns null when [update] touches
     * none of [ADDRESS_KEYS], so a caller can skip staging entirely.
     */
    fun canonicalUpdate(
        update: Map<String, String>,
        storedHost: String,
        storedPort: Int,
        storedProtocol: String,
    ): Map<String, String>? {
        if (ADDRESS_KEYS.none { it in update }) return null
        val endpoint = resolve(
            host = update["log_ship_host"] ?: storedHost,
            port = update["log_ship_port"]?.trim()?.toIntOrNull() ?: storedPort,
            protocol = update["log_ship_protocol"] ?: storedProtocol,
        )
        return mapOf(
            "log_ship_host" to endpoint.host,
            "log_ship_port" to endpoint.port.toString(),
            "log_ship_protocol" to endpoint.protocol,
        )
    }

    /** The canonical form of a recognised protocol or scheme word, or null if it names none. */
    private fun known(raw: String): String? {
        val v = raw.trim().lowercase(Locale.ROOT)
        if (v.isEmpty()) return null
        return ALIASES[v] ?: PROTOCOLS.firstOrNull { it == v }
    }
}
