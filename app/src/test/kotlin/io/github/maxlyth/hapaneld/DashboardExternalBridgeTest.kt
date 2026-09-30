package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.sensors.HaAmbientTransport
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.github.maxlyth.hapaneld.util.HaTransportEvidence
import io.github.maxlyth.hapaneld.util.HaTransportFault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardV2CompatibilityTest {
    @Test fun `HA 2026 4 2 is the exact V2 floor`() {
        assertFalse(DashboardV2Compatibility.eligible("2026.4.1", true))
        assertTrue(DashboardV2Compatibility.eligible("2026.4.2", true))
        assertTrue(DashboardV2Compatibility.eligible("2026.5.0", true))
        assertTrue(DashboardV2Compatibility.eligible("2026.4.2b0", true).not())
        assertFalse(DashboardV2Compatibility.eligible("2026.4.2-beta.1", true))
        assertFalse(DashboardV2Compatibility.eligible("2026.13.1", true))
        assertFalse(DashboardV2Compatibility.eligible(null, true))
        assertFalse(DashboardV2Compatibility.eligible("not-a-version", true))
        assertFalse(DashboardV2Compatibility.eligible("2027.1.0", false))
    }

    @Test fun `admission uses cache only for transient live unavailability`() {
        assertEquals(
            DashboardV2Admission.Compatible("2026.4.2", live = false),
            DashboardV2Admission.resolve(DashboardV2ProbeResult.Unavailable("offline"), "2026.4.2"),
        )
        assertEquals(
            DashboardV2Admission.Blocked(DashboardV2ProbeResult.Unavailable("offline")),
            DashboardV2Admission.resolve(DashboardV2ProbeResult.Unavailable("offline"), "2026.4.1"),
        )
        assertEquals(
            DashboardV2Admission.Blocked(DashboardV2ProbeResult.UnsupportedHa("2026.4.1")),
            DashboardV2Admission.resolve(DashboardV2ProbeResult.UnsupportedHa("2026.4.1"), "2026.5.0"),
        )
    }
}

class DashboardV2CompatibilityProbeTest {
    @Test fun `live stable versions are classified at the exact floor`() = runTest {
        assertEquals(
            DashboardV2ProbeResult.Compatible("2026.4.2"),
            probe(listOf(HaApiSession(URL, "token")), JSONObject().put("version", "2026.4.2")).check(),
        )
        assertEquals(
            DashboardV2ProbeResult.UnsupportedHa("2026.4.1"),
            probe(listOf(HaApiSession(URL, "token")), JSONObject().put("version", "2026.4.1")).check(),
        )
        assertEquals(
            DashboardV2ProbeResult.Unverifiable("2026.4.2b0"),
            probe(listOf(HaApiSession(URL, "token")), JSONObject().put("version", "2026.4.2b0")).check(),
        )
    }

    @Test fun `REST rejection performs exactly one forced credential refresh`() = runTest {
        val forces = mutableListOf<Boolean>()
        val sessions = ArrayDeque(
            listOf(HaApiSession(URL, "old"), HaApiSession(URL, "fresh")),
        )
        val auth = HaApiSessionProvider { force ->
            forces += force
            sessions.removeFirst()
        }
        var calls = 0
        val transport = ConfigTransport {
            if (++calls == 1) throw HaAuthenticationException("rejected")
            JSONObject().put("version", "2026.5.0")
        }

        assertEquals(
            DashboardV2ProbeResult.Compatible("2026.5.0"),
            DashboardV2CompatibilityProbe(auth, transport, Dispatchers.Unconfined).check(),
        )
        assertEquals(listOf(false, true), forces)
        assertEquals(2, calls)
    }

