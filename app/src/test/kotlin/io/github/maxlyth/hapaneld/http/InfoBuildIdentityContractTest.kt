package io.github.maxlyth.hapaneld.http

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/v1/info` names the running build, so a fleet sweep can attribute a panel to a versionCode and
 * package without scraping the `/diag` header. The payload is rendered by the Android-backed server
 * graph, so the emitter is pinned by source and the published schema by the OpenAPI document.
 */
class InfoBuildIdentityContractTest {
    private val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val openApi = JSONObject(File("src/main/assets/openapi.json").readText())

    @Test fun `info payload carries the build versionCode and package`() {
        val info = source.substringAfter("private fun infoJson(strings: AppStrings): String")
            .substringBefore("private fun infoHtml(")

        assertTrue(info, info.contains("\"versionCode\":\${BuildConfig.VERSION_CODE}"))
        assertTrue(info, info.contains("\"package\":\${jsonStr(BuildConfig.APPLICATION_ID)}"))
    }

    @Test fun `openapi documents both build fields on the info response`() {
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
