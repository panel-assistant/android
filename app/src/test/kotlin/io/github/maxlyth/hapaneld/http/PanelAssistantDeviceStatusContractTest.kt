package io.github.maxlyth.hapaneld.http

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binds the device projection to its sole status surface and its public grammar. */
class PanelAssistantDeviceStatusContractTest {
    // Source-text reason: the shipped OpenAPI document is the public API contract.
    private val openApi by lazy { JSONObject(File("src/main/assets/openapi.json").readText()) }

    @Test fun healthCarriesTheVersionCodeToken() {
        assertEquals(" vc=909", versionCodeHealthToken(909))
    }

    @Test fun openApiKeepsTheProjectionAdditiveAndStrictlyBounded() {
        val statusSchema = openApi.getJSONObject("paths").getJSONObject("/api/v1/status")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200")
            .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        val required = statusSchema.getJSONArray("required")
        for (index in 0 until required.length()) {
            assertFalse(required.getString(index) == "panel_assistant_device")
        }
        assertTrue(
            statusSchema.getJSONObject("properties").has("panel_assistant_device"),
        )

        val device = openApi.getJSONObject("components").getJSONObject("schemas")
            .getJSONObject("PanelAssistantDevice")
        assertEquals("object", device.getString("type"))
        assertFalse(device.getBoolean("additionalProperties"))
        assertFalse(device.has("required"))
        val properties = device.getJSONObject("properties")
        assertFalse(properties.has("hw_version"))
        for (name in listOf("name", "manufacturer", "model", "area")) {
            val field = properties.getJSONObject(name)
            assertEquals(name, "string", field.getString("type"))
            assertEquals(name, 1, field.getInt("minLength"))
            assertEquals(name, 128, field.getInt("maxLength"))
        }
        assertEquals(4, properties.length())
    }
}