    @Test fun `missing credentials reject without network and transient errors stay unavailable`() = runTest {
        var networkCalls = 0
        val missing = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, null, rejected = true) },
            ConfigTransport { networkCalls++; JSONObject() },
            Dispatchers.Unconfined,
        ).check()
        assertEquals(DashboardV2ProbeResult.AuthenticationFailed, missing)
        assertEquals(0, networkCalls)

        val unavailable = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, "token") },
            ConfigTransport { error("network\nfailed") },
            Dispatchers.Unconfined,
        ).check()
        // The panel-facing detail is unchanged; the probe now also classifies the throwable it
        // caught, so a diagnostic surface can name the failure without republishing this text.
        assertEquals(
            DashboardV2ProbeResult.Unavailable(
                "network failed",
                HaTransportEvidence(HaTransportFault.UNKNOWN, "IllegalStateException"),
            ),
            unavailable,
        )
    }

    // A TLS trust failure during the token refresh must render as transport unavailability naming the
    // fault, not as an authentication rejection that sends diagnosis at the credentials.
    @Test fun `transport-failed refresh is unavailable with the failure detail, not an auth rejection`() = runTest {
        var networkCalls = 0
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider {
                HaApiSession(URL, null, rejected = false, transientDetail = "Trust anchor for certification path not found")
            },
            ConfigTransport { networkCalls++; JSONObject() },
            Dispatchers.Unconfined,
        ).check()
        assertEquals(DashboardV2ProbeResult.Unavailable("Trust anchor for certification path not found"), result)
        assertEquals(0, networkCalls)
    }

    // A probe can run 1.5 s into a cold boot, before the configuration store has loaded, so no credential
    // is judged at all. A later retry can succeed with the same credential. A session that was never
    // ATTEMPTED must therefore not reach an auth verdict.
    @Test fun `a session that was never attempted is unavailable, not an auth rejection`() = runTest {
        var networkCalls = 0
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, null, rejected = false, notAttempted = true) },
            ConfigTransport { networkCalls++; JSONObject() },
            Dispatchers.Unconfined,
        ).check()

        assertTrue("not-attempted must not be an auth verdict", result is DashboardV2ProbeResult.Unavailable)
        assertNotEquals(DashboardV2ProbeResult.AuthenticationFailed, result)
        // It carries no invented transport fault: nothing was attempted, so there is nothing to blame.
        assertEquals(HaTransportEvidence.NONE, (result as DashboardV2ProbeResult.Unavailable).evidence)
        assertEquals(0, networkCalls)
        // And it recovers on its own rather than parking, which is the whole point of the report.
        assertEquals(AdmissionRetryClass.FROM_BASE, admissionRetryClass(AdmissionOutcome.TRANSPORT_FAILED))
    }

    // A panel that genuinely holds no credential is still an auth verdict — the fix must not turn a
    // real "connect the panel" state into an endless retry that can never succeed.
    @Test fun `an unconfigured panel that attempted resolution still reports an auth verdict`() = runTest {
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, null, rejected = false, notAttempted = false) },
            ConfigTransport { JSONObject() },
            Dispatchers.Unconfined,
        ).check()
        assertEquals(DashboardV2ProbeResult.AuthenticationFailed, result)
    }

    @Test fun `transport failure during the forced refresh is unavailable, not an auth rejection`() = runTest {
        // A real 401 on /api/config forces one refresh; the mint then dies in transport. The panel
        // could not prove anything about the credential, so the verdict is unavailability.
        val sessions = ArrayDeque(
            listOf(
                HaApiSession(URL, "old"),
                HaApiSession(URL, null, rejected = false, transientDetail = "connect timed out"),
            ),
        )
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { sessions.removeFirst() },
            ConfigTransport { throw HaAuthenticationException("rejected") },
            Dispatchers.Unconfined,
        ).check()
        assertEquals(DashboardV2ProbeResult.Unavailable("connect timed out"), result)
    }

    @Test fun `absent credentials with no transport failure still reject`() = runTest {
        // Guards the correction's scope: a panel that was never signed in must keep the sign-in
        // verdict — only a named transport failure reclassifies.
        var networkCalls = 0
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, null, rejected = false) },
            ConfigTransport { networkCalls++; JSONObject() },
            Dispatchers.Unconfined,
        ).check()
        assertEquals(DashboardV2ProbeResult.AuthenticationFailed, result)
        assertEquals(0, networkCalls)
    }

    @Test fun `transient refresh detail is scrubbed and bounded like transport errors`() = runTest {
        val noisy = "line one\nline two\t\tpadded   " + "x".repeat(400)
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, null, transientDetail = noisy) },
            ConfigTransport { JSONObject() },
            Dispatchers.Unconfined,
        ).check()
        // Assert the classification before casting: an unchecked cast turns the most important
        // regression (transport misread as an auth rejection) into a ClassCastException, which is a
        // test that errors rather than one that fails — and an error proves nothing about the contract.
        assertTrue("a transport failure must classify as Unavailable, got $result", result is DashboardV2ProbeResult.Unavailable)
        val detail = (result as DashboardV2ProbeResult.Unavailable).detail
        assertFalse(detail.contains("\n"))
        assertTrue(detail.length <= 240)
        assertTrue(detail.startsWith("line one line two"))
    }

    // The synergy the classification fix buys: a transport-failed refresh with a previously verified
    // eligible version now admits the renderer on the cache instead of parking the panel.
    @Test fun `transport-failed refresh with a cached verified version admits the renderer`() = runTest {
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, null, transientDetail = "connect timed out") },
            ConfigTransport { JSONObject() },
            Dispatchers.Unconfined,
        ).check()
        assertEquals(
            DashboardV2Admission.Compatible("2026.5.0", live = false),
            DashboardV2Admission.resolve(result, "2026.5.0"),
        )
    }

    @Test fun `missing required instance proof cannot admit renderer from cached version`() = runTest {
        var configReads = 0
        val result = DashboardV2CompatibilityProbe(
            HaApiSessionProvider { HaApiSession(URL, null, transientDetail = "Home Assistant connection unavailable") },
            ConfigTransport { configReads++; JSONObject().put("version", "2026.5.0") },
            Dispatchers.Unconfined,
            requiresProvenRoute = { true },
        ).check()
        assertEquals(0, configReads)
        assertTrue(result is DashboardV2ProbeResult.Unavailable)
        assertEquals(DashboardV2Admission.Blocked(result), DashboardV2Admission.resolve(result, "2026.5.0"))
    }

    private fun probe(sessions: List<HaApiSession>, response: JSONObject): DashboardV2CompatibilityProbe {
        val remaining = ArrayDeque(sessions)
        return DashboardV2CompatibilityProbe(
            HaApiSessionProvider { remaining.removeFirst() },
            ConfigTransport { response },
            Dispatchers.Unconfined,
        )
    }

    private fun interface ConfigCall { suspend fun run(): JSONObject }

    private class ConfigTransport(private val call: ConfigCall) : HaAmbientTransport {
        override suspend fun config(baseUrl: String, accessToken: String): JSONObject = call.run()
        override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? = null
        override suspend fun states(baseUrl: String, accessToken: String): JSONArray = JSONArray()
    }

    private companion object { const val URL = "https://ha.example" }
}

