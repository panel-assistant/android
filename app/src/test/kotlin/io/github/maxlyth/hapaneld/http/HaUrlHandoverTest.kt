package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.panelConfiguredBeforeSetupTracking
import io.ktor.http.Parameters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handed-over Home Assistant URL: what the panel accepts, what it refuses, and what the wizard is
 * left holding in each case.
 *
 * The acceptance cases named by the task are the four a panel can actually be in — a handover that is
 * reachable, one that is not, no handover at all, and a payload from an integration too old to send
 * one — plus the regression that a verified handover must not collapse the rest of the journey.
 */
class HaUrlHandoverTest {

    private fun params(vararg pairs: Pair<String, String>): Parameters = Parameters.build {
        pairs.forEach { (key, value) -> append(key, value) }
    }

    private fun rewrite(
        posted: Parameters,
        currentHaUrl: String = "",
        outcome: HaUrlHandover.Outcome = HaUrlHandover.Outcome.VERIFIED,
        onVerify: (String) -> Unit = {},
    ): HandoverConfigPost = runBlocking {
        rewritePostForHandover(posted, currentHaUrl) { offered ->
            onVerify(offered)
            outcome
        }
    }

    /* ---------------- the probe's discrimination ---------------- */

    @Test fun `Home Assistant's own 401 is the only answer that verifies`() {
        assertEquals(HaUrlHandover.Outcome.VERIFIED, HaUrlHandover.classifyStatus(401))
    }

    @Test fun `a 200 is a failure, not a weaker success`() {
        // Measured against a live Home Assistant: an unauthenticated GET /api/ answers 401. A 200 there
        // means something that is not Home Assistant holds the address — a captive portal or a
        // permissive proxy — which is exactly the false positive a bare TCP connect would have taken.
        assertEquals(HaUrlHandover.Outcome.NOT_HOME_ASSISTANT, HaUrlHandover.classifyStatus(200))
    }

    @Test fun `every other status is refused too`() {
        listOf(100, 201, 204, 301, 302, 400, 403, 404, 418, 500, 502, 503).forEach { status ->
            assertEquals(
                "status $status must not verify",
                HaUrlHandover.Outcome.NOT_HOME_ASSISTANT,
                HaUrlHandover.classifyStatus(status),
            )
        }
    }

    @Test fun `each transport failure keeps its own reason`() {
        assertEquals(
            HaUrlHandover.Outcome.UNRESOLVABLE,
            HaUrlHandover.classifyFailure(java.net.UnknownHostException("ha.invalid")),
        )
        assertEquals(
            HaUrlHandover.Outcome.UNREACHABLE,
            HaUrlHandover.classifyFailure(java.net.ConnectException("refused")),
        )
        assertEquals(
            HaUrlHandover.Outcome.UNREACHABLE,
            HaUrlHandover.classifyFailure(java.net.NoRouteToHostException("no route")),
        )
        assertEquals(
            HaUrlHandover.Outcome.TIMEOUT,
            HaUrlHandover.classifyFailure(java.net.SocketTimeoutException("timed out")),
        )
        assertEquals(
            HaUrlHandover.Outcome.TLS,
            HaUrlHandover.classifyFailure(javax.net.ssl.SSLHandshakeException("bad cert")),
        )
        assertEquals(
            HaUrlHandover.Outcome.INVALID,
            HaUrlHandover.classifyFailure(java.net.MalformedURLException("nonsense")),
        )
    }

    @Test fun `a TLS failure is classified as TLS and not as the IOException it also is`() {
        // SSLException extends IOException and SocketTimeoutException extends InterruptedIOException, so
        // the order of the when-branches is load-bearing: a broad IOException arm placed first would
        // swallow both and report every HTTPS certificate problem as an unreachable address.
        val tls: Throwable = javax.net.ssl.SSLPeerUnverifiedException("no peer certificate")
        assertTrue(tls is java.io.IOException)
        assertEquals(HaUrlHandover.Outcome.TLS, HaUrlHandover.classifyFailure(tls))
    }

