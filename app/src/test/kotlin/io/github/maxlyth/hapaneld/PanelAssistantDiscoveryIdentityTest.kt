package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.http.panelAssistantDiscoveryHealthToken
import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantDiscoveryIdentityTest {
    private val androidId = "0123456789abcdef"

    @Test fun tokenIsStableLowercaseSha256AndDomainSeparated() {
        val token = requireNotNull(panelAssistantDiscoveryId(androidId))

        assertEquals(64, token.length)
        assertEquals("9b1b5cbac97251414303d6d6ba268252b8e3458a3613b484dd43eee4460ea23a", token)
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertEquals(token, panelAssistantDiscoveryId("  $androidId  "))
        assertFalse(token == panelAssistantDiscoveryId("fedcba9876543210"))
        assertFalse(token == androidId)
    }

    @Test fun missingInstallationIdentityProducesNoLanIdentity() {
        assertNull(panelAssistantDiscoveryId(""))
        assertNull(panelAssistantDiscoveryId(" \t "))
        assertEquals("", panelAssistantDiscoveryHealthToken(""))
    }

    @Test fun healthTokenContainsOnlyThePseudonym() {
        val token = requireNotNull(panelAssistantDiscoveryId(androidId))
        val healthToken = panelAssistantDiscoveryHealthToken(androidId)

        assertEquals(" did=$token identity=install", healthToken)
        assertFalse(healthToken.contains(androidId))
    }

    @Test fun healthOpenApiContractDocumentsTheIdentityGrammarAndOmissionRule() {
        // Source-text reason: the shipped OpenAPI document is the public API contract.
        val description = JSONObject(TestSources.asset("openapi.json").readText())
            .getJSONObject("paths")
            .getJSONObject("/api/v1/health")
            .getJSONObject("get")
            .getJSONObject("responses")
            .getJSONObject("200")
            .getString("description")

        assertTrue(description.contains("did=<64 lower-case hexadecimal characters>"))
        assertTrue(description.contains("omitted when that identity is unavailable"))
    }
}
