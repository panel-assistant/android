package io.github.maxlyth.hapaneld.assets

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveGetAdmissionContractTest {
    private val openApi by lazy {
        // Source-text reason: the shipped openapi.json is the public API contract, parsed as data.
        val file = listOf(
            File("src/main/assets/openapi.json"),
            File("app/src/main/assets/openapi.json"),
        ).first { it.isFile }
        JSONObject(file.readText()).getJSONObject("paths")
    }

    @Test fun performanceOpenApiContractDescribesReducedProjectionAndConditionalAdmission() {
        val perf = openApi.getJSONObject("/api/v1/perf").getJSONObject("get")
        val perfDescription = perf.getJSONObject("responses")
            .getJSONObject("200")
            .getString("description")
        assertTrue(perfDescription.contains("feature costs are fetched separately"))
        assertFalse(perfDescription.contains("featureCosts field"))
        assertTrue(perf.getJSONObject("responses").has("403"))

        val history = openApi.getJSONObject("/api/v1/perf/history").getJSONObject("get")
        val hours = history.getJSONArray("parameters").getJSONObject(0)
        assertEquals("hours", hours.getString("name"))
        assertEquals("query", hours.getString("in"))
        val schema = hours.getJSONObject("schema")
        assertEquals(1, schema.getInt("minimum"))
        assertEquals(168, schema.getInt("maximum"))
        assertEquals(24, schema.getInt("default"))
        assertTrue(history.getString("description").contains("same-origin"))
        assertTrue(
            history.getJSONObject("responses").getJSONObject("403").getString("description")
                .contains("headerless LAN automation remains supported"),
        )
    }
}