class DashboardV2AttemptGateTest {
    @Test fun `only the latest attempt for the unchanged endpoint and auth owner may complete`() {
        val gate = DashboardV2AttemptGate()
        val firstOwner = owner("https://first.example", "first-refresh")
        val first = gate.start(firstOwner)
        assertTrue(gate.owns(first, firstOwner))
        assertFalse(gate.owns(first, firstOwner.copy(routeEpoch = firstOwner.routeEpoch + 1)))
        assertFalse(gate.owns(first, owner("https://changed.example", "first-refresh")))
        assertFalse(gate.owns(first, owner("https://first.example", "replacement-refresh")))

        val secondOwner = owner("https://second.example", "second-refresh")
        val second = gate.start(secondOwner)
        assertFalse(gate.owns(first, firstOwner))
        assertTrue(gate.owns(second, secondOwner))
        gate.invalidate()
        assertFalse(gate.owns(second, secondOwner))
    }

    private fun owner(url: String, refreshToken: String) = DashboardV2CompatibilityOwner(
        normalizedUrl = url,
        authOwner = HaAuthOwner(url, refreshToken, "client", ""),
    )
}

class BoundedAuthQueueTest {
    private data class Request(val owner: Int, val force: Boolean, val label: String)

    @Test fun `queue retains one active and one successor with forced request priority`() {
        val queue = queue()
        val active = requireNotNull(queue.offer(Request(1, false, "active")))
        assertEquals(null, queue.offer(Request(1, true, "forced")))
        assertEquals(null, queue.offer(Request(1, false, "ordinary-after-force")))
        assertEquals(2, queue.retainedCount())

        val next = requireNotNull(queue.complete(active) { true })
        assertEquals("forced", next.request.label)
        assertEquals(1, queue.retainedCount())
        assertEquals(null, queue.complete(next) { true })
        assertEquals(0, queue.retainedCount())
    }

