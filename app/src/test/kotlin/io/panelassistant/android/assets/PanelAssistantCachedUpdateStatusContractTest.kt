package io.panelassistant.android.assets

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Binds the privacy-safe cached update projection to its sole status surface and public grammar. */
class PanelAssistantCachedUpdateStatusContractTest {
    // Source-text reason: the shipped OpenAPI document is the public API contract.
    private val openApi by lazy { JSONObject(File("src/main/assets/openapi.json").readText()) }

    @Test fun openApiKeepsTheProjectionAdditiveAndBoundsBothShapes() {
        val statusSchema = openApi.getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get")
            .getJSONObject("responses").getJSONObject("200").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertFalse(statusSchema.getJSONArray("required").toString().contains("panel_assistant_update"))
        assertEquals(
            "#/components/schemas/PanelAssistantUpdate",
            statusSchema.getJSONObject("properties").getJSONObject("panel_assistant_update").getString("\$ref"),
        )

        val schema = openApi.getJSONObject("components").getJSONObject("schemas").getJSONObject("PanelAssistantUpdate")
        val alternatives = schema.getJSONArray("oneOf")
        assertEquals(2, alternatives.length())
        val none = alternatives.getJSONObject(0)
        val available = alternatives.getJSONObject(1)
        assertFalse(none.getBoolean("additionalProperties"))
        assertFalse(available.getBoolean("additionalProperties"))
        assertEquals("none", none.getJSONObject("properties").getJSONObject("state").getJSONArray("enum").getString(0))
        assertEquals(
            setOf("state", "current_version", "target_version", "tag"),
            available.getJSONArray("required").let { values ->
                (0 until values.length()).map(values::getString).toSet()
            },
        )
        assertEquals(64, available.getJSONObject("properties").getJSONObject("current_version").getInt("maxLength"))
        assertEquals(64, available.getJSONObject("properties").getJSONObject("target_version").getInt("maxLength"))
        assertEquals(64, available.getJSONObject("properties").getJSONObject("tag").getInt("maxLength"))
    }
}
