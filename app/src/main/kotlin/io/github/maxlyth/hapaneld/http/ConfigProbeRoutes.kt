package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.logship.LogShipRecord
import io.github.maxlyth.hapaneld.logship.LogShipTarget
import io.github.maxlyth.hapaneld.logship.NetworkLogSinkFactory
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.configProbeRoutes(config: Config) {
    get("/config/probe-broker") {
        // Pre-flight from the PANEL's network vantage — the only one that matters — so
        // the wizard can name an unresolvable host or closed port in ~2s instead of
        // committing the save and discovering it minutes into the connect workflow.
        // Read-only: resolves and touches a TCP port, changes nothing.
        val url = call.request.queryParameters["url"].orEmpty()
        val body = withContext(Dispatchers.IO) { probeBrokerJson(url) }
        call.respondText(body, ContentType.Application.Json)
    }
    post("/config/probe-log-sink") {
        // Same pre-flight idea as probe-broker, from the panel's own network vantage,
        // but deliberately a POST rather than a GET: unlike the broker probe this one
        // TRANSMITS a caller-supplied record to a caller-supplied host and port, which
        // is neither safe nor idempotent, and as a GET it would be a CSRF-reachable
        // packet emitter aimed at the panel's LAN. POST puts it behind the shared
        // state-changing OriginGuard admission applied to every mutating route.
        val params = receiveBoundedFormParameters(call) ?: return@post
        val port = selectLogSinkProbePort(params["port"], config.logShipPort)
        if (port == null) {
            call.respondText(
                """{"ok":false,"error":"invalid-port"}""",
                ContentType.Application.Json,
            )
            return@post
        }
        val body = withContext(Dispatchers.IO) {
            probeLogSinkJson(
                host = params["host"] ?: config.logShipHost,
                port = port,
                protocol = params["protocol"] ?: config.logShipProtocol,
                panelId = config.panelId,
            )
        }
        call.respondText(body, ContentType.Application.Json)
    }
}

/** See GET /config/probe-broker. Bounded: one DNS resolve + one 2s TCP connect attempt. */
internal fun probeBrokerJson(url: String): String {
    val ep = io.github.maxlyth.hapaneld.util.BrokerEndpoint.endpoint(url.trim())
        ?: return """{"ok":false,"error":"invalid-url"}"""
    val resolved = runCatching { java.net.InetAddress.getByName(ep.host).hostAddress }.getOrNull()
        ?: return "{\"ok\":false,\"error\":\"unresolvable\",\"host\":${Json.str(ep.host)}}"
    val reachable = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(ep.host, ep.port), 2_000); true }
    }.getOrDefault(false)
    return "{\"ok\":$reachable,\"host\":${Json.str(ep.host)},\"port\":${ep.port}," +
        "\"resolved\":${Json.str(resolved)}" +
        if (reachable) "}" else ",\"error\":\"unreachable\"}"
}

/**
 * See POST /config/probe-log-sink. Bounded: one DNS resolve plus one 2s connect and exactly one
 * marked record, in the real wire format of the selected transport.
 *
 * Every transport transmits. An earlier revision reported a bare TCP `connect()` as success, but
 * a connect succeeds against *any* listening socket — an MQTT broker, an SSH daemon, a mistyped
 * port belonging to something else entirely — so "Connected" asserted a working log sink on
 * evidence that could not distinguish one. Writing a real RFC5424 frame or a real NDJSON POST is
 * the cheapest check that actually discriminates.
 *
 * `delivered` says whether the transport itself confirmed anything. UDP is always false: a send
 * that returns without error proves only that the panel handed the datagram to the network, and
 * calling that success would rebuild in the UI the very false confidence this change removes.
 * Every transport returns the marker regardless, because searching the collector for it is the
 * only end-to-end confirmation that exists.
 *
 * Takes [panelId] as a parameter rather than reading `config`, so it stays free of instance state
 * and can be exercised the same way [probeBrokerJson] is, without the Android-backed server graph.
 */
internal fun probeLogSinkJson(host: String, port: Int, protocol: String, panelId: String): String {
    val ep = LogShipEndpoint.resolve(host, port, protocol)
    if (ep.host.isBlank()) return """{"ok":false,"error":"no-host"}"""
    if (ep.port !in 1..65535) return """{"ok":false,"error":"invalid-port"}"""
    val head = "\"host\":${Json.str(ep.host)},\"port\":${ep.port}," +
        "\"protocol\":${Json.str(ep.protocol)}"
    val marker = "ha-paneld-sink-probe-${System.currentTimeMillis().toString(36)}"
    val name = panelId.ifBlank { "panel" }
    val timestamp = probeTimestamp()
    val build = LogShipRecord.Build.CURRENT
    val payload = when (ep.protocol) {
        LogShipEndpoint.HTTP -> LogShipRecord.jsonEvent(timestamp, name, build, marker)
        LogShipEndpoint.SYSLOG_UDP -> LogShipRecord.syslogFrame(14, timestamp, name, build, marker).trimEnd('\n')
        else -> LogShipRecord.syslogFrame(14, timestamp, name, build, marker)
    }.toByteArray(Charsets.UTF_8)
    val result = NetworkLogSinkFactory.probe(
        LogShipTarget(ep.host, ep.port, ep.protocol, panelId),
        payload,
        timeoutMs = LOG_SINK_DNS_TIMEOUT_MS,
    )
    val body = "$head" +
        (result.candidate?.let { ",\"resolved\":${Json.str(it.hostAddress)}" } ?: "") +
        ",\"marker\":${Json.str(marker)}" +
        (result.status?.let { ",\"status\":$it" } ?: "")
    return if (result.ok) {
        "{\"ok\":true,\"delivered\":${result.delivered},$body}"
    } else {
        val error = when {
            result.status != null -> "http-${result.status}"
            result.error == "unresolvable" -> "unresolvable"
            ep.protocol == LogShipEndpoint.SYSLOG_UDP -> "send-failed"
            else -> "unreachable"
        }
        "{\"ok\":false,\"error\":${Json.str(error)},$body}"
    }
}

private fun probeTimestamp(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date())

private const val LOG_SINK_DNS_TIMEOUT_MS = 2_000L