    @Test fun `clear makes stale completion unable to consume newer document work`() {
        val queue = queue()
        val stale = requireNotNull(queue.offer(Request(1, false, "stale")))
        queue.clear()
        val current = requireNotNull(queue.offer(Request(2, false, "current")))

        assertEquals(null, queue.complete(stale) { true })
        assertEquals(1, queue.retainedCount())
        assertEquals(null, queue.complete(current) { true })
        assertEquals(0, queue.retainedCount())
    }

    @Test fun `completion drops a successor whose generation is no longer current`() {
        val queue = queue()
        val active = requireNotNull(queue.offer(Request(1, false, "active")))
        queue.offer(Request(1, false, "pending"))
        assertEquals(null, queue.complete(active) { false })
        assertEquals(0, queue.retainedCount())
    }

    private fun queue() = BoundedAuthQueue<Request>(
        sameOwner = { left, right -> left.owner == right.owner },
        forced = Request::force,
    )
}

class V2HandshakeGateTest {
    // Each document is driven through the WebViewClient callbacks in the order Android delivers them.
    private fun V2HandshakeGate.committedAndFinished(session: ExternalBusController.Session) {
        begin(session); commit(session); finish(session)
    }

    @Test fun `missing V2 evidence is counted only for finished current documents`() {
        val gate = V2HandshakeGate(missingDocumentLimit = 2)
        val first = ExternalBusController.Session(1, 1)
        val second = ExternalBusController.Session(1, 2)

        gate.begin(first)
        gate.commit(first)
        assertFalse(gate.onTimeout(first))
        gate.finish(first)
        assertFalse(gate.onTimeout(first))
        gate.begin(second)
        assertFalse(gate.onTimeout(first))
        gate.commit(second)
        gate.finish(second)
        assertTrue(gate.onTimeout(second))
    }

    @Test fun `documents that finish without committing during a hung load never block`() {
        // Home Assistant accepting connections while it starts: each retry's load finishes without ever
        // committing a page, so the watchdog must keep retrying behind the reconnecting page.
        val gate = V2HandshakeGate(missingDocumentLimit = 2)
        for (document in 1..4) {
            val session = ExternalBusController.Session(3, document.toLong())
            gate.begin(session)
            gate.finish(session)
            assertFalse("uncommitted document $document", gate.onTimeout(session))
        }
        // Home Assistant then serves a page that really cannot run the bridge: it still blocks in two.
        val served = ExternalBusController.Session(3, 5)
        gate.committedAndFinished(served)
        assertFalse(gate.onTimeout(served))
        val retried = ExternalBusController.Session(3, 6)
        gate.committedAndFinished(retried)
        assertTrue(gate.onTimeout(retried))
    }

    @Test fun `valid current-document V2 observation clears missing history`() {
        val gate = V2HandshakeGate(missingDocumentLimit = 2)
        val first = ExternalBusController.Session(2, 1)
        val second = ExternalBusController.Session(2, 2)
        val third = ExternalBusController.Session(2, 3)

        gate.committedAndFinished(first)
        assertFalse(gate.onTimeout(first))
        gate.observe(first)
        assertFalse(gate.onTimeout(first))
        gate.committedAndFinished(second)
        assertFalse(gate.onTimeout(second))
        gate.committedAndFinished(third)
        assertTrue(gate.onTimeout(third))
        gate.reset()
        assertFalse(gate.onTimeout(third))
    }
}

