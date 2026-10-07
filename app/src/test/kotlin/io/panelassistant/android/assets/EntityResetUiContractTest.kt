package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityResetUiContractTest {
    @Test fun `openapi reset schema contract keeps clear filter optional and off`() {
        // Source-text reason: openapi.json is the published API schema contract.
        val openApi = TestSources.appFile("src/main/assets/openapi.json").readText()
        val schema = JSONObject(openApi).getJSONObject("paths")
            .getJSONObject("/api/v1/dashboard/entities/reset")
            .getJSONObject("post")
            .getJSONObject("requestBody")
            .getJSONObject("content")
            .getJSONObject("application/json")
            .getJSONObject("schema")

        assertEquals(listOf("confirm"), schema.getJSONArray("required").let { array ->
            List(array.length()) { array.getString(it) }
        })
        assertFalse(schema.getJSONObject("properties").getJSONObject("clear_filter").getBoolean("default"))
    }

    @Test fun `deferred mutation request excludes every competing action`() {
        val fixture = TestSources.appFile("src/test/js/entity-mutation-gate-test.mjs")
        // Source-text reason: executes the shipped entities.js in a node behaviour fixture.
        val asset = TestSources.appFile("src/main/assets/entities.js")
        val (code, output) = Node.run(fixture.absolutePath, asset.absolutePath)

        assertEquals(output, 0, code)
        assertTrue(output, output.contains("entity mutation gate deferred-fetch cases passed"))
    }
}