    @Test fun `an unrecognised failure is reported as unreachable rather than verified`() {
        assertEquals(
            HaUrlHandover.Outcome.UNREACHABLE,
            HaUrlHandover.classifyFailure(IllegalStateException("something else entirely")),
        )
        assertFalse(HaUrlHandover.classifyFailure(IllegalStateException("x")).verified)
    }

    @Test fun `only VERIFIED reports itself verified, and only it has a blank reason`() {
        HaUrlHandover.Outcome.entries.forEach { outcome ->
            assertEquals(
                "${outcome.name} verified flag must match its identity",
                outcome == HaUrlHandover.Outcome.VERIFIED,
                outcome.verified,
            )
            assertEquals(
                "${outcome.name} must have a reason exactly when it is not verified",
                outcome != HaUrlHandover.Outcome.VERIFIED,
                outcome.reason.isNotBlank(),
            )
        }
    }

    /* ---------------- the probe URL ---------------- */

    @Test fun `the probe path is appended with exactly one separator`() {
        assertEquals("http://ha.local:8123/api/", HaUrlHandover.probeUrl("http://ha.local:8123"))
        assertEquals("http://ha.local:8123/api/", HaUrlHandover.probeUrl("http://ha.local:8123/"))
        assertEquals("http://ha.local:8123/api/", HaUrlHandover.probeUrl("  http://ha.local:8123//  "))
        assertEquals("https://ha.example.com/api/", HaUrlHandover.probeUrl("https://ha.example.com"))
    }

    @Test fun `anything that is not an http origin has no probe URL`() {
        assertNull(HaUrlHandover.probeUrl(""))
        assertNull(HaUrlHandover.probeUrl("   "))
        assertNull(HaUrlHandover.probeUrl("ha.local:8123"))
        assertNull(HaUrlHandover.probeUrl("ftp://ha.local"))
        assertNull(HaUrlHandover.probeUrl("javascript:alert(1)"))
    }

    @Test fun `the scheme test is case-insensitive but the URL is passed through unchanged`() {
        assertEquals("HTTP://ha.local:8123/api/", HaUrlHandover.probeUrl("HTTP://ha.local:8123"))
    }

    /* ---------------- acceptance case: handed over and reachable ---------------- */

    @Test fun `a verified handover becomes an ordinary ha_url write`() {
        val result = rewrite(
            params("ha_setup_handover" to "true", "ha_url_handover" to "http://ha.local:8123"),
            outcome = HaUrlHandover.Outcome.VERIFIED,
        )
        assertEquals("http://ha.local:8123", result.parameters["ha_url"])
        // Cleared rather than omitted: the wizard must not later find a stale address to correct.
        assertEquals("", result.parameters["ha_url_handover"])
        assertEquals("", result.parameters["ha_url_handover_reason"])
        assertEquals(HaUrlHandover.Outcome.VERIFIED, result.outcome)
    }

    @Test fun `a verified handover keeps the provenance marker the integration sent`() {
        val result = rewrite(params("ha_setup_handover" to "true", "ha_url_handover" to "http://ha.local:8123"))
        assertEquals("true", result.parameters["ha_setup_handover"])
    }

    /* ---------------- acceptance case: handed over and unreachable ---------------- */

    @Test fun `a failed handover keeps the address and the reason for the correction`() {
        val result = rewrite(
            params("ha_setup_handover" to "true", "ha_url_handover" to "http://172.17.0.2:8123"),
            outcome = HaUrlHandover.Outcome.UNREACHABLE,
        )
        assertNull("a failed address must never become the panel's ha_url", result.parameters["ha_url"])
        assertEquals("http://172.17.0.2:8123", result.parameters["ha_url_handover"])
        assertEquals("unreachable", result.parameters["ha_url_handover_reason"])
        assertEquals(HaUrlHandover.Outcome.UNREACHABLE, result.outcome)
        assertEquals("http://172.17.0.2:8123", result.url)
    }

