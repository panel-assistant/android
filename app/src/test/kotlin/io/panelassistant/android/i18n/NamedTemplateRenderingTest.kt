package io.panelassistant.android.i18n

import io.panelassistant.android.http.formattedString
import io.panelassistant.android.http.localizedRuntimeValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NamedTemplateRenderingTest {
    @Test fun `catalogue and HTTP callers preserve the shared literal value contract`() {
        val corpus = JSONArray(checkNotNull(javaClass.getResource("/i18n/named-template-cases.json")).readText())
        for (index in 0 until corpus.length()) {
            val case = corpus.getJSONObject(index)
            val values = case.getJSONArray("values").let { entries ->
                Array(entries.length()) { entry ->
                    entries.getJSONArray(entry).let { it.getString(0) to it.getString(1) }
                }
            }
            val strings = strings("dashboard.test.template" to case.getString("template"))
            val expected = case.getString("expected")
            assertEquals(case.getString("name"), expected, CatalogueText("dashboard.test.template", *values).render(strings))
            assertEquals(case.getString("name"), expected, formattedString(strings, "dashboard.test.template", *values))
        }
    }

    @Test fun `nested catalogue text renders its own values before becoming a literal outer value`() {
        val strings = strings(
            "dashboard.test.outer" to "{detail}; count: {count}; tail: {tail}",
            "dashboard.test.inner" to "Command {command}",
        )
        val text = CatalogueText(
            "dashboard.test.outer",
            "detail" to CatalogueText("dashboard.test.inner", "command" to "{tail} $& \\"),
            "count" to 3,
            "tail" to "done",
        )

        assertEquals("Command {tail} $& \\; count: 3; tail: done", text.render(strings))
    }

    @Test fun `camera runtime preserves a fault containing the stream placeholder`() {
        // Source-text reason: the shipped catalogue is runtime input data, not a source assertion.
        val strings = CatalogueLoader { File("src/main/assets", it).readText() }.strings("en")

        assertEquals(
            "camera gave up after 3 failures ({stream}); stream not listening",
            localizedRuntimeValue("Camera", "camera gave up after 3 failures ({stream}); stream not listening", strings),
        )
    }

    private fun strings(vararg texts: Pair<String, String>): Strings {
        val records = JSONObject()
        texts.forEach { (key, text) ->
            records.put(key, JSONObject()
                .put("text", text)
                .put("sourceHash", sourceHash(text))
                .put("surface", "dashboard")
                .put("context", "Named template caller fixture")
                .put("risk", "ordinary")
                .put("siblings", JSONArray())
                .put("placeholders", JSONArray(extractPlaceholders(text)))
                .put("frozen", JSONArray())
                .put("softMaxChars", 1000)
                .put("hardMaxChars", 2000))
        }
        return Strings(SourceCatalogue.parse(JSONObject()
            .put("schema", 1)
            .put("locale", "en")
            .put("sourceRevision", "0".repeat(40))
            .put("strings", records)
            .toString()))
    }
}
