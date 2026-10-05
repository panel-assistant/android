package io.panelassistant.android.util

import io.panelassistant.android.util.LogShipEndpoint.HTTP
import io.panelassistant.android.util.LogShipEndpoint.SYSLOG_TCP
import io.panelassistant.android.util.LogShipEndpoint.SYSLOG_UDP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LogShipEndpointTest {

    private fun resolve(host: String, port: Int = 514, protocol: String = SYSLOG_TCP) =
        LogShipEndpoint.resolve(host, port, protocol)

    // ---- protocol normalization -------------------------------------------------------------------

    @Test fun retiredSyslogSpellingStaysTcp() {
        // `syslog` predates the UDP transport and meant TCP. A panel that was deliberately shipping
        // over TCP must not silently change transport because it was upgraded.
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("syslog"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("SYSLOG"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("tcp"))
    }

    @Test fun canonicalAndShorthandProtocolsResolve() {
        assertEquals(SYSLOG_UDP, LogShipEndpoint.protocol("syslog-udp"))
        assertEquals(SYSLOG_UDP, LogShipEndpoint.protocol("udp"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("syslog-tcp"))
        assertEquals(HTTP, LogShipEndpoint.protocol("http"))
    }

    @Test fun blankAndUnknownProtocolsTakeTheTcpDefault() {
        // TCP is the default because its failures are visible: refused against a UDP-only collector
        // is recoverable, whereas UDP against a TCP-only collector vanishes with nothing to notice.
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol(""))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("   "))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("carrier-pigeon"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.DEFAULT_PROTOCOL)
    }

    @Test fun schemeWordIsShortenedForDisplay() {
        assertEquals("udp", LogShipEndpoint.scheme(SYSLOG_UDP))
        assertEquals("tcp", LogShipEndpoint.scheme(SYSLOG_TCP))
        assertEquals("http", LogShipEndpoint.scheme(HTTP))
    }

    @Test fun statusHostAndFailureRenderingNeverExposeAuthorityCredentials() {
        assertEquals("collector.lan", LogShipEndpoint.displayHost("operator:super-secret@collector.lan"))
        assertEquals("collector.lan", LogShipEndpoint.displayHost("operator:super-secret@collector.lan?token=also-secret"))
        val rendered = LogShipEndpoint.displayFailure(
            "operator:super-secret@collector.lan refused connection",
            "operator:super-secret@collector.lan",
        )
        assertEquals("collector.lan refused connection", rendered)
        assertFalse(rendered.contains("super-secret"))
    }

    // ---- host resolution --------------------------------------------------------------------------

    @Test fun aPlainHostKeepsTheStoredPortAndProtocol() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 514, SYSLOG_TCP),
            resolve("collector.lan"),
        )
    }

    /**
     * The reported workaround. Typing a scheme into the host box used to make the whole string a
     * hostname, and the resulting `UnknownHostException` message *was* that string — a warning that
     * quoted the destination and named no fault.
     */
    @Test fun aTypedSchemeSelectsTheTransportInsteadOfBecomingTheHostname() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.example", 514, SYSLOG_UDP),
            resolve("udp://collector.example", protocol = SYSLOG_TCP),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.example", 514, SYSLOG_TCP),
            resolve("tcp://collector.example", protocol = SYSLOG_UDP),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.example", 514, HTTP),
            resolve("http://collector.example", protocol = SYSLOG_UDP),
        )
    }

    @Test fun anUnrecognisedSchemeLeavesTheStoredProtocolAlone() {
        // Falling back to the default here would silently retarget the transport the user chose.
        assertEquals(
            LogShipEndpoint.Endpoint("collector.example", 514, SYSLOG_TCP),
            resolve("gopher://collector.example", protocol = SYSLOG_TCP),
        )
    }

    @Test fun aTrailingPortOverridesTheStoredPort() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 1514, SYSLOG_TCP),
            resolve("collector.lan:1514"),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 1514, SYSLOG_TCP),
            resolve("tcp://collector.lan:1514"),
        )
    }

    @Test fun ipv6LiteralsKeepTheirColonsAndLoseTheirBrackets() {
        assertEquals(LogShipEndpoint.Endpoint("::1", 514, SYSLOG_TCP), resolve("[::1]"))
        assertEquals(LogShipEndpoint.Endpoint("::1", 1514, SYSLOG_TCP), resolve("[::1]:1514"))
        assertEquals(
            LogShipEndpoint.Endpoint("fd31::118:1", 514, SYSLOG_UDP),
            resolve("udp://[fd31::118:1]"),
        )
    }

    @Test fun ipv6LiteralsAreRebracketedForUrlUse() {
        // resolve() strips the brackets because InetAddress and Socket want the bare address, but a
        // URL needs them back — "http://::1:514/" cannot be parsed into an address and a port.
        assertEquals("[::1]", LogShipEndpoint.urlHost("::1"))
        assertEquals("[fd31::118:1]", LogShipEndpoint.urlHost("fd31::118:1"))
        // Already-bracketed input must not be double-wrapped, and a name or IPv4 is left alone.
        assertEquals("[::1]", LogShipEndpoint.urlHost("[::1]"))
        assertEquals("collector.lan", LogShipEndpoint.urlHost("collector.lan"))
        assertEquals("192.0.2.118", LogShipEndpoint.urlHost("192.0.2.118"))
    }

    @Test fun anIpv6HttpSinkRoundTripsIntoAParseableUrl() {
        val ep = resolve("http://[::1]:8080", protocol = HTTP)
        assertEquals("::1", ep.host)
        assertEquals(8080, ep.port)
        val url = java.net.URL("http://${LogShipEndpoint.urlHost(ep.host)}:${ep.port}/")
        assertEquals("::1", url.host.trim('[', ']'))
        assertEquals(8080, url.port)
    }

    @Test fun ipv4LiteralsAreUntouched() {
        assertEquals(LogShipEndpoint.Endpoint("192.0.2.118", 514, SYSLOG_TCP), resolve("192.0.2.118"))
        assertEquals(
            LogShipEndpoint.Endpoint("192.0.2.118", 1514, SYSLOG_TCP),
            resolve("192.0.2.118:1514"),
        )
    }

    @Test fun surroundingWhitespaceAndATrailingSlashAreNoise() {
        assertEquals(LogShipEndpoint.Endpoint("collector.lan", 514, HTTP), resolve("  http://collector.lan/  "))
    }

    @Test fun aBlankHostStaysBlankSoShippingRemainsInert() {
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_TCP), resolve(""))
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_TCP), resolve("   ", protocol = "syslog"))
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_UDP), resolve("udp://"))
    }

    @Test fun anUnparseableValueIsPassedThroughRatherThanRewritten() {
        // An underscore is not a legal URI hostname but resolves fine, and a path cannot be honoured
        // by a transport that only ever posts to "/". Both are kept verbatim so the failure names
        // exactly what was typed instead of a silently rewritten guess.
        assertEquals(
            LogShipEndpoint.Endpoint("log_collector", 514, SYSLOG_TCP),
            resolve("log_collector"),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan/ingest", 8080, HTTP),
            resolve("http://collector.lan/ingest", port = 8080),
        )
    }

    @Test fun anOutOfRangePortFallsBackToTheStoredPort() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 514, SYSLOG_TCP),
            resolve("collector.lan:70000"),
        )
    }

    // ---- canonicalUpdate: the three stored fields always describe one destination -----------------

    private val stored = Triple("stored.lan", 514, SYSLOG_TCP)

    private fun canonical(vararg pairs: Pair<String, String>) =
        LogShipEndpoint.canonicalUpdate(linkedMapOf(*pairs), stored.first, stored.second, stored.third)

    @Test fun anUpdateTouchingNoAddressKeyStagesNothing() {
        // The caller skips staging entirely rather than rewriting fields nobody asked to change.
        assertNull(canonical("log_ship_enabled" to "true", "mqtt_broker" to "tcp://broker"))
        assertNull(LogShipEndpoint.canonicalUpdate(emptyMap(), "stored.lan", 514, SYSLOG_TCP))
    }

    @Test fun anEmbeddedAddressRewritesAllThreeFieldsTogether() {
        // The defect this exists for: stored verbatim, the host says udp/1514 while Port and Protocol
        // still say 514/tcp, so shipping goes one place and every surface reports another.
        assertEquals(
            mapOf(
                "log_ship_host" to "collector.lan",
                "log_ship_port" to "1514",
                "log_ship_protocol" to SYSLOG_UDP,
            ),
            canonical("log_ship_host" to "udp://collector.lan:1514"),
        )
    }

    @Test fun anEmbeddedAddressOutranksTheSeparateFieldsInTheSameUpdate() {
        // Legacy-bundle precedence. A panel whose stored host was `udp://collector.lan:1514` really was
        // shipping UDP to 1514 — resolve() takes the destination from the host at send time — so a
        // bundle taken from it must reproduce that, not the stale Port/Protocol it also carried.
        val result = canonical(
            "log_ship_host" to "udp://collector.lan:1514",
            "log_ship_port" to "514",
            "log_ship_protocol" to SYSLOG_TCP,
        )
        assertEquals("1514", result?.get("log_ship_port"))
        assertEquals(SYSLOG_UDP, result?.get("log_ship_protocol"))
    }

    @Test fun theResultIsIndependentOfTheOrderTheBatchIsIterated() {
        // Order-independence is the property the review named. Same three entries, all six insertion
        // orders: a result that varied would mean the fix depended on how a caller built its map.
        val entries = listOf(
            "log_ship_host" to "udp://collector.lan:1514",
            "log_ship_port" to "514",
            "log_ship_protocol" to SYSLOG_TCP,
        )
        val results = permutations(entries).map { ordering ->
            LogShipEndpoint.canonicalUpdate(
                linkedMapOf(*ordering.toTypedArray()), stored.first, stored.second, stored.third,
            )
        }
        assertEquals(6, results.size)
        assertEquals(1, results.distinct().size)
        assertEquals(
            mapOf(
                "log_ship_host" to "collector.lan",
                "log_ship_port" to "1514",
                "log_ship_protocol" to SYSLOG_UDP,
            ),
            results.first(),
        )
    }

    @Test fun aPlainHostTakesTheUpdatesOwnPortAndProtocolThenTheStoredOnes() {
        // Nothing embedded to outrank, so the explicit fields stand; absent ones fall back to stored.
        assertEquals(
            mapOf(
                "log_ship_host" to "collector.lan",
                "log_ship_port" to "6514",
                "log_ship_protocol" to HTTP,
            ),
            canonical(
                "log_ship_host" to "collector.lan",
                "log_ship_port" to "6514",
                "log_ship_protocol" to HTTP,
            ),
        )
        assertEquals(
            mapOf(
                "log_ship_host" to "collector.lan",
                "log_ship_port" to "514",
                "log_ship_protocol" to SYSLOG_TCP,
            ),
            canonical("log_ship_host" to "collector.lan"),
        )
    }

    @Test fun aPortOnlyUpdateStillRepairsAnAlreadyDesynchronisedStoredHost() {
        // A panel upgraded from a build that stored the host verbatim is repaired by the next write
        // touching any of the three, not left inconsistent until the host itself is edited again.
        assertEquals(
            mapOf(
                "log_ship_host" to "collector.lan",
                "log_ship_port" to "1514",
                "log_ship_protocol" to SYSLOG_UDP,
            ),
            LogShipEndpoint.canonicalUpdate(
                mapOf("log_ship_port" to "9999"), "udp://collector.lan:1514", 514, SYSLOG_TCP,
            ),
        )
    }

    @Test fun clearingTheHostIsNotMistakenForAnEmbeddedAddress() {
        // Blank host means "stop shipping"; it must not resurrect a stored host or invent a port.
        assertEquals(
            mapOf(
                "log_ship_host" to "",
                "log_ship_port" to "514",
                "log_ship_protocol" to SYSLOG_TCP,
            ),
            canonical("log_ship_host" to ""),
        )
    }

    @Test fun anAbsentFieldFallsBackToWhatIsStoredNotToTheRegistryDefault() {
        // The stored port is deliberately not 514. A fallback hard-coded to the default would satisfy
        // every other case in this file — they all store 514 — while silently retargeting any panel
        // configured on a non-standard port the moment an unrelated field was edited.
        assertEquals(
            mapOf(
                "log_ship_host" to "collector.lan",
                "log_ship_port" to "6601",
                "log_ship_protocol" to HTTP,
            ),
            LogShipEndpoint.canonicalUpdate(
                mapOf("log_ship_host" to "collector.lan"), "old.lan", 6601, HTTP,
            ),
        )
    }

    @Test fun anUnparseablePortInTheUpdateFallsBackRatherThanThrowing() {
        // Validation runs before this, but the fallback must not depend on that ordering.
        assertEquals("514", canonical("log_ship_port" to "not-a-number")?.get("log_ship_port"))
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { head ->
            permutations(items - head).map { tail -> listOf(head) + tail }
        }
}