    @Test fun `a failed handover still records that Home Assistant deployed this panel`() {
        // The marker is what every skipping step reads, and it is deliberately independent of whether
        // the address worked: a panel whose handover failed is still one Home Assistant deployed, which
        // is precisely why the wizard offers a correction instead of a blank question.
        val result = rewrite(
            params("ha_setup_handover" to "true", "ha_url_handover" to "http://172.17.0.2:8123"),
            outcome = HaUrlHandover.Outcome.TIMEOUT,
        )
        assertEquals("true", result.parameters["ha_setup_handover"])
    }

    @Test fun `every failure reason reaches the stored field verbatim`() {
        HaUrlHandover.Outcome.entries.filterNot { it.verified }.forEach { outcome ->
            val result = rewrite(params("ha_url_handover" to "http://ha.local:8123"), outcome = outcome)
            assertEquals(outcome.reason, result.parameters["ha_url_handover_reason"])
        }
    }

    /* ---------------- acceptance case: no handover ---------------- */

    @Test fun `a POST with no handover never probes and keeps every value it carried`() {
        var probed = false
        val posted = params("ha_url" to "http://typed.local:8123", "panel_id" to "a_panel")
        val result = rewrite(posted, onVerify = { probed = true })
        assertFalse("a POST with no handover must not cost a probe", probed)
        assertNull("no handover means the response says nothing about one", result.outcome)
        assertEquals("http://typed.local:8123", result.parameters["ha_url"])
        assertEquals("a_panel", result.parameters["panel_id"])
        // The only additions are the cleared handover fields, because saving an address answers any
        // correction that was outstanding. Nothing the caller sent is dropped or altered.
        posted.names().forEach { name ->
            assertEquals(
                "$name must survive unchanged",
                posted.getAll(name),
                result.parameters.getAll(name),
            )
        }
    }

    @Test fun `a blank handover is treated as no handover at all`() {
        var probed = false
        val result = rewrite(params("ha_url_handover" to "   "), onVerify = { probed = true })
        assertFalse(probed)
        assertNull(result.outcome)
    }

    /* ---------------- acceptance case: an older integration's payload ---------------- */

    @Test fun `an older integration's payload carries no handover key and is unaffected`() {
        // An integration that predates the handover sends exactly what it always sent. The panel must
        // behave as it did before, which means no probe, no marker and no rewriting.
        var probed = false
        val legacy = params("mqtt_broker" to "192.168.1.5", "mqtt_user" to "panel")
        val result = rewrite(legacy, onVerify = { probed = true })
        assertFalse(probed)
        assertNull(result.outcome)
        assertNull(result.parameters["ha_setup_handover"])
        assertNull(result.parameters["ha_url_handover"])
        assertEquals(legacy.names(), result.parameters.names())
    }

    @Test fun `an unknown key is refused by admission, which is why the integration must ask first`() {
        // This is the whole reason the panel advertises support on GET /api/v1/setup instead of letting
        // an old panel ignore a key it does not know. Admission refuses the unknown name AND the refusal
        // is atomic, so a blind handover would not merely fail to hand over — it would drop every other
        // setting in the same request.
        val posted = params("ha_url_handover" to "http://ha.local:8123", "panel_id" to "kitchen_panel")
        val refused = normalizeConfigPostParameters(Parameters.build { append("ha_not_a_real_key", "x") })
        assertTrue(refused is ConfigPostParameters.Bad)
        assertEquals("ha_not_a_real_key: unknown setting", (refused as ConfigPostParameters.Bad).reason)
        // And the handover key itself is admitted, so a current panel accepts the same shape.
        assertTrue(normalizeConfigPostParameters(posted) is ConfigPostParameters.Ok)
    }

    /* ---------------- answering the correction closes it out ---------------- */

