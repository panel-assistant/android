package io.github.maxlyth.hapaneld.http

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/api/v1/info` names the running build, so a fleet sweep can attribute a panel to a versionCode and package. */
class InfoBuildIdentityContractTest {
    // Source-text reason: openapi.json is the published API schema contract.
    private val openApi = JSONObject(File("src/main/assets/openapi.json").readText())

    @Test fun `openapi info response schema contract documents both build fields`() {
        val schema = openApi.getJSONObject("paths").getJSONObject("/api/v1/info").getJSONObject("get")
            .getJSONObject("responses").getJSONObject("200").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        val properties = schema.getJSONObject("properties")

        assertEquals("integer", properties.getJSONObject("versionCode").getString("type"))
        assertEquals("string", properties.getJSONObject("package").getString("type"))
        val required = schema.getJSONArray("required").let { a -> (0 until a.length()).map(a::getString) }
        assertTrue(required.containsAll(listOf("versionCode", "package")))
    }
}
