package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiRouteSpecContractTest {
    @Test fun peersOpenApiContractDescribesThePersistentRosterWithoutAnIgnoredRefreshParameter() {
        val peers = JSONObject(asset("openapi.json").readText())
            .getJSONObject("paths")
            .getJSONObject("/api/v1/peers")
            .getJSONObject("get")
        assertFalse(peers.has("parameters"))
    }

    private fun asset(name: String): File {
        // Source-text reason: the shipped openapi.json is the public API contract, parsed as data.
        return TestSources.appFile("src/main/assets/$name")
    }
}