    @Test fun `saving an address directly clears the failed handover it answers`() {
        // The correction card's Save posts `ha_url`. Nothing else could ever retract the attempted
        // address and its reason, because the reason is refused from the network by design, so they
        // would otherwise outlive the problem they describe.
        val result = rewrite(params("ha_url" to "http://typed.local:8123"))
        assertEquals("", result.parameters["ha_url_handover"])
        assertEquals("", result.parameters["ha_url_handover_reason"])
        assertEquals("http://typed.local:8123", result.parameters["ha_url"])
        assertNull("clearing a stale verdict is not itself a handover", result.outcome)
    }

    @Test fun `clearing the address does not fabricate a handover verdict`() {
        val result = rewrite(params("ha_url" to ""))
        assertNull(result.parameters["ha_url_handover_reason"])
        assertNull(result.outcome)
    }

    /* ---------------- idempotence ---------------- */

    @Test fun `a handover to an already-configured panel is dropped without probing`() {
        // Adoption is re-runnable. A retry must converge rather than overwrite a panel that already
        // works, and it must not spend a probe proving something the panel no longer needs.
        var probed = false
        val result = rewrite(
            params("ha_setup_handover" to "true", "ha_url_handover" to "http://ha.local:8123"),
            currentHaUrl = "http://already.local:8123",
            onVerify = { probed = true },
        )
        assertFalse("an already-configured panel must not be probed", probed)
        assertNull(result.parameters["ha_url"])
        assertNull(result.parameters["ha_url_handover"])
        assertNull(result.outcome)
        // The marker still lands: Home Assistant did adopt this panel, whatever its URL already was.
        assertEquals("true", result.parameters["ha_setup_handover"])
    }

    /* ---------------- the caller may not declare its own address reachable ---------------- */

    @Test fun `the panel's own verdict is the only thing that ever sets the reason`() {
        // Admission refuses a caller-supplied reason outright (below), so by the time the rewrite runs
        // the only value that can appear is the one the probe just produced.
        val verified = rewrite(
            params("ha_url_handover" to "http://ha.local:8123"),
            outcome = HaUrlHandover.Outcome.VERIFIED,
        )
        assertEquals("", verified.parameters["ha_url_handover_reason"])

        val failed = rewrite(
            params("ha_url_handover" to "http://ha.local:8123"),
            outcome = HaUrlHandover.Outcome.TLS,
        )
        assertEquals("tls", failed.parameters["ha_url_handover_reason"])

        val configured = rewrite(
            params("ha_url_handover" to "http://ha.local:8123"),
            currentHaUrl = "http://already.local:8123",
        )
        assertNull(
            "a panel that was not probed has no verdict to record",
            configured.parameters["ha_url_handover_reason"],
        )
    }

    @Test fun `the reason is refused from the network so nobody can declare their own address reachable`() {
        val refused = normalizeConfigPostParameters(
            Parameters.build { append("ha_url_handover_reason", "") },
        )
        assertTrue(refused is ConfigPostParameters.Bad)
        assertEquals(
            "ha_url_handover_reason: the panel's own verification writes this",
            (refused as ConfigPostParameters.Bad).reason,
        )
    }

