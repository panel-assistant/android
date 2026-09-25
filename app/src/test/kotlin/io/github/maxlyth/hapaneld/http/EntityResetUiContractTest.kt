package io.github.maxlyth.hapaneld.http

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityResetUiContractTest {
    @Test fun `openapi reset schema contract keeps clear filter optional and off`() {
        // Source-text reason: openapi.json is the published API schema contract.
        val openApi = listOf(
            File("src/main/assets/openapi.json"),
            File("app/src/main/assets/openapi.json"),
        ).first(File::isFile).readText()
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
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-mutation-gate-test.mjs"),
            File(working, "src/test/js/entity-mutation-gate-test.mjs"),
        ).first(File::isFile)
        // Source-text reason: executes the shipped entities.js in a node behaviour fixture.
        val asset = listOf(
            File(working, "app/src/main/assets/entities.js"),
            File(working, "src/main/assets/entities.js"),
        ).first(File::isFile)
        val process = ProcessBuilder("node", fixture.absolutePath, asset.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("entity mutation gate deferred-fetch cases passed"))
    }
}
