package io.panelassistant.android.util

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException

/**
 * The classifier's job is to be useful without being publishable-unsafe, so both halves are asserted:
 * the verdict is right, AND nothing an exception carried in its message reaches the token.
 */
class HaTransportFaultTest {

    @Test fun aWrappedCertificatePathFailureIsTrustNotGenericTls() {
        // The common shape of a broken chain: the platform reports the handshake failure, and only
        // the cause names the anchor.
        // Matching only the outer type would classify the incident as a generic TLS fault and lose
        // the one word that pointed at the server certificate.
        val error = SSLHandshakeException("handshake failed").initCause(
            CertPathValidatorException("Trust anchor for certification path not found"),
        )
        assertEquals(HaTransportFault.TLS_TRUST, HaTransportFault.classify(error))
        assertEquals("CertPathValidatorException", HaTransportFault.token(error))
    }

    @Test fun aBareHandshakeFailureIsTlsButNotTrust() {
        val error = SSLHandshakeException("no cipher suites in common")
        assertEquals(HaTransportFault.TLS_OTHER, HaTransportFault.classify(error))
        assertEquals("SSLHandshakeException", HaTransportFault.token(error))
    }

    @Test fun anExpiredCertificateIsAlsoATrustFailure() {
        assertEquals(
            HaTransportFault.TLS_TRUST,
            HaTransportFault.classify(CertificateExpiredException("NotAfter: expired")),
        )
    }

    @Test fun eachTransportShapeGetsItsOwnVerdict() {
        assertEquals(HaTransportFault.DNS, HaTransportFault.classify(UnknownHostException("hass.example.net")))
        assertEquals(HaTransportFault.TIMEOUT, HaTransportFault.classify(SocketTimeoutException("connect timed out")))
        assertEquals(HaTransportFault.REFUSED, HaTransportFault.classify(ConnectException("Connection refused")))
        assertEquals(HaTransportFault.UNREACHABLE, HaTransportFault.classify(NoRouteToHostException("no route")))
        assertEquals(HaTransportFault.PROTOCOL, HaTransportFault.classify(JSONException("not json")))
    }

    @Test fun anUnrecognisedFailureIsUnknownRatherThanGuessed() {
        assertEquals(HaTransportFault.UNKNOWN, HaTransportFault.classify(IllegalStateException("boom")))
        assertEquals("IllegalStateException", HaTransportFault.token(IllegalStateException("boom")))
    }

    @Test fun nothingToClassifyIsUnknownWithNoToken() {
        assertEquals(HaTransportFault.UNKNOWN, HaTransportFault.classify(null))
        assertNull(HaTransportFault.token(null))
    }

    @Test fun theTokenNeverCarriesWhatTheMessageCarried() {
        // `UnknownHostException.message` IS the configured hostname and `ConnectException` carries
        // ip:port. A diagnostic meant for a public issue must not forward either, so the token is
        // built from the class alone — this is the assertion that fails if that ever changes.
        val host = "home-assistant.example.invalid"
        val evidence = HaTransportFault.evidenceOf(UnknownHostException(host))
        assertEquals(HaTransportFault.DNS, evidence.fault)
        assertEquals("UnknownHostException", evidence.token)
        assertFalse(evidence.token!!.contains("home"))
        assertFalse(evidence.token.contains("."))

        val refused = HaTransportFault.evidenceOf(ConnectException("failed to connect to /192.168.1.4 (port 8123)"))
        assertEquals("ConnectException", refused.token)
        assertFalse(refused.token!!.any { it.isDigit() })
    }

    @Test fun anHttpStatusBecomesItsOwnEvidence() {
        val evidence = HaTransportFault.evidenceOfHttpStatus(503)
        assertEquals(HaTransportFault.HTTP_STATUS, evidence.fault)
        assertEquals("http_503", evidence.token)
    }

    @Test fun sanitizeStripsEverythingButNameCharactersAndBoundsLength() {
        assertEquals("abc_DEF9", HaTransportFault.sanitize("a b/c._DEF-9"))
        assertNull(HaTransportFault.sanitize("   "))
        assertNull(HaTransportFault.sanitize(null))
        assertEquals(
            HaTransportFault.MAX_TOKEN_CHARS,
            HaTransportFault.sanitize("X".repeat(200))!!.length,
        )
    }

    @Test fun aSelfReferentialCauseChainTerminates() {
        // A cyclic chain is rare but real (a retry wrapper re-throwing its own cause). Diagnostics
        // reads happen on an HTTP thread, so a walk that never ends is an unavailable panel.
        val outer = IllegalStateException("outer")
        val inner = IllegalStateException("inner")
        outer.initCause(inner)
        inner.initCause(outer)
        assertEquals(HaTransportFault.UNKNOWN, HaTransportFault.classify(outer))
        assertTrue(HaTransportFault.token(outer)!!.isNotBlank())
    }

    @Test fun theVerdictAndTheTokenNameTheSameLinkInTheChain() {
        // A wrapper the vocabulary does not recognise must not lend its name to a verdict drawn from
        // a deeper link: reading `dns` beside `RuntimeException` would send diagnosis nowhere.
        val error = RuntimeException("wrapped").initCause(UnknownHostException("hass.example.net"))
        assertEquals(HaTransportFault.DNS, HaTransportFault.classify(error))
        assertEquals("UnknownHostException", HaTransportFault.token(error))
    }

    @Test fun aFailureThatReachedUsUnclassifiedDegradesToUnknownNotToHealth() {
        // Found by the probe's own suite: a session carrying a transient detail with no classified
        // evidence was publishing `none`, which reads as "no transport failure occurred" — the exact
        // collapse this vocabulary exists to prevent. A known failure can never answer `none`.
        assertEquals(HaTransportEvidence.UNKNOWN, HaTransportEvidence.NONE.orUnclassified())
        assertEquals(HaTransportEvidence.UNKNOWN, HaTransportEvidence.UNKNOWN.orUnclassified())
        // A real classification passes through untouched, token and all.
        val trust = HaTransportEvidence(HaTransportFault.TLS_TRUST, "CertPathValidatorException")
        assertEquals(trust, trust.orUnclassified())
    }

    @Test fun noneAndUnknownAreDistinctEvidence() {
        // "there was no transport failure" and "there was one we did not classify" must never
        // collapse: a surface that treats the second as the first starts reporting health.
        assertEquals(HaTransportFault.NONE, HaTransportEvidence.NONE.fault)
        assertEquals(HaTransportFault.UNKNOWN, HaTransportEvidence.UNKNOWN.fault)
        assertFalse(HaTransportEvidence.NONE == HaTransportEvidence.UNKNOWN)
    }
}