    @Test fun `the setup state advertises handover support, which is the whole version gate`() {
        // An integration decides from this field alone whether it may send the handover at all. If the
        // panel stopped advertising it, every current panel would silently fall back to asking — and if
        // an older panel ever appeared to advertise it, the integration would 400 its whole request.
        val server = listOf(
            java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            java.io.File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        val builder = server.substring(
            server.indexOf("private fun setupJourneyJson()"),
            server.indexOf("private fun renderConfigConcurrencyHash()"),
        )
        assertTrue(
            "the setup state must advertise that this panel accepts a handover",
            builder.contains("\"{\\\"supported\\\":true,\""),
        )
        listOf("\\\"source\\\":", "\\\"url\\\":", "\\\"reason\\\":").forEach { field ->
            assertTrue("the handover object must carry $field", builder.contains(field))
        }
    }

    @Test fun `the handover keys are a machine channel, not settings`() {
        // No SettingsRegistry spec, deliberately: a spec would put them on the Configure page, in the
        // settings catalogue that every release locale must translate, and in exported config bundles.
        // `http_allowed_hosts` is the same shape for the same reason.
        listOf("ha_setup_handover", "ha_url_handover", "ha_url_handover_reason").forEach { key ->
            assertNull("$key must not be a user-facing setting", SettingsRegistry.spec(key))
        }
    }

    /* ---------------- the handed-over value is validated like any other URL ---------------- */

    @Test fun `the handed-over address is validated exactly as the URL it may become`() {
        listOf("http://ha.local:8123", "https://ha.example.com", "").forEach { value ->
            assertTrue(
                "$value must be accepted",
                normalizeConfigPostParameters(
                    Parameters.build { append("ha_url_handover", value) },
                ) is ConfigPostParameters.Ok,
            )
        }
        listOf("not-a-url", "ftp://ha.local", "http://user:pass@ha.local:8123").forEach { value ->
            assertTrue(
                "$value must be refused",
                normalizeConfigPostParameters(
                    Parameters.build { append("ha_url_handover", value) },
                ) is ConfigPostParameters.Bad,
            )
        }
    }

    @Test fun `the provenance marker is accepted only as a boolean`() {
        listOf("true", "false", "1", "0").forEach { value ->
            assertTrue(
                "$value must be accepted",
                normalizeConfigPostParameters(
                    Parameters.build { append("ha_setup_handover", value) },
                ) is ConfigPostParameters.Ok,
            )
        }
        assertTrue(
            normalizeConfigPostParameters(
                Parameters.build { append("ha_setup_handover", "perhaps") },
            ) is ConfigPostParameters.Bad,
        )
    }

    /* ---------------- the regression a verified handover would otherwise cause ---------------- */

    @Test fun `a handed-over URL is not evidence of an install predating setup tracking`() {
        // The load-bearing case. On a freshly Home-Assistant-installed panel `setupIdentityConfirmed` is
        // false, so if a handed-over `ha_url` counted here the panel would look pre-existing the instant
        // verification promoted it: identity inferred confirmed, and `preTracking` force-satisfying the
        // dashboard and entity-filter questions. The journey would report complete without ever asking
        // for the panel's name.
        assertFalse(
            panelConfiguredBeforeSetupTracking(
                haUrl = "http://ha.local:8123",
                haSetupHandover = true,
                otherEvidence = false,
            ),
        )
    }

    @Test fun `a URL a person typed is still evidence of an install predating setup tracking`() {
        // The inference exists for the upgrade case and must keep working: without the marker, a stored
        // URL is exactly what an older install looks like.
        assertTrue(
            panelConfiguredBeforeSetupTracking(
                haUrl = "http://ha.local:8123",
                haSetupHandover = false,
                otherEvidence = false,
            ),
        )
    }

    @Test fun `the marker excuses only the URL, never the other pre-tracking evidence`() {
        // A panel that Home Assistant deployed AND that carries a broker or a foreign renderer really is
        // a pre-existing install, so the marker must not wave those through.
        assertTrue(
            "a stored broker still marks a pre-tracking install",
            panelConfiguredBeforeSetupTracking(
                haUrl = "http://ha.local:8123",
                haSetupHandover = true,
                otherEvidence = "192.168.1.5".isNotBlank(),
            ),
        )
        assertTrue(
            "a stored renderer still marks a pre-tracking install",
            panelConfiguredBeforeSetupTracking(
                haUrl = "http://ha.local:8123",
                haSetupHandover = true,
                otherEvidence = "io.homeassistant.companion.android".isNotBlank(),
            ),
        )
    }

    @Test fun `a blank panel is not a pre-tracking install either way`() {
        listOf(true, false).forEach { handover ->
            assertFalse(
                panelConfiguredBeforeSetupTracking(
                    haUrl = "",
                    haSetupHandover = handover,
                    otherEvidence = false,
                ),
            )
        }
    }
}
