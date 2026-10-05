package io.panelassistant.android.assets

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
        assertTrue(peers.getString("summary").contains("persistent roster"))
        assertFalse(peers.has("parameters"))
    }

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        // Source-text reason: the shipped openapi.json is the public API contract, parsed as data.
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first { it.isFile }
    }
}