class DashboardV2OriginTest {
    @Test fun `origin comparison uses scheme host and effective port only`() {
        assertTrue(sameDashboardOrigin("https://HA.Example", "https://ha.example/dashboard?x=1"))
        assertTrue(sameDashboardOrigin("https://ha.example:443", "https://ha.example/path"))
        assertTrue(sameDashboardOrigin("http://ha.example", "http://ha.example:80/path"))
        assertFalse(sameDashboardOrigin("http://ha.example", "https://ha.example/path"))
        assertFalse(sameDashboardOrigin("https://ha.example", "https://sub.ha.example/path"))
        assertFalse(sameDashboardOrigin("https://ha.example:8443", "https://ha.example/path"))
        assertFalse(sameDashboardOrigin("data:", "data:text/html,x"))
        assertFalse(sameDashboardOrigin(null, "https://ha.example"))
        assertFalse(sameDashboardOrigin("https://ha.example", null))
    }
}

class ExternalAppV2ProtocolTest {
    @Test fun `official auth revoke and bus envelopes retain object payloads`() {
        val auth = ExternalAppV2Protocol.parse(
            """{"type":"getExternalAuth","payload":{"callback":"externalAuthSetToken","force":true}}""",
        ) as ExternalAppV2Protocol.Incoming.GetExternalAuth
        assertEquals(true, ExternalAuthProtocol.validAuthRequestForce(auth.payload))

        val revoke = ExternalAppV2Protocol.parse(
            """{"type":"revokeExternalAuth","payload":{"callback":"externalAuthRevokeToken"}}""",
        ) as ExternalAppV2Protocol.Incoming.RevokeExternalAuth
        assertTrue(ExternalAuthProtocol.revokeReply(revoke.payload)!!.contains("externalAuthRevokeToken(true)"))

        val bus = ExternalAppV2Protocol.parse(
            """{"type":"externalBus","payload":{"id":11,"type":"config/get"}}""",
        ) as ExternalAppV2Protocol.Incoming.ExternalBus
        assertEquals(ExternalBusProtocol.Incoming.ConfigGet(11), ExternalBusProtocol.parse(bus.payload))
    }

    @Test fun `malformed bounded and future envelopes are harmless`() {
        listOf(
            null,
            "not-json",
            "{}",
            """{"type":"externalBus"}""",
            """{"type":"externalBus","payload":"quoted"}""",
            """{"type":"getExternalAuth","payload":[]}""",
        ).forEach { assertTrue("$it", ExternalAppV2Protocol.parse(it) is ExternalAppV2Protocol.Incoming.Malformed) }
        assertTrue(
            ExternalAppV2Protocol.parse("x".repeat(ExternalAppV2Protocol.MAX_MESSAGE_CHARS + 1))
                is ExternalAppV2Protocol.Incoming.Malformed,
        )
        val deep = """{"type":"future","payload":""" + "[".repeat(33) + "]".repeat(33) + "}"
        assertEquals(ExternalAppV2Protocol.Incoming.Malformed("too-deep"), ExternalAppV2Protocol.parse(deep))
        assertEquals(
            ExternalAppV2Protocol.Incoming.Unknown("future/envelope"),
            ExternalAppV2Protocol.parse("""{"type":"future/envelope","payload":{"anything":true}}"""),
        )
    }
}

class HaPaneldV2ProtocolTest {
    @Test fun `private telemetry has typed bounded envelopes`() {
        assertEquals(
            HaPaneldV2Protocol.Incoming.EntityFilterSubscriptionModified,
            HaPaneldV2Protocol.parse("""{"type":"entityFilterSubscriptionModified"}"""),
        )
        assertEquals(
            HaPaneldV2Protocol.Incoming.EntityFilterTrafficMetrics("1,2,3"),
            HaPaneldV2Protocol.parse("""{"type":"entityFilterTrafficMetrics","payload":"1,2,3"}"""),
        )
        val access = HaPaneldV2Protocol.parse(
            """{"type":"entityLearningAccesses","payload":{"accessed":{"light.one":2},"missing":[]}}""",
        ) as HaPaneldV2Protocol.Incoming.EntityLearningAccesses
        assertTrue(access.payload.contains("light.one"))
        assertTrue(
            HaPaneldV2Protocol.parse("""{"type":"entityLearningMetrics","payload":"wrong"}""")
                is HaPaneldV2Protocol.Incoming.Malformed,
        )
        assertTrue(
            HaPaneldV2Protocol.parse("x".repeat(HaPaneldV2Protocol.MAX_MESSAGE_CHARS + 1))
                is HaPaneldV2Protocol.Incoming.Malformed,
        )
    }
}
